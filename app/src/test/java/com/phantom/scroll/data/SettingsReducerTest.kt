package com.phantom.scroll.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsReducerTest {

    private fun snapshot(
        global: ScrollSettings = ScrollSettings.DEFAULT,
        profiles: Map<String, AppProfile> = emptyMap(),
        perAppEnabled: Boolean = false,
        currentPackage: String? = null
    ) = SettingsSnapshot(global, profiles, perAppEnabled, currentPackage)

    private val customGlobal = ScrollSettings(duration = 999L, interval = 9999L, distanceRatio = 0.99f, direction = ScrollDirection.UP)

    @Test
    fun packageSwitched_with_profile_enables_perApp() {
        val pkg = "com.example.novel"
        val profile = AppProfile(pkg, ScrollSettings(duration = 700L, interval = 4000L, distanceRatio = 0.55f))
        val delta = SettingsReducer.reduce(
            snapshot(profiles = mapOf(pkg to profile), currentPackage = null),
            SettingsIntent.PackageSwitched(pkg)
        )
        assertEquals(pkg, delta.currentPackage)
        assertTrue(delta.perAppEnabled!!)
        // global untouched — the key behavior change
        assertNull(delta.global)
    }

    @Test
    fun packageSwitched_without_profile_disables_perApp_and_does_not_touch_global() {
        val delta = SettingsReducer.reduce(
            snapshot(global = customGlobal, currentPackage = null),
            SettingsIntent.PackageSwitched("com.unknown.app")
        )
        assertEquals("com.unknown.app", delta.currentPackage)
        assertFalse(delta.perAppEnabled!!)
        assertNull(delta.global) // does NOT reset global
    }

    @Test
    fun packageSwitched_null_pkg_disables_perApp() {
        val delta = SettingsReducer.reduce(
            snapshot(perAppEnabled = true, currentPackage = "com.a"),
            SettingsIntent.PackageSwitched(null)
        )
        assertNull(delta.currentPackage)
        assertFalse(delta.perAppEnabled!!)
    }

    @Test
    fun perAppToggled_true_clones_global_to_current_pkg_profile() {
        val pkg = "com.example.novel"
        val delta = SettingsReducer.reduce(
            snapshot(global = customGlobal, currentPackage = pkg),
            SettingsIntent.PerAppToggled(true)
        )
        assertTrue(delta.perAppEnabled!!)
        assertEquals(AppProfile(pkg, customGlobal), delta.upsertedProfile)
    }

    @Test
    fun perAppToggled_false_deletes_current_pkg_profile() {
        val pkg = "com.example.novel"
        val delta = SettingsReducer.reduce(
            snapshot(currentPackage = pkg),
            SettingsIntent.PerAppToggled(false)
        )
        assertFalse(delta.perAppEnabled!!)
        assertEquals(pkg, delta.deletedPackage)
    }

    @Test
    fun settingEdited_writes_profile_when_perApp_on_and_pkg_known() {
        val pkg = "com.example.novel"
        val transformed = ScrollSettings(duration = 600L, interval = 2000L, distanceRatio = 0.6f)
        val delta = SettingsReducer.reduce(
            snapshot(global = customGlobal, perAppEnabled = true, currentPackage = pkg),
            SettingsIntent.SettingEdited { transformed }
        )
        assertEquals(AppProfile(pkg, transformed), delta.upsertedProfile)
        assertNull(delta.global)
    }

    @Test
    fun settingEdited_writes_global_when_perApp_off() {
        val transformed = ScrollSettings(duration = 650L, interval = 2100L, distanceRatio = 0.66f)
        val delta = SettingsReducer.reduce(
            snapshot(perAppEnabled = false),
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
    fun forgetActiveApp_deletes_profile_and_disables_perApp() {
        val pkg = "com.example.novel"
        val delta = SettingsReducer.reduce(
            snapshot(currentPackage = pkg, perAppEnabled = true),
            SettingsIntent.ForgetActiveApp
        )
        assertEquals(pkg, delta.deletedPackage)
        assertFalse(delta.perAppEnabled!!)
    }

    @Test
    fun reconcileInitial_applies_screen_adapted_distanceRatio_only_at_default() {
        // loaded global has DEFAULT distanceRatio (0.75) → screen-adapted (1500/h clamped)
        val loaded = LoadedState(
            global = ScrollSettings.DEFAULT,
            profiles = emptyMap(),
            perAppEnabled = false,
            stats = ScrollStats.ZERO
        )
        val delta = SettingsReducer.reconcileInitial(loaded, screenHeight = 2000)
        assertEquals(0.75f, delta.global!!.distanceRatio, 0.0001f) // 1500/2000 = 0.75
        assertEquals(500L, delta.global!!.duration) // duration stays default (ScrollSettings.DEFAULT.duration = 500L)
    }

    @Test
    fun reconcileInitial_keeps_user_distanceRatio_if_not_default() {
        val userSettings = ScrollSettings(duration = 800L, interval = 3000L, distanceRatio = 0.6f)
        val loaded = LoadedState(userSettings, emptyMap(), false, ScrollStats.ZERO)
        val delta = SettingsReducer.reconcileInitial(loaded, screenHeight = 2000)
        assertEquals(0.6f, delta.global!!.distanceRatio, 0.0001f) // user value preserved
    }
}
