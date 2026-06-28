@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package com.phantom.scroll.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val scope: CoroutineScope,
    /**
     * Dispatcher used for DataStore disk I/O and the debounce suspension/resume of the
     * persistence collectors. Defaults to [Dispatchers.IO] so high-frequency stat mutations
     * (one per swipe) never touch the main thread. Injectable for tests.
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
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

    init {
        // Async load (DataStore is async I/O) — backfill defaults once read completes.
        scope.launch {
            // Initial load reads from disk — run on the IO dispatcher, not the main thread.
            val (g, p, enabled, s) = withContext(ioDispatcher) {
                Quad(store.loadGlobal(), store.loadProfiles(), store.loadPerAppEnabled(), store.loadStats())
            }
            _global.value = g
            _profiles.value = p
            _stats.value = s

            // Bind per-app switch status in real-time based on whether currentPackage has a profile
            val currentPkg = _currentPackage.value
            _perAppEnabled.value = currentPkg != null && p.containsKey(currentPkg)

            // Periodic saver runs on the IO dispatcher in the background.
            // It saves all settings every 5 minutes if any in-memory value has changed,
            // avoiding high-frequency disk I/O from slider drags.
            launch {
                var lastSavedGlobal = g
                var lastSavedProfiles = p
                var lastSavedPerApp = _perAppEnabled.value
                var lastSavedStats = s

                while (isActive) {
                    delay(5 * 60 * 1000L) // 5 minutes
                    val currentGlobal = _global.value
                    val currentProfiles = _profiles.value
                    val currentPerApp = _perAppEnabled.value
                    val currentStats = _stats.value

                    if (currentGlobal != lastSavedGlobal ||
                        currentProfiles != lastSavedProfiles ||
                        currentPerApp != lastSavedPerApp ||
                        currentStats != lastSavedStats
                    ) {
                        try {
                            withContext(ioDispatcher) {
                                store.saveGlobal(currentGlobal)
                                store.saveAllProfiles(currentProfiles)
                                store.savePerAppEnabled(currentPerApp)
                                store.saveStats(currentStats)
                            }
                            lastSavedGlobal = currentGlobal
                            lastSavedProfiles = currentProfiles
                            lastSavedPerApp = currentPerApp
                            lastSavedStats = currentStats
                            com.phantom.scroll.util.PhantomLog.d("SettingsRepository", "Periodic save (5 min): successfully recorded all values to disk.")
                        } catch (e: Exception) {
                            com.phantom.scroll.util.PhantomLog.e("SettingsRepository", "Periodic save failed: ${e.message}", e)
                        }
                    }
                }
            }
        }
    }

    /** Local 4-tuple (kotlin has no standard Quad) used only to load in one IO hop. */
    private class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D) {
        operator fun component1() = a
        operator fun component2() = b
        operator fun component3() = c
        operator fun component4() = d
    }

    /**
     * Flushes all current in-memory configurations directly to the persistent DataStore.
     * Called on service destruction or important state changes to ensure zero data loss.
     */
    suspend fun flush() {
        withContext(ioDispatcher) {
            try {
                store.saveGlobal(_global.value)
                store.saveAllProfiles(_profiles.value)
                store.savePerAppEnabled(_perAppEnabled.value)
                store.saveStats(_stats.value)
                com.phantom.scroll.util.PhantomLog.d("SettingsRepository", "Flush: all settings successfully written to disk.")
            } catch (e: Exception) {
                com.phantom.scroll.util.PhantomLog.e("SettingsRepository", "Flush failed: ${e.message}", e)
            }
        }
    }

    // ---- mutations ----
    suspend fun updateGlobal(settings: ScrollSettings) { _global.value = settings }

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
        _perAppEnabled.value = false
        withContext(ioDispatcher) {
            try {
                store.deleteProfile(pkg)
                store.saveAllProfiles(_profiles.value) // Flush remaining profiles
            } catch (e: Exception) {
                com.phantom.scroll.util.PhantomLog.e("SettingsRepository", "Failed to delete profile for $pkg: ${e.message}")
            }
        }
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
    fun setCurrentPackage(packageName: String?) {
        _currentPackage.value = packageName
        // Real-time update: automatically enable switch if a profile exists for this package
        _perAppEnabled.value = packageName != null && _profiles.value.containsKey(packageName)
    }
    fun setPerAppEnabled(enabled: Boolean) {
        _perAppEnabled.value = enabled
        val pkg = _currentPackage.value
        if (pkg != null) {
            scope.launch {
                if (enabled) {
                    // Automatically clone global settings when enabling per-app config for the first time
                    upsertProfile(pkg, _global.value)
                } else {
                    // Turn off per-app config: delete this package's profile so it reverts to global
                    deleteProfile(pkg)
                    withContext(ioDispatcher) {
                        try {
                            store.deleteProfile(pkg)
                            store.saveAllProfiles(_profiles.value)
                        } catch (e: Exception) {
                            com.phantom.scroll.util.PhantomLog.e("SettingsRepository", "Failed to delete profile: ${e.message}")
                        }
                    }
                }
                withContext(ioDispatcher) {
                    store.savePerAppEnabled(enabled)
                }
            }
        }
    }
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
