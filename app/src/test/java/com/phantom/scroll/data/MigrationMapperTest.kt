package com.phantom.scroll.data

import com.phantom.scroll.gesture.ScrollDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class MigrationMapperTest {

    @Test
    fun all_legacy_values_present_maps_directly() {
        val s = MigrationMapper.buildGlobalFromLegacy(
            duration = 700L, interval = 4000L, ratio = 0.55f
        )
        assertEquals(700L, s.duration)
        assertEquals(4000L, s.interval)
        assertEquals(0.55f, s.distanceRatio)
        // direction is a new field with no legacy source → default DOWN
        assertEquals(ScrollDirection.DOWN, s.direction)
    }

    @Test
    fun all_null_like_new_install_uses_defaults() {
        val s = MigrationMapper.buildGlobalFromLegacy(duration = null, interval = null, ratio = null)
        assertEquals(ScrollSettings.DEFAULT, s)
    }

    @Test
    fun partial_values_fill_missing_with_defaults() {
        val s = MigrationMapper.buildGlobalFromLegacy(duration = 900L, interval = null, ratio = null)
        assertEquals(900L, s.duration)
        assertEquals(ScrollSettings.DEFAULT.interval, s.interval)
        assertEquals(ScrollSettings.DEFAULT.distanceRatio, s.distanceRatio)
    }
}
