package com.phantom.scroll.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerAppDetectorTest {

    private val ownPackage = "com.phantom.scroll"
    private val denylist = setOf("com.android.systemui", "com.example.launcher", "com.example.ime")
    private val detector = PerAppDetector(ownPackage = ownPackage, denylist = denylist)

    @Test
    fun skips_when_perApp_disabled() {
        // even a fresh app is ignored when the feature is off (zero-overhead path)
        val r = detector.evaluate(
            eventPackage = "com.example.novel",
            currentPackage = null,
            perAppEnabled = false,
            nowMs = 1000L
        )
        assertTrue(r is PerAppDecision.Skip)
    }

    @Test
    fun skips_when_package_unchanged() {
        val r = detector.evaluate(
            eventPackage = "com.example.novel",
            currentPackage = "com.example.novel",
            perAppEnabled = true,
            nowMs = 1000L
        )
        assertTrue(r is PerAppDecision.Skip)
    }

    @Test
    fun skips_system_ui_and_launcher_and_ime() {
        denylist.forEach { pkg ->
            val r = detector.evaluate(pkg, currentPackage = null, perAppEnabled = true, nowMs = 1000L)
            assertTrue("denylisted pkg $pkg should be skipped", r is PerAppDecision.Skip)
        }
    }

    @Test
    fun skips_own_package() {
        val r = detector.evaluate(ownPackage, currentPackage = null, perAppEnabled = true, nowMs = 1000L)
        assertTrue(r is PerAppDecision.Skip)
    }

    @Test
    fun handles_new_user_app() {
        val r = detector.evaluate("com.example.novel", currentPackage = null, perAppEnabled = true, nowMs = 1000L)
        assertTrue(r is PerAppDecision.Handle)
        assertEquals("com.example.novel", (r as PerAppDecision.Handle).packageToSet)
    }

    @Test
    fun debounces_rapid_switches_within_window() {
        // first switch at t=1000 is handled
        val first = detector.evaluate("com.example.a", currentPackage = "com.example.start", perAppEnabled = true, nowMs = 1000L)
        assertTrue(first is PerAppDecision.Handle)
        // another switch 100ms later (within 300ms window) is skipped
        val second = detector.evaluate("com.example.b", currentPackage = "com.example.a", perAppEnabled = true, nowMs = 1100L)
        assertTrue("within debounce window should skip", second is PerAppDecision.Skip)
    }

    @Test
    fun handles_switch_after_debounce_window_elapsed() {
        detector.evaluate("com.example.a", currentPackage = "com.example.start", perAppEnabled = true, nowMs = 1000L)
        val later = detector.evaluate("com.example.b", currentPackage = "com.example.a", perAppEnabled = true, nowMs = 1400L)
        assertTrue("after 300ms window should handle", later is PerAppDecision.Handle)
    }

    @Test
    fun handles_null_or_blank_event_package_safely() {
        val r = detector.evaluate("", currentPackage = null, perAppEnabled = true, nowMs = 1000L)
        assertTrue(r is PerAppDecision.Skip)
    }
}
