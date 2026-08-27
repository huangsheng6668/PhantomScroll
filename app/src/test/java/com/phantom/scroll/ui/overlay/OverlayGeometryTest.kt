package com.phantom.scroll.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayGeometryTest {

    @Test
    fun snapTarget_left_when_center_left_of_middle() {
        // panelCenterX 400 < middle 540 → snap to left edge
        val t = OverlayGeometry.snapTarget(panelCenterX = 400, screenWidth = 1080, panelWidthPx = 200)
        assertEquals(0, t.x)
        assertEquals(true, t.isLeftEdge)
    }

    @Test
    fun snapTarget_right_when_center_right_of_middle() {
        // panelCenterX 700 > middle 540 → snap to right edge
        val t = OverlayGeometry.snapTarget(panelCenterX = 700, screenWidth = 1080, panelWidthPx = 200)
        assertEquals(1080 - 200, t.x)
        assertEquals(false, t.isLeftEdge)
    }

    @Test
    fun edgeX_is_zero_for_left_else_screen_minus_width() {
        assertEquals(0, OverlayGeometry.edgeX(isLeftEdge = true, screenWidth = 1080, widthPx = 200))
        assertEquals(880, OverlayGeometry.edgeX(isLeftEdge = false, screenWidth = 1080, widthPx = 200))
    }

    @Test
    fun clamp_keeps_value_within_bounds() {
        assertEquals(0, OverlayGeometry.clamp(-5, 0, 100))
        assertEquals(100, OverlayGeometry.clamp(150, 0, 100))
        assertEquals(42, OverlayGeometry.clamp(42, 0, 100))
    }

    @Test
    fun clamp_handles_zero_range() {
        // defensive: if max == min, coerceIn returns that single valid value
        assertEquals(0, OverlayGeometry.clamp(50, 0, 0))
    }

    @Test
    fun clamp_handles_inverted_bounds_gracefully() {
        // defensive: if max < min (e.g. very small screen), clamp should not crash
        // and should return min as a safe default
        assertEquals(0, OverlayGeometry.clamp(50, 0, -10))
    }

    @Test
    fun panel_and_collapse_width_constants_match_redesign() {
        assertEquals(250, OverlayGeometry.PANEL_WIDTH_DP)
        assertEquals(20, OverlayGeometry.COLLAPSED_WIDTH_DP)
    }

    // ---- clampRestingY: portrait keeps the resting bubble off the four corners; landscape allows them ----

    private val bandScreenW = 1080
    private val bandScreenH = 2400
    private val bandOverlayH = 200
    // portrait safe band on 2400px: top 15% = 360, bottom 12% = 288 → band is [360, 2112 - 200] = [360, 1912]

    @Test
    fun restingY_portrait_pulls_top_corner_into_band() {
        val y = OverlayGeometry.clampRestingY(y = 0, screenWidth = bandScreenW, screenHeight = bandScreenH, overlayH = bandOverlayH)
        assertEquals(360, y)
    }

    @Test
    fun restingY_portrait_pulls_bottom_corner_into_band() {
        val y = OverlayGeometry.clampRestingY(y = bandScreenH, screenWidth = bandScreenW, screenHeight = bandScreenH, overlayH = bandOverlayH)
        assertEquals(1912, y)
    }

    @Test
    fun restingY_portrait_keeps_middle_untouched() {
        val y = OverlayGeometry.clampRestingY(y = 1200, screenWidth = bandScreenW, screenHeight = bandScreenH, overlayH = bandOverlayH)
        assertEquals(1200, y)
    }

    @Test
    fun restingY_landscape_allows_top_corner() {
        // landscape (2400 wide x 1080 tall): corners are fine → a release at y=0 stays at 0
        val y = OverlayGeometry.clampRestingY(y = 0, screenWidth = bandScreenH, screenHeight = bandScreenW, overlayH = bandOverlayH)
        assertEquals(0, y)
    }

    @Test
    fun restingY_landscape_allows_bottom_corner() {
        // landscape bottom corner: only kept on-screen (screenH - overlayH)
        val y = OverlayGeometry.clampRestingY(y = bandScreenW, screenWidth = bandScreenH, screenHeight = bandScreenW, overlayH = bandOverlayH)
        assertEquals(bandScreenW - bandOverlayH, y)
    }

    @Test
    fun restingY_landscape_keeps_middle_untouched() {
        val y = OverlayGeometry.clampRestingY(y = 500, screenWidth = bandScreenH, screenHeight = bandScreenW, overlayH = bandOverlayH)
        assertEquals(500, y)
    }
}
