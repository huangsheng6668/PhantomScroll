package com.phantom.scroll.gesture

import android.graphics.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Random
import kotlin.math.abs
import kotlin.math.max

class GestureEngine {
    // ===== Object Pooling: zero GC in hot loop =====
    // Reuse a single Path instance — call reset() before each use
    private val reusablePath = Path()

    // Pre-allocated coordinate arrays (max ~94 points for 1500ms @ 60fps)
    private val maxSamples = 120
    private val xPoints = FloatArray(maxSamples)
    private val yPoints = FloatArray(maxSamples)

    // Reuse Random instance
    private val random = Random()

    // ===== Cubic Bezier Interpolator: cubic-bezier(0.25, 0.1, 0.25, 1.0) =====
    // Asymmetric speed curve: short+sharp acceleration, long+gentle deceleration
    private val p1x = 0.25f
    private val p1y = 0.1f
    private val p2x = 0.25f
    private val p2y = 1.0f

    /**
     * Generates a realistic human-like vertical scroll path.
     *
     * Calculated entirely on [Dispatchers.Default] to keep Main thread free.
     * Uses quadratic Bezier curve B(t) = (1-t)²P₀ + 2t(1-t)P₁ + t²P₂
     * with bio-noise jitter and non-symmetric speed interpolation.
     *
     * @return Pair of (reusable Path, actual duration in ms)
     */
    suspend fun generateGesturePath(
        screenWidth: Int,
        screenHeight: Int,
        distanceRatio: Float,
        durationMs: Long
    ): Pair<Path, Long> = withContext(Dispatchers.Default) {
        synchronized(reusablePath) {
            reusablePath.reset()

            // 1. Define screen safe zone (avoid status bar + navigation bar)
            val safeTop = screenHeight * 0.15f
            val safeBottom = screenHeight * 0.85f
            val safeHeight = safeBottom - safeTop

            // 2. Scroll distance with Bio-Noise (±8% Gaussian fluctuation)
            val baselineDistance = safeHeight * distanceRatio
            val noisyDistance = addBioNoise(baselineDistance, 0.08f)
                .coerceIn(safeHeight * 0.2f, safeHeight * 0.95f)

            // 3. Start/end Y: swipe from bottom upward (pulls content down)
            val startY = safeBottom - addBioNoise(screenHeight * 0.03f, 0.1f)
            val endY = (startY - noisyDistance).coerceAtLeast(safeTop)

            // 4. X center with micro thumb-landing deviation
            val centerX = screenWidth * 0.5f
            val startX = centerX + random.nextGaussian().toFloat() * (screenWidth * 0.02f)

            // 5. Quadratic Bezier control point P1 (creates natural thumb arc)
            val controlX = centerX + random.nextGaussian().toFloat() * (screenWidth * 0.05f)
            val controlY = (startY + endY) * 0.5f

            // 6. Duration with Bio-Noise (±7%)
            val actualDuration = addBioNoise(durationMs.toFloat(), 0.07f)
                .toLong().coerceIn(200, 1500)

            // 7. Sampling density: ~1 point per 16ms frame (60fps target)
            val sampleCount = max(12, (actualDuration / 16).toInt()).coerceAtMost(maxSamples)

            // 8. Sample path points with Bezier + custom interpolator + jitter
            for (i in 0 until sampleCount) {
                val rawT = i.toFloat() / (sampleCount - 1)

                // Apply non-symmetric ease-out curve
                val t = solveCubicBezier(rawT)

                // B(t) = (1-t)² · P₀ + 2t(1-t) · P₁ + t² · P₂
                val mt = 1f - t
                val x = mt * mt * startX + 2f * mt * t * controlX + t * t * startX
                val y = mt * mt * startY + 2f * mt * t * controlY + t * t * endY

                // Pixel-level hand-tremor noise
                xPoints[i] = x + random.nextGaussian().toFloat() * 0.4f
                yPoints[i] = y + random.nextGaussian().toFloat() * 0.4f
            }

            // 9. Build Path from pre-allocated arrays
            reusablePath.moveTo(xPoints[0], yPoints[0])
            for (i in 1 until sampleCount) {
                reusablePath.lineTo(xPoints[i], yPoints[i])
            }

            Pair(reusablePath, actualDuration)
        }
    }

    /**
     * Adds Gaussian (normal distribution) noise to a base value.
     * The noise magnitude is ±[ratio] of the base value, clamped to ±2σ.
     */
    fun addBioNoise(baseValue: Float, ratio: Float): Float {
        val factor = (random.nextGaussian().toFloat() * ratio).coerceIn(-ratio * 2, ratio * 2)
        return baseValue * (1f + factor)
    }

    // ===== Cubic Bezier Interpolator Solver =====
    // Solves cubic-bezier(0.25, 0.1, 0.25, 1.0) using Newton-Raphson

    private fun solveCubicBezier(x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f

        // Newton-Raphson: find t where curveX(t) = x, then return curveY(t)
        var t = x // initial guess
        for (i in 0..7) {
            val error = sampleCurveX(t) - x
            val derivative = sampleCurveDerivativeX(t)
            if (abs(derivative) < 1e-5f) break
            t -= error / derivative
        }
        return sampleCurveY(t.coerceIn(0f, 1f))
    }

    private fun sampleCurveX(t: Float): Float {
        val cx = 3f * p1x
        val bx = 3f * (p2x - p1x) - cx
        val ax = 1f - cx - bx
        return ((ax * t + bx) * t + cx) * t
    }

    private fun sampleCurveY(t: Float): Float {
        val cy = 3f * p1y
        val by = 3f * (p2y - p1y) - cy
        val ay = 1f - cy - by
        return ((ay * t + by) * t + cy) * t
    }

    private fun sampleCurveDerivativeX(t: Float): Float {
        val cx = 3f * p1x
        val bx = 3f * (p2x - p1x) - cx
        val ax = 1f - cx - bx
        return (3f * ax * t + 2f * bx) * t + cx
    }
}
