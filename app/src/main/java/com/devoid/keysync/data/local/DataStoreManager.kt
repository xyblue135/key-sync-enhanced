package com.devoid.keysync.data.local

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.devoid.keysync.model.AppConfig
import com.devoid.keysync.model.DraggableItem
import com.devoid.keysync.model.Profile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.inject.Inject

val Context.datastore by preferencesDataStore("app_configurations")

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
        val KEYS_CONFIG = stringPreferencesKey("keys_config")

        /** All user-defined keymap presets. */
        val PROFILES = stringPreferencesKey("profiles")

        /** ID of the currently-active profile. */
        val ACTIVE_PROFILE_ID = stringPreferencesKey("active_profile_id")
    }

    fun getButtonsConfigKeys(): Flow<List<Preferences.Key<String>>> {
       return context.datastore.data.map { it.asMap().keys.map { it as Preferences.Key<String> }.filter { prefKey->prefKey.name.startsWith("buttons_config") } }
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
        return context.datastore.data.map { pref ->
            val raw = pref[key] ?: return@map emptyList()
            runCatching { json.decodeFromString<List<DraggableItem>>(raw) }
                .onFailure { if (it is SerializationException) android.util.Log.e("DataStoreManager", "Invalid button mapping JSON", it) }
                .getOrElse { emptyList() }
        }
    }

    suspend fun saveList(key: Preferences.Key<String>, value: List<String>) {
        val json = json.encodeToString(value)
        context.datastore.edit { pref ->
            pref[key] = json
        }
    }

    fun getList(key: Preferences.Key<String>): Flow<List<String>> {
        return context.datastore.data.map { pref ->
            val raw = pref[key] ?: return@map emptyList()
            runCatching { json.decodeFromString<List<String>>(raw) }.getOrElse { emptyList() }
        }
    }

    suspend fun save(key: Preferences.Key<String>, value: AppConfig) {
        val json = json.encodeToString(value)
        context.datastore.edit { pref ->
            pref[key] = json
        }
    }

    fun getKeyConfig(key: Preferences.Key<String>): Flow<AppConfig> {
        return context.datastore.data.map { pref ->
            val raw = pref[key] ?: return@map AppConfig.Default
            runCatching { json.decodeFromString<AppConfig>(raw) }.getOrElse { AppConfig.Default }
        }
    }

    /* ---------- profile storage ---------- */

    suspend fun saveProfiles(profiles: List<Profile>) {
        val raw = json.encodeToString(profiles)
        context.datastore.edit { pref -> pref[PROFILES] = raw }
    }

    fun getProfiles(): Flow<List<Profile>> {
        return context.datastore.data.map { pref ->
            val raw = pref[PROFILES] ?: return@map emptyList()
            runCatching { json.decodeFromString<List<Profile>>(raw) }
                .onFailure { android.util.Log.e("DataStoreManager", "Invalid profile JSON", it) }
                .getOrElse { emptyList() }
        }
    }

    suspend fun saveActiveProfileId(id: String?) {
        context.datastore.edit { pref ->
            if (id == null) pref.remove(ACTIVE_PROFILE_ID) else pref[ACTIVE_PROFILE_ID] = id
        }
    }

    fun getActiveProfileId(): Flow<String?> {
        return context.datastore.data.map { pref -> pref[ACTIVE_PROFILE_ID] }
    }

    suspend fun save(key: Preferences.Key<String>, value: String) {
        context.datastore.edit { pref ->
            pref[key] = value
        }
    }


    fun getString(key: Preferences.Key<String>): Flow<String?> {
        return context.datastore.data.map { pref ->
            pref[key]
        }
    }

    suspend fun save(key: Preferences.Key<Int>, value: Int) {
        context.datastore.edit { pref ->
            pref[key] = value
        }
    }

    fun getInt(key: Preferences.Key<Int>): Flow<Int?> {
        return context.datastore.data.map { pref ->
            pref[key]
        }
    }

    suspend fun save(key: Preferences.Key<Float>, value: Float) {
        context.datastore.edit { pref ->
            pref[key] = value
        }
    }

    fun getFloat(key: Preferences.Key<Float>): Flow<Float?> {
        return context.datastore.data.map { pref ->
            pref[key]
        }
    }


}
