package com.phantom.scroll.data

import com.phantom.scroll.gesture.SafeZone

/**
 * Read-only snapshot of the repository's persistable state, fed to [SettingsReducer].
 * The reducer never holds mutable state — this is its only input besides the intent.
 */
data class SettingsSnapshot(
    val global: ScrollSettings,
    val profiles: Map<String, AppProfile>,
    val currentPackage: String?
)

/** What a disk load yields before reconciliation. */
data class LoadedState(
    val global: ScrollSettings,
    val profiles: Map<String, AppProfile>
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
 *
 * Per-app model: "recording" is per APP, not a global toggle. An app is "recorded" iff a
 * profile exists for it — that is what the overlay switch displays (derived in
 * SettingsRepository.perAppEnabled). [PerAppToggled] creates / deletes the CURRENT app's
 * profile only, so switching apps never flips the switch and turning it off in app B
 * never affects app A.
 */
object SettingsReducer {

    fun reduce(state: SettingsSnapshot, intent: SettingsIntent): SettingsDelta = when (intent) {
        is SettingsIntent.PackageSwitched -> {
            // Only the foreground identity changes. Per-app recording state is derived
            // from profile existence and must never be mutated here.
            SettingsDelta(
                currentPackage = intent.pkg,
                clearCurrentPackage = intent.pkg == null
            )
        }
        is SettingsIntent.PerAppToggled -> {
            val pkg = state.currentPackage
            if (intent.enabled) {
                // Record the CURRENT app: keep its existing profile if any, otherwise
                // start it from the global defaults.
                if (pkg != null) {
                    SettingsDelta(
                        upsertedProfile = AppProfile(pkg, state.profiles[pkg]?.settings ?: state.global)
                    )
                } else {
                    SettingsDelta() // no foreground app to record — no-op
                }
            } else {
                // Stop recording the CURRENT app → forget its profile. Only this app is
                // affected; other apps keep their profiles and their switch states.
                if (pkg != null) SettingsDelta(deletedPackage = pkg) else SettingsDelta()
            }
        }
        is SettingsIntent.SettingEdited -> {
            val pkg = state.currentPackage
            if (pkg != null && state.profiles.containsKey(pkg)) {
                // The current app is recorded → the edit belongs to ITS profile.
                val existing = state.profiles[pkg]?.settings ?: state.global
                SettingsDelta(upsertedProfile = AppProfile(pkg, intent.transform(existing)))
            } else {
                // Not recorded (or no foreground app) → edit the global defaults.
                SettingsDelta(global = intent.transform(state.global))
            }
        }
        is SettingsIntent.PresetApplied -> SettingsDelta(global = intent.settings)
        SettingsIntent.ForgetActiveApp -> {
            val pkg = state.currentPackage
            if (pkg != null) {
                SettingsDelta(deletedPackage = pkg)
            } else {
                SettingsDelta()
            }
        }
    }

    /**
     * Merges a disk load with defaults. The ONLY place screen-height-adapted distanceRatio
     * is computed — applied once at load, only when the stored global still holds the default
     * distanceRatio (i.e. the user never customized it). The adaptation targets a physical
     * swipe of 1500px, which is a fraction of the SAFE zone height (not the full screen —
     * the engine swipes within 15%~85% of the screen), so 1500px is divided by
     * [SafeZone.HEIGHT_RATIO] × screenHeight.
     */
    fun reconcileInitial(loaded: LoadedState, screenHeight: Int): SettingsDelta {
        val adapted = if (loaded.global.distanceRatio == ScrollSettings.DEFAULT.distanceRatio && screenHeight > 0) {
            val safeHeight = screenHeight * SafeZone.HEIGHT_RATIO
            loaded.global.copy(distanceRatio = (1500f / safeHeight).coerceIn(0.3f, 0.95f))
        } else {
            loaded.global
        }
        return SettingsDelta(
            global = adapted,
            upsertedProfile = null,
            deletedPackage = null,
            currentPackage = null,
            clearCurrentPackage = true,
            profiles = loaded.profiles
        )
    }
}
