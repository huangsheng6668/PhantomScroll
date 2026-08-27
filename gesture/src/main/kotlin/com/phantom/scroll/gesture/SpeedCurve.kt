package com.phantom.scroll.gesture

import java.util.Random
import kotlin.math.pow

/**
 * Pure-JVM mathematics for human-like scroll speed curves and bezier sampling.
 *
 * Side-effect-free, zero Android SDK dependencies, fully unit-testable on a plain JVM.
 * This file is the mathematical heart of the independent `:gesture` module.
 *
 * Four orthogonal responsibilities:
 *  1. **Time → progress mapping** ([mapTimeToProgress] / [flooredEaseOut]): produce a
 *     human-like velocity profile. [flooredEaseOut] is the modern curve — it keeps a
 *     *floor* on the trailing speed so the finger never dwells slowly across an ad/诱导
 *     button (the root cause of misdetection), while still decelerating organically.
 *     An optional [flooredEaseOut.easeScale] varies the attack sharpness per swipe so
 *     the normalized speed curve is not bit-identical on every swipe (a statistical tell).
 *  2. **Bezier sampling** ([sampleBezier] / [sampleCubicBezier] /
 *     [sampleCubicBezierArcLength]): flatten a quadratic or cubic bezier into discrete
 *     points, each carrying sub-pixel Gaussian jitter. The ARC-LENGTH variant is the
 *     engine's primary path: it distributes samples evenly along the curve's *length*
 *     (not its parameter), so the time-domain resampling below encodes speed without
 *     distortion from parameter-speed variance on strongly curved (S-curve) shapes.
 *  3. **Time-domain resampling** ([resampleByTimeProgress]): redistribute arc-uniform
 *     samples into time-uniform samples, encoding a speed curve *inside one stroke* —
 *     this is what lets a single `StrokeDescription` express acceleration + gentle
 *     deceleration **without** a `continueStroke` seam (the other misdetection root).
 *     Implemented as a monotone forward sweep: O(n) over the output.
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
     * with a power-based ease-out, we keep the organic fast-start/slow-finish *shape*
     * while guaranteeing the trailing instantaneous speed stays ≥ [speedFloor]× the mean.
     *
     * The attack sharpness scales with the floor: `exponent = (3 + 2·speedFloor)·easeScale`.
     *  - Fast bands (floor 0.93, exponent ≈ 4.86) keep a relatively UNIFORM profile
     *    (attack ≈ 1.27× mean, tail 0.93×) — a quick flick has no room for drama, and
     *    the barely-decelerating tail means a lift over an ad banner is a brief graze,
     *    never a dwell (anti-misclick hardening for the fast bands).
     *  - Slow band (floor 0.82, exponent ≈ 4.64) gets the most dramatic contrast
     *    (attack ≈ 1.66× mean, tail 0.82×) — a deliberate drag that "grabs and pulls"
     *    before crawling, the signature of relaxed reading.
     *  - `speedFloor = 0` recovers pure [easeOutCubic] exactly (exponent 3).
     *
     * Definition: `progress(t) = speedFloor·t + (1 - speedFloor)·(1 - (1-t)^exponent)`
     *  - `progress(0) = 0`, `progress(1) = 1` (endpoints preserved).
     *  - `progress'(t) = speedFloor + (1 - speedFloor)·exponent·(1-t)^(exponent-1)`,
     *    which is ≥ speedFloor everywhere and equals speedFloor at t=1 (the floor).
     *
     * @param t normalized time ∈ [0,1], clamped.
     * @param speedFloor normalized minimum trailing speed ∈ [0,1). 0.93 (fast bands) ≈ 93%
     *        of mean — the finger grazes any ad button in ~12ms at reading distances, far
     *        below any tap timeout, yet the curve still decelerates visibly relative to its
     *        attack. Pass 0 to recover pure ease-out.
     * @param easeScale per-swipe multiplier on the attack exponent (default 1). Scales how
     *        hard the curve attacks without touching the terminal floor. Slightly above 1 =
     *        a snappier-than-usual swipe, below 1 = a lazier one — the per-swipe variety
     *        that keeps the normalized speed profile from being identical every swipe.
     */
    fun flooredEaseOut(t: Float, speedFloor: Float, easeScale: Float = 1f): Float {
        val clamped = t.coerceIn(0f, 1f)
        val floor = speedFloor.coerceIn(0f, 0.99f)
        val scale = easeScale.coerceIn(0.5f, 2f)
        // Attack exponent grows with the floor: faster swipes attack harder, while
        // floor=0 recovers pure easeOutCubic (exponent 3) exactly.
        val exponent = (3f + 2f * floor) * scale
        return floor * clamped + (1f - floor) * (1f - (1f - clamped).pow(exponent))
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
     * Samples a cubic bezier
     * `B(s) = (1-s)³·P0 + 3s(1-s)²·C1 + 3s²(1-s)·C2 + s³·P3` into [sampleCount]
     * evenly-spaced points along the *parameter* `s ∈ [0,1]`, adding independent
     * Gaussian jitter (σ ≈ [jitterPx], clamped to ±2σ) to each non-endpoint sample.
     *
     * Two control points allow the trajectory to be either a gentle C-arc
     * (`C1 == C2` at the midpoint) or an S-curve (`C1` offset one way near s=1/3,
     * `C2` the other way near s=2/3 — the thumb-pivot signature of a real finger).
     * Endpoints are returned untouched so the swipe always begins and ends exactly
     * where intended.
     *
     * @param sampleCount total points to produce. Must be ≥ 2 (one start, one end);
     *        otherwise a 2-point list `[p0, p3]` is returned unconditionally.
     * @param jitterPx standard deviation of the per-axis Gaussian noise. Pass 0 to
     *        disable jitter (useful for testing).
     * @param random source of Gaussian noise; injected for deterministic tests.
     */
    fun sampleCubicBezier(
        p0: SampledPoint,
        c1: SampledPoint,
        c2: SampledPoint,
        p3: SampledPoint,
        sampleCount: Int,
        jitterPx: Float,
        random: Random
    ): List<SampledPoint> {
        if (sampleCount <= 2) return listOf(p0, p3)

        val out = ArrayList<SampledPoint>(sampleCount)
        out.add(p0) // endpoint, no jitter

        val maxJitter = jitterPx * 2f // clamp at ±2σ
        for (i in 1 until sampleCount - 1) {
            val s = i.toFloat() / (sampleCount - 1).toFloat()
            val oneMinusS = 1f - s
            val b0 = oneMinusS * oneMinusS * oneMinusS
            val b1 = 3f * s * oneMinusS * oneMinusS
            val b2 = 3f * s * s * oneMinusS
            val b3 = s * s * s
            val x = b0 * p0.x + b1 * c1.x + b2 * c2.x + b3 * p3.x
            val y = b0 * p0.y + b1 * c1.y + b2 * c2.y + b3 * p3.y
            val jx = (random.nextGaussian().toFloat() * jitterPx).coerceIn(-maxJitter, maxJitter)
            val jy = (random.nextGaussian().toFloat() * jitterPx).coerceIn(-maxJitter, maxJitter)
            out.add(SampledPoint(x + jx, y + jy))
        }

        out.add(p3) // endpoint, no jitter
        return out
    }

    /**
     * Arc-length-uniform sampling of the same cubic bezier as [sampleCubicBezier] —
     * the engine's PRIMARY sampling path and the accuracy fix over parameter-uniform
     * sampling.
     *
     * **Why.** A bezier's parameter does not advance proportionally to distance: near a
     * control point the curve slows down (in parameter space the point lingers), so
     * parameter-uniform samples bunch up where the curve bends. Downstream,
     * [resampleByTimeProgress] assumes its input is "arc-uniform" — with parameter-
     * uniform input the encoded speed curve was subtly distorted on strongly curved
     * S-swipes (the very swipes whose shape is most human). This sampler makes the
     * assumption literally true.
     *
     * **How.** The curve is densely pre-sampled (≥ 8× the output count, ≥ 128 segments)
     * to build a cumulative chord-length table; each output i then targets the arc
     * fraction `f = i/(n-1)`, locates the enclosing dense segment, converts to the exact
     * bezier parameter `t`, and evaluates the analytic curve there (no polyline
     * interpolation of the dense points — output precision is limited only by float
     * math). Interior points receive the same Gaussian jitter as elsewhere; endpoints
     * are exact.
     */
    fun sampleCubicBezierArcLength(
        p0: SampledPoint,
        c1: SampledPoint,
        c2: SampledPoint,
        p3: SampledPoint,
        sampleCount: Int,
        jitterPx: Float,
        random: Random
    ): List<SampledPoint> {
        if (sampleCount <= 2) return listOf(p0, p3)

        // 1. Dense pre-sample for the cumulative arc-length table. Dense t is uniform,
        //    so a position inside segment j maps back to t = (j + local)/dense.
        val dense = (sampleCount * 8).coerceAtLeast(128)
        val xs = FloatArray(dense + 1)
        val ys = FloatArray(dense + 1)
        val cumLen = FloatArray(dense + 1)
        xs[0] = p0.x; ys[0] = p0.y
        for (j in 1..dense) {
            val t = j.toFloat() / dense
            val p = evalCubic(p0, c1, c2, p3, t)
            xs[j] = p.x; ys[j] = p.y
            val dx = p.x - xs[j - 1]
            val dy = p.y - ys[j - 1]
            cumLen[j] = cumLen[j - 1] + kotlin.math.sqrt(dx * dx + dy * dy)
        }
        val totalLen = cumLen[dense]
        if (totalLen <= 0f) return sampleCubicBezier(p0, c1, c2, p3, sampleCount, jitterPx, random)

        // 2. Invert the table for each output arc fraction.
        val out = ArrayList<SampledPoint>(sampleCount)
        out.add(p0) // endpoint, no jitter
        val maxJitter = jitterPx * 2f
        var seg = 0
        for (i in 1 until sampleCount - 1) {
            val f = i.toFloat() / (sampleCount - 1).toFloat()
            val target = f * totalLen
            // Monotone forward sweep: targets increase with i, so never rewind.
            while (seg < dense - 1 && cumLen[seg + 1] < target) seg++
            val segStart = cumLen[seg]
            val segLen = cumLen[seg + 1] - segStart
            val local = if (segLen > 0f) ((target - segStart) / segLen).coerceIn(0f, 1f) else 0f
            val t = (seg + local) / dense
            val p = evalCubic(p0, c1, c2, p3, t)
            val jx = (random.nextGaussian().toFloat() * jitterPx).coerceIn(-maxJitter, maxJitter)
            val jy = (random.nextGaussian().toFloat() * jitterPx).coerceIn(-maxJitter, maxJitter)
            out.add(SampledPoint(p.x + jx, p.y + jy))
        }
        out.add(p3) // endpoint, no jitter
        return out
    }

    /** Evaluates the cubic bezier at parameter [t] ∈ [0,1]. */
    private fun evalCubic(
        p0: SampledPoint,
        c1: SampledPoint,
        c2: SampledPoint,
        p3: SampledPoint,
        t: Float
    ): SampledPoint {
        val oneMinusT = 1f - t
        val b0 = oneMinusT * oneMinusT * oneMinusT
        val b1 = 3f * t * oneMinusT * oneMinusT
        val b2 = 3f * t * t * oneMinusT
        val b3 = t * t * t
        return SampledPoint(
            b0 * p0.x + b1 * c1.x + b2 * c2.x + b3 * p3.x,
            b0 * p0.y + b1 * c1.y + b2 * c2.y + b3 * p3.y
        )
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
     * `t = i/(outCount-1)`. Map `t → progress p` via [flooredEaseOut] with [speedFloor]
     * (attack shaped by [easeScale]); then locate the input point at arc-fraction `p` by
     * linear interpolation over the (arc-uniform) input list. Because `p` is monotone in
     * `t`, a forward sweep suffices — O(n) total, no per-point binary search.
     *
     * @param input arc-length-evenly-sampled points (e.g. from [sampleCubicBezierArcLength]);
     *        must be non-empty. At least 2 points recommended for meaningful resampling.
     * @param outCount number of output points. Clamped to ≥ 2. The caller typically
     *        matches the input count to keep path resolution constant.
     * @param speedFloor normalized minimum trailing speed ∈ [0,1). 0.85 keeps the finger
     *        crossing any button in <50ms while still visibly "slowing" toward the end.
     * @param easeScale per-swipe attack-shape multiplier forwarded to [flooredEaseOut]
     *        (default 1). See its doc for the realism rationale.
     * @return a new list of [outCount] points; the first and last equal the input's
     *         first and last exactly (endpoints preserved). The input list is untouched.
     */
    fun resampleByTimeProgress(
        input: List<SampledPoint>,
        outCount: Int,
        speedFloor: Float,
        easeScale: Float = 1f
    ): List<SampledPoint> {
        if (input.isEmpty()) return emptyList()
        if (input.size == 1 || outCount <= 2) return listOf(input.first(), input.last())

        val n = outCount.coerceAtLeast(2)
        val lastInput = input.size - 1
        val out = ArrayList<SampledPoint>(n)
        var lower = 0
        for (i in 0 until n) {
            val t = if (n == 1) 0f else i.toFloat() / (n - 1).toFloat()
            val p = flooredEaseOut(t, speedFloor, easeScale)
            // Locate the input sample at arc-fraction p via linear interpolation.
            // p is monotone in i, so advance the segment cursor without ever rewinding.
            val pos = p * lastInput
            while (lower < lastInput - 1 && pos >= lower + 1) lower++
            val frac = (pos - lower).coerceIn(0f, 1f)
            val a = input[lower]
            val b = input[lower + 1]
            out.add(SampledPoint(a.x + (b.x - a.x) * frac, a.y + (b.y - a.y) * frac))
        }
        return out
    }

    /**
     * Immutable sampled point. A pure-Kotlin analogue of `android.graphics.PointF`
     * so that bezier sampling stays JVM-testable — the `:gesture` module must never
     * grow an Android dependency.
     */
    data class SampledPoint(val x: Float, val y: Float)
}
