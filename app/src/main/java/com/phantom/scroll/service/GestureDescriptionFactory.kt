package com.phantom.scroll.service

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.phantom.scroll.gesture.ContinuousPlan
import com.phantom.scroll.gesture.GesturePlan
import com.phantom.scroll.gesture.SpeedCurve

/**
 * Android adapter for the pure-JVM `:gesture` module: converts point-list swipe plans
 * into [GestureDescription] strokes for [android.accessibilityservice.AccessibilityService.dispatchGesture].
 *
 * This is the ONLY place `android.graphics.Path` enters the swipe pipeline, honoring the
 * zero-GC spec (§3): the Path objects are pooled per factory instance and `reset()` +
 * rebuilt on every plan — never reallocated inside the scroll loop. The factory is
 * main-thread-confined (same confinement as [ScrollOrchestrator]'s dispatch step), so
 * the pool needs no synchronization.
 *
 * Plan references are BORROWED (the engine reuses its buffers on the next generate
 * call); build must complete before the orchestrator asks for the next plan — which its
 * strictly sequential generate→dispatch loop already guarantees.
 */
class GestureDescriptionFactory {

    // Pooled Path objects — never reallocated; reset() before each rebuild.
    private val continuousPath = Path()
    private val accelPath = Path()
    private val decelPath = Path()

    /**
     * Builds the preferred single continuous stroke: one `StrokeDescription` whose
     * point spacing encodes the accelerate/gently-decelerate profile, with a leading
     * run of duplicated points encoding the touch-down dwell (see [ContinuousPlan]).
     */
    fun build(plan: ContinuousPlan): GestureDescription {
        rebuildPolyline(continuousPath, plan.points)
        val stroke = GestureDescription.StrokeDescription(continuousPath, 0L, plan.duration)
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    /**
     * Builds the legacy fallback: two continuous strokes (the second via
     * `continueStroke`), so Android treats them as one finger with a speed change at
     * the seam. Used only in degraded mode on ROMs that mishandle the single-stroke plan.
     */
    fun build(plan: GesturePlan): GestureDescription {
        rebuildPolyline(accelPath, plan.accelPoints)
        rebuildPolyline(decelPath, plan.decelPoints)
        val accel = GestureDescription.StrokeDescription(
            accelPath, 0L, plan.accelDuration, true /* willContinue */
        )
        val decel = accel.continueStroke(decelPath, 0L, plan.decelDuration, false)
        return GestureDescription.Builder()
            .addStroke(accel)
            .addStroke(decel)
            .build()
    }

    private fun rebuildPolyline(target: Path, points: List<SpeedCurve.SampledPoint>) {
        target.reset()
        if (points.isEmpty()) return
        val first = points[0]
        target.moveTo(first.x, first.y)
        for (i in 1 until points.size) {
            target.lineTo(points[i].x, points[i].y)
        }
    }
}
