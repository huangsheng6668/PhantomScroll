package com.phantom.scroll.data

/**
 * The single shape of every state change that flows through [SettingsRepository.apply].
 * Keeping all mutations behind this sealed type makes the per-app consistency rules
 * exhaustively matchable in [SettingsReducer] (a `when` over this type) and lets
 * concurrency tests replay any mutation deterministically.
 *
 * Non-persisted runtime state (isRunning, screen size) does NOT go through intents —
 * those are written directly to their StateFlows since they carry no cross-field rules.
 */
sealed interface SettingsIntent {
    /** Foreground app switched (from PerAppDetector decision). */
    data class PackageSwitched(val pkg: String?) : SettingsIntent
    /** User toggled the per-app switch. */
    data class PerAppToggled(val enabled: Boolean) : SettingsIntent
    /** User edited a parameter (Slider / direction) — writes to the active target. */
    data class SettingEdited(val transform: (ScrollSettings) -> ScrollSettings) : SettingsIntent
    /** Apply a preset's settings to the global defaults. */
    data class PresetApplied(val settings: ScrollSettings) : SettingsIntent
    /** Forget the current app's profile (falls back to global). */
    data object ForgetActiveApp : SettingsIntent
}
