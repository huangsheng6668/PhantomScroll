package com.phantom.scroll.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {

    private fun TestScope.repoWith(store: FakeProfileStore): SettingsRepository {
        // Build a scope whose Job is independent of this TestScope but whose dispatcher IS the
        // TestScope's TestDispatcher. This lets advanceUntilIdle() drive the repo's loads and
        // debounces via the shared dispatcher, while the long-lived collectors (and the eager
        // stateIn sharing) are NOT children of the TestScope — so runTest can finalize instead
        // of hanging on never-completing infinite collects.
        val repoScope = CoroutineScope(coroutineContext + Job())
        return SettingsRepository(store, repoScope).also { advanceUntilIdle() }
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
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.setCurrentPackage("com.example.novel")
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
        repo.setPerAppEnabled(true)
        repo.setCurrentPackage("com.example.novel")
        advanceUntilIdle()
        assertEquals(profileSettings, repo.activeSettings.value)
    }

    @Test
    fun activeSettings_falls_back_to_global_when_pkg_has_no_profile() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.setPerAppEnabled(true)
        repo.setCurrentPackage("com.unknown.app")
        advanceUntilIdle()
        assertEquals(repo.global.value, repo.activeSettings.value)
    }

    @Test
    fun updateGlobal_persists_after_debounce() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        val updated = ScrollSettings(duration = 650L, interval = 2200L, distanceRatio = 0.8f)
        repo.updateGlobal(updated)
        advanceUntilIdle() // advance virtual time past 500ms debounce
        assertEquals(updated, store.global)
    }

    @Test
    fun upsert_and_delete_profile_persist() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        val s = ScrollSettings(duration = 500L, interval = 2000L, distanceRatio = 0.75f)
        repo.upsertProfile("com.a", s)
        advanceUntilIdle()
        assertEquals(s, store.profiles["com.a"]?.settings)
        repo.deleteProfile("com.a")
        advanceUntilIdle()
        assertNull(store.profiles["com.a"])
    }

    @Test
    fun stats_increment_and_reset() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.incrementStats(swipeDelta = 1, elapsedDeltaMs = 2000L)
        repo.incrementStats(swipeDelta = 1, elapsedDeltaMs = 3000L)
        assertEquals(2L, repo.stats.value.swipeCount)
        assertEquals(5000L, repo.stats.value.elapsedMs)
        repo.resetStats()
        assertEquals(ScrollStats.ZERO, repo.stats.value)
    }

    @Test
    fun stats_persist_after_debounce() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.incrementStats(swipeDelta = 5, elapsedDeltaMs = 1000L)
        advanceUntilIdle()
        assertEquals(5L, store.stats.swipeCount)
    }

    @Test
    fun setPerAppEnabled_persists() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.setPerAppEnabled(true)
        advanceUntilIdle()
        assertTrue(store.perAppEnabled)
    }

    @Test
    fun isRunning_is_not_persisted() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.setRunning(true)
        advanceUntilIdle()
        // store has no isRunning field by design; nothing to assert except no crash + value held in memory
        assertEquals(true, repo.isRunning.value)
    }

    @Test
    fun currentPackage_initially_null() = runTest {
        val repo = repoWith(FakeProfileStore())
        assertNull(repo.currentPackage.value)
    }

    @Test
    fun applyPreset_writes_preset_settings_to_activeSettings() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.applyPreset(Preset.NOVEL)
        advanceUntilIdle()
        assertEquals(Preset.NOVEL.settings, repo.activeSettings.value)
        assertEquals(Preset.NOVEL.settings, store.global)
    }

    @Test
    fun applyPreset_writes_to_profile_when_perApp_on_and_pkg_known() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.setPerAppEnabled(true)
        repo.setCurrentPackage("com.example.novel")
        repo.applyPreset(Preset.NOVEL)
        advanceUntilIdle()
        assertEquals(Preset.NOVEL.settings, store.profiles["com.example.novel"]?.settings)
        // global untouched
        assertEquals(ScrollSettings.DEFAULT, repo.global.value)
    }

    @Test
    fun selectedPreset_tracks_activeSettings() = runTest {
        val repo = repoWith(FakeProfileStore())
        assertEquals(PresetSelection.Custom, repo.selectedPreset.value)
        repo.applyPreset(Preset.COMIC)
        advanceUntilIdle()
        assertEquals(PresetSelection.BuiltIn(Preset.COMIC), repo.selectedPreset.value)
    }

    @Test
    fun updateActive_writes_profile_when_perApp_on_and_pkg_known() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.setPerAppEnabled(true)
        repo.setCurrentPackage("com.example.novel")
        val s = ScrollSettings(duration = 600L, interval = 2000L, distanceRatio = 0.6f)
        repo.updateActive(s)
        advanceUntilIdle()
        assertEquals(s, store.profiles["com.example.novel"]?.settings)
        // global untouched
        assertEquals(ScrollSettings.DEFAULT, repo.global.value)
    }

    @Test
    fun updateActive_writes_global_when_perApp_off() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        // perApp default off, no currentPackage
        val s = ScrollSettings(duration = 650L, interval = 2100L, distanceRatio = 0.66f)
        repo.updateActive(s)
        advanceUntilIdle()
        assertEquals(s, repo.global.value)
        assertEquals(s, store.global)
        assertTrue(store.profiles.isEmpty())
    }

    @Test
    fun forgetActiveProfile_removes_current_pkg_profile() = runTest {
        val store = FakeProfileStore().apply {
            profiles["com.example.novel"] = AppProfile("com.example.novel", ScrollSettings.DEFAULT)
        }
        val repo = repoWith(store)
        repo.setPerAppEnabled(true)
        repo.setCurrentPackage("com.example.novel")
        advanceUntilIdle()
        repo.forgetActiveProfile()
        advanceUntilIdle()
        assertNull(store.profiles["com.example.novel"])
    }

    @Test
    fun forgetActiveProfile_no_op_when_no_current_pkg() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.forgetActiveProfile() // currentPackage null → no-op, no crash
        advanceUntilIdle()
        assertTrue(store.profiles.isEmpty())
    }
}
