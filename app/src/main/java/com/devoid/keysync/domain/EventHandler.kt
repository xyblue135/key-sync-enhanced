package com.devoid.keysync.domain

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.nativeKeyCode
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
    @Volatile
    var humanizationStrength = 0f

    fun setHumanization(wasd: Boolean, keys: Boolean, strength: Float) {
        wasdHumanization = wasd
        keyHumanization = keys
        humanizationStrength = strength.coerceIn(0f, 1f)
    }

    // 持键期间低频率重掷抖动（约 140ms），在完全静止的方向上也产生自然的轨迹扰动。
    private val humanizeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wasdJitterJob: Job? = null

    /** 拟人化抖动偏移（px）。强度 0 时返回零偏移。 */
    private fun jitterOffset(): Offset {
        val max = humanizationStrength * 40f
        if (max <= 0f) return Offset.Zero
        return Offset(
            (Random.nextFloat() * 2f - 1f) * max,
            (Random.nextFloat() * 2f - 1f) * max,
        )
    }

    private fun stopWasdJitter() {
        wasdJitterJob?.cancel()
        wasdJitterJob = null
    }

    private fun restartWasdJitter(pointerId: Int, mask: Int, map: KeyMap) {
        wasdJitterJob?.cancel()
        wasdJitterJob = humanizeScope.launch {
            while (isActive && wasdHumanization) {
                delay(140L)
                if (!wasdHumanization) break
                // 只拖 current 触点，绝不 reanchor（见 JoystickMotion.jitter）。
                joystickMotion.jitter(pointerId, mask, map.position + jitterOffset())
            }
        }
    }

    /** 普通按键的触点位置加随机抖动（仅当「其他按键拟人化」开启时）。 */
    private fun humanizedMap(map: KeyMap): KeyMap {
        if (!keyHumanization || humanizationStrength <= 0f) return map
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
                TouchMode.TAP -> TapModeTouchHandler(eventInjector)
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
            val key = when (button) {
                MotionEvent.BUTTON_PRIMARY -> KEYCODE_LMC
                MotionEvent.BUTTON_SECONDARY -> KEYCODE_RMC
                else -> KEYCODE_MMC
            }
            if (key == shootingModeKeyCode) heldMouseButtons.add(button)
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
            return
        }
        val target = if (wasdHumanization) entry.position + jitterOffset() else entry.position
        joystickMotion.update(pointerId, effectiveMask, entry.center!!, target)
        if (wasdHumanization) restartWasdJitter(pointerId, effectiveMask, entry) else stopWasdJitter()
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
                    mappingPressed[id] = handler.handleTouchEvent(
                        KeyEvent(KeyEvent.ACTION_DOWN, keyCode),
                        wasPressed,
                        pointerId,
                        humanizedMap(it)
                    )
                }
            }
        }
    }

    private fun handleKeyUp(keyCode: Int, pointerId: Int) {
        // The toggle owns a persistent aim contact; normal UP handlers must not lift it.
        if (keyCode == walkKeyCode || keyCode == shootingModeKeyCode) return
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
        val keyCode = when (button) {
            MotionEvent.BUTTON_PRIMARY -> KEYCODE_LMC
            MotionEvent.BUTTON_SECONDARY -> KEYCODE_RMC
            MotionEvent.BUTTON_TERTIARY -> KEYCODE_MMC
            else -> return false
        }
        if (keyCode == shootingModeKeyCode) {
            if (!pressed) return false
            toggleShootingMode()
            return true
        }
        val pointerId = pointerIds[keyCode] ?: return false

        if (!shootingMode) {
            simulateNativeClick(pointerId = pointerId, pressed = pressed)
            return true
        }

        if (keyCode !in mouseHoldKeys || keyTouchMode[keyCode] == TouchMode.WHEEL) {
            if (pressed) handleKeyDown(keyCode, pointerId) else handleKeyUp(keyCode, pointerId)
            return true
        }
        keyMap[keyCode]?.let {
            val m = humanizedMap(it)
            if (pressed)
                eventInjector.injectPointer(pointerId, m.position, m.end!!)
            else
                eventInjector.releasePointer(pointerId)
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
            if (applied != Offset.Zero) eventInjector.updatePointerPosition(pointerId, applied)
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

        // 非射击模式：鼠标移动只是移动光标（光标位置已由 FloatingWindowStateManager
        // 的 _mousePointerOffset 跟踪），不拖动 LMC 触点。旧实现无条件调用
        // updatePointerPosition(LMC)，一旦用户按住左键（点击后未及时松开）再滑鼠标，
        // LMC 触点就被拖出屏幕 → 游戏收到越界多指事件 → 重置所有触点（WASD 断触）。
        if (MotionEvent.BUTTON_PRIMARY in heldMouseButtons) {
            pointerIds[KEYCODE_LMC]?.let { eventInjector.transFormGesture(it, mousePointerPosition) }
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

    private fun toggleShootingMode() {
        stopWheel()
        val newState = !shootingMode
        if (!newState) {
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

    fun updateKeyMapping(items: List<DraggableItem>) {
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

    fun clear() {
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
        wheelActive = false
        _wheelCursor.value = null
        wheelPointerId = -1
        shootingMode = false
        _shootingModeFlow.value = false
        aimPosition = Offset.Zero
        aimCenter = Offset.Zero
    }
}
