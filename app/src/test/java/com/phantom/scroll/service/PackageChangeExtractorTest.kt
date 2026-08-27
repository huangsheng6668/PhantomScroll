package com.phantom.scroll.service

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class PackageChangeExtractorTest {

    @Test
    fun returns_null_for_unhandled_event_type() {
        val result = PackageChangeExtractor.extract(
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            "com.example"
        )
        assertEquals(null, result)
    }

    @Test
    fun returns_package_for_window_state_changed() {
        val result = PackageChangeExtractor.extract(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            "com.example.novel"
        )
        assertEquals("com.example.novel", result)
    }

    @Test
    fun ignores_window_content_changed_even_when_package_differs() {
        // Cross-app content-change events (IME candidates, chat heads, PiP, overlays)
        // are NOT evidence of a foreground switch — treating them as switches flipped
        // currentPackage to the wrong app and corrupted per-app profiles.
        val result = PackageChangeExtractor.extract(
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            "com.example.ime"
        )
        assertEquals(null, result)
    }

    @Test
    fun ignores_window_content_changed_when_package_unchanged() {
        val result = PackageChangeExtractor.extract(
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            "com.example.novel"
        )
        assertEquals(null, result)
    }

    @Test
    fun returns_null_package_when_event_package_is_null() {
        val result = PackageChangeExtractor.extract(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            null
        )
        assertEquals(null, result)
    }
}
