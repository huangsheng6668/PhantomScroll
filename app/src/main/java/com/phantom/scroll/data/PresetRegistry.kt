package com.phantom.scroll.data

/**
 * Pure: derives the [PresetSelection] for a given [ScrollSettings].
 * Returns [PresetSelection.BuiltIn] when [settings] exactly matches a built-in [Preset];
 * otherwise [PresetSelection.Custom]. No Android / coroutine dependencies → JVM-testable.
 */
object PresetRegistry {
    fun selectionFor(settings: ScrollSettings): PresetSelection =
        Preset.BUILT_INS.firstOrNull { it.settings == settings }?.let { PresetSelection.BuiltIn(it) }
            ?: PresetSelection.Custom
}
