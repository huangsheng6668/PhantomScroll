@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package com.phantom.scroll.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Single source of truth for all scroll settings, per-app profiles, stats and runtime flags.
 *
 * Initialization is asynchronous: the constructor seeds [global]/[profiles]/[stats] with
 * defaults and launches suspend [ProfileStore] reads in [scope]; values backfill once loaded.
 *
 * Non-suspend mutators (e.g. [setCurrentPackage], [incrementStats]) only mutate in-memory
 * [MutableStateFlow]s synchronously; persistence is performed by debounced collectors launched
 * in [scope], so these mutators are thread-safe and non-blocking from any caller (incl. Binder).
 */
class SettingsRepository(
    private val store: ProfileStore,
    scope: CoroutineScope
) {
    // ---- editable global defaults ----
    private val _global = MutableStateFlow(ScrollSettings.DEFAULT)
    val global: StateFlow<ScrollSettings> = _global.asStateFlow()

    // ---- per-app profiles ----
    private val _profiles = MutableStateFlow<Map<String, AppProfile>>(emptyMap())
    val profiles: StateFlow<Map<String, AppProfile>> = _profiles.asStateFlow()

    private val _perAppEnabled = MutableStateFlow(false)
    val perAppEnabled: StateFlow<Boolean> = _perAppEnabled.asStateFlow()

    private val _currentPackage = MutableStateFlow<String?>(null)
    val currentPackage: StateFlow<String?> = _currentPackage.asStateFlow()

    // ---- runtime stats ----
    private val _stats = MutableStateFlow(ScrollStats.ZERO)
    val stats: StateFlow<ScrollStats> = _stats.asStateFlow()

    // ---- runtime-only flags (not persisted) ----
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /** Mutable handle for components that drive isRunning directly (ScreenStateCoordinator). */
    val isRunningMutable: MutableStateFlow<Boolean> get() = _isRunning

    // dynamic display dimensions (pixels)
    private val _screenWidth = MutableStateFlow(0)
    val screenWidth: StateFlow<Int> = _screenWidth.asStateFlow()
    val screenWidthMutable: MutableStateFlow<Int> get() = _screenWidth
    private val _screenHeight = MutableStateFlow(0)
    val screenHeight: StateFlow<Int> = _screenHeight.asStateFlow()
    val screenHeightMutable: MutableStateFlow<Int> get() = _screenHeight

    fun setScreenWidth(value: Int) { _screenWidth.value = value }
    fun setScreenHeight(value: Int) { _screenHeight.value = value }

    /**
     * Resolved effective settings: the per-app profile for [currentPackage] when per-app is on,
     * otherwise the global defaults. This is the single value gesture generation consumes.
     */
    val activeSettings: StateFlow<ScrollSettings> =
        combine(_perAppEnabled, _currentPackage, _profiles, _global) { enabled, pkg, profiles, global ->
            if (enabled && pkg != null) profiles[pkg]?.settings ?: global else global
        }.stateIn(scope, SharingStarted.Eagerly, ScrollSettings.DEFAULT)

    /** Which built-in preset (or Custom) the ACTIVE settings currently match. Derived from
     *  [activeSettings] (not [global]) so the highlight reflects what the panel is actually
     *  editing — e.g. when per-app is on and the current app has a profile, the chip reflects
     *  that profile, and editing it flips to "自定义" (spec §3.1/§3.4). */
    val selectedPreset: StateFlow<PresetSelection> =
        activeSettings.map { PresetRegistry.selectionFor(it) }
            .stateIn(scope, SharingStarted.Eagerly, PresetRegistry.selectionFor(activeSettings.value))

    init {
        // Async load (DataStore is async I/O) — backfill defaults once read completes.
        scope.launch {
            _global.value = store.loadGlobal()
            _profiles.value = store.loadProfiles()
            _perAppEnabled.value = store.loadPerAppEnabled()
            _stats.value = store.loadStats()

            // Start persistence collectors AFTER the initial load completes.
            // drop(1) filters out the just-loaded values, collecting subsequent mutations only,
            // which avoids a redundant startup write-back. The collectors are long-lived
            // (infinite collect) and are children of [scope], so they are cancelled together
            // with the repo's owner scope (e.g. serviceScope in PhantomScrollService).
            launch {
                _global.drop(1).debounce(PERSIST_DEBOUNCE_MS).collect { store.saveGlobal(it) }
            }
            launch {
                _profiles.drop(1).debounce(PERSIST_DEBOUNCE_MS).collect { store.saveAllProfiles(it) }
            }
            launch {
                _perAppEnabled.drop(1).debounce(PERSIST_DEBOUNCE_MS).collect { store.savePerAppEnabled(it) }
            }
            launch {
                _stats.drop(1).debounce(PERSIST_DEBOUNCE_MS).collect { store.saveStats(it) }
            }
        }
    }

    // ---- mutations ----
    suspend fun updateGlobal(settings: ScrollSettings) { _global.value = settings }

    /** Applies a built-in preset to the ACTIVE target (spec §3.1): the current package's profile
     *  when per-app is on and a package is known, otherwise the global defaults. Delegates to
     *  [updateActive] so it shares the same per-app semantics as Slider edits. */
    suspend fun applyPreset(preset: Preset) { updateActive(preset.settings) }

    /**
     * Writes [settings] to the active target: the current package's profile when per-app is on
     * and a current package is known (dynamically creating the profile), otherwise the global
     * defaults (spec §3.4 "首次调整自动创建 profile"). Persistence is debounced via collectors.
     */
    suspend fun updateActive(settings: ScrollSettings) {
        val pkg = _currentPackage.value
        if (_perAppEnabled.value && pkg != null) {
            upsertProfile(pkg, settings)
        } else {
            _global.value = settings
        }
    }

    /** Forgets the current package's profile so activeSettings falls back to global. No-op if no
     *  current package. (spec §3.4 "忘记当前 App 配置") */
    suspend fun forgetActiveProfile() {
        val pkg = _currentPackage.value ?: return
        deleteProfile(pkg)
    }

    suspend fun upsertProfile(packageName: String, settings: ScrollSettings) {
        _profiles.update { current ->
            current + (packageName to AppProfile(packageName, settings))
        }
    }
    suspend fun deleteProfile(packageName: String) {
        _profiles.update { current ->
            current - packageName
        }
    }
    fun setCurrentPackage(packageName: String?) { _currentPackage.value = packageName }
    fun setPerAppEnabled(enabled: Boolean) { _perAppEnabled.value = enabled }
    fun setRunning(value: Boolean) { _isRunning.value = value }
    /** Stops autoscroll (runtime-only). Used by failure auto-pause and external stop. */
    fun stopRunning() { setRunning(false) }
    /** Starts autoscroll (runtime-only). */
    fun startRunning() { setRunning(true) }
    /** Toggles autoscroll (runtime-only). */
    fun toggleRunning() { setRunning(!_isRunning.value) }

    fun incrementStats(swipeDelta: Long = 1, elapsedDeltaMs: Long) {
        _stats.update { current ->
            current.copy(
                swipeCount = current.swipeCount + swipeDelta,
                elapsedMs = current.elapsedMs + elapsedDeltaMs
            )
        }
    }
    fun resetStats() { _stats.value = ScrollStats.ZERO }

    private companion object {
        const val PERSIST_DEBOUNCE_MS = 500L
    }
}
