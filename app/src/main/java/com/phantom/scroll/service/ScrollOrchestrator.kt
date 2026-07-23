package com.phantom.scroll.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
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
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) {
    private val TAG = "ScrollOrchestrator"
    private val gestureEngine = GestureEngine()
    private val failurePolicy = FailurePolicy(threshold = 3)
    private var loopJob: Job? = null

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

    fun start() {
        loopJob = scope.launch {
            while (isActive) {
                try {
                    repository.isRunning.first { it }
                    if (!repository.isRunning.value) continue

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
                        // If it genuinely never resolved, abandon this iteration and let the next
                        // loop tick retry rather than stack gestures.
                        if (gestureInFlight) {
                            PhantomLog.w(TAG, "Gesture never resolved; skipping iteration.")
                            delay(500)
                            continue
                        }
                    }

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
                    val noiseInterval = withContext(defaultDispatcher) {
                        gestureEngine.addBioNoise(settings.interval.toFloat(), 0.08f)
                            .toLong().coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
                    }
                    // spec §3.2: count one successful swipe + accumulate the wait as elapsed time.
                    repository.incrementStats(swipeDelta = 1, elapsedDeltaMs = noiseInterval)
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
        // 1. Generate the path off the main thread.
        //    Preferred: a single continuous stroke encoding the speed curve via
        //    non-uniform point spacing — no seam, no slow-drag tail, best ROM support.
        //    Fallback (degraded): the legacy two-segment continueStroke plan.
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
            buildGesture = {
                // Legacy two continuous strokes: the second continues the first, so
                // Android treats them as one finger with a speed change at the seam.
                val accel = GestureDescription.StrokeDescription(
                    plan.accelPath, 0L, plan.accelDuration, true /* willContinue */
                )
                val decel = accel.continueStroke(plan.decelPath, 0L, plan.decelDuration, false)
                GestureDescription.Builder()
                    .addStroke(accel)
                    .addStroke(decel)
                    .build()
            }
        } else {
            val plan: ContinuousPlan = withContext(defaultDispatcher) {
                gestureEngine.generateContinuousPlan(
                    screenWidth, screenHeight,
                    settings.distanceRatio, settings.duration, settings.direction
                )
            }
            totalDurationHint = plan.duration
            buildGesture = {
                // One stroke; the accelerate/gentle-decelerate profile lives in the
                // path's point spacing (see GestureEngine.generateContinuousPlan).
                val stroke = GestureDescription.StrokeDescription(plan.path, 0L, plan.duration)
                GestureDescription.Builder().addStroke(stroke).build()
            }
        }

        // 2. Dispatch on the main thread (dispatchGesture requires it).
        return withContext(mainDispatcher) {
            if (!isActive || !repository.isRunning.value) return@withContext false
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
         * Inter-swipe interval bounds. Aligned with the overlay slider's
         * `valueFrom/valueTo` (500..10000) so the noisy wait never exceeds what the
         * user configured. (Legacy code used 400..12000, which could overrun the max.)
         */
        const val MIN_INTERVAL_MS = 500L
        const val MAX_INTERVAL_MS = 10000L
    }
}
