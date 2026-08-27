package com.phantom.scroll.service

import android.view.accessibility.AccessibilityEvent

/**
 * Pure logic extracted from PhantomScrollService.onAccessibilityEvent: decides whether an
 * accessibility event is trustworthy evidence that the foreground app changed, and if so
 * returns its package name. No Android framework references except the [AccessibilityEvent]
 * type constants — unit-testable on the JVM.
 *
 * ONLY [AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED] qualifies as switch evidence.
 * [AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED] is NOT: content-change events are
 * reported for ANY visible window that updates — third-party IMEs, chat heads, PiP
 * windows, music-player overlays — and their package naturally differs from the
 * foreground app. Treating them as switches flipped currentPackage to the wrong app,
 * which corrupted the per-app ("按App分别记录") profiles: edits were written under the
 * wrong package and the overlay displayed a bogus "前台" app.
 *
 * Content-change events are not subscribed to at all (see the service config): they have
 * had no consumer since the smart-interval feature was removed, and subscribing again
 * would only re-open this corruption path.
 *
 * @return the candidate package name, or null if the event should be ignored (the
 *         caller treats a null package as "ignore").
 */
object PackageChangeExtractor {

    fun extract(eventType: Int, eventPackage: String?): String? {
        val isWindowStateChanged = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (!isWindowStateChanged) return null

        return eventPackage // may be null
    }
}
