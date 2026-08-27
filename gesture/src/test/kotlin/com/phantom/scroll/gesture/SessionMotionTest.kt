package com.phantom.scroll.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class SessionMotionTest {

    @Test
    fun warmup_ramp_first_second_then_settles() {
        val m = SessionMotion()
        val r = Random(0)
        assertEquals(1.22f, m.nextSwipe(r).warmupDurationMultiplier, 1e-5f)
        assertEquals(1.10f, m.nextSwipe(r).warmupDurationMultiplier, 1e-5f)
        repeat(10) {
            assertEquals("settled multiplier must be 1.0", 1f, m.nextSwipe(r).warmupDurationMultiplier, 1e-5f)
        }
    }

    @Test
    fun beginSession_resets_the_ramp() {
        val m = SessionMotion()
        val r = Random(0)
        m.nextSwipe(r)
        m.nextSwipe(r)
        m.beginSession()
        assertEquals("ramp must restart after beginSession", 1.22f, m.nextSwipe(r).warmupDurationMultiplier, 1e-5f)
    }

    @Test
    fun headroom_fractions_stay_within_clamp() {
        val m = SessionMotion()
        val r = Random(1)
        repeat(500) {
            val f = m.nextSwipe(r).headroomFraction
            assertTrue("headroom fraction $f out of [0.02, 0.98]", f in 0.02f..0.98f)
        }
    }

    @Test
    fun start_positions_vary_across_swipes() {
        // Drift + scatter + occasional relocation keep the habitual band alive: over
        // many swipes the drawn fractions must produce more than a couple of values.
        val m = SessionMotion()
        val r = Random(2)
        val values = HashSet<Float>()
        repeat(100) { values.add(m.nextSwipe(r).headroomFraction) }
        assertTrue("expected varied start fractions, got ${values.size}", values.size > 30)
    }

    @Test
    fun deterministic_under_fixed_seed() {
        val r1 = Random(42)
        val r2 = Random(42)
        val m1 = SessionMotion()
        val m2 = SessionMotion()
        repeat(50) {
            assertEquals(m1.nextSwipe(r1), m2.nextSwipe(r2))
        }
    }
}
