package com.phantom.scroll.service

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class PackageChangeExtractorTest {

    @Test
    fun returns_null_for_unhandled_event_type() {
        val result = PackageChangeExtractor.extract(
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            "com.example",
            null
        )
        assertEquals(null, result)
    }

    @Test
    fun returns_package_for_window_state_changed() {
        val result = PackageChangeExtractor.extract(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            "com.example.novel",
            "com.other"
        )
        assertEquals("com.example.novel", result)
    }

    @Test
    fun returns_package_for_window_content_changed_when_package_differs() {
        val result = PackageChangeExtractor.extract(
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            "com.example.novel",
            "com.other"
        )
        assertEquals("com.example.novel", result)
    }

    @Test
    fun returns_null_for_window_content_changed_when_package_unchanged() {
        // high-frequency content events with no package change are skipped
        val result = PackageChangeExtractor.extract(
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            "com.example.novel",
            "com.example.novel"
        )
        assertEquals(null, result)
    }

    @Test
    fun returns_null_package_when_event_package_is_null() {
        val result = PackageChangeExtractor.extract(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            null,
            "com.other"
        )
        assertEquals(null, result)
    }
}
