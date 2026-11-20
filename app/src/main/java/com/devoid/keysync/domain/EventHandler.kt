package com.devoid.keysync.domain

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
    private val macroRunner = ClickMacroRunner(
        schedule = { delay, action -> mainHandler.postDelayed({ action() }, delay) },
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

    /** 拖拽起点：按住左键（在游戏里表现为按下不放）。 */
    fun beginCursorDrag(pointerId: Int, position: Offset) {
        eventInjector.injectPointer(pointerId, position)
    }

    /** 拖拽中途：绝对定位到新位置，保持按下状态。 */
    fun moveCursorDrag(pointerId: Int, position: Offset) {
        eventInjector.transFormGesture(pointerId, position)
    }

    /** 松开左键；取消/异常路径也必须调用。 */
    fun endCursorDrag(pointerId: Int) {
        eventInjector.releasePointer(pointerId)
    }

    fun setMacros(value: List<ClickMacro>, viewport: Offset, origin: Offset) {
        macroRunner.cancel()
        macros = value
        macroViewport = viewport
        macroOrigin = origin
    }

    fun isMacroTrigger(key: Int): Boolean = macros.any { it.enabled && it.triggerKeyCode == key }

    private fun macroEdge(key: Int, pressed: Boolean, repeat: Boolean = false): Boolean =
        macroRunner.handle(key, pressed, repeat, macros, macroViewport, macroOrigin)

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

    private val strengthFactor: Float
        get() = (humanizationStrength / 100f).coerceIn(0f, 1f)

    private val swayFactor: Float
        get() = (wasdSwayStrength / 100f).coerceIn(0f, 1f)

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

    // 持键期间低频率重掷抖动。间隔可配置（默认 1s），并带 ±20% 随机，
    // 避免固定周期本身成为可提取的规律。
    private val humanizeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wasdJitterJob: Job? = null
    private var wasdTransitionJob: Job? = null
    /** 本次按压段使用的推力倍率；换向或重新按下时重采样。 */
    private var pressVariationMask = 0
    private var pressVariation = 1f
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

    /**
     * WASD 抖动的目标点。
     *
     * 摇杆方向点是「从中心向外推动的位移」。如果按各向同性随机偏移，强度一高就会
     * 把触点拉回摇杆死区，游戏判定为没有推动 —— 表现为「强度往上调就完全不动」。
     * 因此这里只旋转一个极小角度，并允许半径 ±2%~±8% 的变化，且绝不低于原距离的
     * 97%，保证方向与推动量始终有效。
     */
    private fun jitterTarget(center: Offset, target: Offset): Offset {
        // WASD 晃动只看独立的「方向晃动」参数，不再跟着整体偏移强度放大，
        // 这样前进时可以只留很小幅度的摆头。
        val sway = swayFactor
        if (sway <= 0.001f) return target
        val delta = target - center
        val length = delta.getDistance()
        if (length <= 0.5f) return target
        // 满幅度也只旋转 ±1.5°：足以打破机械角度，又不会让按住 W 时明显跑偏。
        val maxAngle = (1.5f * sway) * (Math.PI.toFloat() / 180f)
        val angle = (Random.nextFloat() * 2f - 1f) * maxAngle
        val cosA = cos(angle)
        val sinA = sin(angle)
        // 半径最多收缩 0.6%，且不低于原距离的 99%，避免掉进游戏摇杆的慢走/死区。
        val scale = (1f - Random.nextFloat() * 0.006f * sway).coerceAtLeast(0.99f)
        val rotated = Offset(delta.x * cosA - delta.y * sinA, delta.x * sinA + delta.y * cosA)
        return center + rotated * scale
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
     * 小角度换向沿等半径弧线走；接近 180° 的大换向（A↔D、W↔S）会把半径
     * 在中间压到 [TRANSITION_MIN_RADIUS_FACTOR]，让触点几乎横穿中心，
     * 轨迹是一条左右/上下的直线，同时保留最低推动量避免完全停住。
     */
    private fun startWasdTransition(
        pointerId: Int,
        mask: Int,
        center: Offset,
        unit: Offset,
        start: Offset,
        end: Offset,
    ) {
        stopWasdTransition()
        val duration = wasdTransitionMs.coerceIn(MIN_WASD_TRANSITION_MS, MAX_WASD_TRANSITION_MS)
        // 再密的中间点也受录制采样下限约束；这里按 10ms 一步，保证短视频里能看出轨迹。
        val steps = (duration / 10).coerceIn(3, 8)
        wasdTransitionJob = humanizeScope.launch {
            val stepDelay = (duration / steps).coerceAtLeast(8).toLong()
            for (i in 1..steps) {
                delay(stepDelay)
                if (!isActive) return@launch
                val point = if (i == steps) end else arcPoint(center, start, end, i.toFloat() / steps)
                joystickMotion.jitter(pointerId, mask, point)
                recordWasdVector(center, unit, point)
            }
        }
    }

    private fun arcPoint(center: Offset, start: Offset, end: Offset, t: Float): Offset {
        val startDelta = start - center
        val endDelta = end - center
        val startRadius = startDelta.getDistance()
        val endRadius = endDelta.getDistance()
        if (startRadius < 1f || endRadius < 1f) return start + (end - start) * t
        val startAngle = atan2(startDelta.y, startDelta.x)
        val endAngle = atan2(endDelta.y, endDelta.x)
        var delta = endAngle - startAngle
        while (delta > PI) delta -= (2 * PI).toFloat()
        while (delta < -PI) delta += (2 * PI).toFloat()
        val angle = startAngle + delta * t
        val baseRadius = startRadius + (endRadius - startRadius) * t
        val radius = if (abs(delta) > (PI / 2).toFloat()) {
            // 大换向：中段把半径压到下限，形成近直线的横穿；两端仍精确落回原位。
            val floor = maxOf(startRadius, endRadius) * TRANSITION_MIN_RADIUS_FACTOR
            val dip = (baseRadius - floor).coerceAtLeast(0f) * sin((PI * t).toFloat())
            (baseRadius - dip).coerceAtLeast(floor)
        } else {
            baseRadius
        }
        return center + Offset(cos(angle) * radius, sin(angle) * radius)
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
        unit: Offset,
        baseTarget: Offset,
    ) {
        wasdJitterJob?.cancel()
        wasdJitterJob = humanizeScope.launch {
            while (isActive && wasdHumanization) {
                delay(nextJitterDelay())
                if (!wasdHumanization) break
                // 只拖 current 触点，绝不 reanchor（见 JoystickMotion.jitter）。
                val center = map.center ?: return@launch
                val target = jitterTarget(center, baseTarget)
                recordWasdVector(center, unit, target)
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
        val center = mapping.position
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

        if (sprintBit != 0 && event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0 && !pressedKeyCodes.contains(event.keyCode)) {
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
        val vertical = when {
            rawDirectionMask and MASK_W != 0 && rawDirectionMask and MASK_S == 0 -> MASK_W
            rawDirectionMask and MASK_S != 0 && rawDirectionMask and MASK_W == 0 -> MASK_S
            else -> 0
        }
        val horizontal = when {
            rawDirectionMask and MASK_A != 0 && rawDirectionMask and MASK_D == 0 -> MASK_A
            rawDirectionMask and MASK_D != 0 && rawDirectionMask and MASK_A == 0 -> MASK_D
            else -> 0
        }
        val directionMask = vertical or horizontal
        if (directionMask == 0) {
            stopWasdJitter()
            stopWasdTransition()
            pressVariationMask = 0
            pressVariation = 1f
            TrajectoryRecorder.recordWasd(0f, 0f)
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
        }
        val nominal = if (pressVariation != 1f) {
            entry.center!! + (entry.position - entry.center!!) * pressVariation
        } else entry.position
        val target = if (wasdHumanization) {
            // 每次换向时由 biasedDirection 重新采样偏置；再叠加低频微抖。
            jitterTarget(entry.center!!, biasedDirection(entry.center!!, nominal))
        } else nominal
        val directionChanged = joystickMotion.activeMask != effectiveMask
        // 过渡期间同一方向的按键重复（长按 W/D 的系统 repeat）不能把触点提前拉到终点，
        // 否则 80ms 的弧线只走了第一帧就被截断。
        if (!directionChanged && wasdTransitionJob?.isActive == true) return
        val actualStart = joystickMotion.target
        val wantsTransition = wasdTransitionEnabled &&
            directionChanged &&
            joystickMotion.activeMask != 0 &&
            actualStart != target
        // 返回 false 表示 update 把落点交给过渡协程，不能在这里先记终点，
        // 否则录像里会先出现终点、再出现弧线中间点。
        // 录像归一化基准取「非疾跑方向落点」：疾跑条目坐标在 sprintScale（默认 1.5）
        // 倍半径上，用它自己归一化会把奔跑也画成 1.0，轨迹里看不出 Shift。
        // 录像按轴归一化：x 用 A/D 落点距离、y 用 W 落点距离，普通方向就是半径 1
        // 的菱形、斜向 0.71；疾跑是同一圈 1.5–1.68，和实际注入形状一致。若按当前
        // 方向自己的落点距离归一化，斜向疾跑会被放大到 2 倍以上再被画布裁成圆弧。
        val center = entry.center!!
        val sideUnit = abs((wasdMap[MASK_D]?.position ?: entry.position).x - center.x)
        val forwardUnit = abs((wasdMap[MASK_W]?.position ?: entry.position).y - center.y)
        val unit = Offset(sideUnit.coerceAtLeast(1f), forwardUnit.coerceAtLeast(1f))
        val moved = joystickMotion.update(
            pointerId,
            effectiveMask,
            center,
            target,
            sendTarget = !wantsTransition,
            reanchorOnSprint = !wantsTransition,
        )
        if (moved) {
            stopWasdTransition()
            recordWasdVector(center, unit, target)
        } else {
            startWasdTransition(pointerId, effectiveMask, center, unit, actualStart, target)
        }
        if (wasdHumanization) {
            restartWasdJitter(pointerId, effectiveMask, entry, unit, nominal)
        } else stopWasdJitter()
    }

    /**
     * 把摇杆落点换算成以中心为原点、普通方向键距离为 1 的向量，供轨迹录制使用。
     *
     * [unit] 是按轴取的基准距离（x 用 A/D、y 用 W），不能用当前落点自己的半径：
     * 疾跑落点本身就在 1.5 倍半径上，用它会画成 1.0；斜向用方向自己的半径又会
     * 被放大到 2 倍以上。记录的是 [target]（已含方向偏置与低频晃动），也就是
     * 真正注入给游戏的那个点。
     */
    private fun recordWasdVector(center: Offset, unit: Offset, target: Offset) {
        TrajectoryRecorder.recordWasd(
            (target.x - center.x) / unit.x.coerceAtLeast(1f),
            (target.y - center.y) / unit.y.coerceAtLeast(1f),
        )
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
                    wheelCenter = it.position
                    wheelOffset = Offset.Zero
                    wheelAngleDeg = 0f
                    // Drag the same finger that opened the wheel, around this button.
                    wheelControlPointerId = pointerId
                    mappingPressed[keyId(keyCode)] = true
                    eventInjector.injectPointer(pointerId, it.position)
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
            val target = clampAimToBounds(wheelCenter + wheelOffset)
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
            aimPosition = clampAimToBounds(requested)
            val applied = aimPosition - previous
            if (applied != Offset.Zero) {
                TrajectoryRecorder.recordMouse(applied.x, applied.y)
                eventInjector.updatePointerPosition(pointerId, applied)
            }
            if (aimPosition != requested) {
                val now = SystemClock.uptimeMillis()
                if (now - lastRecenterTime >= recenterMinIntervalMs) {
                    // Finish this segment before UP; a fresh batch starts after DOWN.
                    eventInjector.releasePointer(pointerId)
                    eventInjector.injectPointer(pointerId, aimCenter)
                    aimPosition = aimCenter
                    lastRecenterTime = now
                }
            }
            return true
        }

        // 非射击模式：鼠标移动只是移动光标。若左/右键正按住不放，
        // 就把按住的触点跟着光标一起拖动——按住上下滑动、拖拽列表都依赖这个。
        if (cursorDownAt.isNotEmpty()) {
            cursorDownAt.keys.toList().forEach { id ->
                eventInjector.transFormGesture(id, mousePointerPosition)
            }
        }
        return true
    }

    /** 把瞄准逻辑坐标钳制在屏幕边缘内（含回中留白），节流期内防止触点继续越界。 */
    private fun clampAimToBounds(p: Offset): Offset {
        val w = screenSize.x
        val h = screenSize.y
        if (w <= 0f || h <= 0f) return p
        val margin = minOf(aimEdgeMargin, w / 2f, h / 2f)
        return Offset(
            p.x.coerceIn(margin, w - margin),
            p.y.coerceIn(margin, h - margin)
        )
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
        val target = clampAimToBounds(Offset(x, y))
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
        val center = clampAimToBounds(keyMap[key]?.position ?: aimCenter)
        aimCenter = center
        aimPosition = center
        eventInjector.releasePointer(pointerId)
        eventInjector.injectPointer(pointerId, center)
    }

    private fun toggleShootingMode() {
        stopWheel()
        val newState = !shootingMode
        if (!newState) {
            // 退出射击模式时释放所有鼠标键对应的触点。否则「按住左键时切出鼠标」
            // 会让开火触点一直保持按下，表现为切出后仍在持续开火。
            releaseHeldMouseMappings()
            val center = clampAimToBounds(keyMap[shootingModeKeyCode]?.position ?: aimCenter)
            mousePointerPosition = center
            onCursorRecenter?.invoke(center)
        }
        setShootingMode(newState)

        val key = shootingModeKeyCode
        val pointerId = pointerIds[key]

        if (pointerId != null) {
            if (newState) {
                keyMap[key]?.let {
                    // 进入射击模式：瞄准触点从 SHOOTING_MODE 按钮位置（可拖动准心）出发。
                    // 用户把准心拖到游戏视野区（通常是屏幕右侧）再切射击模式，滑动鼠标即可
                    // 转视角；之前强制屏幕中央会落到视野区之外，导致「转不了视角」。
                    val center = clampAimToBounds(it.position)
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
        nextKeyId = 0
        // eventInjector.clear() above lifted the joystick contact, so the
        // Reset the joystick transition tracker after cancelling its contact.
        wasdMask = 0
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
                    alloc(k)
                }

                is DraggableItem.FixedKey -> {
                    val k = item.keyCode
                    val center = item.touchCenter ?: item.position + Offset(item.size / 2f, item.size / 2f)
                    keyMap[k] = KeyMap(position = center, end = center)
                    item.touchMode?.let { keyTouchMode[k] = it }
                    wheelRadii[k] = item.wheelRadius
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

        /**
         * 大角度换向时摇杆推动量的最低比例。
         *
         * A↔D / W↔S 这类接近 180° 的换向，半径在中间压到这个比例（约 18%），
         * 触点几乎横穿摇杆中心，轨迹呈现为一条从左到右（或上到下）的线；
         * 保留一点推动量，避免游戏里出现完全归零的“停顿帧”。
         */
        const val TRANSITION_MIN_RADIUS_FACTOR = 0.18f

        /** 疾跑半径在 sprintScale 之上再随机上浮 0–12%，避免每次都是同一圈。 */
        const val SPRINT_VARIATION_RANGE = 0.12f

        /** 普通移动的推力在基准之上随机上浮 0–8%（再乘「WASD 晃动」系数）。 */
        const val WALK_VARIATION_RANGE = 0.08f
    }
}
