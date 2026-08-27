package com.phantom.scroll.ui.overlay

/**
 * Pure geometry helpers for the floating overlay. Side-effect-free, no Android View
 * dependencies beyond primitives, so they are JVM-unit-testable.
 */
object OverlayGeometry {

    /** Redesigned panel width (dp). See docs/superpowers/specs/2026-06-22-overlay-redesign-design.md. */
    const val PANEL_WIDTH_DP = 250

    /** Redesigned collapsed bubble width (dp). (20dp per user feedback: ~1/3 of the original 56dp.) */
    const val COLLAPSED_WIDTH_DP = 20

    const val PANEL_WIDTH_RATIO = 0.45f
    const val PANEL_WIDTH_MIN_DP = 230
    const val PANEL_WIDTH_MAX_DP = 270

    /** Computes dynamic panel width in px based on screen width and density. */
    fun panelWidthPx(screenWidthPx: Int, density: Float): Int {
        if (screenWidthPx <= 0) return (PANEL_WIDTH_DP * density).toInt()
        val ratioBased = (screenWidthPx * PANEL_WIDTH_RATIO).toInt()
        val minPx = (PANEL_WIDTH_MIN_DP * density).toInt()
        val maxPx = (PANEL_WIDTH_MAX_DP * density).toInt()
        return ratioBased.coerceIn(minPx, maxPx)
    }

    /** Result of computing the snap target after a drag ends. */
    data class SnapTarget(val x: Int, val isLeftEdge: Boolean)

    /**
     * Fraction of screen height reserved as a no-go zone at the TOP when the overlay rests.
     * Avoids the status bar / notch / camera cutout and the upper curved edge of waterfall
     * displays so the resting bubble stays reachable.
     */
    const val SAFE_TOP_RATIO = 0.15f

    /**
     * Fraction of screen height reserved as a no-go zone at the BOTTOM when the overlay rests.
     * The bottom corners of a curved-edge (waterfall) screen are the hardest region to tap, so we
     * keep a slightly larger margin here than at the top.
     */
    const val SAFE_BOTTOM_RATIO = 0.12f

    /**
     * Given the panel's center X, decides which screen edge to snap to.
     * Center left of screen middle → left edge (x=0); otherwise right edge.
     */
    fun snapTarget(panelCenterX: Int, screenWidth: Int, panelWidthPx: Int): SnapTarget {
        val middle = screenWidth / 2
        return if (panelCenterX > middle) {
            SnapTarget(x = screenWidth - panelWidthPx, isLeftEdge = false)
        } else {
            SnapTarget(x = 0, isLeftEdge = true)
        }
    }

    /**
     * Decides the overlay's resting Y for the current orientation.
     *
     * - **Portrait**: clamps into a vertical "safe band" that avoids the four screen corners.
     *   This is critical for curved-edge (waterfall) phones where the corners sit on the bent
     *   glass and are hard to tap. The top margin clears the status bar / notch; the bottom
     *   margin clears the lower curve.
     * - **Landscape**: corners are left alone. In landscape the user is typically gaming or
     *   watching video and won't use PhantomScroll, so a bubble parked in a corner is harmless.
     *   We only keep it on-screen (the legacy behaviour).
     *
     * Only the *resting* position is constrained; dragging itself is always free.
     *
     * @param y            current top Y of the overlay (px)
     * @param screenWidth  full screen width (px) — used to detect orientation
     * @param screenHeight full screen height (px)
     * @param overlayH     height of the overlay (px); used so the whole bubble stays inside
     * @return the closest valid resting Y for the current orientation
     */
    fun clampRestingY(y: Int, screenWidth: Int, screenHeight: Int, overlayH: Int): Int {
        val portrait = screenWidth > 0 && screenHeight > 0 && screenWidth < screenHeight
        if (!portrait) {
            // Landscape (or unknown): corners are fine — just keep the bubble fully on-screen.
            return clamp(y, 0, (screenHeight - overlayH).coerceAtLeast(0))
        }
        val min = (screenHeight * SAFE_TOP_RATIO).toInt()
        val maxBottomExclusive = screenHeight - (screenHeight * SAFE_BOTTOM_RATIO).toInt()
        val max = (maxBottomExclusive - overlayH).coerceAtLeast(min)
        return clamp(y, min, max)
    }

    /** The resting X for a given edge (0 for left, screenWidth - widthPx for right). */
    fun edgeX(isLeftEdge: Boolean, screenWidth: Int, widthPx: Int): Int =
        if (isLeftEdge) 0 else screenWidth - widthPx

    /**
     * Clamps [value] into [min]..[max]. If [max] < [min] (defensive: very small screen),
     * returns [min] to avoid [IllegalArgumentException] from [coerceIn].
     */
    fun clamp(value: Int, min: Int, max: Int): Int =
        value.coerceIn(min, max.coerceAtLeast(min))
}
