package com.phantom.scroll.gesture

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class SpeedCurveTest {

    // ---------- easeOutCubic ----------

    @Test
    fun easeOutCubic_endpoints_are_zero_and_one() {
        assertEquals(0f, SpeedCurve.easeOutCubic(0f), 1e-5f)
        assertEquals(1f, SpeedCurve.easeOutCubic(1f), 1e-5f)
    }

    @Test
    fun easeOutCubic_is_monotonically_increasing() {
        var prev = -1f
        for (i in 0..100) {
            val t = i / 100f
            val v = SpeedCurve.easeOutCubic(t)
            assertTrue("easeOutCubic must not decrease at t=$t (prev=$prev, v=$v)", v >= prev - 1e-6f)
            prev = v
        }
    }

    @Test
    fun easeOutCubic_clamps_inputs_outside_unit_interval() {
        // Inputs below 0 clamp to 0; above 1 clamp to 1, never throwing or wrapping.
        assertEquals(0f, SpeedCurve.easeOutCubic(-0.5f), 1e-5f)
        assertEquals(1f, SpeedCurve.easeOutCubic(1.5f), 1e-5f)
    }

    // ---------- mapTimeToProgress ----------

    @Test
    fun mapTimeToProgress_endpoints_are_zero_and_one() {
        val r = SpeedCurve.mapTimeToProgress(0f, 0.25f, 0.45f)
        val one = SpeedCurve.mapTimeToProgress(1f, 0.25f, 0.45f)
        assertEquals(0f, r, 1e-5f)
        assertEquals(1f, one, 1e-5f)
    }

    @Test
    fun mapTimeToProgress_hits_accel_distance_at_accel_duration_boundary() {
        // At the boundary between phases, progress must equal accelDistanceRatio.
        val adRatio = 0.25f
        val aDistRatio = 0.45f
        val progress = SpeedCurve.mapTimeToProgress(adRatio, adRatio, aDistRatio)
        assertEquals(aDistRatio, progress, 1e-5f)
    }

    @Test
    fun mapTimeToProgress_accelerates_then_decelerates() {
        // With 25% of time covering 45% of distance, the average speed in the first
        // 25% of time must exceed the average speed in the remaining 75%.
        val adRatio = 0.25f
        val aDistRatio = 0.45f
        val accelSpeed = SpeedCurve.mapTimeToProgress(adRatio, adRatio, aDistRatio) / adRatio
        val decelSpeed = (1f - aDistRatio) / (1f - adRatio)
        assertTrue(
            "Acceleration phase ($accelSpeed) must be faster than deceleration ($decelSpeed)",
            accelSpeed > decelSpeed
        )
    }

    @Test
    fun mapTimeToProgress_is_monotonic() {
        var prev = -1f
        for (i in 0..100) {
            val t = i / 100f
            val p = SpeedCurve.mapTimeToProgress(t, 0.25f, 0.45f)
            assertTrue("progress must not decrease at t=$t (prev=$prev, p=$p)", p >= prev - 1e-6f)
            prev = p
        }
    }

    // ---------- sampleBezier ----------

    @Test
    fun sampleBezier_returns_requested_count() {
        val pts = SpeedCurve.sampleBezier(
            SpeedCurve.SampledPoint(0f, 100f),
            SpeedCurve.SampledPoint(50f, 50f),
            SpeedCurve.SampledPoint(100f, 0f),
            sampleCount = 11,
            jitterPx = 1f,
            random = Random(0)
        )
        assertEquals(11, pts.size)
    }

    @Test
    fun sampleBezier_returns_two_points_when_count_too_small() {
        val pts = SpeedCurve.sampleBezier(
            SpeedCurve.SampledPoint(0f, 100f),
            SpeedCurve.SampledPoint(50f, 50f),
            SpeedCurve.SampledPoint(100f, 0f),
            sampleCount = 1,
            jitterPx = 1f,
            random = Random(0)
        )
        assertEquals(2, pts.size)
    }

    @Test
    fun sampleBezier_endpoints_match_input() {
        val p0 = SpeedCurve.SampledPoint(10f, 200f)
        val p2 = SpeedCurve.SampledPoint(90f, 20f)
        val pts = SpeedCurve.sampleBezier(
            p0,
            SpeedCurve.SampledPoint(50f, 110f),
            p2,
            sampleCount = 21,
            jitterPx = 1f,
            random = Random(7)
        )
        assertEquals(p0.x, pts.first().x, 1e-5f)
        assertEquals(p0.y, pts.first().y, 1e-5f)
        assertEquals(p2.x, pts.last().x, 1e-5f)
        assertEquals(p2.y, pts.last().y, 1e-5f)
    }

    @Test
    fun sampleBezier_jitter_stays_within_bounds() {
        // With jitterPx=0, samples must lie exactly on the analytic bezier curve.
        val p0 = SpeedCurve.SampledPoint(0f, 0f)
        val p1 = SpeedCurve.SampledPoint(100f, 0f)
        val p2 = SpeedCurve.SampledPoint(100f, 100f)
        val pts = SpeedCurve.sampleBezier(p0, p1, p2, sampleCount = 41, jitterPx = 0f, random = Random(0))
        for ((i, pt) in pts.withIndex()) {
            val s = i / 40f
            val expectedX = 2 * s * (1 - s) * 100 + s * s * 100
            val expectedY = s * s * 100
            assertEquals(expectedX.toFloat(), pt.x, 1e-3f)
            assertEquals(expectedY.toFloat(), pt.y, 1e-3f)
        }
    }

    @Test
    fun sampleBezier_jitter_does_not_exceed_two_sigma() {
        val jitterPx = 5f
        val pts = SpeedCurve.sampleBezier(
            SpeedCurve.SampledPoint(500f, 500f),
            SpeedCurve.SampledPoint(510f, 250f),
            SpeedCurve.SampledPoint(500f, 0f),
            sampleCount = 61,
            jitterPx = jitterPx,
            random = Random(123)
        )
        val maxJitter = jitterPx * 2f
        // Compare interior samples to the analytic curve and bound the deviation.
        val p0 = SpeedCurve.SampledPoint(500f, 500f)
        val p1 = SpeedCurve.SampledPoint(510f, 250f)
        val p2 = SpeedCurve.SampledPoint(500f, 0f)
        for (i in 1 until pts.size - 1) {
            val s = i / (pts.size - 1).toFloat()
            val oneMinusS = 1f - s
            val baseX = oneMinusS * oneMinusS * p0.x + 2 * s * oneMinusS * p1.x + s * s * p2.x
            val baseY = oneMinusS * oneMinusS * p0.y + 2 * s * oneMinusS * p1.y + s * s * p2.y
            assertTrue("x jitter out of range at i=$i", abs(pts[i].x - baseX) <= maxJitter + 1e-3f)
            assertTrue("y jitter out of range at i=$i", abs(pts[i].y - baseY) <= maxJitter + 1e-3f)
        }
    }

    // ---------- SampledPoint ----------

    @Test
    fun sampled_point_equals_works_for_data_class() {
        assertEquals(SpeedCurve.SampledPoint(1f, 2f), SpeedCurve.SampledPoint(1f, 2f))
    }
}
