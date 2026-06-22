package com.phantom.scroll.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class ParamStepsTest {

    @Test
    fun speed_lower_duration_is_higher_multiplier() {
        assertEquals("极速 8x", ParamSteps.toSpeedLabel(150))   // <400 极速
        assertEquals("极速 8x", ParamSteps.toSpeedLabel(399))
        assertEquals("快速 5x", ParamSteps.toSpeedLabel(400))   // 边界 400→快速
        assertEquals("快速 5x", ParamSteps.toSpeedLabel(699))
        assertEquals("中速 3x", ParamSteps.toSpeedLabel(700))   // 边界 700→中速
        assertEquals("中速 3x", ParamSteps.toSpeedLabel(1049))
        assertEquals("慢速 1x", ParamSteps.toSpeedLabel(1050))  // 边界 1050→慢速
        assertEquals("慢速 1x", ParamSteps.toSpeedLabel(1500))
    }

    @Test
    fun interval_formats_seconds_one_decimal() {
        assertEquals("0.5s", ParamSteps.toIntervalLabel(500))
        assertEquals("2.0s", ParamSteps.toIntervalLabel(2000))
        assertEquals("10.0s", ParamSteps.toIntervalLabel(10000))
    }

    @Test
    fun distance_band_and_equivalent_px() {
        // screenH 2400px
        assertEquals("短距 720px", ParamSteps.toDistanceLabel(0.30f, 2400))  // 0.3*2400=720, <0.5 短
        assertEquals("中距 1200px", ParamSteps.toDistanceLabel(0.50f, 2400)) // 边界 0.5→中
        assertEquals("中距 1799px", ParamSteps.toDistanceLabel(0.7496f, 2400))
        assertEquals("长距 1800px", ParamSteps.toDistanceLabel(0.75f, 2400)) // 边界 0.75→长
        assertEquals("长距 2280px", ParamSteps.toDistanceLabel(0.95f, 2400))
    }
}
