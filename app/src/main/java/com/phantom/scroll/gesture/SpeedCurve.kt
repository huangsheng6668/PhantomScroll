package com.phantom.scroll.gesture

import java.util.Random
import kotlin.math.pow

/**
 * Pure-JVM mathematics for human-like scroll speed curves and bezier sampling.
 *
 * Side-effect-free, zero Android SDK dependencies, fully unit-testable on a plain JVM.
 * Mirrors the architectural style of [com.phantom.scroll.ui.overlay.OverlayGeometry]:
 * stateless top-level functions grouped in an `object`.
 *
 * Two orthogonal responsibilities:
 *  1. **Time → progress mapping** ([mapTimeToProgress]): produces the asymmetric
 *     "short/aggressive acceleration + long/gentle deceleration" velocity profile
 *     required by AGENTS.md §4. This is consumed by the caller to split a swipe into
 *     two continuous strokes with different durations.
 *  2. **Bezier sampling** ([sampleBezier]): flattens a quadratic bezier into discrete
 *     points, each carrying sub-pixel Gaussian jitter, to break the geometric
 *     perfection of a single [android.graphics.Path.quadTo] curve.
 *
 * These two are intentionally independent: the speed curve controls *when* distance
 * is covered, while sampling controls *the shape* of the path.
 */
object SpeedCurve {

    /**
     * ease-out-cubic easing. Monotonically increasing on [0,1] with
     * `f(0) = 0` and `f(1) = 1`. Used as the shape of the deceleration tail.
     */
    fun easeOutCubic(t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        return 1f - (1f - clamped).pow(3)
    }

    /**
     * Maps normalized time `t ∈ [0,1]` to normalized scroll progress `∈ [0,1]`,
     * implementing the asymmetric "accelerate briefly, decelerate gently" profile.
     *
     * Two phases, piecewise-linear in (time, progress) space:
     *  - **Acceleration phase**: during the first [accelDurationRatio] of total time,
     *    progress advances to [accelDistanceRatio]. Because the distance gained exceeds
     *    the time elapsed, this phase is *faster* than the mean.
     *  - **Deceleration phase**: the remainder of the time covers the remainder of the
     *    distance at a slower pace.
     *
     * Example: `accelDurationRatio = 0.25, accelDistanceRatio = 0.45`
     *  → first 25% of time covers 45% of distance (1.8× mean speed),
     *    remaining 75% of time covers 55% of distance (0.73× mean speed).
     *
     * @param t normalized elapsed time, clamped to [0,1].
     * @param accelDurationRatio fraction of total duration spent accelerating,
     *        clamped to (0,1).
     * @param accelDistanceRatio fraction of total distance covered during the
     *        acceleration phase, clamped to (0,1). Must exceed [accelDurationRatio]
     *        for the phase to actually be "fast" — the caller enforces this invariant.
     */
    fun mapTimeToProgress(
        t: Float,
        accelDurationRatio: Float,
        accelDistanceRatio: Float
    ): Float {
        val time = t.coerceIn(0f, 1f)
        val adRatio = accelDurationRatio.coerceIn(0.001f, 0.999f)
        val aDistRatio = accelDistanceRatio.coerceIn(0.001f, 0.999f)
        return if (time <= adRatio) {
            // Acceleration phase: scale time into [0, aDistRatio].
            (time / adRatio) * aDistRatio
        } else {
            // Deceleration phase: scale remaining time into remaining progress.
            val remainT = (time - adRatio) / (1f - adRatio)
            aDistRatio + remainT * (1f - aDistRatio)
        }
    }

    /**
     * Samples a quadratic bezier `B(s) = (1-s)²·P0 + 2s(1-s)·P1 + s²·P2` into
     * [sampleCount] evenly-spaced points along the *parameter* `s ∈ [0,1]`,
     * adding independent Gaussian jitter (σ ≈ [jitterPx], clamped to ±2σ) to each
     * non-endpoint sample's x and y.
     *
     * The endpoints ([p0] and [p2]) are returned **untouched** (zero jitter) so the
     * swipe always begins and ends exactly where intended. Jitter is applied to the
     * interior samples to give the trajectory an organic, finger-imperfect shape.
     *
     * @param sampleCount total points to produce. Must be ≥ 2 (one start, one end);
     *        otherwise a 2-point list `[p0, p2]` is returned unconditionally.
     * @param jitterPx standard deviation of the per-axis Gaussian noise. Pass 0 to
     *        disable jitter (useful for testing).
     * @param random source of Gaussian noise; injected for deterministic tests.
     */
    fun sampleBezier(
        p0: SampledPoint,
        p1: SampledPoint,
        p2: SampledPoint,
        sampleCount: Int,
        jitterPx: Float,
        random: Random
    ): List<SampledPoint> {
        if (sampleCount <= 2) return listOf(p0, p2)

        val out = ArrayList<SampledPoint>(sampleCount)
        out.add(p0) // endpoint, no jitter

        val maxJitter = jitterPx * 2f // clamp at ±2σ
        for (i in 1 until sampleCount - 1) {
            val s = i.toFloat() / (sampleCount - 1).toFloat()
            val oneMinusS = 1f - s
            val b0 = oneMinusS * oneMinusS
            val b1 = 2f * s * oneMinusS
            val b2 = s * s
            val x = b0 * p0.x + b1 * p1.x + b2 * p2.x
            val y = b0 * p0.y + b1 * p1.y + b2 * p2.y
            val jx = (random.nextGaussian().toFloat() * jitterPx).coerceIn(-maxJitter, maxJitter)
            val jy = (random.nextGaussian().toFloat() * jitterPx).coerceIn(-maxJitter, maxJitter)
            out.add(SampledPoint(x + jx, y + jy))
        }

        out.add(p2) // endpoint, no jitter
        return out
    }

    /**
     * Immutable sampled point. A pure-Kotlin analogue of `android.graphics.PointF`
     * so that bezier sampling stays JVM-testable.
     */
    data class SampledPoint(val x: Float, val y: Float)
}
