package com.phantom.scroll.ui.overlay

/**
 * Pure formatter for the collapsed bubble's count badge. Caps display at "99+".
 * JVM-unit-testable.
 */
object BadgeFormatter {
    fun format(count: Int): String = if (count > 99) "99+" else count.toString()
}
