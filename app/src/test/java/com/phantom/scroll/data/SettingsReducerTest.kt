package com.phantom.scroll.data

import com.phantom.scroll.gesture.ScrollDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsReducerTest {

    private fun snapshot(
        global: ScrollSettings = ScrollSettings.DEFAULT,
        profiles: Map<String, AppProfile> = emptyMap(),
        currentPackage: String? = null
    ) = SettingsSnapshot(global, profiles, currentPackage)

    private val customGlobal = ScrollSettings(duration = 999L, interval = 9999L, distanceRatio = 0.99f, direction = ScrollDirection.UP)

    @Test
    fun packageSwitched_sets_current_package_and_touches_nothing_else() {
        val pkg = "com.example.novel"
        val profile = AppProfile(pkg, ScrollSettings(duration = 700L, interval = 4000L, distanceRatio = 0.55f))
        val delta = SettingsReducer.reduce(
            snapshot(profiles = mapOf(pkg to profile), currentPackage = null),
            SettingsIntent.PackageSwitched(pkg)
        )
        assertEquals(pkg, delta.currentPackage)
        // The per-app switch is DERIVED from profile existence — a foreground switch
        // must never mutate profiles or global.
        assertNull(delta.upsertedProfile)
        assertNull(delta.deletedPackage)
        assertNull(delta.global)
    }

    @Test
    fun packageSwitched_without_profile_does_not_touch_global() {
        val delta = SettingsReducer.reduce(
            snapshot(global = customGlobal, currentPackage = null),
            SettingsIntent.PackageSwitched("com.unknown.app")
        )
        assertEquals("com.unknown.app", delta.currentPackage)
        assertNull(delta.global)
    }

    @Test
    fun packageSwitched_null_pkg_clears_current_package() {
        val delta = SettingsReducer.reduce(
            snapshot(currentPackage = "com.a"),
            SettingsIntent.PackageSwitched(null)
        )
        assertNull(delta.currentPackage)
        assertTrue(delta.clearCurrentPackage)
    }

    @Test
    fun perAppToggled_true_clones_global_to_current_pkg_profile() {
        val pkg = "com.example.novel"
        val delta = SettingsReducer.reduce(
            snapshot(global = customGlobal, currentPackage = pkg),
            SettingsIntent.PerAppToggled(true)
        )
        assertEquals(AppProfile(pkg, customGlobal), delta.upsertedProfile)
        assertNull(delta.deletedPackage)
    }

    @Test
    fun perAppToggled_true_keeps_existing_profile_settings() {
        val pkg = "com.example.novel"
        val existing = ScrollSettings(duration = 600L, interval = 2000L, distanceRatio = 0.6f)
        val delta = SettingsReducer.reduce(
            snapshot(profiles = mapOf(pkg to AppProfile(pkg, existing)), currentPackage = pkg),
            SettingsIntent.PerAppToggled(true)
        )
        assertEquals(AppProfile(pkg, existing), delta.upsertedProfile)
    }

    @Test
    fun perAppToggled_true_without_foreground_app_is_noop() {
        val delta = SettingsReducer.reduce(
            snapshot(currentPackage = null),
            SettingsIntent.PerAppToggled(true)
        )
        assertNull(delta.upsertedProfile)
        assertNull(delta.deletedPackage)
    }

    @Test
    fun perAppToggled_false_deletes_current_pkg_profile_only() {
        val pkg = "com.example.novel"
        val other = "com.example.other"
        val otherProfile = AppProfile(other, ScrollSettings.DEFAULT)
        val delta = SettingsReducer.reduce(
            snapshot(
                profiles = mapOf(pkg to AppProfile(pkg, ScrollSettings.DEFAULT), other to otherProfile),
                currentPackage = pkg
            ),
            SettingsIntent.PerAppToggled(false)
        )
        // Regression: turning the switch off in the current app must NOT affect other apps.
        assertEquals(pkg, delta.deletedPackage)
        assertNull(delta.upsertedProfile)
    }

    @Test
    fun settingEdited_writes_profile_when_current_pkg_is_recorded() {
        val pkg = "com.example.novel"
        val transformed = ScrollSettings(duration = 600L, interval = 2000L, distanceRatio = 0.6f)
        val delta = SettingsReducer.reduce(
            snapshot(global = customGlobal, profiles = mapOf(pkg to AppProfile(pkg, customGlobal)), currentPackage = pkg),
            SettingsIntent.SettingEdited { transformed }
        )
        assertEquals(AppProfile(pkg, transformed), delta.upsertedProfile)
        assertNull(delta.global)
    }

    @Test
    fun settingEdited_writes_global_when_current_pkg_not_recorded() {
        val transformed = ScrollSettings(duration = 650L, interval = 2100L, distanceRatio = 0.66f)
        val delta = SettingsReducer.reduce(
            snapshot(profiles = emptyMap(), currentPackage = "com.unknown.app"),
            SettingsIntent.SettingEdited { transformed }
        )
        assertEquals(transformed, delta.global)
        assertNull(delta.upsertedProfile)
    }

    @Test
    fun settingEdited_writes_global_when_no_foreground_app() {
        val transformed = ScrollSettings(duration = 650L, interval = 2100L, distanceRatio = 0.66f)
        val delta = SettingsReducer.reduce(
            snapshot(currentPackage = null),
            SettingsIntent.SettingEdited { transformed }
        )
        assertEquals(transformed, delta.global)
        assertNull(delta.upsertedProfile)
    }

    @Test
    fun presetApplied_writes_global() {
        val preset = ScrollSettings(duration = 700L, interval = 4000L, distanceRatio = 0.55f, direction = ScrollDirection.UP)
        val delta = SettingsReducer.reduce(snapshot(), SettingsIntent.PresetApplied(preset))
        assertEquals(preset, delta.global)
    }

    @Test
    fun forgetActiveApp_deletes_profile() {
        val pkg = "com.example.novel"
        val delta = SettingsReducer.reduce(
            snapshot(currentPackage = pkg),
            SettingsIntent.ForgetActiveApp
        )
        assertEquals(pkg, delta.deletedPackage)
    }

    @Test
    fun reconcileInitial_applies_screen_adapted_distanceRatio_only_at_default() {
        // loaded global has DEFAULT distanceRatio (0.75) → screen-adapted: 1500px divided
        // by the SAFE-zone height (0.7 × 2000 = 1400) = 1.071 → clamped to the 0.95 max.
        val loaded = LoadedState(
            global = ScrollSettings.DEFAULT,
            profiles = emptyMap()
        )
        val delta = SettingsReducer.reconcileInitial(loaded, screenHeight = 2000)
        assertEquals(0.95f, delta.global!!.distanceRatio, 0.0001f) // 1500/1400 clamped to 0.95
        assertEquals(500L, delta.global!!.duration) // duration stays default (ScrollSettings.DEFAULT.duration = 500L)
    }

    @Test
    fun reconcileInitial_keeps_user_distanceRatio_if_not_default() {
        val userSettings = ScrollSettings(duration = 800L, interval = 3000L, distanceRatio = 0.6f)
        val loaded = LoadedState(userSettings, emptyMap())
        val delta = SettingsReducer.reconcileInitial(loaded, screenHeight = 2000)
        assertEquals(0.6f, delta.global!!.distanceRatio, 0.0001f) // user value preserved
    }

    @Test
    fun perApp_switch_state_is_per_app_not_global() {
        // Regression for "切换App再切回原App时按钮就被关了" and
        // "从A切到B…再切回A开关还是关的": the switch is derived from whether the
        // CURRENT app has a profile, so each app shows its own state and toggling in
        // one app never affects another.
        val pkgA = "com.example.a"
        val profileA = AppProfile(pkgA, ScrollSettings.DEFAULT)

        // In A: profile exists → recording ON for A.
        val recordA = SettingsReducer.reduce(
            snapshot(profiles = mapOf(pkgA to profileA), currentPackage = null),
            SettingsIntent.PackageSwitched(pkgA)
        )
        assertEquals(pkgA, recordA.currentPackage)

        // Switch to B: B has no profile → nothing recorded for B, and A is untouched.
        val switchB = SettingsReducer.reduce(
            snapshot(profiles = mapOf(pkgA to profileA), currentPackage = pkgA),
            SettingsIntent.PackageSwitched("com.example.b")
        )
        assertEquals("com.example.b", switchB.currentPackage)
        assertNull(switchB.upsertedProfile)
        assertNull(switchB.deletedPackage)

        // Turn the switch OFF in B: only B is affected (no profile to delete).
        val offB = SettingsReducer.reduce(
            snapshot(profiles = mapOf(pkgA to profileA), currentPackage = "com.example.b"),
            SettingsIntent.PerAppToggled(false)
        )
        assertEquals("com.example.b", offB.deletedPackage)
        assertNull(offB.upsertedProfile) // A's profile is untouched by the B-only delta

        // Back in A: A's profile still exists → its switch is ON again.
        val backA = SettingsReducer.reduce(
            snapshot(profiles = mapOf(pkgA to profileA), currentPackage = "com.example.b"),
            SettingsIntent.PackageSwitched(pkgA)
        )
        assertEquals(pkgA, backA.currentPackage)
        assertNull(backA.deletedPackage)
        assertNull(backA.global)
    }

    @Test
    fun turning_switch_on_in_unrecorded_app_records_it() {
        // "B根本没被记录" → toggling ON in B records B with the global defaults.
        val delta = SettingsReducer.reduce(
            snapshot(global = customGlobal, currentPackage = "com.example.b"),
            SettingsIntent.PerAppToggled(true)
        )
        assertEquals(AppProfile("com.example.b", customGlobal), delta.upsertedProfile)
    }
}
