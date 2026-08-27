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
 * **Trailing-edge debounce — the final foreground app is never lost.** A switch arriving
 * within [debounceWindowMs] of the previous one is not dropped: the latest candidate is
 * remembered as [pendingPackage] and the service schedules a trailing-edge apply after the
 * window (see [takePending]). The old behaviour skipped the event entirely, which stranded
 * currentPackage on an intermediate app during rapid A→B→A bursts — the overlay then showed
 * the wrong app and per-app profiles were written for the wrong package.
 *
 * Denylisted / own-package noise never touches the pending candidate: neither is a state
 * change and neither may cancel a scheduled trailing-edge apply. An event for the CURRENT
 * package is different — it proves the foreground settled back where it already was, so it
 * DOES clear a stale pending candidate (A→B→A bursts, transient popups).
 *
 * Stateful ONLY for the debounce window timestamp and the pending package; reset by
 * recreating the instance (the service owns one instance for its lifetime).
 *
 * @param ownPackage the app's own package name (always filtered).
 * @param denylist package names to always ignore (SystemUI / Launcher / IME …).
 * @param debounceWindowMs minimum interval between two accepted switches. Default 300ms (spec §3.4).
 */
class PerAppDetector(
    private val ownPackage: String,
    private val denylist: Set<String>,
    val debounceWindowMs: Long = 300L
) {
    private var lastHandledAtMs: Long = 0L
    private var pendingPackage: String? = null

    fun evaluate(
        eventPackage: String?,
        currentPackage: String?,
        nowMs: Long
    ): PerAppDecision {
        val pkg = eventPackage?.takeIf { it.isNotBlank() } ?: return PerAppDecision.Skip
        if (pkg == currentPackage) {
            // The foreground has settled on (or returned to) the CURRENT package. If a
            // debounced candidate is still pending from an A→B→A burst, that burst has
            // resolved back to A: the candidate is stale and must be dropped here, or the
            // trailing-edge apply would flip currentPackage to the transient app B.
            // If B really is becoming foreground, its own window events follow and
            // re-establish the switch — so unconditional clearing is safe.
            pendingPackage = null
            return PerAppDecision.Skip
        }
        if (pkg == ownPackage || pkg in denylist) return PerAppDecision.Skip
        if (nowMs - lastHandledAtMs < debounceWindowMs) {
            // Within the debounce window: remember the LATEST candidate instead of
            // dropping it. A trailing-edge apply commits it once the burst ends.
            pendingPackage = pkg
            return PerAppDecision.Skip
        }
        lastHandledAtMs = nowMs
        pendingPackage = null
        return PerAppDecision.Handle(pkg)
    }

    /**
     * The most recent debounced candidate, or null. Non-destructive peek: whenever this
     * is non-null the service (re)schedules the trailing-edge apply.
     */
    fun pendingPackage(): String? = pendingPackage

    /**
     * Consumes the pending candidate after the debounce window elapsed and treats it as
     * handled — the window restarts from [nowMs], so a follow-up burst debounces against
     * the applied time.
     */
    fun takePending(nowMs: Long): String? {
        val pending = pendingPackage
        pendingPackage = null
        if (pending != null) lastHandledAtMs = nowMs
        return pending
    }
}
