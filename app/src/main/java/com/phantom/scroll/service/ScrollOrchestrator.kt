package com.phantom.scroll.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import com.phantom.scroll.data.ScrollSettings
import com.phantom.scroll.data.SettingsRepository
import com.phantom.scroll.gesture.ContinuousPlan
import com.phantom.scroll.gesture.GestureEngine
import com.phantom.scroll.gesture.GesturePlan
import com.phantom.scroll.util.PhantomLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.coroutines.resume

/**
 * Orchestrator that manages the automated scrolling loop.
 * Reads effective settings from [SettingsRepository] and delegates consecutive-failure
 * accounting to a [FailurePolicy].
 *
 * Gesture dispatch uses a **single continuous stroke** whose internal point spacing
 * encodes a human-like accelerate-then-gently-decelerate profile (see
 * [com.phantom.scroll.gesture.GestureEngine.generateContinuousPlan]). Encoding the
 * speed curve *inside one path* — rather than chaining two `continueStroke` segments —
 * eliminates both the seam micro-pause and the slow-drag tail that caused ad/诱导
 * buttons to be misread as taps. Some exotic ROMs mishandle even a single complex
 * path; we track cancellation patterns and fall back to the legacy two-segment plan,
 * then to a constant-speed single path, without losing the bezier jitter or the
 * randomized start point. See [degradedMode].
 */
class ScrollOrchestrator(
    private val service: AccessibilityService,
    private val repository: SettingsRepository,
    private val scope: CoroutineScope,
    /**
     * Off-main dispatcher for path/noise computation (spec §3). Defaults to
     * [Dispatchers.Default]; injectable so tests can drive timing deterministically.
     */
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * Dispatcher used for [AccessibilityService.dispatchGesture] (must be the main thread).
     * Defaults to [Dispatchers.Main.immediate]; injectable for tests.
     */
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    /**
     * Whether the screen is currently interactive (on). Polled before every dispatch:
     * scrolling can be (re)started from the notification while the screen is ALREADY off,
     * in which case no ACTION_SCREEN_OFF broadcast will ever arrive to pause the loop —
     * this gate is what actually honors the "熄屏自动暂停" spec in that gap. Injectable
     * for tests.
     */
    private val isScreenInteractive: () -> Boolean = {
        (service.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    }
) {
    private val TAG = "ScrollOrchestrator"
    private val gestureEngine = GestureEngine()
    /**
     * Converts the pure-JVM plans from [gestureEngine] into dispatchable
     * [android.accessibilityservice.GestureDescription]s. Owns the pooled
     * `android.graphics.Path` objects — the only Android dependency in the swipe
     * pipeline (the algorithm itself lives in the `:gesture` module).
     */
    private val strokeFactory = GestureDescriptionFactory()
    private val failurePolicy = FailurePolicy(threshold = 3)
    private var loopJob: Job? = null

    // ---- User-touch conflict state (see onUserTouch) ----------------------------
    // Set from PhantomScrollService's TouchInteractionController observer (API 31+):
    // while the user's own finger is on the screen we hold off injecting, and
    // cancellations inside the grace window are attributed to the user instead of the ROM.
    @Volatile
    private var userTouching = false

    @Volatile
    private var lastUserTouchAtMs = 0L

    /**
     * `true` once the preferred single continuous stroke has been observed to fail
     * repeatedly on this device. While set, [buildAndDispatch] emits the legacy
     * two-segment plan instead. Deliberately *not* auto-cleared on success to avoid
     * flapping; a service restart restores the fast path.
     */
    @Volatile
    private var degradedMode = false

    /**
     * Consecutive cancellation count while still on the preferred single-stroke path.
     * Crossing [DEGRADATION_THRESHOLD] flips [degradedMode]. Reset on any completion
     * or once degradation engages.
     */
    private var consecutiveCancellation = 0

    /**
     * `true` while a dispatched gesture has not yet resolved (completed/cancelled/rejected).
     * Guards against [scrolling-loop] overlap: if a previous [withTimeoutOrNull] timed out,
     * the underlying system gesture keeps running and its callback can still arrive late —
     * this flag lets the loop wait for it before dispatching again (preventing two fingers
     * on screen at once) and lets late callbacks self-cancel without touching degradation
     * counters (see [handleCancellation]).
     */
    @Volatile
    private var gestureInFlight = false

    /**
     * Consecutive loop iterations skipped because [gestureInFlight] never cleared. Crossing
     * [IN_FLIGHT_FORCE_CLEAR_SKIPS] force-clears the flag (see the overlap guard) — see
     * [stuckInFlightSkips] there for why waiting forever is worse than the alternative.
     */
    private var stuckInFlightSkips = 0

    fun start() {
        loopJob = scope.launch {
            // Rising-edge detection of isRunning: the startup humanization delay fires
            // once per pause→resume transition, not on every loop iteration.
            var wasRunning = false
            while (isActive) {
                try {
                    // While paused, keep the rising-edge flag cleared so the next
                    // resume gets a fresh startup humanization delay. (Checked before
                    // first{} because first{} SUSPENDS while the value is false —
                    // it does not return false.)
                    if (!repository.isRunning.value) {
                        wasRunning = false
                    }
                    val running = repository.isRunning.first { it }
                    if (!running) continue
                    if (!wasRunning) {
                        wasRunning = true
                        // Fresh scrolling session: reset the warm-up ramp so the first
                        // two swipes re-enter gently (the habitual start anchor is kept).
                        gestureEngine.beginSession()
                        // Humanized startup pause: a real reader never begins swiping
                        // the instant the mode is enabled.
                        val startupDelayMs = withContext(defaultDispatcher) {
                            gestureEngine.addBioNoise(
                                STARTUP_DELAY_BASE_MS, STARTUP_DELAY_NOISE_RATIO
                            ).toLong().coerceIn(STARTUP_DELAY_MIN_MS, STARTUP_DELAY_MAX_MS)
                        }
                        PhantomLog.d(TAG, "Startup humanization delay: ${startupDelayMs}ms")
                        delay(startupDelayMs)
                    }

                    // Guard against gesture overlap: if the previous dispatch's callback has not
                    // arrived yet (e.g. it timed out and the system gesture is still animating),
                    // wait briefly rather than firing a second gesture on top of the first.
                    if (gestureInFlight) {
                        PhantomLog.w(TAG, "Previous gesture still in flight; waiting before next dispatch.")
                        var waits = 0
                        while (gestureInFlight && isActive && waits < OVERLAP_WAIT_PROBES) {
                            delay(OVERLAP_WAIT_STEP_MS)
                            waits++
                        }
                        if (gestureInFlight) {
                            stuckInFlightSkips++
                            if (stuckInFlightSkips >= IN_FLIGHT_FORCE_CLEAR_SKIPS) {
                                // Escape hatch: the callback is genuinely lost (some ROMs drop
                                // it entirely). Waiting forever would stall scrolling with no
                                // failure recorded and therefore no auto-pause — a permanent,
                                // invisible hang. The residual risk that a zombie gesture is
                                // still animating is far smaller than that stall, so force-clear
                                // and let the next dispatch proceed.
                                PhantomLog.e(
                                    TAG,
                                    "Gesture callback lost after $stuckInFlightSkips skipped " +
                                        "iterations; force-clearing in-flight flag."
                                )
                                gestureInFlight = false
                                stuckInFlightSkips = 0
                            } else {
                                // Give a genuinely-late callback one more chance on a later tick.
                                PhantomLog.w(TAG, "Gesture never resolved; skipping iteration.")
                                delay(500)
                                continue
                            }
                        }
                    } else {
                        stuckInFlightSkips = 0
                    }

                    // Never inject while the user's own finger is on the screen — competing
                    // touches both look robotic and cancel our gesture (which would wrongly
                    // count as a failure).
                    waitForUserRelease()

                    // Never inject while the screen is off. Besides the broadcast-driven pause
                    // (ScreenStateCoordinator), this covers the gaps broadcasts cannot see:
                    // scrolling started from the notification while the screen was already off,
                    // or a missed SCREEN_OFF delivery.
                    awaitScreenInteractive()

                    val screenWidth = repository.screenWidth.value
                    val screenHeight = repository.screenHeight.value
                    val settings = repository.activeSettings.value

                    val gestureSucceeded = dispatchSwipe(screenWidth, screenHeight, settings)

                    if (!gestureSucceeded) {
                        delay(500)
                        continue
                    }

                    // Noise computation on the off-main dispatcher per spec §3 (gaussian sampling
                    // belongs with the rest of the trajectory math), then clamp to slider bounds.
                    // The inter-swipe wait stays anchored to the user's configured interval:
                    // this ±8% (2σ-clamped) bio-noise band is the ONLY permitted deviation.
                    // Deliberately no rest-pause stretch and no content-gated extension —
                    // both previously produced perceptible multi-second stalls.
                    val noiseInterval = withContext(defaultDispatcher) {
                        gestureEngine.addBioNoise(settings.interval.toFloat(), 0.08f)
                            .toLong().coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
                    }
                    delay(noiseInterval)

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PhantomLog.e(TAG, "Error in scrolling loop: ${e.message}", e)
                    delay(1000)
                }
            }
        }
    }

    /**
     * Computes (off-main) and dispatches (on-main) a single swipe, returning `true`
     * only on a completed gesture. Handles the single-stroke → legacy two-segment
     * transition transparently.
     */
    private suspend fun dispatchSwipe(
        screenWidth: Int,
        screenHeight: Int,
        settings: ScrollSettings
    ): Boolean {
        // 1. Generate the plan off the main thread (pure-JVM math in :gesture).
        //    Preferred: a single continuous stroke encoding the speed curve via
        //    non-uniform point spacing — no seam, no slow-drag tail, best ROM support.
        //    Fallback (degraded): the legacy two-segment continueStroke plan.
        //    Path building (the only Android touchpoint) lives in [strokeFactory] with
        //    pooled Path objects — zero allocation per swipe.
        val totalDurationHint: Long
        val buildGesture: () -> GestureDescription
        if (degradedMode) {
            val plan: GesturePlan = withContext(defaultDispatcher) {
                gestureEngine.generateGesturePlan(
                    screenWidth, screenHeight,
                    settings.distanceRatio, settings.duration, settings.direction
                )
            }
            totalDurationHint = plan.accelDuration + plan.decelDuration
            buildGesture = { strokeFactory.build(plan) }
        } else {
            val plan: ContinuousPlan = withContext(defaultDispatcher) {
                gestureEngine.generateContinuousPlan(
                    screenWidth, screenHeight,
                    settings.distanceRatio, settings.duration, settings.direction
                )
            }
            totalDurationHint = plan.duration
            buildGesture = { strokeFactory.build(plan) }
        }

        // 2. Dispatch on the main thread (dispatchGesture requires it).
        return withContext(mainDispatcher) {
            if (!isActive || !repository.isRunning.value) return@withContext false
            // Last-moment screen check: the screen may have turned off while the path was
            // being generated off-main. Skipping is not a failure: the loop retries.
            if (!isScreenInteractive()) {
                PhantomLog.d(TAG, "Skipping dispatch — screen not interactive.")
                return@withContext false
            }
            // Last-moment touch check (the user may have started touching while the
            // path was being generated off-main). Skipping is not a failure: the loop
            // retries after a short pause.
            if (userTouching ||
                (lastUserTouchAtMs > 0L &&
                    SystemClock.elapsedRealtime() - lastUserTouchAtMs < USER_CANCEL_GRACE_MS)
            ) {
                PhantomLog.d(TAG, "Skipping dispatch — user touch in progress or too recent.")
                return@withContext false
            }
            val timeoutMs = totalDurationHint + GESTURE_TIMEOUT_SLACK_MS
            gestureInFlight = true
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val gesture = buildGesture()
                    val dispatched = service.dispatchGesture(
                        gesture,
                        object : AccessibilityService.GestureResultCallback() {
                            override fun onCompleted(g: GestureDescription?) {
                                gestureInFlight = false
                                failurePolicy.recordSuccess()
                                consecutiveCancellation = 0
                                if (cont.isActive) cont.resume(true)
                            }

                            override fun onCancelled(g: GestureDescription?) {
                                handleCancellation(cont)
                            }
                        },
                        null
                    )

                    if (!dispatched) {
                        // dispatchGesture rejected synchronously — treat as ordinary failure.
                        gestureInFlight = false
                        if (cont.isActive) cont.resume(false)
                        handleFailure()
                    }
                }
            } ?: run {
                // Timed out with no callback at all. The system gesture may still be animating,
                // so leave gestureInFlight=true: the loop's overlap guard will wait for the late
                // callback (which clears the flag) before dispatching again.
                PhantomLog.w(TAG, "Gesture dispatch timed out; leaving in-flight flag set.")
                handleFailure()
                false
            }
        }
    }

    /**
     * Routes a cancellation through the degradation ladder. While on the fast
     * (two-phase) path, the first [DEGRADATION_THRESHOLD]-1 cancellations are tolerated
     * as ordinary failures; crossing the threshold switches [degradedMode] on. Once in
     * degraded mode every cancellation is an ordinary failure (no more special-casing).
     */
    private fun handleCancellation(cont: CancellableContinuation<Boolean>) {
        // Always clear the in-flight flag: a cancellation is a terminal resolution.
        gestureInFlight = false
        // User-touch attribution: a cancellation that lands while (or just after) the
        // user is touching the screen is the system arbitrating between two fingers —
        // NOT a ROM incompatibility. Don't touch the degradation ladder or the
        // FailurePolicy for it.
        if (userTouching ||
            (lastUserTouchAtMs > 0L &&
                SystemClock.elapsedRealtime() - lastUserTouchAtMs < USER_CANCEL_GRACE_MS)
        ) {
            PhantomLog.d(TAG, "Cancellation attributed to user touch — ignored (not a failure).")
            if (cont.isActive) cont.resume(false)
            return
        }
        // Late callback guard: if the continuation is no longer active, the loop already moved
        // on (typically via a timeout). Counting such a residual cancellation would wrongly
        // bump the degradation ladder, so just log and return.
        if (!cont.isActive) {
            PhantomLog.w(TAG, "Late gesture cancellation ignored (continuation already resolved).")
            return
        }
        if (degradedMode) {
            if (cont.isActive) cont.resume(false)
            handleFailure()
            return
        }
        consecutiveCancellation++
        if (consecutiveCancellation >= DEGRADATION_THRESHOLD) {
            degradedMode = true
            consecutiveCancellation = 0
            PhantomLog.w(TAG, "Single-stroke cancellations hit threshold → legacy two-segment mode.")
            Toast.makeText(service, "⚠️ 检测到设备兼容性问题，已切换兼容滑动模式", Toast.LENGTH_SHORT).show()
            // Give the degraded path a fair chance on the next iteration: resume without
            // recording a FailurePolicy miss so we don't double-penalize the transition.
            if (cont.isActive) cont.resume(false)
        } else {
            if (cont.isActive) cont.resume(false)
            handleFailure()
        }
    }

    /**
     * Centralized failure handling: delegates counting/decision to [failurePolicy] and
     * performs the auto-pause + user feedback when the threshold is reached.
     */
    private fun handleFailure() {
        val consecutive = failurePolicy.runsAfterLastSuccess()
        when (failurePolicy.recordFailure(repository.isRunning.value)) {
            FailureDecision.Continue -> {
                if (consecutive > 0) PhantomLog.w(TAG, "Gesture failed. Consecutive: $consecutive")
            }
            FailureDecision.Ignored -> {
                // manual pause / lock screen — not counted
            }
            FailureDecision.AutoPause -> {
                PhantomLog.e(TAG, "Threshold reached → auto-pausing.")
                repository.isRunning.value = false
                Toast.makeText(service, "⚠️ 连续三次滑动失败，已自动暂停", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
    }

    /**
     * Called from PhantomScrollService's TouchInteractionController observer (API 31+).
     * While the user's finger is on the screen the loop holds off injecting; the
     * timestamp also feeds the cancellation grace window in [handleCancellation].
     */
    fun onUserTouch(touching: Boolean) {
        userTouching = touching
        lastUserTouchAtMs = SystemClock.elapsedRealtime()
        PhantomLog.d(
            TAG,
            if (touching) "User touch started — holding injection." else "User touch ended."
        )
    }

    /**
     * Waits while the user's finger is on the screen, then a short settle cooldown,
     * before the loop may dispatch again.
     */
    private suspend fun waitForUserRelease() {
        if (!userTouching) return
        PhantomLog.d(TAG, "User is touching the screen — holding injection.")
        while (userTouching && currentCoroutineContext().isActive) delay(USER_TOUCH_POLL_MS)
        delay(USER_TOUCH_COOLDOWN_MS)
    }

    /**
     * Holds injection while the screen is not interactive. Once the screen wakes, the
     * loop resumes normally (the resume edge itself is driven by ScreenStateCoordinator's
     * USER_PRESENT handling when the pause was broadcast-driven; here we simply stop
     * injecting into a dark screen).
     */
    private suspend fun awaitScreenInteractive() {
        if (isScreenInteractive()) return
        PhantomLog.w(TAG, "Screen not interactive — holding injection until wake.")
        while (!isScreenInteractive() && currentCoroutineContext().isActive) {
            delay(SCREEN_WAIT_POLL_MS)
        }
    }

    private companion object {
        /** Consecutive continuous-stroke cancellations before switching to degraded mode. */
        const val DEGRADATION_THRESHOLD = 2

        /** Extra time allowed beyond the gesture's own duration before declaring timeout. */
        const val GESTURE_TIMEOUT_SLACK_MS = 2000L

        /**
         * When the previous gesture's callback has not arrived (overlap guard), poll this many
         * times for [OVERLAP_WAIT_STEP_MS] before giving up the iteration. 40 × 50ms = 2s,
         * matching the timeout slack — enough for a genuinely-late callback to land.
         */
        const val OVERLAP_WAIT_PROBES = 40
        const val OVERLAP_WAIT_STEP_MS = 50L

        /**
         * Consecutive skipped iterations after which a never-resolving [gestureInFlight] is
         * force-cleared instead of blocking the loop forever. 3 skips ≈ 7.5s worst case —
         * long enough to rule out any legitimately late callback, short enough that the
         * stall stays bounded and recoverable.
         */
        const val IN_FLIGHT_FORCE_CLEAR_SKIPS = 3

        /** Poll cadence while waiting for the screen to become interactive. */
        const val SCREEN_WAIT_POLL_MS = 500L

        /**
         * Inter-swipe interval bounds. Aligned with the overlay slider's
         * `valueFrom/valueTo` (500..10000) so the noisy wait never exceeds what the
         * user configured. (Legacy code used 400..12000, which could overrun the max.)
         */
        const val MIN_INTERVAL_MS = 500L
        const val MAX_INTERVAL_MS = 10000L

        // ---- Startup humanization (first swipe after each resume) -----------------
        const val STARTUP_DELAY_BASE_MS = 650f
        const val STARTUP_DELAY_NOISE_RATIO = 0.35f
        const val STARTUP_DELAY_MIN_MS = 400L
        const val STARTUP_DELAY_MAX_MS = 900L

        // ---- User-touch conflict avoidance ----------------------------------------
        /** Poll cadence while waiting for the user's finger to lift. */
        const val USER_TOUCH_POLL_MS = 60L
        /** Settle pause after the finger lifts before injection resumes. */
        const val USER_TOUCH_COOLDOWN_MS = 350L
        /** Cancellations within this window after a touch are attributed to the user. */
        const val USER_CANCEL_GRACE_MS = 500L
    }
}
