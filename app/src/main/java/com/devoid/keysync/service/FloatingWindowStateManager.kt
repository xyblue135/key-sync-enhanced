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
import com.devoid.keysync.model.AreaPoint
import com.devoid.keysync.model.DraggableItem
import com.devoid.keysync.model.DraggableItemType
import com.devoid.keysync.domain.AimRecenterMode
import com.devoid.keysync.domain.MouseButtonTracker
import com.devoid.keysync.domain.ScrollTracker
import com.devoid.keysync.domain.mouseButtonKeyCode
import com.devoid.keysync.domain.mouseKeyAlias
import com.devoid.keysync.model.ClickMacro
import com.devoid.keysync.model.ProfileAssist
import com.devoid.keysync.model.DefaultPlayModes
import com.devoid.keysync.model.PlayMode
import com.devoid.keysync.model.SimplePlayMode
import com.devoid.keysync.model.validateClickMacros
import com.devoid.keysync.model.withAssist
import com.devoid.keysync.model.SeqAction
import com.devoid.keysync.util.keyCodeToString
import com.devoid.keysync.domain.EventHandler
import com.devoid.keysync.domain.InputStats
import com.devoid.keysync.domain.TrajectoryRecorder
import com.devoid.keysync.domain.KEYCODE_LMC
import com.devoid.keysync.domain.KEYCODE_MMC
import com.devoid.keysync.domain.KEYCODE_RMC
import com.devoid.keysync.domain.KEYCODE_SCROLL_DOWN
import com.devoid.keysync.domain.KEYCODE_SCROLL_UP
import com.devoid.keysync.data.external.ShizukuSystemServerAPi
import com.devoid.keysync.model.Profile
import com.devoid.keysync.model.ExtraTapPoint
import com.devoid.keysync.model.EXTRA_TAP_WINDOW_MS
import com.devoid.keysync.model.MAX_EXTRA_TAP_POINTS
import com.devoid.keysync.model.MAX_SHOOTING_AREA_POINTS
import com.devoid.keysync.model.clampToViewport
import com.devoid.keysync.model.layoutSizeForClamp
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** 光标渲染刷新率（Hz），默认 60；只影响显示，鼠标逻辑仍全速更新。 */
    val cursorRenderHz = MutableStateFlow(DEFAULT_CURSOR_RENDER_HZ)

    // 按键拟人化（随机偏移）：全局生效、对所有预设统一，不随预设存储。
    val wasdHumanization = MutableStateFlow(true)
    val keyHumanization = MutableStateFlow(false)
    /** 偏移强度 1–100（100 = 最大）。 */
    val humanizationStrength = MutableStateFlow(50f)

    /** WASD 方向晃动幅度 1–100，独立于整体强度。 */
    val wasdSway = MutableStateFlow(50f)
    /** 随机偏移间隔（ms），默认 1000（约每秒一次）。 */
    val humanizationInterval = MutableStateFlow(1000L)

    /** WASD 专用随机间隔：默认开启，200–300ms 内均匀采样。 */
    val wasdJitterRandomEnabled = MutableStateFlow(true)
    val wasdJitterMinMs = MutableStateFlow(200)
    val wasdJitterMaxMs = MutableStateFlow(300)

    /** WASD 换向弧线过渡：默认开启，80ms；全局设置。 */
    val wasdTransition = MutableStateFlow(true)
    val wasdTransitionMs = MutableStateFlow(EventHandler.DEFAULT_WASD_TRANSITION_MS)

    // 拟人化拆分：过渡段只留极小毛刺，明显抖动放到到位驻留阶段。
    val wasdTransitTremor = MutableStateFlow(EventHandler.DEFAULT_WASD_TRANSIT_TREMOR_PX)
    val wasdHoldTremor = MutableStateFlow(EventHandler.DEFAULT_WASD_HOLD_TREMOR_PX)
    val wasdHoldSettleMs = MutableStateFlow(EventHandler.DEFAULT_WASD_HOLD_SETTLE_MS)
    /** 回中落点的随机圆半径（px）。 */
    val wasdCenterJitterPx = MutableStateFlow(EventHandler.DEFAULT_WASD_CENTER_JITTER_PX.toInt())

    /** 鼠标回中节流（ms）；没有按 WASD 时内部会自动降到 16ms。 */
    val aimRecenterIntervalMs = MutableStateFlow(50)

    /** 开火时视角与鼠标活动范围解耦，使用整屏行程，可持续 360°。 */
    val fireFreeAim = MutableStateFlow(true)

    /* ----------------- 鼠标高级参数（全局，与预设无关） ----------------- */

    /** 单帧位移超过该值就视为光标跳变并丢弃；默认 240px。 */
    val mouseJumpThresholdPx = MutableStateFlow(DEFAULT_MOUSE_JUMP_THRESHOLD_PX)
    /** 为 true 时，一段操作内固定相对轴/绝对坐标解析方式，避免模式抖动。 */
    val mouseModeLatch = MutableStateFlow(true)
    /** 为 true 时缓存屏幕尺寸，避免每个鼠标事件都查询 WindowManager。 */
    val screenSizeCacheEnabled = MutableStateFlow(true)
    /** 「防止大幅甩动导致视角问题」总开关；关闭后上面三项都不生效。 */
    val mouseGuardEnabled = MutableStateFlow(true)

    // 悬浮窗按键的显示/隐藏开关（一键切换，不影响功能）。
    private val _keysVisible = MutableStateFlow(true)
    val keysVisible = _keysVisible.asStateFlow()

    // 顶部状态提示条（预设名 · 模式 · 最近输入）的显隐。
    // 与 keysVisible 不同，这个开关是持久化的：提示条只是信息展示，
    // 用户关掉它通常是想一直关着，而不是每次重启服务都要再关一次。
    private val _statusBarVisible = MutableStateFlow(true)
    val statusBarVisible = _statusBarVisible.asStateFlow()

    private val _appConfig = MutableStateFlow(AppConfig.Default)
    /** 当前预设的全局触摸模式：编辑态据此给按钮上色，便于看整体布局。 */
    val normalTouchMode = MutableStateFlow(TouchMode.TAP)
    val cancelableTouchMode = MutableStateFlow(TouchMode.TAP)
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

    /** 进入编辑态时的布局快照：点「完成」确认后才提交，取消则回滚到这里。 */
    private data class EditSnapshot(
        val profiles: List<Profile>,
        val containerItems: List<DraggableItem>,
    )
    private var editSnapshot: EditSnapshot? = null

    // 暂停映射：不拦截触控、不注入任何事件，用于临时打字/操作其他界面。
    // 只存在于本次服务生命周期内，不持久化——重启服务后默认恢复映射。
    private val _mappingPaused = MutableStateFlow(false)
    val mappingPaused = _mappingPaused.asStateFlow()

    private val _mousePointerOffset =
        MutableStateFlow(Offset(displayMetrics.widthPixels / 2f, displayMetrics.heightPixels / 2f))
    val pointerOffset = _mousePointerOffset.asStateFlow()

    /** 供合成层取初始值，避免直接在组合里读 StateFlow.value 触发 lint。 */
    fun currentPointerOffset(): Offset = _mousePointerOffset.value

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
        _overlayOriginFlow.value = origin
        _reportedViewport.value = viewport
        _shootingAnchorFlow.value = shootingModeAnchor()
        if (mouseAreaHalf.value > 0f) {
            recomputeMouseAreaCenters()
            syncMouseAreas()
        }
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
    /** Alt+D 拖拽速度倍数（1–100）：值越大移动越快，默认 25。 */
    val discardDragSpeed = MutableStateFlow(DEFAULT_DISCARD_DRAG_SPEED)
    /** Alt+D 拖拽开始前先按住鼠标的时长（ms），默认 200。 */
    val discardPressHoldMs = MutableStateFlow(DEFAULT_DISCARD_PRESS_HOLD_MS)

    /* -------- 鼠标双范围（全局）：位置相对 ` 按钮，主按钮移动时整体跟随（屏幕坐标） -------- */
    private val mouseAreaOffset = MutableStateFlow(Offset.Zero)
    val mouseAreaHalf = MutableStateFlow(0f)
    private val landingAreaOffset = MutableStateFlow(Offset.Zero)
    val landingAreaHalf = MutableStateFlow(0f)
    /** 派生的绝对中心，供事件处理器与悬浮层使用。 */
    val mouseAreaCenter = MutableStateFlow(Offset.Zero)
    val landingAreaCenter = MutableStateFlow(Offset.Zero)
    /** 射击模式空闲重锚阈值（ms）：停手超过该时间后，下次移动前重新落指；0 = 关闭。 */
    val aimIdleReanchorMs = MutableStateFlow(DEFAULT_AIM_IDLE_REANCHOR_MS)
    /** 瞄准回中预设（经典 / 即回中 / 缓冲补发），随时切换，互不干扰。 */
    val aimRecenterMode = MutableStateFlow(AimRecenterMode.INSTANT)
    private var mouseAreaCustomized = false
    /** 悬浮窗原点（屏幕坐标），供高级层把双范围从屏幕坐标换算到本地坐标。 */
    private val _overlayOriginFlow = MutableStateFlow(Offset.Zero)
    val overlayOriginFlow = _overlayOriginFlow.asStateFlow()
    /** ` 按钮中心（屏幕坐标），` 移动后更新，供齿轮设置显示相对偏移。 */
    private val _shootingAnchorFlow = MutableStateFlow<Offset?>(null)
    val shootingAnchorFlow = _shootingAnchorFlow.asStateFlow()
    private var discardDragJob: Job? = null
    /** M 键开关地图并切换鼠标（全局），默认开启。 */
    val mapMouseToggleEnabled = MutableStateFlow(true)
    /** F 滚轮操控：触发「向上滑」的物理键，默认鼠标滚轮向上。 */
    val fScrollUpKeyCode = MutableStateFlow(KEYCODE_SCROLL_UP)
    /** F 滚轮操控：触发「向下滑」的物理键，默认鼠标滚轮向下。 */
    val fScrollDownKeyCode = MutableStateFlow(KEYCODE_SCROLL_DOWN)
    /** F 交互后左键唤出光标（全局，高级功能），默认开启。 */
    val fCursorClickEnabled = MutableStateFlow(false)
    /** F 交互后允许左键唤出光标的窗口（ms），默认 1000。 */
    val fCursorClickWindowMs = MutableStateFlow(DEFAULT_F_CURSOR_CLICK_WINDOW_MS)
    /** F 交互的时间戳；左键在窗口内命中后立即清空，保证一轮只触发一次。 */
    private var fInteractArmedAt = 0L
    /** M 键当前是否处于「地图已打开」状态；重启服务后归零。 */
    private var mapToggleOpen = false
    /** ESC / Tab 关闭界面后自动回瞄准（全局），默认开启。 */
    val tabEscAimEnabled = MutableStateFlow(true)
    /** 回瞄准延迟（ms），默认 110。 */
    val tabEscAimDelayMs = MutableStateFlow(DEFAULT_TAB_ESC_AIM_DELAY_MS)
    /** 按键松开后批量点击落点（全局），默认关闭；本体映射不受影响。 */
    val escAfterTapEnabled = MutableStateFlow(false)
    /** 松开后首次点击延迟（ms），默认 100。 */
    val escAfterTapDelayMs = MutableStateFlow(DEFAULT_ESC_AFTER_TAP_DELAY_MS)
    /** 多个落点之间的间隔（ms），默认 50。 */
    val escAfterTapIntervalMs = MutableStateFlow(DEFAULT_ESC_AFTER_TAP_INTERVAL_MS)
    /** ` 键手绘区域草稿；null = 未在绘制。 */
    val shootingAreaDraft = MutableStateFlow<List<AreaPoint>?>(null)
    /** 回瞄准延迟任务：等游戏把界面关掉再重建瞄准触点。 */
    private var aimReturnJob: Job? = null

    /** F 光标滚轮操控（全局），默认开启；只在 F 唤出光标后临时接管滚轮。 */
    val fScrollControlEnabled = MutableStateFlow(true)
    /** 每格滚轮的短滑距离（px）。 */
    val fScrollSwipePx = MutableStateFlow(DEFAULT_SCROLL_SWIPE_PX)
    /** 滚轮方向反转：默认关闭（滚轮向下 = 手指向下滑）。 */
    val fScrollInvert = MutableStateFlow(false)
    /** 每小步延迟（ms），默认 7。 */
    val fScrollStepDelayMs = MutableStateFlow(7)
    /** 当前是否处于 F 唤出光标后的滚轮操控状态；ESC/Tab/切回射击模式即退出。 */
    private var fScrollControlActive = false
    /** 连续滚轮逐格排队执行，避免多根手指同时滑动。 */
    private val scrollSwipeMutex = Mutex()

    /** 玩法模式：0 = 简易，1 = 高级 A，2 = 高级 B。 */
    val playModes = MutableStateFlow(DefaultPlayModes)
    val currentPlayModeIndex = MutableStateFlow(1)
    /** 运行时高级层预览（仅编辑态使用，运行时不再显示）。 */
    private val _advancedVisible = MutableStateFlow(false)
    val advancedVisible = _advancedVisible.asStateFlow()

    val activePlayMode: PlayMode
        get() = playModes.value.getOrElse(currentPlayModeIndex.value) { DefaultPlayModes[1] }
    val isSimpleMode: Boolean
        get() = currentPlayModeIndex.value == 0

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
            cursorRenderHz.value =
                (dataStoreManager.getInt(DataStoreManager.CURSOR_RENDER_HZ).first()
                    ?: DEFAULT_CURSOR_RENDER_HZ).coerceIn(MIN_CURSOR_RENDER_HZ, MAX_CURSOR_RENDER_HZ)
            dataStoreManager.getFloat(DataStoreManager.POINTER_SENSITIVITY).first()?.let {
                pointerSensitivity.value = it
            }
            // 读取全局按键拟人化设置并立即应用到事件处理器。
            wasdHumanization.value =
                dataStoreManager.getBoolean(DataStoreManager.WASD_HUMANIZATION).first() ?: true
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
            wasdJitterRandomEnabled.value =
                dataStoreManager.getBoolean(DataStoreManager.WASD_JITTER_RANDOM_ENABLED).first() ?: true
            wasdJitterMinMs.value =
                (dataStoreManager.getInt(DataStoreManager.WASD_JITTER_MIN_MS).first() ?: 200)
                    .coerceIn(50, 5000)
            wasdJitterMaxMs.value =
                (dataStoreManager.getInt(DataStoreManager.WASD_JITTER_MAX_MS).first() ?: 300)
                    .coerceIn(wasdJitterMinMs.value, 5000)
            wasdSway.value =
                (dataStoreManager.getInt(DataStoreManager.WASD_SWAY).first() ?: 50)
                    .coerceIn(1, 100).toFloat()
            wasdTransitTremor.value =
                (dataStoreManager.getInt(DataStoreManager.WASD_TRANSIT_TREMOR).first() ?: 4)
                    .coerceIn(0, 20) / 10f
            wasdHoldTremor.value =
                (dataStoreManager.getInt(DataStoreManager.WASD_HOLD_TREMOR).first() ?: 8)
                    .coerceIn(0, 40) / 10f
            wasdHoldSettleMs.value =
                (dataStoreManager.getInt(DataStoreManager.WASD_HOLD_SETTLE_MS).first() ?: 150)
                    .coerceIn(0, 800)
            wasdCenterJitterPx.value =
                (dataStoreManager.getInt(DataStoreManager.WASD_CENTER_JITTER_PX).first() ?: 8)
                    .coerceIn(0, 40)
            aimRecenterIntervalMs.value =
                (dataStoreManager.getInt(DataStoreManager.AIM_RECENTER_INTERVAL_MS).first() ?: 50)
                    .coerceIn(0, 120)
            fireFreeAim.value =
                dataStoreManager.getBoolean(DataStoreManager.FIRE_FREE_AIM).first() ?: true
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
            mouseGuardEnabled.value =
                dataStoreManager.getBoolean(DataStoreManager.MOUSE_GUARD_ENABLED).first() ?: true
            discardDragEnabled.value =
                dataStoreManager.getBoolean(DataStoreManager.DISCARD_DRAG_ENABLED).first() ?: false
            val storedDragSpeed = dataStoreManager.getFloat(DataStoreManager.DISCARD_DRAG_SPEED).first()
            // 旧刻度是 1–10，新刻度扩大到 1–100：旧值直接 ×10 迁移。
            var migratedDragSpeed = storedDragSpeed?.let {
                if (it <= LEGACY_MAX_DISCARD_DRAG_SPEED) it * 10f else it
            }
            // 默认速度整体降为原来的一半：只对仍是旧默认 50 的用户做一次性 ÷2，避免影响已自定义的值。
            val speedHalved = dataStoreManager.getBoolean(DataStoreManager.DISCARD_SPEED_HALVED).first() ?: false
            if (!speedHalved && migratedDragSpeed == 50f) migratedDragSpeed = 25f
            discardDragSpeed.value =
                (migratedDragSpeed ?: DEFAULT_DISCARD_DRAG_SPEED)
                    .coerceIn(MIN_DISCARD_DRAG_SPEED, MAX_DISCARD_DRAG_SPEED)
            if (!speedHalved) {
                scope.launch {
                    dataStoreManager.save(DataStoreManager.DISCARD_SPEED_HALVED, true)
                    dataStoreManager.save(DataStoreManager.DISCARD_DRAG_SPEED, discardDragSpeed.value)
                }
            } else if (migratedDragSpeed != null && migratedDragSpeed != storedDragSpeed) {
                scope.launch {
                    dataStoreManager.save(DataStoreManager.DISCARD_DRAG_SPEED, discardDragSpeed.value)
                }
            }
            discardPressHoldMs.value =
                (dataStoreManager.getInt(DataStoreManager.DISCARD_PRESS_HOLD_MS).first()
                    ?: DEFAULT_DISCARD_PRESS_HOLD_MS)
                    .coerceIn(MIN_DISCARD_PRESS_HOLD_MS, MAX_DISCARD_PRESS_HOLD_MS)
            mapMouseToggleEnabled.value =
                dataStoreManager.getBoolean(DataStoreManager.MAP_MOUSE_TOGGLE_ENABLED).first() ?: true
            fCursorClickEnabled.value =
                dataStoreManager.getBoolean(DataStoreManager.F_CURSOR_CLICK_ENABLED).first() ?: true
            tabEscAimEnabled.value =
                dataStoreManager.getBoolean(DataStoreManager.TAB_ESC_AIM_ENABLED).first() ?: true
            fScrollControlEnabled.value =
                dataStoreManager.getBoolean(DataStoreManager.F_SCROLL_CONTROL_ENABLED).first() ?: true
            fScrollSwipePx.value =
                (dataStoreManager.getFloat(DataStoreManager.F_SCROLL_SWIPE_PX).first()
                    ?: DEFAULT_SCROLL_SWIPE_PX)
                    .coerceIn(MIN_SCROLL_SWIPE_PX, MAX_SCROLL_SWIPE_PX)
            fScrollInvert.value =
                dataStoreManager.getBoolean(DataStoreManager.F_SCROLL_INVERT).first() ?: false
            fScrollStepDelayMs.value =
                (dataStoreManager.getInt(DataStoreManager.F_SCROLL_STEP_DELAY_MS).first()
                    ?: 7).coerceIn(2, 40)
            fCursorClickWindowMs.value =
                (dataStoreManager.getInt(DataStoreManager.F_CURSOR_CLICK_WINDOW_MS).first()
                    ?: DEFAULT_F_CURSOR_CLICK_WINDOW_MS).coerceIn(100, 3000)
            tabEscAimDelayMs.value =
                (dataStoreManager.getInt(DataStoreManager.TAB_ESC_AIM_DELAY_MS).first()
                    ?: DEFAULT_TAB_ESC_AIM_DELAY_MS).coerceIn(50, 1000)
            escAfterTapEnabled.value =
                dataStoreManager.getBoolean(DataStoreManager.ESC_AFTER_TAP_ENABLED).first() ?: false
            escAfterTapDelayMs.value =
                (dataStoreManager.getInt(DataStoreManager.ESC_AFTER_TAP_DELAY_MS).first()
                    ?: DEFAULT_ESC_AFTER_TAP_DELAY_MS).coerceIn(0, 1000)
            escAfterTapIntervalMs.value =
                (dataStoreManager.getInt(DataStoreManager.ESC_AFTER_TAP_INTERVAL_MS).first()
                    ?: DEFAULT_ESC_AFTER_TAP_INTERVAL_MS).coerceIn(0, 500)
            aimIdleReanchorMs.value =
                (dataStoreManager.getInt(DataStoreManager.AIM_IDLE_REANCHOR_MS).first()
                    ?: DEFAULT_AIM_IDLE_REANCHOR_MS).coerceIn(MIN_AIM_IDLE_REANCHOR_MS, MAX_AIM_IDLE_REANCHOR_MS)
            eventHandler.aimIdleReanchorMs = aimIdleReanchorMs.value.toLong()
            aimRecenterMode.value = dataStoreManager.getString(DataStoreManager.AIM_RECENTER_MODE).first()
                ?.let { name -> runCatching { AimRecenterMode.valueOf(name) }.getOrNull() }
                ?: AimRecenterMode.INSTANT
            eventHandler.aimRecenterMode = aimRecenterMode.value
            fScrollUpKeyCode.value =
                dataStoreManager.getInt(DataStoreManager.F_SCROLL_UP_KEY_CODE).first()
                    ?: KEYCODE_SCROLL_UP
            fScrollDownKeyCode.value =
                dataStoreManager.getInt(DataStoreManager.F_SCROLL_DOWN_KEY_CODE).first()
                    ?: KEYCODE_SCROLL_DOWN
            // 鼠标双范围：位置保存为相对 ` 按钮的偏移，主按钮移动后整体跟随。
            val areaOx = dataStoreManager.getFloat(DataStoreManager.MOUSE_AREA_OFFSET_X).first()
            val areaOy = dataStoreManager.getFloat(DataStoreManager.MOUSE_AREA_OFFSET_Y).first()
            val areaHalf = dataStoreManager.getFloat(DataStoreManager.MOUSE_AREA_HALF).first()
            if (areaOx != null && areaOy != null && areaHalf != null && areaHalf > 0f) {
                mouseAreaOffset.value = Offset(areaOx, areaOy)
                mouseAreaHalf.value = areaHalf
                mouseAreaCustomized = true
            }
            val landOx = dataStoreManager.getFloat(DataStoreManager.LANDING_AREA_OFFSET_X).first()
            val landOy = dataStoreManager.getFloat(DataStoreManager.LANDING_AREA_OFFSET_Y).first()
            val landHalf = dataStoreManager.getFloat(DataStoreManager.LANDING_AREA_HALF).first()
            if (landOx != null && landOy != null && landHalf != null && landHalf > 0f) {
                landingAreaOffset.value = Offset(landOx, landOy)
                landingAreaHalf.value = landHalf
            }
            recomputeMouseAreaCenters()
            clampMouseAreasToScreen()
            syncMouseAreas()
            val loadedModes = runCatching {
                Json { ignoreUnknownKeys = true }
                    .decodeFromString<List<PlayMode>>(
                        dataStoreManager.getString(DataStoreManager.PLAY_MODES).first() ?: ""
                    )
            }.getOrElse { DefaultPlayModes }
            // 旧版本固定三槽（简易 / 高级 A / 高级 B）迁移为「简易 + 方案1」，避免老用户看到旧命名。
            val migratedModes =
                if (loadedModes.size == 3 && loadedModes[0].name == "简易" &&
                    loadedModes[1].name == "高级 A" && loadedModes[2].name == "高级 B"
                ) {
                    listOf(SimplePlayMode, loadedModes[1].copy(name = "方案1"))
                } else {
                    loadedModes
                }
            playModes.value =
                if (migratedModes.size >= 2) migratedModes else DefaultPlayModes
            if (playModes.value != loadedModes) {
                scope.launch {
                    dataStoreManager.save(
                        DataStoreManager.PLAY_MODES,
                        Json { ignoreUnknownKeys = true }.encodeToString(playModes.value)
                    )
                }
            }
            currentPlayModeIndex.value =
                (dataStoreManager.getInt(DataStoreManager.CURRENT_PLAY_MODE).first() ?: 1)
                    .coerceIn(0, playModes.value.lastIndex)
            applyPlayMode()
            // 切回射击模式（ESC/Tab/手动模式键）时立即解除滚轮操控。
            scope.launch {
                isShootingMode.collect { shooting ->
                    if (shooting) {
                        fScrollControlActive = false
                        // 用户手动/自动回到射击模式后，下一次 M 应重新开图而不是直接关图。
                        mapToggleOpen = false
                    }
                }
            }
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
            val temporary = Profile(id = UUID.randomUUID().toString(), name = "临时预设（不会保存）")
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

        // 一次性迁移：把旧版全局 POINTER_SENSITIVITY 值并入第一个预设，
        // 之后删除全局键，避免再次覆盖用户手动设回默认值的操作。
        migrateLegacySensitivity(stored).let { migrated ->
            if (migrated != null) {
                stored = migrated
                dataStoreManager.saveProfiles(stored)
                dataStoreManager.remove(DataStoreManager.POINTER_SENSITIVITY)
            }
        }

        // 一次性迁移：快速近战的默认键从 V 改为右侧小键盘 num0。
        // 只在从未迁移过的旧数据上执行，用户之后手动改回 V 不会再被覆盖。
        if (dataStoreManager.getBoolean(DataStoreManager.QUICK_MELEE_NUMPAD_MIGRATED).first() != true) {
            var meleeChanged = false
            stored = stored.map { profile ->
                if (profile.quickMeleeMacro.meleeKeyCode == KeyEvent.KEYCODE_V) {
                    meleeChanged = true
                    profile.copy(
                        quickMeleeMacro = profile.quickMeleeMacro.copy(
                            meleeKeyCode = KeyEvent.KEYCODE_NUMPAD_0,
                        )
                    )
                } else profile
            }
            if (meleeChanged) dataStoreManager.saveProfiles(stored)
            dataStoreManager.save(DataStoreManager.QUICK_MELEE_NUMPAD_MIGRATED, true)
        }

        // 一次性迁移：快速近战触发键从默认 Alt+5 改为 Alt+0。
        // 只在旧默认值（5）上执行；用户之后手动改回 5 不会被再次覆盖。
        if (dataStoreManager.getBoolean(DataStoreManager.QUICK_MELEE_TRIGGER_ZERO_MIGRATED).first() != true) {
            var triggerChanged = false
            stored = stored.map { profile ->
                if (profile.quickMeleeMacro.triggerKeyCode == KeyEvent.KEYCODE_5) {
                    triggerChanged = true
                    profile.copy(
                        quickMeleeMacro = profile.quickMeleeMacro.copy(
                            triggerKeyCode = KeyEvent.KEYCODE_0,
                        )
                    )
                } else profile
            }
            if (triggerChanged) dataStoreManager.saveProfiles(stored)
            dataStoreManager.save(DataStoreManager.QUICK_MELEE_TRIGGER_ZERO_MIGRATED, true)
        }

        // 一次性迁移：快速近战宏改为默认关闭。只关闭仍是旧默认参数
        // （Alt+0 / num0 / 80ms）的宏，手动改过参数的保留用户选择。
        if (dataStoreManager.getBoolean(DataStoreManager.QUICK_MELEE_DEFAULT_OFF).first() != true) {
            var offChanged = false
            stored = stored.map { profile ->
                val m = profile.quickMeleeMacro
                if (m.enabled && m.triggerKeyCode == KeyEvent.KEYCODE_0 && m.requireAlt &&
                    m.meleeKeyCode == KeyEvent.KEYCODE_NUMPAD_0 && m.delayMs == 80
                ) {
                    offChanged = true
                    profile.copy(quickMeleeMacro = m.copy(enabled = false))
                } else profile
            }
            if (offChanged) dataStoreManager.saveProfiles(stored)
            dataStoreManager.save(DataStoreManager.QUICK_MELEE_DEFAULT_OFF, true)
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

    private companion object {
        /** 鼠标断点阈值默认值；用户可在设置页的高级项里调整（全局）。 */
        const val DEFAULT_MOUSE_JUMP_THRESHOLD_PX = 240
        /** F 交互后允许左键唤出光标的窗口（ms）默认值。 */
        const val DEFAULT_F_CURSOR_CLICK_WINDOW_MS = 1000
        /** ESC / Tab 关闭界面后的回瞄准延迟（ms）默认值。 */
        const val DEFAULT_TAB_ESC_AIM_DELAY_MS = 110
        /** ESC 后首次点击延迟（ms）默认值。 */
        const val DEFAULT_ESC_AFTER_TAP_DELAY_MS = 100
        /** ESC 后多个落点之间的间隔（ms）默认值。 */
        const val DEFAULT_ESC_AFTER_TAP_INTERVAL_MS = 50
        /** 射击模式空闲重锚阈值（ms）默认值与范围；0 = 关闭。 */
        const val DEFAULT_AIM_IDLE_REANCHOR_MS = 1000
        const val MIN_AIM_IDLE_REANCHOR_MS = 0
        const val MAX_AIM_IDLE_REANCHOR_MS = 5000
        /** 光标渲染刷新率默认 60Hz，可调 15–144Hz；只影响显示。 */
        const val DEFAULT_CURSOR_RENDER_HZ = 60
        const val MIN_CURSOR_RENDER_HZ = 15
        const val MAX_CURSOR_RENDER_HZ = 240
        /** Alt+D 拖拽：单程分几步、每步间隔（ms）；12 步 × 14ms ≈ 170ms。 */
        const val DRAG_STEPS = 12
        const val DRAG_STEP_MS = 14L
        /** Alt+D 拖拽速度倍数默认值与范围（设置页滑杆）；默认比旧版慢一倍。 */
        const val DEFAULT_DISCARD_DRAG_SPEED = 25f
        const val MIN_DISCARD_DRAG_SPEED = 1f
        const val MAX_DISCARD_DRAG_SPEED = 100f
        const val DEFAULT_DISCARD_PRESS_HOLD_MS = 200
        const val MIN_DISCARD_PRESS_HOLD_MS = 0
        const val MAX_DISCARD_PRESS_HOLD_MS = 1000
        /** 旧版本滑杆上限；读到 1–10 的旧值按 10 倍换算到新刻度。 */
        private const val LEGACY_MAX_DISCARD_DRAG_SPEED = 10f
        /** 鼠标活动范围（大方形）与回中落点范围（小方形）的最小半边长（px）。 */
        private const val MIN_MOUSE_AREA_HALF = 40f
        private const val MIN_LANDING_AREA_HALF = 8f
        /** F 光标滚轮操控：每格短滑的像素距离默认值与范围。 */
        const val DEFAULT_SCROLL_SWIPE_PX = 120f
        const val MIN_SCROLL_SWIPE_PX = 40f
        const val MAX_SCROLL_SWIPE_PX = 400f
        /** 滚轮短滑分几步；每步间隔可在设置里调（默认 7ms，5 步 ≈ 35ms）。 */
        private const val SCROLL_SWIPE_STEPS = 5
        /** 玩法槽上限（含简易槽）。 */
        const val MAX_PLAY_MODES = 8
        /** 快捷换位触发键：1/2（3/4 固定用于近战流程，不参与换位）。 */
        val SWAP_SLOT_KEYS = setOf(
            KeyEvent.KEYCODE_1,
            KeyEvent.KEYCODE_2,
        )
        /** 固定还原键：X（不对用户开放配置）。 */
        val SWAP_RESTORE_KEY = KeyEvent.KEYCODE_X
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
        val items = active.items
        layoutViewport = viewport
        _containerItems.value = items
        _appConfig.value = active.appConfig
        eventHandler.appConfig = active.appConfig
        eventHandler.setCancelableTouchMode(active.appConfig.cancellableTouchMode)
        eventHandler.setNormalBtnTouchMode(active.appConfig.normalBtnTouchMode)
        cancelableTouchMode.value = active.appConfig.cancellableTouchMode
        normalTouchMode.value = active.appConfig.normalBtnTouchMode
        // 灵敏度随预设切换。
        pointerSensitivity.value = active.appConfig.pointerSensitivity
        android.util.Log.i("KeySyncLayout", "apply id=${id} origin=${overlayOrigin?.let { it.x to it.y }} screen=${currentW}x$currentH items=${items.size}")
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
        eventHandler.shootingAreaOffsets = active.shootingAreaPoints.map { Offset(it.x, it.y) }
        eventHandler.shiftCancelWalkEnabled =
            activePlayMode.assists && active.shiftCancelWalkEnabled
        ensureDefaultMouseAreas()
        syncMacros()
        scrollTracker.reset()
    }

    private fun syncMacros() {
        val macros = activeProfile()?.macros.orEmpty()
        eventHandler.setMacros(macros,
            overlayViewport ?: currentScreenSize(), overlayOrigin ?: Offset.Zero)
        syncCursorSpawn()
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
                profile.copy(
                    // 尚未完成首次布局时容器是空的，直接沿用预设里的 items，避免把布局清空。
                    items = if (applied) _containerItems.value.map { it.copy() } else profile.items,
                    appConfig = _appConfig.value,
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
            syncMacros()
        }
        if (dataStoreManager.canWriteProfiles) {
            scope.launch { dataStoreManager.saveProfiles(_profiles.value) }
        }
    }

    // 连按落点计数：同一个键在窗口内连续按下时递增，超时或换键就重新从 1 开始。
    private var extraTapKeyCode = -1
    private var extraTapCount = 0
    private var extraTapLastAt = 0L
    // 批量落点：按下时先挂起，等按键松开后再执行（延迟自动适配按住时长）。
    private var pendingExtraTapKeyCode = -1
    private var pendingExtraTapPoints: List<ExtraTapPoint> = emptyList()

    private fun clearPendingExtraTap() {
        pendingExtraTapKeyCode = -1
        pendingExtraTapPoints = emptyList()
    }

    /**
     * 连按落点触发：第 N 次按下（N≥2）额外点击该键的第 N-1 个落点，最多 3 个。
     * 本体映射不受影响，由调用方继续走普通按键流程。
     */
    private fun handleExtraTap(keyCode: Int) {
        val profile = _profiles.value.firstOrNull { it.id == _activeProfileId.value } ?: return
        // 批量落点模式：任何配置了落点的键（ESC、F 等）在松开后依次点击全部落点，
        // 本体映射仍照常触发。按下时只挂起，避免落点与本体触点重叠。
        if (escAfterTapEnabled.value) {
            val points = profile.extraTapPoints.filter { it.keyCode == keyCode }
            pendingExtraTapKeyCode = if (points.isEmpty()) -1 else keyCode
            pendingExtraTapPoints = points
            return
        }
        val now = SystemClock.uptimeMillis()
        if (keyCode != extraTapKeyCode || now - extraTapLastAt > EXTRA_TAP_WINDOW_MS) {
            extraTapKeyCode = keyCode
            extraTapCount = 0
        }
        extraTapLastAt = now
        extraTapCount++
        val index = extraTapCount - 2
        if (index !in 0 until MAX_EXTRA_TAP_POINTS) return
        val point = profile.extraTapPoints.filter { it.keyCode == keyCode }.getOrNull(index) ?: return
        eventHandler.tapExtraPoint(Offset(point.x, point.y))
    }

    /** 该键是否配了批量落点脚本；用于把 ESC 的回瞄准推迟到脚本结束后。 */
    private fun hasBatchExtraTap(keyCode: Int): Boolean {
        if (!escAfterTapEnabled.value) return false
        val profile = _profiles.value.firstOrNull { it.id == _activeProfileId.value } ?: return false
        return profile.extraTapPoints.any { it.keyCode == keyCode }
    }

    /**
     * 按键松开后执行挂起的批量落点。
     *
     * 以松开时刻为起点，延迟自动适配用户按住了多久；全部落点跑完后，
     * ESC / Tab 再按「回瞄准」配置切回射击模式。
     */
    private fun flushPendingExtraTap(keyCode: Int) {
        if (keyCode != pendingExtraTapKeyCode) return
        val points = pendingExtraTapPoints
        clearPendingExtraTap()
        if (points.isEmpty() || _isEditMode.value || _mappingPaused.value) return
        val returnAim = tabEscAimEnabled.value &&
            (keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_TAB)
        eventHandler.tapExtraPointsSequential(
            points.map { Offset(it.x, it.y) },
            escAfterTapDelayMs.value.toLong(),
            escAfterTapIntervalMs.value.toLong(),
            onFinished = { if (returnAim) scheduleAimReturn() },
        )
    }

    private var tapSequence234Job: Job? = null
    private var keyMacroJob: Job? = null
    private var quickMeleeJob: Job? = null
    /** 快速近战的触发键是否仍被按住；用它控制循环，避免依赖被吞掉的按键状态。 */
    private var quickMeleeHeld = false

    /**
     * 按 3 切近战：依次点击 2 → 4 → 3。
     *
     * 游戏会按携带情况重排四个格子：单枪时近战在 2，双枪时近战在 4，
     * 双枪 + 手枪时近战在 3。在 2 → 4 → 3 这个固定顺序里，三种布局的近战位
     * 都只出现一次（单枪在 2、双枪在 4、双枪+手枪在 3），所以最终一定停在近战上。
     * 点空位不会切走当前武器，没有副作用——这是固定顺序也能通用的前提。
     *
     * 真正要避免的是「近战位被点第二次」：刀已经在手上时再点同一格，游戏会
     * 收回刀、切回主武器 1。因此一轮里近战位只出现一次，绝不补第二轮。
     *
     * 另外游戏内需要关闭「快速切枪」：它会在切换后自动切回主武器，
     * 破坏最后落在近战上的结果。
     *
     * 三个触点都走正常的按键映射（`eventHandler.handleKeyEvent`），所以用户把
     * 2/4/3 绑到哪个位置就点哪里。整个过程串行执行，重复按 3 不会叠加。
     */
    private fun runTapSequence234(keyCode: Int): Boolean {
        if (!activePlayMode.assists) return false
        if (keyCode != KeyEvent.KEYCODE_3) return false
        val profile = _profiles.value.firstOrNull { it.id == _activeProfileId.value } ?: return false
        if (!profile.tapSequence234Enabled) return false
        // 三个按钮缺一就不接管，让 3 走原本的单键映射，避免按了没反应。
        val targets = listOf(KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_3)
        val items = _containerItems.value
        if (targets.any { code -> items.none { it.boundKeyCode() == code } }) return false
        if (tapSequence234Job?.isActive == true) return true
        // 相邻触点之间的延迟在预设的区间内随机（默认 20–30ms），避免固定节拍。
        val gapMin = profile.tapSequence234GapMinMs.coerceIn(5, 500)
        val gapMax = profile.tapSequence234GapMaxMs.coerceIn(gapMin, 500)
        tapSequence234Job = scope.launch {
            // 只跑一轮：开大/已对调时也不补第二轮，避免近战位被点第二次又切回主武器 1。
            injectMeleeSequence(targets, gapMin, gapMax)
        }
        return true
    }

    /** 串行注入一轮 2 → 4 → 3，每个触点走正常按键映射，段间延迟在 [gapMin, gapMax] 内随机。 */
    private suspend fun injectMeleeSequence(targets: List<Int>, gapMin: Int, gapMax: Int) {
        targets.forEach { code ->
            val now = SystemClock.uptimeMillis()
            eventHandler.handleKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
            val gap = if (gapMax <= gapMin) gapMin
            else kotlin.random.Random.nextInt(gapMin, gapMax + 1)
            val hold = (gap / 2).coerceIn(5, 80).toLong()
            delay(hold)
            eventHandler.handleKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
            delay(gap.coerceAtLeast(2).toLong())
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
    /**
     * 快速近战宏：默认按住 Alt+0 触发。
     *
     * 每一轮：点一次「快速近战」绑定的映射位置 → 等 delayMs（默认 80ms）→ 点一次 1 号键。
     * 按住触发键会一直循环同一套动作，松开立刻停止；键盘长按产生的重复 DOWN 直接被吞掉。
     * 整个循环只有一个协程和两条时间戳/状态，不会注册额外监听，所以不会因为长按或狂按卡顿。
     */
    /** 立即停止快速近战循环（松开触发键或切换玩法模式时用）。 */
    private fun stopQuickMelee() {
        quickMeleeHeld = false
        quickMeleeJob?.cancel()
        quickMeleeJob = null
    }

    private fun runQuickMeleeMacro(keyEvent: KeyEvent): Boolean {
        if (!activePlayMode.quickMelee) return false
        val profile = _profiles.value.firstOrNull { it.id == _activeProfileId.value } ?: return false
        val macro = profile.quickMeleeMacro
        if (!macro.enabled || keyEvent.keyCode != macro.triggerKeyCode) return false
        // 松开必须能停：Alt 可能比 5 先松开，所以 UP 不校验 Alt 状态。
        if (keyEvent.action == KeyEvent.ACTION_UP) {
            val wasActive = quickMeleeJob?.isActive == true || quickMeleeHeld
            stopQuickMelee()
            return wasActive
        }
        if (keyEvent.action != KeyEvent.ACTION_DOWN) return false
        // 循环期间连重复 DOWN 也一起吞掉，避免触发键本体漏到游戏里。
        if (quickMeleeHeld || quickMeleeJob?.isActive == true) return true
        if (macro.requireAlt && !keyEvent.isAltPressed) return false
        quickMeleeHeld = true
        val startedAt = SystemClock.uptimeMillis()
        quickMeleeJob = scope.launch {
            try {
                while (quickMeleeHeld && !_isEditMode.value && !_mappingPaused.value) {
                    // 兜底：鼠标固件万一不发 UP，最长循环 60 秒后自动停，避免无限点击。
                    if (SystemClock.uptimeMillis() - startedAt > 60_000L) break
                    val items = _containerItems.value
                    val meleeItem = items.firstOrNull { it.boundKeyCode() == macro.meleeKeyCode } ?: break
                    val slotOne = items.firstOrNull { it.boundKeyCode() == KeyEvent.KEYCODE_1 } ?: break
                    val meleeCenter = meleeItem.touchCenter ?: break
                    val oneCenter = slotOne.touchCenter ?: break
                    tapSeqCenter(meleeCenter)
                    delay(macro.delayMs.coerceIn(20, 1000).toLong())
                    tapSeqCenter(oneCenter)
                }
            } finally {
                quickMeleeHeld = false
            }
        }
        return true
    }

    /** 在指定屏幕坐标注入一次短按（5ms），供循环类宏复用。 */
    private suspend fun tapSeqCenter(center: androidx.compose.ui.geometry.Offset) {
        val pointer = eventHandler.allocateSeqPointer()
        eventHandler.pressSeqPointer(pointer, center)
        try {
            delay(5)
        } finally {
            // 循环被取消/暂停打断时也必须松开，避免触点卡在按下状态。
            eventHandler.releaseSeqPointer(pointer)
        }
    }

    private fun runKeyMacro(keyEvent: KeyEvent): Boolean {
        if (!activePlayMode.macros) return false
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
        if (!activePlayMode.assists) return false
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
        val count = profile.extraTapPoints.count { it.keyCode == keyCode }
        if (count >= MAX_EXTRA_TAP_POINTS) return
        val anchor = _containerItems.value.firstOrNull { it.boundKeyCode() == keyCode }?.position
            ?: Offset(240f, 520f)
        // 依次错开，避免多个落点叠在同一个像素上。
        updateExtraTapPoints(
            profile.extraTapPoints + ExtraTapPoint(
                keyCode,
                anchor.x + 90f + count * 44f,
                anchor.y + 90f + count * 44f,
            )
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

    /** 删除某个按键的第 [index] 个连按落点（index 按该键自己的顺序，从 0 起）。 */
    fun removeExtraTapPoint(keyCode: Int, index: Int) {
        val profile = _profiles.value.firstOrNull { it.id == _activeProfileId.value } ?: return
        var seen = -1
        val updated = profile.extraTapPoints.filter { point ->
            if (point.keyCode != keyCode) {
                true
            } else {
                seen++
                seen != index
            }
        }
        if (updated.size != profile.extraTapPoints.size) updateExtraTapPoints(updated)
    }

    /* ---------- ` 键手绘区域 ---------- */

    /** 进入绘制：从零开始画新的多边形。 */
    fun beginShootingAreaDrawing() {
        if (!_isEditMode.value) return
        shootingAreaDraft.value = emptyList()
    }

    /** 记录一次点击：坐标是悬浮层绝对坐标，存成相对 ` 键左上角的偏移。 */
    fun addShootingAreaDraftPoint(absolute: Offset) {
        val draft = shootingAreaDraft.value ?: return
        if (draft.size >= MAX_SHOOTING_AREA_POINTS) return
        val key = _containerItems.value.filterIsInstance<DraggableItem.FixedKey>()
            .firstOrNull { it.type == DraggableItemType.SHOOTING_MODE } ?: return
        shootingAreaDraft.value = draft + AreaPoint(
            x = absolute.x - key.position.x,
            y = absolute.y - key.position.y,
        )
    }

    fun undoShootingAreaDraftPoint() {
        val draft = shootingAreaDraft.value ?: return
        if (draft.isNotEmpty()) shootingAreaDraft.value = draft.dropLast(1)
    }

    fun clearShootingAreaDraft() {
        if (shootingAreaDraft.value == null) return
        shootingAreaDraft.value = emptyList()
    }

    fun cancelShootingAreaDrawing() {
        shootingAreaDraft.value = null
    }

    /** 完成绘制：少于 3 个点视为放弃；否则写入当前预设并立即生效。 */
    fun finishShootingAreaDrawing() {
        val draft = shootingAreaDraft.value ?: return
        shootingAreaDraft.value = null
        if (draft.size < 3) return
        val profile = activeProfile() ?: return
        _profiles.value = _profiles.value.map {
            if (it.id == profile.id) it.copy(shootingAreaPoints = draft) else it
        }
        eventHandler.shootingAreaOffsets = draft.map { Offset(it.x, it.y) }
        persistActiveProfile()
    }

    /** 恢复默认方形区域（清空手绘多边形）。 */
    fun clearShootingArea() {
        shootingAreaDraft.value = null
        val profile = activeProfile() ?: return
        if (profile.shootingAreaPoints.isEmpty()) return
        _profiles.value = _profiles.value.map {
            if (it.id == profile.id) it.copy(shootingAreaPoints = emptyList()) else it
        }
        eventHandler.shootingAreaOffsets = emptyList()
        persistActiveProfile()
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

    /** 全局开关：F 交互后 400ms 内首次左键唤出光标（默认关闭）。 */
    fun setFCursorClickEnabled(enabled: Boolean) {
        fCursorClickEnabled.value = enabled
        if (!enabled) fInteractArmedAt = 0L
        scope.launch { dataStoreManager.save(DataStoreManager.F_CURSOR_CLICK_ENABLED, enabled) }
    }

    /** 全局开关：物理 ESC / Tab 关闭界面后自动回射击模式（默认开启）。 */
    fun setTabEscAimEnabled(enabled: Boolean) {
        tabEscAimEnabled.value = enabled
        if (!enabled) aimReturnJob?.cancel()
        scope.launch { dataStoreManager.save(DataStoreManager.TAB_ESC_AIM_ENABLED, enabled) }
    }

    /** ESC / Tab 回瞄准延迟（50–1000ms）。 */
    fun setTabEscAimDelayMs(ms: Int) {
        val safe = ms.coerceIn(50, 1000)
        tabEscAimDelayMs.value = safe
        scope.launch { dataStoreManager.save(DataStoreManager.TAB_ESC_AIM_DELAY_MS, safe) }
    }

    /** 全局开关：ESC 按下后延迟依次点击配置的落点（ESC 本体映射不变）。 */
    fun setEscAfterTapEnabled(enabled: Boolean) {
        escAfterTapEnabled.value = enabled
        if (!enabled) {
            eventHandler.cancelExtraTapSequence()
            clearPendingExtraTap()
        }
        scope.launch { dataStoreManager.save(DataStoreManager.ESC_AFTER_TAP_ENABLED, enabled) }
    }

    /** ESC 后首次点击延迟（0–1000ms）。 */
    fun setEscAfterTapDelayMs(ms: Int) {
        val safe = ms.coerceIn(0, 1000)
        escAfterTapDelayMs.value = safe
        scope.launch { dataStoreManager.save(DataStoreManager.ESC_AFTER_TAP_DELAY_MS, safe) }
    }

    /** ESC 后多个落点之间的间隔（0–500ms）。 */
    fun setEscAfterTapIntervalMs(ms: Int) {
        val safe = ms.coerceIn(0, 500)
        escAfterTapIntervalMs.value = safe
        scope.launch { dataStoreManager.save(DataStoreManager.ESC_AFTER_TAP_INTERVAL_MS, safe) }
    }

    /** 射击模式空闲重锚阈值（0–5000ms），0 = 关闭。 */
    fun setAimIdleReanchorMs(ms: Int) {
        val safe = ms.coerceIn(MIN_AIM_IDLE_REANCHOR_MS, MAX_AIM_IDLE_REANCHOR_MS)
        aimIdleReanchorMs.value = safe
        eventHandler.aimIdleReanchorMs = safe.toLong()
        scope.launch { dataStoreManager.save(DataStoreManager.AIM_IDLE_REANCHOR_MS, safe) }
    }

    /** 切换瞄准回中预设；旧值或非法值回落到 INSTANT，保证兼容。 */
    fun setAimRecenterMode(mode: AimRecenterMode) {
        aimRecenterMode.value = mode
        eventHandler.aimRecenterMode = mode
        scope.launch { dataStoreManager.save(DataStoreManager.AIM_RECENTER_MODE, mode.name) }
    }

    /** F 交互后左键唤出光标的窗口（100–3000ms）。 */
    fun setFCursorClickWindowMs(ms: Int) {
        val safe = ms.coerceIn(100, 3000)
        fCursorClickWindowMs.value = safe
        scope.launch { dataStoreManager.save(DataStoreManager.F_CURSOR_CLICK_WINDOW_MS, safe) }
    }

    /** F 滚轮操控：上/下滑触发键（默认鼠标滚轮上/下）。 */
    fun setFScrollTriggerKeys(upKeyCode: Int, downKeyCode: Int) {
        fScrollUpKeyCode.value = upKeyCode
        fScrollDownKeyCode.value = downKeyCode
        scope.launch {
            dataStoreManager.save(DataStoreManager.F_SCROLL_UP_KEY_CODE, upKeyCode)
            dataStoreManager.save(DataStoreManager.F_SCROLL_DOWN_KEY_CODE, downKeyCode)
        }
    }

    /**
     * 延迟回瞄准：游戏关闭背包/地图需要几帧，立即注入会被丢掉。
     * 延迟结束后若仍在射击模式则幂等重建准心触点，否则切入射击模式。
     */
    private fun scheduleAimReturn() {
        aimReturnJob?.cancel()
        // 已经在射击模式时不做任何事，避免按 Tab 看比分、ESC 开菜单时重建触点。
        if (isShootingMode.value) return
        aimReturnJob = scope.launch {
            delay(tabEscAimDelayMs.value.toLong())
            eventHandler.setShootingModeEnabled(true)
            // 界面还在收尾时注入的 DOWN 可能被游戏丢掉，等它彻底关闭后再补一次。
            delay(150L)
            eventHandler.ensureAimContact()
        }
    }

    /**
     * M 键：第一次按下开图并立即切出鼠标（M 本身继续走正常映射）；
     * 第二次按下点 ESC 映射位置并收回鼠标（进入射击模式），本次 M 被吞掉。
     */
    private fun handleMapMouseToggle(): Boolean {
        if (!mapToggleOpen) {
            mapToggleOpen = true
            fInteractArmedAt = 0L
            // 开图后鼠标立刻可用，滚轮也能直接滑地图，不需要先晃一下鼠标。
            mouseCursorSeen = true
            eventHandler.setShootingModeEnabled(false)
            fScrollControlActive = fScrollControlEnabled.value && activePlayMode.scrollControl
            return false
        }
        // 第二次按下：收回光标。
        mapToggleOpen = false
        fInteractArmedAt = 0L
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

    /** 全局设置：Alt+D 开始前先按住鼠标的时长（0–1000ms）。 */
    fun setDiscardPressHoldMs(ms: Int) {
        val safe = ms.coerceIn(MIN_DISCARD_PRESS_HOLD_MS, MAX_DISCARD_PRESS_HOLD_MS)
        discardPressHoldMs.value = safe
        scope.launch { dataStoreManager.save(DataStoreManager.DISCARD_PRESS_HOLD_MS, safe) }
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

    /* ---------------- 鼠标双范围 ---------------- */

    /** 把双范围同步给事件处理器（鼠标活动范围 + 回中随机落点范围）。 */
    private fun syncMouseAreas() {
        eventHandler.mouseAreaCenter = mouseAreaCenter.value
        eventHandler.mouseAreaHalfSize = mouseAreaHalf.value
        eventHandler.landingAreaCenter = landingAreaCenter.value
        eventHandler.landingAreaHalfSize = landingAreaHalf.value
    }

    /** ` 按钮当前测得的屏幕中心；供悬浮层换算本地坐标。 */
    fun shootingModeAnchorPoint(): Offset? =
        _containerItems.value.filterIsInstance<DraggableItem.FixedKey>()
            .firstOrNull { it.type == DraggableItemType.SHOOTING_MODE }?.touchCenter

    /** ` 按钮的屏幕中心；没有该按钮时回落到右半屏默认位置。 */
    private fun shootingModeAnchor(): Offset {
        val viewport = overlayViewport ?: currentScreenSize()
        val origin = overlayOrigin ?: Offset.Zero
        val fallback = origin + Offset(viewport.x * 0.68f, viewport.y * 0.5f)
        val toggle = _containerItems.value.filterIsInstance<DraggableItem.FixedKey>()
            .firstOrNull { it.type == DraggableItemType.SHOOTING_MODE }
            ?: return fallback
        return toggle.touchCenter
            ?: origin + toggle.position + Offset(toggle.size / 2f, toggle.size / 2f)
    }

    /** 由「相对 ` 的偏移」换算出绝对中心，主按钮移动后自动跟随。 */
    private fun recomputeMouseAreaCenters() {
        val anchor = shootingModeAnchor()
        mouseAreaCenter.value = anchor + mouseAreaOffset.value
        landingAreaCenter.value = anchor + landingAreaOffset.value
    }

    /** 旋转 / 分辨率变化后把双范围裁回屏幕内，并同步回相对偏移。 */
    private fun clampMouseAreasToScreen() {
        val half = mouseAreaHalf.value
        if (half <= 0f) return
        val screen = currentScreenSize()
        if (screen.x <= 0f || screen.y <= 0f) return
        val anchor = shootingModeAnchor()
        val safeHalf = half.coerceAtMost(minOf(screen.x, screen.y) * 0.48f)
        mouseAreaHalf.value = safeHalf
        val center = anchor + mouseAreaOffset.value
        val safeCenter = Offset(
            center.x.coerceIn(safeHalf, (screen.x - safeHalf).coerceAtLeast(safeHalf)),
            center.y.coerceIn(safeHalf, (screen.y - safeHalf).coerceAtLeast(safeHalf)),
        )
        mouseAreaOffset.value = safeCenter - anchor
        val landHalf = landingAreaHalf.value.coerceAtMost(safeHalf)
        landingAreaHalf.value = landHalf
        val landCenter = anchor + landingAreaOffset.value
        landingAreaOffset.value = Offset(
            landCenter.x.coerceIn(safeCenter.x - safeHalf, safeCenter.x + safeHalf),
            landCenter.y.coerceIn(safeCenter.y - safeHalf, safeCenter.y + safeHalf),
        ) - anchor
        recomputeMouseAreaCenters()
        syncMouseAreas()
    }

    /**
     * 首次使用时生成默认双范围：都以准心（` 按钮）为中心，
     * 大范围限制视角触点的活动区域，小范围用于每次回中的随机落点。
     */
    private fun ensureDefaultMouseAreas() {
        if (mouseAreaCustomized) {
            recomputeMouseAreaCenters()
            syncMouseAreas()
            return
        }
        val viewport = overlayViewport ?: currentScreenSize()
        if (viewport.x <= 0f || viewport.y <= 0f) return
        val minSide = minOf(viewport.x, viewport.y)
        mouseAreaOffset.value = Offset.Zero
        mouseAreaHalf.value = (minSide * 0.30f).coerceIn(60f, minSide * 0.45f)
        landingAreaOffset.value = Offset.Zero
        landingAreaHalf.value = (minSide * 0.05f).coerceIn(10f, mouseAreaHalf.value)
        recomputeMouseAreaCenters()
        syncMouseAreas()
    }

    /** 主按钮 ` 移动后重新锚定双范围（编辑态拖动时实时跟随）。 */
    fun refreshMouseAreaAnchor() {
        if (mouseAreaHalf.value <= 0f) return
        recomputeMouseAreaCenters()
        syncMouseAreas()
    }

    /** 高级层拖动/缩放「鼠标活动范围」（大范围，形状为手绘不规则多边形）。 */
    fun setMouseArea(center: Offset, half: Float) {
        mouseAreaCustomized = true
        val screen = currentScreenSize()
        val safeHalf = half.coerceIn(MIN_MOUSE_AREA_HALF, maxOf(MIN_MOUSE_AREA_HALF, minOf(screen.x, screen.y) * 0.48f))
        val anchor = shootingModeAnchor()
        val safeCenter = Offset(
            center.x.coerceIn(safeHalf, (screen.x - safeHalf).coerceAtLeast(safeHalf)),
            center.y.coerceIn(safeHalf, (screen.y - safeHalf).coerceAtLeast(safeHalf)),
        )
        mouseAreaOffset.value = safeCenter - anchor
        mouseAreaHalf.value = safeHalf
        val landCenter = landingAreaCenter.value
        landingAreaOffset.value = Offset(
            landCenter.x.coerceIn(safeCenter.x - safeHalf, safeCenter.x + safeHalf),
            landCenter.y.coerceIn(safeCenter.y - safeHalf, safeCenter.y + safeHalf),
        ) - anchor
        landingAreaHalf.value = landingAreaHalf.value.coerceAtMost(safeHalf)
        recomputeMouseAreaCenters()
        syncMouseAreas()
        scope.launch {
            dataStoreManager.save(DataStoreManager.MOUSE_AREA_OFFSET_X, mouseAreaOffset.value.x)
            dataStoreManager.save(DataStoreManager.MOUSE_AREA_OFFSET_Y, mouseAreaOffset.value.y)
            dataStoreManager.save(DataStoreManager.MOUSE_AREA_HALF, safeHalf)
            dataStoreManager.save(DataStoreManager.LANDING_AREA_OFFSET_X, landingAreaOffset.value.x)
            dataStoreManager.save(DataStoreManager.LANDING_AREA_OFFSET_Y, landingAreaOffset.value.y)
            dataStoreManager.save(DataStoreManager.LANDING_AREA_HALF, landingAreaHalf.value)
        }
    }

    /** 高级层拖动/缩放「回中落点范围」（小方形）。 */
    fun setLandingArea(center: Offset, half: Float) {
        mouseAreaCustomized = true
        val bigHalf = mouseAreaHalf.value
        val bigCenter = mouseAreaCenter.value
        val anchor = shootingModeAnchor()
        val safeHalf = half.coerceIn(MIN_LANDING_AREA_HALF, maxOf(MIN_LANDING_AREA_HALF, bigHalf))
        val clampedCenter = if (bigHalf > 0f) {
            Offset(
                center.x.coerceIn(bigCenter.x - bigHalf, bigCenter.x + bigHalf),
                center.y.coerceIn(bigCenter.y - bigHalf, bigCenter.y + bigHalf),
            )
        } else center
        landingAreaOffset.value = clampedCenter - anchor
        landingAreaHalf.value = safeHalf
        recomputeMouseAreaCenters()
        syncMouseAreas()
        scope.launch {
            dataStoreManager.save(DataStoreManager.LANDING_AREA_OFFSET_X, landingAreaOffset.value.x)
            dataStoreManager.save(DataStoreManager.LANDING_AREA_OFFSET_Y, landingAreaOffset.value.y)
            dataStoreManager.save(DataStoreManager.LANDING_AREA_HALF, safeHalf)
        }
    }

    /** 进入光标模式时光标出现的位置；未自定义时回到屏幕中心。 */
    fun cursorSpawnPoint(): Offset {
        val profile = activeProfile()
        val x = profile?.cursorSpawnX
        val y = profile?.cursorSpawnY
        if (x != null && y != null) return Offset(x, y)
        val viewport = overlayViewport ?: currentScreenSize()
        return Offset(viewport.x / 2f, viewport.y / 2f)
    }

    /** 拖动「光标落点」：写入当前预设并立即生效。 */
    fun moveCursorSpawn(position: Offset) {
        val id = _activeProfileId.value ?: return
        _profiles.value = _profiles.value.map {
            if (it.id == id) it.copy(cursorSpawnX = position.x, cursorSpawnY = position.y) else it
        }
        syncCursorSpawn()
        persistActiveProfile()
    }

    /** 把「光标落点」恢复为屏幕中心（清掉自定义坐标）。 */
    fun resetCursorSpawn() {
        val id = _activeProfileId.value ?: return
        _profiles.value = _profiles.value.map {
            if (it.id == id) it.copy(cursorSpawnX = null, cursorSpawnY = null) else it
        }
        syncCursorSpawn()
        persistActiveProfile()
    }

    private fun syncCursorSpawn() {
        eventHandler.cursorSpawnPosition = cursorSpawnPoint()
    }

    /* ---------------- F 光标滚轮操控 ---------------- */

    /** 全局开关：F 唤出光标后，滚轮改在光标位置上下滑动。 */
    fun setFScrollControlEnabled(enabled: Boolean) {
        fScrollControlEnabled.value = enabled
        if (!enabled) fScrollControlActive = false
        scope.launch { dataStoreManager.save(DataStoreManager.F_SCROLL_CONTROL_ENABLED, enabled) }
    }

    /** 每格滚轮的短滑距离（px）。 */
    fun setFScrollSwipePx(px: Float) {
        val safe = px.coerceIn(MIN_SCROLL_SWIPE_PX, MAX_SCROLL_SWIPE_PX)
        fScrollSwipePx.value = safe
        scope.launch { dataStoreManager.save(DataStoreManager.F_SCROLL_SWIPE_PX, safe) }
    }

    /** 滚轮方向反转。 */
    fun setFScrollInvert(invert: Boolean) {
        fScrollInvert.value = invert
        scope.launch { dataStoreManager.save(DataStoreManager.F_SCROLL_INVERT, invert) }
    }

    /** 每小步延迟（2–40ms）。 */
    fun setFScrollStepDelayMs(ms: Int) {
        val safe = ms.coerceIn(2, 40)
        fScrollStepDelayMs.value = safe
        scope.launch { dataStoreManager.save(DataStoreManager.F_SCROLL_STEP_DELAY_MS, safe) }
    }

    /* ---------------- 玩法模式 ---------------- */

    /** 切换全局玩法模式；只做运行时门控，不改各功能自己的开关。 */
    fun setCurrentPlayMode(index: Int) {
        val safe = index.coerceIn(0, playModes.value.lastIndex)
        currentPlayModeIndex.value = safe
        applyPlayMode()
        scope.launch { dataStoreManager.save(DataStoreManager.CURRENT_PLAY_MODE, safe) }
    }

    /** 编辑方案的功能组合；简易槽固定不可编辑。 */
    fun updatePlayMode(index: Int, mode: PlayMode) {
        if (index !in 1..playModes.value.lastIndex) return
        val safe = mode.copy(name = mode.name.ifBlank { "方案$index" })
        playModes.value = playModes.value.toMutableList().also { it[index] = safe }
        if (index == currentPlayModeIndex.value) applyPlayMode()
        savePlayModes()
    }

    /** 新增一个方案槽（自动命名方案 N 并切过去）。 */
    fun addPlayMode() {
        if (playModes.value.size >= MAX_PLAY_MODES) return
        val newMode = PlayMode(name = "方案${playModes.value.size}")
        playModes.value = playModes.value + newMode
        currentPlayModeIndex.value = playModes.value.lastIndex
        applyPlayMode()
        savePlayModes()
        scope.launch {
            dataStoreManager.save(DataStoreManager.CURRENT_PLAY_MODE, currentPlayModeIndex.value)
        }
    }

    private fun savePlayModes() {
        scope.launch {
            dataStoreManager.save(
                DataStoreManager.PLAY_MODES,
                Json { ignoreUnknownKeys = true }.encodeToString(playModes.value)
            )
        }
    }

    /** 把当前模式的开关同步到运行时；关闭的功能立即停掉正在跑的宏。 */
    private fun applyPlayMode() {
        val mode = activePlayMode
        eventHandler.macrosAllowed = mode.macros
        eventHandler.humanizationAllowed = mode.humanization
        if (!mode.macros) {
            eventHandler.cancelClickMacros()
            keyMacroJob?.cancel()
            keyMacroJob = null
        }
        if (!mode.quickMelee) stopQuickMelee()
        if (!mode.assists) {
            tapSequence234Job?.cancel()
        }
        eventHandler.shiftCancelWalkEnabled =
            mode.assists && (activeProfile()?.shiftCancelWalkEnabled ?: true)
        if (!mode.fCursor) fScrollControlActive = false
    }

    /**
     * F 唤出光标后接管滚轮：在光标当前位置注入一次短滑。
     * 返回 false 时由调用方继续走原有滚轮映射。
     */
    private fun handleScrollControl(codes: List<Int>): Boolean {
        if (!activePlayMode.scrollControl) return false
        if (!fScrollControlActive || !fScrollControlEnabled.value) return false
        if (isShootingMode.value || _isEditMode.value || !mouseCursorSeen) return false
        val upKey = fScrollUpKeyCode.value
        val downKey = fScrollDownKeyCode.value
        val vertical = codes.filter { it == upKey || it == downKey }
        if (vertical.isEmpty()) return false
        // 全屏任意位置都能滑：以当前光标为起点，不再分左右半屏，也不需要预放锚点。
        val screenAnchor = _mousePointerOffset.value + (overlayOrigin ?: Offset.Zero)
        val distance = fScrollSwipePx.value.coerceIn(MIN_SCROLL_SWIPE_PX, MAX_SCROLL_SWIPE_PX)
        val flip = if (fScrollInvert.value) -1f else 1f
        vertical.forEach { code ->
            val sign = if (code == upKey) -1f else 1f
            queueScrollSwipe(screenAnchor, distance * sign * flip)
            _lastInputLabel.value = if (code == upKey) "滚轮上滑" else "滚轮下滑"
        }
        return true
    }

    /** 逐格串行执行，避免多根触点同时滑动被游戏识别成缩放。 */
    private fun queueScrollSwipe(screenAnchor: Offset, deltaY: Float) {
        scope.launch {
            scrollSwipeMutex.withLock {
                if (!fScrollControlActive || isShootingMode.value) return@withLock
                val pointer = eventHandler.allocateDragPointer()
                try {
                    eventHandler.beginCursorDrag(pointer, screenAnchor)
                    repeat(SCROLL_SWIPE_STEPS) { index ->
                        val t = (index + 1f) / SCROLL_SWIPE_STEPS
                        eventHandler.moveCursorDrag(pointer, screenAnchor + Offset(0f, deltaY * t))
                        delay(fScrollStepDelayMs.value.toLong())
                    }
                } finally {
                    eventHandler.endCursorDrag(pointer)
                }
            }
        }
    }

    /**
     * Alt+D：在当前光标位置按住左键（默认 200ms，可配）→ 拖到丢弃点 → 停顿 → 松开 → 光标回位。
     *
     * 只在光标模式（非射击模式）且真正出现过鼠标输入时生效；按住 Alt 是硬性前提，
     * 避免单按 D 或误触时在游戏里拖拽/转视角。
     */
    private fun startDiscardDrag(): Boolean {
        if (!activePlayMode.discard) return false
        if (!discardDragEnabled.value || isShootingMode.value || !mouseCursorSeen) return false
        if (_isEditMode.value) return false
        if (discardDragJob?.isActive == true) return false
        val target = discardPoint() ?: return false
        val origin = overlayOrigin ?: Offset.Zero
        val start = _mousePointerOffset.value
        // 速度倍数只作用在移动阶段；按下/停顿时长不变，避免游戏来不及识别“放下”。
        // 高倍速下一程可能不足 1ms/步，此时减少插值步数（最快约 2ms 走完），
        // 否则 delay() 的最小粒度会把 100 倍速压回和 10 倍速一样快。
        val dragSpeed = discardDragSpeed.value
            .coerceIn(MIN_DISCARD_DRAG_SPEED, MAX_DISCARD_DRAG_SPEED)
        val totalMs = DRAG_STEP_MS * DRAG_STEPS / dragSpeed
        val stepCount = if (totalMs < DRAG_STEPS) {
            totalMs.roundToInt().coerceIn(2, DRAG_STEPS)
        } else {
            DRAG_STEPS
        }
        val stepMs = if (totalMs < DRAG_STEPS) 1L
        else (totalMs / DRAG_STEPS).roundToLong().coerceAtLeast(1L)
        discardDragJob = scope.launch {
            val pointer = eventHandler.allocateDragPointer()
            var released = false
            try {
                eventHandler.beginCursorDrag(pointer, start + origin)
                // 按下后先保持一段时间，等游戏识别「拿起物品」再开始移动；时长可配。
                delay(discardPressHoldMs.value.toLong()
                    .coerceIn(MIN_DISCARD_PRESS_HOLD_MS.toLong(), MAX_DISCARD_PRESS_HOLD_MS.toLong()))
                repeat(stepCount) { index ->
                    val t = (index + 1f) / stepCount
                    val p = lerpOffset(start, target, t)
                    moveCursorTo(p)
                    eventHandler.moveCursorDrag(pointer, p + origin)
                    delay(stepMs)
                }
                delay(70)
                eventHandler.endCursorDrag(pointer)
                released = true
                delay(70)
                repeat(stepCount) { index ->
                    val t = (index + 1f) / stepCount
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

    /** 编辑态「点哪放哪」：点一下按钮选中，再点屏幕任意位置即可把它移过去。 */
    val pendingPlaceItemId = MutableStateFlow<Int?>(null)
    /** 放置后短暂抑制选中，避免落点位置上的其他按钮被顺带选中。 */
    private var lastPlacementAt = 0L

    /** 编辑态点一下按钮：选中 / 再点一次取消；不影响拖动与双击设置。 */
    fun toggleSelectForPlacement(itemId: Int) {
        if (!_isEditMode.value) return
        if (SystemClock.uptimeMillis() - lastPlacementAt < 500L) return
        pendingPlaceItemId.value = if (pendingPlaceItemId.value == itemId) null else itemId
    }

    fun clearPendingPlacement() {
        pendingPlaceItemId.value = null
    }

    /**
     * 把选中的按钮放到点击位置：点击点作为按钮中心，并限制在屏幕内。
     * 放置后立即持久化。
     */
    fun placeSelectedItemAt(click: Offset) {
        val id = pendingPlaceItemId.value ?: return
        pendingPlaceItemId.value = null
        if (!_isEditMode.value) return
        val items = _containerItems.value
        val item = items.firstOrNull { it.id == id } ?: return
        val viewport = overlayViewport ?: currentScreenSize()
        val scale = if (item is DraggableItem.WASDGroup) item.scale else _appConfig.value.buttonScale
        val size = item.layoutSizeForClamp(scale)
        if (size.width <= 0f || size.height <= 0f) return
        val target = clampToViewport(
            click - Offset(size.width / 2f, size.height / 2f),
            size,
            scale,
            Size(viewport.x, viewport.y),
        )
        item.position = target
        item.anchorPosition = target
        _containerItems.value = items.toList()
        lastPlacementAt = SystemClock.uptimeMillis()
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
        // ` 按钮的位置变化（拖动/改布局）时，双范围整体跟着走。
        if (item is DraggableItem.FixedKey && item.type == DraggableItemType.SHOOTING_MODE) {
            _shootingAnchorFlow.value = item.touchCenter
            refreshMouseAreaAnchor()
        }
        eventHandler.updateMeasuredPosition(item)
        // 只测量，不改布局、不落盘。
        // 这里以前会 pullItemIntoViewport() 并 persistActiveProfile()，而
        // onGloballyPositioned 在启动首帧、insets 变化、旋转后都会触发。
        // 一旦首帧视口比真实视口小，顶部按钮就被夹进来并永久保存，
        // 重启后继续沿用错误位置——这就是「右上角按钮每次重启下移」的持久化路径。
        // 真正的越界修复改为用户进入编辑态时集中处理（见 toggleEditMode）。
    }

    /**
     * 进入编辑态时集中修复真正越界的按钮。
     *
     * 只有用户主动进入编辑才允许改坐标并保存；启动测量过程绝不能改预设。
     * 判据是「按钮中心是否还在屏幕内」：顶部按钮允许保留少量负坐标，
     * 完全跑到屏幕外的旧布局才会被拉回。这正是 1.81「重启后顶部按钮下移」
     * 没修干净的那条持久化路径。
     */
    private fun repairItemsIntoViewport() {
        val viewport = overlayViewport ?: return
        val origin = overlayOrigin ?: Offset.Zero
        val limit = Size(viewport.x, viewport.y)
        var changed = false
        val repaired = _containerItems.value.map { item ->
            val size = when (item) {
                is DraggableItem.FixedKey -> item.size
                is DraggableItem.VariableKey -> item.size
                is DraggableItem.CancelableKey -> item.size
                is DraggableItem.WASDGroup -> 0
            }
            if (size <= 0) return@map item
            val target = clampToViewport(
                item.position,
                Size(size.toFloat(), size.toFloat()),
                _appConfig.value.buttonScale,
                limit,
            )
            if (target == item.position) return@map item
            changed = true
            Log.i(TAG, "repair item ${item.id} into viewport: ${item.position} -> $target")
            item.copy(position = target).also {
                it.anchorPosition = target
                it.touchCenter = origin + target + Offset(size / 2f, size / 2f)
            }
        }
        if (!changed) return
        _containerItems.value = repaired
        eventHandler.updateKeyMapping(repaired)
        persistActiveProfile()
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
        if (screenSizeCacheEnabled.value && mouseGuardEnabled.value) {
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
        cancelableTouchMode.value = newConfig.cancellableTouchMode
        normalTouchMode.value = newConfig.normalBtnTouchMode
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

    /** 光标渲染刷新率（Hz），默认 60；降低可减少悬浮层重绘。 */
    fun setCursorRenderHz(hz: Int) {
        val safe = hz.coerceIn(MIN_CURSOR_RENDER_HZ, MAX_CURSOR_RENDER_HZ)
        cursorRenderHz.value = safe
        scope.launch { dataStoreManager.save(DataStoreManager.CURSOR_RENDER_HZ, safe) }
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
        eventHandler.setWasdJitterInterval(
            wasdJitterRandomEnabled.value,
            wasdJitterMinMs.value,
            wasdJitterMaxMs.value,
        )
        eventHandler.setWasdTransition(wasdTransition.value, wasdTransitionMs.value)
        eventHandler.wasdTransitTremorPx = wasdTransitTremor.value
        eventHandler.wasdHoldTremorPx = wasdHoldTremor.value
        eventHandler.wasdHoldSettleMs = wasdHoldSettleMs.value
        eventHandler.wasdCenterJitterPx = wasdCenterJitterPx.value.toFloat()
        eventHandler.recenterMinIntervalMs = aimRecenterIntervalMs.value.toLong()
        eventHandler.fireFreeAimEnabled = fireFreeAim.value
    }

    fun setFireFreeAim(enabled: Boolean) {
        fireFreeAim.value = enabled
        eventHandler.fireFreeAimEnabled = enabled
        scope.launch { dataStoreManager.save(DataStoreManager.FIRE_FREE_AIM, enabled) }
    }

    fun setWasdTransitTremor(value: Float) {
        val clamped = value.coerceIn(0f, 2f)
        wasdTransitTremor.value = clamped
        eventHandler.wasdTransitTremorPx = clamped
        scope.launch {
            dataStoreManager.save(DataStoreManager.WASD_TRANSIT_TREMOR, (clamped * 10).roundToInt())
        }
    }

    fun setWasdHoldTremor(value: Float) {
        val clamped = value.coerceIn(0f, 4f)
        wasdHoldTremor.value = clamped
        eventHandler.wasdHoldTremorPx = clamped
        scope.launch {
            dataStoreManager.save(DataStoreManager.WASD_HOLD_TREMOR, (clamped * 10).roundToInt())
        }
    }

    fun setWasdHoldSettleMs(value: Int) {
        val clamped = value.coerceIn(0, 800)
        wasdHoldSettleMs.value = clamped
        eventHandler.wasdHoldSettleMs = clamped
        scope.launch { dataStoreManager.save(DataStoreManager.WASD_HOLD_SETTLE_MS, clamped) }
    }

    fun setWasdCenterJitterPx(value: Int) {
        val clamped = value.coerceIn(0, 40)
        wasdCenterJitterPx.value = clamped
        eventHandler.wasdCenterJitterPx = clamped.toFloat()
        scope.launch { dataStoreManager.save(DataStoreManager.WASD_CENTER_JITTER_PX, clamped) }
    }

    fun setAimRecenterIntervalMs(value: Int) {
        val clamped = value.coerceIn(0, 120)
        aimRecenterIntervalMs.value = clamped
        eventHandler.recenterMinIntervalMs = clamped.toLong()
        scope.launch { dataStoreManager.save(DataStoreManager.AIM_RECENTER_INTERVAL_MS, clamped) }
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

    /** WASD 随机偏移间隔开关；开启后在自定义区间内随机。 */
    fun setWasdJitterRandomEnabled(enabled: Boolean) {
        wasdJitterRandomEnabled.value = enabled
        syncHumanization()
        scope.launch { dataStoreManager.save(DataStoreManager.WASD_JITTER_RANDOM_ENABLED, enabled) }
    }

    /** WASD 随机间隔区间（ms），下限不得大于上限。 */
    fun setWasdJitterRange(minMs: Int, maxMs: Int) {
        val lo = minMs.coerceIn(50, 5000)
        val hi = maxMs.coerceIn(lo, 5000)
        wasdJitterMinMs.value = lo
        wasdJitterMaxMs.value = hi
        syncHumanization()
        scope.launch {
            dataStoreManager.save(DataStoreManager.WASD_JITTER_MIN_MS, lo)
            dataStoreManager.save(DataStoreManager.WASD_JITTER_MAX_MS, hi)
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

    /** 「防止大幅甩动导致视角问题」总开关：关掉后断点过滤、输入锁存、屏幕缓存都停用。 */
    fun setMouseGuardEnabled(enabled: Boolean) {
        mouseGuardEnabled.value = enabled
        if (!enabled) {
            latchedRelativeAxisMode = null
            cachedScreenSize = null
        }
        scope.launch { dataStoreManager.save(DataStoreManager.MOUSE_GUARD_ENABLED, enabled) }
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
        shootingAreaDraft.value = null
        clearPendingExtraTap()
        clearPendingPlacement()
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
        if (!_isEditMode.value) {
            // 进入编辑：先把真正跑到屏幕外的按钮拉回来（用户主动操作，允许保存），
            // 再拍快照。顺序不能反，否则取消编辑会把这次修复一起回滚。
            repairItemsIntoViewport()
            editSnapshot = EditSnapshot(
                profiles = _profiles.value.map { it.deepCopied() },
                containerItems = _containerItems.value.map { it.copy() },
            )
        }
        _isEditMode.value = !_isEditMode.value
        // 进/出编辑态都清掉“点哪放哪”的选中状态，避免跨层级残留。
        clearPendingPlacement()
        // 进/出编辑态都清空按下状态：编辑态下不维护 pressedKeys，避免切换回来时
        // 残留上一个状态的「按下」高亮。
        _pressedKeys.value = emptySet()
        // 编辑态不接管滚轮；退出编辑时回到按键层，避免下次进入还停在高级层。
        fScrollControlActive = false
        if (!_isEditMode.value) {
            eventHandler.updateKeyMapping(containerItems.value)
            syncMacros()
            // 退出编辑时丢弃未完成的区域草稿，避免下次进入编辑态闪出半截多边形。
            shootingAreaDraft.value = null
            // 编辑期间挂起的批量落点也不能留到游戏里执行。
            clearPendingExtraTap()
            // Position/size edits currently mutate DraggableItem in place, so
            // closing edit mode is the natural commit point for persistence.
            persistActiveProfile()
            editSnapshot = null
        }
    }

    /**
     * 取消本次编辑：回滚到进入编辑前的布局，再退出编辑态。
     *
     * 编辑期间的部分操作会立即落盘，所以回滚后还要再持久化一次，
     * 否则重启后会看到被误触改过的布局。
     */
    fun cancelEditMode() {
        if (!_isEditMode.value) return
        val snapshot = editSnapshot
        if (snapshot == null) {
            toggleEditMode()
            return
        }
        clearActivePointers()
        shootingAreaDraft.value = null
        clearPendingExtraTap()
        clearPendingPlacement()
        _profiles.value = snapshot.profiles
        val restored = snapshot.containerItems.map { it.copy() }
        _containerItems.value = restored
        // copy() 不带 transient 的 touchCenter，这里按当前悬浮窗原点补算，
        // 否则 updateKeyMapping 会退回本地坐标、触点整体偏移。
        overlayOrigin?.let { origin ->
            restored.forEach { item ->
                val size = when (item) {
                    is DraggableItem.FixedKey -> item.size
                    is DraggableItem.VariableKey -> item.size
                    else -> 0
                }
                if (size > 0) {
                    item.touchCenter = origin + item.position + Offset(size / 2f, size / 2f)
                }
            }
        }
        _isEditMode.value = false
        _pressedKeys.value = emptySet()
        fScrollControlActive = false
        eventHandler.updateKeyMapping(restored)
        syncMacros()
        persistActiveProfile()
        editSnapshot = null
    }

    /** 深拷贝预设，隔离 items / 宏步骤里可变的坐标字段。 */
    private fun Profile.deepCopied(): Profile = copy(
        items = items.map { it.copy() },
        macros = macros.map { macro -> macro.copy(steps = macro.steps.map { it.copy() }) },
    )

    /** 一键切换悬浮窗按键的显示/隐藏。 */
    fun toggleKeysVisible() {
        _keysVisible.value = !_keysVisible.value
        _advancedVisible.value = false
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
            clearPendingExtraTap()
            clearPendingPlacement()
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
        // 轨迹录制：记录所有物理按键的按下/抬起（自动重复只记第一次），
        // 供回放里的按键条显示，不参与映射逻辑。
        if (!_isEditMode.value &&
            (keyEvent.action == KeyEvent.ACTION_DOWN || keyEvent.action == KeyEvent.ACTION_UP) &&
            !(keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount > 0)
        ) {
            TrajectoryRecorder.recordKey(keyEvent.keyCode, keyEvent.action == KeyEvent.ACTION_DOWN)
        }
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
        // F 滚轮操控：默认滚轮键走 ACTION_SCROLL，这里补上用户改绑的键盘触发键。
        if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0 &&
            (keyEvent.keyCode == fScrollUpKeyCode.value || keyEvent.keyCode == fScrollDownKeyCode.value) &&
            handleScrollControl(listOf(keyEvent.keyCode))
        ) {
            return true
        }
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
        // F 交互后左键/中键唤出光标：只记时间戳，不注册监听；命中后立即清空。
        // 按住 Shift 时按 F 视为组合键（如 Shift+F），不进入唤出光标的窗口。
        if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0 &&
            keyEvent.keyCode == KeyEvent.KEYCODE_F && fCursorClickEnabled.value &&
            activePlayMode.fCursor && isShootingMode.value && !keyEvent.isShiftPressed
        ) {
            fInteractArmedAt = SystemClock.uptimeMillis()
        }
        // 光标模式（鼠标已唤起）下按 F：在光标位置快速点两下，方便拾取/打开物品。
        if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0 &&
            keyEvent.keyCode == KeyEvent.KEYCODE_F && activePlayMode.assists &&
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
            fInteractArmedAt = 0L
            fScrollControlActive = false
        }
        // 物理 ESC / Tab：无论是否开启「回瞄准」，都先解除滚轮操控，回到原来的滚轮映射。
        if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0 &&
            (keyEvent.keyCode == KeyEvent.KEYCODE_ESCAPE || keyEvent.keyCode == KeyEvent.KEYCODE_TAB)
        ) {
            fScrollControlActive = false
        }
        // 物理 ESC / Tab：关闭背包、地图后延迟回瞄准；不吞按键，游戏照常收到。
        if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0 &&
            tabEscAimEnabled.value &&
            (keyEvent.keyCode == KeyEvent.KEYCODE_ESCAPE || keyEvent.keyCode == KeyEvent.KEYCODE_TAB) &&
            // 配了批量落点的键由脚本跑完后再回瞄准，避免准心先于落点重建。
            !hasBatchExtraTap(keyEvent.keyCode)
        ) {
            // 用户已主动回射击模式，F 的唤出光标窗口立刻作废。
            fInteractArmedAt = 0L
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
        // 快速近战宏：默认 Alt+0，按住循环「近战键 → 延迟 → 1 号键」，松开立即停。
        if (runQuickMeleeMacro(keyEvent)) return true
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
        // 批量落点：按键松开后才真正开始点击，延迟从松开时刻算起。
        if (keyEvent.action == KeyEvent.ACTION_UP) {
            flushPendingExtraTap(keyEvent.keyCode)
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
                // 例外：如果开局锁成了绝对差值，但之后设备开始提供非零相对轴，
                // 允许中途升级为相对轴——相对轴永远比绝对差值更可信。
                if (latchedRelativeAxisMode == false && rawRelativeAxes) {
                    latchedRelativeAxisMode = true
                }
                val relativeAxes = if (mouseModeLatch.value && mouseGuardEnabled.value) {
                    latchedRelativeAxisMode
                        ?: rawRelativeAxes.also { latchedRelativeAxisMode = it }
                } else rawRelativeAxes
                InputStats.setMouseAxisMode(
                    when {
                        relativeMouse -> "relative"
                        relativeAxes -> "axis"
                        else -> "raw"
                    }
                )
                // 总开关关闭时不限幅，也不丢弃跳变，完全按原始位移处理。
                val maxDelta = if (mouseGuardEnabled.value) {
                    mouseJumpThresholdPx.value.toFloat()
                } else {
                    Float.MAX_VALUE
                }
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
                if (handleScrollControl(codes)) return true
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
            TrajectoryRecorder.recordKey(key, pressed)
            _pressedKeys.value = if (pressed) _pressedKeys.value + key else _pressedKeys.value - key
            _lastInputLabel.value = key.keyCodeToString() + if (pressed) " 按下" else " 松开"
        }
        // F 交互后窗口时间内首次左键或中键：先切出光标，再转发这一下按键，
        // 让它按「光标模式」在光标位置点击；触发后立刻清空时间戳，一轮只生效一次。
        if (pressed &&
            (button == MotionEvent.BUTTON_PRIMARY || button == MotionEvent.BUTTON_TERTIARY) &&
            fCursorClickEnabled.value &&
            activePlayMode.fCursor &&
            isShootingMode.value && fInteractArmedAt > 0L &&
            SystemClock.uptimeMillis() - fInteractArmedAt <= fCursorClickWindowMs.value
        ) {
            fInteractArmedAt = 0L
            mouseCursorSeen = true
            eventHandler.setShootingModeEnabled(false)
            // 进入光标模式的同时打开滚轮操控；左右滚动锚点有缺失时滚轮会自动回落到原映射。
            fScrollControlActive = fScrollControlEnabled.value && activePlayMode.scrollControl
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
     * Some synthetic events cannot call MotionEvent.setActionButton (hidden API),
     * so they encode that one button in buttonState as an internal fallback.
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
