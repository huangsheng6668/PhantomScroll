package com.phantom.scroll.data

/** Per-app override of [ScrollSettings], keyed by [packageName]. */
data class AppProfile(
    val packageName: String,
    val settings: ScrollSettings
)
