package com.phantom.scroll.data

/**
 * Persistence boundary for [SettingsRepository]. All reads/writes are suspend
 * because the production backing store (DataStore) is inherently async I/O.
 * Tests supply [FakeProfileStore] (in-memory) to keep repository logic pure-JVM.
 */
interface ProfileStore {
    suspend fun loadGlobal(): ScrollSettings
    suspend fun loadProfiles(): Map<String, AppProfile>

    suspend fun saveGlobal(settings: ScrollSettings)
    suspend fun saveAllProfiles(profiles: Map<String, AppProfile>)
    suspend fun saveProfile(profile: AppProfile)
    suspend fun deleteProfile(packageName: String)
}
