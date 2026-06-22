package com.phantom.scroll.ui.overlay

/**
 * Pure geometry helpers for the floating overlay. Side-effect-free, no Android View
 * dependencies beyond primitives, so they are JVM-unit-testable.
 */
object OverlayGeometry {

    /** Redesigned panel width (dp). See docs/superpowers/specs/2026-06-22-overlay-redesign-design.md. */
    const val PANEL_WIDTH_DP = 240

    /** Redesigned collapsed bubble width (dp). */
    const val COLLAPSED_WIDTH_DP = 56

    /** Result of computing the snap target after a drag ends. */
    data class SnapTarget(val x: Int, val isLeftEdge: Boolean)

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
