package com.phantom.scroll.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.widget.Toast
import com.phantom.scroll.data.SettingsRepository
import com.phantom.scroll.gesture.GestureEngine
import com.phantom.scroll.util.PhantomLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.coroutines.resume

/**
 * Orchestrator that manages the automated scrolling loop.
 * Reads effective settings from [SettingsRepository] and delegates consecutive-failure
 * accounting to a [FailurePolicy].
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

    fun start() {
        loopJob = scope.launch {
            while (isActive) {
                try {
                    repository.isRunning.first { it }
                    if (!repository.isRunning.value) continue

                    val dm = service.resources.displayMetrics
                    val screenWidth = dm.widthPixels
                    val screenHeight = dm.heightPixels
                    val settings = repository.activeSettings.value

                    val gestureResult = withContext(Dispatchers.Default) {
                        gestureEngine.generateGesturePath(
                            screenWidth = screenWidth,
                            screenHeight = screenHeight,
                            distanceRatio = settings.distanceRatio,
                            durationMs = settings.duration,
                            direction = settings.direction
                        )
                    }

                    val gestureSucceeded = withContext(Dispatchers.Main.immediate) {
                        if (!isActive || !repository.isRunning.value) return@withContext false
                        val timeoutMs = gestureResult.duration + 2000L
                        withTimeoutOrNull(timeoutMs) {
                            suspendCancellableCoroutine<Boolean> { cont ->
                                val stroke = GestureDescription.StrokeDescription(
                                    gestureResult.path, 0L, gestureResult.duration
                                )
                                val gesture = GestureDescription.Builder().addStroke(stroke).build()

                                val dispatched = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                                    override fun onCompleted(g: GestureDescription?) {
                                        failurePolicy.recordSuccess()
                                        if (cont.isActive) cont.resume(true)
                                    }
                                    override fun onCancelled(g: GestureDescription?) {
                                        if (cont.isActive) cont.resume(false)
                                        handleFailure()
                                    }
                                }, null)

                                if (!dispatched) {
                                    if (cont.isActive) cont.resume(false)
                                    handleFailure()
                                }
                            }
                        } ?: run {
                            handleFailure()
                            false
                        }
                    }

                    if (!gestureSucceeded) {
                        delay(500)
                        continue
                    }

                    val noiseInterval = gestureEngine.addBioNoise(settings.interval.toFloat(), 0.08f)
                        .toLong().coerceIn(400, 12000)
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
     * Centralized failure handling: delegates counting/decision to [failurePolicy] and performs
     * the auto-pause + user feedback when the threshold is reached.
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
}
