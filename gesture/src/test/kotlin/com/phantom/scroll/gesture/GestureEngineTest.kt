package com.phantom.scroll.gesture

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class GestureEngineTest {

    @Test
    fun testCalculateGesturePoints_basicVerification() {
        val screenWidth = 1080
        val screenHeight = 2400
        val distanceRatio = 0.75f
        val durationMs = 500L
        val random = Random(42) // Fixed seed for reproducibility where needed, or checking ranges

        val points = GestureEngine.calculateGesturePoints(
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            distanceRatio = distanceRatio,
            durationMs = durationMs,
            random = random
        )

        // Safe zone limits:
        // safeTop = 2400 * 0.15 = 360
        // safeBottom = 2400 * 0.85 = 2040
        val safeTop = screenHeight * 0.15f
        val safeBottom = screenHeight * 0.85f

        // 1. Verify Y coordinates are within safe zones
        assertTrue("startY (${points.startY}) must be below safeTop ($safeTop)", points.startY > safeTop)
        assertTrue("startY (${points.startY}) must be above safeBottom ($safeBottom) plus noise boundary", points.startY <= screenHeight)
        assertTrue("endY (${points.endY}) must be below or equal safeTop ($safeTop)", points.endY >= safeTop)
        assertTrue("endY (${points.endY}) must be above startY (${points.startY})", points.endY < points.startY)

        // 2. Verify X coordinates are around screen center
        val centerX = screenWidth * 0.5f
        assertTrue("startX (${points.startX}) should be near center ($centerX)", Math.abs(points.startX - centerX) < screenWidth * 0.1f)
        assertTrue("endX (${points.endX}) should be near center ($centerX)", Math.abs(points.endX - centerX) < screenWidth * 0.1f)
        assertTrue("cubicControlX1 (${points.cubicControlX1}) should be near center ($centerX)", Math.abs(points.cubicControlX1 - centerX) < screenWidth * 0.2f)
        assertTrue("cubicControlX2 (${points.cubicControlX2}) should be near center ($centerX)", Math.abs(points.cubicControlX2 - centerX) < screenWidth * 0.2f)

        // 3. Verify duration clamping/coercion
        assertTrue("duration (${points.duration}) must be within [200, 1500]", points.duration in 200L..1500L)
    }

    @Test
    fun testCalculateGesturePoints_clampingBounds() {
        val screenWidth = 1080
        val screenHeight = 2400
        val random = Random()

        // Test very small duration
        val pointsShort = GestureEngine.calculateGesturePoints(
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            distanceRatio = 0.5f,
            durationMs = 50L,
            random = random
        )
        assertEquals("Short duration should be coerced to 150ms", 150L, pointsShort.duration)

        // Test very long duration
        val pointsLong = GestureEngine.calculateGesturePoints(
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            distanceRatio = 0.5f,
            durationMs = 5000L,
            random = random
        )
        assertEquals("Long duration should be coerced to 1500ms", 1500L, pointsLong.duration)
    }

    @Test
    fun testCalculateGesturePoints_randomnessAndNoise() {
        val screenWidth = 1080
        val screenHeight = 2400
        val distanceRatio = 0.75f
        val durationMs = 500L
        val random = Random()

        // Invoke multiple times and verify we get different, organic results
        val results = List(5) {
            GestureEngine.calculateGesturePoints(
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                distanceRatio = distanceRatio,
                durationMs = durationMs,
                random = random
            )
        }

        // Verify startY has variation
        val startYs = results.map { it.startY }.toSet()
        assertTrue("Points should have varying startY coordinates due to bio-noise", startYs.size > 1)

        // Verify controlX has variation
        val controlXs = results.map { it.cubicControlX1 }.toSet()
        assertTrue("Points should have varying controlX coordinates due to bio-noise", controlXs.size > 1)

        // Verify duration has variation
        val durations = results.map { it.duration }.toSet()
        assertTrue("Points should have varying durations due to bio-noise", durations.size > 1)
    }

    @Test
    fun direction_up_swipes_from_bottom_to_top() {
        val screenWidth = 1080
        val screenHeight = 2400
        val random = Random(42)
        val points = GestureEngine.calculateGesturePoints(
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            distanceRatio = 0.75f,
            durationMs = 500L,
            random = random,
            direction = ScrollDirection.UP
        )
        assertTrue("UP: endY (${points.endY}) must be above startY (${points.startY})", points.endY < points.startY)
    }

    @Test
    fun direction_down_swipes_from_top_to_bottom() {
        val screenWidth = 1080
        val screenHeight = 2400
        val random = Random(42)
        val points = GestureEngine.calculateGesturePoints(
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            distanceRatio = 0.75f,
            durationMs = 500L,
            random = random,
            direction = ScrollDirection.DOWN
        )
        // DOWN mirrors UP: start near top, end below start
        assertTrue("DOWN: startY (${points.startY}) must be near top (safeTop=${screenHeight * 0.15f})",
            points.startY < screenHeight * 0.5f)
        assertTrue("DOWN: endY (${points.endY}) must be below startY (${points.startY})", points.endY > points.startY)
    }

    // ------------------------------------------------------------------
    // New humanization tests: randomized start Y, X drift convergence,
    // asymmetric speed-curve ratios, and full-distance-within-safe-zone.
    // ------------------------------------------------------------------

    @Test
    fun startY_varies_across_samples() {
        // The start point must not be pinned to an edge; repeated generation should
        // yield visibly different Y positions thanks to the slack-based randomization.
        val random = Random()
        val startYs = List(20) {
            GestureEngine.calculateGesturePoints(
                screenWidth = 1080, screenHeight = 2400,
                distanceRatio = 0.5f, durationMs = 500L,
                random = random, direction = ScrollDirection.UP
            ).startY
        }.toSet()
        assertTrue("startY should vary across samples (got ${startYs.size} unique values)", startYs.size > 5)
    }

    @Test
    fun endX_stays_near_startX() {
        // Real fingers barely drift horizontally during a vertical swipe. The end X
        // must stay close to the start X (within a few percent of screen width).
        val random = Random()
        repeat(50) {
            val pts = GestureEngine.calculateGesturePoints(
                screenWidth = 1080, screenHeight = 2400,
                distanceRatio = 0.6f, durationMs = 400L,
                random = random, direction = ScrollDirection.UP
            )
            val drift = Math.abs(pts.endX - pts.startX)
            assertTrue("Horizontal drift ($drift) exceeds 5% of screen width", drift < 1080 * 0.05f)
        }
    }

    @Test
    fun full_distance_fits_within_safe_zone_at_high_ratio() {
        // Regression guard: with distanceRatio near the slider max, the full noisy
        // distance must still fit between safeTop and safeBottom (no truncation).
        val safeTop = 2400 * 0.15f
        val safeBottom = 2400 * 0.85f
        val random = Random()
        repeat(50) {
            val pts = GestureEngine.calculateGesturePoints(
                screenWidth = 1080, screenHeight = 2400,
                distanceRatio = 0.95f, durationMs = 500L,
                random = random, direction = ScrollDirection.UP
            )
            val travelled = Math.abs(pts.startY - pts.endY)
            assertTrue("startY (${pts.startY}) out of safe zone", pts.startY in safeTop..safeBottom)
            assertTrue("endY (${pts.endY}) out of safe zone", pts.endY in safeTop..safeBottom)
            // Travelled distance must stay close to the requested ratio even after the
            // ±8% bio-noise (clamped at ±2σ ≈ ±16%). At ratio 0.95 the floor is
            // 0.95 × 0.84 ≈ 0.798, so require ≥ 0.75 of safe height to leave headroom.
            assertTrue("Travelled distance ($travelled) too short for ratio 0.95",
                travelled >= (safeBottom - safeTop) * 0.75f)
        }
    }

    @Test
    fun speed_curve_ratios_are_asymmetric() {
        // The acceleration phase must cover more distance than time it consumes,
        // i.e. accelDistanceRatio > accelDurationRatio, so the phase is genuinely "fast".
        val pts = GestureEngine.calculateGesturePoints(
            screenWidth = 1080, screenHeight = 2400,
            distanceRatio = 0.5f, durationMs = 500L,
            random = Random(0), direction = ScrollDirection.UP
        )
        assertTrue(
            "accelDistanceRatio (${pts.accelDistanceRatio}) must exceed accelDurationRatio (${pts.accelDurationRatio})",
            pts.accelDistanceRatio > pts.accelDurationRatio
        )
        assertTrue("accelDurationRatio must be in (0,1)", pts.accelDurationRatio in 0f..1f)
        assertTrue("accelDistanceRatio must be in (0,1)", pts.accelDistanceRatio in 0f..1f)
    }

    @Test
    fun direction_down_mirrors_up_correctly() {
        // UP and DOWN with the same seed must travel the same absolute distance and
        // share the same control-point Y midpoint — i.e. a true vertical mirror.
        val w = 1080
        val h = 2400
        val up = GestureEngine.calculateGesturePoints(w, h, 0.6f, 500L, Random(99), ScrollDirection.UP)
        val down = GestureEngine.calculateGesturePoints(w, h, 0.6f, 500L, Random(99), ScrollDirection.DOWN)
        val upTravel = Math.abs(up.startY - up.endY)
        val downTravel = Math.abs(down.startY - down.endY)
        assertEquals("UP and DOWN must travel the same distance", upTravel, downTravel, 1.0f)
        assertTrue("UP endY must be above startY", up.endY < up.startY)
        assertTrue("DOWN endY must be below startY", down.endY > down.startY)
    }

    // ------------------------------------------------------------------
    // Band-selected trailing-speed floor — the pure-JVM heart of the
    // anti-misdetection hardening.
    // ------------------------------------------------------------------

    @Test
    fun speedFloor_quick_and_fast_band_use_high_floor() {
        // Durations < 700ms (极速 + 快速 bands) must use the high floor so the finger
        // grazes any ad button in ~12ms — anti-misclick hardening for the fast bands.
        assertEquals(GestureEngine.speedFloorFor(300L), 0.93f, 1e-5f) // 极速
        assertEquals(GestureEngine.speedFloorFor(500L), 0.93f, 1e-5f) // 快速
        assertEquals(GestureEngine.speedFloorFor(699L), 0.93f, 1e-5f) // 快速上界
    }

    @Test
    fun speedFloor_normal_band_uses_mid_floor() {
        // 700–1049ms (中速 band): mid floor, still safe.
        assertEquals(GestureEngine.speedFloorFor(700L), 0.88f, 1e-5f)
        assertEquals(GestureEngine.speedFloorFor(850L), 0.88f, 1e-5f)
        assertEquals(GestureEngine.speedFloorFor(1049L), 0.88f, 1e-5f)
    }

    @Test
    fun speedFloor_slow_band_relaxes_floor() {
        // ≥1050ms (慢速 band): the floor relaxes to 0.82 for a gentler curve, since a
        // long dwell there is intentional reading rather than a tap.
        assertEquals(GestureEngine.speedFloorFor(1050L), 0.82f, 1e-5f)
        assertEquals(GestureEngine.speedFloorFor(1200L), 0.82f, 1e-5f)
        assertEquals(GestureEngine.speedFloorFor(1500L), 0.82f, 1e-5f)
    }

    @Test
    fun speedFloor_never_below_global_minimum() {
        // Across the full supported duration range, the floor must stay ≥ 0.82 — the
        // global anti-misdetection minimum. Faster bands go higher, none may drop lower.
        for (durationMs in 150..1500L step 50) {
            val floor = GestureEngine.speedFloorFor(durationMs)
            assertTrue(
                "speedFloor $floor dropped below global minimum 0.82 at durationMs=$durationMs",
                floor >= 0.82f
            )
        }
    }

    @Test
    fun fast_band_duration_is_never_shortened_by_noise() {
        // Anti-misclick regression: a shorter stroke approaches the WebView tap window
        // (~300ms) and looks more like a deliberate tap. Fast-band noise must only
        // stretch the duration (up to +10%), never shorten it below the request.
        val random = Random(11)
        repeat(100) {
            val pts = GestureEngine.calculateGesturePoints(
                screenWidth = 1080, screenHeight = 2400,
                distanceRatio = 0.5f, durationMs = 400L,
                random = random, direction = ScrollDirection.UP
            )
            assertTrue("fast swipe shortened by noise (${pts.duration} < 400)", pts.duration >= 400L)
            assertTrue("fast swipe stretched beyond +10% (${pts.duration})", pts.duration <= 440L)
        }
    }

    // ------------------------------------------------------------------
    // Trajectory-shape variety (C-arc vs thumb-pivot S-curve).
    // ------------------------------------------------------------------

    @Test
    fun trajectory_mixes_c_arc_and_s_curve_shapes() {
        // Deterministic seed: over 200 swipes both shapes must occur —
        // C-arc = both cubic controls coincide (midpoint), S-curve = they differ.
        val random = Random(7)
        var cArcs = 0
        var sCurves = 0
        repeat(200) {
            val pts = GestureEngine.calculateGesturePoints(
                screenWidth = 1080, screenHeight = 2400,
                distanceRatio = 0.5f, durationMs = 500L,
                random = random, direction = ScrollDirection.UP
            )
            if (pts.cubicControlX1 == pts.cubicControlX2 && pts.cubicControlY1 == pts.cubicControlY2) {
                cArcs++
            } else {
                sCurves++
            }
        }
        assertTrue("C-arc shape must occur (got $cArcs)", cArcs > 0)
        assertTrue("S-curve shape must occur (got $sCurves)", sCurves > 0)
    }

    @Test
    fun s_curve_control_points_sit_at_thirds_with_opposite_offsets() {
        val random = Random(7)
        repeat(200) {
            val pts = GestureEngine.calculateGesturePoints(
                screenWidth = 1080, screenHeight = 2400,
                distanceRatio = 0.5f, durationMs = 500L,
                random = random, direction = ScrollDirection.UP
            )
            val isS = pts.cubicControlX1 != pts.cubicControlX2
            if (isS) {
                // Control Ys sit exactly at s=1/3 and s=2/3 of the vertical span.
                val third = (pts.endY - pts.startY) / 3f
                assertEquals(pts.startY + third, pts.cubicControlY1, 1e-4f)
                assertEquals(pts.startY + 2f * third, pts.cubicControlY2, 1e-4f)
                // Lateral deviations from the straight line are opposite (thumb pivot).
                val dev1 = pts.cubicControlX1 - (pts.startX + (pts.endX - pts.startX) / 3f)
                val dev2 = pts.cubicControlX2 - (pts.startX + 2f * (pts.endX - pts.startX) / 3f)
                assertTrue("S-curve deviations must oppose (dev1=$dev1, dev2=$dev2)", dev1 * dev2 < 0f)
            }
        }
    }

    // ------------------------------------------------------------------
    // Session-motion integration (headroom anchor + speed coupling).
    // ------------------------------------------------------------------

    @Test
    fun headroom_fraction_pins_start_y_deterministically() {
        // Same seed & settings, headroom fraction differs by 0.6 — the start Y must
        // shift by exactly 0.6 × slack (micro-jitter draw is identical under the fixed
        // seed, so the delta is exact).
        val screenHeight = 2400
        val safeHeight = screenHeight * 0.7f
        val seed = 42L
        val low = GestureEngine.calculateGesturePoints(
            1080, screenHeight, 0.5f, 500L, Random(seed),
            ScrollDirection.UP, headroomFraction = 0.2f
        )
        val high = GestureEngine.calculateGesturePoints(
            1080, screenHeight, 0.5f, 500L, Random(seed),
            ScrollDirection.UP, headroomFraction = 0.8f
        )
        val travel = Math.abs(low.startY - low.endY)
        val slack = safeHeight - travel
        val delta = high.startY - low.startY
        assertEquals(0.6f * slack, delta, 0.01f)
    }

    // ------------------------------------------------------------------
    // Continuous-stroke plan. Since the engine became a pure-JVM module member
    // (no android.graphics.Path), generateContinuousPlan is directly testable
    // here — previously only its Path-free fragments were.
    // ------------------------------------------------------------------

    @Test
    fun continuous_plan_endpoints_and_count() {
        val engine = GestureEngine(Random(1))
        val plan = engine.generateContinuousPlan(1080, 2400, 0.6f, 500L, ScrollDirection.UP)
        val pts = plan.points
        assertTrue("point count ${pts.size} outside expected band", pts.size in 40..70)
        // Endpoints inside the safe zone; UP direction travels bottom → top.
        val safeTop = 2400 * 0.15f
        val safeBottom = 2400 * 0.85f
        assertTrue(pts.first().y in safeTop..safeBottom)
        assertTrue(pts.last().y in safeTop..safeBottom)
        assertTrue("UP plan must end above its start", pts.last().y < pts.first().y)
        assertEquals(plan.duration in 150..1500, true)
    }

    @Test
    fun continuous_plan_prepends_touch_down_dwell() {
        // The finger lands and settles before moving: a leading run of duplicated
        // points (k ≤ DWELL_MAX_POINTS) whose time share stays within the dwell band.
        repeat(30) { seed ->
            val engine = GestureEngine(Random(seed.toLong()))
            val plan = engine.generateContinuousPlan(1080, 2400, 0.6f, 500L, ScrollDirection.UP)
            val pts = plan.points
            var k = 0
            while (k + 1 < pts.size && pts[k + 1] == pts.first()) k++
            assertEquals("dwell run length must match plan metadata", k, plan.dwellPointCount)
            assertTrue("dwell run $k out of bounds", k in 0..GestureEngine.DWELL_MAX_POINTS)
            val share = k.toFloat() / pts.size
            assertTrue("dwell share $share out of band", share in 0f..0.12f)
        }
    }

    @Test
    fun continuous_plan_encodes_fast_start() {
        // Skipping the dwell prefix, the mean step length of the first third must
        // exceed that of the last third — the accelerate/gently-decelerate encoding.
        val engine = GestureEngine(Random(3))
        val plan = engine.generateContinuousPlan(1080, 2400, 0.6f, 500L, ScrollDirection.UP)
        val moving = plan.points.drop(plan.dwellPointCount + 1)
        val third = moving.size / 3
        fun meanStep(range: IntRange): Float {
            var acc = 0f
            for (i in range) {
                val dy = moving[i].y - moving[i - 1].y
                val dx = moving[i].x - moving[i - 1].x
                acc += kotlin.math.sqrt(dy * dy + dx * dx)
            }
            return acc / range.count()
        }
        val early = meanStep(1..third)
        val late = meanStep((moving.size - third) until moving.size)
        assertTrue("early mean step $early must exceed late $late", early > late)
    }

    @Test
    fun continuous_plan_is_deterministic_under_fixed_seed() {
        val a = GestureEngine(Random(77)).generateContinuousPlan(1080, 2400, 0.6f, 500L, ScrollDirection.UP)
        val b = GestureEngine(Random(77)).generateContinuousPlan(1080, 2400, 0.6f, 500L, ScrollDirection.UP)
        assertEquals(a.duration, b.duration)
        assertEquals(a.dwellPointCount, b.dwellPointCount)
        assertEquals(a.points, b.points)
    }

    @Test
    fun continuous_plan_varies_speed_profile_across_seeds() {
        // Per-swipe ease-scale variety: the normalized distance covered by the first
        // moving quarter must differ across seeds (not a single fixed profile).
        val firstQuarterTravel = HashSet<Float>()
        repeat(20) { seed ->
            val plan = GestureEngine(Random(seed.toLong())).generateContinuousPlan(1080, 2400, 0.6f, 500L, ScrollDirection.UP)
            val moving = plan.points.drop(plan.dwellPointCount + 1)
            val q = moving.size / 4
            val travel = Math.abs(moving[q].y - moving.first().y)
            val total = Math.abs(moving.last().y - moving.first().y)
            firstQuarterTravel.add(travel / total)
        }
        assertTrue(
            "normalized first-quarter travel should vary across seeds (got ${firstQuarterTravel.size} unique)",
            firstQuarterTravel.size > 5
        )
    }

    @Test
    fun legacy_plan_phase_point_counts_and_seam() {
        // The degraded-mode two-segment plan chains along the same bezier: decel's
        // first point sits one sample past the split point (decelPts[0], which exactly
        // duplicates accel's end, is dropped by design), so the seam gap must be a
        // small fraction of the total travel — no teleport.
        val engine = GestureEngine(Random(4))
        val plan = engine.generateGesturePlan(1080, 2400, 0.6f, 500L, ScrollDirection.UP)
        assertTrue(plan.accelPoints.size >= 6)
        assertTrue(plan.decelPoints.size >= 6)
        val accelStart = plan.accelPoints.first()
        val decelEnd = plan.decelPoints.last()
        val totalTravel = Math.abs(decelEnd.y - accelStart.y)
        val seamGap = kotlin.math.sqrt(
            square(plan.decelPoints.first().x - plan.accelPoints.last().x) +
                square(plan.decelPoints.first().y - plan.accelPoints.last().y)
        )
        assertTrue(
            "seam gap $seamGap too large vs travel $totalTravel",
            seamGap < totalTravel * 0.15f
        )
        assertTrue("total phase time must cover the stroke", plan.accelDuration + plan.decelDuration >= 80L)
    }

    private fun square(v: Float): Float = v * v
}
