package com.phantom.scroll.gesture

import android.graphics.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Random
import kotlin.math.abs
import kotlin.math.max

class GestureEngine {
    // ===== Object Pooling: zero GC in hot loop =====
    private val reusablePath = Path()
    private val random = Random()

    /**
     * Generates a realistic human-like vertical scroll path.
     *
     * Calculated entirely on [Dispatchers.Default] to keep Main thread free.
     * Uses quadratic Bezier curve B(t) = (1-t)²P₀ + 2t(1-t)P₁ + t²P₂
     * with low-frequency bio-noise offsets.
     *
     * @return GestureResult containing the path and actual duration
     */
    suspend fun generateGesturePath(
        screenWidth: Int,
        screenHeight: Int,
        distanceRatio: Float,
        durationMs: Long
    ): GestureResult = withContext(Dispatchers.Default) {
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

            // 4. X center with micro thumb-landing deviation (low-frequency noise)
            val centerX = screenWidth * 0.5f
            val startX = centerX + random.nextGaussian().toFloat() * (screenWidth * 0.02f)
            val endX = centerX + random.nextGaussian().toFloat() * (screenWidth * 0.02f)

            // 5. Quadratic Bezier control point P1 (creates natural thumb arc)
            val controlX = centerX + random.nextGaussian().toFloat() * (screenWidth * 0.05f)
            val controlY = (startY + endY) * 0.5f

            // 6. Duration with Bio-Noise (±7%)
            val actualDuration = addBioNoise(durationMs.toFloat(), 0.07f)
                .toLong().coerceIn(200, 1500)

            // 7. Build path using native hardware-smooth quadratic Bezier
            reusablePath.moveTo(startX, startY)
            reusablePath.quadTo(controlX, controlY, endX, endY)

            GestureResult(
                path = reusablePath,
                duration = actualDuration
            )
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
}

class GestureResult(
    val path: Path,
    val duration: Long
)

