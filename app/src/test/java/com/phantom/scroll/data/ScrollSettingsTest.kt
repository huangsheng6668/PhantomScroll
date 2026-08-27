package com.phantom.scroll.data

import com.phantom.scroll.gesture.ScrollDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollSettingsTest {
    @Test
    fun defaults_match_legacy_scroll_config() {
        // Must equal the old ScrollConfig defaults so behavior is unchanged after migration.
        val d = ScrollSettings.DEFAULT
        assertEquals(500L, d.duration)
        assertEquals(2000L, d.interval)
        assertEquals(0.75f, d.distanceRatio)
        assertEquals(ScrollDirection.DOWN, d.direction)
    }
}
