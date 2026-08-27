package com.phantom.scroll.gesture

import java.util.Random
import kotlin.math.roundToInt

/**
 * Geometric layout, timing and segmentation of a human-like vertical swipe.
 *
 * Fully decoupled from Android (pure JVM, part of the independent `:gesture` module)
 * for high-coverage unit testing. In addition to the endpoints/control points/duration,
 * it carries the two ratios that drive the asymmetric "short-accel / long-decel" speed
 * curve (see [SpeedCurve.mapTimeToProgress]).
 *
 * The trajectory is a CUBIC bezier with two control points ([cubicControlX1]/[cubicControlY1]
 * and [cubicControlX2]/[cubicControlY2]): for a C-arc both sit at the geometric midpoint;
 * for an S-curve they sit at s=1/3 and s=2/3 with opposite lateral offsets (the
 * thumb-pivot signature). Both engine paths (continuous / legacy two-segment) sample
 * this same cubic curve.
 *
 * @property duration total stroke duration (ms) **before** splitting into phases.
 * @property accelDistanceRatio fraction of the total swipe distance covered during
 *           the acceleration phase. The acceleration phase is *fast*, so this must
 *           exceed [accelDurationRatio].
 * @property accelDurationRatio fraction of the total duration spent accelerating.
 */
data class GesturePoints(
    val startX: Float,
    val startY: Float,
    val cubicControlX1: Float,
    val cubicControlY1: Float,
    val cubicControlX2: Float,
    val cubicControlY2: Float,
    val endX: Float,
    val endY: Float,
    val duration: Long,
    val accelDistanceRatio: Float,
    val accelDurationRatio: Float
)

/**
 * A **single continuous-stroke** swipe plan — the modern, seam-free way to express a
 * human-like speed curve (see [GestureEngine.generateContinuousPlan]).
 *
 * Unlike [GesturePlan] (two `continueStroke` segments with a velocity discontinuity at
 * the seam), this dispatches as **one** stroke. The acceleration / gentle-deceleration
 * profile is encoded *inside the point sequence itself* by non-uniform spacing (see
 * [SpeedCurve.resampleByTimeProgress]), and a short run of duplicated leading points
 * encodes a touch-down dwell (the finger lands, settles briefly, then moves). This
 * eliminates both misdetection roots at once: no seam micro-pause, and a floored
 * trailing speed that never reads as a deliberate tap on an ad/诱导 button.
 *
 * The consumer (`:app`'s GestureDescriptionFactory) flattens [points] into exactly one
 * `StrokeDescription` of [duration] ms.
 *
 * @property points the ordered swipe polyline. **Borrowed reference** — invalidated by
 *           the next call to [GestureEngine.generateContinuousPlan].
 * @property duration total stroke duration (ms).
 * @property speedFloor the normalized minimum trailing speed this plan was built with
 *           (exposed for unit-test assertions that it matches the requested band).
 * @property dwellPointCount number of leading duplicated (stationary) points encoding
 *           the touch-down dwell; 0..[GestureEngine.DWELL_MAX_POINTS].
 */
class ContinuousPlan(
    val points: List<SpeedCurve.SampledPoint>,
    val duration: Long,
    val speedFloor: Float,
    val dwellPointCount: Int
)

/**
 * A segmented swipe plan consumed by `:app`'s GestureDescriptionFactory.
 *
 * The polyline is split into a fast acceleration phase and a slow deceleration phase,
 * dispatched as two **continuous** strokes (the second via
 * `StrokeDescription.continueStroke`). This is the only way to vary finger speed
 * within a single gesture on the legacy path, because Android interpolates *within* a
 * stroke at constant arc-length speed.
 *
 * @property accelPoints polyline of the acceleration phase. **Borrowed reference** —
 *           invalidated by the next call to [GestureEngine.generateGesturePlan].
 * @property decelPoints polyline of the deceleration phase. **Borrowed reference** —
 *           same caveat.
 * @property accelDuration duration (ms) of the acceleration phase.
 * @property decelDuration duration (ms) of the deceleration phase.
 */
class GesturePlan(
    val accelPoints: List<SpeedCurve.SampledPoint>,
    val decelPoints: List<SpeedCurve.SampledPoint>,
    val accelDuration: Long,
    val decelDuration: Long
)

/**
 * Engine producing organic vertical scroll gestures. 100% Android-free — it plans in
 * pure point space; the `:app` side converts plans into `GestureDescription` strokes
 * (with pooled `android.graphics.Path` objects, per the zero-GC spec).
 *
 * Heavy math is intended to run on [kotlinx.coroutines.Dispatchers.Default]
 * (caller's responsibility). The engine instance is NOT thread-safe: confine it to one
 * coroutine at a time (the orchestrator generates, dispatches, then generates again —
 * strictly sequential). Borrowed-reference semantics are documented on
 * [ContinuousPlan] and [GesturePlan]: callers must finish dispatching before requesting
 * the next plan.
 *
 * @param random injectable entropy source; tests pass a seeded [Random] for
 *        deterministic plans.
 */
class GestureEngine(private val random: Random = Random()) {

    /** Per-session finger-habit model: habitual start band drift + warm-up ramp. */
    private val sessionMotion = SessionMotion()

    /**
     * Marks a new scrolling session (pause→resume). Resets the warm-up ramp so the
     * first swipes re-enter gently; the habitual start anchor is deliberately kept —
     * a pause does not erase where the thumb likes to rest.
     */
    fun beginSession() = sessionMotion.beginSession()

    /** Minimum absolute number of samples in a single path segment. */
    private val minSegmentSamples = 6

    /**
     * Generates a [ContinuousPlan]: a **single stroke** whose internal point spacing
     * encodes a human-like accelerate-then-gently-decelerate profile, with a floored
     * trailing speed so the finger never dwells slowly across an ad/诱导 button.
     *
     * This is the preferred path for the orchestrator: no `continueStroke` seam
     * (hence no ROM incompatibility from stroke chaining) and no slow-drag tail (hence
     * no ad misdetection).
     *
     * Pipeline (all pure JVM):
     *  1. [SpeedCurve.sampleCubicBezierArcLength] — arc-length-uniform samples of the
     *     C-arc / S-curve cubic, with per-swipe-randomized jitter amplitude so no two
     *     consecutive swipes share the same texture.
     *  2. [SpeedCurve.resampleByTimeProgress] — redistribute to time-even spacing with
     *     a per-swipe attack scale ([EASE_SCALE_SIGMA]), encoding the floored speed
     *     curve into the single polyline.
     *  3. Touch-down dwell — prepend a few duplicated start points so the finger
     *     "lands and settles" before moving, like every real touch.
     */
    fun generateContinuousPlan(
        screenWidth: Int,
        screenHeight: Int,
        distanceRatio: Float,
        durationMs: Long,
        direction: ScrollDirection = ScrollDirection.UP
    ): ContinuousPlan {
        // Session pacing: habitual start position + warm-up ramp (see SessionMotion).
        val pacing = sessionMotion.nextSwipe(random)
        val points = calculateGesturePoints(
            screenWidth, screenHeight, distanceRatio,
            (durationMs * pacing.warmupDurationMultiplier).toLong(),
            random, direction, pacing.headroomFraction
        )
        val floor = speedFloorFor(points.duration)
        // Fast bands get denser sampling: more MOVE events per stroke so a ROM that
        // drops/coalesces input can never make the gesture look like a stationary tap.
        val sampleCount = if (points.duration < FAST_BAND_CEILING_MS) FAST_SAMPLE_COUNT else SAMPLE_COUNT

        val p0 = SpeedCurve.SampledPoint(points.startX, points.startY)
        val c1 = SpeedCurve.SampledPoint(points.cubicControlX1, points.cubicControlY1)
        val c2 = SpeedCurve.SampledPoint(points.cubicControlX2, points.cubicControlY2)
        val p3 = SpeedCurve.SampledPoint(points.endX, points.endY)
        val arcPts = SpeedCurve.sampleCubicBezierArcLength(p0, c1, c2, p3, sampleCount, perSwipeJitterPx(), random)

        // Per-swipe ease variety: snappier or lazier attacks break the "identical
        // normalized speed curve on every swipe" statistical tell. The trailing floor
        // is unaffected by the scale (see SpeedCurve.flooredEaseOut).
        val timePts = SpeedCurve.resampleByTimeProgress(arcPts, sampleCount, floor, perSwipeEaseScale())

        val dwell = touchDownDwellPoints(timePts.size)
        val out = ArrayList<SpeedCurve.SampledPoint>(timePts.size + dwell)
        repeat(dwell + 1) { out.add(timePts.first()) }
        for (i in 1 until timePts.size) out.add(timePts[i])
        return ContinuousPlan(
            points = out,
            duration = points.duration,
            speedFloor = floor,
            dwellPointCount = dwell
        )
    }

    /**
     * Generates a two-phase [GesturePlan] implementing the asymmetric speed curve via
     * two `continueStroke` segments.
     *
     * **Legacy fallback.** Prefer [generateContinuousPlan]: it expresses the same
     * accelerate/decelerate profile in a *single* stroke (no seam, no slow-drag tail),
     * which both improves ROM compatibility and avoids ad/诱导-button misdetection.
     * This two-segment path is retained only as a last-resort fallback should the
     * single-stroke plan ever prove incompatible with an exotic ROM.
     */
    fun generateGesturePlan(
        screenWidth: Int,
        screenHeight: Int,
        distanceRatio: Float,
        durationMs: Long,
        direction: ScrollDirection = ScrollDirection.UP
    ): GesturePlan {
        // Session pacing: habitual start position + warm-up ramp (see SessionMotion).
        val pacing = sessionMotion.nextSwipe(random)
        val points = calculateGesturePoints(
            screenWidth, screenHeight, distanceRatio,
            (durationMs * pacing.warmupDurationMultiplier).toLong(),
            random, direction, pacing.headroomFraction
        )

        val totalSamples = if (points.duration < FAST_BAND_CEILING_MS) FAST_SAMPLE_COUNT else SAMPLE_COUNT
        // Samples per phase proportional to distance covered (denser sampling where
        // the finger travels further). Each phase keeps enough points to look smooth.
        val accelSamples = (totalSamples * points.accelDistanceRatio)
            .toInt().coerceAtLeast(minSegmentSamples)
        val decelSamples = (totalSamples - accelSamples).coerceAtLeast(minSegmentSamples)

        // Sample each phase independently along the parameter range it covers.
        val p0 = SpeedCurve.SampledPoint(points.startX, points.startY)
        val c1 = SpeedCurve.SampledPoint(points.cubicControlX1, points.cubicControlY1)
        val c2 = SpeedCurve.SampledPoint(points.cubicControlX2, points.cubicControlY2)
        val p3 = SpeedCurve.SampledPoint(points.endX, points.endY)
        val jitterPx = perSwipeJitterPx()
        val accelPts = sampleSubCubicBezier(p0, c1, c2, p3, sStart = 0f, sEnd = points.accelDistanceRatio,
            count = accelSamples, jitterPx = jitterPx, random = random)
        val decelPts = sampleSubCubicBezier(p0, c1, c2, p3, sStart = points.accelDistanceRatio, sEnd = 1f,
            count = decelSamples + 1, jitterPx = jitterPx, random = random)
        // decelPts[0] duplicates accelPts[last]; drop it so phases chain seamlessly.
        val decelTail = if (decelPts.size > 1) decelPts.subList(1, decelPts.size) else decelPts

        val accelDuration = (points.duration.toFloat() * points.accelDurationRatio).toLong()
            .coerceAtLeast(MIN_PHASE_DURATION_MS)
        val decelDuration = (points.duration - accelDuration)
            .coerceAtLeast(MIN_PHASE_DURATION_MS)

        return GesturePlan(
            accelPoints = accelPts,
            decelPoints = decelTail,
            accelDuration = accelDuration,
            decelDuration = decelDuration
        )
    }

    /**
     * Adds Gaussian (normal distribution) noise to a base value.
     * The noise magnitude is ±[ratio] of the base value, clamped to ±2σ.
     * Kept public so the orchestrator can noise the inter-swipe interval consistently.
     */
    fun addBioNoise(baseValue: Float, ratio: Float): Float {
        val factor = (random.nextGaussian().toFloat() * ratio).coerceIn(-ratio * 2, ratio * 2)
        return baseValue * (1f + factor)
    }

    /** Randomizes the per-swipe jitter σ within [JITTER_MIN_PX, JITTER_MAX_PX]. */
    private fun perSwipeJitterPx(): Float =
        JITTER_MIN_PX + random.nextFloat() * (JITTER_MAX_PX - JITTER_MIN_PX)

    /**
     * Per-swipe attack-shape scale for [SpeedCurve.flooredEaseOut]: gaussian around 1,
     * 2σ-clamped to [EASE_SCALE_MIN, EASE_SCALE_MAX]. >1 = a snappier swipe than
     * usual, <1 = a lazier one — the speed-curve variety that breaks the "every swipe
     * has the same normalized profile" tell.
     */
    private fun perSwipeEaseScale(): Float =
        (1f + random.nextGaussian().toFloat() * EASE_SCALE_SIGMA)
            .coerceIn(EASE_SCALE_MIN, EASE_SCALE_MAX)

    /**
     * Number of duplicated leading points encoding the touch-down dwell for a polyline
     * of [totalPoints] points. A real finger lands, settles for a few dozen ms, then
     * moves; an injected gesture that starts moving on the very first sample is a
     * subtle tell. The dwell share (stationary time ÷ total duration) is gaussian
     * around [DWELL_SHARE], clamped to [DWELL_MIN_SHARE, DWELL_MAX_SHARE] and to at
     * most [DWELL_MAX_POINTS] points.
     */
    private fun touchDownDwellPoints(totalPoints: Int): Int {
        val share = (DWELL_SHARE + random.nextGaussian().toFloat() * DWELL_SIGMA)
            .coerceIn(DWELL_MIN_SHARE, DWELL_MAX_SHARE)
        // dwellShare = k / (n + k)  ⇒  k = share·n / (1 - share)
        val k = (share * totalPoints / (1f - share)).roundToInt()
        return k.coerceIn(0, DWELL_MAX_POINTS)
    }

    companion object {
        /** Total samples across the whole swipe for normal/slow bands. */
        private const val SAMPLE_COUNT = 40

        /**
         * Denser sampling for the fast bands: 64 points instead of 40. A fast stroke
         * delivers MOVE events every few ms, so even if an OEM input pipeline drops
         * or coalesces some events, the app still observes continuous movement —
         * DOWN→UP can never degrade into what looks like a stationary tap on an ad.
         */
        private const val FAST_SAMPLE_COUNT = 64

        // ---- Per-swipe jitter amplitude (organic texture variety) -----------------
        /** Minimum per-axis jitter σ per swipe — smoother swipes. */
        private const val JITTER_MIN_PX = 0.6f
        /** Maximum per-axis jitter σ per swipe — wobblier swipes. */
        private const val JITTER_MAX_PX = 1.6f

        // ---- Trajectory shape variety (C-arc vs thumb-pivot S-curve) -------------
        /** Probability that a swipe is an S-curve rather than a C-arc. */
        private const val S_CURVE_PROBABILITY = 0.4f
        /** Lateral amplitude of the S-curve control points, as screen-width ratios. */
        private const val S_CURVE_MIN_AMP_RATIO = 0.008f
        private const val S_CURVE_MAX_AMP_RATIO = 0.030f

        // ---- Touch-down dwell (finger lands, settles, then moves) ----------------
        /** Mean share of the stroke duration spent stationary at the landing point. */
        private const val DWELL_SHARE = 0.05f
        /** σ of the dwell share. */
        private const val DWELL_SIGMA = 0.02f
        private const val DWELL_MIN_SHARE = 0.015f
        private const val DWELL_MAX_SHARE = 0.11f
        /** Hard cap on duplicated leading points (bounds the dwell even on huge counts). */
        const val DWELL_MAX_POINTS = 6

        // ---- Per-swipe speed-curve attack variety ---------------------------------
        /** σ of the per-swipe ease-scale wobble around 1.0. */
        private const val EASE_SCALE_SIGMA = 0.06f
        private const val EASE_SCALE_MIN = 0.88f
        private const val EASE_SCALE_MAX = 1.18f

        // ---- Speed-coupling noise ---------------------------------------------------
        /** σ of the per-swipe finger-speed wobble (duration ∝ travel ÷ speed). */
        private const val SPEED_WOBBLE_SIGMA = 0.05f

        /** Floor duration (ms) for any single phase; protects against degenerate splits. */
        private const val MIN_PHASE_DURATION_MS = 40L

        /**
         * Default asymmetric profile: 25% of time covers 45% of distance.
         * Acceleration is ~1.8× mean speed, deceleration ~0.73× — ratio ≈ 2.5:1,
         * matching the spec ("加速短急 / 减速长缓").
         *
         * Used only by the legacy two-segment [generateGesturePlan] fallback. The
         * primary [generateContinuousPlan] path ignores these and drives its speed
         * curve via [speedFloorFor] + [SpeedCurve.flooredEaseOut] instead.
         */
        const val DEFAULT_ACCEL_DURATION_RATIO = 0.25f
        const val DEFAULT_ACCEL_DISTANCE_RATIO = 0.45f

        // ---- Speed-adaptive trailing-speed floor (anti-misdetection) -----------
        //
        // The continuous plan encodes its speed curve via non-uniform point spacing
        // (see SpeedCurve.resampleByTimeProgress + flooredEaseOut). The floor on the
        // *trailing* speed is chosen per band so the finger never crawls across an
        // ad/诱导 button long enough to be read as a deliberate press (~50-80ms
        // contact threshold; Android tap timeouts run 100-300ms):
        //
        // 极速 / 快速 (<700ms):   floor 0.93 — ~12ms across any button (hardened
        //                          after field reports of ad misclicks: the tail
        //                          barely decelerates, so a lift on an ad banner is
        //                          a brief graze, never a dwell).
        // 中速 (700–1049ms):       floor 0.88 — ~19ms (safe).
        // 慢速 (≥1050ms):          floor 0.82 — ~29ms; a long dwell here is intentional
        //                          reading, not a tap, but the floor stays far above
        //                          the ~0.3px/ms that would read as a press.
        //
        // Bands align with ParamSteps.toSpeedLabel boundaries (极速<400 / 快速<700 /
        // 中速<1050 / 慢速≥1050).
        private const val FAST_BAND_CEILING_MS = 700L
        private const val NORMAL_BAND_CEILING_MS = 1050L

        private const val FAST_SPEED_FLOOR = 0.93f
        private const val NORMAL_SPEED_FLOOR = 0.88f
        private const val SLOW_SPEED_FLOOR = 0.82f

        /**
         * Returns the normalized minimum trailing-speed floor appropriate for a swipe
         * of the given *post-noise* duration. Faster swipes get a higher floor (they
         * must keep moving briskly past buttons); the slow reading band may ease off.
         */
        internal fun speedFloorFor(actualDurationMs: Long): Float = when {
            actualDurationMs < FAST_BAND_CEILING_MS -> FAST_SPEED_FLOOR
            actualDurationMs < NORMAL_BAND_CEILING_MS -> NORMAL_SPEED_FLOOR
            else -> SLOW_SPEED_FLOOR
        }

        /**
         * Samples a sub-arc `s ∈ [sStart, sEnd]` of the cubic bezier defined by
         * endpoints [p0], [p3] and control points [c1], [c2], returning [count] points
         * with Gaussian jitter applied to interior samples (endpoints untouched).
         * `sStart == 0` yields the true bezier start [p0]; `sEnd == 1` yields the true
         * end [p3].
         */
        private fun sampleSubCubicBezier(
            p0: SpeedCurve.SampledPoint,
            c1: SpeedCurve.SampledPoint,
            c2: SpeedCurve.SampledPoint,
            p3: SpeedCurve.SampledPoint,
            sStart: Float,
            sEnd: Float,
            count: Int,
            jitterPx: Float,
            random: Random
        ): List<SpeedCurve.SampledPoint> {
            val n = count.coerceAtLeast(2)
            val out = ArrayList<SpeedCurve.SampledPoint>(n)
            val maxJitter = jitterPx * 2f
            for (i in 0 until n) {
                val frac = if (n == 1) 0f else i.toFloat() / (n - 1).toFloat()
                val s = sStart + (sEnd - sStart) * frac
                val oneMinusS = 1f - s
                val b0 = oneMinusS * oneMinusS * oneMinusS
                val b1 = 3f * s * oneMinusS * oneMinusS
                val b2 = 3f * s * s * oneMinusS
                val b3 = s * s * s
                val x = b0 * p0.x + b1 * c1.x + b2 * c2.x + b3 * p3.x
                val y = b0 * p0.y + b1 * c1.y + b2 * c2.y + b3 * p3.y
                // Endpoints of the *sub-arc* are also left unjittered so the two
                // phases meet seamlessly at the split point.
                if (i == 0 || i == n - 1) {
                    out.add(SpeedCurve.SampledPoint(x, y))
                } else {
                    val jx = (random.nextGaussian().toFloat() * jitterPx).coerceIn(-maxJitter, maxJitter)
                    val jy = (random.nextGaussian().toFloat() * jitterPx).coerceIn(-maxJitter, maxJitter)
                    out.add(SpeedCurve.SampledPoint(x + jx, y + jy))
                }
            }
            return out
        }

        /**
         * Pure mathematical calculation of swipe coordinates, timing and phase ratios.
         * Zero Android SDK dependencies — testable on plain JVM.
         *
         * Humanization properties:
         *  1. **Start Y is randomized** across the safe-zone slack (not pinned to an edge),
         *     while still guaranteeing the full [distanceRatio] of travel fits within bounds.
         *     When [headroomFraction] is supplied (production path via [SessionMotion]),
         *     the start position is anchored in the reader's habitual band instead of
         *     jumping uniformly.
         *  2. **End X stays near start X** (finger drifts very little horizontally).
         *  3. Bio-noise is preserved on distance (±8%); duration is **speed-coupled** to the
         *     actual travel with a ±5% wobble, so the finger keeps a roughly constant speed
         *     instead of compounding two independent noises into ~16% speed spikes.
         *
         * @param headroomFraction where within the travel slack the swipe starts,
         *        ∈ [0,1]; `null` falls back to the legacy uniform-random draw (tests).
         */
        fun calculateGesturePoints(
            screenWidth: Int,
            screenHeight: Int,
            distanceRatio: Float,
            durationMs: Long,
            random: Random,
            direction: ScrollDirection = ScrollDirection.UP,
            headroomFraction: Float? = null
        ): GesturePoints {
            // 1. Safe zone (avoid status bar + navigation bar) — geometry owned by
            //    [SafeZone], the single authority shared with screen adaptation and
            //    the overlay's px labels.
            val safeTop = SafeZone.top(screenHeight)
            val safeBottom = SafeZone.bottom(screenHeight)
            val safeHeight = SafeZone.height(screenHeight)

            fun addNoise(base: Float, ratio: Float): Float {
                val factor = (random.nextGaussian().toFloat() * ratio).coerceIn(-ratio * 2, ratio * 2)
                return base * (1f + factor)
            }

            // 2. Scroll distance with Bio-Noise (±8%), clamped to fit the safe zone.
            val baselineDistance = safeHeight * distanceRatio
            val noisyDistance = addNoise(baselineDistance, 0.08f)
                .coerceIn(safeHeight * 0.2f, safeHeight * 0.95f)

            // 3. Start/end Y. The start point is randomized within the slack so the
            //    finger doesn't always lift off from the exact same edge — yet the
            //    full [noisyDistance] always fits, so high ratios are never truncated.
            //
            //    A small finger-landing jitter is added on top of the slack-based
            //    headroom, then the whole offset is clamped back into [0, slack] so the
            //    endpoints can never leave the safe zone — even at distanceRatio near 0.95
            //    where slack is tiny.
            //
            //    UP   (default): finger moves bottom→top (start below end).
            //    DOWN          : finger moves top→bottom (start above end).
            val slack = (safeHeight - noisyDistance).coerceAtLeast(0f)
            val microJitter = addNoise(screenHeight * 0.02f, 0.1f)
            // SessionMotion supplies the headroom position in slack-fraction space;
            // null keeps the legacy uniform draw for direct/test callers.
            val headroom = ((headroomFraction ?: random.nextFloat()) * slack + microJitter)
                .coerceIn(0f, slack)
            val startY: Float
            val endY: Float
            if (direction == ScrollDirection.DOWN) {
                startY = safeTop + headroom
                endY = startY + noisyDistance
            } else {
                startY = safeTop + headroom + noisyDistance
                endY = startY - noisyDistance
            }

            // 4. X: start near center with a small thumb-landing offset; end stays
            //    close to start (real fingers barely drift horizontally during a swipe).
            val centerX = screenWidth * 0.5f
            val startX = centerX + random.nextGaussian().toFloat() * (screenWidth * 0.02f)
            val endX = startX + random.nextGaussian().toFloat() * (screenWidth * 0.01f)

            // 5. Trajectory shape — cubic bezier with two control points:
            //    - C-arc (~60%): both controls at the geometric midpoint with a gentle
            //      lateral deviation — the classic smooth arc.
            //    - S-curve (~40%): thumb-pivot realism — drift one way at s=1/3 and back
            //      the other way at s=2/3, like a real thumb rotating around its joint.
            //    Draw order is direction-independent so UP/DOWN with the same seed
            //    remain exact vertical mirrors.
            val midX = centerX + random.nextGaussian().toFloat() * (screenWidth * 0.05f)
            val midY = (startY + endY) * 0.5f
            val sCurve = random.nextFloat() < S_CURVE_PROBABILITY
            val c1X: Float
            val c1Y: Float
            val c2X: Float
            val c2Y: Float
            if (sCurve) {
                val amp = screenWidth * (S_CURVE_MIN_AMP_RATIO +
                    random.nextFloat() * (S_CURVE_MAX_AMP_RATIO - S_CURVE_MIN_AMP_RATIO))
                val sign = if (random.nextBoolean()) 1f else -1f
                c1X = startX + (endX - startX) / 3f + sign * amp
                c1Y = startY + (endY - startY) / 3f
                c2X = startX + 2f * (endX - startX) / 3f - sign * amp
                c2Y = startY + 2f * (endY - startY) / 3f
            } else {
                c1X = midX
                c1Y = midY
                c2X = midX
                c2Y = midY
            }

            // 6. Duration is SPEED-COUPLED to the actual travel: the finger keeps a
            //    roughly constant speed with only a ±5% wobble, so a noise-stretched
            //    swipe takes proportionally longer. (Independent ±7% duration noise
            //    could compound with ±8% distance noise into ~16% speed spikes — a
            //    robot tell that this coupling removes.)
            //
            //    Anti-misclick hardening stays: in the fast bands the duration is NEVER
            //    shortened below the requested value — a shorter stroke approaches the
            //    WebView tap window (~300ms) and looks more like a deliberate tap, which
            //    is exactly what opens ads. Fast swipes only stretch (up to +10%).
            val speedWobble = (random.nextGaussian().toFloat() * SPEED_WOBBLE_SIGMA)
                .coerceIn(-SPEED_WOBBLE_SIGMA * 2, SPEED_WOBBLE_SIGMA * 2)
            val coupledDuration = durationMs * (noisyDistance / baselineDistance) * (1f + speedWobble)
            val actualDuration = if (durationMs < FAST_BAND_CEILING_MS) {
                coupledDuration.coerceIn(durationMs.toFloat(), durationMs * 1.10f)
            } else {
                coupledDuration
            }.toLong().coerceIn(150, 1500)

            // 7. Phase ratios here are only consumed by the legacy two-segment
            //    [generateGesturePlan] fallback. The primary path
            //    [generateContinuousPlan] ignores these ratios and drives its speed
            //    curve via [speedFloorFor] + SpeedCurve.flooredEaseOut instead.
            return GesturePoints(
                startX = startX,
                startY = startY,
                cubicControlX1 = c1X,
                cubicControlY1 = c1Y,
                cubicControlX2 = c2X,
                cubicControlY2 = c2Y,
                endX = endX,
                endY = endY,
                duration = actualDuration,
                accelDurationRatio = DEFAULT_ACCEL_DURATION_RATIO,
                accelDistanceRatio = DEFAULT_ACCEL_DISTANCE_RATIO
            )
        }
    }
}
