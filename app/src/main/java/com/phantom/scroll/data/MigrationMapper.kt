package com.phantom.scroll.data

/**
 * Pure mapping from legacy SharedPreferences keys to the new [ScrollSettings] model.
 * Kept side-effect-free so the migration transformation is unit-testable without DataStore.
 */
object MigrationMapper {
    fun buildGlobalFromLegacy(
        duration: Long?,
        interval: Long?,
        ratio: Float?
    ): ScrollSettings = ScrollSettings(
        duration = duration ?: ScrollSettings.DEFAULT.duration,
        interval = interval ?: ScrollSettings.DEFAULT.interval,
        distanceRatio = ratio ?: ScrollSettings.DEFAULT.distanceRatio,
        direction = ScrollDirection.DOWN // new field, no legacy source
    )
}
