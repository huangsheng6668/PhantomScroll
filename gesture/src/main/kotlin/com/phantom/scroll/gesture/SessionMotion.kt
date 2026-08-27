package com.phantom.scroll.gesture

import java.util.Random

/**
 * Per-session finger-habit model — the humanization layer ABOVE per-swipe randomness.
 *
 * Per-swipe noise (distance/duration/jitter) makes any SINGLE swipe look organic, but
 * an unmodeled sequence still has robot tells: start heights jump uniformly across the
 * whole safe zone every swipe, and the first swipe after a pause is as brisk as steady
 * state. Real readers instead:
 *
 *  - swipe from a **habitual start band** that drifts slowly (random walk) and only
 *    occasionally relocates wholesale (the finger lifts and settles elsewhere);
 *  - **re-enter gently** after a pause — the first swipe or two is slightly slower
 *    before the reader settles into their rhythm.
 *
 * The anchor lives in *slack-fraction space* (`0 = swipe-origin edge of the slack,
 * 1 = far edge`), so the model composes with [GestureEngine]'s travel guarantee: the
 * headroom is always coerced into `[0, slack]` there, meaning the full swipe distance
 * NEVER gets truncated no matter how high `distanceRatio` is.
 *
 * Pure Kotlin (no Android dependencies) → fully JVM-unit-testable. Random is injected,
 * so behavior is deterministic under a fixed seed. Draw order never consults swipe
 * direction, so UP/DOWN mirroring in [GestureEngine] is preserved.
 */
class SessionMotion(
    /** Initial anchor position within the slack band (0.5 = center of the slack). */
    private val initialAnchor: Float = 0.5f,
    /** σ of the anchor's slow random-walk drift per swipe (slack-fraction units). */
    private val driftSigma: Float = 0.10f,
    /** σ of the per-swipe scatter of the actual start around the anchor. */
    private val scatterSigma: Float = 0.12f,
    /** Probability that this swipe starts with the finger re-settled elsewhere. */
    private val relocationProbability: Float = 0.07f,
    /** σ of the relocation jump (slack-fraction units). */
    private val relocationSigma: Float = 0.30f,
    /** Duration multiplier of the first swipe after [beginSession] (gentle re-entry). */
    private val firstSwipeMultiplier: Float = 1.22f,
    /** Duration multiplier of the second swipe after [beginSession]. */
    private val secondSwipeMultiplier: Float = 1.10f
) {

    /** Per-swipe pacing decisions handed to [GestureEngine]. */
    data class SwipePacing(
        /** Where in the travel slack this swipe starts (clamped to the anchor band). */
        val headroomFraction: Float,
        /** Warm-up duration multiplier for this swipe (1.0 once the session settles). */
        val warmupDurationMultiplier: Float
    )

    /** Habitual start anchor; drifts per swipe, occasionally relocates. */
    private var anchor = initialAnchor

    /** Swipes since the last [beginSession]; drives the warm-up ramp. */
    private var swipeIndex = 0

    /**
     * Marks a new scrolling session (pause→resume rising edge). Resets the warm-up
     * ramp but NOT the anchor — a pause does not erase where the thumb likes to rest.
     */
    fun beginSession() {
        swipeIndex = 0
    }

    /**
     * Advances the model by one swipe and returns its pacing decisions.
     *
     * Draw order is fixed (relocation? → drift → scatter) and never branches on
     * direction, so identical seeds produce identical sequences.
     */
    fun nextSwipe(random: Random): SwipePacing {
        val warmup = when (swipeIndex) {
            0 -> firstSwipeMultiplier
            1 -> secondSwipeMultiplier
            else -> 1f
        }
        if (random.nextFloat() < relocationProbability) {
            anchor = (anchor + random.nextGaussian().toFloat() * relocationSigma)
                .coerceIn(ANCHOR_MIN, ANCHOR_MAX)
        }
        anchor = (anchor + random.nextGaussian().toFloat() * driftSigma)
            .coerceIn(ANCHOR_MIN, ANCHOR_MAX)
        val fraction = (anchor + random.nextGaussian().toFloat() * scatterSigma)
            .coerceIn(ANCHOR_MIN, ANCHOR_MAX)
        swipeIndex++
        return SwipePacing(fraction, warmup)
    }

    private companion object {
        /** Anchor/scatter clamp — keeps a sliver of slack on both edges. */
        const val ANCHOR_MIN = 0.02f
        const val ANCHOR_MAX = 0.98f
    }
}
