package com.phantom.scroll.data

/**
 * Named scene preset. Phase 1 ships only the model shell;
 * built-in constants (小说/漫画) are filled in Phase 3.
 */
data class Preset(
    val name: String,
    val settings: ScrollSettings
)
