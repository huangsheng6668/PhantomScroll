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
        assertEquals(240, OverlayGeometry.PANEL_WIDTH_DP)
        assertEquals(56, OverlayGeometry.COLLAPSED_WIDTH_DP)
    }
}
