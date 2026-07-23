package com.phantom.scroll.data

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
    fun activeSettings_falls_back_to_global_when_perApp_disabled() = runTest {
        val repo = repoWith(FakeProfileStore())
        repo.apply(SettingsIntent.PackageSwitched("com.example.novel"))
        advanceUntilIdle()
        assertEquals(repo.global.value, repo.activeSettings.value)
    }

    @Test
    fun activeSettings_uses_profile_when_perApp_enabled_and_pkg_matches() = runTest {
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
        assertTrue(store.perAppEnabled)
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
        assertFalse(store.perAppEnabled)
    }

    @Test
    fun stats_increment_and_reset() = runTest {
        val repo = repoWith(FakeProfileStore())
        repo.incrementStats(swipeDelta = 1, elapsedDeltaMs = 2000L)
        repo.incrementStats(swipeDelta = 1, elapsedDeltaMs = 3000L)
        assertEquals(2L, repo.stats.value.swipeCount)
        assertEquals(5000L, repo.stats.value.elapsedMs)
        repo.resetStats()
        assertEquals(ScrollStats.ZERO, repo.stats.value)
    }

    @Test
    fun stats_persist_after_flush() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.incrementStats(swipeDelta = 5, elapsedDeltaMs = 1000L)
        repo.flush()
        assertEquals(5L, store.stats.swipeCount)
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
        assertFalse(repo.perAppEnabled.value)
    }
}

