package com.phantom.scroll.gesture

import android.graphics.Path
import java.util.Random

/**
 * Data class representing the geometric layout and timing of a human-like vertical swipe.
 * Fully decoupled from Android SDK classes for pure JVM unit testing.
 */
data class GesturePoints(
    val startX: Float,
    val startY: Float,
    val controlX: Float,
    val controlY: Float,
    val endX: Float,
    val endY: Float,
    val duration: Long
)

/**
 * Engine responsible for producing organic vertical scroll gestures.
 * Uses a cached [Path] instance to prevent memory allocation and GC jitter during continuous loops.
 */
class GestureEngine {
    private val reusablePath = Path()
    private val random = Random()

    /**
     * Adds Gaussian (normal distribution) noise to a base value.
     * The noise magnitude is ±[ratio] of the base value, clamped to ±2σ.
     */
    fun addBioNoise(baseValue: Float, ratio: Float): Float {
        val factor = (random.nextGaussian().toFloat() * ratio).coerceIn(-ratio * 2, ratio * 2)
        return baseValue * (1f + factor)
    }

    /**
     * Generates a realistic human-like vertical scroll path.
     *
     * @return GestureResult containing the path and actual duration.
     * Note: [GestureResult.path] is a borrowed reference that is reset and modified
     * upon subsequent calls. Callers must dispatch the gesture before requesting a new path.
     */
    fun generateGesturePath(
        screenWidth: Int,
        screenHeight: Int,
        distanceRatio: Float,
        durationMs: Long
    ): GestureResult {
        val points = calculateGesturePoints(screenWidth, screenHeight, distanceRatio, durationMs, random)
        
        reusablePath.reset()
        reusablePath.moveTo(points.startX, points.startY)
        reusablePath.quadTo(points.controlX, points.controlY, points.endX, points.endY)

        return GestureResult(
            path = reusablePath,
            duration = points.duration
        )
    }

    companion object {
        /**
         * Pure mathematical calculations for generating swipe gesture coordinates and durations.
         * Contains zero Android SDK dependencies for testing on JVM.
         */
        fun calculateGesturePoints(
            screenWidth: Int,
            screenHeight: Int,
            distanceRatio: Float,
            durationMs: Long,
            random: Random
        ): GesturePoints {
            // 1. Define screen safe zone (avoid status bar + navigation bar)
            val safeTop = screenHeight * 0.15f
            val safeBottom = screenHeight * 0.85f
            val safeHeight = safeBottom - safeTop

            // Helper for local noise calculations using the provided random instance
            fun addNoise(base: Float, ratio: Float): Float {
                val factor = (random.nextGaussian().toFloat() * ratio).coerceIn(-ratio * 2, ratio * 2)
                return base * (1f + factor)
            }

            // 2. Scroll distance with Bio-Noise (±8% Gaussian fluctuation)
            val baselineDistance = safeHeight * distanceRatio
            val noisyDistance = addNoise(baselineDistance, 0.08f)
                .coerceIn(safeHeight * 0.2f, safeHeight * 0.95f)

            // 3. Start/end Y: swipe from bottom upward (pulls content down)
            val startY = safeBottom - addNoise(screenHeight * 0.03f, 0.1f)
            val endY = (startY - noisyDistance).coerceAtLeast(safeTop)

            // 4. X center with micro thumb-landing deviation (low-frequency noise)
            val centerX = screenWidth * 0.5f
            val startX = centerX + random.nextGaussian().toFloat() * (screenWidth * 0.02f)
            val endX = centerX + random.nextGaussian().toFloat() * (screenWidth * 0.02f)

            // 5. Quadratic Bezier control point P1 (creates natural thumb arc)
            val controlX = centerX + random.nextGaussian().toFloat() * (screenWidth * 0.05f)
            val controlY = (startY + endY) * 0.5f

            // 6. Duration with Bio-Noise (±7%)
            val actualDuration = addNoise(durationMs.toFloat(), 0.07f)
                .toLong().coerceIn(200, 1500)

            return GesturePoints(
                startX = startX,
                startY = startY,
                controlX = controlX,
                controlY = controlY,
                endX = endX,
                endY = endY,
                duration = actualDuration
            )
        }
    }
}

/**
 * Result class representing the path and duration.
 *
 * @property path **Borrowed reference** — this Path is reused internally.
 * It becomes invalid after the next call to [GestureEngine.generateGesturePath].
 * The caller must complete gesture dispatch before requesting a new path.
 * @property duration The customized time in milliseconds the stroke should take.
 */
class GestureResult(
    val path: Path,
    val duration: Long
)
