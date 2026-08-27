package com.phantom.scroll.data

import com.phantom.scroll.util.PhantomLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Single source of truth for scroll settings, per-app profiles and runtime flags.
 *
 * Three-layer design:
 *  1. StateHolder — the [MutableStateFlow]s below; only [applyDelta] writes to the
 *     persistable ones.
 *  2. SettingsReducer — the stateless pure logic (in [SettingsReducer]) that decides
 *     how an intent transforms state; the single home of per-app consistency rules.
 *  3. Persistence — ONE debounce(500ms) collector over the persistable flows, started
 *     only after the initial load completes (so default seed values are never written
 *     back over real disk values). [flush] forces an immediate, awaitable write on
 *     service shutdown.
 *
 * Mutations enter only via [apply] (persistable) or the runtime mutators (isRunning /
 * screen size — non-persisted, no cross-field rules).
 */
@OptIn(FlowPreview::class)
class SettingsRepository(
    private val store: ProfileStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val _global = MutableStateFlow(ScrollSettings.DEFAULT)
    val global: StateFlow<ScrollSettings> = _global.asStateFlow()

    private val _profiles = MutableStateFlow<Map<String, AppProfile>>(emptyMap())
    val profiles: StateFlow<Map<String, AppProfile>> = _profiles.asStateFlow()

    private val _currentPackage = MutableStateFlow<String?>(null)
    val currentPackage: StateFlow<String?> = _currentPackage.asStateFlow()

    /**
     * Derived, NOT persisted: whether the CURRENT foreground app has its own recorded
     * profile. This is exactly what the overlay's "按App分别记录" switch displays — every
     * app shows its own recording state, and toggling only affects the current app
     * (PerAppToggled upserts / deletes the current app's profile). The old design stored
     * ONE global persisted flag, so the switch flip-flopped with the foreground app and
     * turning it off in app B also turned it off in app A.
     */
    val perAppEnabled: StateFlow<Boolean> =
        combine(_currentPackage, _profiles) { pkg, profiles ->
            pkg != null && profiles.containsKey(pkg)
        }.stateIn(scope, SharingStarted.Eagerly, false)

    private val _isRunning = MutableStateFlow(false)
    /** Backing mutable flow for components that drive isRunning directly (ScreenStateCoordinator). */
    val isRunning: MutableStateFlow<Boolean> = _isRunning

    private val _screenWidth = MutableStateFlow(0)
    val screenWidth: StateFlow<Int> = _screenWidth.asStateFlow()
    private val _screenHeight = MutableStateFlow(0)
    val screenHeight: StateFlow<Int> = _screenHeight.asStateFlow()

    /**
     * Resolved effective settings: the per-app profile for [currentPackage] when one is
     * recorded, otherwise the global defaults. This is the single value gesture generation
     * consumes. Profileless apps transparently fall back to global (spec: "切换回普通应用
     * 时自动还原全局默认").
     */
    val activeSettings: StateFlow<ScrollSettings> =
        combine(_currentPackage, _profiles, _global) { pkg, profiles, global ->
            if (pkg != null) profiles[pkg]?.settings ?: global else global
        }.stateIn(scope, SharingStarted.Eagerly, ScrollSettings.DEFAULT)

    private val initialized = CompletableDeferred<Unit>()

    init {
        scope.launch(ioDispatcher) {
            // 1. Explicit load — blocks this coroutine (not the main thread).
            val loaded = LoadedState(
                global = store.loadGlobal(),
                profiles = store.loadProfiles()
            )
            // 2. Reconcile defaults + screen-adapted distanceRatio exactly once.
            applyDelta(SettingsReducer.reconcileInitial(loaded, _screenHeight.value))
            // 3. Start the single persistence collector ONLY after disk values are in place,
            //    so seeded defaults are never written back over real values.
            startPersistenceCollector()
            initialized.complete(Unit)
        }
    }

    /** Suspends until the initial load + reconciliation has completed. For tests. */
    suspend fun awaitInitialized() = initialized.await()

    private fun startPersistenceCollector() {
        scope.launch(ioDispatcher) {
            combine(_global, _profiles) { g, p ->
                PersistableSnapshot(g, p)
            }.debounce(PERSIST_DEBOUNCE_MS)
                .collect { snapshot -> savePersistable(snapshot) }
        }
    }

    private suspend fun savePersistable(snapshot: PersistableSnapshot) {
        // I/O failure is non-fatal: log and let the next debounce tick retry. Throwing would
        // cancel the collector and silently stop ALL persistence.
        try {
            store.saveGlobal(snapshot.global)
            store.saveAllProfiles(snapshot.profiles)
        } catch (e: Exception) {
            PhantomLog.e(TAG, "Persist failed (will retry on next change): ${e.message}", e)
        }
    }

    /**
     * Forces an immediate, awaitable write of current in-memory state, skipping the debounce.
     * Called on service shutdown. Non-blocking from the caller's perspective — the caller
     * awaits completion on an IO dispatcher (Service.onDestroy launches this on Dispatchers.IO).
     */
    suspend fun flush() {
        // Wait for initial load so we don't flush defaults over real disk values.
        initialized.await()
        savePersistable(
            PersistableSnapshot(_global.value, _profiles.value)
        )
    }

    // ---- the single mutation entry for persistable state ----

    /**
     * Applies [intent] via [SettingsReducer] and writes the resulting delta atomically.
     * Thread-safe: StateFlow updates are atomic; the reducer is stateless and replayable.
     */
    fun apply(intent: SettingsIntent) {
        val snapshot = SettingsSnapshot(
            global = _global.value,
            profiles = _profiles.value,
            currentPackage = _currentPackage.value
        )
        val delta = SettingsReducer.reduce(snapshot, intent)
        // Reducer is pure logic — a delta it can't produce is a programming error.
        // (It currently can't fail, but this guard future-proofs new intents.)
        applyDelta(delta)
    }

    /** Applies a delta to the backing flows. The ONLY place persistable flows are written. */
    private fun applyDelta(delta: SettingsDelta) {
        delta.global?.let { _global.value = it }
        if (delta.upsertedProfile != null) {
            _profiles.update { it + (delta.upsertedProfile.packageName to delta.upsertedProfile) }
        }
        if (delta.deletedPackage != null) {
            _profiles.update { it - delta.deletedPackage }
        }
        delta.profiles?.let { loaded -> _profiles.value = loaded }
        if (delta.clearCurrentPackage) {
            _currentPackage.value = null
        } else if (delta.currentPackage != null) {
            _currentPackage.value = delta.currentPackage
        }
    }

    // ---- non-persisted runtime mutators (no cross-field rules) ----

    var isRunningValue: Boolean
        get() = _isRunning.value
        set(value) { _isRunning.value = value }

    fun toggleRunning() { _isRunning.value = !_isRunning.value }

    fun setScreenWidth(value: Int) { _screenWidth.value = value }
    fun setScreenHeight(value: Int) { _screenHeight.value = value }

    private data class PersistableSnapshot(
        val global: ScrollSettings,
        val profiles: Map<String, AppProfile>
    )

    private companion object {
        const val TAG = "SettingsRepository"
        const val PERSIST_DEBOUNCE_MS = 500L
    }
}
