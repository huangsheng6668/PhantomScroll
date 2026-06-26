package com.phantom.scroll.gesture

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import com.phantom.scroll.data.ScrollDirection

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
        assertTrue("controlX (${points.controlX}) should be near center ($centerX)", Math.abs(points.controlX - centerX) < screenWidth * 0.2f)

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
        val controlXs = results.map { it.controlX }.toSet()
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
    // Single continuous-stroke plan tests. The engine now encodes its speed curve
    // *inside one path* (no continueStroke seam, no slow-drag tail) via a floored
    // trailing speed that's chosen per speed band — the fix for ad/诱导 misdetection.
    //
    // These target [GestureEngine.speedFloorFor] directly: it's the pure-JVM, Path-free
    // heart of the band selection (generateContinuousPlan itself touches android.graphics
    // Path and so can only run on a device/Robolectric, not a plain JVM).
    // ------------------------------------------------------------------

    @Test
    fun speedFloor_quick_and_fast_band_use_high_floor() {
        // Durations < 700ms (极速 + 快速 bands) must use the high floor so the finger
        // crosses any ad button in <50ms.
        assertEquals(GestureEngine.speedFloorFor(300L), 0.88f, 1e-5f) // 极速
        assertEquals(GestureEngine.speedFloorFor(500L), 0.88f, 1e-5f) // 快速
        assertEquals(GestureEngine.speedFloorFor(699L), 0.88f, 1e-5f) // 快速上界
    }

    @Test
    fun speedFloor_normal_band_uses_mid_floor() {
        // 700–1049ms (中速 band): mid floor, still safe.
        assertEquals(GestureEngine.speedFloorFor(700L), 0.85f, 1e-5f)
        assertEquals(GestureEngine.speedFloorFor(850L), 0.85f, 1e-5f)
        assertEquals(GestureEngine.speedFloorFor(1049L), 0.85f, 1e-5f)
    }

    @Test
    fun speedFloor_slow_band_relaxes_floor() {
        // ≥1050ms (慢速 band): the floor relaxes to 0.80 for a gentler curve, since a
        // long dwell there is intentional reading rather than a tap.
        assertEquals(GestureEngine.speedFloorFor(1050L), 0.80f, 1e-5f)
        assertEquals(GestureEngine.speedFloorFor(1200L), 0.80f, 1e-5f)
        assertEquals(GestureEngine.speedFloorFor(1500L), 0.80f, 1e-5f)
    }

    @Test
    fun speedFloor_never_below_global_minimum() {
        // Across the full supported duration range, the floor must stay ≥ 0.80 — the
        // global anti-misdetection minimum. Faster bands go higher, none may drop lower.
        for (durationMs in 150..1500L step 50) {
            val floor = GestureEngine.speedFloorFor(durationMs)
            assertTrue(
                "speedFloor $floor dropped below global minimum 0.80 at durationMs=$durationMs",
                floor >= 0.80f
            )
        }
    }
}
