package com.devoid.keysync.domain

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.nativeKeyCode
import com.devoid.keysync.model.ClickMacro
import com.devoid.keysync.model.AppConfig
import com.devoid.keysync.model.DraggableItem
import com.devoid.keysync.model.DraggableItemType
import com.devoid.keysync.model.EventInjector
import com.devoid.keysync.model.KeyMap
import com.devoid.keysync.model.KeymapType
import com.devoid.keysync.model.MultiModeTouchHandler
import com.devoid.keysync.data.mapping.MappingConflictDetector
import com.devoid.keysync.model.TouchMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random


private const val MASK_W = 1 shl 0
private const val MASK_A = 1 shl 1
private const val MASK_S = 1 shl 2
private const val MASK_D = 1 shl 3
// 疾跑修饰位（Shift）。与 W/A/S/D 方向位一起组成 wasdMask 的键，
// 例如 MASK_W or MASK_SPRINT 表示“向左前方疾跑”。
private const val MASK_SPRINT = 1 shl 9

// Internal bindings must never live in Android's KeyEvent namespace.  The old
// values (32/64/128/256) are all valid Android key codes, so a real keyboard
// key could silently alias a joystick/mouse binding.  Use a negative namespace
// for virtual devices instead.  Stored legacy mouse codes are migrated by
// FloatingWindowStateManager.
const val KEYCODE_WASD = -10_001
const val KEYCODE_LMC = -10_002
const val KEYCODE_RMC = -10_003
const val KEYCODE_MMC = -10_004


private fun keyToWasdMask(keyCode: Int): Int = when (keyCode) {
    Key.W.nativeKeyCode -> MASK_W
    Key.A.nativeKeyCode -> MASK_A
    Key.S.nativeKeyCode -> MASK_S
    Key.D.nativeKeyCode -> MASK_D
    else -> 0
}

private fun keyToSprintMask(keyCode: Int): Int = when (keyCode) {
    KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> MASK_SPRINT
    else -> 0
}

class EventHandler(
    private val eventInjector: EventInjector
) {

    // Macro touches use a separate logical id and never recursively synthesize keyboard events.
    private val macroPointerId = 1_000_000
    /** 点击宏在独立线程上调度，避免大量步骤和主线程的悬浮层重绘抢帧。 */
    private val macroThread = HandlerThread("key-sync-macro").apply { start() }
    private val macroHandler = Handler(macroThread.looper)
    private val macroRunner = ClickMacroRunner(
        schedule = { delay, action -> macroHandler.postDelayed(action, delay.coerceAtLeast(0L)) },
        down = { position -> eventInjector.injectPointer(macroPointerId, position) },
        up = { eventInjector.releasePointer(macroPointerId) },
    )
    private var macros = emptyList<ClickMacro>()
    private var macroViewport = Offset.Zero
    private var macroOrigin = Offset.Zero
    private val scrollPulse = TapPulse(schedule = { delay, action -> mainHandler.postDelayed({ action() }, delay) })

    // 连按落点使用独立触点号段：不与宏、普通按键、摇杆的 pointer id 冲突。
    private val extraTapPointerBase = 1_200_000
    private var extraTapCursor = 0
    private val extraTapPulse = TapPulse(schedule = { delay, action -> mainHandler.postDelayed({ action() }, delay) })

    /** 在指定屏幕坐标补一次点击（连按落点）。不阻塞按键路径，抬起时刻由 TapPulse 采样。 */
    fun tapExtraPoint(position: Offset) {
        val pointer = extraTapPointerBase + (extraTapCursor++ % 8)
        extraTapPulse.tap(
            pointer,
            down = { eventInjector.injectPointer(pointer, position) },
            up = { eventInjector.releasePointer(pointer) },
        )
    }

    /**
     * 在指定屏幕坐标快速点两下（光标模式下 F 键）。
     *
     * 复用同一个 pointer：第二下调用 tap() 会先抬起上一段未结束的触点，
     * 再重新按下，保证「抬起 → 短间隔 → 按下」的顺序，不会变成双指同时按。
     */
    fun doubleTapExtraPoint(position: Offset, gapMs: Long = 90L) {
        val pointer = extraTapPointerBase + (extraTapCursor++ % 8)
        val tapOnce = {
            extraTapPulse.tap(
                pointer,
                down = { eventInjector.injectPointer(pointer, position) },
                up = { eventInjector.releasePointer(pointer) },
            )
        }
        tapOnce()
        mainHandler.postDelayed({ tapOnce() }, gapMs)
    }

    // 拖拽丢弃使用独立触点号段，避免和摇杆/宏/按键触点互相覆盖。
    private val dragPointerBase = 1_300_000
    private var dragPointerCursor = 0

    fun allocateDragPointer(): Int = dragPointerBase + (dragPointerCursor++ % 4)

    /** 已按下、尚未松开的触点，保证重复调用释放是幂等的。 */
    private val activeDragPointers = HashSet<Int>(8)

    /** 拖拽起点：按住左键（在游戏里表现为按下不放）。 */
    fun beginCursorDrag(pointerId: Int, position: Offset) {
        activeDragPointers.add(pointerId)
        eventInjector.injectPointer(pointerId, position)
    }

    /** 拖拽中途：绝对定位到新位置，保持按下状态。 */
    fun moveCursorDrag(pointerId: Int, position: Offset) {
        eventInjector.transFormGesture(pointerId, position)
    }

    /** 松开左键；取消/异常路径也必须调用，重复调用不会再次释放。 */
    fun endCursorDrag(pointerId: Int) {
        if (activeDragPointers.remove(pointerId)) eventInjector.releasePointer(pointerId)
    }

    // 快速切枪宏使用独立触点号段，和拖拽/按键触点错开。
    private val seqPointerBase = 1_400_000
    private var seqPointerCursor = 0

    fun allocateSeqPointer(): Int = seqPointerBase + (seqPointerCursor++ % 8)

    /** 宏触点是否仍在按下；防止 UP 步骤与 finally 重复释放同一个触点。 */
    private val activeSeqPointers = HashSet<Int>(16)

    /** 宏的一步：在指定位置按下，不经过任何按键模式/换位逻辑。 */
    fun pressSeqPointer(pointerId: Int, position: Offset) {
        activeSeqPointers.add(pointerId)
        eventInjector.injectPointer(pointerId, position)
    }

    fun releaseSeqPointer(pointerId: Int) {
        if (activeSeqPointers.remove(pointerId)) eventInjector.releasePointer(pointerId)
    }

    fun setMacros(value: List<ClickMacro>, viewport: Offset, origin: Offset) {
        macroRunner.cancel()
        macros = value
        macroViewport = viewport
        macroOrigin = origin
    }

    /** 模式切换时停掉正在跑的点击宏。 */
    fun cancelClickMacros() {
        macroRunner.cancel()
    }

    fun isMacroTrigger(key: Int): Boolean =
        macrosAllowed && macros.any { it.enabled && it.triggerKeyCode == key }

    private fun macroEdge(key: Int, pressed: Boolean, repeat: Boolean = false): Boolean =
        macrosAllowed && macroRunner.handle(key, pressed, repeat, macros, macroViewport, macroOrigin)

    /** A wheel direction is a tap, regardless of a button's hold/toggle mode. */
    fun handleScrollBinding(key: Int): Boolean {
        if (macroEdge(key, true)) { macroEdge(key, false); return true }
        if (key == shootingModeKeyCode) { toggleShootingMode(); return true }
        val mapping = keyMap[key] ?: return false
        val pointer = pointerIds[key] ?: return false
        scrollPulse.tap(pointer,
            down = { eventInjector.injectPointer(pointer, mapping.position) },
            up = { eventInjector.releasePointer(pointer) })
        return true
    }

    /* ---------- state ---------- */

    // WASD masks (1,2,4,8 and their combinations) and real Android key codes
    // used to share one HashMap. They overlap — KEYCODE_BACK is 4 (= MASK_S) and
    // KEYCODE_1 is 8 (= MASK_D) — so a weapon-1 button silently overwrote a
    // joystick direction and vice versa. Keeping the two key spaces apart is
    // what lets the joystick and the action keys stay down at the same time.
    private val wasdMap = HashMap<Int, KeyMap>(16)
    private val keyMap = HashMap<Int, KeyMap>(64)
    private val cancelKeyMap = HashMap<Int, KeyMap>(32)

    // Do not assume Android key codes fit a fixed array. External keyboards and
    // vendor-specific keys can legally use values outside the old 338-entry range.
    private val pressedKeyCodes = HashSet<Int>(64)
    private val shootingPress = TogglePress()
    private val heldMouseButtons = mutableSetOf<Int>()
    private var mouseHoldKeys: Set<Int> = emptySet()
    private val releaseSwitch = ReleaseSwitch(
        now = { SystemClock.uptimeMillis() },
        schedule = { delay, action -> mainHandler.postDelayed({ action() }, delay) },
    )
    private val _walkEnabled = MutableStateFlow(false)
    val walkEnabled = _walkEnabled.asStateFlow()
    private var walkKeyCode: Int? = null
    private var lastWalkPosition: Offset? = null
    private val walkToggle = WalkToggle(
        schedule = { delay, action -> mainHandler.postDelayed({ action() }, delay) },
        changed = { _walkEnabled.value = it },
    )
    fun calibrateWalkOff() { walkToggle.calibrateOff(); eventInjector.clear() }

    /** Shift 是否取消我们自己开启的静步；由当前预设的按键辅助开关控制。 */
    @Volatile
    var shiftCancelWalkEnabled: Boolean = true

    /** 鼠标活动范围（大方形，屏幕坐标）：中心 + 半边长；half <= 0 表示沿用整屏。 */
    @Volatile
    var mouseAreaCenter: Offset = Offset.Zero
    @Volatile
    var mouseAreaHalfSize: Float = 0f

    /** 射击模式每次回中/重新按下时的随机落点范围（小方形，屏幕坐标）。 */
    @Volatile
    var landingAreaCenter: Offset = Offset.Zero
    @Volatile
    var landingAreaHalfSize: Float = 0f

    /** 进入光标模式时光标的落点（悬浮层坐标）；null = 回退到准心按钮位置。 */
    var cursorSpawnPosition: Offset? = null


    private fun triggerWalk(sprint: Boolean = false) {
        val key = walkKeyCode ?: -10_005
        val pointer = pointerIds[key] ?: return
        val position = keyMap[key]?.position ?: lastWalkPosition ?: return
        lastWalkPosition = position
        val down = { eventInjector.injectPointer(pointer, position) }
        val up = { eventInjector.releasePointer(pointer) }
        if (sprint) walkToggle.sprint(down, up) else walkToggle.toggle(down, up)
    }

    private val mappingPressed = HashMap<Int, Boolean>(64)
    private val cancelToggle = HashMap<Int, Boolean>(32)
    private val pointerIds = HashMap<Int, Int>(64)

    private val sprintGate = SprintGate()
    private var wasdMask = 0
    /** WASD 方向键按下顺序（位掩码），用于 A+D / W+S 同时按住时让最后按下的方向生效。 */
    private val wasdDirectionOrder = ArrayDeque<Int>()
    private val joystickMotion = JoystickMotion(
        MASK_SPRINT,
        release = { eventInjector.releaseGesture(it) },
        start = { id, center, target -> eventInjector.injectGesture(id, center, target) },
        move = { id, target -> eventInjector.transFormGesture(id, target) },
    )

    private val keyIdMap = HashMap<Int, Int>(64)
    private var nextKeyId = 0


    /* ---------- 按键拟人化（随机偏移） ---------- */
    // 全局生效、不按预设存储。仅对注入位置加随机抖动，绝不加延迟、不阻塞，
    // 用于对抗把「死板的 8 向摇杆 / 机械重复点按」识别成脚本的风控。
    @Volatile
    var wasdHumanization = false
    @Volatile
    var keyHumanization = false

    /** 偏移强度，1–100（100 = 最大值）。内部统一用 [strengthFactor] 换算成 0–1。 */
    @Volatile
    var humanizationStrength = 50f

    /** WASD 方向晃动幅度，1–100（100 = 最大 ±1.5°）。与整体强度分开，便于单独压小前进晃动。 */
    @Volatile
    var wasdSwayStrength = 50f

    /** 玩法模式门控：简易模式关闭点击宏。 */
    @Volatile
    var macrosAllowed = true

    /** 玩法模式门控：简易模式关闭拟人化抖动。 */
    @Volatile
    var humanizationAllowed = true

    private val strengthFactor: Float
        get() = if (!humanizationAllowed) 0f else (humanizationStrength / 100f).coerceIn(0f, 1f)

    private val swayFactor: Float
        get() = if (!humanizationAllowed) 0f else (wasdSwayStrength / 100f).coerceIn(0f, 1f)

    /**
     * 抖动间隔（ms）。以前固定 100–220ms，几乎每帧都在动，实际用起来会干扰瞄准；
     * 现在改为可配置，默认 1000ms（约每秒一次），只在按住期间偶尔扰动一次。
     */
    @Volatile
    var humanizationIntervalMs = DEFAULT_HUMANIZATION_INTERVAL_MS

    /** 换向时沿圆弧补中间点，避免 8 向摇杆瞬移。全局设置，默认开启。 */
    @Volatile
    var wasdTransitionEnabled = true

    /** 换向过渡时长（ms），默认 80。 */
    @Volatile
    var wasdTransitionMs = DEFAULT_WASD_TRANSITION_MS

    fun setWasdTransition(enabled: Boolean, durationMs: Int) {
        wasdTransitionEnabled = enabled
        wasdTransitionMs = durationMs.coerceIn(MIN_WASD_TRANSITION_MS, MAX_WASD_TRANSITION_MS)
        if (!enabled) stopWasdTransition()
    }

    fun setHumanization(wasd: Boolean, keys: Boolean, strength: Float, sway: Float = wasdSwayStrength) {
        wasdHumanization = wasd
        keyHumanization = keys
        humanizationStrength = strength.coerceIn(1f, 100f)
        wasdSwayStrength = sway.coerceIn(1f, 100f)
        // 触点压力/接触面积与位置抖动同源：任一路开启即让触点参数不再是固定值。
        eventInjector.setHumanizeContact(wasd || keys)
        if (!keys) stopAllKeyJitter()
    }

    fun setHumanizationInterval(intervalMs: Long) {
        humanizationIntervalMs = intervalMs.coerceIn(MIN_HUMANIZATION_INTERVAL_MS, MAX_HUMANIZATION_INTERVAL_MS)
    }

    /** WASD 抖动间隔随机波动（默认开启，200–300ms）；关闭时回落到 [humanizationIntervalMs] ±20%。 */
    @Volatile
    var wasdJitterRandomEnabled = true

    @Volatile
    var wasdJitterMinMs = 200

    @Volatile
    var wasdJitterMaxMs = 300

    fun setWasdJitterInterval(enabled: Boolean, minMs: Int, maxMs: Int) {
        wasdJitterRandomEnabled = enabled
        wasdJitterMinMs = minMs.coerceIn(50, 5000)
        wasdJitterMaxMs = maxMs.coerceIn(wasdJitterMinMs, 5000)
    }

    // 持键期间低频率重掷抖动。间隔可配置（默认 1s），并带 ±20% 随机，
    // 避免固定周期本身成为可提取的规律。
    private val humanizeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wasdJitterJob: Job? = null
    private var wasdTransitionJob: Job? = null
    /** 本次按压段使用的推力倍率；换向或重新按下时重采样。 */
    private var pressVariationMask = 0
    private var pressVariation = 1f
    /** 本次落点的额外随机：向外延伸的半径比例与方向偏移（弧度）。 */
    private var pressLandingScale = 1f
    private var pressLandingAngle = 0f
    /** WASD 按住期间的连续摆动状态（角度/半径都做指数平滑，避免逐次跳变）。 */
    private var swayPhase = 0f
    private var swayPhaseTarget = 0f
    private var swayRadius = 1f
    private var swayRadiusTarget = 1f
    private val keyJitterJobs = HashMap<Int, Job>(16)

    /** 拟人化抖动偏移（px）。强度 0 时返回零偏移。 */
    private fun jitterOffset(): Offset {
        // 强度 1–100 换算成 0–1；满强度时最大偏移 8px。
        val radius = strengthFactor * 8f
        if (radius <= 0f) return Offset.Zero
        // 圆内均匀采样：真实手指落点集中在目标中心附近，方形分布会让四个角
        // 概率与中心相同，统计上比圆分布更容易被识别。
        val r = radius * sqrt(Random.nextFloat())
        val angle = Random.nextFloat() * (2 * Math.PI).toFloat()
        return Offset(r * cos(angle), r * sin(angle))
    }

    /** 抖动周期可配置，并在 ±20% 内随机，避免固定间隔形成周期性信号。 */
    private fun nextJitterDelay(): Long {
        val base = humanizationIntervalMs
            .coerceIn(MIN_HUMANIZATION_INTERVAL_MS, MAX_HUMANIZATION_INTERVAL_MS)
        return (base * (0.8f + Random.nextFloat() * 0.4f)).toLong().coerceAtLeast(50L)
    }

    /** WASD 专用间隔：开关打开时在自定义区间内均匀采样，否则沿用通用间隔。 */
    private fun nextWasdJitterDelay(): Long {
        if (!wasdJitterRandomEnabled) return nextJitterDelay()
        val lo = wasdJitterMinMs.coerceIn(50, 5000)
        val hi = wasdJitterMaxMs.coerceIn(lo, 5000)
        return if (hi == lo) lo.toLong()
        else Random.nextLong(lo.toLong(), hi.toLong() + 1)
    }

    /**
     * WASD 抖动的目标点。
     *
     * 摇杆方向点是「从中心向外推动的位移」。如果按各向同性随机偏移，强度一高就会
     * 把触点拉回摇杆死区，游戏判定为没有推动 —— 表现为「强度往上调就完全不动」。
     * 因此这里只旋转一个极小角度，并允许半径 ±2%~±8% 的变化，且绝不低于原距离的
     * 97%，保证方向与推动量始终有效。
     *
     * 角度/半径用 [swayPhase] / [swayRadius] 的慢速漂移，而不是每次重新随机，
     * 这样按住时是连续的轻微摆动，不会每 200ms 跳一下。
     */
    private fun jitterTarget(center: Offset, target: Offset): Offset {
        // WASD 晃动只看独立的「方向晃动」参数，不再跟着整体偏移强度放大，
        // 这样前进时可以只留很小幅度的摆头。
        val sway = swayFactor
        if (sway <= 0.001f) return target
        val delta = target - center
        if (delta.getDistance() <= 0.5f) return target
        val cosA = cos(swayPhase)
        val sinA = sin(swayPhase)
        val scale = swayRadius.coerceAtLeast(0.99f)
        val rotated = Offset(delta.x * cosA - delta.y * sinA, delta.x * sinA + delta.y * cosA)
        return center + rotated * scale
    }

    /**
     * 推进一次低频漂移：角度朝一个新的随机目标缓慢靠近（指数平滑），
     * 而不是直接跳到随机值；半径同理。
     */
    private fun advanceSway() {
        val sway = swayFactor
        if (sway <= 0.001f) return
        val maxAngle = (1.5f * sway) * (Math.PI.toFloat() / 180f)
        swayPhase += (swayPhaseTarget - swayPhase) * 0.35f
        if (abs(swayPhaseTarget - swayPhase) < maxAngle * 0.2f) {
            swayPhaseTarget = (Random.nextFloat() * 2f - 1f) * maxAngle
        }
        swayRadius += (swayRadiusTarget - swayRadius) * 0.3f
        if (abs(swayRadiusTarget - swayRadius) < 0.0015f) {
            swayRadiusTarget = 1f - Random.nextFloat() * 0.006f * sway
        }
    }

    private fun stopWasdJitter() {
        wasdJitterJob?.cancel()
        wasdJitterJob = null
    }

    private fun stopWasdTransition() {
        wasdTransitionJob?.cancel()
        wasdTransitionJob = null
    }

    /**
     * 把触点从 [start] 平滑移到 [end]。
     *
     * 真人换向时拇指是贴着摇杆外圈划过去，所以有半径的换向用**圆弧**，
     * 而不是可能横穿中心的直线/浅弧；Shift 推杆（同方向加半径）与从中心
     * 起按则用轻微侧弯的二次贝塞尔。首尾点严格落在 [start] / [end]。
     *
     * 中间点按路程长度采样（不是固定步数），大角度换向会补更多点；
     * 时间轴上再用 smoothstep 缓动，避免匀速机械感。
     */
    private fun startWasdTransition(
        pointerId: Int,
        mask: Int,
        center: Offset,
        start: Offset,
        end: Offset,
    ) {
        stopWasdTransition()
        val baseDuration = wasdTransitionMs.coerceIn(MIN_WASD_TRANSITION_MS, MAX_WASD_TRANSITION_MS)
        val chord = end - start
        val chordLength = chord.getDistance()
        if (chordLength < 1f) {
            joystickMotion.finishMove(pointerId, mask, end)
            return
        }
        val turn = directionTurn(center, start, end)
        val startRadius = (start - center).getDistance()
        val endRadius = (end - center).getDistance()
        val hasRadius = startRadius > 1f && endRadius > 1f
        // 有半径且不是同向推杆时才贴圈画弧；从中心起按仍是贝塞尔，但弯得更明显。
        val useArc = hasRadius && turn >= SMALL_TURN_RAD
        val bowSign = if (Random.nextBoolean()) 1f else -1f
        val arcScale = if (useArc) 0.94f + Random.nextFloat() * 0.06f else 1f
        val bowRatio = when {
            useArc -> 0f
            !hasRadius -> 0.38f + Random.nextFloat() * 0.14f   // 从中心起按
            else -> 0.20f + Random.nextFloat() * 0.14f          // Shift 推杆
        }
        val normal = Offset(-chord.y / chordLength, chord.x / chordLength) *
            (chordLength * bowRatio * bowSign)
        val control = (start + end) / 2f + normal
        // 大角度换向的弧长更长，需要更多中间点，否则回放里会被拉成折线。
        val pathLength = if (useArc) {
            abs(turn) * maxOf(startRadius, endRadius)
        } else {
            chordLength * 1.35f
        }
        val steps = kotlin.math.ceil(pathLength / TRANSITION_SEGMENT_PX)
            .toInt().coerceIn(6, 18)
        // 大换向略延长过渡，让弧线有足够时间铺开；仍在可调范围内。
        val duration = (baseDuration * (1f + 0.35f * (turn / PI))).toInt()
            .coerceIn(baseDuration, MAX_WASD_TRANSITION_MS)
        wasdTransitionJob = humanizeScope.launch {
            val stepDelay = (duration / steps).coerceAtLeast(6).toLong()
            for (i in 1..steps) {
                delay(stepDelay)
                if (!isActive) return@launch
                if (i == steps) {
                    // 最后一帧必须精确落到目标点；jitter 会因为目标相同而跳过。
                    joystickMotion.finishMove(pointerId, mask, end)
                } else {
                    val raw = i.toFloat() / steps
                    // smoothstep：两端慢、中段快，接近人手推杆的加减速。
                    val t = raw * raw * (3f - 2f * raw)
                    val point = if (useArc) {
                        arcPoint(center, start, end, t, arcScale, bowSign)
                    } else {
                        bezierPoint(start, control, end, t)
                    }
                    joystickMotion.jitter(pointerId, mask, point)
                }
            }
        }
    }

    private fun bezierPoint(start: Offset, control: Offset, end: Offset, t: Float): Offset {
        val u = 1f - t
        return start * (u * u) + control * (2f * u * t) + end * (t * t)
    }

    /**
     * 贴外圈的圆弧过渡：角度从起点扫到终点，半径在两端保持精确值，
     * 中段最多内收 [arcScale]（6% 以内）模拟拇指的轻微内切。
     */
    private fun arcPoint(
        center: Offset,
        start: Offset,
        end: Offset,
        t: Float,
        arcScale: Float,
        sweepSign: Float,
    ): Offset {
        val startDelta = start - center
        val endDelta = end - center
        val startRadius = startDelta.getDistance()
        val endRadius = endDelta.getDistance()
        if (startRadius < 1f || endRadius < 1f) return start + (end - start) * t
        val startAngle = atan2(startDelta.y, startDelta.x)
        val endAngle = atan2(endDelta.y, endDelta.x)
        var sweep = endAngle - startAngle
        while (sweep > PI) sweep -= (2 * PI).toFloat()
        while (sweep <= -PI) sweep += (2 * PI).toFloat()
        // 正好 180° 时两个方向等价，由本次采样的 sweepSign 决定走上边还是下边。
        if (abs(abs(sweep) - PI) < 0.02f) sweep = if (sweepSign > 0) PI.toFloat() else -PI.toFloat()
        val angle = startAngle + sweep * t
        val baseRadius = startRadius + (endRadius - startRadius) * t
        val radius = baseRadius * (1f - (1f - arcScale) * sin((PI * t).toFloat()))
        return center + Offset(cos(angle) * radius, sin(angle) * radius)
    }

    /** 起点/终点方向之间的夹角；从摇杆中心起按时按大角度处理（弯得更明显）。 */
    private fun directionTurn(center: Offset, start: Offset, end: Offset): Float {
        val a = start - center
        val b = end - center
        val ra = a.getDistance()
        val rb = b.getDistance()
        if (ra < 1f || rb < 1f) return PI.toFloat()
        val cosValue = ((a.x * b.x + a.y * b.y) / (ra * rb)).coerceIn(-1f, 1f)
        return acos(cosValue)
    }

    // 方向落点偏置由 biasedDirection() 在换向时采样，这里只保留记录以便调试。
    // （无状态依赖，删除字段后行为不变。）

    /**
     * 给离散的 8 向方向点加上一次性角度/半径偏置，让每次按下的方向不再精确落在
     * 0°/45°/90°，消除「永远正北/正东北」这种机械特征。
     */
    private fun biasedDirection(center: Offset, point: Offset): Offset {
        // 与 jitterTarget 共用「WASD 方向晃动」参数，保证调小后方向点也一起收敛。
        val sway = swayFactor
        if (sway <= 0.001f) return point
        val delta = point - center
        if (delta.getDistance() <= 0.5f) return point
        // 满幅度也只有 ±1.2°，半径最多 -0.8%，
        // 不会把 W 的推动量压到游戏的慢走/死区里。
        val maxBiasDeg = 1.2f * sway
        val rad = (Random.nextFloat() * 2f - 1f) * maxBiasDeg * (Math.PI.toFloat() / 180f)
        val radius = 1f - Random.nextFloat() * 0.008f * sway
        val cosA = cos(rad)
        val sinA = sin(rad)
        val rotated = Offset(delta.x * cosA - delta.y * sinA, delta.x * sinA + delta.y * cosA)
        return center + rotated * radius
    }

    private fun restartWasdJitter(
        pointerId: Int,
        mask: Int,
        map: KeyMap,
        baseTarget: Offset,
    ) {
        wasdJitterJob?.cancel()
        wasdJitterJob = humanizeScope.launch {
            while (isActive && wasdHumanization) {
                delay(nextWasdJitterDelay())
                if (!wasdHumanization) break
                // 只拖 current 触点，绝不 reanchor（见 JoystickMotion.jitter）。
                val center = map.center ?: return@launch
                advanceSway()
                val target = jitterTarget(center, baseTarget)
                joystickMotion.jitter(pointerId, mask, target)
            }
        }
    }

    /**
     * 持键期间的触点微抖。
     *
     * 只对「单点落位」的按键生效：滑动类按键（position != end）的落点本身就在
     * 矩形内随机，再按锚点抽动会把触点拽回角落，破坏滑动语义。
     * 只发 MOVE，绝不重新 DOWN —— 重新按下会打断连发/开火状态。
     */
    private fun startKeyJitter(pointerId: Int, map: KeyMap) {
        stopKeyJitter(pointerId)
        if (!keyHumanization || strengthFactor <= 0.001f) return
        if (map.end != map.position) return
        val anchor = map.position
        keyJitterJobs[pointerId] = humanizeScope.launch {
            while (isActive) {
                delay(nextJitterDelay())
                if (!keyHumanization || strengthFactor <= 0.001f) break
                eventInjector.transFormGesture(pointerId, anchor + jitterOffset())
            }
        }
    }

    private fun stopKeyJitter(pointerId: Int) {
        keyJitterJobs.remove(pointerId)?.cancel()
    }

    private fun stopAllKeyJitter() {
        keyJitterJobs.values.forEach { it.cancel() }
        keyJitterJobs.clear()
    }

    /**
     * TAP 的松手由脉冲定时器负责，触点不是「按住」状态，抽动会打乱点击节奏；
     * 其它模式只要 handler 报告仍在按下就持续微抖。
     */
    private fun syncHeldJitter(pointerId: Int, pressed: Boolean, mode: TouchMode, map: KeyMap) {
        if (pressed && mode != TouchMode.TAP) startKeyJitter(pointerId, map) else stopKeyJitter(pointerId)
    }

    /** TAP 按下与抬起之间补一帧小幅位移：真实手指不会在两次采样之间完全静止。 */
    private fun tapWiggle(pointerId: Int, map: KeyMap) {
        if (!keyHumanization || strengthFactor <= 0.001f) return
        // 与 jitterOffset 同一尺度：满强度 1.2px 以内。
        val max = 0.3f + strengthFactor * 0.9f
        val dx = (Random.nextFloat() * 2f - 1f) * max
        val dy = (Random.nextFloat() * 2f - 1f) * max
        eventInjector.transFormGesture(pointerId, map.position + Offset(dx, dy))
    }

    /** 普通按键的触点位置加随机抖动（仅当「其他按键拟人化」开启时）。 */
    private fun humanizedMap(map: KeyMap): KeyMap {
        if (!keyHumanization || strengthFactor <= 0.001f) return map
        val j = jitterOffset()
        return map.copy(position = map.position + j, end = map.end?.plus(j))
    }


    @Volatile
    private var shootingMode = false
    private val _shootingModeFlow = MutableStateFlow(false)
    val shootingModeFlow = _shootingModeFlow.asStateFlow()

    /**
     * The hardware keyCode (e.g. KEYCODE_GRAVE for the backtick) that
     * is bound to the on-screen "shooting mode" toggle. Resolved from the
     * current items' SHOOTING_MODE FixedKey during [updateKeyMapping], falling
     * back to [KeyEvent.KEYCODE_GRAVE] when no such button exists. Kept here as a
     * dedicated field (rather than read from AppConfig) so the user can
     * rebind the toggle from the floating window itself.
     */
    @Volatile
    var shootingModeKeyCode: Int = KeyEvent.KEYCODE_GRAVE
        private set

    // 射击模式瞄准触点：从准心（SHOOTING_MODE 按钮位置）出发，随鼠标相对位移移动。
    // 触点拖到屏幕边缘时自动「抬按回中」到准心。拖拽式视角的游戏（三角洲/和平精英等）
    // 读到的是触点位移增量，若直接瞬移回中会反向甩视角；只有「抬起 + 在准心重按」才能在
    // 不反向甩的前提下获得持续转向能力（等价于 PC 上鼠标移到鼠标垫边缘后抬起重放）。
    // 熊猫映射的 sight 准星正是这个机制，还配有「屏幕边缘抬起延迟」/「鼠标活动区域」。
    private var aimPosition = Offset.Zero
    private var aimCenter = Offset.Zero

    // 屏幕尺寸（width, height），由 FloatingWindowStateManager 注入。为 0 时退化为
    // 不回中（保持旧行为），避免在未初始化时误判越界。
    var screenSize: Offset = Offset.Zero

    // 回中边界留白（px）：触点在距屏幕边缘此距离内即回中，避免触点压到物理边缘像素。
    private var aimEdgeMargin = 8f

    // 回中节流：两次回中之间的最小间隔（ms）。快速甩鼠标（高灵敏度）时触点连续越界，
    // 若不限流就会高频「抬按回中」，aim 触点反复 POINTER_UP/DOWN，多指↔单指切换会触发
    // 游戏重置摇杆触点（WASD 断触）。正常滑动回中频率远低于此间隔，不受影响。
    private val recenterMinIntervalMs = 50L
    private var lastRecenterTime = 0L

    /**
     * 越界后没发出去的位移。
     *
     * 快速甩动时一次位移可能远超活动范围；直接丢弃会让视角「少转一截」。这里把溢出量
     * 缓存起来，回中后从新落点继续补发，等价于手游玩家「滑一下→抬手→再滑一下」的
     * 分片滑动。为避免无限追帧，单次最多缓存 [MAX_AIM_OVERFLOW_FACTOR] 个半宽。
     */
    private var pendingAimOverflow = Offset.Zero
    private var overflowDrainJob: Job? = null

    var onCursorRecenter: ((Offset) -> Unit)? = null
    var mousePointerPosition = Offset.Zero
    var appConfig = AppConfig.Default

    // 全局默认模式（由 appConfig 配置）。setCancelableTouchMode / setNormalBtnTouchMode
    // 仍会更新这里，供 touchMode == null 的按键回退。
    private var cancelableTouchMode: TouchMode = TouchMode.TAP
    private var normalTouchMode: TouchMode = TouchMode.TAP

    // 按需创建的 handler 缓存，供每键独立模式复用。
    private val normalHandlers = HashMap<TouchMode, MultiModeTouchHandler>()
    private val cancelableHandlers = HashMap<TouchMode, MultiModeTouchHandler>()

    // keyCode -> 该键覆盖的触摸模式（在 updateKeyMapping 里填充，null 键不出现）。
    private val keyTouchMode = HashMap<Int, TouchMode>()

    /* ---------- wheel (轮盘) state ---------- */

    private var wheelActive = false
    private var wheelPointerId = -1
    private var wheelCenter = Offset.Zero
    private var wheelAngleDeg = 0f
    // 轮盘触点绕中心旋转的半径（px）。滚轮每格转动 wheelStepDeg 度。
    private var wheelRadius = 50f
    private var wheelControlPointerId = -1
    private var wheelOffset = Offset.Zero
    private val wheelRadii = mutableMapOf<Int, Float>()
    /** 每个轮盘按钮的旋转中心相对按钮中心的偏移。 */
    private val wheelCenterOffsets = mutableMapOf<Int, Offset>()
    private val _wheelCursor = MutableStateFlow<Offset?>(null)
    val wheelCursor = _wheelCursor.asStateFlow()
    val isWheelActive: Boolean get() = wheelActive
    val wheelCenterPosition: Offset get() = wheelCenter
    val wheelRadiusPx: Float get() = wheelRadius
    private val wheelStepDeg = 30f

    private fun keyId(keyCode: Int): Int =
        keyIdMap.getOrPut(keyCode) { nextKeyId++ }

    /* ---------- touch mode ---------- */

    fun setCancelableTouchMode(mode: TouchMode) {
        cancelableTouchMode = mode
    }

    fun setNormalBtnTouchMode(mode: TouchMode) {
        normalTouchMode = mode
    }

    private fun normalHandler(mode: TouchMode): MultiModeTouchHandler =
        normalHandlers.getOrPut(mode) {
            when (mode) {
                TouchMode.TAP -> TapModeTouchHandler(eventInjector, ::tapWiggle)
                TouchMode.HOLD -> HoldModeTouchHandler(eventInjector)
                TouchMode.MIXED -> MixedModeTouchHandler(eventInjector)
                TouchMode.WHEEL -> throw IllegalArgumentException("WHEEL is handled by the wheel state machine")
            }
        }

    private fun cancelableHandler(mode: TouchMode): MultiModeTouchHandler =
        cancelableHandlers.getOrPut(mode) {
            when (mode) {
                TouchMode.TAP -> CancelableTapModeTouchHandler(eventInjector)
                TouchMode.HOLD -> CancelableHoldModeTouchHandler(eventInjector)
                TouchMode.MIXED -> CancelableMixedModeTouchHandler(eventInjector)
                TouchMode.WHEEL -> throw IllegalArgumentException("WHEEL is handled by the wheel state machine")
            }
        }



    private fun setShootingMode(enabled: Boolean) {
        if (shootingMode == enabled) return

        shootingMode = enabled
        _shootingModeFlow.value = enabled
    }


    /** Snapshot of the key codes that are physically held down right now. */
    fun heldKeyCodes(): Set<Int> = pressedKeyCodes.toSet()

    /**
     * Re-issues a press for keys that are still physically held after the
     * mapping was replaced by a profile switch.
     *
     * [updateKeyMapping] lifts every contact, so without this a held W would
     * silently stop working until the user released and pressed it again —
     * very visible when switching profile mid-run.
     */
    fun replayHeldKeys(keyCodes: Collection<Int>) {
        keyCodes.forEach { keyCode ->
            val wasdBit = keyToWasdMask(keyCode)
            val sprintBit = keyToSprintMask(keyCode)
            val hasWasd = pointerIds.containsKey(KEYCODE_WASD)
            val handlesAsWasd = wasdBit != 0 && hasWasd
            when {
                // Explicit Shift mapping wins over the optional WASD sprint
                // modifier. Several bundled presets intentionally bind Shift
                // to an on-screen button while also containing a WASD group.
                sprintBit != 0 && hasWasd && !keyMap.containsKey(keyCode) -> {
                    wasdMask = wasdMask or sprintBit
                    handleWasd(pointerIds[KEYCODE_WASD]!!)
                }

                handlesAsWasd -> {
                    val pointerId = pointerIds[KEYCODE_WASD] ?: return@forEach
                    wasdMask = wasdMask or wasdBit
                    handleWasd(pointerId)
                }

                else -> {
                    val pointerId = pointerIds[keyCode] ?: return@forEach
                    if (keyCode != shootingModeKeyCode && keyCode != walkKeyCode &&
                        (keyTouchMode[keyCode] ?: normalTouchMode) == TouchMode.HOLD) {
                        handleKeyDown(keyCode, pointerId)
                    }
                }
            }
        }
    }

    fun heldMouseButtons(): Set<Int> = heldMouseButtons.toSet()
    fun replayHeldMouseButtons(buttons: Set<Int>) {
        heldMouseButtons.clear()
        buttons.forEach { button ->
            val key = mouseButtonKeyCode(button) ?: return@forEach
            // A profile/layout change must not retrigger a one-shot macro.
            if (key == shootingModeKeyCode || isMacroTrigger(key)) heldMouseButtons.add(button)
            else handleMouseButton(button, true)
        }
    }

    /**
     * Re-presses the shooting-mode contact after a profile switch.
     *
     * Shooting mode keeps a contact down at the SHOOTING_MODE button for as
     * long as it is enabled, and [updateKeyMapping] lifts it along with
     * everything else. Without this the state would claim "shooting mode on"
     * — so [handlePointerMove] stops moving the cursor — while the game no
     * longer sees the aim contact. Aiming stays dead until the user toggles
     * the mode off and back on.
     */
    fun restoreShootingModeContact() {
        if (!shootingMode) return
        val key = shootingModeKeyCode
        val pointerId = pointerIds[key]
        val mapping = keyMap[key]
        if (pointerId == null || mapping == null) {
            // The new profile has no shooting-mode contact. Keeping the flag
            // true would hide/lock the normal cursor while all aim movement is
            // dropped, leaving the user stuck in a mode they cannot use.
            setShootingMode(false)
            aimCenter = Offset.Zero
            aimPosition = Offset.Zero
            return
        }

        // 切换预设会重建映射，重新锚定回中基准到 SHOOTING_MODE 按钮位置（可拖动准心）。
        // 若设置了回中范围，则在范围内随机取点。
        val center = randomLandingPoint(mapping.position)
        aimCenter = center
        aimPosition = center
        eventInjector.injectPointer(pointerId, center)
    }

    /* ---------- key events ---------- */

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
            if (macroEdge(event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.repeatCount > 0)) return true
        }
        if (event.keyCode == walkKeyCode && event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) return true
        val wasdBit = keyToWasdMask(event.keyCode)
        val sprintBit = keyToSprintMask(event.keyCode)
        val hasWasd = pointerIds.containsKey(KEYCODE_WASD)
        val handlesAsWasd = wasdBit != 0 && hasWasd
        val pointerIdKey = if (handlesAsWasd) KEYCODE_WASD else event.keyCode
        val pointerId = pointerIds[pointerIdKey]

        // Shift 起跑时，如果静步是我们自己点开的，补点一次静步键取消掉，
        // 避免「一按 Shift 就变成慢走」。用户手动开的静步不介入。
        if (sprintBit != 0 && event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0 && !pressedKeyCodes.contains(event.keyCode) &&
            shiftCancelWalkEnabled) {
            triggerWalk(sprint = true)
        }

        // Shift 作为 WASD 疾跑修饰键（当存在 WASD 摇杆时）。按下时把疾跑位并入
        // wasdMask，让摇杆沿中心方向延长到疾跑档，而不是触发独立的 SPRINT 按钮。
        if (sprintBit != 0 && hasWasd && !keyMap.containsKey(event.keyCode)) {
            val wasdPointerId = pointerIds[KEYCODE_WASD]!!
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!pressedKeyCodes.add(event.keyCode)) return true
                    sprintGate.shiftPressed()
                    wasdMask = wasdMask or sprintBit
                    handleWasd(wasdPointerId)
                }

                MotionEvent.ACTION_UP -> {
                    pressedKeyCodes.remove(event.keyCode)
                    if (pressedKeyCodes.none { keyToSprintMask(it) != 0 })
                        wasdMask = wasdMask and sprintBit.inv()
                    handleWasd(wasdPointerId)
                }
            }
            return true
        }

        // Nothing mapped: hand the event back to the game.
        if (pointerId == null) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Auto-repeat: only the first DOWN of a hold counts.
                if (event.keyCode == shootingModeKeyCode) {
                    if (!shootingPress.down(event.downTime, event.repeatCount)) return true
                    pressedKeyCodes.add(event.keyCode)
                } else if (!pressedKeyCodes.add(event.keyCode)) return true
                // The press edge belongs to the mapping.
                // is also mapped still triggers its action here, with no
                // added latency.
                if (pointerId != null) {
                    if (handlesAsWasd) {
                        sprintGate.directionPressed(wasdBit == MASK_S)
                        wasdMask = wasdMask or wasdBit
                        wasdDirectionOrder.remove(wasdBit)
                        wasdDirectionOrder.addLast(wasdBit)
                        handleWasd(pointerId)
                    } else {
                        handleKeyDown(event.keyCode, pointerId)
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!pressedKeyCodes.remove(event.keyCode)) return true

                // Release against the mapping that received the press, i.e.
                // the profile we are about to leave.
                if (pointerId != null) {
                    if (handlesAsWasd) {
                        wasdMask = wasdMask and wasdBit.inv()
                        wasdDirectionOrder.remove(wasdBit)
                        handleWasd(pointerId)
                    } else {
                        handleKeyUp(event.keyCode, pointerId)
                    }
                }

                return true
            }
        }
        return false
    }

    /* ---------- WASD ---------- */

    private fun handleWasd(pointerId: Int) {
        // Resolve opposite directions before lookup. W+S and A+D used to have
        // no entry in wasdMap, so the joystick stayed at its previous position
        // until another key changed the mask. Treat opposite axes as neutral.
        val rawDirectionMask = wasdMask and (MASK_W or MASK_A or MASK_S or MASK_D)
        // 同时按住相反方向时不再当成中立：用最后按下的那个方向，避免 A→D 交替时
        // 触点被松开回中、再从原点重新按下（表现为瞬移/直线穿心）。
        val latestVertical = wasdDirectionOrder.lastOrNull { it == MASK_W || it == MASK_S } ?: 0
        val latestHorizontal = wasdDirectionOrder.lastOrNull { it == MASK_A || it == MASK_D } ?: 0
        val vertical = when {
            rawDirectionMask and MASK_W != 0 && rawDirectionMask and MASK_S == 0 -> MASK_W
            rawDirectionMask and MASK_S != 0 && rawDirectionMask and MASK_W == 0 -> MASK_S
            rawDirectionMask and MASK_W != 0 && rawDirectionMask and MASK_S != 0 -> latestVertical
            else -> 0
        }
        val horizontal = when {
            rawDirectionMask and MASK_A != 0 && rawDirectionMask and MASK_D == 0 -> MASK_A
            rawDirectionMask and MASK_D != 0 && rawDirectionMask and MASK_A == 0 -> MASK_D
            rawDirectionMask and MASK_A != 0 && rawDirectionMask and MASK_D != 0 -> latestHorizontal
            else -> 0
        }
        val directionMask = vertical or horizontal
        if (directionMask == 0) {
            stopWasdJitter()
            stopWasdTransition()
            pressVariationMask = 0
            pressVariation = 1f
            joystickMotion.update(pointerId, 0, Offset.Zero, Offset.Zero)
            return
        }

        val sprintAllowed = sprintGate.allowed(wasdMask and MASK_S != 0)
        val requestedMask = directionMask or (if (sprintAllowed) wasdMask and MASK_SPRINT else 0)
        // Profiles with sprintScale <= 1 do not create sprint entries. Shift
        // should not freeze movement in that case; fall back to normal speed.
        val effectiveMask = if (wasdMap.containsKey(requestedMask)) requestedMask else directionMask
        val entry = wasdMap[effectiveMask]
        if (entry == null) {
            stopWasdJitter()
            stopWasdTransition()
            return
        }
        // 每次换向/重新按下都重采样一次推力，真实拇指每次推的幅度并不相同：
        // 疾跑在 1.50–1.68 倍之间（始终超过 +50%）；普通移动只向上浮动 0–8%
        // （幅度随「WASD 晃动」缩放），不会低于基准、掉进游戏的慢走/死区。
        if (pressVariationMask != effectiveMask) {
            pressVariationMask = effectiveMask
            pressVariation = if (effectiveMask and MASK_SPRINT != 0) {
                1f + Random.nextFloat() * SPRINT_VARIATION_RANGE
            } else {
                1f + Random.nextFloat() * WALK_VARIATION_RANGE * swayFactor
            }
            // 落点也带一次随机：半径只向外延伸（避免推进死区），角度只偏 2° 上下，
            // 让每次按下的落点都略有差异，而不是永远精确落在同一个像素上。
            val sprinting = effectiveMask and MASK_SPRINT != 0
            pressLandingScale = if (sprinting) {
                1f + Random.nextFloat() * SPRINT_LANDING_RADIUS_RANGE
            } else {
                1f + Random.nextFloat() * WALK_LANDING_RADIUS_RANGE
            }
            val maxAngleDeg = if (sprinting) SPRINT_LANDING_ANGLE_DEG else WALK_LANDING_ANGLE_DEG
            pressLandingAngle = (Random.nextFloat() * 2f - 1f) * maxAngleDeg * (Math.PI.toFloat() / 180f)
            // 新按压段从「无额外摆动」开始，随后由 advanceSway() 缓慢漂移。
            swayPhase = 0f
            swayPhaseTarget = 0f
            swayRadius = 1f
            swayRadiusTarget = 1f
        }
        val nominal = entry.center!!.let { center ->
            val delta = (entry.position - center) * (pressVariation * pressLandingScale)
            val cosA = cos(pressLandingAngle)
            val sinA = sin(pressLandingAngle)
            center + Offset(delta.x * cosA - delta.y * sinA, delta.x * sinA + delta.y * cosA)
        }
        val target = if (wasdHumanization) {
            // 每次换向时由 biasedDirection 重新采样偏置；再叠加低频微抖。
            jitterTarget(entry.center!!, biasedDirection(entry.center!!, nominal))
        } else nominal
        val directionChanged = joystickMotion.activeMask != effectiveMask
        // 过渡期间同一方向的按键重复（长按 W/D 的系统 repeat）不能把触点提前拉到终点，
        // 否则 80ms 的弧线只走了第一帧就被截断。
        if (!directionChanged && wasdTransitionJob?.isActive == true) return
        val starting = joystickMotion.activeMask == 0
        val actualStart = joystickMotion.target
        val wantsTransition = wasdTransitionEnabled &&
            (directionChanged || starting) &&
            actualStart != target
        // 返回 false 表示 update 把落点交给过渡协程；触点轨迹由注入层统一录制，
        // 过渡的每个中间点都会按真实注入坐标落到轨迹里。
        val center = entry.center!!
        val moved = joystickMotion.update(
            pointerId,
            effectiveMask,
            center,
            target,
            sendTarget = !wantsTransition,
            reanchorOnSprint = !wantsTransition,
            // 从中心起按时先 down 在中心，再由过渡曲线拉到落点；否则
            // injectGesture 会直接「中心 → 落点」瞬移出一条直线。
            startOffset = center,
        )
        if (moved) {
            stopWasdTransition()
        } else {
            startWasdTransition(
                pointerId,
                effectiveMask,
                center,
                if (starting) center else actualStart,
                target,
            )
        }
        if (wasdHumanization) {
            restartWasdJitter(pointerId, effectiveMask, entry, nominal)
        } else stopWasdJitter()
    }

    /* ---------- normal keys ---------- */

    private fun handleKeyDown(keyCode: Int, pointerId: Int) {
        if (keyCode == walkKeyCode) { triggerWalk(); return }
        Log.d("KeySyncInput", "KEY code=$keyCode pointer=$pointerId mapping=${keyMap[keyCode]}")
        keyMap[keyCode]?.let {
            if (keyCode == shootingModeKeyCode) {
                toggleShootingMode()
                return
            }
            if (it.type == KeymapType.CANCELABLE) {
                val handler = cancelableHandler(keyTouchMode[keyCode] ?: cancelableTouchMode)
                val id = keyId(keyCode)
                val wasPressed = cancelToggle[id] ?: false
                cancelToggle[id] = if (wasPressed) {
                    cancelKeyMap[keyCode]?.let { cancelMap ->
                        handler.handleTouchEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode), wasPressed, pointerId, cancelMap)
                    } ?: false
                } else {
                    handler.handleTouchEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode), wasPressed, pointerId, humanizedMap(it))
                }
            } else {
                val mode = keyTouchMode[keyCode] ?: normalTouchMode
                if (mode == TouchMode.WHEEL) {
                    // 轮盘：按住按钮后移动同一触点选择，松开确认。
                    if (wheelActive) return
                    wheelActive = true
                    wheelPointerId = pointerId
                    wheelRadius = (wheelRadii[keyCode] ?: 50f).coerceIn(10f, 500f)
                    // 旋转中心默认就是按钮中心；可在按钮设置里加偏移，让圆心与按钮分离。
                    wheelCenter = it.position + (wheelCenterOffsets[keyCode] ?: Offset.Zero)
                    wheelOffset = Offset.Zero
                    wheelAngleDeg = 0f
                    // Drag the same finger that opened the wheel, around this button.
                    wheelControlPointerId = pointerId
                    mappingPressed[keyId(keyCode)] = true
                    eventInjector.injectPointer(pointerId, wheelCenter)
                    _wheelCursor.value = wheelCenter
                } else {
                    val handler = normalHandler(mode)
                    val id = keyId(keyCode)
                    val wasPressed = mappingPressed[id] ?: false
                    val pressedMap = humanizedMap(it)
                    mappingPressed[id] = handler.handleTouchEvent(
                        KeyEvent(KeyEvent.ACTION_DOWN, keyCode),
                        wasPressed,
                        pointerId,
                        pressedMap
                    )
                    syncHeldJitter(pointerId, mappingPressed[id] ?: false, mode, pressedMap)
                }
            }
        }
    }

    private fun handleKeyUp(keyCode: Int, pointerId: Int) {
        // The toggle owns a persistent aim contact; normal UP handlers must not lift it.
        if (keyCode == walkKeyCode || keyCode == shootingModeKeyCode) return
        stopKeyJitter(pointerId)
        val id = keyId(keyCode)
        keyMap[keyCode]?.let {
            if (it.type == KeymapType.CANCELABLE){
                val handler = cancelableHandler(keyTouchMode[keyCode] ?: cancelableTouchMode)
                val wasPressed = cancelToggle[id] ?: false
                cancelToggle[id] = if (wasPressed) {
                    cancelKeyMap[keyCode]?.let { cancelMap ->
                        handler.handleTouchEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode), wasPressed, pointerId, cancelMap)
                    } ?: false
                } else {
                    handler.handleTouchEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode), wasPressed, pointerId, it)
                }
                return
            }
            val mode = keyTouchMode[keyCode] ?: normalTouchMode
            if (mode == TouchMode.WHEEL) {
                // 松开轮盘键：释放触点，确认当前角度指向的选择。
                if (wheelActive && wheelPointerId == pointerId) {
                    stopWheel()
                }
                mappingPressed[id] = false
            } else {
                val handler = normalHandler(mode)
                val wasPressed = mappingPressed[id] ?: false
                mappingPressed[id] = handler.handleTouchEvent(
                    KeyEvent(KeyEvent.ACTION_UP, keyCode),
                    wasPressed,
                    pointerId,
                    it
                )
            }
        } ?: eventInjector.releasePointer(pointerId)
    }

    /* ---------- mouse ---------- */

    fun handleMouseButton(button: Int, pressed: Boolean): Boolean {
        Log.d("KeySyncInput", "MOUSE button=$button pressed=$pressed shooting=$shootingMode")
        if (pressed) {
            if (!heldMouseButtons.add(button)) return true
        } else if (!heldMouseButtons.remove(button)) return true
        val keyCode = mouseButtonKeyCode(button) ?: return false
        if (macroEdge(keyCode, pressed)) return true
        if (keyCode == shootingModeKeyCode) {
            if (!pressed) return false
            toggleShootingMode()
            return true
        }
        val pointerId = pointerIds[keyCode] ?: return false

        if (!shootingMode) {
            // 光标模式（鼠标已切出瞄准）：左键/右键在光标位置注入一次原生点击，
            // 可以操作游戏/系统 UI。注意这是「点到哪就点哪」——如果光标恰好落在
            // 开火/右键按钮上，游戏会把它当成真的点了那个按钮（这是有意为之，
            // 详见 README「光标模式的按键语义」）。
            if (button == MotionEvent.BUTTON_PRIMARY || button == MotionEvent.BUTTON_SECONDARY) {
                simulateNativeClick(pointerId = pointerId, pressed = pressed)
                return true
            }
        }

        if (keyCode !in mouseHoldKeys || keyTouchMode[keyCode] == TouchMode.WHEEL) {
            if (pressed) handleKeyDown(keyCode, pointerId) else handleKeyUp(keyCode, pointerId)
            return true
        }
        keyMap[keyCode]?.let {
            val m = humanizedMap(it)
            if (pressed) {
                eventInjector.injectPointer(pointerId, m.position, m.end!!)
                syncHeldJitter(pointerId, true, TouchMode.HOLD, m)
            } else {
                stopKeyJitter(pointerId)
                eventInjector.releasePointer(pointerId)
            }
        }
        return true
    }

    private fun stopWheel() {
        if (!wheelActive) return
        eventInjector.releasePointer(wheelPointerId)
        wheelActive = false
        _wheelCursor.value = null
        wheelPointerId = -1
        wheelControlPointerId = -1
    }

    fun handlePointerMove(position: Offset): Boolean {
        if (wheelActive) {
            val previous = _wheelCursor.value ?: wheelCenter
            wheelOffset = withinWheel(wheelOffset + position, wheelRadius)
            // 轮盘是独立按钮的 360° 交互，只受自己的半径和屏幕边缘约束，
            // 不能套用射击模式的鼠标活动范围（否则左侧轮盘会被拉到右侧准心附近）。
            val target = clampToScreen(wheelCenter + wheelOffset)
            // Use coalesced deltas, as aiming does, to avoid a high-polling mouse backlog.
            if (target != previous) eventInjector.updatePointerPosition(wheelControlPointerId, target - previous)
            _wheelCursor.value = target
            return true
        }
        if (shootingMode) {
            val pointerId = pointerIds[shootingModeKeyCode] ?: return false
            if (!position.x.isFinite() || !position.y.isFinite()) return true
            val previous = aimPosition
            val requested = previous + position
            val clamped = clampAimToBounds(requested)
            aimPosition = clamped
            val applied = clamped - previous
            if (applied != Offset.Zero) {
                eventInjector.updatePointerPosition(pointerId, applied)
            }
            val overflow = requested - clamped
            if (overflow != Offset.Zero) {
                // 超出边界的部分不丢：缓存下来，等回中后从新落点继续补发。
                pendingAimOverflow = capAimOverflow(pendingAimOverflow + overflow)
                scheduleAimOverflowDrain()
            }
            return true
        }
        pendingAimOverflow = Offset.Zero

        // 非射击模式：鼠标移动只是移动光标。若左/右键正按住不放，
        // 就把按住的触点跟着光标一起拖动——按住上下滑动、拖拽列表都依赖这个。
        if (cursorDownAt.isNotEmpty()) {
            cursorDownAt.keys.toList().forEach { id ->
                eventInjector.transFormGesture(id, mousePointerPosition)
            }
        }
        return true
    }

    /**
     * 限制缓存溢出的总量，避免一次极端甩动让视角在鼠标停下后还转很久。
     * 上限是 4 个活动范围半宽（约两倍直径）。
     */
    private fun capAimOverflow(overflow: Offset): Offset {
        val length = overflow.getDistance()
        if (length <= 0.5f) return Offset.Zero
        val half = mouseAreaHalfSize
        val max = if (half > 0f) half * MAX_AIM_OVERFLOW_FACTOR else maxOf(screenSize.x, screenSize.y)
        if (max <= 1f) return Offset.Zero
        return if (length > max) overflow * (max / length) else overflow
    }

    /** 溢出量补发协程：每次回中最多补一段，用节流间隔把「分片滑动」摊到几帧里。 */
    private fun scheduleAimOverflowDrain() {
        if (overflowDrainJob?.isActive == true) return
        overflowDrainJob = humanizeScope.launch {
            var segments = 0
            while (isActive && pendingAimOverflow != Offset.Zero && segments < MAX_OVERFLOW_SEGMENTS) {
                val wait = (recenterMinIntervalMs -
                    (SystemClock.uptimeMillis() - lastRecenterTime)).coerceAtLeast(0L)
                if (wait > 0L) delay(wait)
                if (!shootingMode) {
                    pendingAimOverflow = Offset.Zero
                    break
                }
                // 射击键可能被重新映射，取当前触点 id。
                val id = pointerIds[shootingModeKeyCode] ?: -1
                if (id < 0) {
                    pendingAimOverflow = Offset.Zero
                    break
                }
                drainAimOverflowOnce(id)
                segments++
            }
            // 防御：极端形状下投影可能产生永远排不干净的残差，达到上限直接丢弃。
            if (segments >= MAX_OVERFLOW_SEGMENTS) pendingAimOverflow = Offset.Zero
        }
    }

    /**
     * 补发一段溢出：抬指 → 在小方框内随机重按 → 从新落点继续把能走的位移发出去。
     * 视角不会因为「瞬移回中」而反甩，因为回中本身只发 DOWN，不发 MOVE。
     */
    private fun drainAimOverflowOnce(pointerId: Int) {
        val overflow = pendingAimOverflow
        if (overflow == Offset.Zero) return
        val landing = randomLandingPoint()
        val target = clampAimToBounds(landing + overflow)
        val applied = target - landing
        eventInjector.releasePointer(pointerId)
        eventInjector.injectPointer(pointerId, landing)
        aimCenter = landing
        aimPosition = target
        if (applied.getDistance() > 0.5f) {
            // 一段可能跨越大半个活动范围，拆成几次 MOVE，游戏看到的是一段快速拖动，
            // 而不是一次瞬移。
            val steps = kotlin.math.ceil(applied.getDistance() / 60f).toInt().coerceIn(1, 6)
            val step = applied * (1f / steps)
            repeat(steps) { eventInjector.updatePointerPosition(pointerId, step) }
            pendingAimOverflow -= applied
        } else {
            // 落点已贴着该方向的边界，无法再走；清掉避免死循环。
            pendingAimOverflow = Offset.Zero
        }
        lastRecenterTime = SystemClock.uptimeMillis()
    }

    /**
     * 把瞄准逻辑坐标钳制在鼠标活动范围内。
     *
     * 大范围是一条手绘的不规则闭合轮廓（不是方框）：点在轮廓内保持不变，
     * 越界时投影到最近的轮廓边上。这样鼠标再甩也不会跑到屏幕另一侧，
     * 同时路径边界像真人手部扫过的范围。
     */
    private fun clampAimToBounds(p: Offset): Offset {
        val areaHalf = mouseAreaHalfSize
        if (areaHalf > 0f) {
            return clampToMouseShape(p, mouseAreaCenter, areaHalf)
        }
        return clampToScreen(p)
    }

    /** 只按屏幕边缘（含留白）兜底，用于轮盘、光标等与射击活动范围无关的触点。 */
    private fun clampToScreen(p: Offset): Offset {
        val w = screenSize.x
        val h = screenSize.y
        if (w <= 0f || h <= 0f) return p
        val margin = minOf(aimEdgeMargin, w / 2f, h / 2f)
        return Offset(
            p.x.coerceIn(margin, w - margin),
            p.y.coerceIn(margin, h - margin)
        )
    }

    /** 把点限制在手绘轮廓内：内部原样返回，外部投影到最近边。 */
    private fun clampToMouseShape(p: Offset, center: Offset, half: Float): Offset {
        val nx = (p.x - center.x) / half
        val ny = (p.y - center.y) / half
        if (isInsideMouseShape(nx, ny)) return p
        val shape = MOUSE_AREA_SHAPE
        val count = shape.size / 2
        var bestX = nx
        var bestY = ny
        var bestDist = Float.MAX_VALUE
        var j = count - 1
        for (i in 0 until count) {
            val ax = shape[i * 2]
            val ay = shape[i * 2 + 1]
            val bx = shape[j * 2]
            val by = shape[j * 2 + 1]
            val abx = bx - ax
            val aby = by - ay
            val lenSq = abx * abx + aby * aby
            val t = if (lenSq < 0.000001f) 0f else {
                (((nx - ax) * abx + (ny - ay) * aby) / lenSq).coerceIn(0f, 1f)
            }
            val px = ax + abx * t
            val py = ay + aby * t
            val dx = nx - px
            val dy = ny - py
            val dist = dx * dx + dy * dy
            if (dist < bestDist) {
                bestDist = dist
                bestX = px
                bestY = py
            }
            j = i
        }
        return center + Offset(bestX * half, bestY * half)
    }

    /** 射线法判断归一化坐标是否在手绘轮廓内。 */
    private fun isInsideMouseShape(x: Float, y: Float): Boolean {
        val shape = MOUSE_AREA_SHAPE
        val count = shape.size / 2
        var inside = false
        var j = count - 1
        for (i in 0 until count) {
            val xi = shape[i * 2]
            val yi = shape[i * 2 + 1]
            val xj = shape[j * 2]
            val yj = shape[j * 2 + 1]
            if ((yi > y) != (yj > y) &&
                x < (xj - xi) * (y - yi) / (yj - yi) + xi
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * 本次按下/回中使用的落点。
     *
     * 设置了「小方形」时在范围内均匀随机取点（更接近真人每次落指的位置差异），
     * 否则用 [fallback]；结果仍受大方形活动范围约束。
     */
    private fun randomLandingPoint(fallback: Offset = aimCenter): Offset {
        val half = landingAreaHalfSize
        val point = if (half > 0.5f) {
            val c = landingAreaCenter
            Offset(
                c.x + (Random.nextFloat() * 2f - 1f) * half,
                c.y + (Random.nextFloat() * 2f - 1f) * half
            )
        } else fallback
        return clampAimToBounds(point)
    }

    /**
     * 滚轮滚动。仅当轮盘模式激活（某键配置为 WHEEL 且正被按住）时生效：
     * 让轮盘触点绕中心旋转，滚轮每格转动 [wheelStepDeg] 度。
     *
     * @param delta 滚轮增量（ACTION_SCROLL 的 AXIS_VSCROLL），正负表示方向。
     */
    fun handleScroll(delta: Float): Boolean {
        if (!wheelActive) return false
        wheelAngleDeg += delta * wheelStepDeg
        val rad = Math.toRadians(wheelAngleDeg.toDouble())
        val x = wheelCenter.x + wheelRadius * kotlin.math.cos(rad).toFloat()
        val y = wheelCenter.y + wheelRadius * kotlin.math.sin(rad).toFloat()
        // 轮盘触点要绕中心旋转到「绝对位置 (x, y)」，必须用 transFormGesture（内部
        // updatePointer 绝对定位）。不能用 updatePointerPosition——那是 offsetPointer
        // 累加偏移，会把绝对坐标当成增量无限漂移，转几下触点就飞出屏幕。
        wheelOffset = Offset(x, y) - wheelCenter
        // 同上：轮盘只按屏幕边缘兜底，不进入射击模式的鼠标活动范围。
        val target = clampToScreen(Offset(x, y))
        eventInjector.transFormGesture(wheelControlPointerId, target)
        _wheelCursor.value = target
        return true
    }

    /* ---------- shooting mode ---------- */

    /** 释放所有仍按住的鼠标键对应的触点（切出射击模式时调用）。 */
    private fun releaseHeldMouseMappings() {
        heldMouseButtons.toList().forEach { button ->
            heldMouseButtons.remove(button)
            val key = mouseButtonKeyCode(button) ?: return@forEach
            val pointerId = pointerIds[key] ?: return@forEach
            handleKeyUp(key, pointerId)
        }
    }

    /** 外部（M 键开关地图）设置射击模式：true = 瞄准/光标收回，false = 光标模式。 */
    fun setShootingModeEnabled(enabled: Boolean) {
        if (shootingMode != enabled) toggleShootingMode()
    }

    /**
     * 幂等重建瞄准触点：仅在射击模式下生效。
     *
     * 游戏切换界面时可能把注入的触点丢掉，而 App 仍认为还在瞄准。这里先抬起再按下，
     * 保证游戏侧一定能收到一次新的 DOWN；摄像头方向不会跳，因为触点位置没变。
     */
    fun ensureAimContact() {
        if (!shootingMode) return
        val key = shootingModeKeyCode
        val pointerId = pointerIds[key] ?: return
        val center = randomLandingPoint(keyMap[key]?.position ?: aimCenter)
        aimCenter = center
        aimPosition = center
        eventInjector.releasePointer(pointerId)
        eventInjector.injectPointer(pointerId, center)
    }

    private fun toggleShootingMode() {
        stopWheel()
        val newState = !shootingMode
        pendingAimOverflow = Offset.Zero
        if (!newState) {
            // 退出射击模式时释放所有鼠标键对应的触点。否则「按住左键时切出鼠标」
            // 会让开火触点一直保持按下，表现为切出后仍在持续开火。
            releaseHeldMouseMappings()
            // 光标落点由状态管理器同步（缺省为屏幕中心）；这里保留准心兜底以防未初始化。
            val spawn = cursorSpawnPosition ?: keyMap[shootingModeKeyCode]?.position ?: aimCenter
            // 光标是独立功能，只做屏幕兜底，不能被鼠标活动范围拉走。
            val center = clampToScreen(spawn)
            mousePointerPosition = center
            onCursorRecenter?.invoke(center)
        }
        setShootingMode(newState)

        val key = shootingModeKeyCode
        val pointerId = pointerIds[key]

        if (pointerId != null) {
            if (newState) {
                keyMap[key]?.let {
                    // 进入射击模式：默认从 SHOOTING_MODE 按钮位置（可拖动准心）出发；
                    // 配置了回中范围时在小方形内随机落点，更接近真人按下的位置差异。
                    val center = randomLandingPoint(it.position)
                    lastRecenterTime = 0L
                    aimCenter = center
                    aimPosition = center
                    eventInjector.injectPointer(pointerId, center)
                }
            } else {
                eventInjector.releasePointer(pointerId)
            }
        }
    }


    private val cursorDownAt = mutableMapOf<Int, Long>()
    private val cursorGeneration = mutableMapOf<Int, Int>()

    /** 光标模式下的原生点击：在光标处按下，松开后至少保持 60ms 再抬起。 */
    private fun simulateNativeClick(
        pointerId: Int,
        position: Offset = mousePointerPosition,
        pressed: Boolean
    ) {
        if (pressed) {
            cursorGeneration[pointerId] = (cursorGeneration[pointerId] ?: 0) + 1
            cursorDownAt[pointerId] = SystemClock.uptimeMillis()
            eventInjector.injectPointer(pointerId, position)
        } else {
            val start = cursorDownAt.remove(pointerId) ?: return
            val token = cursorGeneration[pointerId]
            val delay = (60L - (SystemClock.uptimeMillis() - start)).coerceAtLeast(0)
            mainHandler.postDelayed({
                if (cursorGeneration[pointerId] == token) eventInjector.releasePointer(pointerId)
            }, delay)
        }
    }

    /* ---------- mapping ---------- */

    /** Refresh measured coordinates without cancelling active WASD/aim contacts. */
    fun updateMeasuredPosition(item: DraggableItem) {
        if (item is DraggableItem.WASDGroup) {
            refreshWasdGeometry(item)
            return
        }
        val key = when (item) {
            is DraggableItem.FixedKey -> item.keyCode
            is DraggableItem.VariableKey -> item.keyCode
            is DraggableItem.CancelableKey -> item.keyCode
            is DraggableItem.WASDGroup -> null
        } ?: return
        item.touchCenter?.let { center ->
            keyMap[key]?.let { keyMap[key] = it.copy(position = center, end = center) }
        }
        item.cancelTouchCenter?.let { center ->
            cancelKeyMap[key]?.let { cancelKeyMap[key] = it.copy(position = center, end = center) }
        }
    }

    /**
     * Rebuild the joystick entries for [item] once Compose has measured it.
     *
     * [updateKeyMapping] runs before the first layout pass of a new layout, so
     * the coordinates it reads are the ones the group reported for the previous
     * layout. After a rotation or a viewport change those are stale, and the
     * joystick origin and deflection no longer match the group on screen.
     * Walking survives a stale origin; sprint does not, because the sprint entry
     * has to sit well outside the centre to cross the game's sprint threshold.
     *
     * Only the WASD entries are rewritten, so live key contacts and the aim
     * contact are left alone.
     */
    fun refreshWasdGeometry(item: DraggableItem) {
        val group = item as? DraggableItem.WASDGroup ?: return
        if (!pointerIds.containsKey(KEYCODE_WASD)) return
        putWasdEntries(group)
    }

    /** Publish the joystick entries for [item] into [wasdMap]. */
    private fun putWasdEntries(item: DraggableItem.WASDGroup) {
        wasdMap[MASK_W] = KeyMap(position = item.w, center = item.center)
        wasdMap[MASK_A] = KeyMap(position = item.a, center = item.center)
        wasdMap[MASK_S] = KeyMap(position = item.s, center = item.center)
        wasdMap[MASK_D] = KeyMap(position = item.d, center = item.center)
        val wa = Offset(
            x = item.w.x - ((item.w.x - item.a.x) / 2),
            y = item.w.y + ((item.a.y - item.w.y) / 2)
        )
        val wd = Offset(
            x = item.w.x + ((item.d.x - item.w.x) / 2),
            y = item.w.y + ((item.d.y - item.w.y) / 2)
        )
        val sa = Offset(
            x = item.a.x + ((item.s.x - item.a.x) / 2),
            y = item.a.y + ((item.s.y - item.a.y) / 2)
        )
        val sd = Offset(
            x = item.s.x + ((item.d.x - item.s.x) / 2),
            y = item.s.y + ((item.d.y - item.s.y) / 2)
        )
        wasdMap[MASK_W or MASK_A] = KeyMap(position = wa, center = item.center)
        wasdMap[MASK_W or MASK_D] = KeyMap(position = wd, center = item.center)
        wasdMap[MASK_S or MASK_A] = KeyMap(position = sa, center = item.center)
        wasdMap[MASK_S or MASK_D] = KeyMap(position = sd, center = item.center)

        val forwardDistance = item.sprintForwardDistance
            ?: ((item.w - item.center).getDistance() * item.sprintScale)
        val sideDistance = item.sprintSideDistance
            ?: ((item.d - item.center).getDistance() * item.sprintScale)
        listOf(MASK_W, MASK_A, MASK_D, MASK_W or MASK_A, MASK_W or MASK_D).forEach { mask ->
            val forward = if (mask and MASK_W != 0) 1 else 0
            val side = if (mask and MASK_A != 0) -1 else if (mask and MASK_D != 0) 1 else 0
            val target = item.center + sprintOffset(forward, side,
                forwardDistance.coerceIn(10f, 1000f), sideDistance.coerceIn(10f, 1000f))
            wasdMap[mask or MASK_SPRINT] = KeyMap(position = target, center = item.center)
        }
    }

    fun updateKeyMapping(items: List<DraggableItem>) {
        macroRunner.cancel()
        scrollPulse.reset()
        releaseSwitch.reset()
        cursorDownAt.clear()
        cursorGeneration.keys.toList().forEach { cursorGeneration[it] = cursorGeneration.getValue(it) + 1 }
        walkToggle.interrupt()
        MappingConflictDetector.detect(items).forEach {
            Log.w("EventHandler", "Mapping conflict: ${it.message}")
        }
        // Pointer ids are re-allocated below, so any contact still reported as
        // down would become an orphan: the manager would keep counting it and
        // the next finger would be announced with ACTION_POINTER_DOWN instead of
        // ACTION_DOWN, which invalidates the gesture. Lift everything first.
        eventInjector.clear()
        // A MIXED-mode tap may have a delayed release queued on the main
        // thread. Invalidate it before pointer ids are re-used by this mapping.
        normalHandlers.values.filterIsInstance<TapModeTouchHandler>()
            .forEach { it.resetPendingActions() }
        normalHandlers.values.filterIsInstance<MixedModeTouchHandler>()
            .forEach { it.resetPendingActions() }
        wasdMap.clear()
        keyMap.clear()
        cancelKeyMap.clear()
        pointerIds.clear()
        keyIdMap.clear()
        mappingPressed.clear()
        cancelToggle.clear()
        keyTouchMode.clear()
        wheelRadii.clear()
        wheelCenterOffsets.clear()
        nextKeyId = 0
        // eventInjector.clear() above lifted the joystick contact, so the
        // Reset the joystick transition tracker after cancelling its contact.
        wasdMask = 0
        wasdDirectionOrder.clear()
        joystickMotion.reset()
        stopWasdJitter()
        stopWasdTransition()
        stopAllKeyJitter()
        wheelActive = false
        _wheelCursor.value = null
        wheelPointerId = -1
        walkKeyCode = items.filterIsInstance<DraggableItem.FixedKey>()
            .firstOrNull { it.type == DraggableItemType.WALK_TOGGLE }
            ?.keyCode?.takeUnless { keyToSprintMask(it) != 0 || keyToWasdMask(it) != 0 }
        mouseHoldKeys = items.filterIsInstance<DraggableItem.FixedKey>()
            .filter { it.type == DraggableItemType.FIRE || it.type == DraggableItemType.SCOPE }
            .map { it.keyCode }.toSet()
        var nextPointer = 0

        // Resolve the current shooting-mode toggle key from the items. If no
        // SHOOTING_MODE FixedKey is on the layout, fall back to the grave key
        // (the new default; can be switched to middle-mouse from the overlay).
        shootingModeKeyCode = items.filterIsInstance<DraggableItem.FixedKey>()
            .firstOrNull { it.type == DraggableItemType.SHOOTING_MODE }
            ?.keyCode
            ?: KeyEvent.KEYCODE_GRAVE

        fun alloc(key: Int) {
            if (!pointerIds.containsKey(key)) {
                pointerIds[key] = nextPointer++
            }
        }

        items.forEach { item ->
            when (item) {
                is DraggableItem.WASDGroup -> {
                    putWasdEntries(item)
                    alloc(KEYCODE_WASD)
                }

                is DraggableItem.VariableKey -> {
                    val k = item.keyCode ?: return@forEach
                    val center = item.touchCenter ?: item.position + Offset(item.size / 2f, item.size / 2f)
                    keyMap[k] = KeyMap(position = center, end = center)
                    item.touchMode?.let { keyTouchMode[k] = it }
                    wheelRadii[k] = item.wheelRadius
                    wheelCenterOffsets[k] = item.wheelCenterOffset
                    alloc(k)
                }

                is DraggableItem.FixedKey -> {
                    val k = item.keyCode
                    val center = item.touchCenter ?: item.position + Offset(item.size / 2f, item.size / 2f)
                    keyMap[k] = KeyMap(position = center, end = center)
                    item.touchMode?.let { keyTouchMode[k] = it }
                    wheelRadii[k] = item.wheelRadius
                    wheelCenterOffsets[k] = item.wheelCenterOffset
                    alloc(k)
                }

                is DraggableItem.CancelableKey -> {
                    val k = item.keyCode ?: return@forEach
                    val center = item.touchCenter ?: item.position + Offset(item.size / 2f, item.size / 2f)
                    val cancel = item.cancelTouchCenter ?: item.cancelPosition + Offset(item.size / 2f, item.size / 2f)
                    keyMap[k] = KeyMap(type = KeymapType.CANCELABLE, position = center, end = center)
                    cancelKeyMap[k] = KeyMap(type = KeymapType.CANCELABLE, position = cancel, end = cancel)
                    item.touchMode?.let { keyTouchMode[k] = it }
                    wheelRadii[k] = item.wheelRadius
                    wheelCenterOffsets[k] = item.wheelCenterOffset
                    alloc(k)
                }
            }
        }

        if (walkKeyCode == null && lastWalkPosition != null) alloc(-10_005)
        alloc(KEYCODE_LMC)
    }

    /**
     * 只刷新按键的注入坐标，不重建映射。
     *
     * 交换对只改变按钮的屏幕位置。若这里调用 [updateKeyMapping]，它会
     * `eventInjector.clear()` 释放所有触点并重新分配 pointer id —— 按一次
     * 换位键就会把正在按着的 W / 开火强制松开。此方法只覆盖坐标，pointer id、
     * 手势状态与触点全部保持不变，因此反复按换位 / 还原键是幂等的。
     */
    fun repositionItems(items: List<DraggableItem>) {
        items.forEach { item ->
            when (item) {
                is DraggableItem.VariableKey -> {
                    val k = item.keyCode ?: return@forEach
                    val center = item.touchCenter ?: item.position + Offset(item.size / 2f, item.size / 2f)
                    keyMap[k]?.let { keyMap[k] = it.copy(position = center, end = center) }
                }

                is DraggableItem.FixedKey -> {
                    val k = item.keyCode
                    val center = item.touchCenter ?: item.position + Offset(item.size / 2f, item.size / 2f)
                    keyMap[k]?.let { keyMap[k] = it.copy(position = center, end = center) }
                }

                is DraggableItem.CancelableKey -> {
                    val k = item.keyCode ?: return@forEach
                    val center = item.touchCenter ?: item.position + Offset(item.size / 2f, item.size / 2f)
                    val cancel = item.cancelTouchCenter ?: item.cancelPosition + Offset(item.size / 2f, item.size / 2f)
                    keyMap[k]?.let { keyMap[k] = it.copy(position = center, end = center) }
                    cancelKeyMap[k]?.let { cancelKeyMap[k] = it.copy(position = cancel, end = cancel) }
                }

                is DraggableItem.WASDGroup -> Unit
            }
        }
    }

    fun clear() {
        macroRunner.cancel()
        scrollPulse.reset()
        releaseSwitch.reset()
        cursorDownAt.clear()
        cursorGeneration.keys.toList().forEach { cursorGeneration[it] = cursorGeneration.getValue(it) + 1 }
        walkToggle.interrupt()
        heldMouseButtons.clear()
        normalHandlers.values.filterIsInstance<TapModeTouchHandler>()
            .forEach { it.resetPendingActions() }
        normalHandlers.values.filterIsInstance<MixedModeTouchHandler>()
            .forEach { it.resetPendingActions() }
        eventInjector.clear()
        pressedKeyCodes.clear()
        shootingPress.reset()
        mappingPressed.clear()
        cancelToggle.clear()
        wasdMask = 0
        wasdDirectionOrder.clear()
        joystickMotion.reset()
        stopWasdJitter()
        stopWasdTransition()
        stopAllKeyJitter()
        wheelActive = false
        _wheelCursor.value = null
        wheelPointerId = -1
        shootingMode = false
        _shootingModeFlow.value = false
        aimPosition = Offset.Zero
        aimCenter = Offset.Zero
        pendingAimOverflow = Offset.Zero
    }

    companion object {
        /** 随机偏移间隔：默认 1000ms（约每秒一次），可调范围 200–3000ms。 */
        const val DEFAULT_HUMANIZATION_INTERVAL_MS = 1000L
        const val MIN_HUMANIZATION_INTERVAL_MS = 200L
        const val MAX_HUMANIZATION_INTERVAL_MS = 3000L

        /** WASD 换向过渡：默认 60ms，可调 40–200ms。 */
        const val DEFAULT_WASD_TRANSITION_MS = 60
        const val MIN_WASD_TRANSITION_MS = 40
        const val MAX_WASD_TRANSITION_MS = 200

        /** 转角超过该值（约 34°）且两端都在摇杆半径上时，换向贴外圈画圆弧。 */
        const val SMALL_TURN_RAD = 0.6f
        /** 过渡采样目标：每段弧长不超过约 34px，避免回放被拉成折线。 */
        const val TRANSITION_SEGMENT_PX = 34f
        /** 越界溢出的最大缓存倍数（相对活动范围半宽）。 */
        const val MAX_AIM_OVERFLOW_FACTOR = 4f
        /** 一次甩动最多补发多少段，防止异常残差导致无限抬按。 */
        const val MAX_OVERFLOW_SEGMENTS = 12

        /**
         * 鼠标活动范围的形状：按用户手绘的不规则闭合轮廓采样（归一化到 [-1, 1]），
         * 比正方形自然得多。x 向右、y 向下，与屏幕坐标一致。
         */
        val MOUSE_AREA_SHAPE = floatArrayOf(
            -0.9871f, -0.0640f, -0.9806f, -0.3468f, -0.9161f, -0.4747f,
            -0.7677f, -0.6229f, -0.6194f, -0.7441f, -0.4452f, -0.8114f,
            -0.2581f, -0.8114f, -0.0645f, -0.9394f, 0.0710f, -0.9259f,
            0.3161f, -0.9798f, 0.4839f, -0.9865f, 0.6516f, -0.8249f,
            0.6968f, -0.6364f, 0.8323f, -0.4411f, 0.8645f, -0.2929f,
            0.8968f, -0.0774f, 0.9226f, 0.1246f, 1.0000f, 0.3535f,
            0.9613f, 0.4949f, 0.7871f, 0.7172f, 0.6452f, 0.8721f,
            0.5226f, 0.9529f, 0.3226f, 1.0000f, 0.1097f, 1.0000f,
            -0.0645f, 0.9394f, -0.2065f, 0.7845f, -0.3161f, 0.6633f,
            -0.4516f, 0.5488f, -0.6323f, 0.5084f, -0.8581f, 0.4478f,
            -0.9355f, 0.2997f, -0.9677f, 0.0707f,
        )

        /**
         * 落点随机：半径只向外延伸，避免把推力压进游戏的慢走/死区。
         * 普通移动最多 +5%，疾跑最多 +8%；方向偏移不超过 2.5°。
         */
        const val WALK_LANDING_RADIUS_RANGE = 0.05f
        const val SPRINT_LANDING_RADIUS_RANGE = 0.08f
        const val WALK_LANDING_ANGLE_DEG = 1.8f
        const val SPRINT_LANDING_ANGLE_DEG = 2.5f

        /** 疾跑半径在 sprintScale 之上再随机上浮 0–12%，避免每次都是同一圈。 */
        const val SPRINT_VARIATION_RANGE = 0.12f

        /** 普通移动的推力在基准之上随机上浮 0–8%（再乘「WASD 晃动」系数）。 */
        const val WALK_VARIATION_RANGE = 0.08f
    }
}
