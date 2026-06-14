package com.phantom.scroll.data

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory ProfileStore for unit tests. Thread-safe; records all mutations
 * so tests can assert what was persisted.
 */
class FakeProfileStore : ProfileStore {
    @Volatile var global: ScrollSettings = ScrollSettings.DEFAULT
    @Volatile var perAppEnabled: Boolean = false
    @Volatile var stats: ScrollStats = ScrollStats.ZERO
    val profiles: MutableMap<String, AppProfile> = ConcurrentHashMap()

    override suspend fun loadGlobal(): ScrollSettings = global
    override suspend fun loadProfiles(): Map<String, AppProfile> = profiles.toMap()
    override suspend fun loadPerAppEnabled(): Boolean = perAppEnabled
    override suspend fun loadStats(): ScrollStats = stats

    override suspend fun saveGlobal(settings: ScrollSettings) { global = settings }
    override suspend fun saveAllProfiles(profiles: Map<String, AppProfile>) {
        this.profiles.clear()
        this.profiles.putAll(profiles)
    }
    override suspend fun saveProfile(profile: AppProfile) { profiles[profile.packageName] = profile }
    override suspend fun deleteProfile(packageName: String) { profiles.remove(packageName) }
    override suspend fun savePerAppEnabled(enabled: Boolean) { perAppEnabled = enabled }
    override suspend fun saveStats(stats: ScrollStats) { this.stats = stats }
}
