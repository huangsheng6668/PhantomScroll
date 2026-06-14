@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package com.phantom.scroll.config

import com.phantom.scroll.data.ScrollDirection
import com.phantom.scroll.data.ScrollSettings
import com.phantom.scroll.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * @Deprecated bridge that exposes the legacy field-level [MutableStateFlow] API on top of
 * [SettingsRepository], so the Compose FloatingPanel compiles unchanged during Phase 1.
 *
 * REMOVED in Phase 2 when the overlay becomes a native View consuming [SettingsRepository] directly.
 *
 * @param repository the single source of truth.
 * @param scope used for repo<->bridge two-way sync.
 */
@Deprecated("Bridge to SettingsRepository; removed in Phase 2. Use SettingsRepository directly.")
class ScrollConfig(
    private val repository: SettingsRepository,
    private val scope: CoroutineScope
) {
    // Field-level backing flows, seeded from repository then kept in sync.
    private val _scrollDuration = MutableStateFlow(repository.global.value.duration)
    val scrollDuration: MutableStateFlow<Long> = _scrollDuration

    private val _scrollInterval = MutableStateFlow(repository.global.value.interval)
    val scrollInterval: MutableStateFlow<Long> = _scrollInterval

    private val _scrollDistanceRatio = MutableStateFlow(repository.global.value.distanceRatio)
    val scrollDistanceRatio: MutableStateFlow<Float> = _scrollDistanceRatio

    // Delegated directly to repository (same backing instance, no duplication).
    val screenWidth: MutableStateFlow<Int> get() = repository.screenWidthMutable
    val screenHeight: MutableStateFlow<Int> get() = repository.screenHeightMutable
    val isRunning: MutableStateFlow<Boolean> get() = repository.isRunningMutable

    init {
        // repository.global -> backing fields (so external global changes reflect)
        repository.global
            .onEach { g ->
                _scrollDuration.value = g.duration
                _scrollInterval.value = g.interval
                _scrollDistanceRatio.value = g.distanceRatio
            }
            .launchIn(scope)

        // backing fields -> repository (debounced; drop first seed). StateFlow dedups equal
        // values, so no feedback loop when repository.global echoes the same numbers back.
        _scrollDuration.drop(1).debounce(500)
            .map { repository.global.value.copy(duration = it) }
            .onEach { scope.launch { repository.updateGlobal(it) } }
            .launchIn(scope)
        _scrollInterval.drop(1).debounce(500)
            .map { repository.global.value.copy(interval = it) }
            .onEach { scope.launch { repository.updateGlobal(it) } }
            .launchIn(scope)
        _scrollDistanceRatio.drop(1).debounce(500)
            .map { repository.global.value.copy(distanceRatio = it) }
            .onEach { scope.launch { repository.updateGlobal(it) } }
            .launchIn(scope)
    }

    /** Snapshot of the effective (active) settings for one swipe loop. */
    fun snapshot(): ConfigSnapshot {
        val a = repository.activeSettings.value
        return ConfigSnapshot(duration = a.duration, interval = a.interval, distanceRatio = a.distanceRatio)
    }
}

/**
 * Legacy snapshot shape retained for compatibility. [direction] lives on [ScrollSettings]
 * (Phase 3); the bridge snapshot stays field-compatible with the old API.
 */
data class ConfigSnapshot(
    val duration: Long,
    val interval: Long,
    val distanceRatio: Float
)
