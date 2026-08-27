package com.phantom.scroll.data

import com.phantom.scroll.gesture.ScrollDirection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {

    private fun TestScope.repoWith(store: FakeProfileStore): SettingsRepository {
        val repoScope = CoroutineScope(coroutineContext + Job())
        val testDispatcher = checkNotNull(coroutineContext[CoroutineDispatcher]) {
            "TestScope must carry a CoroutineDispatcher"
        }
        return SettingsRepository(store, repoScope, ioDispatcher = testDispatcher)
            .also { advanceUntilIdle() } // let init load + reconcile + start collector run
    }

    @Test
    fun loads_initial_values_from_store() = runTest {
        val store = FakeProfileStore().apply {
            global = ScrollSettings(duration = 800L, interval = 3000L, distanceRatio = 0.6f)
        }
        val repo = repoWith(store)
        assertEquals(800L, repo.global.value.duration)
        assertEquals(0.6f, repo.global.value.distanceRatio)
    }

    @Test
    fun activeSettings_falls_back_to_global_when_no_profile() = runTest {
        val repo = repoWith(FakeProfileStore())
        repo.apply(SettingsIntent.PackageSwitched("com.example.novel"))
        advanceUntilIdle()
        assertEquals(repo.global.value, repo.activeSettings.value)
    }

    @Test
    fun activeSettings_uses_profile_when_current_pkg_recorded() = runTest {
        val profileSettings = ScrollSettings(duration = 999L, interval = 1111L, distanceRatio = 0.42f)
        val store = FakeProfileStore().apply {
            profiles["com.example.novel"] = AppProfile("com.example.novel", profileSettings)
        }
        val repo = repoWith(store)
        repo.apply(SettingsIntent.PackageSwitched("com.example.novel"))
        advanceUntilIdle()
        assertEquals(profileSettings, repo.activeSettings.value)
    }

    @Test
    fun activeSettings_falls_back_to_global_when_pkg_has_no_profile() = runTest {
        val repo = repoWith(FakeProfileStore())
        repo.apply(SettingsIntent.PerAppToggled(true))
        repo.apply(SettingsIntent.PackageSwitched("com.unknown.app"))
        advanceUntilIdle()
        assertEquals(repo.global.value, repo.activeSettings.value)
    }

    @Test
    fun packageSwitched_does_not_touch_global() = runTest {
        // Replaces old setCurrentPackage_resets_global_to_default — the behavior change:
        // switching to a profile-less app must NOT reset the user's global defaults.
        val repo = repoWith(FakeProfileStore())
        val custom = ScrollSettings(duration = 999L, interval = 9999L, distanceRatio = 0.99f, direction = ScrollDirection.UP)
        repo.apply(SettingsIntent.PresetApplied(custom))
        assertEquals(custom, repo.global.value)
        repo.apply(SettingsIntent.PackageSwitched("com.unknown.app"))
        assertEquals(custom, repo.global.value) // unchanged
    }

    @Test
    fun presetApplied_persists_after_debounce() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        val updated = ScrollSettings(duration = 650L, interval = 2200L, distanceRatio = 0.8f)
        repo.apply(SettingsIntent.PresetApplied(updated))
        repo.flush()
        assertEquals(updated, store.global)
    }

    @Test
    fun perAppToggled_true_creates_profile_and_persists() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.apply(SettingsIntent.PackageSwitched("com.a"))
        repo.apply(SettingsIntent.PerAppToggled(true))
        repo.flush()
        assertEquals(repo.global.value, store.profiles["com.a"]?.settings)
        advanceUntilIdle() // let the derived perAppEnabled flow process the upsert
        assertTrue(repo.perAppEnabled.value) // derived: com.a is now recorded
    }

    @Test
    fun perAppToggled_false_deletes_profile() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.apply(SettingsIntent.PackageSwitched("com.a"))
        repo.apply(SettingsIntent.PerAppToggled(true))
        repo.apply(SettingsIntent.PerAppToggled(false))
        repo.flush()
        assertNull(store.profiles["com.a"])
        advanceUntilIdle() // let the derived perAppEnabled flow process the delete
        assertFalse(repo.perAppEnabled.value) // derived: com.a no longer recorded
    }

    @Test
    fun isRunning_is_not_persisted_and_toggleable() = runTest {
        val repo = repoWith(FakeProfileStore())
        repo.isRunning.value = true
        advanceUntilIdle()
        assertEquals(true, repo.isRunning.value)
        repo.toggleRunning()
        assertEquals(false, repo.isRunning.value)
    }

    @Test
    fun currentPackage_initially_null() = runTest {
        val repo = repoWith(FakeProfileStore())
        assertNull(repo.currentPackage.value)
    }

    @Test
    fun settingEdited_writes_profile_when_perApp_on_and_pkg_known() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.apply(SettingsIntent.PackageSwitched("com.example.novel"))
        repo.apply(SettingsIntent.PerAppToggled(true))
        val s = ScrollSettings(duration = 600L, interval = 2000L, distanceRatio = 0.6f)
        repo.apply(SettingsIntent.SettingEdited { s })
        repo.flush()
        assertEquals(s, store.profiles["com.example.novel"]?.settings)
        assertEquals(ScrollSettings.DEFAULT, repo.global.value)
    }

    @Test
    fun settingEdited_writes_global_when_perApp_off() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        val s = ScrollSettings(duration = 650L, interval = 2100L, distanceRatio = 0.66f)
        repo.apply(SettingsIntent.SettingEdited { s })
        repo.flush()
        assertEquals(s, repo.global.value)
        assertEquals(s, store.global)
        assertTrue(store.profiles.isEmpty())
    }

    @Test
    fun forgetActiveApp_removes_current_pkg_profile() = runTest {
        val store = FakeProfileStore().apply {
            profiles["com.example.novel"] = AppProfile("com.example.novel", ScrollSettings.DEFAULT)
        }
        val repo = repoWith(store)
        repo.apply(SettingsIntent.PackageSwitched("com.example.novel"))
        advanceUntilIdle()
        repo.apply(SettingsIntent.ForgetActiveApp)
        repo.flush()
        assertNull(store.profiles["com.example.novel"])
        advanceUntilIdle() // let the derived perAppEnabled flow process the delete
        assertFalse(repo.perAppEnabled.value)
    }

    /**
     * Regression for the user-reported flows:
     *  1. "切换App再切回原App时按钮就被关了"
     *  2. "从A切到B，B根本没被记录；把开关关了，再切回A开关还是关的"
     *
     * With the derived per-app switch: each app shows its own recording state, toggling
     * only affects the current app, and switching back to a recorded app shows ON again.
     */
    @Test
    fun perApp_switch_state_is_per_app_across_switches() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)

        // Record A (toggle ON in A).
        repo.apply(SettingsIntent.PackageSwitched("com.a"))
        repo.apply(SettingsIntent.PerAppToggled(true))
        advanceUntilIdle()
        assertTrue(repo.perAppEnabled.value) // A recorded → ON in A
        assertTrue(store.profiles.containsKey("com.a"))

        // Switch to B: B has no profile → switch shows OFF for B (B not recorded).
        repo.apply(SettingsIntent.PackageSwitched("com.b"))
        advanceUntilIdle()
        assertFalse(repo.perAppEnabled.value)
        assertFalse(store.profiles.containsKey("com.b")) // visiting does NOT record

        // Toggle the switch OFF in B (no-op for profiles — B had none).
        repo.apply(SettingsIntent.PerAppToggled(false))
        advanceUntilIdle()

        // Toggle ON in B records B (cloned from global).
        repo.apply(SettingsIntent.PerAppToggled(true))
        advanceUntilIdle()
        assertTrue(store.profiles.containsKey("com.b"))
        assertTrue(repo.perAppEnabled.value)

        // Turn it OFF again in B: only B's profile goes away.
        repo.apply(SettingsIntent.PerAppToggled(false))
        advanceUntilIdle()
        assertFalse(store.profiles.containsKey("com.b"))
        assertTrue(store.profiles.containsKey("com.a")) // A untouched

        // Back in A: A is still recorded → switch ON again.
        repo.apply(SettingsIntent.PackageSwitched("com.a"))
        advanceUntilIdle()
        assertTrue(repo.perAppEnabled.value)
        assertEquals(store.profiles["com.a"], repo.profiles.value["com.a"])
    }
}

