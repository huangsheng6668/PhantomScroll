package com.phantom.scroll.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerAppDetectorTest {

    private val ownPackage = "com.phantom.scroll"
    private val denylist = setOf("com.android.systemui", "com.example.launcher", "com.example.ime")
    private val detector = PerAppDetector(ownPackage = ownPackage, denylist = denylist)

    @Test
    fun skips_when_package_unchanged() {
        val r = detector.evaluate(
            eventPackage = "com.example.novel",
            currentPackage = "com.example.novel",
            nowMs = 1000L
        )
        assertTrue(r is PerAppDecision.Skip)
    }

    @Test
    fun skips_system_ui_and_launcher_and_ime() {
        denylist.forEach { pkg ->
            val r = detector.evaluate(pkg, currentPackage = null, nowMs = 1000L)
            assertTrue("denylisted pkg $pkg should be skipped", r is PerAppDecision.Skip)
        }
    }

    @Test
    fun skips_own_package() {
        val r = detector.evaluate(ownPackage, currentPackage = null, nowMs = 1000L)
        assertTrue(r is PerAppDecision.Skip)
    }

    @Test
    fun handles_new_user_app() {
        val r = detector.evaluate("com.example.novel", currentPackage = null, nowMs = 1000L)
        assertTrue(r is PerAppDecision.Handle)
        assertEquals("com.example.novel", (r as PerAppDecision.Handle).packageToSet)
    }

    @Test
    fun debounces_rapid_switches_within_window() {
        // first switch at t=1000 is handled
        val first = detector.evaluate("com.example.a", currentPackage = "com.example.start", nowMs = 1000L)
        assertTrue(first is PerAppDecision.Handle)
        // another switch 100ms later (within 300ms window) is skipped
        val second = detector.evaluate("com.example.b", currentPackage = "com.example.a", nowMs = 1100L)
        assertTrue("within debounce window should skip", second is PerAppDecision.Skip)
    }

    @Test
    fun handles_switch_after_debounce_window_elapsed() {
        detector.evaluate("com.example.a", currentPackage = "com.example.start", nowMs = 1000L)
        val later = detector.evaluate("com.example.b", currentPackage = "com.example.a", nowMs = 1400L)
        assertTrue("after 300ms window should handle", later is PerAppDecision.Handle)
    }

    @Test
    fun handles_null_or_blank_event_package_safely() {
        val r = detector.evaluate("", currentPackage = null, nowMs = 1000L)
        assertTrue(r is PerAppDecision.Skip)
    }

    // ---- Trailing-edge debounce (the final foreground app is never lost) ----------

    @Test
    fun debounced_switch_remembers_latest_candidate_for_trailing_apply() {
        val first = detector.evaluate("com.example.a", currentPackage = "com.example.start", nowMs = 1000L)
        assertTrue(first is PerAppDecision.Handle)
        // Within the 300ms window the switch is debounced but NOT dropped.
        val second = detector.evaluate("com.example.b", currentPackage = "com.example.a", nowMs = 1100L)
        assertTrue(second is PerAppDecision.Skip)
        assertEquals("com.example.b", detector.pendingPackage())
        // A later candidate overwrites the earlier one — the final app wins.
        detector.evaluate("com.example.c", currentPackage = "com.example.a", nowMs = 1200L)
        assertEquals("com.example.c", detector.pendingPackage())
        // After the window the service consumes the candidate via takePending.
        assertEquals("com.example.c", detector.takePending(nowMs = 1600L))
        assertNull(detector.pendingPackage())
    }

    @Test
    fun noise_events_do_not_clear_pending_candidate() {
        detector.evaluate("com.example.a", currentPackage = "com.example.start", nowMs = 1000L)
        detector.evaluate("com.example.b", currentPackage = "com.example.a", nowMs = 1100L) // debounced
        // Denylisted IME noise inside the window must NOT cancel the pending switch.
        detector.evaluate("com.example.ime", currentPackage = "com.example.a", nowMs = 1200L)
        assertEquals("com.example.b", detector.pendingPackage())
    }

    @Test
    fun takePending_restarts_debounce_window() {
        detector.evaluate("com.example.a", currentPackage = "com.example.start", nowMs = 1000L)
        detector.evaluate("com.example.b", currentPackage = "com.example.a", nowMs = 1100L)
        detector.takePending(nowMs = 1600L)
        // 100ms after the trailing apply, a new switch must still be debounced
        // (the window restarted from the applied time).
        val r = detector.evaluate("com.example.c", currentPackage = "com.example.b", nowMs = 1700L)
        assertTrue(r is PerAppDecision.Skip)
        assertEquals("com.example.c", detector.pendingPackage())
    }

    @Test
    fun return_to_current_package_clears_stale_pending_candidate() {
        // A→B burst: B lands inside the debounce window and becomes pending…
        detector.evaluate("com.example.a", currentPackage = "com.example.start", nowMs = 1000L)
        detector.evaluate("com.example.b", currentPackage = "com.example.a", nowMs = 1100L)
        assertEquals("com.example.b", detector.pendingPackage())
        // …then the foreground resolves BACK to A. The A event (pkg == current) must
        // invalidate the stale B candidate — otherwise the trailing-edge apply would
        // flip currentPackage to the transient B.
        detector.evaluate("com.example.a", currentPackage = "com.example.a", nowMs = 1200L)
        assertNull(detector.pendingPackage())
    }

    @Test
    fun cleared_pending_makes_trailing_apply_a_noop() {
        detector.evaluate("com.example.a", currentPackage = "com.example.start", nowMs = 1000L)
        detector.evaluate("com.example.b", currentPackage = "com.example.a", nowMs = 1100L)
        detector.evaluate("com.example.a", currentPackage = "com.example.a", nowMs = 1200L)
        // The service's trailing-edge job consumes via takePending — now empty.
        assertNull(detector.takePending(nowMs = 1600L))
    }
}
