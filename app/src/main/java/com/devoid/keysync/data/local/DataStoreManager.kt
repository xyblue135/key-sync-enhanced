package com.devoid.keysync.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.devoid.keysync.model.AppConfig
import com.devoid.keysync.model.DraggableItem
import com.devoid.keysync.model.Profile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Outcome of reading the persisted profile blob.
 *
 * The three cases must stay distinguishable. Collapsing "the store could not be
 * read" and "nothing was ever stored" into one empty list is what let a single
 * corrupt byte wipe every profile: the caller took the first-run branch and
 * overwrote the blob with freshly migrated defaults.
 */
sealed interface ProfilesLoad {
    /** Nothing has ever been persisted. Bootstrapping defaults is safe. */
    data object Absent : ProfilesLoad

    /** The blob decoded successfully. */
    data class Loaded(val profiles: List<Profile>) : ProfilesLoad

    /**
     * A blob exists but could not be decoded, or the store itself could not be
     * read. [raw] keeps the original text when it was available so the caller
     * can preserve it rather than overwrite it.
     */
    data class Unreadable(val raw: String?, val cause: Throwable) : ProfilesLoad
}

private const val TAG = "DataStoreManager"

/**
 * [ReplaceFileCorruptionHandler] only covers a corrupt *preferences file*. A
 * blob that parses as Preferences but not as JSON is handled per key below.
 *
 * Without a handler DataStore throws on every read of a corrupt file. The
 * collectors in FloatingWindowStateManager run on `Dispatchers.Main` without a
 * catch, so that exception would surface as a process crash.
 */
val Context.datastore by preferencesDataStore(
    name = "app_configurations",
    corruptionHandler = ReplaceFileCorruptionHandler { cause ->
        Log.e(TAG, "preferences file was unreadable; starting from an empty store", cause)
        emptyPreferences()
    },
)

class DataStoreManager @Inject constructor(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    companion object {
        fun getButtonsConfigKey(packageName: String): Preferences.Key<String> =
            stringPreferencesKey("buttons_config_$packageName")

        val ADDED_PACKAGES = stringPreferencesKey("added_packages")
        val POINTER_SENSITIVITY = floatPreferencesKey("pointer_sensitivity")
        val OVERLAY_OPACITY = floatPreferencesKey("overlay_opacity")
        // 按键拟人化（随机偏移）开关与强度：全局键，对所有预设统一生效。
        val WASD_HUMANIZATION = booleanPreferencesKey("wasd_humanization")
        val KEY_HUMANIZATION = booleanPreferencesKey("key_humanization")
        val HUMANIZATION_STRENGTH = floatPreferencesKey("humanization_strength")
        val KEYS_CONFIG = stringPreferencesKey("keys_config")

        /** All user-defined keymap presets. */
        val PROFILES = stringPreferencesKey("profiles")

        /** ID of the currently-active profile. */
        val ACTIVE_PROFILE_ID = stringPreferencesKey("active_profile_id")

        /**
         * Where an unreadable [PROFILES] blob is copied before the store is
         * reseeded, so the original bytes are never destroyed by an upgrade.
         */
        val PROFILES_QUARANTINE = stringPreferencesKey("profiles_quarantine")
    }

    /**
     * DataStore surfaces I/O failures (unreadable file, no space, revoked
     * storage) as exceptions on the `data` flow. Emitting a fallback keeps the
     * collector alive; letting it propagate terminates the flow for the rest of
     * the process lifetime and crashes anything collecting on Main.
     */
    private fun <T> Flow<T>.recoverFromReadFailure(fallback: T, label: String): Flow<T> =
        catch { t ->
            if (t is CancellationException) throw t
            Log.e(TAG, "DataStore read failed for $label; falling back to default", t)
            emit(fallback)
        }

    fun getButtonsConfigKeys(): Flow<List<Preferences.Key<String>>> {
        return context.datastore.data
            .map { pref ->
                pref.asMap().keys
                    .filter { it.name.startsWith("buttons_config") }
                    // Rebuild the key from its name rather than casting it. The
                    // type argument is erased, so `as Preferences.Key<String>` is
                    // an unchecked cast that cannot fail loudly; every
                    // buttons_config_* key is written as a String key anyway.
                    .map { stringPreferencesKey(it.name) }
            }
            .recoverFromReadFailure(emptyList(), "buttons_config keys")
    }

    suspend fun <T> remove(key: Preferences.Key<T>) {
        context.datastore.edit { pref ->
            pref.remove(key)
        }
    }

    suspend fun save(key: Preferences.Key<String>, value: List<DraggableItem>) {
        val json = json.encodeToString(value)
        context.datastore.edit { pref ->
            pref[key] = json
        }
    }

    fun getButtons(key: Preferences.Key<String>): Flow<List<DraggableItem>> {
        return context.datastore.data
            .map { pref ->
                val raw = pref[key] ?: return@map emptyList()
                try {
                    json.decodeFromString<List<DraggableItem>>(raw)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.e(TAG, "invalid button mapping JSON for '${key.name}'", t)
                    emptyList()
                }
            }
            .recoverFromReadFailure(emptyList(), "button mapping '${key.name}'")
    }

    suspend fun saveList(key: Preferences.Key<String>, value: List<String>) {
        val json = json.encodeToString(value)
        context.datastore.edit { pref ->
            pref[key] = json
        }
    }

    fun getList(key: Preferences.Key<String>): Flow<List<String>> {
        return context.datastore.data
            .map { pref ->
                val raw = pref[key] ?: return@map emptyList()
                try {
                    json.decodeFromString<List<String>>(raw)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.e(TAG, "invalid JSON list for '${key.name}'", t)
                    emptyList()
                }
            }
            .recoverFromReadFailure(emptyList(), "list '${key.name}'")
    }

    suspend fun save(key: Preferences.Key<String>, value: AppConfig) {
        val json = json.encodeToString(value)
        context.datastore.edit { pref ->
            pref[key] = json
        }
    }

    fun getKeyConfig(key: Preferences.Key<String>): Flow<AppConfig> {
        return context.datastore.data
            .map { pref ->
                val raw = pref[key] ?: return@map AppConfig.Default
                try {
                    json.decodeFromString<AppConfig>(raw)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.e(TAG, "invalid AppConfig JSON for '${key.name}'; using defaults", t)
                    AppConfig.Default
                }
            }
            .recoverFromReadFailure(AppConfig.Default, "AppConfig '${key.name}'")
    }

    /* ---------- profile storage ---------- */

    suspend fun saveProfiles(profiles: List<Profile>) {
        val raw = json.encodeToString(profiles)
        context.datastore.edit { pref -> pref[PROFILES] = raw }
    }

    /**
     * Reads the profile blob without collapsing failures into "empty".
     *
     * Callers must branch on [ProfilesLoad]: only [ProfilesLoad.Absent] may be
     * treated as a first run. Overwriting on [ProfilesLoad.Unreadable] destroys
     * the user's layouts.
     */
    fun getProfilesLoad(): Flow<ProfilesLoad> {
        return context.datastore.data
            .map { pref -> decodeProfiles(pref[PROFILES]) }
            .catch { t ->
                if (t is CancellationException) throw t
                Log.e(TAG, "DataStore read failed while loading profiles", t)
                emit(ProfilesLoad.Unreadable(null, t))
            }
    }

    private fun decodeProfiles(raw: String?): ProfilesLoad {
        if (raw == null) return ProfilesLoad.Absent
        return try {
            ProfilesLoad.Loaded(json.decodeFromString(raw))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.e(TAG, "profile blob is present but not decodable; preserving it", t)
            ProfilesLoad.Unreadable(raw, t)
        }
    }

    /**
     * Copies an unreadable blob aside before the store is reseeded.
     *
     * Kept under a separate key rather than in a file so it rides along with the
     * rest of the preferences and can be recovered by a later build that fixes
     * the decoder.
     */
    suspend fun quarantineUnreadableProfiles(raw: String) {
        context.datastore.edit { pref -> pref[PROFILES_QUARANTINE] = raw }
    }

    fun getQuarantinedProfiles(): Flow<String?> {
        return context.datastore.data
            .map { pref -> pref[PROFILES_QUARANTINE] }
            .recoverFromReadFailure(null, "quarantined profiles")
    }

    suspend fun saveActiveProfileId(id: String?) {
        context.datastore.edit { pref ->
            if (id == null) pref.remove(ACTIVE_PROFILE_ID) else pref[ACTIVE_PROFILE_ID] = id
        }
    }

    fun getActiveProfileId(): Flow<String?> {
        return context.datastore.data
            .map { pref -> pref[ACTIVE_PROFILE_ID] }
            .recoverFromReadFailure(null, "active profile id")
    }

    suspend fun save(key: Preferences.Key<String>, value: String) {
        context.datastore.edit { pref ->
            pref[key] = value
        }
    }


    fun getString(key: Preferences.Key<String>): Flow<String?> {
        return context.datastore.data
            .map { pref -> pref[key] }
            .recoverFromReadFailure(null, "string '${key.name}'")
    }

    suspend fun save(key: Preferences.Key<Int>, value: Int) {
        context.datastore.edit { pref ->
            pref[key] = value
        }
    }

    fun getInt(key: Preferences.Key<Int>): Flow<Int?> {
        return context.datastore.data
            .map { pref -> pref[key] }
            .recoverFromReadFailure(null, "int '${key.name}'")
    }

    suspend fun save(key: Preferences.Key<Float>, value: Float) {
        context.datastore.edit { pref ->
            pref[key] = value
        }
    }

    fun getFloat(key: Preferences.Key<Float>): Flow<Float?> {
        return context.datastore.data
            .map { pref -> pref[key] }
            .recoverFromReadFailure(null, "float '${key.name}'")
    }

    suspend fun save(key: Preferences.Key<Boolean>, value: Boolean) {
        context.datastore.edit { pref ->
            pref[key] = value
        }
    }

    fun getBoolean(key: Preferences.Key<Boolean>): Flow<Boolean?> {
        return context.datastore.data
            .map { pref -> pref[key] }
            .recoverFromReadFailure(null, "boolean '${key.name}'")
    }


}
