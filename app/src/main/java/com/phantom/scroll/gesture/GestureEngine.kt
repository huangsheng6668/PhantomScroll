package com.phantom.scroll.gesture

import android.graphics.Path
import com.phantom.scroll.data.ScrollDirection
import java.util.Random

/**
 * Geometric layout, timing and segmentation of a human-like vertical swipe.
 *
 * Fully decoupled from Android SDK for pure-JVM unit testing. In addition to the
 * endpoints/control point/duration, it carries the two ratios that drive the
 * asymmetric "short-accel / long-decel" speed curve (see [SpeedCurve.mapTimeToProgress]).
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
    val controlX: Float,
    val controlY: Float,
    val endX: Float,
    val endY: Float,
    val duration: Long,
    val accelDistanceRatio: Float,
    val accelDurationRatio: Float
)

/**
 * A segmented swipe plan consumed by [android.accessibilityservice.AccessibilityService.dispatchGesture].
 *
 * The path is split into a fast acceleration phase and a slow deceleration phase,
 * dispatched as two **continuous** strokes (the second via
 * `StrokeDescription.continuedStroke`). This is the only way to vary finger speed
 * within a single gesture, because Android interpolates *within* a stroke at constant
 * arc-length speed.
 *
 * @property accelPath path of the acceleration phase. **Borrowed reference** —
 *           invalidated by the next call to [GestureEngine.generateGesturePlan].
 * @property decelPath path of the deceleration phase. **Borrowed reference** — same caveat.
 * @property accelDuration duration (ms) of the acceleration phase.
 * @property decelDuration duration (ms) of the deceleration phase.
 */
class GesturePlan(
    val accelPath: Path,
    val decelPath: Path,
    val accelDuration: Long,
    val decelDuration: Long
)

/**
 * A single-path fallback used in degraded mode (see ScrollOrchestrator). Keeps the
 * organic sampling jitter and randomized start point, but drops the speed-curve
 * segmentation — yielding a constant-speed stroke that is maximally ROM-compatible.
 *
 * @property path **Borrowed reference**, invalidated by subsequent engine calls.
 */
class SinglePathResult(
    val path: Path,
    val duration: Long
)

/**
 * Engine producing organic vertical scroll gestures.
 *
 * Caches internal [Path] instances (object pooling) to avoid per-iteration allocation
 * and GC jitter during continuous loops. Borrowed-reference semantics are documented
 * on [GesturePlan] and [SinglePathResult]: callers must finish dispatching before
 * requesting the next path.
 */
class GestureEngine {

    // Pooled Path objects — never reallocated; reset() before each rebuild.
    private val reusableAccelPath = Path()
    private val reusableDecelPath = Path()
    private val reusableSinglePath = Path()
    private val random = Random()

    /** Minimum absolute number of samples in a single path segment. */
    private val minSegmentSamples = 6

    /**
     * Generates a two-phase [GesturePlan] implementing the asymmetric speed curve.
     *
     * Heavy math runs on [kotlinx.coroutines.Dispatchers.Default] (caller's responsibility).
     */
    fun generateGesturePlan(
        screenWidth: Int,
        screenHeight: Int,
        distanceRatio: Float,
        durationMs: Long,
        direction: ScrollDirection = ScrollDirection.UP
    ): GesturePlan {
        val points = calculateGesturePoints(
            screenWidth, screenHeight, distanceRatio, durationMs, random, direction
        )

        val totalSamples = SAMPLE_COUNT
        // Samples per phase proportional to distance covered (denser sampling where
        // the finger travels further). Each phase keeps enough points to look smooth.
        val accelSamples = (totalSamples * points.accelDistanceRatio)
            .toInt().coerceAtLeast(minSegmentSamples)
        val decelSamples = (totalSamples - accelSamples).coerceAtLeast(minSegmentSamples)

        // Sample each phase independently along the parameter range it covers.
        val p0 = SpeedCurve.SampledPoint(points.startX, points.startY)
        val p1 = SpeedCurve.SampledPoint(points.controlX, points.controlY)
        val p2 = SpeedCurve.SampledPoint(points.endX, points.endY)
        val accelPts = sampleSubBezier(p0, p1, p2, sStart = 0f, sEnd = points.accelDistanceRatio,
            count = accelSamples, jitterPx = JITTER_PX, random = random)
        val decelPts = sampleSubBezier(p0, p1, p2, sStart = points.accelDistanceRatio, sEnd = 1f,
            count = decelSamples + 1, jitterPx = JITTER_PX, random = random)
        // decelPts[0] duplicates accelPts[last]; drop it so phases chain seamlessly.
        val decelTail = if (decelPts.size > 1) decelPts.subList(1, decelPts.size) else decelPts

        rebuildPolyline(reusableAccelPath, accelPts)
        rebuildPolyline(reusableDecelPath, decelTail)

        val accelDuration = (points.duration.toFloat() * points.accelDurationRatio).toLong()
            .coerceAtLeast(MIN_PHASE_DURATION_MS)
        val decelDuration = (points.duration - accelDuration)
            .coerceAtLeast(MIN_PHASE_DURATION_MS)

        return GesturePlan(
            accelPath = reusableAccelPath,
            decelPath = reusableDecelPath,
            accelDuration = accelDuration,
            decelDuration = decelDuration
        )
    }

    /**
     * Generates a single-path, constant-speed result. Used by the orchestrator's
     * degraded mode when continuous-stroke chaining proves incompatible with a ROM.
     *
     * Retains the bezier shape with sub-pixel sampling jitter, so the swipe still
     * looks organic — only the speed curve is lost.
     */
    fun generateSinglePath(
        screenWidth: Int,
        screenHeight: Int,
        distanceRatio: Float,
        durationMs: Long,
        direction: ScrollDirection = ScrollDirection.UP
    ): SinglePathResult {
        val points = calculateGesturePoints(
            screenWidth, screenHeight, distanceRatio, durationMs, random, direction
        )
        val p0 = SpeedCurve.SampledPoint(points.startX, points.startY)
        val p1 = SpeedCurve.SampledPoint(points.controlX, points.controlY)
        val p2 = SpeedCurve.SampledPoint(points.endX, points.endY)
        val pts = SpeedCurve.sampleBezier(p0, p1, p2, SAMPLE_COUNT, JITTER_PX, random)
        rebuildPolyline(reusableSinglePath, pts)
        return SinglePathResult(path = reusableSinglePath, duration = points.duration)
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

    private fun rebuildPolyline(target: Path, points: List<SpeedCurve.SampledPoint>) {
        target.reset()
        if (points.isEmpty()) return
        val first = points[0]
        target.moveTo(first.x, first.y)
        for (i in 1 until points.size) {
            target.lineTo(points[i].x, points[i].y)
        }
    }

    companion object {
        /** Total samples across the whole swipe (both phases combined). */
        private const val SAMPLE_COUNT = 40

        /** Standard deviation of sub-pixel sampling jitter, in pixels. */
        private const val JITTER_PX = 1.0f

        /** Floor duration (ms) for any single phase; protects against degenerate splits. */
        private const val MIN_PHASE_DURATION_MS = 40L

        /**
         * Default asymmetric profile: 25% of time covers 45% of distance.
         * Acceleration is ~1.8× mean speed, deceleration ~0.73× — ratio ≈ 2.5:1,
         * matching AGENTS.md §4 ("加速短急 / 减速长缓").
         */
        const val DEFAULT_ACCEL_DURATION_RATIO = 0.25f
        const val DEFAULT_ACCEL_DISTANCE_RATIO = 0.45f

        /**
         * Samples a sub-arc `s ∈ [sStart, sEnd]` of the quadratic bezier defined by
         * control points [p0], [p1], [p2], returning [count] points with Gaussian jitter
         * applied to interior samples (endpoints untouched). `sStart == 0` yields the
         * true bezier start [p0]; `sEnd == 1` yields the true end [p2].
         */
        private fun sampleSubBezier(
            p0: SpeedCurve.SampledPoint,
            p1: SpeedCurve.SampledPoint,
            p2: SpeedCurve.SampledPoint,
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
                val b0 = oneMinusS * oneMinusS
                val b1 = 2f * s * oneMinusS
                val b2 = s * s
                val x = b0 * p0.x + b1 * p1.x + b2 * p2.x
                val y = b0 * p0.y + b1 * p1.y + b2 * p2.y
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
         * Three humanization fixes vs. the legacy implementation:
         *  1. **Start Y is randomized** across the safe-zone slack (not pinned to an edge),
         *     while still guaranteeing the full [distanceRatio] of travel fits within bounds.
         *     This also eliminates the old `coerceAtLeast(safeTop)` truncation that silently
         *     shortened high-ratio swipes.
         *  2. **End X stays near start X** (finger drifts very little horizontally),
         *     instead of two independent Gaussian draws that could make the finger "drift".
         *  3. Bio-noise is preserved on distance (±8%) and duration (±7%).
         */
        fun calculateGesturePoints(
            screenWidth: Int,
            screenHeight: Int,
            distanceRatio: Float,
            durationMs: Long,
            random: Random,
            direction: ScrollDirection = ScrollDirection.UP
        ): GesturePoints {
            // 1. Safe zone (avoid status bar + navigation bar)
            val safeTop = screenHeight * 0.15f
            val safeBottom = screenHeight * 0.85f
            val safeHeight = safeBottom - safeTop

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
            val headroom = (random.nextFloat() * slack + microJitter).coerceIn(0f, slack)
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

            // 5. Quadratic Bezier control point P1, at the geometric midpoint with
            //    a slightly larger horizontal deviation to give a gentle arc.
            val controlX = centerX + random.nextGaussian().toFloat() * (screenWidth * 0.05f)
            val controlY = (startY + endY) * 0.5f

            // 6. Duration with Bio-Noise (±7%), clamped to the engine's supported range.
            val actualDuration = addNoise(durationMs.toFloat(), 0.07f)
                .toLong().coerceIn(150, 1500)

            return GesturePoints(
                startX = startX,
                startY = startY,
                controlX = controlX,
                controlY = controlY,
                endX = endX,
                endY = endY,
                duration = actualDuration,
                accelDistanceRatio = DEFAULT_ACCEL_DISTANCE_RATIO,
                accelDurationRatio = DEFAULT_ACCEL_DURATION_RATIO
            )
        }
    }
}
