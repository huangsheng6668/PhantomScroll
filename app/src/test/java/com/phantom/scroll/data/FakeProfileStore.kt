package com.phantom.scroll.data

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory ProfileStore for unit tests. Thread-safe; records all mutations
 * so tests can assert what was persisted.
 */
class FakeProfileStore : ProfileStore {
    @Volatile var global: ScrollSettings = ScrollSettings.DEFAULT
    @Volatile var perAppEnabled: Boolean = false
    @Volatile var stats: ScrollStats = ScrollStats.ZERO
    val profiles: MutableMap<String, AppProfile> = ConcurrentHashMap()

    /**
     * Number of times [saveGlobal] was invoked. AtomicInteger for strict
     * thread-safety — under runTest's single TestDispatcher the persistence
     * collector runs serially so a plain Int would also be correct, but the
     * store advertises itself as thread-safe and is also used outside virtual
     * time, so the counter follows the same guarantee. Used by the debounce
     * regression test to prove writes COLLAPSE (bounded count), not merely
     * that the final value is right (which last-write-wins would satisfy even
     * without debounce).
     */
    val globalSaveCount: AtomicInteger = AtomicInteger(0)

    override suspend fun loadGlobal(): ScrollSettings = global
    override suspend fun loadProfiles(): Map<String, AppProfile> = profiles.toMap()
    override suspend fun loadPerAppEnabled(): Boolean = perAppEnabled
    override suspend fun loadStats(): ScrollStats = stats

    override suspend fun saveGlobal(settings: ScrollSettings) {
        global = settings
        globalSaveCount.incrementAndGet()
    }
    override suspend fun saveAllProfiles(profiles: Map<String, AppProfile>) {
        this.profiles.clear()
        this.profiles.putAll(profiles)
    }
    override suspend fun saveProfile(profile: AppProfile) { profiles[profile.packageName] = profile }
    override suspend fun deleteProfile(packageName: String) { profiles.remove(packageName) }
    override suspend fun savePerAppEnabled(enabled: Boolean) { perAppEnabled = enabled }
    override suspend fun saveStats(stats: ScrollStats) { this.stats = stats }
}
