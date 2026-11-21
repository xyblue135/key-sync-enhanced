package com.devoid.keysync.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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

/** Preserve corrupt files. Read failures are reported below, never replaced with an empty store. */
val Context.datastore by preferencesDataStore(name = "app_configurations")

class DataStoreManager internal constructor(
    private val store: DataStore<Preferences>,
    private val reportError: (String, Throwable) -> Unit = { message, error -> Log.e(TAG, message, error); Unit },
) {
    constructor(context: Context) : this(context.datastore)

    // Closed until the original profile blob has been read successfully. A failed
    // read must also prevent later UI edits/service shutdown from overwriting it.
    @Volatile
    var canWriteProfiles: Boolean = false
        private set

    private fun requireWritableProfiles() {
        check(canWriteProfiles) { "预设尚未成功读取，本次修改不会保存，请重启后重试" }
    }

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

        /**
         * 悬浮窗顶部状态提示条（预设名 · 模式 · 最近输入）的显隐。
         * 全局键：提示条是纯信息展示，关掉它通常是想一直关着，
         * 因此与 keysVisible 不同，这个开关会被持久化。
         */
        val STATUS_BAR_VISIBLE = booleanPreferencesKey("status_bar_visible")

        // 按键拟人化（随机偏移）开关与强度：全局键，对所有预设统一生效。
        val WASD_HUMANIZATION = booleanPreferencesKey("wasd_humanization")
        val KEY_HUMANIZATION = booleanPreferencesKey("key_humanization")
        val HUMANIZATION_STRENGTH = floatPreferencesKey("humanization_strength")
        /** 偏移强度 1–100（新刻度）；旧 float 键仅用于迁移。 */
        val HUMANIZATION_STRENGTH_SCALE = intPreferencesKey("humanization_strength_scale")
        /** WASD 方向晃动幅度 1–100（独立于整体强度）。 */
        val WASD_SWAY = intPreferencesKey("wasd_sway")
        /** 随机偏移间隔（ms），默认 1000；全局键，与拟人化开关一起生效。 */
        val HUMANIZATION_INTERVAL_MS = intPreferencesKey("humanization_interval_ms")
        /** WASD 换向时是否沿圆弧过渡，默认开启。 */
        val WASD_TRANSITION = booleanPreferencesKey("wasd_transition")
        /** WASD 换向过渡时长（ms），默认 80。 */
        val WASD_TRANSITION_MS = intPreferencesKey("wasd_transition_ms")
        /** Alt+D 拖拽丢弃开关（全局），默认关闭，避免误触。 */
        val DISCARD_DRAG_ENABLED = booleanPreferencesKey("discard_drag_enabled")
        /** Alt+D 拖拽速度倍数（1–10，默认 5 = 原速的 5 倍）。 */
        val DISCARD_DRAG_SPEED = floatPreferencesKey("discard_drag_speed")
        /** F 光标滚轮操控（全局），默认开启。 */
        val F_SCROLL_CONTROL_ENABLED = booleanPreferencesKey("f_scroll_control_enabled")
        /** 每格滚轮短滑的像素距离，默认 120。 */
        val F_SCROLL_SWIPE_PX = floatPreferencesKey("f_scroll_swipe_px")
        /** 滚轮方向反转（默认关闭：滚轮向下 = 手指向下滑）。 */
        val F_SCROLL_INVERT = booleanPreferencesKey("f_scroll_invert")
        /** M 键开关地图并切换鼠标（全局），默认开启。 */
        val MAP_MOUSE_TOGGLE_ENABLED = booleanPreferencesKey("map_mouse_toggle_enabled")
        /** F 交互后左键唤出光标（全局），默认开启。 */
        val F_CURSOR_CLICK_ENABLED = booleanPreferencesKey("f_cursor_click_enabled")
        /** 物理 ESC / Tab 关闭界面后自动回射击模式（全局），默认开启。 */
        val TAB_ESC_AIM_ENABLED = booleanPreferencesKey("tab_esc_aim_enabled")
        /** 快速近战默认键已从 V 迁移到右侧小键盘 num0 的一次性标记。 */
        val QUICK_MELEE_NUMPAD_MIGRATED = booleanPreferencesKey("quick_melee_numpad_migrated")

        /* -------- 鼠标高级参数（全局，与预设无关） -------- */
        /** 单帧位移断点阈值（px），默认 240。 */
        val MOUSE_JUMP_THRESHOLD_PX = intPreferencesKey("mouse_jump_threshold_px")
        /** 是否在一段手势内锁存相对轴/绝对坐标解析方式，默认开启。 */
        val MOUSE_MODE_LATCH = booleanPreferencesKey("mouse_mode_latch")
        /** 是否缓存屏幕尺寸，默认开启。 */
        val SCREEN_SIZE_CACHE = booleanPreferencesKey("screen_size_cache")

        val KEYS_CONFIG = stringPreferencesKey("keys_config")

        /** All user-defined keymap presets. */
        val PROFILES = stringPreferencesKey("profiles")

        /** ID of the currently-active profile. */
        val ACTIVE_PROFILE_ID = stringPreferencesKey("active_profile_id")

        /**
         * A backup of an unreadable [PROFILES] blob. The active blob is also
         * preserved; an older, different backup must never be overwritten.
         */
        val PROFILES_QUARANTINE = stringPreferencesKey("profiles_quarantine")
    }

    /**
     * DataStore surfaces I/O failures (unreadable file, no space, revoked
     * storage) as exceptions on the `data` flow. A fallback completes this
     * collection without a crash; it does not retry or keep the upstream alive.
     * Profile reads use an explicit Unreadable result instead of defaults.
     */
    private fun <T> Flow<T>.recoverFromReadFailure(fallback: T, label: String): Flow<T> =
        catch { t ->
            if (t is CancellationException) throw t
            reportError("DataStore read failed for $label; falling back to default", t)
            emit(fallback)
        }

    fun getButtonsConfigKeys(): Flow<List<Preferences.Key<String>>> {
        return store.data
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
        store.edit { pref ->
            pref.remove(key)
        }
    }

    suspend fun save(key: Preferences.Key<String>, value: List<DraggableItem>) {
        val json = json.encodeToString(value)
        store.edit { pref ->
            pref[key] = json
        }
    }

    fun getButtons(key: Preferences.Key<String>): Flow<List<DraggableItem>> {
        return store.data
            .map { pref ->
                val raw = pref[key] ?: return@map emptyList()
                try {
                    json.decodeFromString<List<DraggableItem>>(raw)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    reportError("invalid button mapping JSON for '${key.name}'", t)
                    emptyList()
                }
            }
            .recoverFromReadFailure(emptyList(), "button mapping '${key.name}'")
    }

    suspend fun saveList(key: Preferences.Key<String>, value: List<String>) {
        val json = json.encodeToString(value)
        store.edit { pref ->
            pref[key] = json
        }
    }

    fun getList(key: Preferences.Key<String>): Flow<List<String>> {
        return store.data
            .map { pref ->
                val raw = pref[key] ?: return@map emptyList()
                try {
                    json.decodeFromString<List<String>>(raw)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    reportError("invalid JSON list for '${key.name}'", t)
                    emptyList()
                }
            }
            .recoverFromReadFailure(emptyList(), "list '${key.name}'")
    }

    suspend fun save(key: Preferences.Key<String>, value: AppConfig) {
        val json = json.encodeToString(value)
        store.edit { pref ->
            pref[key] = json
        }
    }

    fun getKeyConfig(key: Preferences.Key<String>): Flow<AppConfig> {
        return store.data
            .map { pref ->
                val raw = pref[key] ?: return@map AppConfig.Default
                try {
                    json.decodeFromString<AppConfig>(raw)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    reportError("invalid AppConfig JSON for '${key.name}'; using defaults", t)
                    AppConfig.Default
                }
            }
            .recoverFromReadFailure(AppConfig.Default, "AppConfig '${key.name}'")
    }

    /* ---------- profile storage ---------- */

    suspend fun saveProfiles(profiles: List<Profile>) {
        requireWritableProfiles()
        val raw = json.encodeToString(profiles)
        store.edit { pref ->
            requireWritableProfiles()
            pref[PROFILES] = raw
        }
    }

    /**
     * Reads the profile blob without collapsing failures into "empty".
     *
     * Callers must branch on [ProfilesLoad]: only [ProfilesLoad.Absent] may be
     * treated as a first run. Overwriting on [ProfilesLoad.Unreadable] destroys
     * the user's layouts.
     */
    fun getProfilesLoad(): Flow<ProfilesLoad> {
        return store.data
            .map { pref ->
                decodeProfiles(pref[PROFILES]).also { canWriteProfiles = it !is ProfilesLoad.Unreadable }
            }
            .catch { t ->
                if (t is CancellationException) throw t
                canWriteProfiles = false
                reportError("DataStore read failed while loading profiles", t)
                emit(ProfilesLoad.Unreadable(null, t))
            }
    }

    private fun decodeProfiles(raw: String?): ProfilesLoad {
        if (raw == null) return ProfilesLoad.Absent
        return try {
            ProfilesLoad.Loaded(json.decodeFromString(raw))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            reportError("profile blob is present but not decodable; preserving it", t)
            ProfilesLoad.Unreadable(raw, t)
        }
    }

    /**
     * Copies an unreadable blob aside without replacing the active blob.
     *
     * Kept under a separate key rather than in a file so it rides along with the
     * rest of the preferences and can be recovered by a later build that fixes
     * the decoder.
     */
    suspend fun quarantineUnreadableProfiles(raw: String) {
        store.edit { pref ->
            check(pref[PROFILES] == raw) { "Profile data changed before backup" }
            val previous = pref[PROFILES_QUARANTINE]
            check(previous == null || previous == raw) { "An earlier profile backup already exists" }
            pref[PROFILES_QUARANTINE] = raw
        }
    }

    fun getQuarantinedProfiles(): Flow<String?> {
        return store.data
            .map { pref -> pref[PROFILES_QUARANTINE] }
            .recoverFromReadFailure(null, "quarantined profiles")
    }

    suspend fun saveActiveProfileId(id: String?) {
        requireWritableProfiles()
        store.edit { pref ->
            requireWritableProfiles()
            if (id == null) pref.remove(ACTIVE_PROFILE_ID) else pref[ACTIVE_PROFILE_ID] = id
        }
    }

    fun getActiveProfileId(): Flow<String?> {
        return store.data
            .map { pref -> pref[ACTIVE_PROFILE_ID] }
            .recoverFromReadFailure(null, "active profile id")
    }

    suspend fun save(key: Preferences.Key<String>, value: String) {
        store.edit { pref ->
            pref[key] = value
        }
    }


    fun getString(key: Preferences.Key<String>): Flow<String?> {
        return store.data
            .map { pref -> pref[key] }
            .recoverFromReadFailure(null, "string '${key.name}'")
    }

    suspend fun save(key: Preferences.Key<Int>, value: Int) {
        store.edit { pref ->
            pref[key] = value
        }
    }

    fun getInt(key: Preferences.Key<Int>): Flow<Int?> {
        return store.data
            .map { pref -> pref[key] }
            .recoverFromReadFailure(null, "int '${key.name}'")
    }

    suspend fun save(key: Preferences.Key<Float>, value: Float) {
        store.edit { pref ->
            pref[key] = value
        }
    }

    fun getFloat(key: Preferences.Key<Float>): Flow<Float?> {
        return store.data
            .map { pref -> pref[key] }
            .recoverFromReadFailure(null, "float '${key.name}'")
    }

    suspend fun save(key: Preferences.Key<Boolean>, value: Boolean) {
        store.edit { pref ->
            pref[key] = value
        }
    }

    fun getBoolean(key: Preferences.Key<Boolean>): Flow<Boolean?> {
        return store.data
            .map { pref -> pref[key] }
            .recoverFromReadFailure(null, "boolean '${key.name}'")
    }


}
