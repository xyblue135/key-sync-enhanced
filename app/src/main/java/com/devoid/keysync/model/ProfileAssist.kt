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
    val swapPairs: List<SwapPair> = emptyList(),
    val tapSequence234Enabled: Boolean = true,
    val tapSequence234GapMs: Int = 60,
    val tabEscEnabled: Boolean = true,
    val tabComboWindowMs: Int = 500,
    val tabComboCooldownMs: Int = 2000,
    val tabEscDelayMs: Int = 1000,
    val tabDebounceEnabled: Boolean = true,
    val tabDebounceMs: Int = 1000,
    val stanceAssistEnabled: Boolean = true,
    val macros: List<ClickMacro> = emptyList(),
)

/** 取出预设里参与草稿的字段。items / layout 等布局数据不在此列。 */
fun Profile.assist(): ProfileAssist = ProfileAssist(
    quickSwapEnabled = quickSwapEnabled,
    swapPairs = swapPairs,
    tapSequence234Enabled = tapSequence234Enabled,
    tapSequence234GapMs = tapSequence234GapMs,
    tabEscEnabled = tabEscEnabled,
    tabComboWindowMs = tabComboWindowMs,
    tabComboCooldownMs = tabComboCooldownMs,
    tabEscDelayMs = tabEscDelayMs,
    tabDebounceEnabled = tabDebounceEnabled,
    tabDebounceMs = tabDebounceMs,
    stanceAssistEnabled = stanceAssistEnabled,
    macros = macros,
)

/** 把草稿写回预设，保留布局、应用配置与分辨率记录。 */
fun Profile.withAssist(assist: ProfileAssist): Profile = copy(
    quickSwapEnabled = assist.quickSwapEnabled,
    swapPairs = assist.swapPairs,
    tapSequence234Enabled = assist.tapSequence234Enabled,
    tapSequence234GapMs = assist.tapSequence234GapMs.coerceIn(20, 400),
    tabEscEnabled = assist.tabEscEnabled,
    tabComboWindowMs = assist.tabComboWindowMs.coerceIn(300, 3000),
    tabComboCooldownMs = assist.tabComboCooldownMs.coerceIn(0, 5000),
    tabEscDelayMs = assist.tabEscDelayMs.coerceIn(0, 5000),
    tabDebounceEnabled = assist.tabDebounceEnabled,
    tabDebounceMs = assist.tabDebounceMs.coerceIn(200, 3000),
    stanceAssistEnabled = assist.stanceAssistEnabled,
    macros = assist.macros,
)
