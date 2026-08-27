package com.phantom.scroll.ui.overlay

import com.phantom.scroll.gesture.SafeZone
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure label resolvers for the 2×2 param grid. Map continuous [com.phantom.scroll.data.ScrollSettings]
 * values to friendly step labels shown in the collapsed grid cell. Side-effect-free, JVM-unit-testable.
 */
object ParamSteps {

    /**
     * duration(ms) → "中速 3x". Lower duration = faster scroll = higher multiplier.
     * Bands: <400 极速8x · 400–699 快速5x · 700–1049 中速3x · ≥1050 慢速1x.
     */
    fun toSpeedLabel(durationMs: Long): String {
        val (label, mult) = when {
            durationMs < 400 -> "极速" to 8
            durationMs < 700 -> "快速" to 5
            durationMs < 1050 -> "中速" to 3
            else -> "慢速" to 1
        }
        return "$label ${mult}x"
    }

    /** interval(ms) → "2.5s" (one decimal, Locale.ROOT for test stability). */
    fun toIntervalLabel(intervalMs: Long): String =
        String.format(Locale.ROOT, "%.1fs", intervalMs / 1000.0)

    /**
     * distanceRatio + screen height(px) → "中距 580px".
     * Bands: <0.5 短距 · 0.5–0.7499 中距 · ≥0.75 长距.
     * px is the PHYSICAL swipe distance the engine produces: ratio × safe-zone height
     * (see [SafeZone.HEIGHT_RATIO]) — matching what actually lands on screen, not the
     * full display height.
     */
    fun toDistanceLabel(distanceRatio: Float, screenH: Int): String {
        val band = when {
            distanceRatio < 0.5f -> "短距"
            distanceRatio < 0.75f -> "中距"
            else -> "长距"
        }
        val px = (distanceRatio * SafeZone.HEIGHT_RATIO * screenH).roundToInt()
        return "$band ${px}px"
    }
}
