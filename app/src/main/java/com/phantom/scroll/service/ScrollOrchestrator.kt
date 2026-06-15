package com.phantom.scroll.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.widget.Toast
import com.phantom.scroll.data.ScrollSettings
import com.phantom.scroll.data.SettingsRepository
import com.phantom.scroll.gesture.GestureEngine
import com.phantom.scroll.gesture.GesturePlan
import com.phantom.scroll.gesture.SinglePathResult
import com.phantom.scroll.util.PhantomLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.coroutines.resume

/**
 * Orchestrator that manages the automated scrolling loop.
 * Reads effective settings from [SettingsRepository] and delegates consecutive-failure
 * accounting to a [FailurePolicy].
 *
 * Gesture dispatch uses a **two-phase continuous-stroke** plan (acceleration + deceleration)
 * to produce the human-like asymmetric speed curve. Some ROMs mishandle
 * `StrokeDescription.continuedStroke`; to stay robust we track cancellation patterns and
 * automatically fall back to a single-path (constant-speed) plan — without losing the
 * bezier sampling jitter or randomized start point. See [degradedMode].
 */
class ScrollOrchestrator(
    private val service: AccessibilityService,
    private val repository: SettingsRepository,
    private val scope: CoroutineScope
) {
    private val TAG = "ScrollOrchestrator"
    private val gestureEngine = GestureEngine()
    private val failurePolicy = FailurePolicy(threshold = 3)
    private var loopJob: Job? = null

    /**
     * `true` once continuous-stroke chaining has been observed to fail repeatedly on
     * this device. While set, [buildAndDispatch] emits a single-path stroke instead of
     * the two-phase plan. Deliberately *not* auto-cleared on success to avoid flapping;
     * a service restart restores the fast path.
     */
    @Volatile
    private var degradedMode = false

    /**
     * Consecutive cancellation count while still on the fast (two-phase) path. Crossing
     * [DEGRADATION_THRESHOLD] flips [degradedMode]. Reset on any completion or once
     * degradation engages.
     */
    private var consecutiveCancellation = 0

    fun start() {
        loopJob = scope.launch {
            while (isActive) {
                try {
                    repository.isRunning.first { it }
                    if (!repository.isRunning.value) continue

                    val screenWidth = repository.screenWidth.value
                    val screenHeight = repository.screenHeight.value
                    val settings = repository.activeSettings.value

                    val gestureSucceeded = dispatchSwipe(screenWidth, screenHeight, settings)

                    if (!gestureSucceeded) {
                        delay(500)
                        continue
                    }

                    val noiseInterval = gestureEngine.addBioNoise(settings.interval.toFloat(), 0.08f)
                        .toLong().coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
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
     * only on a completed gesture. Handles the two-phase → degraded single-path
     * transition transparently.
     */
    private suspend fun dispatchSwipe(
        screenWidth: Int,
        screenHeight: Int,
        settings: ScrollSettings
    ): Boolean {
        // 1. Generate the path off the main thread.
        val totalDurationHint: Long
        val buildGesture: () -> GestureDescription
        if (degradedMode) {
            val single: SinglePathResult = withContext(Dispatchers.Default) {
                gestureEngine.generateSinglePath(
                    screenWidth, screenHeight,
                    settings.distanceRatio, settings.duration, settings.direction
                )
            }
            totalDurationHint = single.duration
            buildGesture = {
                val stroke = GestureDescription.StrokeDescription(single.path, 0L, single.duration)
                GestureDescription.Builder().addStroke(stroke).build()
            }
        } else {
            val plan: GesturePlan = withContext(Dispatchers.Default) {
                gestureEngine.generateGesturePlan(
                    screenWidth, screenHeight,
                    settings.distanceRatio, settings.duration, settings.direction
                )
            }
            totalDurationHint = plan.accelDuration + plan.decelDuration
            buildGesture = {
                // Two continuous strokes: the second continues the first, so Android
                // treats them as one finger with a speed change at the seam.
                val accel = GestureDescription.StrokeDescription(
                    plan.accelPath, 0L, plan.accelDuration, true /* willContinue */
                )
                // decel is the terminal segment → willContinue = false.
                val decel = accel.continueStroke(plan.decelPath, 0L, plan.decelDuration, false)
                GestureDescription.Builder()
                    .addStroke(accel)
                    .addStroke(decel)
                    .build()
            }
        }

        // 2. Dispatch on the main thread (dispatchGesture requires it).
        return withContext(Dispatchers.Main.immediate) {
            if (!isActive || !repository.isRunning.value) return@withContext false
            val timeoutMs = totalDurationHint + GESTURE_TIMEOUT_SLACK_MS
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val gesture = buildGesture()
                    val dispatched = service.dispatchGesture(
                        gesture,
                        object : AccessibilityService.GestureResultCallback() {
                            override fun onCompleted(g: GestureDescription?) {
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
                        if (cont.isActive) cont.resume(false)
                        handleFailure()
                    }
                }
            } ?: run {
                // Timed out with no callback at all — treat as ordinary failure.
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
        if (degradedMode) {
            if (cont.isActive) cont.resume(false)
            handleFailure()
            return
        }
        consecutiveCancellation++
        if (consecutiveCancellation >= DEGRADATION_THRESHOLD) {
            degradedMode = true
            consecutiveCancellation = 0
            PhantomLog.w(TAG, "Continuous-stroke cancellations hit threshold → degraded single-path mode.")
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
                repository.stopRunning()
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
         * Inter-swipe interval bounds. Aligned with the overlay slider's
         * `valueFrom/valueTo` (500..10000) so the noisy wait never exceeds what the
         * user configured. (Legacy code used 400..12000, which could overrun the max.)
         */
        const val MIN_INTERVAL_MS = 500L
        const val MAX_INTERVAL_MS = 10000L
    }
}
