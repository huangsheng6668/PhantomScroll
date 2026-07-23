package com.phantom.scroll.data

/**
 * Read-only snapshot of the repository's persistable state, fed to [SettingsReducer].
 * The reducer never holds mutable state — this is its only input besides the intent.
 */
data class SettingsSnapshot(
    val global: ScrollSettings,
    val profiles: Map<String, AppProfile>,
    val perAppEnabled: Boolean,
    val currentPackage: String?
)

/** What a disk load yields before reconciliation. */
data class LoadedState(
    val global: ScrollSettings,
    val profiles: Map<String, AppProfile>,
    val perAppEnabled: Boolean,
    val stats: ScrollStats
)

/**
 * The set of fields a reducer run wants changed. Only non-null fields are written by
 * [SettingsRepository.applyDelta]; null means "leave unchanged". This keeps the reducer
 * from overwriting fields it has no opinion about, and makes each intent's blast radius
 * explicit.
 */
data class SettingsDelta(
    val global: ScrollSettings? = null,
    val upsertedProfile: AppProfile? = null,
    val deletedPackage: String? = null,
    val perAppEnabled: Boolean? = null,
    val currentPackage: String? = null,
    /** Whether currentPackage should be cleared (distinct from setting it to some string). */
    val clearCurrentPackage: Boolean = false,
    /**
     * Wholesale replacement of the profiles map. Used only by [reconcileInitial] to push the
     * disk-loaded profiles into the holder in one shot. Null means "leave the map unchanged".
     * Mutating intents use [upsertedProfile] / [deletedPackage] instead.
     */
    val profiles: Map<String, AppProfile>? = null
)

/**
 * Stateless pure logic: the single home of every per-app consistency rule.
 * Inputs are immutable; output is a [SettingsDelta] that [SettingsRepository.apply]
 * applies atomically. Safe to replay under concurrency.
 */
object SettingsReducer {

    fun reduce(state: SettingsSnapshot, intent: SettingsIntent): SettingsDelta = when (intent) {
        is SettingsIntent.PackageSwitched -> {
            val hasProfile = intent.pkg != null && state.profiles.containsKey(intent.pkg)
            SettingsDelta(
                currentPackage = intent.pkg,
                clearCurrentPackage = intent.pkg == null,
                perAppEnabled = hasProfile
                // NOTE: deliberately does NOT touch global — switching apps must never reset
                // the user's global defaults (the historical bug from commit 193b9cb).
            )
        }
        is SettingsIntent.PerAppToggled -> {
            if (intent.enabled) {
                val pkg = state.currentPackage
                if (pkg != null) {
                    SettingsDelta(perAppEnabled = true, upsertedProfile = AppProfile(pkg, state.global))
                } else {
                    SettingsDelta(perAppEnabled = true)
                }
            } else {
                SettingsDelta(perAppEnabled = false, deletedPackage = state.currentPackage)
            }
        }
        is SettingsIntent.SettingEdited -> {
            val pkg = state.currentPackage
            if (state.perAppEnabled && pkg != null) {
                val existing = state.profiles[pkg]?.settings ?: state.global
                SettingsDelta(upsertedProfile = AppProfile(pkg, intent.transform(existing)))
            } else {
                SettingsDelta(global = intent.transform(state.global))
            }
        }
        is SettingsIntent.PresetApplied -> SettingsDelta(global = intent.settings)
        SettingsIntent.ForgetActiveApp -> {
            val pkg = state.currentPackage
            SettingsDelta(deletedPackage = pkg, perAppEnabled = false, clearCurrentPackage = pkg == null)
        }
    }

    /**
     * Merges a disk load with defaults. The ONLY place screen-height-adapted distanceRatio
     * is computed — applied once at load, only when the stored global still holds the default
     * distanceRatio (i.e. the user never customized it).
     */
    fun reconcileInitial(loaded: LoadedState, screenHeight: Int): SettingsDelta {
        val adapted = if (loaded.global.distanceRatio == ScrollSettings.DEFAULT.distanceRatio && screenHeight > 0) {
            loaded.global.copy(distanceRatio = (1500f / screenHeight).coerceIn(0.3f, 0.95f))
        } else {
            loaded.global
        }
        return SettingsDelta(
            global = adapted,
            upsertedProfile = null,
            deletedPackage = null,
            perAppEnabled = loaded.perAppEnabled,
            currentPackage = null,
            clearCurrentPackage = true,
            profiles = loaded.profiles
        )
    }
}
