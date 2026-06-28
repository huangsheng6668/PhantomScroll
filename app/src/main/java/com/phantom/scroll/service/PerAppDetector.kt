package com.phantom.scroll.service

/** Outcome of [PerAppDetector.evaluate]. */
sealed interface PerAppDecision {
    /** Update currentPackage to [packageToSet]. */
    data class Handle(val packageToSet: String) : PerAppDecision
    /** Ignore this event (feature off / unchanged / denylisted / debounced / blank). */
    data object Skip : PerAppDecision
}

/**
 * Pure logic that decides whether an accessibility event's package name should update the
 * repository's current package (spec §3.4). Extracted from PhantomScrollService.onAccessibilityEvent
 * so the filtering (denylist / dedup / debounce) is unit-testable without Android.
 *
 * Stateful ONLY for the debounce window timestamp; reset by recreating the instance
 * (the service owns one instance for its lifetime).
 *
 * @param ownPackage the app's own package name (always filtered).
 * @param denylist package names to always ignore (SystemUI / Launcher / IME …).
 * @param debounceMs minimum interval between two accepted switches. Default 300ms (spec §3.4).
 */
class PerAppDetector(
    private val ownPackage: String,
    private val denylist: Set<String>,
    private val debounceMs: Long = 300L
) {
    private var lastHandledAtMs: Long = 0L

    fun evaluate(
        eventPackage: String?,
        currentPackage: String?,
        nowMs: Long
    ): PerAppDecision {
        val pkg = eventPackage?.takeIf { it.isNotBlank() } ?: return PerAppDecision.Skip
        if (pkg == currentPackage) return PerAppDecision.Skip
        if (pkg == ownPackage || pkg in denylist) return PerAppDecision.Skip
        if (nowMs - lastHandledAtMs < debounceMs) return PerAppDecision.Skip
        lastHandledAtMs = nowMs
        return PerAppDecision.Handle(pkg)
    }
}
