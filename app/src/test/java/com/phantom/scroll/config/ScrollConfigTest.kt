package com.phantom.scroll.config

import com.phantom.scroll.data.FakeProfileStore
import com.phantom.scroll.data.ScrollSettings
import com.phantom.scroll.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 1: ScrollConfig is now a @Deprecated bridge over SettingsRepository.
 * These tests cover only bridge adapter behavior; persistence/active-resolution coverage
 * lives in SettingsRepositoryTest. The bridge's long-lived collectors are isolated in a
 * detached dispatcher-shared scope so runTest can finalize.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScrollConfigTest {

    @Suppress("DEPRECATION")
    private fun TestScope.makeConfig(global: ScrollSettings): ScrollConfig {
        // Detached job (independent of the TestScope's job) but shared TestDispatcher,
        // so advanceUntilIdle() drives the bridge's collectors while runTest can complete.
        val scope = CoroutineScope(coroutineContext + Job())
        val store = FakeProfileStore().apply { this.global = global }
        val repo = SettingsRepository(store, scope)
        return ScrollConfig(repo, scope)
    }

    @Test
    fun bridge_seeds_field_flows_from_repository_global() = runTest {
        val config = makeConfig(ScrollSettings(duration = 650L, interval = 2500L, distanceRatio = 0.8f))
        advanceUntilIdle() // let repo load + bridge onEach propagate
        assertEquals(650L, config.scrollDuration.value)
        assertEquals(2500L, config.scrollInterval.value)
        assertEquals(0.8f, config.scrollDistanceRatio.value)
    }

    @Test
    fun snapshot_reflects_active_settings() = runTest {
        val config = makeConfig(ScrollSettings(duration = 700L, interval = 3000L, distanceRatio = 0.55f))
        advanceUntilIdle()
        val snap = config.snapshot()
        assertEquals(700L, snap.duration)
        assertEquals(3000L, snap.interval)
        assertEquals(0.55f, snap.distanceRatio)
    }
}
