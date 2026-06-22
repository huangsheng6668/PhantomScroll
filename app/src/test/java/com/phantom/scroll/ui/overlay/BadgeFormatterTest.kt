package com.phantom.scroll.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class BadgeFormatterTest {
    @Test
    fun zero_and_small_counts_render_verbatim() {
        assertEquals("0", BadgeFormatter.format(0))
        assertEquals("1", BadgeFormatter.format(1))
        assertEquals("99", BadgeFormatter.format(99))
    }

    @Test
    fun counts_over_99_capped_to_99plus() {
        assertEquals("99+", BadgeFormatter.format(100))
        assertEquals("99+", BadgeFormatter.format(9999))
    }
}
