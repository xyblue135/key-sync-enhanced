package com.devoid.keysync.model

/**
 * 一个预设里的「按键辅助」设置集合。
 *
 * 设置页把这些开关和宏先收在本地草稿里，点保存时一次性写回 [Profile]，
 * 这样返回/切换预设时的「放弃更改」才真的能放弃，而不是像以前那样
 * 开关已经落盘、滑杆却还没保存。
 */
data class ProfileAssist(
    val quickSwapEnabled: Boolean = true,
    val tapSequence234Enabled: Boolean = true,
    val tapSequence234GapMinMs: Int = 20,
    val tapSequence234GapMaxMs: Int = 30,
    val keyMacro: KeySeqMacro = KeySeqMacro(),
    val shieldMacro: KeySeqMacro = defaultShieldMacro(),
    val scallopMacro: KeySeqMacro = defaultScallopMacro(),
    val scallopDashMacro: KeySeqMacro = defaultScallopDashMacro(),
    val quickMeleeMacro: QuickMeleeMacro = QuickMeleeMacro(),
    val shiftCancelWalkEnabled: Boolean = true,
    val macros: List<ClickMacro> = emptyList(),
)

/** 取出预设里参与草稿的字段。items / layout 等布局数据不在此列。 */
fun Profile.assist(): ProfileAssist = ProfileAssist(
    quickSwapEnabled = quickSwapEnabled,
    tapSequence234Enabled = tapSequence234Enabled,
    tapSequence234GapMinMs = tapSequence234GapMinMs,
    tapSequence234GapMaxMs = tapSequence234GapMaxMs,
    keyMacro = keyMacro,
    shieldMacro = shieldMacro,
    scallopMacro = scallopMacro,
    scallopDashMacro = scallopDashMacro,
    quickMeleeMacro = quickMeleeMacro,
    shiftCancelWalkEnabled = shiftCancelWalkEnabled,
    macros = macros,
)

/** 把草稿写回预设，保留布局、应用配置与分辨率记录。 */
fun Profile.withAssist(assist: ProfileAssist): Profile = copy(
    quickSwapEnabled = assist.quickSwapEnabled,
    tapSequence234Enabled = assist.tapSequence234Enabled,
    tapSequence234GapMinMs = assist.tapSequence234GapMinMs.coerceIn(5, 500),
    tapSequence234GapMaxMs = assist.tapSequence234GapMaxMs
        .coerceIn(assist.tapSequence234GapMinMs.coerceIn(5, 500), 500),
    keyMacro = assist.keyMacro.sanitized(),
    shieldMacro = assist.shieldMacro.sanitized(),
    scallopMacro = assist.scallopMacro.sanitized(),
    scallopDashMacro = assist.scallopDashMacro.sanitized(),
    quickMeleeMacro = assist.quickMeleeMacro.copy(
        delayMs = assist.quickMeleeMacro.delayMs.coerceIn(20, 1000),
    ),
    shiftCancelWalkEnabled = assist.shiftCancelWalkEnabled,
    macros = assist.macros,
)

/** 宏参数的统一兜底：按住时长、步数、单步延迟。 */
private fun KeySeqMacro.sanitized(): KeySeqMacro = copy(
    holdMs = holdMs.coerceIn(5, 100),
    steps = steps.take(128).map { it.copy(delayMs = it.delayMs.coerceIn(0, 5000)) },
)
