package com.devoid.keysync.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import com.devoid.keysync.data.local.DataStoreManager
import com.devoid.keysync.data.local.ProfilesLoad
import com.devoid.keysync.model.AppConfig
import com.devoid.keysync.model.DraggableItem
import com.devoid.keysync.model.DraggableItemType
import com.devoid.keysync.domain.MouseButtonTracker
import com.devoid.keysync.domain.ScrollTracker
import com.devoid.keysync.domain.mouseButtonKeyCode
import com.devoid.keysync.domain.mouseKeyAlias
import com.devoid.keysync.model.ClickMacro
import com.devoid.keysync.model.ProfileAssist
import com.devoid.keysync.model.validateClickMacros
import com.devoid.keysync.model.withAssist
import com.devoid.keysync.model.SeqAction
import com.devoid.keysync.util.keyCodeToString
import com.devoid.keysync.domain.EventHandler
import com.devoid.keysync.domain.KEYCODE_LMC
import com.devoid.keysync.domain.KEYCODE_MMC
import com.devoid.keysync.domain.KEYCODE_RMC
import com.devoid.keysync.data.external.ShizukuSystemServerAPi
import com.devoid.keysync.model.Profile
import com.devoid.keysync.model.ExtraTapPoint
import com.devoid.keysync.model.EXTRA_TAP_WINDOW_MS
import com.devoid.keysync.model.MAX_EXTRA_TAP_POINTS
import com.devoid.keysync.model.projectLayout
import com.devoid.keysync.model.clampToViewport
import com.devoid.keysync.model.ProfileBundle
import com.devoid.keysync.model.independentCopy
import com.devoid.keysync.model.importProfileCopies
import com.devoid.keysync.model.TouchMode
import com.devoid.keysync.model.withMeasuredPositionFrom
import com.devoid.keysync.model.defaultKeyCode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    /**
     * SupervisorJob keeps one failed child from cancelling its siblings; the
     * handler keeps the failure off the default uncaught-exception path, which
     * on `Dispatchers.Main` means killing the process.
     */
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "uncaught failure in FloatingWindowStateManager scope", throwable)
    }

    val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + exceptionHandler)

    private val shizukuSystemServerAPi = ShizukuSystemServerAPi()
    private val eventHandler: EventHandler by lazy { shizukuSystemServerAPi.getEventHandler() }
    val windowManager: WindowManager by lazy { context.getSystemService(WindowManager::class.java) }
    private val displayMetrics = context.resources.displayMetrics

    val pointerSensitivity = MutableStateFlow(0.5f)
    val overlayOpacity = MutableStateFlow(0.5f)

    // 按键拟人化（随机偏移）：全局生效、对所有预设统一，不随预设存储。
    val wasdHumanization = MutableStateFlow(false)
    val keyHumanization = MutableStateFlow(false)
    /** 偏移强度 1–100（100 = 最大）。 */
    val humanizationStrength = MutableStateFlow(50f)

    /** WASD 方向晃动幅度 1–100，独立于整体强度。 */
    val wasdSway = MutableStateFlow(50f)
    /** 随机偏移间隔（ms），默认 1000（约每秒一次）。 */
    val humanizationInterval = MutableStateFlow(1000L)

    /** WASD 换向弧线过渡：默认开启，80ms；全局设置。 */
    val wasdTransition = MutableStateFlow(true)
    val wasdTransitionMs = MutableStateFlow(EventHandler.DEFAULT_WASD_TRANSITION_MS)

    /* ----------------- 鼠标高级参数（全局，与预设无关） ----------------- */

    /** 单帧位移超过该值就视为光标跳变并丢弃；默认 240px。 */
    val mouseJumpThresholdPx = MutableStateFlow(DEFAULT_MOUSE_JUMP_THRESHOLD_PX)
    /** 为 true 时，一段操作内固定相对轴/绝对坐标解析方式，避免模式抖动。 */
    val mouseModeLatch = MutableStateFlow(true)
    /** 为 true 时缓存屏幕尺寸，避免每个鼠标事件都查询 WindowManager。 */
    val screenSizeCacheEnabled = MutableStateFlow(true)

    // 悬浮窗按键的显示/隐藏开关（一键切换，不影响功能）。
    private val _keysVisible = MutableStateFlow(true)
    val keysVisible = _keysVisible.asStateFlow()

    // 顶部状态提示条（预设名 · 模式 · 最近输入）的显隐。
    // 与 keysVisible 不同，这个开关是持久化的：提示条只是信息展示，
    // 用户关掉它通常是想一直关着，而不是每次重启服务都要再关一次。
    private val _statusBarVisible = MutableStateFlow(true)
    val statusBarVisible = _statusBarVisible.asStateFlow()

    private val _appConfig = MutableStateFlow(AppConfig.Default)
    val keysConfig = _appConfig.asStateFlow()

    // 鼠标（瞄准）灵敏度：只作用于射击模式下的转视角（见 onMouseEvent），
    // 把 0.1..1.0 的滑块映射成 0.3x..3x 的增益（默认 0.5 → 1.5x）。
    // 非射击模式的鼠标指针是「光标定位」，跟手应当 1:1，不做灵敏度缩放；
    // 键鼠映射工具（熊猫映射等）的「鼠标灵敏度」也是指瞄准灵敏度，默认约 1.2–1.5x。
    val sensitivity: Float get() = 3f * pointerSensitivity.value

    // 编辑态：入口是通知栏的「编辑布局」action，屏幕上不再有常驻气泡。
    // true 时悬浮窗可触摸/可聚焦，允许拖动与绑定按键。
    private val _isEditMode = MutableStateFlow(false)
    val isEditMode = _isEditMode.asStateFlow()

    // 暂停映射：不拦截触控、不注入任何事件，用于临时打字/操作其他界面。
    // 只存在于本次服务生命周期内，不持久化——重启服务后默认恢复映射。
    private val _mappingPaused = MutableStateFlow(false)
    val mappingPaused = _mappingPaused.asStateFlow()

    private val _mousePointerOffset =
        MutableStateFlow(Offset(displayMetrics.widthPixels / 2f, displayMetrics.heightPixels / 2f))
    val pointerOffset = _mousePointerOffset.asStateFlow()

    // Captured-pointer 事件里 rawX/rawY 不携带位移信息（真正的位移在相对轴，
    // 见 onMouseEvent）。少数 ROM 不填 AXIS_RELATIVE_X/Y，需要退回「相邻事件
    // raw 坐标差」兜底，这两个字段就用来记上一次的 raw 坐标。
    private var overlayOrigin: Offset? = null
    private var overlayViewport: Offset? = null
    // Dimensions of the coordinates currently in _containerItems, not today's orientation.
    private var layoutViewport: Offset? = null

    private val _reportedViewport = MutableStateFlow<Offset?>(null)

    /**
     * The overlay window's own size in pixels, or null until the service reports
     * its first frame. Unlike [viewportForLayout] this never falls back to a
     * display metric, so the home screen can tell "the overlay has not measured
     * itself yet" apart from "the overlay is exactly as big as the display".
     */
    val reportedViewport: StateFlow<Offset?> = _reportedViewport.asStateFlow()

    /**
     * The rectangle the on-screen items are laid out in, and therefore the space
     * their coordinates are expressed in. Callers that build items from a preset
     * must scale by this rather than by a display metric: the two disagree about
     * system bars, and about orientation.
     */
    val viewportForLayout: Offset
        get() = overlayViewport ?: currentScreenSize()

    fun onOverlayFrame(width: Int, height: Int, origin: Offset) {
        if (width <= 0 || height <= 0) return
        val viewport = Offset(width.toFloat(), height.toFloat())
        if (overlayViewport == viewport && overlayOrigin == origin) return
        // 这里不再 persist：窗口重建/首帧/insets 落地时的尺寸是瞬态的，
        // 把按瞬态尺寸投影出的坐标写回预设会让边缘按钮被永久压平。
        // 预设坐标只在用户实际操作（拖拽、改键、缩放等）时写回。
        overlayViewport = viewport
        overlayOrigin = origin
        _reportedViewport.value = viewport
        val held = eventHandler.heldKeyCodes()
        val mouseHeld = eventHandler.heldMouseButtons()
        applyActiveProfile()
        eventHandler.replayHeldKeys(held)
        eventHandler.restoreShootingModeContact()
        eventHandler.replayHeldMouseButtons(mouseHeld)
    }
    private val mouseButtons = MouseButtonTracker()
    private val scrollTracker = ScrollTracker()
    private val _lastInputLabel = MutableStateFlow("等待输入")
    val lastInputLabel = _lastInputLabel.asStateFlow()
    private var lastMouseRawX = Float.NaN
    private var lastMouseRawY = Float.NaN
    // 手势期间锁存的解析方式；null 表示尚未决定。换设备/新手势时清空。
    private var latchedRelativeAxisMode: Boolean? = null
    private var lastMouseDeviceId: Int = -1
    // 屏幕尺寸缓存；服务收到 configuration change 时清空。
    private var cachedScreenSize: Offset? = null
    /** 光标模式是否真正出现过鼠标输入；没出现过就不允许 Alt+D 拖拽，避免盲点。 */
    private var mouseCursorSeen = false
    /** Alt+D 拖拽：按住 Alt 时按 D 触发，由高级功能开关控制。 */
    val discardDragEnabled = MutableStateFlow(false)
    /** Alt+D 拖拽速度倍数（1–10）：值越大移动越快，默认 5 = 原速的 5 倍。 */
    val discardDragSpeed = MutableStateFlow(DEFAULT_DISCARD_DRAG_SPEED)
    private var discardDragJob: Job? = null
    /** M 键开关地图并切换鼠标（全局），默认开启。 */
    val mapMouseToggleEnabled = MutableStateFlow(true)
    /** M 键当前是否处于「地图已打开」状态；重启服务后归零。 */
    private var mapToggleOpen = false
    /** ESC / Tab 关闭界面后自动回瞄准（全局），默认开启。 */
    val tabEscAimEnabled = MutableStateFlow(true)
    /** 回瞄准延迟任务：等游戏把界面关掉再重建瞄准触点。 */
    private var aimReturnJob: Job? = null

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

    /**
     * Set when profiles cannot be read. The original data remains untouched;
     * the temporary in-memory layout must never be presented as a saved layout.
     */
    private val _profilesLoadError = MutableStateFlow<String?>(null)
    val profilesLoadError = _profilesLoadError.asStateFlow()

    fun consumeProfilesLoadError() {
        _profilesLoadError.value = null
    }

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
            // 读取全局按键拟人化设置并立即应用到事件处理器。
            wasdHumanization.value =
                dataStoreManager.getBoolean(DataStoreManager.WASD_HUMANIZATION).first() ?: false
            keyHumanization.value =
                dataStoreManager.getBoolean(DataStoreManager.KEY_HUMANIZATION).first() ?: false
            // 强度已改为 1–100；旧版本存的是 0–1 的 float，这里做一次迁移。
            val scaled = dataStoreManager.getInt(DataStoreManager.HUMANIZATION_STRENGTH_SCALE).first()
            humanizationStrength.value = if (scaled != null) {
                scaled.coerceIn(1, 100).toFloat()
            } else {
                val legacy = dataStoreManager.getFloat(DataStoreManager.HUMANIZATION_STRENGTH).first() ?: 0.5f
                (legacy * 100f).coerceIn(1f, 100f)
            }
            humanizationInterval.value =
                (dataStoreManager.getInt(DataStoreManager.HUMANIZATION_INTERVAL_MS).first()
                    ?: EventHandler.DEFAULT_HUMANIZATION_INTERVAL_MS.toInt()).toLong()
            wasdSway.value =
                (dataStoreManager.getInt(DataStoreManager.WASD_SWAY).first() ?: 50)
                    .coerceIn(1, 100).toFloat()
            wasdTransition.value =
                dataStoreManager.getBoolean(DataStoreManager.WASD_TRANSITION).first() ?: true
            wasdTransitionMs.value =
                (dataStoreManager.getInt(DataStoreManager.WASD_TRANSITION_MS).first()
                    ?: EventHandler.DEFAULT_WASD_TRANSITION_MS)
                    .coerceIn(EventHandler.MIN_WASD_TRANSITION_MS, EventHandler.MAX_WASD_TRANSITION_MS)
            // 顶部提示条是持久化开关：默认显示，用户关掉后下次启动仍然隐藏。
            _statusBarVisible.value =
                dataStoreManager.getBoolean(DataStoreManager.STATUS_BAR_VISIBLE).first() ?: true
            mouseJumpThresholdPx.value =
                (dataStoreManager.getInt(DataStoreManager.MOUSE_JUMP_THRESHOLD_PX).first()
                    ?: DEFAULT_MOUSE_JUMP_THRESHOLD_PX).coerceIn(40, 800)
            mouseModeLatch.value =
                dataStoreManager.getBoolean(DataStoreManager.MOUSE_MODE_LATCH).first() ?: true
            screenSizeCacheEnabled.value =
                dataStoreManager.getBoolean(DataStoreManager.SCREEN_SIZE_CACHE).first() ?: true
            discardDragEnabled.value =
                dataStoreManager.getBoolean(DataStoreManager.DISCARD_DRAG_ENABLED).first() ?: false
            discardDragSpeed.value =
                (dataStoreManager.getFloat(DataStoreManager.DISCARD_DRAG_SPEED).first()
                    ?: DEFAULT_DISCARD_DRAG_SPEED)
                    .coerceIn(MIN_DISCARD_DRAG_SPEED, MAX_DISCARD_DRAG_SPEED)
            mapMouseToggleEnabled.value =
                dataStoreManager.getBoolean(DataStoreManager.MAP_MOUSE_TOGGLE_ENABLED).first() ?: true
            tabEscAimEnabled.value =
                dataStoreManager.getBoolean(DataStoreManager.TAB_ESC_AIM_ENABLED).first() ?: true
            // 两条 init 协程的完成顺序不确定：若预设先加载完，这里要把刚读到的拟人化设置补应用一次。
            syncHumanization()
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
        val load = dataStoreManager.getProfilesLoad().first()
        if (load is ProfilesLoad.Unreadable) {
            // Even a successful backup is not consent to replace the user's layouts.
            // Keep a temporary profile in memory and skip ALL migration/write paths.
            val backedUp = if (load.raw != null) {
                try {
                    dataStoreManager.quarantineUnreadableProfiles(load.raw)
                    true
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "could not back up unreadable profiles; original remains untouched", e)
                    false
                }
            } else false
            _profilesLoadError.value = if (backedUp)
                "预设解析失败，原始数据已保留并备份。本次预设修改不会保存，请重启后重试。"
            else "预设读取失败，未覆盖原始数据。本次预设修改不会保存，请重启后重试。"
            val viewport = currentScreenSize()
            val temporary = Profile(id = UUID.randomUUID().toString(), name = "临时预设（不会保存）",
                layoutScreenWidth = viewport.x.toInt(), layoutScreenHeight = viewport.y.toInt())
            _profiles.value = listOf(temporary)
            _activeProfileId.value = temporary.id
            // 等悬浮层首帧尺寸到达后再投影；此前用整屏 bounds 会污染坐标。
            if (overlayViewport != null) applyActiveProfile()
            return
        }
        val storedActiveId = dataStoreManager.getActiveProfileId().first()

        var stored: List<Profile> = when (load) {
            is ProfilesLoad.Loaded -> load.profiles

            is ProfilesLoad.Absent -> {
                // Genuine first run (or an install that predates profile
                // storage): fold any legacy per-package layout in and adopt it.
                val fresh = listOf(migrateLegacy())
                dataStoreManager.saveProfiles(fresh)
                fresh
            }

            is ProfilesLoad.Unreadable -> error("Unreadable profiles handled before migration")
        }

        if (stored.isEmpty()) {
            stored = listOf(Profile(id = UUID.randomUUID().toString(), name = "默认"))
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

        // 分辨率基准迁移：旧数据未记录分辨率，统一假定 legacy 基准，
        // 实际换算会在 applyActiveProfile 时按当前屏幕滚动完成。
        migrateLegacyLayoutScreen(stored).let { migrated ->
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
        // 悬浮层还没布局时先不投影：currentScreenSize() 是整屏 bounds（含系统栏），
        // 与预设保存的可用区域坐标系不同。等 onOverlayFrame 拿到真实尺寸后再应用。
        if (overlayViewport != null) applyActiveProfile()
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

    // 现存的历史布局是基于 legacyLayoutScreenWidth x legacyLayoutScreenHeight 的屏幕配置的。
    // 这些值也作为「未记录分辨率」旧数据的默认基准；实际换算在 applyActiveProfile 中按当前屏幕完成。
    private companion object {
        const val legacyLayoutScreenWidth = 2608
        const val legacyLayoutScreenHeight = 1200
        /** 鼠标断点阈值默认值；用户可在设置页的高级项里调整（全局）。 */
        const val DEFAULT_MOUSE_JUMP_THRESHOLD_PX = 240
        /** Alt+D 拖拽：单程分几步、每步间隔（ms）；12 步 × 14ms ≈ 170ms。 */
        const val DRAG_STEPS = 12
        const val DRAG_STEP_MS = 14L
        /** 拖拽速度倍数默认值与范围（设置页滑杆）。 */
        const val DEFAULT_DISCARD_DRAG_SPEED = 5f
        const val MIN_DISCARD_DRAG_SPEED = 1f
        const val MAX_DISCARD_DRAG_SPEED = 10f
        /** 快捷换位触发键：1/2（3/4 固定用于近战流程，不参与换位）。 */
        val SWAP_SLOT_KEYS = setOf(
            KeyEvent.KEYCODE_1,
            KeyEvent.KEYCODE_2,
        )
        /** 固定还原键：X（不对用户开放配置）。 */
        val SWAP_RESTORE_KEY = KeyEvent.KEYCODE_X
    }

    /** 旧数据未记录分辨率时，统一假定为 legacy 基准，便于 applyActiveProfile 迁移。 */
    private fun migrateLegacyLayoutScreen(stored: List<Profile>): List<Profile>? {
        var changed = false
        val migrated = stored.map { p ->
            if (p.layoutScreenWidth > 0 && p.layoutScreenHeight > 0) p
            else {
                changed = true
                p.copy(
                    layoutScreenWidth = legacyLayoutScreenWidth,
                    layoutScreenHeight = legacyLayoutScreenHeight,
                )
            }
        }
        return migrated.takeIf { changed }
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
        val id = _activeProfileId.value ?: return
        val active = _profiles.value.firstOrNull { it.id == id } ?: return
        val viewport = overlayViewport ?: currentScreenSize()
        val currentW = viewport.x.toInt()
        val currentH = viewport.y.toInt()
        val baseW = active.layoutScreenWidth.takeIf { it > 0 } ?: legacyLayoutScreenWidth
        val baseH = active.layoutScreenHeight.takeIf { it > 0 } ?: legacyLayoutScreenHeight
        val needsScale = currentW != baseW || currentH != baseH
        val items = projectLayout(active.items, Offset(baseW.toFloat(), baseH.toFloat()), viewport, active.appConfig.buttonScale)
        layoutViewport = viewport
        _containerItems.value = items
        _appConfig.value = active.appConfig
        eventHandler.appConfig = active.appConfig
        eventHandler.setCancelableTouchMode(active.appConfig.cancellableTouchMode)
        eventHandler.setNormalBtnTouchMode(active.appConfig.normalBtnTouchMode)
        // 灵敏度随预设切换。
        pointerSensitivity.value = active.appConfig.pointerSensitivity
        android.util.Log.i("KeySyncLayout", "apply id=${id} origin=${overlayOrigin?.let { it.x to it.y }} screen=${currentW}x$currentH base=${baseW}x$baseH scaled=$needsScale items=${items.size}")
        overlayOrigin?.let { origin ->
            items.forEach { item ->
                val size = when (item) {
                    is DraggableItem.FixedKey -> item.size
                    is DraggableItem.VariableKey -> item.size
                    else -> 0
                }
                if (size > 0) item.touchCenter = origin + item.position + Offset(size / 2f, size / 2f)
            }
        }
        eventHandler.updateKeyMapping(items)
        syncMacros()
        scrollTracker.reset()
    }

    private fun syncMacros() {
        val macros = activeProfile()?.macros.orEmpty()
        eventHandler.setMacros(macros,
            overlayViewport ?: currentScreenSize(), overlayOrigin ?: Offset.Zero)
    }

    fun updateMacroPoint(macroId: String, stepId: String, x: Float, y: Float) {
        if (!_isEditMode.value || !x.isFinite() || !y.isFinite()) return
        _profiles.value = _profiles.value.map { profile ->
            if (profile.id != _activeProfileId.value) profile else profile.copy(macros = profile.macros.map { macro ->
                if (macro.id != macroId) macro else macro.copy(steps = macro.steps.map { step ->
                    if (step.id != stepId) step else step.copy(x = x.coerceIn(0f, 1f), y = y.coerceIn(0f, 1f))
                })
            })
        }
    }

    fun saveMacroPoints() {
        syncMacros()
        persistActiveProfile()
    }

    private fun activeProfile(): Profile? {
        val id = _activeProfileId.value ?: return null
        return _profiles.value.firstOrNull { it.id == id }
    }

    /** Persist the current in-memory items/appConfig back into the active profile. */
    private fun persistActiveProfile() {
        val id = _activeProfileId.value ?: return
        val applied = layoutViewport != null
        val updated = _profiles.value.map { profile ->
            if (profile.id == id) {
                val layout = layoutViewport ?: overlayViewport
                profile.copy(
                    // 尚未完成首次投影时容器是空的，直接沿用预设里的 items，避免把布局清空。
                    items = if (applied) _containerItems.value.map { it.copy() } else profile.items,
                    appConfig = _appConfig.value,
                    layoutScreenWidth = layout?.x?.toInt() ?: profile.layoutScreenWidth,
                    layoutScreenHeight = layout?.y?.toInt() ?: profile.layoutScreenHeight,
                )
            } else profile
        }
        _profiles.value = updated
        android.util.Log.i("KeySyncLayout", "persist id=${id} origin=${overlayOrigin?.let { it.x to it.y }} screen=${displayMetrics.widthPixels}x${displayMetrics.heightPixels} items=${_containerItems.value.size}")
        if (dataStoreManager.canWriteProfiles) {
            scope.launch { dataStoreManager.saveProfiles(updated) }
        }
    }

    /* ----------------- swap pairs (swapOn / swapOff) ----------------- */

    /* ----------------- 按键辅助（设置页一次性保存） ----------------- */

    /**
     * 设置页把「按键辅助」草稿一次性写回预设。宏校验失败时整批不落盘，
     * 避免只保存了一半的开关。开关的即时语义（Tab 防连按、姿态取消）
     * 与以前单独设置时保持一致。
     */
    fun saveProfileAssist(profileId: String, assist: ProfileAssist) {
        if (validateClickMacros(assist.macros) != null) return
        val target = _profiles.value.firstOrNull { it.id == profileId } ?: return
        val updatedProfile = target.withAssist(assist)
        _profiles.value = _profiles.value.map { if (it.id == profileId) updatedProfile else it }
        if (_activeProfileId.value == profileId) {
            if (!assist.tabDebounceEnabled) {
                lastAcceptedTabAt = 0L
                swallowedTabDown = false
            }
            if (!assist.stanceAssistEnabled) stanceKeyCode = null
            syncMacros()
        }
        if (dataStoreManager.canWriteProfiles) {
            scope.launch { dataStoreManager.saveProfiles(_profiles.value) }
        }
    }

    /** 最后一次按下的姿态键（C / Z / Ctrl）；null 表示当前没有可取消的姿态。 */
    private var stanceKeyCode: Int? = null
    private var stanceTapJob: Job? = null

    private val stanceKeys = setOf(
        KeyEvent.KEYCODE_C,
        KeyEvent.KEYCODE_Z,
        KeyEvent.KEYCODE_CTRL_LEFT,
        KeyEvent.KEYCODE_CTRL_RIGHT,
    )

    /**
     * 蹲/趴与 Shift 的协调（与静步同理，只取消「我们自己点开的」姿态）。
     *
     * - 按 C（趴）/ Z / Ctrl（蹲）：记录最后一次姿态键；同一个键再按一次视为关闭
     * - 按 Shift：若记录了姿态，补点一次同一个姿态键把它取消，然后开始跑
     * - 姿态键或 Shift 没有绑定按钮时不介入，避免按了没反应
     */
    private fun trackStanceAssist(keyCode: Int) {
        val profile = _profiles.value.firstOrNull { it.id == _activeProfileId.value }
        if (profile == null || !profile.stanceAssistEnabled) {
            stanceKeyCode = null
            return
        }
        val items = _containerItems.value
        if (keyCode in stanceKeys) {
            // 姿态键本身没绑按钮就无从点起，不记录。
            if (items.none { it.boundKeyCode() == keyCode }) return
            stanceKeyCode = if (stanceKeyCode == keyCode) null else keyCode
            return
        }
        if (keyCode != KeyEvent.KEYCODE_SHIFT_LEFT && keyCode != KeyEvent.KEYCODE_SHIFT_RIGHT) return
        val stance = stanceKeyCode ?: return
        stanceKeyCode = null
        if (stanceTapJob?.isActive == true) return
        stanceTapJob = scope.launch {
            val at = SystemClock.uptimeMillis()
            eventHandler.handleKeyEvent(KeyEvent(at, at, KeyEvent.ACTION_DOWN, stance, 0))
            // 快速补点，尽量在 Shift 生效前把姿态取消掉。
            delay(kotlin.random.Random.nextLong(35L, 66L))
            eventHandler.handleKeyEvent(KeyEvent(at, at, KeyEvent.ACTION_UP, stance, 0))
        }
    }

    // 连按落点计数：同一个键在窗口内连续按下时递增，超时或换键就重新从 1 开始。
    private var extraTapKeyCode = -1
    private var extraTapCount = 0
    private var extraTapLastAt = 0L

    /**
     * 连按落点触发：第 N 次按下（N≥2）额外点击该键的第 N-1 个落点，最多 3 个。
     * 本体映射不受影响，由调用方继续走普通按键流程。
     */
    private fun handleExtraTap(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        if (keyCode != extraTapKeyCode || now - extraTapLastAt > EXTRA_TAP_WINDOW_MS) {
            extraTapKeyCode = keyCode
            extraTapCount = 0
        }
        extraTapLastAt = now
        extraTapCount++
        val index = extraTapCount - 2
        if (index !in 0 until MAX_EXTRA_TAP_POINTS) return
        val profile = _profiles.value.firstOrNull { it.id == _activeProfileId.value } ?: return
        val point = profile.extraTapPoints.filter { it.keyCode == keyCode }.getOrNull(index) ?: return
        eventHandler.tapExtraPoint(Offset(point.x, point.y))
    }

    // Tab 防连按：记录最后一次被接受的 Tab 按下时间，以及本次 Tab 是否已被吞掉。
    // 被吞掉的 DOWN 必须连同 UP 一起吞，否则 UP 仍会走映射，可能出现残留状态。
    private var lastAcceptedTabAt = 0L
    private var swallowedTabDown = false

    /**
     * Tab 防连按（预设级子开关）。
     *
     * 开启后，距上一次有效 Tab 不足预设间隔（默认 1000ms，可调）的重复 Tab 整段吞掉：
     * - 快速连按 Tab 时，多余的按下不会落到游戏里（避免点到别的东西）
     *
     * 只拦截 Tab，其他按键不受影响；长按产生的重复事件跟随首次判定。
     */
    private fun guardTabRepeat(keyEvent: KeyEvent): Boolean {
        if (keyEvent.keyCode != KeyEvent.KEYCODE_TAB) return false
        val profile = _profiles.value.firstOrNull { it.id == _activeProfileId.value } ?: return false
        if (!profile.tabDebounceEnabled) {
            swallowedTabDown = false
            return false
        }
        val guardMs = profile.tabDebounceMs.coerceIn(200, 3000).toLong()
        return when (keyEvent.action) {
            KeyEvent.ACTION_DOWN -> {
                if (keyEvent.repeatCount > 0) {
                    swallowedTabDown
                } else {
                    val now = SystemClock.uptimeMillis()
                    val tooSoon = lastAcceptedTabAt != 0L &&
                        now - lastAcceptedTabAt < guardMs
                    swallowedTabDown = tooSoon
                    if (!tooSoon) lastAcceptedTabAt = now
                    tooSoon
                }
            }
            KeyEvent.ACTION_UP -> {
                val swallowed = swallowedTabDown
                swallowedTabDown = false
                swallowed
            }
            else -> false
        }
    }

    private var tapSequence234Job: Job? = null
    private var keyMacroJob: Job? = null

    /**
     * 按 3 切近战：依次点击 2 → 4 → 3。
     *
     * 游戏会按携带情况重排四个格子：单枪时近战在 2，双枪时近战在 4，
     * 双枪 + 手枪时近战在 3。在 2 → 4 → 3 这个固定顺序里，三种布局的近战位
     * 都只出现一次（单枪在 2、双枪在 4、双枪+手枪在 3），所以最终一定停在近战上。
     * 点空位不会切走当前武器，没有副作用——这是固定顺序也能通用的前提。
     *
     * 真正要避免的是「近战位被点第二次」：刀已经在手上时再点同一格，游戏会
     * 收回刀、切回主武器 1。因此一轮里近战位只出现一次；开大/已对调时也只跑
     * 一轮（先等卸盾动作结束），绝不补第二轮。
     *
     * 另外游戏内需要关闭「快速切枪」：它会在切换后自动切回主武器，
     * 破坏最后落在近战上的结果。
     *
     * 三个触点都走正常的按键映射（`eventHandler.handleKeyEvent`），所以用户把
     * 2/4/3 绑到哪个位置就点哪里。整个过程串行执行，重复按 3 不会叠加。
     */
    private fun runTapSequence234(keyCode: Int): Boolean {
        if (keyCode != KeyEvent.KEYCODE_3) return false
        val profile = _profiles.value.firstOrNull { it.id == _activeProfileId.value } ?: return false
        if (!profile.tapSequence234Enabled) return false
        // 三个按钮缺一就不接管，让 3 走原本的单键映射，避免按了没反应。
        val targets = listOf(KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_3)
        val items = _containerItems.value
        if (targets.any { code -> items.none { it.boundKeyCode() == code } }) return false
        if (tapSequence234Job?.isActive == true) return true
        // 间隔由预设决定（5–1000ms），按住时长取间隔的一半左右，整体节奏随间隔缩放。
        val baseGap = profile.tapSequence234GapMs.coerceIn(5, 1000)
        // 两个按钮处于对调状态时（通常是开大后），3 会先触发卸盾动作；等动作结束再注入。
        val swapDelayMs = if (profile.quickSwapLatched) {
            profile.meleeSwapDelayMs.coerceIn(0, 3000).toLong()
        } else {
            0L
        }
        tapSequence234Job = scope.launch {
            // 已对调（开大后）先等卸盾动作结束；只跑一轮，避免近战位被点第二次。
            if (swapDelayMs > 0) delay(swapDelayMs)
            injectMeleeSequence(targets, baseGap)
        }
        return true
    }

    /** 串行注入一轮 2 → 4 → 3，每个触点走正常按键映射，段间带 ±10% 抖动。 */
    private suspend fun injectMeleeSequence(targets: List<Int>, baseGap: Int) {
        targets.forEach { code ->
            val now = SystemClock.uptimeMillis()
            eventHandler.handleKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
            val hold = (baseGap / 2 + kotlin.random.Random.nextLong(-5L, 6L))
                .coerceIn(15L, 80L)
            delay(hold)
            eventHandler.handleKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
            // 间隔本身也加 ±10% 抖动，避免固定节拍。
            val jittered = (baseGap * (0.9f + kotlin.random.Random.nextFloat() * 0.2f)).toLong()
            delay(jittered.coerceAtLeast(2L))
        }
    }

    /**
     * 快速切枪宏：触发键（默认 Alt+9）按下后，按预设步骤直接注入原始触点。
     *
     * 中间步骤刻意不经过 applyQuickSwap / runTapSequence234 / handleExtraTap：
     * 宏里的 1、X 只作为游戏按键生效，不会翻转 R/右键布局，也不会改变换位锁存。
     * 但宏正常跑完后，如果最后一步落在换位键（1/2）上，会补一次换位，
     * 相当于游戏里最后真的按了一次那个键（快速切枪宏默认以 1 结尾）。
     * 每一步在对应按钮的当前位置按下 holdMs 再抬起，然后等待该步的延迟。
     */
    private fun runKeyMacro(keyEvent: KeyEvent): Boolean {
        val profile = _profiles.value.firstOrNull { it.id == _activeProfileId.value } ?: return false
        val macros = listOf(
            profile.keyMacro,
            profile.shieldMacro,
            profile.scallopMacro,
            profile.scallopDashMacro,
        )
        // 宏运行期间吞掉所有宏触发键，避免两段宏叠加。
        if (keyMacroJob?.isActive == true) {
            return macros.any {
                it.enabled && it.triggerKeyCode == keyEvent.keyCode &&
                    (!it.requireAlt || keyEvent.isAltPressed)
            }
        }
        val macro = macros.firstOrNull {
            it.enabled && it.steps.isNotEmpty() &&
                it.triggerKeyCode == keyEvent.keyCode &&
                (!it.requireAlt || keyEvent.isAltPressed)
        } ?: return false
        val items = _containerItems.value
        if (macro.steps.none { step ->
                step.action == SeqAction.WAIT || items.any { it.boundKeyCode() == step.keyCode }
            }
        ) {
            return false
        }
        val hold = macro.holdMs.coerceIn(5, 100).toLong()
        keyMacroJob = scope.launch {
            // DOWN 的触点要跨步骤保持，UP 时按同一个 keyCode 释放。
            val held = mutableMapOf<Int, Int>()
            var completed = true
            try {
                for (step in macro.steps) {
                    if (_isEditMode.value || _mappingPaused.value) {
                        completed = false
                        break
                    }
                    when (step.action) {
                        SeqAction.WAIT -> Unit
                        SeqAction.DOWN -> {
                            if (held[step.keyCode] == null) {
                                val item = items.firstOrNull { it.boundKeyCode() == step.keyCode }
                                    ?: continue
                                val center = item.touchCenter ?: continue
                                val pointer = eventHandler.allocateSeqPointer()
                                eventHandler.pressSeqPointer(pointer, center)
                                held[step.keyCode] = pointer
                            }
                        }
                        SeqAction.UP -> {
                            held.remove(step.keyCode)?.let {
                                eventHandler.releaseSeqPointer(it)
                            }
                        }
                        SeqAction.TAP -> {
                            val item = items.firstOrNull { it.boundKeyCode() == step.keyCode }
                                ?: continue
                            val center = item.touchCenter ?: continue
                            val pointer = eventHandler.allocateSeqPointer()
                            eventHandler.pressSeqPointer(pointer, center)
                            delay(hold)
                            eventHandler.releaseSeqPointer(pointer)
                        }
                    }
                    if (step.delayMs > 0) delay(step.delayMs.toLong())
                }
            } finally {
                // 异常/中途退出时把仍按着的触点松开，避免卡键。
                held.values.forEach { eventHandler.releaseSeqPointer(it) }
            }
            // 正常跑完后补一次换位：宏最后一步是 1/2 时，等同于游戏里最后按了该键。
            // 中间步骤仍然不换位，避免一次宏里反复翻转按钮。
            if (completed) {
                macro.steps.lastOrNull { it.action != SeqAction.WAIT }
                    ?.keyCode
                    ?.takeIf { it in SWAP_SLOT_KEYS }
                    ?.let { applyQuickSwap(it) }
            }
        }
        return true
    }

    /**
     * 快捷换位：按 1/2 让「R 键按钮」与「鼠标右键按钮」交换屏幕位置，按 X 还原。
     *
     * 键位固定，不做任何用户配置；只有预设级开关 [Profile.quickSwapEnabled]。
     * 3/4 固定用于近战流程，不参与换位。
     * 不依赖任何手动设置：A/B 每次按当前绑定实时解析，所以用户重绑 R 或右键后
     * 依然正确；位置恢复使用 anchorPosition，不会漂移。
     *
     * 锁存：未换位时按 1/2 换一次；已换位时再按只当普通按键透传；
     * 还原键 X 只在已换位时生效。锁存状态存在 [Profile.quickSwapLatched] 并持久化，
     * 重启后位置仍是换位状态，不会被误判成未换位而再跳一次。
     */
    private fun applyQuickSwap(keyCode: Int): Boolean {
        val id = _activeProfileId.value ?: return false
        val profile = _profiles.value.firstOrNull { it.id == id } ?: return false
        if (!profile.quickSwapEnabled) return false
        val isOnKey = keyCode in SWAP_SLOT_KEYS
        if (!isOnKey && keyCode != SWAP_RESTORE_KEY) return false
        val items = _containerItems.value
        val r = items.firstOrNull { it.boundKeyCode() == KeyEvent.KEYCODE_R } ?: return false
        val rmb = items.firstOrNull { it.boundKeyCode() == KEYCODE_RMC } ?: return false
        if (r === rmb) return false
        if (isOnKey == profile.quickSwapLatched) return false
        r.anchorPosition = r.anchorPosition ?: r.position
        rmb.anchorPosition = rmb.anchorPosition ?: rmb.position
        if (isOnKey) {
            r.position = rmb.anchorPosition!!
            rmb.position = r.anchorPosition!!
        } else {
            r.position = r.anchorPosition!!
            rmb.position = rmb.anchorPosition!!
        }
        _profiles.value = _profiles.value.map {
            if (it.id != id) it else it.copy(quickSwapLatched = isOnKey)
        }
        refreshItemTouchCenters(items)
        eventHandler.repositionItems(listOf(r, rmb))
        persistActiveProfile()
        return true
    }

    private fun DraggableItem.boundKeyCode(): Int? = when (this) {
        is DraggableItem.VariableKey -> keyCode
        is DraggableItem.FixedKey -> keyCode
        is DraggableItem.CancelableKey -> keyCode
        is DraggableItem.WASDGroup -> null
    }

    /** After a swap, move each button's live touch center to follow its new position. */
    private fun refreshItemTouchCenters(items: List<DraggableItem>) {
        val origin = overlayOrigin ?: return
        items.forEach { item ->
            val size = when (item) {
                is DraggableItem.FixedKey -> item.size
                is DraggableItem.VariableKey -> item.size
                is DraggableItem.CancelableKey -> item.size
                else -> 0
            }
            if (size > 0) item.touchCenter = origin + item.position + Offset(size / 2f, size / 2f)
        }
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

    /**
     * 更新背包映射（CancelableKey）的物理按键与触摸模式。
     * 与固定键/通用按钮一致：写入后立即重新分发键位并持久化。
     */
    fun updateCancelableKeyCode(itemId: Int, newKeyCode: Int, touchMode: TouchMode?) {
        val updated = _containerItems.value.map { item ->
            if (item is DraggableItem.CancelableKey && item.id == itemId) {
                item.copy(keyCode = newKeyCode, touchMode = touchMode).withMeasuredPositionFrom(item)
            } else item
        }
        _containerItems.value = updated
        eventHandler.updateKeyMapping(updated)
        persistActiveProfile()
    }

    /**
     * 单个按钮的渲染尺寸（dp）。只改外观，不动按键绑定与位置；
     * `size`（实际测量像素）会在重组后的 onSizeChanged 里刷新，
     * 触点中心由 onGloballyPositioned 重新上报。
     */
    fun updateItemSize(itemId: Int, sizeDp: Float) {
        val clamped = sizeDp.coerceIn(28f, 110f)
        var changed = false
        _containerItems.value.forEach { item ->
            when (item) {
                is DraggableItem.VariableKey -> if (item.id == itemId) {
                    item.customSizeDp = clamped; changed = true
                }
                is DraggableItem.FixedKey -> if (item.id == itemId) {
                    item.customSizeDp = clamped; changed = true
                }
                is DraggableItem.CancelableKey -> if (item.id == itemId) {
                    item.customSizeDp = clamped; changed = true
                }
                is DraggableItem.WASDGroup -> Unit
            }
        }
        if (!changed) return
        // DraggableItem 是可变对象，必须换一个列表实例才能触发重组。
        _containerItems.value = _containerItems.value.toList()
        persistActiveProfile()
    }

    /**
     * 为指定按键添加一个连按落点（最多 3 个）。
     * 默认放在该按键右下 90px 处，随后可在编辑态直接拖动。
     */
    fun addExtraTapPoint(keyCode: Int) {
        val profile = _profiles.value.firstOrNull { it.id == _activeProfileId.value } ?: return
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return
        if (profile.extraTapPoints.count { it.keyCode == keyCode } >= MAX_EXTRA_TAP_POINTS) return
        val anchor = _containerItems.value.firstOrNull { it.boundKeyCode() == keyCode }?.position
            ?: Offset(240f, 520f)
        updateExtraTapPoints(
            profile.extraTapPoints + ExtraTapPoint(keyCode, anchor.x + 90f, anchor.y + 90f)
        )
    }

    /** 拖动结束后更新某个落点的坐标。 */
    fun moveExtraTapPoint(keyCode: Int, index: Int, position: Offset) {
        val profile = _profiles.value.firstOrNull { it.id == _activeProfileId.value } ?: return
        var seen = -1
        val updated = profile.extraTapPoints.map { point ->
            if (point.keyCode != keyCode) return@map point
            seen++
            if (seen == index) point.copy(x = position.x, y = position.y) else point
        }
        updateExtraTapPoints(updated)
    }

    /** 清空某个按键的全部连按落点。 */
    fun clearExtraTapPoints(keyCode: Int) {
        val profile = _profiles.value.firstOrNull { it.id == _activeProfileId.value } ?: return
        updateExtraTapPoints(profile.extraTapPoints.filterNot { it.keyCode == keyCode })
    }

    private fun updateExtraTapPoints(points: List<ExtraTapPoint>) {
        val id = _activeProfileId.value
        _profiles.value = _profiles.value.map {
            if (it.id == id) it.copy(extraTapPoints = points) else it
        }
        persistActiveProfile()
    }

    /* ---------------- 拖拽丢弃（Alt+D） ---------------- */

    /** 全局开关：高级功能里控制 Alt+D 拖拽是否生效。 */
    fun setDiscardDragEnabled(enabled: Boolean) {
        discardDragEnabled.value = enabled
        scope.launch { dataStoreManager.save(DataStoreManager.DISCARD_DRAG_ENABLED, enabled) }
    }

    /** 全局开关：M 键开关地图并切换鼠标（默认开启）。 */
    fun setMapMouseToggleEnabled(enabled: Boolean) {
        mapMouseToggleEnabled.value = enabled
        scope.launch { dataStoreManager.save(DataStoreManager.MAP_MOUSE_TOGGLE_ENABLED, enabled) }
    }

    /** 全局开关：物理 ESC / Tab 关闭界面后自动回射击模式（默认开启）。 */
    fun setTabEscAimEnabled(enabled: Boolean) {
        tabEscAimEnabled.value = enabled
        if (!enabled) aimReturnJob?.cancel()
        scope.launch { dataStoreManager.save(DataStoreManager.TAB_ESC_AIM_ENABLED, enabled) }
    }

    /**
     * 延迟回瞄准：游戏关闭背包/地图需要几帧，立即注入会被丢掉。
     * 110ms 后若仍在射击模式则幂等重建准心触点，否则切入射击模式。
     */
    private fun scheduleAimReturn() {
        aimReturnJob?.cancel()
        // 已经在射击模式时不做任何事，避免按 Tab 看比分、ESC 开菜单时重建触点。
        if (isShootingMode.value) return
        aimReturnJob = scope.launch {
            delay(110L)
            eventHandler.setShootingModeEnabled(true)
            // 界面还在收尾时注入的 DOWN 可能被游戏丢掉，等它彻底关闭后再补一次。
            delay(150L)
            eventHandler.ensureAimContact()
        }
    }

    /**
     * M 键：第一次按下开图并把鼠标切出来（退出射击模式），M 键本身继续走正常映射；
     * 第二次按下点 ESC 映射位置并收回鼠标（进入射击模式），本次 M 被吞掉。
     */
    private fun handleMapMouseToggle(): Boolean {
        if (!mapToggleOpen) {
            mapToggleOpen = true
            eventHandler.setShootingModeEnabled(false)
            return false
        }
        mapToggleOpen = false
        val esc = _containerItems.value.firstOrNull {
            it.boundKeyCode() == KeyEvent.KEYCODE_ESCAPE
        }
        if (esc != null) eventHandler.tapExtraPoint(esc.position)
        eventHandler.setShootingModeEnabled(true)
        return true
    }

    /** 全局设置：Alt+D 拖拽速度倍数（1–10），立即持久化。 */
    fun setDiscardDragSpeed(speed: Float) {
        val safe = speed.coerceIn(MIN_DISCARD_DRAG_SPEED, MAX_DISCARD_DRAG_SPEED)
        discardDragSpeed.value = safe
        scope.launch { dataStoreManager.save(DataStoreManager.DISCARD_DRAG_SPEED, safe) }
    }

    /** 当前预设的丢弃点；未放置返回 null。 */
    fun discardPoint(): Offset? {
        val profile = activeProfile() ?: return null
        val x = profile.discardX ?: return null
        val y = profile.discardY ?: return null
        return Offset(x, y)
    }

    /** 编辑态「丢弃点」入口：放在右侧 90% / 垂直居中，之后可拖动。 */
    fun addDiscardPoint() {
        val viewport = overlayViewport ?: currentScreenSize()
        updateDiscardPoint(Offset(viewport.x * 0.9f, viewport.y * 0.5f))
    }

    fun moveDiscardPoint(position: Offset) = updateDiscardPoint(position)

    private fun updateDiscardPoint(position: Offset) {
        val id = _activeProfileId.value
        _profiles.value = _profiles.value.map {
            if (it.id == id) it.copy(discardX = position.x, discardY = position.y) else it
        }
        persistActiveProfile()
    }

    /**
     * Alt+D：在当前光标位置按住左键 → 拖到丢弃点 → 停顿 → 松开 → 光标回位。
     *
     * 只在光标模式（非射击模式）且真正出现过鼠标输入时生效；按住 Alt 是硬性前提，
     * 避免单按 D 或误触时在游戏里拖拽/转视角。
     */
    private fun startDiscardDrag(): Boolean {
        if (!discardDragEnabled.value || isShootingMode.value || !mouseCursorSeen) return false
        if (_isEditMode.value) return false
        if (discardDragJob?.isActive == true) return false
        val target = discardPoint() ?: return false
        val origin = overlayOrigin ?: Offset.Zero
        val start = _mousePointerOffset.value
        // 速度倍数只作用在移动阶段；按下/停顿时长不变，避免游戏来不及识别“放下”。
        val stepMs = (DRAG_STEP_MS / discardDragSpeed.value.coerceIn(MIN_DISCARD_DRAG_SPEED, MAX_DISCARD_DRAG_SPEED))
            .roundToLong().coerceAtLeast(1L)
        discardDragJob = scope.launch {
            val pointer = eventHandler.allocateDragPointer()
            var released = false
            try {
                eventHandler.beginCursorDrag(pointer, start + origin)
                delay(40)
                repeat(DRAG_STEPS) { index ->
                    val t = (index + 1f) / DRAG_STEPS
                    val p = lerpOffset(start, target, t)
                    moveCursorTo(p)
                    eventHandler.moveCursorDrag(pointer, p + origin)
                    delay(stepMs)
                }
                delay(70)
                eventHandler.endCursorDrag(pointer)
                released = true
                delay(70)
                repeat(DRAG_STEPS) { index ->
                    val t = (index + 1f) / DRAG_STEPS
                    moveCursorTo(lerpOffset(target, start, t))
                    delay(stepMs)
                }
            } finally {
                // 取消/异常时也必须松开，否则鼠标会卡在按下状态。
                if (!released) eventHandler.endCursorDrag(pointer)
            }
        }
        return true
    }

    private fun moveCursorTo(position: Offset) {
        val viewport = overlayViewport ?: currentScreenSize()
        _mousePointerOffset.value = Offset(
            position.x.coerceIn(0f, (viewport.x - 1f).coerceAtLeast(0f)),
            position.y.coerceIn(0f, (viewport.y - 1f).coerceAtLeast(0f))
        )
        eventHandler.mousePointerPosition = _mousePointerOffset.value + (overlayOrigin ?: Offset.Zero)
    }

    private fun lerpOffset(from: Offset, to: Offset, t: Float): Offset =
        Offset(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)

    /** UI 弹出绑定框后调用，清除待绑定信号，避免下次重组又弹一次。 */
    fun updateMeasuredPosition(item: DraggableItem) {        val size = when (item) {
            is DraggableItem.FixedKey -> item.size
            is DraggableItem.VariableKey -> item.size
            else -> 0
        }
        if (size > 0) item.touchCenter?.let {
            overlayOrigin = it - item.position - Offset(size / 2f, size / 2f)
        }
        eventHandler.updateMeasuredPosition(item)
        pullItemIntoViewport(item)
    }

    /**
     * Pull an item back inside the visible overlay once its real size is known.
     *
     * A layout saved on another device, restored into a differently sized window,
     * or dragged past the edge before the drag clamp existed can sit outside the
     * overlay, where it is drawn clipped and can no longer be grabbed. Compose
     * reports a button's size only after it has been measured, which is why this
     * runs from [updateMeasuredPosition] rather than from the drag handler.
     */
    private fun pullItemIntoViewport(item: DraggableItem) {
        val viewport = overlayViewport ?: return
        val size = when (item) {
            is DraggableItem.FixedKey -> item.size
            is DraggableItem.VariableKey -> item.size
            is DraggableItem.CancelableKey -> item.size
            // A WASD group has no cached measurement; the drag clamp covers it.
            is DraggableItem.WASDGroup -> return
        }
        if (size <= 0) return
        val target = clampToViewport(
            item.position,
            Size(size.toFloat(), size.toFloat()),
            _appConfig.value.buttonScale,
            Size(viewport.x, viewport.y),
        )
        if (target == item.position) return
        val origin = overlayOrigin ?: Offset.Zero
        _containerItems.value = _containerItems.value.map { current ->
            if (current.id != item.id) {
                current
            } else {
                current.copy(position = target).also {
                    it.anchorPosition = target
                    it.touchCenter = origin + target + Offset(size / 2f, size / 2f)
                }
            }
        }
        eventHandler.updateKeyMapping(_containerItems.value)
        persistActiveProfile()
        Log.i(TAG, "pulled item ${item.id} back into the viewport: ${item.position} -> $target")
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
        if (screenSizeCacheEnabled.value) {
            cachedScreenSize?.let { return it }
            return computeScreenSize().also { cachedScreenSize = it }
        }
        return computeScreenSize()
    }

    private fun computeScreenSize(): Offset {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            return Offset(bounds.width().toFloat(), bounds.height().toFloat())
        }
        return Offset(displayMetrics.widthPixels.toFloat(), displayMetrics.heightPixels.toFloat())
    }

    private fun nextItemOffset(): Offset {
        // New items must land inside the window that will draw them, not inside
        // the raw display: those differ by the system bars and, before the
        // overlay reports its first frame, by nothing at all.
        val screen = viewportForLayout
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

    /** 悬浮窗透明度：全局设置，立即持久化（旧代码只改 StateFlow，重启即丢）。 */
    fun saveOverlayOpacity(value: Float) {
        overlayOpacity.value = value
        scope.launch { dataStoreManager.save(DataStoreManager.OVERLAY_OPACITY, value) }
    }

    /* ----------------- 按键拟人化（全局设置） ----------------- */

    private fun syncHumanization() {
        eventHandler.setHumanization(
            wasdHumanization.value,
            keyHumanization.value,
            humanizationStrength.value,
            wasdSway.value,
        )
        eventHandler.setHumanizationInterval(humanizationInterval.value)
        eventHandler.setWasdTransition(wasdTransition.value, wasdTransitionMs.value)
    }

    fun setWasdHumanization(enabled: Boolean) {
        wasdHumanization.value = enabled
        syncHumanization()
        scope.launch { dataStoreManager.save(DataStoreManager.WASD_HUMANIZATION, enabled) }
    }

    fun setKeyHumanization(enabled: Boolean) {
        keyHumanization.value = enabled
        syncHumanization()
        scope.launch { dataStoreManager.save(DataStoreManager.KEY_HUMANIZATION, enabled) }
    }

    fun setHumanizationStrength(value: Float) {
        val clamped = value.coerceIn(1f, 100f)
        humanizationStrength.value = clamped
        syncHumanization()
        scope.launch {
            dataStoreManager.save(
                DataStoreManager.HUMANIZATION_STRENGTH_SCALE,
                clamped.roundToInt()            )
        }
    }

    /** WASD 方向晃动幅度（1–100），只影响摇杆的轻微摆头，不影响整体偏移强度。 */
    fun setWasdSway(value: Float) {
        val clamped = value.coerceIn(1f, 100f)
        wasdSway.value = clamped
        syncHumanization()
        scope.launch { dataStoreManager.save(DataStoreManager.WASD_SWAY, clamped.roundToInt()) }
    }

    /** 随机偏移间隔（ms）：默认 1000，约每秒扰动一次。 */
    fun setHumanizationInterval(intervalMs: Long) {
        val clamped = intervalMs.coerceIn(200L, 3000L)
        humanizationInterval.value = clamped
        syncHumanization()
        scope.launch {
            dataStoreManager.save(DataStoreManager.HUMANIZATION_INTERVAL_MS, clamped.toInt())
        }
    }

    /** WASD 换向弧线过渡开关（全局）。 */
    fun setWasdTransition(enabled: Boolean) {
        wasdTransition.value = enabled
        syncHumanization()
        scope.launch { dataStoreManager.save(DataStoreManager.WASD_TRANSITION, enabled) }
    }

    /** WASD 换向过渡时长 40–200ms（全局）。 */
    fun setWasdTransitionMs(ms: Int) {
        val clamped = ms.coerceIn(
            EventHandler.MIN_WASD_TRANSITION_MS,
            EventHandler.MAX_WASD_TRANSITION_MS,
        )
        wasdTransitionMs.value = clamped
        syncHumanization()
        scope.launch { dataStoreManager.save(DataStoreManager.WASD_TRANSITION_MS, clamped) }
    }

    /* ----------------- 鼠标高级参数 ----------------- */

    /** 单帧鼠标位移断点阈值（40–800px），改动立即生效并保存（全局）。 */
    fun setMouseJumpThreshold(px: Int) {
        val clamped = px.coerceIn(40, 800)
        mouseJumpThresholdPx.value = clamped
        scope.launch { dataStoreManager.save(DataStoreManager.MOUSE_JUMP_THRESHOLD_PX, clamped) }
    }

    fun setMouseModeLatch(enabled: Boolean) {
        mouseModeLatch.value = enabled
        if (!enabled) latchedRelativeAxisMode = null
        scope.launch { dataStoreManager.save(DataStoreManager.MOUSE_MODE_LATCH, enabled) }
    }

    fun setScreenSizeCache(enabled: Boolean) {
        screenSizeCacheEnabled.value = enabled
        if (!enabled) cachedScreenSize = null
        scope.launch { dataStoreManager.save(DataStoreManager.SCREEN_SIZE_CACHE, enabled) }
    }

    /** 屏幕旋转/分辨率变化后由服务调用，丢弃缓存的屏幕尺寸。 */
    fun invalidateScreenSizeCache() {
        cachedScreenSize = null
    }

    /** 新手势开始时重置鼠标位移基准与锁存模式。 */
    fun resetMouseRawBaseline() {
        lastMouseRawX = Float.NaN
        lastMouseRawY = Float.NaN
        latchedRelativeAxisMode = null
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
        val remaining = _profiles.value.filterNot { it.id == id }
        _profiles.value = remaining
        scope.launch { dataStoreManager.saveProfiles(remaining) }
        if (_activeProfileId.value == id) {
            switchProfile(remaining.first().id)
        }
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
        return null
    }

    fun exportAllProfilesJson(): String {
        persistActiveProfile()
        return profileJson.encodeToString(ProfileBundle(profiles = _profiles.value))
    }

    fun toggleEditMode() {
        clearActivePointers()
        eventHandler.screenSize = currentScreenSize()
        _isEditMode.value = !_isEditMode.value
        // 进/出编辑态都清空按下状态：编辑态下不维护 pressedKeys，避免切换回来时
        // 残留上一个状态的「按下」高亮。
        _pressedKeys.value = emptySet()
        if (!_isEditMode.value) {
            eventHandler.updateKeyMapping(containerItems.value)
            syncMacros()
            // Position/size edits currently mutate DraggableItem in place, so
            // closing edit mode is the natural commit point for persistence.
            persistActiveProfile()
        }
    }

    /** 一键切换悬浮窗按键的显示/隐藏。 */
    fun toggleKeysVisible() {
        _keysVisible.value = !_keysVisible.value
    }

    /**
     * 暂停 / 恢复映射。
     *
     * 暂停时悬浮窗改为不可触摸并释放指针捕获，系统触控与键盘完全穿透到当前
     * 应用，同时取消所有按下的触点；服务、悬浮窗和配置都保持不变，恢复时
     * 无需重新授权或重新加键。用于临时打字、操作系统界面等场景。
     */
    fun toggleMappingPaused() {
        setMappingPaused(!_mappingPaused.value)
    }

    fun setMappingPaused(paused: Boolean) {
        if (_mappingPaused.value == paused) return
        // 先落暂停标志：窗口监听是 combine(isEditMode, mappingPaused)，先置位
        // 可以避免「退出编辑态」那一帧短暂地重新捕获指针（会吞掉一次点击）。
        _mappingPaused.value = paused
        if (paused) {
            // 暂停意味着「完全不干扰」，编辑态也必须退出，否则全屏窗口仍可触摸。
            if (_isEditMode.value) toggleEditMode()
            // 松开所有已按下的虚拟手指/鼠标键，避免恢复后游戏里还卡着按住状态。
            clearActivePointers()
        } else {
            // 暂停期间可能改过配置（例如通过设置页），恢复时重新同步键位。
            eventHandler.updateKeyMapping(containerItems.value)
        }
    }

    /**
     * 一键切换顶部状态提示条（预设名 · 模式 · 最近输入）的显示/隐藏。
     * 只影响这一行文字，不影响按键容器，也不影响任何注入逻辑。
     */
    fun toggleStatusBarVisible() {
        setStatusBarVisible(!_statusBarVisible.value)
    }

    /** 设置顶部提示条的显隐并持久化，重启服务后保持。 */
    fun setStatusBarVisible(visible: Boolean) {
        _statusBarVisible.value = visible
        scope.launch { dataStoreManager.save(DataStoreManager.STATUS_BAR_VISIBLE, visible) }
    }

    fun onKeyEvent(keyEvent: KeyEvent): Boolean {
        mouseKeyAlias(keyEvent.keyCode, keyEvent.source)?.let { button ->
            if (keyEvent.action != KeyEvent.ACTION_DOWN && keyEvent.action != KeyEvent.ACTION_UP) return true
            val pressed = keyEvent.action == KeyEvent.ACTION_DOWN
            if (_isEditMode.value) {
                if (pressed && keyEvent.repeatCount == 0) mouseButtonKeyCode(button)?.let { keyCaptureListener?.invoke(it) }
            } else {
                mouseButtons.update(if (pressed) MotionEvent.ACTION_BUTTON_PRESS else MotionEvent.ACTION_BUTTON_RELEASE,
                    if (pressed) button else 0, button).forEach { (changed, down) -> handleMouseEdge(changed, down) }
            }
            return true
        }
        if (_isEditMode.value) {
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
        // Tab 防连按：必须在更新任何 UI 状态和映射之前拦截，
        // 否则被吞掉的 Tab 仍会点亮按钮、刷新最近输入。
        if (guardTabRepeat(keyEvent)) return true
        // Alt+D：按住 Alt 才生效，工具开关打开且当前是光标模式时执行拖拽丢弃。
        if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0 &&
            keyEvent.keyCode == KeyEvent.KEYCODE_D &&
            keyEvent.isAltPressed && startDiscardDrag()
        ) return true
        // M 键开图并切光标：第一下透传 M 并切出光标，第二下改点 ESC 并收回光标。
        if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0 &&
            keyEvent.keyCode == KeyEvent.KEYCODE_M && mapMouseToggleEnabled.value
        ) {
            if (handleMapMouseToggle()) return true
        }
        // 光标模式（鼠标已唤起）下按 F：在光标位置快速点两下，方便拾取/打开物品。
        if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0 &&
            keyEvent.keyCode == KeyEvent.KEYCODE_F &&
            !isShootingMode.value && mouseCursorSeen
        ) {
            eventHandler.doubleTapExtraPoint(eventHandler.mousePointerPosition)
            return true
        }
        // 手动切模式（`）时取消待执行的回瞄准，避免把用户刚切出的光标又收回。
        if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0 &&
            keyEvent.keyCode == eventHandler.shootingModeKeyCode
        ) {
            aimReturnJob?.cancel()
        }
        // 物理 ESC / Tab：关闭背包、地图后延迟回瞄准；不吞按键，游戏照常收到。
        if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0 &&
            tabEscAimEnabled.value &&
            (keyEvent.keyCode == KeyEvent.KEYCODE_ESCAPE || keyEvent.keyCode == KeyEvent.KEYCODE_TAB)
        ) {
            scheduleAimReturn()
        }
        _lastInputLabel.value = keyEvent.keyCode.keyCodeToString() +
            if (keyEvent.action == KeyEvent.ACTION_DOWN) " 按下" else " 松开"
        // 非编辑态：同步物理按键的按下/抬起状态，供悬浮窗按钮显示「按下」高亮。
        when (keyEvent.action) {
            KeyEvent.ACTION_DOWN -> _pressedKeys.value = _pressedKeys.value + keyEvent.keyCode
            KeyEvent.ACTION_UP -> _pressedKeys.value = _pressedKeys.value - keyEvent.keyCode
        }
        // Swap pair: on key press, swap the pair's layout positions; the same
        // key still flows to the game mapping untouched.
        if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
            // 蹲/趴与 Shift 的协调：只记录/补点姿态，原按键照常透传。
            trackStanceAssist(keyEvent.keyCode)
        }
        // 快速切枪宏：默认 Alt+9，命中时吞掉触发键，由宏自己注入原始触点。
        if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0 &&
            runKeyMacro(keyEvent)
        ) {
            return true
        }
        if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0 &&
            !eventHandler.isMacroTrigger(keyEvent.keyCode)) {
            // 连按落点：本体照常映射，第 2/3/4 次快速按下时额外点击预设位置。
            handleExtraTap(keyEvent.keyCode)
            // 换位优先：1/2 / X 命中时先生效，按键本身仍继续透传给游戏。
            val swapped = applyQuickSwap(keyEvent.keyCode)
            // 预设级按 3 切近战负责整段注入，命中时吞掉原始 3，避免再点一次。
            if (!swapped && runTapSequence234(keyEvent.keyCode)) return true
        }
        return eventHandler.handleKeyEvent(keyEvent)
    }

    fun onMouseEvent(motionEvent: MotionEvent): Boolean {
        // 看到任何鼠标事件就认为光标模式可用；Alt+D 不允许在从未出现光标时盲拖。
        mouseCursorSeen = true
        if (isEditMode.value) {
            val listener = keyCaptureListener ?: return false
            if (motionEvent.actionMasked == MotionEvent.ACTION_SCROLL) {
                scrollTracker.update(motionEvent.getAxisValue(MotionEvent.AXIS_VSCROLL),
                    motionEvent.getAxisValue(MotionEvent.AXIS_HSCROLL)).firstOrNull()?.let(listener)
                return true
            }
            // Leave left/right clicks to Compose; dropdown also supports those bindings.
            val bindable = MotionEvent.BUTTON_TERTIARY or MotionEvent.BUTTON_BACK or MotionEvent.BUTTON_FORWARD
            val button = modifiedMouseButton(motionEvent) and bindable
            if (motionEvent.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
                motionEvent.actionMasked == MotionEvent.ACTION_DOWN) {
                mouseButtonKeyCode(button)?.let { listener(it); return true }
            }
            return false
        }
        val screen = currentScreenSize()
        eventHandler.screenSize = screen
        // 新的一段输入开始时清掉上一段的绝对坐标基准：换鼠标、光标被系统回中、
        // 悬浮窗重新捕获指针后，rawX/rawY 会从一个完全不同的位置开始，
        // 继续和旧值相减会得到几百像素的假位移，视角因此瞬间甩到背后。
        if (motionEvent.deviceId != lastMouseDeviceId) {
            lastMouseDeviceId = motionEvent.deviceId
            resetMouseRawBaseline()
        }
        when (motionEvent.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_OUTSIDE,
            MotionEvent.ACTION_HOVER_EXIT -> resetMouseRawBaseline()
        }
        eventHandler.mousePointerPosition = (pointerOffset.value + (overlayOrigin ?: Offset.Zero)).let {
            Offset(it.x.coerceIn(0f, screen.x - 1f), it.y.coerceIn(0f, screen.y - 1f))
        }
        mouseButtons.update(motionEvent.actionMasked, motionEvent.buttonState, motionEvent.actionButton)
            .forEach { (button, pressed) -> handleMouseEdge(button, pressed) }
        when (motionEvent.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                // Captured mouse X/Y already contain deltas. Never differentiate them.
                val relativeMouse = motionEvent.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)
                val axisX = if (relativeMouse) 0f else motionEvent.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                val axisY = if (relativeMouse) 0f else motionEvent.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                val rawRelativeAxes = !relativeMouse &&
                    (motionEvent.isFromSource(InputDevice.SOURCE_TOUCHPAD) || axisX != 0f || axisY != 0f)
                // 有的设备只在部分事件里填相对轴。逐事件判断会让同一段移动在
                // 「相对位移」和「绝对坐标差」之间来回切换，表现为速度忽快忽慢。
                // 锁存后整段手势固定一种解析方式，直到新手势/换设备。
                val relativeAxes = if (mouseModeLatch.value) {
                    latchedRelativeAxisMode
                        ?: rawRelativeAxes.also { latchedRelativeAxisMode = it }
                } else rawRelativeAxes
                val maxDelta = mouseJumpThresholdPx.value.toFloat()
                val dx: Float
                val dy: Float
                if (relativeMouse) {
                    dx = motionEvent.x
                    dy = motionEvent.y
                    lastMouseRawX = Float.NaN
                    lastMouseRawY = Float.NaN
                } else {
                    dx = if (relativeAxes) axisX else if (lastMouseRawX.isNaN()) 0f else motionEvent.rawX - lastMouseRawX
                    dy = if (relativeAxes) axisY else if (lastMouseRawY.isNaN()) 0f else motionEvent.rawY - lastMouseRawY
                    lastMouseRawX = motionEvent.rawX
                    lastMouseRawY = motionEvent.rawY
                }

                val rawOffset = Offset(dx, dy)
                if (eventHandler.isWheelActive) return eventHandler.handlePointerMove(rawOffset)
                // 绝对坐标回退路径在光标回中、换设备、窗口重捕获或投屏端重新按下时
                // 会跳几百像素；超过阈值说明是断点而不是人手一帧的真实位移，
                // 丢掉这一帧并重建基准，避免视角被瞬间甩到背后。
                if (!relativeMouse && !relativeAxes &&
                    (abs(dx) > maxDelta || abs(dy) > maxDelta)
                ) {
                    lastMouseRawX = motionEvent.rawX
                    lastMouseRawY = motionEvent.rawY
                    return true
                }
                if (isShootingMode.value) {
                    val clamped = Offset(
                        dx.coerceIn(-maxDelta, maxDelta),
                        dy.coerceIn(-maxDelta, maxDelta)
                    )
                    // 射击模式：灵敏度作用在瞄准转视角上。
                    return eventHandler.handlePointerMove(clamped * sensitivity)
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
                // Existing radial-wheel selection owns scrolling while it is held open.
                if (eventHandler.isWheelActive) { scrollTracker.reset(); return eventHandler.handleScroll(vScroll) }
                val codes = scrollTracker.update(vScroll, motionEvent.getAxisValue(MotionEvent.AXIS_HSCROLL))
                codes.forEach { code ->
                    _lastInputLabel.value = code.keyCodeToString()
                    if (!eventHandler.isMacroTrigger(code)) applyQuickSwap(code)
                    eventHandler.handleScrollBinding(code)
                }
                return true
            }
        }
        return true
    }

    private fun handleMouseEdge(button: Int, pressed: Boolean) {
        mouseButtonKeyCode(button)?.let { key ->
            _pressedKeys.value = if (pressed) _pressedKeys.value + key else _pressedKeys.value - key
            _lastInputLabel.value = key.keyCodeToString() + if (pressed) " 按下" else " 松开"
        }
        eventHandler.handleMouseButton(button, pressed)
    }

    fun clearActivePointers() {
        mouseButtons.reset()
        scrollTracker.reset()
        _pressedKeys.value = emptySet()
        lastMouseRawX = Float.NaN
        lastMouseRawY = Float.NaN
        eventHandler.clear()
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
        _isEditMode.value = false
        // Drag operations mutate the runtime item objects directly. Persist
        // once more before the overlay disappears so a service stop does not
        // lose the last positioning edit.
        persistActiveProfile()
        clearActivePointers()
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
