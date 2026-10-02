package com.devoid.keysync.model

import androidx.compose.ui.geometry.Offset
import com.devoid.keysync.domain.mouseInputKeyCodes
import kotlinx.serialization.Serializable

/** Independent click centers, normalized to the overlay viewport, in execution order. */
@Serializable
data class MacroStep(
    val id: String,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val delayAfterMs: Long = 100,
)

@Serializable
data class ClickMacro(
    val id: String,
    val name: String,
    val triggerKeyCode: Int,
    val steps: List<MacroStep> = emptyList(),
    val enabled: Boolean = false,
)

const val MAX_MACROS = 16
const val MAX_MACRO_STEPS = 16
const val MAX_MACRO_DELAY_MS = 5000L

fun validateClickMacros(macros: List<ClickMacro>): String? {
    if (macros.size > MAX_MACROS) return "每个预设最多 $MAX_MACROS 个宏"
    if (macros.map { it.id }.distinct().size != macros.size) return "宏 ID 重复"
    if (macros.filter { it.enabled }.groupBy { it.triggerKeyCode }.any { it.value.size > 1 })
        return "同一个触发键只能启用一个宏"
    for (macro in macros) {
        if (macro.id.isBlank() || macro.name.isBlank()) return "请填写宏名称"
        if (macro.triggerKeyCode <= 0 && macro.triggerKeyCode !in mouseInputKeyCodes)
            return "${macro.name}：请选择有效触发键"
        if (macro.steps.isEmpty() || macro.steps.size > MAX_MACRO_STEPS)
            return "${macro.name}：需要 1–$MAX_MACRO_STEPS 个点击位置"
        if (macro.steps.any { it.id.isBlank() } || macro.steps.map { it.id }.distinct().size != macro.steps.size)
            return "${macro.name}：步骤 ID 无效或重复"
        if (macro.steps.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..1f || it.y !in 0f..1f })
            return "${macro.name}：位置必须在画面内"
        if (macro.steps.any { it.delayAfterMs !in 0..MAX_MACRO_DELAY_MS })
            return "${macro.name}：步骤间隔需为 0–$MAX_MACRO_DELAY_MS 毫秒"
    }
    return null
}

fun MacroStep.screenPosition(viewport: Offset, origin: Offset): Offset =
    origin + Offset((x * viewport.x).coerceIn(0f, (viewport.x - 1f).coerceAtLeast(0f)),
        (y * viewport.y).coerceIn(0f, (viewport.y - 1f).coerceAtLeast(0f)))
