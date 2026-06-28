package com.phantom.scroll.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.migrations.SharedPreferencesMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * DataStore-backed [ProfileStore]. Migrates the legacy SharedPreferences file
 * ("phantom_scroll_prefs") on first access via [SharedPreferencesMigration],
 * delegating key mapping to the pure [MigrationMapper].
 */
class DataStoreProfileStore(private val context: Context) : ProfileStore {

    private val dataStore: DataStore<Preferences> = context.phantomDataStore

    private object Keys {
        // global
        val DURATION = longPreferencesKey("global.duration")
        val INTERVAL = longPreferencesKey("global.interval")
        val RATIO = floatPreferencesKey("global.distanceRatio")
        val DIRECTION = stringPreferencesKey("global.direction")
        // per-app
        val PERAPP_ENABLED = booleanPreferencesKey("perapp.enabled")
        // stats
        val STATS_SWIPE = longPreferencesKey("stats.swipe")
        val STATS_ELAPSED = longPreferencesKey("stats.elapsed")
    }

    override suspend fun loadGlobal(): ScrollSettings {
        val p = dataStore.data.first()
        val direction = p[Keys.DIRECTION]?.let { runCatching { ScrollDirection.valueOf(it) }.getOrNull() }
            ?: ScrollDirection.DOWN
        return ScrollSettings(
            duration = p[Keys.DURATION] ?: ScrollSettings.DEFAULT.duration,
            interval = p[Keys.INTERVAL] ?: ScrollSettings.DEFAULT.interval,
            distanceRatio = p[Keys.RATIO] ?: ScrollSettings.DEFAULT.distanceRatio,
            direction = direction
        )
    }

    override suspend fun loadProfiles(): Map<String, AppProfile> {
        val p = dataStore.data.first()
        // profiles stored under profile.<pkg>.<field>
        val pkgs = p.asMap().keys
            .mapNotNull { it.name }
            .mapNotNull { profilePackageFromKey(it) }
            .toSet()
        return pkgs.associateWith { pkg -> AppProfile(pkg, loadProfileInternal(p, pkg)) }
    }

    override suspend fun loadPerAppEnabled(): Boolean =
        dataStore.data.first()[Keys.PERAPP_ENABLED] ?: false

    override suspend fun loadStats(): ScrollStats {
        val p = dataStore.data.first()
        return ScrollStats(
            swipeCount = p[Keys.STATS_SWIPE] ?: 0L,
            elapsedMs = p[Keys.STATS_ELAPSED] ?: 0L
        )
    }

    override suspend fun saveGlobal(settings: ScrollSettings) {
        dataStore.edit { it ->
            it[Keys.DURATION] = settings.duration
            it[Keys.INTERVAL] = settings.interval
            it[Keys.RATIO] = settings.distanceRatio
            it[Keys.DIRECTION] = settings.direction.name
        }
    }

    override suspend fun saveProfile(profile: AppProfile) {
        dataStore.edit { it -> writeProfile(it, profile.packageName, profile.settings) }
    }

    override suspend fun deleteProfile(packageName: String) {
        dataStore.edit { it ->
            it.remove(longPreferencesKey("$PROFILE_PREFIX$packageName.duration"))
            it.remove(longPreferencesKey("$PROFILE_PREFIX$packageName.interval"))
            it.remove(floatPreferencesKey("$PROFILE_PREFIX$packageName.distanceRatio"))
            it.remove(stringPreferencesKey("$PROFILE_PREFIX$packageName.direction"))
        }
    }

    override suspend fun saveAllProfiles(profiles: Map<String, AppProfile>) {
        dataStore.edit { it ->
            // clear existing profile keys first
            it.asMap().keys.forEach { key ->
                if (key.name.startsWith(PROFILE_PREFIX)) it.remove(key)
            }
            profiles.values.forEach { profile -> writeProfile(it, profile.packageName, profile.settings) }
        }
    }

    override suspend fun savePerAppEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.PERAPP_ENABLED] = enabled }
    }

    override suspend fun saveStats(stats: ScrollStats) {
        dataStore.edit { it ->
            it[Keys.STATS_SWIPE] = stats.swipeCount
            it[Keys.STATS_ELAPSED] = stats.elapsedMs
        }
    }

    private fun loadProfileInternal(p: Preferences, pkg: String): ScrollSettings = ScrollSettings(
        duration = p[longPreferencesKey("$PROFILE_PREFIX$pkg.duration")] ?: ScrollSettings.DEFAULT.duration,
        interval = p[longPreferencesKey("$PROFILE_PREFIX$pkg.interval")] ?: ScrollSettings.DEFAULT.interval,
        distanceRatio = p[floatPreferencesKey("$PROFILE_PREFIX$pkg.distanceRatio")] ?: ScrollSettings.DEFAULT.distanceRatio,
        direction = p[stringPreferencesKey("$PROFILE_PREFIX$pkg.direction")]?.let {
            runCatching { ScrollDirection.valueOf(it) }.getOrNull()
        } ?: ScrollDirection.DOWN
    )

    private fun writeProfile(
        it: MutablePreferences,
        pkg: String,
        s: ScrollSettings
    ) {
        it[longPreferencesKey("$PROFILE_PREFIX$pkg.duration")] = s.duration
        it[longPreferencesKey("$PROFILE_PREFIX$pkg.interval")] = s.interval
        it[floatPreferencesKey("$PROFILE_PREFIX$pkg.distanceRatio")] = s.distanceRatio
        it[stringPreferencesKey("$PROFILE_PREFIX$pkg.direction")] = s.direction.name
    }

    private companion object {
        const val PROFILE_PREFIX = "profile."
    }
}

/**
 * Pure: extracts the package name from a `profile.<pkg>.<field>` DataStore key.
 * Returns null if [key] is not a profile key. Handles dotted package names
 * (e.g. "profile.com.example.app.duration" -> "com.example.app") by stripping
 * the trailing field suffix via the LAST dot, not the first.
 */
internal fun profilePackageFromKey(key: String, prefix: String = "profile."): String? {
    if (!key.startsWith(prefix)) return null
    return key.removePrefix(prefix).substringBeforeLast('.')
}

// Top-level DataStore delegate (one instance per process). File name mirrors the legacy prefs
// conceptually; legacy data is migrated from "phantom_scroll_prefs" SharedPreferences.
//
// NOTE on DataStore 1.1.1 API: the androidx.datastore.preferences module's
// SharedPreferencesMigration(context, name, keys) overload does NOT accept a custom migrate
// lambda (it only copies same-named keys). To remap legacy keys -> global.*, we use the
// androidx.datastore.migrations.SharedPreferencesMigration constructor directly, whose
// `migrate` lambda receives a SharedPreferencesView (restricted to keysToMigrate) and is
// suspend. The lambda runs once on first DataStore creation and is idempotent thereafter.
private val Context.phantomDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "phantom_scroll_datastore",
    produceMigrations = { context ->
        listOf(
            SharedPreferencesMigration(
                context,
                "phantom_scroll_prefs",
                setOf("scroll_duration", "scroll_interval", "scroll_distance_ratio")
            ) { sharedPrefs, currentData ->
                // Map legacy keys into the new global.* keys via the pure mapper.
                // SharedPreferencesView.contains(key) is restricted to keysToMigrate, so absent
                // keys correctly fall through to the DEFAULT values inside MigrationMapper.
                val legacy = MigrationMapper.buildGlobalFromLegacy(
                    duration = if (sharedPrefs.contains("scroll_duration")) {
                        sharedPrefs.getLong("scroll_duration", ScrollSettings.DEFAULT.duration)
                    } else null,
                    interval = if (sharedPrefs.contains("scroll_interval")) {
                        sharedPrefs.getLong("scroll_interval", ScrollSettings.DEFAULT.interval)
                    } else null,
                    ratio = if (sharedPrefs.contains("scroll_distance_ratio")) {
                        sharedPrefs.getFloat("scroll_distance_ratio", ScrollSettings.DEFAULT.distanceRatio)
                    } else null
                )
                currentData.toMutablePreferences().apply {
                    this[longPreferencesKey("global.duration")] = legacy.duration
                    this[longPreferencesKey("global.interval")] = legacy.interval
                    this[floatPreferencesKey("global.distanceRatio")] = legacy.distanceRatio
                }.toPreferences()
            }
        )
    }
)
