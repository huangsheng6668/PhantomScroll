package com.phantom.scroll.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsRepositoryConcurrencyTest {

    private fun TestScope.repoWith(store: FakeProfileStore): SettingsRepository {
        val repoScope = CoroutineScope(coroutineContext + Job())
        val testDispatcher = checkNotNull(coroutineContext[CoroutineDispatcher])
        return SettingsRepository(store, repoScope, ioDispatcher = testDispatcher)
            .also { advanceUntilIdle() }
    }

    // Race 1 (commit 6d95e25): defaults written back over real disk values during load.
    @Test
    fun load_completes_before_persistence_starts_no_default_overwrite() = runTest {
        val realDiskGlobal = ScrollSettings(duration = 800L, interval = 3000L, distanceRatio = 0.6f)
        val store = FakeProfileStore().apply { global = realDiskGlobal }
        val repo = repoWith(store)
        // After load, disk value must be intact (not overwritten by the DEFAULT seed).
        repo.flush()
        assertEquals(realDiskGlobal, store.global)
    }

    // Race 2 (commit 349feea): rapid per-app toggle leaves inconsistent profile/flag.
    @Test
    fun rapid_perApp_toggle_ends_consistent() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.apply(SettingsIntent.PackageSwitched("com.a"))
        repeat(5) {
            repo.apply(SettingsIntent.PerAppToggled(true))
            repo.apply(SettingsIntent.PerAppToggled(false))
        }
        repo.apply(SettingsIntent.PerAppToggled(true))
        advanceUntilIdle()
        assertTrue(repo.perAppEnabled.value)
        assertEquals("com.a", repo.profiles.value.keys.singleOrNull())
    }

    // Race 3 (commit 6d95e25): edit during a package switch writes to the wrong (old) target.
    @Test
    fun edit_during_package_switch_writes_to_current_not_old() = runTest {
        val repo = repoWith(FakeProfileStore())
        repo.apply(SettingsIntent.PackageSwitched("com.old"))
        repo.apply(SettingsIntent.PerAppToggled(true)) // per-app on, profile for com.old
        repo.apply(SettingsIntent.PackageSwitched("com.new"))
        repo.apply(SettingsIntent.PerAppToggled(true)) // profile for com.new
        // Edit now targets com.new, not com.old.
        val edited = ScrollSettings(duration = 600L, interval = 2000L, distanceRatio = 0.6f)
        repo.apply(SettingsIntent.SettingEdited { edited })
        advanceUntilIdle()
        assertEquals(edited, repo.profiles.value["com.new"]?.settings)
        assertNotEquals(edited, repo.profiles.value["com.old"]?.settings)
    }

    // Race 4 (T2): flush is non-blocking — runs on IO dispatcher, doesn't hang the caller.
    @Test
    fun flush_completes_without_blocking() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.apply(SettingsIntent.PresetApplied(ScrollSettings(duration = 700L, interval = 4000L, distanceRatio = 0.55f)))
        repo.flush() // suspend; completes within virtual time
        assertEquals(700L, store.global.duration)
    }

    // Race 5 (commit ededb56): high-frequency writes collapse to a single persisted write per debounce.
    @Test
    fun debounce_collapses_high_frequency_writes() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repeat(10) { i ->
            repo.apply(SettingsIntent.PresetApplied(ScrollSettings(duration = (500L + i), interval = 2000L, distanceRatio = 0.7f)))
        }
        // Before debounce elapses, disk still holds the seed/default.
        // advance past debounce window.
        advanceTimeBy(600)
        advanceUntilIdle()
        // Disk reflects the last value; intermediate ones collapsed.
        assertEquals(509L, store.global.duration)
    }

    // Race 6: flush on shutdown does not lose the last change.
    @Test
    fun flush_before_scope_cancel_persists_last_change() = runTest {
        val store = FakeProfileStore()
        val repoScope = CoroutineScope(coroutineContext + Job())
        val testDispatcher = checkNotNull(coroutineContext[CoroutineDispatcher])
        val repo = SettingsRepository(store, repoScope, ioDispatcher = testDispatcher)
        advanceUntilIdle()
        val last = ScrollSettings(duration = 750L, interval = 2500L, distanceRatio = 0.72f)
        repo.apply(SettingsIntent.PresetApplied(last))
        repo.flush() // explicit flush before cancel
        repoScope.cancel()
        assertEquals(last, store.global)
    }

    // Race 7 (commit f430d93): forgetActiveApp is atomic (profile delete + perApp off together).
    @Test
    fun forgetActiveApp_is_atomic() = runTest {
        val store = FakeProfileStore().apply {
            profiles["com.a"] = AppProfile("com.a", ScrollSettings.DEFAULT)
        }
        val repo = repoWith(store)
        repo.apply(SettingsIntent.PackageSwitched("com.a"))
        repo.apply(SettingsIntent.PerAppToggled(true))
        advanceUntilIdle()
        repo.apply(SettingsIntent.ForgetActiveApp)
        // After one apply(), both effects visible together (no intermediate inconsistent state).
        assertNull(repo.profiles.value["com.a"])
        assertFalse(repo.perAppEnabled.value)
    }
}
