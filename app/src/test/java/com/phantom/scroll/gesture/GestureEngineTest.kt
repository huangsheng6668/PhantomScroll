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
        assertEquals("Short duration should be coerced to 200ms", 200L, pointsShort.duration)

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
}
