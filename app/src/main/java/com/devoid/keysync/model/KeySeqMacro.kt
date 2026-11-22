package com.devoid.keysync.model

import android.view.KeyEvent
import com.devoid.keysync.domain.KEYCODE_RMC
import kotlinx.serialization.Serializable

/** 宏的一步做什么。 */
@Serializable
enum class SeqAction {
    /** 按下并保持，直到对应的 UP 步骤。 */
    DOWN,

    /** 松开 DOWN 按下的触点。 */
    UP,

    /** 点击一下（按下 → 按住 holdMs → 抬起）。 */
    TAP,

    /** 只等待，不按键。 */
    WAIT,
}

/** 快速切枪宏的一步：执行 [action]，然后等待 [delayMs] 毫秒。 */
@Serializable
data class KeySeqStep(
    val action: SeqAction = SeqAction.TAP,
    val keyCode: Int = 0,
    val delayMs: Int = 0,
)

/**
 * 按键序列宏：按触发键后依次执行一串 DOWN / UP / TAP / WAIT。
 *
 * 宏的每一步都直接注入触点，不经过 R/右键换位、按 3 切近战和连按落点，
 * 避免宏里的 1/X 把按钮布局翻来翻去。DOWN 可以跨步骤保持，用来实现“按住 V 的同时点 X”。
 */
@Serializable
data class KeySeqMacro(
    val enabled: Boolean = true,
    /** 触发键，默认 9；配合 [requireAlt] 即 Alt + 9。 */
    val triggerKeyCode: Int = KeyEvent.KEYCODE_9,
    /** 是否必须按住 Alt 才触发。 */
    val requireAlt: Boolean = true,
    /** TAP 步骤的按住时长（ms），默认 10；每个宏可单独调整，DOWN/UP 不受影响。 */
    val holdMs: Int = 10,
    /** 按顺序执行的步骤，最多 128 步。 */
    val steps: List<KeySeqStep> = defaultQuickSwapSteps(),
)

private const val MAX_STEPS = 128

private fun down(code: Int, delay: Int) = KeySeqStep(SeqAction.DOWN, code, delay)
private fun up(code: Int, delay: Int) = KeySeqStep(SeqAction.UP, code, delay)
private fun tap(code: Int, delay: Int) = KeySeqStep(SeqAction.TAP, code, delay)

/** G502 板载快速切枪序列：开头和结尾的 X 显式按下/放开，中间是点击。 */
fun defaultQuickSwapSteps(): List<KeySeqStep> = buildList {
    add(down(KeyEvent.KEYCODE_1, 50))
    add(up(KeyEvent.KEYCODE_1, 50))
    repeat(11) { add(tap(KeyEvent.KEYCODE_1, 50)) }
    repeat(2) { add(tap(KeyEvent.KEYCODE_1, 100)) }
    repeat(2) { add(tap(KeyEvent.KEYCODE_1, 10)) }
    repeat(2) { add(tap(KeyEvent.KEYCODE_X, 10)) }
    repeat(4) { add(tap(KeyEvent.KEYCODE_1, 10)) }
    add(down(KeyEvent.KEYCODE_X, 1))
    add(up(KeyEvent.KEYCODE_X, 10))
    add(tap(KeyEvent.KEYCODE_1, 10))
    add(tap(KeyEvent.KEYCODE_1, 0))
}

/** 快速合盾：按住 V 点一下 X 再松开 V，然后按住 G 点一下 X 再松开 G，事件间隔 10ms。 */
fun defaultShieldSteps(): List<KeySeqStep> = listOf(
    down(KeyEvent.KEYCODE_V, 10),
    down(KeyEvent.KEYCODE_X, 10),
    up(KeyEvent.KEYCODE_X, 10),
    up(KeyEvent.KEYCODE_V, 10),
    down(KeyEvent.KEYCODE_G, 10),
    down(KeyEvent.KEYCODE_X, 10),
    up(KeyEvent.KEYCODE_X, 10),
    up(KeyEvent.KEYCODE_G, 0),
)

/** 扇贝盾：1 → G → V → X，间隔 5ms。 */
fun defaultScallopSteps(): List<KeySeqStep> = listOf(
    tap(KeyEvent.KEYCODE_1, 5),
    tap(KeyEvent.KEYCODE_G, 5),
    tap(KeyEvent.KEYCODE_V, 5),
    tap(KeyEvent.KEYCODE_X, 0),
)

/** 扇贝盾冲刺：1 → G → V → X → 鼠标右键，间隔 5ms。 */
fun defaultScallopDashSteps(): List<KeySeqStep> = listOf(
    tap(KeyEvent.KEYCODE_1, 5),
    tap(KeyEvent.KEYCODE_G, 5),
    tap(KeyEvent.KEYCODE_V, 5),
    tap(KeyEvent.KEYCODE_X, 5),
    tap(KEYCODE_RMC, 0),
)

fun defaultShieldMacro(): KeySeqMacro = KeySeqMacro(
    triggerKeyCode = KeyEvent.KEYCODE_8,
    holdMs = 10,
    steps = defaultShieldSteps(),
)

fun defaultScallopMacro(): KeySeqMacro = KeySeqMacro(
    triggerKeyCode = KeyEvent.KEYCODE_7,
    holdMs = 10,
    steps = defaultScallopSteps(),
)

fun defaultScallopDashMacro(): KeySeqMacro = KeySeqMacro(
    triggerKeyCode = KeyEvent.KEYCODE_6,
    holdMs = 10,
    steps = defaultScallopDashSteps(),
)

/**
 * 快速近战宏：按住触发键（默认 Alt+0）循环执行「点一次快速近战键 → 延迟 → 点一次 1 号键」。
 * 松开触发键立即停止；只使用已有映射点位，不新增额外监听。
 */
@Serializable
data class QuickMeleeMacro(
    val enabled: Boolean = true,
    val triggerKeyCode: Int = KeyEvent.KEYCODE_0,
    val requireAlt: Boolean = true,
    /** 游戏里「快速近战」绑定的物理键，宏会点它对应的映射位置；默认右侧小键盘 num0。 */
    val meleeKeyCode: Int = KeyEvent.KEYCODE_NUMPAD_0,
    /** 近战键与 1 号键之间的延迟（ms），默认 80。 */
    val delayMs: Int = 80,
)

private val namedKeys = mapOf(
    "ESC" to KeyEvent.KEYCODE_ESCAPE,
    "ESCAPE" to KeyEvent.KEYCODE_ESCAPE,
    "TAB" to KeyEvent.KEYCODE_TAB,
    "SPACE" to KeyEvent.KEYCODE_SPACE,
    "ENTER" to KeyEvent.KEYCODE_ENTER,
    "SHIFT" to KeyEvent.KEYCODE_SHIFT_LEFT,
    "CTRL" to KeyEvent.KEYCODE_CTRL_LEFT,
    "CONTROL" to KeyEvent.KEYCODE_CTRL_LEFT,
    "ALT" to KeyEvent.KEYCODE_ALT_LEFT,
    "DEL" to KeyEvent.KEYCODE_DEL,
    "BACKSPACE" to KeyEvent.KEYCODE_DEL,
    "RMB" to KEYCODE_RMC,
    // 右侧小键盘数字：默认键位是 num0，也允许用户自己填 NUM1–NUM9。
    "NUM0" to KeyEvent.KEYCODE_NUMPAD_0,
    "NUM1" to KeyEvent.KEYCODE_NUMPAD_1,
    "NUM2" to KeyEvent.KEYCODE_NUMPAD_2,
    "NUM3" to KeyEvent.KEYCODE_NUMPAD_3,
    "NUM4" to KeyEvent.KEYCODE_NUMPAD_4,
    "NUM5" to KeyEvent.KEYCODE_NUMPAD_5,
    "NUM6" to KeyEvent.KEYCODE_NUMPAD_6,
    "NUM7" to KeyEvent.KEYCODE_NUMPAD_7,
    "NUM8" to KeyEvent.KEYCODE_NUMPAD_8,
    "NUM9" to KeyEvent.KEYCODE_NUMPAD_9,
)

/** 把 "1" / "X" / "F5" / "ESC" / "RMB" 这类名字解析成 KeyCode；无法识别返回 null。 */
fun keyCodeForName(raw: String): Int? {
    val name = raw.trim().uppercase().removePrefix("KEYCODE_")
    if (name.isEmpty()) return null
    if (name.length == 1) {
        val c = name[0]
        if (c in '0'..'9') return KeyEvent.KEYCODE_0 + (c - '0')
        if (c in 'A'..'Z') return KeyEvent.KEYCODE_A + (c - 'A')
    }
    if (name.length in 2..3 && name.startsWith("F")) {
        val n = name.drop(1).toIntOrNull() ?: return null
        if (n in 1..12) return KeyEvent.KEYCODE_F1 + (n - 1)
    }
    return namedKeys[name]
}

/** KeyCode → 展示名（1、X、F5、ESC、RMB…）；未收录的返回 K<code>。 */
fun keyName(code: Int): String {
    if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
        return ('0' + (code - KeyEvent.KEYCODE_0)).toString()
    }
    if (code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
        return ('A' + (code - KeyEvent.KEYCODE_A)).toString()
    }
    if (code in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) {
        return "F${code - KeyEvent.KEYCODE_F1 + 1}"
    }
    return namedKeys.entries.firstOrNull { it.value == code }?.key ?: "K$code"
}

/**
 * 解析文本序列，每行一步，支持 `#` 注释：
 *
 * ```
 * D 1 50   # 按下 1，等待 50ms
 * U 1 50   # 放开 1，等待 50ms
 * T 1 50   # 点击 1，等待 50ms
 * W 50     # 纯等待 50ms
 * ```
 *
 * 也兼容旧格式 `1 50`（等价于 `T 1 50`）。任意一行不合法、超过 128 步或延迟越界返回 null。
 */
fun parseKeySequence(text: String): List<KeySeqStep>? {
    val steps = mutableListOf<KeySeqStep>()
    for (raw in text.lineSequence()) {
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty()) continue
        val parts = line.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) continue
        if (steps.size >= MAX_STEPS) return null
        val head = parts[0].uppercase()
        val step = when (head) {
            "W", "WAIT", "等待" -> {
                val delay = parts.getOrNull(1)?.toIntOrNull() ?: return null
                KeySeqStep(SeqAction.WAIT, 0, delay)
            }
            "D", "DOWN", "按下" -> parseKeyStep(parts, SeqAction.DOWN) ?: return null
            "U", "UP", "放开" -> parseKeyStep(parts, SeqAction.UP) ?: return null
            "T", "TAP", "点击" -> parseKeyStep(parts, SeqAction.TAP) ?: return null
            else -> {
                // 旧格式：按键 延迟
                val key = keyCodeForName(parts[0]) ?: return null
                val delay = parts.getOrNull(1)?.toIntOrNull() ?: 0
                KeySeqStep(SeqAction.TAP, key, delay)
            }
        }
        if (step.delayMs !in 0..5000) return null
        steps += step
    }
    return steps.takeIf { it.isNotEmpty() }
}

private fun parseKeyStep(parts: List<String>, action: SeqAction): KeySeqStep? {
    val key = keyCodeForName(parts.getOrNull(1) ?: return null) ?: return null
    val delay = parts.getOrNull(2)?.toIntOrNull() ?: 0
    return KeySeqStep(action, key, delay)
}

/** 把步骤渲染回文本，便于编辑。 */
fun keySequenceToText(steps: List<KeySeqStep>): String = steps.joinToString("\n") {
    when (it.action) {
        SeqAction.DOWN -> "D ${keyName(it.keyCode)} ${it.delayMs}"
        SeqAction.UP -> "U ${keyName(it.keyCode)} ${it.delayMs}"
        SeqAction.TAP -> "T ${keyName(it.keyCode)} ${it.delayMs}"
        SeqAction.WAIT -> "W ${it.delayMs}"
    }
}
