package com.phantom.scroll.data

/** Cumulative runtime statistics. Persisted across service restarts. */
data class ScrollStats(
    val swipeCount: Long,
    val elapsedMs: Long
) {
    companion object {
        val ZERO = ScrollStats(swipeCount = 0L, elapsedMs = 0L)
    }
}
