package com.devoid.keysync.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.ui.geometry.Offset
import androidx.core.view.WindowInsetsCompat
import com.devoid.keysync.data.local.DataStoreManager
import com.devoid.keysync.model.AppConfig
import com.devoid.keysync.model.DraggableItem
import com.devoid.keysync.model.DraggableItemType
import com.devoid.keysync.domain.MouseButtonTracker
import com.devoid.keysync.util.keyCodeToString
import com.devoid.keysync.domain.EventHandler
import com.devoid.keysync.domain.KEYCODE_LMC
import com.devoid.keysync.domain.KEYCODE_MMC
import com.devoid.keysync.domain.KEYCODE_RMC
import com.devoid.keysync.data.external.ShizukuSystemServerAPi
import com.devoid.keysync.model.Profile
import com.devoid.keysync.model.ProfileBundle
import com.devoid.keysync.model.independentCopy
import com.devoid.keysync.model.importProfileCopies
import com.devoid.keysync.model.profileActivationRoutes
import com.devoid.keysync.model.ProfileSwitchHotkey
import com.devoid.keysync.model.TouchMode
import com.devoid.keysync.model.withMeasuredPositionFrom
import com.devoid.keysync.model.defaultKeyCode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.inject.Inject
import java.util.UUID

class FloatingWindowStateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStoreManager: DataStoreManager
) {

    private val TAG = "FloatingWindowStateManager"

    val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val shizukuSystemServerAPi = ShizukuSystemServerAPi()
    private val eventHandler: EventHandler by lazy { shizukuSystemServerAPi.getEventHandler() }
    val windowManager: WindowManager by lazy { context.getSystemService(WindowManager::class.java) }
    private val displayMetrics = context.resources.displayMetrics

    val pointerSensitivity = MutableStateFlow(0.5f)
    val overlayOpacity = MutableStateFlow(0.5f)

    // 悬浮窗按键的显示/隐藏开关（一键切换，不影响功能）。
    private val _keysVisible = MutableStateFlow(true)
    val keysVisible = _keysVisible.asStateFlow()

    private val _appConfig = MutableStateFlow(AppConfig.Default)
    val keysConfig = _appConfig.asStateFlow()

    // 鼠标（瞄准）灵敏度：只作用于射击模式下的转视角（见 onMouseEvent），
    // 把 0.1..1.0 的滑块映射成 0.3x..3x 的增益（默认 0.5 → 1.5x）。
    // 非射击模式的鼠标指针是「光标定位」，跟手应当 1:1，不做灵敏度缩放；
    // 键鼠映射工具（熊猫映射等）的「鼠标灵敏度」也是指瞄准灵敏度，默认约 1.2–1.5x。
    val sensitivity: Float get() = 3f * pointerSensitivity.value

    private val _isBubbleExpanded = MutableStateFlow(false)
    val isBubbleExpanded = _isBubbleExpanded.asStateFlow()

    private val _mousePointerOffset =
        MutableStateFlow(Offset(displayMetrics.widthPixels / 2f, displayMetrics.heightPixels / 2f))
    val pointerOffset = _mousePointerOffset.asStateFlow()

    // Captured-pointer 事件里 rawX/rawY 不携带位移信息（真正的位移在相对轴，
    // 见 onMouseEvent）。少数 ROM 不填 AXIS_RELATIVE_X/Y，需要退回「相邻事件
    // raw 坐标差」兜底，这两个字段就用来记上一次的 raw 坐标。
    private var overlayOrigin: Offset? = null
    private val mouseButtons = MouseButtonTracker()
    private val _lastInputLabel = MutableStateFlow("等待输入")
    val lastInputLabel = _lastInputLabel.asStateFlow()
    private var lastMouseRawX = Float.NaN
    private var lastMouseRawY = Float.NaN

    private val _containerItems = MutableStateFlow(listOf<DraggableItem>())
    val containerItems = _containerItems.asStateFlow()

    // 刚新建、尚未绑定按键的「按钮」id。UI 观察到后自动弹出绑定对话框，避免新建后
    // 挂着一个显示 "?" 的空按钮让用户不知道该点哪里去绑键。
    private val _pendingVariableKeyBind = MutableStateFlow<Int?>(null)
    val pendingVariableKeyBind = _pendingVariableKeyBind.asStateFlow()

    // 当前正被物理按下的按键（非编辑态），供悬浮窗按钮显示「按下」高亮。
    // 编辑态下不维护（onKeyEvent 编辑态分支直接返回），进/出编辑态时清空。
    private val _pressedKeys = MutableStateFlow<Set<Int>>(emptySet())
    val pressedKeys = _pressedKeys.asStateFlow()

    // 按键捕获：绑定对话框（KeyCaptureDialog）打开时，把「收到物理按键」的回调挂到这里。
    // 悬浮窗跑在 Service 里没有 Activity 焦点，Compose 的 onKeyEvent 收不到按键；
    // 这里改由 FloatingBubbleService 的 View 层 setOnKeyListener 在气泡展开时路由按键。
    // 回调由对话框的 DisposableEffect 挂载/卸载，整个对话框生命周期内都有效——这样用户
    // 第一次按错键还能再按一次改绑，而不是按一次就失效。
    @Volatile
    var keyCaptureListener: ((Int) -> Unit)? = null

    val isShootingMode = eventHandler.shootingModeFlow
    val walkEnabled = eventHandler.walkEnabled
    val wheelCursor = eventHandler.wheelCursor
    fun wheelCircle(): Pair<Offset, Float> = wheelCursorLocal(eventHandler.wheelCenterPosition) to eventHandler.wheelRadiusPx
    fun wheelCursorLocal(position: Offset): Offset = position - (overlayOrigin ?: Offset.Zero)
    fun calibrateWalkOff() = eventHandler.calibrateWalkOff()

    /* ----------------- profiles ----------------- */

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles = _profiles.asStateFlow()

    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId = _activeProfileId.asStateFlow()

    private val migrationJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** Pretty-printed so an exported profile stays human-readable. */
    private val profileJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
        prettyPrint = true
    }

    init {
        // Hotkeys are resolved on release, so the handler calls back into the
        // profile layer once the key's own mapping has been released.
        eventHandler.onProfileSwitch = { targetId -> handleProfileSwitch(targetId) }
        eventHandler.onCursorRecenter = { screenCenter ->
            _mousePointerOffset.value = screenCenter - (overlayOrigin ?: Offset.Zero)
            lastMouseRawX = Float.NaN
            lastMouseRawY = Float.NaN
        }
        // 射击模式「边缘回中」需要屏幕尺寸来判定瞄准触点是否越界。
        eventHandler.screenSize = Offset(
            displayMetrics.widthPixels.toFloat(),
            displayMetrics.heightPixels.toFloat()
        )
        // Restore global overlay tuning values right away.
        scope.launch {
            dataStoreManager.getFloat(DataStoreManager.OVERLAY_OPACITY).first()?.let {
                overlayOpacity.value = it
            }
            dataStoreManager.getFloat(DataStoreManager.POINTER_SENSITIVITY).first()?.let {
                pointerSensitivity.value = it
            }
        }
        // Load profiles + active id. Run migration if this is the first run.
        scope.launch {
            bootstrapProfiles()
        }
    }

    /**
     * Reads profiles + active-id from DataStore. If profiles is empty, this is
     * the first run (or post-migration), so we:
     *  - try to migrate any legacy `buttons_config_<pkg>` data and the
     *    legacy KEYS_CONFIG AppConfig into a "默认" profile.
     *  - if no legacy data exists either, create a single empty default.
     */
    private suspend fun bootstrapProfiles() {
        var stored = dataStoreManager.getProfiles().first()
        val storedActiveId = dataStoreManager.getActiveProfileId().first()

        if (stored.isEmpty()) {
            val migrated = migrateLegacy()
            stored = listOf(migrated)
            dataStoreManager.saveProfiles(stored)
        }

        // Virtual mouse buttons used to be persisted as 64/128/256. Those
        // numbers are legitimate Android KeyEvent codes, so the input layer
        // now uses a negative private namespace. Migrate only the semantic
        // fixed buttons that historically owned those values.
        migrateLegacyVirtualMouseCodes(stored).let { migrated ->
            if (migrated != null) {
                stored = migrated
                dataStoreManager.saveProfiles(stored)
            }
        }

        // 一次性迁移：把旧版全局 POINTER_SENSITIVITY 值并入第一个预设，
        // 之后删除全局键，避免再次覆盖用户手动设回默认值的操作。
        migrateLegacySensitivity(stored).let { migrated ->
            if (migrated != null) {
                stored = migrated
                dataStoreManager.saveProfiles(stored)
                dataStoreManager.remove(DataStoreManager.POINTER_SENSITIVITY)
            }
        }

        val activeId = storedActiveId?.takeIf { id -> stored.any { it.id == id } }
            ?: stored.first().id
        if (activeId != storedActiveId) {
            dataStoreManager.saveActiveProfileId(activeId)
        }

        _profiles.value = stored
        _activeProfileId.value = activeId
        applyActiveProfile()
    }

    private fun migrateLegacyVirtualMouseCodes(stored: List<Profile>): List<Profile>? {
        var changed = false
        val migrated = stored.map { profile ->
            val items = profile.items.map { item ->
                if (item !is DraggableItem.FixedKey) return@map item

                val migratedKeyCode = when {
                    item.type == DraggableItemType.FIRE && item.keyCode == LEGACY_KEYCODE_LMC -> KEYCODE_LMC
                    item.type == DraggableItemType.SCOPE && item.keyCode == LEGACY_KEYCODE_RMC -> KEYCODE_RMC
                    (item.type == DraggableItemType.SHOOTING_MODE || item.type == DraggableItemType.MOUSE_MID) &&
                        item.keyCode == LEGACY_KEYCODE_MMC -> KEYCODE_MMC
                    else -> item.keyCode
                }
                if (migratedKeyCode != item.keyCode) {
                    changed = true
                    item.copy(keyCode = migratedKeyCode)
                } else item
            }
            profile.copy(items = items)
        }
        return migrated.takeIf { changed }
    }

    /**
     * Returns profiles with the legacy global sensitivity folded into the
     * first profile's appConfig, or null when there is nothing to migrate
     * (no legacy value, or the value was already migrated / default).
     */
    private suspend fun migrateLegacySensitivity(
        stored: List<Profile>,
    ): List<Profile>? {
        val legacy = dataStoreManager.getFloat(DataStoreManager.POINTER_SENSITIVITY).first()
            ?: return null
        if (legacy == 0.5f) return null
        // 只要有一个预设已经带上了非默认灵敏度，就认为已迁移过。
        if (stored.any { it.appConfig.pointerSensitivity != 0.5f }) return null
        return stored.mapIndexed { i, p ->
            if (i == 0) p.copy(appConfig = p.appConfig.copy(pointerSensitivity = legacy)) else p
        }
    }

    private suspend fun migrateLegacy(): Profile {
        // Best-effort migration: read any buttons_config_<pkg> raw JSON, pick
        // the first non-empty one, and apply the legacy KEYS_CONFIG
        // keyCode overrides to the SHOOTING_MODE / FIRE / SCOPE items.
        val keys = dataStoreManager.getButtonsConfigKeys().first()
        var items: List<DraggableItem> = emptyList()
        for (key in keys) {
            items = dataStoreManager.getButtons(key).first()
            if (items.isNotEmpty()) break
        }
        val legacy = runCatching {
            val raw = dataStoreManager.getString(DataStoreManager.KEYS_CONFIG).first()
            if (raw.isNullOrBlank()) return@runCatching null
            migrationJson.decodeFromString<LegacyAppConfig>(raw)
        }.getOrNull()
        val hasLegacy = legacy != null && (
            legacy.shootingModeKeyCode != null ||
                legacy.fireKeyCode != null ||
                legacy.scopeKeyCode != null
            )

        val migratedItems = items.map { item ->
            if (item is DraggableItem.FixedKey && hasLegacy) {
                val newCode = when (item.type) {
                    DraggableItemType.SHOOTING_MODE -> legacy!!.shootingModeKeyCode ?: item.keyCode
                    DraggableItemType.FIRE -> legacy!!.fireKeyCode ?: item.keyCode
                    DraggableItemType.SCOPE -> legacy!!.scopeKeyCode ?: item.keyCode
                    else -> item.keyCode
                }
                item.copy(keyCode = newCode)
            } else item
        }
        Log.i(TAG, "Migrated ${migratedItems.size} items from legacy storage into default profile")
        return Profile(
            id = UUID.randomUUID().toString(),
            name = "默认",
            items = migratedItems,
            appConfig = AppConfig.Default,
        )
    }

    /** Mirror of the pre-Profile [AppConfig] schema; only used for one-time
     *  migration of the three global keyCode fields into FixedButton fields. */
    @kotlinx.serialization.Serializable
    private data class LegacyAppConfig(
        val buttonScale: Float = 1f,
        val deleteDataOnRemove: Boolean = false,
        val themePreference: String = "SYSTEM_DYNAMIC",
        val cancellableTouchMode: String = "TAP",
        val normalBtnTouchMode: String = "TAP",
        val shootingModeKeyCode: Int? = null,
        val fireKeyCode: Int? = null,
        val scopeKeyCode: Int? = null,
        val screenMirrorCompatMode: Boolean = false,
    )

    private fun applyActiveProfile() {
        val active = activeProfile() ?: return
        _containerItems.value = active.items
        _appConfig.value = active.appConfig
        eventHandler.appConfig = active.appConfig
        eventHandler.setCancelableTouchMode(active.appConfig.cancellableTouchMode)
        eventHandler.setNormalBtnTouchMode(active.appConfig.normalBtnTouchMode)
        // 灵敏度随预设切换。
        pointerSensitivity.value = active.appConfig.pointerSensitivity
        pushSwitchHotkeys()
        overlayOrigin?.let { origin ->
            active.items.forEach { item ->
                val size = when (item) {
                    is DraggableItem.FixedKey -> item.size
                    is DraggableItem.VariableKey -> item.size
                    else -> 0
                }
                if (size > 0) item.touchCenter = origin + item.position + Offset(size / 2f, size / 2f)
            }
        }
        eventHandler.updateKeyMapping(active.items)
    }

    /**
     * Publishes the active profile's switch hotkeys to the handler. The master
     * switch lives on [AppConfig], so turning it off leaves the bindings
     * configured but dormant instead of deleting them.
     */
    private fun pushSwitchHotkeys() {
        val active = activeProfile() ?: return
        eventHandler.setSwitchHotkeys(
            (if (_appConfig.value.profileSwitchEnabled) active.switchHotkeys else emptyList()) +
                profileActivationRoutes(_profiles.value)
        )
    }

    private fun activeProfile(): Profile? {
        val id = _activeProfileId.value ?: return null
        return _profiles.value.firstOrNull { it.id == id }
    }

    /** Persist the current in-memory items/appConfig back into the active profile. */
    private fun persistActiveProfile() {
        val id = _activeProfileId.value ?: return
        val updated = _profiles.value.map { profile ->
            if (profile.id == id) {
                profile.copy(items = _containerItems.value, appConfig = _appConfig.value)
            } else profile
        }
        _profiles.value = updated
        scope.launch { dataStoreManager.saveProfiles(updated) }
    }

    fun loadButtonsConfig(packageName: String) {
        // No-op kept for API compatibility. With the profile model, the
        // floating window always reflects the active profile. The packageName
        // is intentionally unused but we still log it for debugging.
        Log.i(TAG, "loadButtonsConfig($packageName) — using active profile: ${_activeProfileId.value}")
    }

    fun addNewItem(itemType: DraggableItemType) {
        if (itemType == DraggableItemType.WALK_TOGGLE && containerItems.value.any {
                it is DraggableItem.FixedKey && it.type == DraggableItemType.WALK_TOGGLE
            }) {
            android.widget.Toast.makeText(context, "此预设已有静步按钮，请拖动或编辑现有按钮", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        // maxOfOrNull (not sumOf) so ids keep incrementing by one instead of
        // drifting upwards every time an item is removed and re-added.
        val itemID = (containerItems.value.maxOfOrNull { it.id } ?: 0) + 1
        val offset = nextItemOffset()
        val item = when (itemType) {
            // 统一「按钮」：自定义按键 + 点击/按住模式（齿轮对话框里切换）。
            // 跳跃 / 蹲下 / 趴下 / 冲刺 / 装弹 / 近战 / 手雷 / 治疗 / 武器 /
            // 交互 / 标记 / 探头 QE / M / ESC 等动作键都只用这一个类型。
            DraggableItemType.KEY -> {
                // 新建的按钮还没有 keyCode，通过信号让 UI 立刻弹出绑定框。
                _pendingVariableKeyBind.value = itemID
                DraggableItem.VariableKey(itemID, position = offset, size = 0)
            }

            DraggableItemType.WASD_KEY ->
                DraggableItem.WASDGroup(itemID, offset)

            // 射击三件套仍是固定键：开火 / 开镜 / 射击模式切换，各自有专用语义。
            DraggableItemType.WALK_TOGGLE, DraggableItemType.FIRE, DraggableItemType.SCOPE, DraggableItemType.SHOOTING_MODE ->
                DraggableItem.FixedKey(
                    itemID,
                    offset,
                    size = 0,
                    type = itemType,
                    keyCode = itemType.defaultKeyCode()
                )

            // 其余旧语义类型（HOLD_KEY / BACKPACK / BAG_MAP / QUICK_SWITCH / USE /
            // JUMP / CROUCH / ...）不再单独暴露入口，统一回落成通用按钮。
            // 枚举值保留只为兼容已持久化的旧数据，不再从这里创建。
            else ->
                DraggableItem.VariableKey(itemID, position = offset, size = 0)
        }
        _containerItems.value = containerItems.value.plus(item)
        persistActiveProfile()
    }

    /**
     * Update the keyCode of an already-added FixedKey item (e.g. after the
     * user re-binds a key via the gear-icon dialog). Triggers a re-mapping of
     * the EventHandler so the new binding takes effect immediately.
     */
    fun updateFixedKeyCode(itemId: Int, newKeyCode: Int, touchMode: TouchMode?) {
        val updated = _containerItems.value.map { item ->
            if (item is DraggableItem.FixedKey && item.id == itemId) {
                item.copy(keyCode = newKeyCode, touchMode = touchMode).withMeasuredPositionFrom(item)
            } else item
        }
        _containerItems.value = updated
        eventHandler.updateKeyMapping(updated)
        persistActiveProfile()
    }

    /**
     * Update a generic "button" (VariableKey) after the user rebinds its key or
     * switches its touch mode via the gear dialog. Without this the binding
     * used to be written straight into the in-memory item and never re-mapped,
     * so the newly bound key did nothing at all — the root cause of "按住同步
     * 不生效" for probe/peek keys (Q/E) and the rest.
     */
    fun updateVariableKeyCode(itemId: Int, newKeyCode: Int, touchMode: TouchMode?) {
        val updated = _containerItems.value.map { item ->
            if (item is DraggableItem.VariableKey && item.id == itemId) {
                item.copy(keyCode = newKeyCode, touchMode = touchMode).withMeasuredPositionFrom(item)
            } else item
        }
        _containerItems.value = updated
        eventHandler.updateKeyMapping(updated)
        persistActiveProfile()
    }

    /** UI 弹出绑定框后调用，清除待绑定信号，避免下次重组又弹一次。 */
    fun updateMeasuredPosition(item: DraggableItem) {
        val size = when (item) {
            is DraggableItem.FixedKey -> item.size
            is DraggableItem.VariableKey -> item.size
            else -> 0
        }
        if (size > 0) item.touchCenter?.let {
            overlayOrigin = it - item.position - Offset(size / 2f, size / 2f)
        }
        eventHandler.updateMeasuredPosition(item)
    }

    fun consumePendingVariableKeyBind() {
        _pendingVariableKeyBind.value = null
    }

    fun removeItem(id: Int) {
        _containerItems.value = _containerItems.value.filterNot { it.id == id }
        eventHandler.updateKeyMapping(_containerItems.value)
        persistActiveProfile()
    }

    /**
     * Add a pre-built [DraggableItem] (e.g. one produced from a JSON preset)
     * to the active profile, reassigning its id to avoid collisions.
     */
    fun addNewItemType(item: DraggableItem) {
        val nextId = (_containerItems.value.maxOfOrNull { it.id } ?: 0) + 1
        val withFreshId = when (item) {
            is DraggableItem.VariableKey -> item.copy(id = nextId, size = if (item.size <= 0) 0 else item.size)
            is DraggableItem.WASDGroup -> item.copy(id = nextId)
            is DraggableItem.FixedKey -> item.copy(id = nextId, size = if (item.size <= 0) 0 else item.size)
            is DraggableItem.CancelableKey -> item.copy(id = nextId, size = if (item.size <= 0) 0 else item.size)
        }
        _containerItems.value = _containerItems.value + withFreshId
        eventHandler.updateKeyMapping(_containerItems.value)
        persistActiveProfile()
    }

    /**
     * Where to drop a newly added button.
     *
     * Every new item used to land on the same hard-coded (0, 200) point, so
     * adding several keys in a row stacked them invisibly on top of each
     * other. New items now fan out across a coarse grid instead.
     */
    private fun currentScreenSize(): Offset {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            return Offset(bounds.width().toFloat(), bounds.height().toFloat())
        }
        return Offset(displayMetrics.widthPixels.toFloat(), displayMetrics.heightPixels.toFloat())
    }

    private fun nextItemOffset(): Offset {
        val screen = currentScreenSize()
        val width = screen.x
        val height = screen.y
        val density = displayMetrics.density
        val footprint = 160f * density
        val maxX = (width - footprint).coerceAtLeast(0f)
        val maxY = (height - footprint).coerceAtLeast(0f)
        val candidates = (0..4).flatMap { row ->
            (0..4).map { column -> Offset(maxX * column / 4f, maxY * row / 4f) }
        }.sortedBy { (it - Offset(maxX / 2f, maxY / 2f)).getDistance() }
        return candidates.maxByOrNull { candidate ->
            _containerItems.value.minOfOrNull { (it.position - candidate).getDistance() }
                ?: Float.MAX_VALUE
        } ?: Offset.Zero
    }

    /**
     * Replaces the active profile's whole layout with [items].
     *
     * Used by the preset picker. A preset describes a complete layout, so it
     * must not be appended on top of whatever the user already had — applying
     * the same preset twice used to stack a second copy of every button.
     * Ids are renumbered from 1 so the layout stays internally consistent.
     */
    fun replaceAllItems(items: List<DraggableItem>) {
        val renumbered = items.mapIndexed { index, item ->
            val newId = index + 1
            when (item) {
                is DraggableItem.VariableKey -> item.copy(id = newId, size = item.size.coerceAtLeast(0))
                is DraggableItem.WASDGroup -> item.copy(id = newId)
                is DraggableItem.FixedKey -> item.copy(id = newId, size = item.size.coerceAtLeast(0))
                is DraggableItem.CancelableKey -> item.copy(id = newId, size = item.size.coerceAtLeast(0))
            }
        }
        _containerItems.value = renumbered
        eventHandler.updateKeyMapping(renumbered)
        persistActiveProfile()
    }

    /** Persist the latest AppConfig (called from settings UI). */
    fun saveAppConfig(newConfig: AppConfig) {
        _appConfig.value = newConfig
        eventHandler.appConfig = newConfig
        eventHandler.setCancelableTouchMode(newConfig.cancellableTouchMode)
        eventHandler.setNormalBtnTouchMode(newConfig.normalBtnTouchMode)
        pointerSensitivity.value = newConfig.pointerSensitivity
        pushSwitchHotkeys()
        persistActiveProfile()
    }

    /** 灵敏度随预设保存：更新当前值并写回 active profile 的 appConfig。 */
    fun savePointerSensitivity(value: Float) {
        pointerSensitivity.value = value
        val updated = _appConfig.value.copy(pointerSensitivity = value)
        _appConfig.value = updated
        eventHandler.appConfig = updated
        persistActiveProfile()
    }

    /* ----------------- profile CRUD ----------------- */

    fun switchProfile(id: String) {
        if (_profiles.value.none { it.id == id }) return
        if (_activeProfileId.value == id) return
        // updateKeyMapping() lifts every contact, so remember what is still
        // physically held and press it again against the new mapping. Without
        // this a held W would go dead until the user let go and re-pressed it.
        persistActiveProfile()
        val held = eventHandler.heldKeyCodes()
        val heldMouse = eventHandler.heldMouseButtons()
        _activeProfileId.value = id
        scope.launch { dataStoreManager.saveActiveProfileId(id) }
        applyActiveProfile()
        eventHandler.replayHeldKeys(held)
        // Shooting mode is not a held key: it is a contact that stays down for
        // as long as the mode is on, and updateKeyMapping just lifted it.
        eventHandler.restoreShootingModeContact()
        eventHandler.replayHeldMouseButtons(heldMouse)
    }

    /**
     * Resolves a switch-hotkey request. Called from the key handler on the
     * *release* edge, after the key's own mapping has already been released,
     * which is what lets one key both trigger an action and change profile.
     */
    private fun handleProfileSwitch(targetProfileId: String?) {
        val currentId = _activeProfileId.value ?: return
        val profiles = _profiles.value
        val nextId = if (targetProfileId != null) {
            targetProfileId.takeIf { id -> profiles.any { it.id == id } }
        } else {
            val index = profiles.indexOfFirst { it.id == currentId }
            profiles.getOrNull((index + 1) % profiles.size)?.id
        } ?: return
        switchProfile(nextId)
    }

    fun createProfile(name: String): String {
        val id = UUID.randomUUID().toString()
        val newProfile = Profile(
            id = id,
            name = name.ifBlank { "预设 ${_profiles.value.size + 1}" },
            items = emptyList(),
            appConfig = AppConfig.Default,
        )
        _profiles.value = _profiles.value + newProfile
        scope.launch { dataStoreManager.saveProfiles(_profiles.value) }
        switchProfile(id)
        return id
    }

    fun renameProfile(id: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        _profiles.value = _profiles.value.map {
            if (it.id == id) it.copy(name = trimmed) else it
        }
        scope.launch { dataStoreManager.saveProfiles(_profiles.value) }
    }

    fun deleteProfile(id: String) {
        if (_profiles.value.size <= 1) return // keep at least one profile
        val remaining = _profiles.value
            .filterNot { it.id == id }
            .map { profile ->
                // A hotkey pointing at the profile being deleted would
                // silently do nothing, so drop it rather than leave it dangling.
                val cleaned = profile.switchHotkeys.filterNot { it.targetProfileId == id }
                if (cleaned.size == profile.switchHotkeys.size) profile
                else profile.copy(switchHotkeys = cleaned)
            }
        _profiles.value = remaining
        scope.launch { dataStoreManager.saveProfiles(remaining) }
        if (_activeProfileId.value == id) {
            switchProfile(remaining.first().id)
        }
        pushSwitchHotkeys()
    }

    /* ----------------- profile copy / share ----------------- */

    /** First "<name> 副本" that is not taken yet. */
    private fun nextCopyName(base: String): String {
        val taken = _profiles.value.mapTo(HashSet()) { it.name }
        var candidate = "$base 副本"
        var suffix = 2
        while (candidate in taken) {
            candidate = "$base 副本 $suffix"
            suffix++
        }
        return candidate
    }

    /**
     * Copies a profile so a variant can be built on top of an existing layout.
     * Item ids only need to be unique inside a profile, and switch-hotkey
     * targets stay valid, so the copy is a straight [Profile.copy] with a
     * fresh id.
     */
    fun duplicateProfile(id: String): String? {
        persistActiveProfile()
        val source = _profiles.value.firstOrNull { it.id == id } ?: return null
        val newId = UUID.randomUUID().toString()
        _profiles.value = _profiles.value + source.independentCopy(newId, nextCopyName(source.name))
        scope.launch { dataStoreManager.saveProfiles(_profiles.value) }
        return newId
    }

    /** Serialises a profile for the clipboard; null when the id is unknown. */
    fun exportProfileJson(id: String): String? {
        persistActiveProfile()
        val profile = _profiles.value.firstOrNull { it.id == id } ?: return null
        return runCatching { profileJson.encodeToString(profile) }.getOrNull()
    }

    /**
     * Parses a profile produced by [exportProfileJson].
     *
     * @return null on success, otherwise a message describing why it failed.
     */
    fun importProfileJson(raw: String): String? {
        val result = runCatching {
            val sources = runCatching { profileJson.decodeFromString<ProfileBundle>(raw) }
                .getOrNull()?.also { require(it.version == 1) { "不支持的预设版本" } }?.profiles
                ?: listOf(profileJson.decodeFromString<Profile>(raw))
            importProfileCopies(sources, _profiles.value) { UUID.randomUUID().toString() }
        }.getOrElse { return "导入失败：${it.message ?: "请复制完整预设文本"}" }
        _profiles.value = _profiles.value + result
        scope.launch { dataStoreManager.saveProfiles(_profiles.value) }
        pushSwitchHotkeys()
        return null
    }

    fun exportAllProfilesJson(): String {
        persistActiveProfile()
        return profileJson.encodeToString(ProfileBundle(profiles = _profiles.value))
    }

    fun setProfileActivationKey(profileId: String, keyCode: Int?) {
        if (keyCode != null && keyCode <= KeyEvent.KEYCODE_UNKNOWN) return
        _profiles.value = _profiles.value.map { profile ->
            when {
                profile.id == profileId -> profile.copy(activationKeyCode = keyCode, activationHotkeyEnabled = true)
                keyCode != null && profile.activationKeyCode == keyCode -> profile.copy(activationHotkeyEnabled = false)
                else -> profile
            }
        }
        scope.launch { dataStoreManager.saveProfiles(_profiles.value) }
        pushSwitchHotkeys()
    }

    /** First-run shortcut: keep the current layout and make one independent variant. */
    fun setupTwoProfiles() {
        persistActiveProfile()
        val first = _profiles.value.firstOrNull { it.activationHotkeyEnabled && it.activationKeyCode == KeyEvent.KEYCODE_X }
            ?: activeProfile() ?: return
        // With two or more layouts, configure the first other layout; never append repeatedly.
        val second = _profiles.value.firstOrNull { it.id != first.id && it.activationHotkeyEnabled && it.activationKeyCode == KeyEvent.KEYCODE_1 }
            ?: _profiles.value.firstOrNull { it.id != first.id } ?: run {
            val copied = first.independentCopy(UUID.randomUUID().toString(), "预设 2")
            _profiles.value = _profiles.value + copied
            copied
        }
        setProfileActivationKey(first.id, KeyEvent.KEYCODE_X)
        setProfileActivationKey(second.id, KeyEvent.KEYCODE_1)
    }

    /* ----------------- profile switch hotkeys ----------------- */

    /** Binds (or re-binds) [keyCode] on [profileId] to [targetProfileId]. */
    fun setProfileSwitchHotkey(profileId: String, keyCode: Int, targetProfileId: String?) {
        _profiles.value = _profiles.value.map { profile ->
            if (profile.id != profileId) profile
            else profile.copy(
                switchHotkeys = profile.switchHotkeys.filterNot { it.keyCode == keyCode } +
                    ProfileSwitchHotkey(keyCode, targetProfileId)
            )
        }
        scope.launch { dataStoreManager.saveProfiles(_profiles.value) }
        if (_activeProfileId.value == profileId) pushSwitchHotkeys()
    }

    fun removeProfileSwitchHotkey(profileId: String, keyCode: Int) {
        _profiles.value = _profiles.value.map { profile ->
            if (profile.id != profileId) profile
            else profile.copy(switchHotkeys = profile.switchHotkeys.filterNot { it.keyCode == keyCode })
        }
        scope.launch { dataStoreManager.saveProfiles(_profiles.value) }
        if (_activeProfileId.value == profileId) pushSwitchHotkeys()
    }

    fun onFloatingBubbleClick() {
        eventHandler.screenSize = currentScreenSize()
        mouseButtons.reset()
        lastMouseRawX = Float.NaN
        lastMouseRawY = Float.NaN
        _isBubbleExpanded.value = !_isBubbleExpanded.value
        // 进/出编辑态都清空按下状态：编辑态下不维护 pressedKeys，避免切换回来时
        // 残留上一个状态的「按下」高亮。
        _pressedKeys.value = emptySet()
        if (!_isBubbleExpanded.value) {
            eventHandler.updateKeyMapping(containerItems.value)
            // Position/size edits currently mutate DraggableItem in place, so
            // closing edit mode is the natural commit point for persistence.
            persistActiveProfile()
        }
    }

    /** 一键切换悬浮窗按键的显示/隐藏。 */
    fun toggleKeysVisible() {
        _keysVisible.value = !_keysVisible.value
    }

    fun onKeyEvent(keyEvent: KeyEvent): Boolean {
        if (_isBubbleExpanded.value) {
            // 气泡展开（编辑态）：优先把按键交给正在打开的绑定对话框。
            // 注意不清空 keyCaptureListener——它由对话框关闭时的 DisposableEffect
            // 卸载；保留它才能让用户第一次按错后重新按键改绑。
            val listener = keyCaptureListener
            if (listener != null) {
                if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0)
                    listener(keyEvent.keyCode)
                // Consume UP too, otherwise Escape can close the window after capture.
                return true
            }
            return false
        }
        _lastInputLabel.value = keyEvent.keyCode.keyCodeToString() +
            if (keyEvent.action == KeyEvent.ACTION_DOWN) " 按下" else " 松开"
        // 非编辑态：同步物理按键的按下/抬起状态，供悬浮窗按钮显示「按下」高亮。
        when (keyEvent.action) {
            KeyEvent.ACTION_DOWN -> _pressedKeys.value = _pressedKeys.value + keyEvent.keyCode
            KeyEvent.ACTION_UP -> _pressedKeys.value = _pressedKeys.value - keyEvent.keyCode
        }
        return eventHandler.handleKeyEvent(keyEvent)
    }

    fun onMouseEvent(motionEvent: MotionEvent): Boolean {
        if (isBubbleExpanded.value) {
            val listener = keyCaptureListener ?: return false
            // Leave primary clicks to Compose so confirm/cancel remain clickable.
            if (motionEvent.actionMasked == MotionEvent.ACTION_BUTTON_PRESS &&
                motionEvent.actionButton == MotionEvent.BUTTON_TERTIARY) {
                listener(KEYCODE_MMC)
                return true
            }
            return false
        }
        val screen = currentScreenSize()
        eventHandler.screenSize = screen
        eventHandler.mousePointerPosition = (pointerOffset.value + (overlayOrigin ?: Offset.Zero)).let {
            Offset(it.x.coerceIn(0f, screen.x - 1f), it.y.coerceIn(0f, screen.y - 1f))
        }
        mouseButtons.update(motionEvent.actionMasked, motionEvent.buttonState, motionEvent.actionButton)
            .forEach { (button, pressed) ->
                mouseButtonToKeyCode(button)?.let { key ->
                    _pressedKeys.value = if (pressed) _pressedKeys.value + key else _pressedKeys.value - key
                    _lastInputLabel.value = key.keyCodeToString() + if (pressed) " 按下" else " 松开"
                }
                eventHandler.handleMouseButton(button, pressed)
            }
        when (motionEvent.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                // Captured mouse X/Y already contain deltas. Never differentiate them.
                val relativeMouse = motionEvent.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)
                val dx: Float
                val dy: Float
                if (relativeMouse) {
                    dx = motionEvent.x
                    dy = motionEvent.y
                    lastMouseRawX = Float.NaN
                    lastMouseRawY = Float.NaN
                } else {
                    val axisX = motionEvent.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                    val axisY = motionEvent.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                    val relativeAxes = motionEvent.isFromSource(InputDevice.SOURCE_TOUCHPAD) ||
                        axisX != 0f || axisY != 0f
                    dx = if (relativeAxes) axisX else if (lastMouseRawX.isNaN()) 0f else motionEvent.rawX - lastMouseRawX
                    dy = if (relativeAxes) axisY else if (lastMouseRawY.isNaN()) 0f else motionEvent.rawY - lastMouseRawY
                    lastMouseRawX = motionEvent.rawX
                    lastMouseRawY = motionEvent.rawY
                }

                val rawOffset = Offset(dx, dy)
                if (eventHandler.isWheelActive) return eventHandler.handlePointerMove(rawOffset)
                if (isShootingMode.value) {
                    // 射击模式：灵敏度作用在瞄准转视角上。
                    return eventHandler.handlePointerMove(rawOffset * sensitivity)
                }
                // 非射击模式：鼠标指针 1:1 跟手移动（不乘灵敏度）。
                val position = _mousePointerOffset.value + rawOffset
                _mousePointerOffset.value = Offset(
                    position.x.coerceIn(0f, (screen.x - (overlayOrigin?.x ?: 0f) - 1f).coerceAtLeast(0f)),
                    position.y.coerceIn(0f, (screen.y - (overlayOrigin?.y ?: 0f) - 1f).coerceAtLeast(0f))
                )
                eventHandler.mousePointerPosition = _mousePointerOffset.value + (overlayOrigin ?: Offset.Zero)
                return eventHandler.handlePointerMove(rawOffset)

            }

            MotionEvent.ACTION_SCROLL -> {
                val vScroll = motionEvent.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (vScroll != 0f) {
                    return eventHandler.handleScroll(vScroll)
                }
            }
        }
        return true
    }

    fun clearActivePointers() {
        mouseButtons.reset()
        eventHandler.clear()
    }

    /** 把鼠标物理按钮映射成 KeySync 内部键码（LMB/RMB/MMB），用于按下高亮。 */
    private fun mouseButtonToKeyCode(button: Int): Int? = when (button) {
        MotionEvent.BUTTON_PRIMARY -> KEYCODE_LMC
        MotionEvent.BUTTON_SECONDARY -> KEYCODE_RMC
        MotionEvent.BUTTON_TERTIARY -> KEYCODE_MMC
        else -> null
    }

    /**
     * ACTION_BUTTON_* normally exposes the changed button through actionButton.
     * TouchToMouseTranslator cannot call MotionEvent.setActionButton because it
     * is a hidden Android API, so its synthetic events encode that one button
     * in buttonState as an internal fallback.
     */
    private fun modifiedMouseButton(event: MotionEvent): Int =
        event.actionButton.takeIf { it != 0 } ?: event.buttonState

    fun onDestroy() {
        _isBubbleExpanded.value = false
        // Drag operations mutate the runtime item objects directly. Persist
        // once more before the overlay disappears so a service stop does not
        // lose the last positioning edit.
        persistActiveProfile()
        eventHandler.clear()
    }

    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    private fun getStatusBrHeight(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return windowManager.currentWindowMetrics.windowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top
        } else {
            val resourceId =
                Resources.getSystem().getIdentifier("status_bar_height", "dimen", "android")
            return if (resourceId > 0) Resources.getSystem()
                .getDimensionPixelSize(resourceId) else 0

        }
    }
}

private const val LEGACY_KEYCODE_LMC = 64
private const val LEGACY_KEYCODE_RMC = 128
private const val LEGACY_KEYCODE_MMC = 256
