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
 * Three orthogonal responsibilities:
 *  1. **Time → progress mapping** ([mapTimeToProgress] / [flooredEaseOut]): produce a
 *     human-like velocity profile. [flooredEaseOut] is the modern curve — it keeps a
 *     *floor* on the trailing speed so the finger never dwells slowly across an ad/诱导
 *     button (the root cause of misdetection), while still decelerating organically.
 *  2. **Bezier sampling** ([sampleBezier]): flattens a quadratic bezier into discrete
 *     points, each carrying sub-pixel Gaussian jitter, to break the geometric
 *     perfection of a single [android.graphics.Path.quadTo] curve.
 *  3. **Time-domain resampling** ([resampleByTimeProgress]): redistributes arc-uniform
 *     samples into time-uniform samples, encoding a speed curve *inside one stroke* —
 *     this is what lets a single `StrokeDescription` express acceleration + gentle
 *     deceleration **without** a `continueStroke` seam (the other misdetection root).
 *
 * These are intentionally independent: the speed curve controls *when* distance is
 * covered, while sampling controls *the shape* of the path.
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
     * A "floored" ease-out: a human-like accelerate-then-gentle-decelerate curve
     * whose *terminal* speed never drops below [speedFloor] (mean speed normalized to 1).
     *
     * This is the anti-misdetection core. Pure [easeOutCubic] ends with derivative 0,
     * i.e. the finger all but stops near the end — exactly the "slow dwell" that ad/诱导
     * SDKs register as a deliberate tap. By mixing a linear term of slope [speedFloor]
     * with a scaled ease-out, we keep the organic fast-start/slow-finish *shape* while
     * guaranteeing the trailing instantaneous speed stays ≥ [speedFloor]× the mean.
     *
     * Definition:  `progress(t) = speedFloor·t + (1 - speedFloor)·easeOutCubic(t)`
     *  - `progress(0) = 0`, `progress(1) = 1` (endpoints preserved).
     *  - `progress'(t) = speedFloor + (1 - speedFloor)·3·(1-t)²` ≥ speedFloor everywhere,
     *    and `progress'(0) = speedFloor + 3·(1 - speedFloor)` > 1 (fast start).
     *
     * @param t normalized time ∈ [0,1], clamped.
     * @param speedFloor normalized minimum trailing speed ∈ [0,1). 0.85 ≈ 85% of mean
     *        speed — fast enough to cross any ad button in <50ms, yet still visibly
     *        "decelerating" relative to the fast start. Pass 0 to recover pure ease-out.
     */
    fun flooredEaseOut(t: Float, speedFloor: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        val floor = speedFloor.coerceIn(0f, 0.99f)
        return floor * clamped + (1f - floor) * easeOutCubic(clamped)
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
     * Redistributes an arc-length-evenly-sampled point list into a **time-evenly**-
     * distributed one, encoding a speed curve *into a single continuous path* — the
     * technique that lets one `StrokeDescription` express human-like acceleration and
     * gentle deceleration **without** any `continueStroke` seam.
     *
     * **Why this works.** Android maps linear time `[0, duration]` across a stroke's
     * path points; it does *not* re-parameterize by arc length. So if output points are
     * sparse along the arc early (the finger covers a lot of ground per unit time) and
     * denser later, the finger is genuinely *fast* early and *slower* later — even
     * though there is only one stroke and no seam. This is what kills the two
     * misdetection roots at once:
     *   1. No `continueStroke` join → no velocity discontinuity / micro-pause.
     *   2. The trailing speed is floored via [flooredEaseOut], so the finger never
     *      "dwells slowly" across an ad/诱导 button long enough to read as a tap.
     *
     * **How.** Each output index `i ∈ [0, outCount-1]` corresponds to uniform time
     * `t = i/(outCount-1)`. Map `t → progress p` via [flooredEaseOut] with [speedFloor];
     * then locate the input point at arc-fraction `p` by linear interpolation over the
     * (approximately arc-uniform) input list. Early `t` yields large `p` jumps (sparse,
     * fast), late `t` yields small-but-floored `p` steps (denser, but never crawling).
     *
     * @param input arc-length-evenly-sampled points (e.g. from [sampleBezier]); must be
     *        non-empty. At least 2 points recommended for meaningful resampling.
     * @param outCount number of output points. Clamped to ≥ 2. The caller typically
     *        matches the input count to keep path resolution constant.
     * @param speedFloor normalized minimum trailing speed ∈ [0,1). 0.85 keeps the finger
     *        crossing any button in <50ms while still visibly "slowing" toward the end.
     * @return a new list of [outCount] points; the first and last equal the input's
     *         first and last exactly (endpoints preserved). The input list is untouched.
     */
    fun resampleByTimeProgress(
        input: List<SampledPoint>,
        outCount: Int,
        speedFloor: Float
    ): List<SampledPoint> {
        if (input.isEmpty()) return emptyList()
        if (input.size == 1 || outCount <= 2) return listOf(input.first(), input.last())

        val n = outCount.coerceAtLeast(2)
        val lastInput = input.size - 1
        val out = ArrayList<SampledPoint>(n)
        for (i in 0 until n) {
            val t = if (n == 1) 0f else i.toFloat() / (n - 1).toFloat()
            val p = flooredEaseOut(t, speedFloor)
            // Locate the input sample at arc-fraction p via linear interpolation.
            val pos = p * lastInput
            val lower = pos.toInt().coerceIn(0, lastInput - 1)
            val frac = pos - lower
            val a = input[lower]
            val b = input[lower + 1]
            out.add(SampledPoint(a.x + (b.x - a.x) * frac, a.y + (b.y - a.y) * frac))
        }
        return out
    }

    /**
     * Immutable sampled point. A pure-Kotlin analogue of `android.graphics.PointF`
     * so that bezier sampling stays JVM-testable.
     */
    data class SampledPoint(val x: Float, val y: Float)
}
