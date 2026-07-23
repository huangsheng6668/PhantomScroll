package com.phantom.scroll.service

import android.view.accessibility.AccessibilityEvent

/**
 * Pure logic extracted from PhantomScrollService.onAccessibilityEvent: decides whether an
 * accessibility event should be considered for per-app handling, and if so returns its
 * package name. No Android framework references except the [AccessibilityEvent] type
 * constants — unit-testable on the JVM.
 *
 * @return the package name to feed PerAppDetector, or null if the event should be ignored.
 */
object PackageChangeExtractor {

    fun extract(eventType: Int, eventPackage: String?, currentPackage: String?): String? {
        val isWindowStateChanged = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val isWindowContentChanged = eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        if (!isWindowStateChanged && !isWindowContentChanged) return null

        // Skip high-frequency content-changed events when the package hasn't changed.
        if (isWindowContentChanged && eventPackage == currentPackage) return null

        return eventPackage // may be null
    }
}
