package com.phantom.scroll.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.widget.Toast
import com.phantom.scroll.config.ScrollConfig
import com.phantom.scroll.gesture.GestureEngine
import com.phantom.scroll.util.PhantomLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.coroutines.resume

/**
 * Orchestrator that manages the automated scrolling loop.
 * Monitors [ScrollConfig.isRunning] state and schedules gestural injections
 * with realistic human biological noise, speed interpolation, and safety fallback mechanisms.
 */
class ScrollOrchestrator(
    private val service: AccessibilityService,
    private val config: ScrollConfig,
    private val scope: CoroutineScope
) {
    private val TAG = "ScrollOrchestrator"
    private val gestureEngine = GestureEngine()
    private var consecutiveFailures = 0
    private var loopJob: Job? = null

    /**
     * Starts the automated gesture loop.
     */
    fun start() {
        loopJob = scope.launch {
            while (isActive) {
                try {
                    // Suspend until isRunning becomes true
                    config.isRunning.first { it }

                    // Double-check after resume
                    if (!config.isRunning.value) continue

                    val dm = service.resources.displayMetrics
                    val screenWidth = dm.widthPixels
                    val screenHeight = dm.heightPixels
                    val snap = config.snapshot()

                    // Generate path on Dispatchers.Default (CPU-intensive calculation)
                    val gestureResult = withContext(Dispatchers.Default) {
                        gestureEngine.generateGesturePath(
                            screenWidth = screenWidth,
                            screenHeight = screenHeight,
                            distanceRatio = snap.distanceRatio,
                            durationMs = snap.duration
                        )
                    }

                    // Dispatch gesture and WAIT for completion on Main thread
                    val gestureSucceeded = withContext(Dispatchers.Main.immediate) {
                        if (!isActive || !config.isRunning.value) return@withContext false

                        // Safety Timeout: duration + 2 seconds to prevent coroutines hanging if the system swallows the callback
                        val timeoutMs = gestureResult.duration + 2000L
                        withTimeoutOrNull(timeoutMs) {
                            suspendCancellableCoroutine<Boolean> { cont ->
                                val stroke = GestureDescription.StrokeDescription(
                                    gestureResult.path,
                                    0L,
                                    gestureResult.duration
                                )
                                val gesture = GestureDescription.Builder().addStroke(stroke).build()

                                val dispatched = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                                    override fun onCompleted(gestureDescription: GestureDescription?) {
                                        consecutiveFailures = 0
                                        if (cont.isActive) cont.resume(true)
                                    }

                                    override fun onCancelled(gestureDescription: GestureDescription?) {
                                        // 🔴 Avoid false positives: if the user paused manually or locked screen, don't treat it as failure.
                                        if (config.isRunning.value) {
                                            consecutiveFailures++
                                            PhantomLog.w(TAG, "Gesture cancelled by system. Consecutive failures: $consecutiveFailures")
                                            if (consecutiveFailures >= 3) {
                                                PhantomLog.e(TAG, "3 consecutive failures → auto-pausing.")
                                                config.isRunning.value = false
                                                consecutiveFailures = 0
                                                Toast.makeText(service, "⚠️ 连续三次滑动失败，已自动暂停", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            PhantomLog.d(TAG, "Gesture cancelled due to manual pause/lock screen. Ignoring failure count.")
                                        }
                                        if (cont.isActive) cont.resume(false)
                                    }
                                }, null)

                                if (!dispatched) {
                                    PhantomLog.w(TAG, "dispatchGesture returned false.")
                                    if (config.isRunning.value) {
                                        consecutiveFailures++
                                        if (consecutiveFailures >= 3) {
                                            config.isRunning.value = false
                                            consecutiveFailures = 0
                                            Toast.makeText(service, "⚠️ 连续三次滑动失败，已自动暂停", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    if (cont.isActive) cont.resume(false)
                                }
                            }
                        } ?: run {
                            PhantomLog.e(TAG, "Gesture dispatch timed out after ${timeoutMs}ms.")
                            if (config.isRunning.value) {
                                consecutiveFailures++
                                if (consecutiveFailures >= 3) {
                                    config.isRunning.value = false
                                    consecutiveFailures = 0
                                    Toast.makeText(service, "⚠️ 连续三次滑动失败，已自动暂停", Toast.LENGTH_SHORT).show()
                                }
                            }
                            false
                        }
                    }

                    if (!gestureSucceeded) {
                        // Pause briefly before retrying to prevent hot looping
                        delay(500)
                        continue
                    }

                    // Wait interval with Bio-Noise (non-blocking delay)
                    val noiseInterval = gestureEngine.addBioNoise(snap.interval.toFloat(), 0.08f)
                        .toLong().coerceIn(400, 12000)
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
     * Cancels the scrolling loop job.
     */
    fun stop() {
        loopJob?.cancel()
    }
}
