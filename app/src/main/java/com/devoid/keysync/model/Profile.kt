package com.devoid.keysync.model

import kotlinx.serialization.Serializable

/**
 * A user-defined keymap preset. A user can maintain several of these
 * (e.g. infantry and vehicle layouts for the same game) and switch between them manually from the settings
 * screen. Each profile owns its own [items] (the on-screen button layout)
 * and its own [appConfig] (touch mode, theme, ...).
 */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val items: List<DraggableItem> = emptyList(),
    val appConfig: AppConfig = AppConfig.Default,
    /** Swap pairs: A/B button layout positions swap on swapOn, restore on swapOff. Moves position only. */
    val swapPairs: List<SwapPair> = emptyList(),
    /** 预设级快捷换位：X 让 R 与鼠标右键按钮交换位置，1 还原。默认开启。 */
    val quickSwapEnabled: Boolean = true,
    /** 按 3 时依次点击 2 → 3 → 4，用于一键切换武器组。默认开启。 */
    val tapSequence234Enabled: Boolean = true,
    /** 2-3-4 连点之间每个触点的间隔（ms），越小越快。默认 60。 */
    val tapSequence234GapMs: Int = 60,
    /** Tab 双击（2 秒内）改为点击 Tab + Esc 两个位置，之后 2 秒冷却。默认开启。 */
    val tabEscEnabled: Boolean = true,
    /** Shift 按下时取消最后按下的蹲/趴姿态（C / Z / Ctrl）。默认开启。 */
    val stanceAssistEnabled: Boolean = true,
    /** Ordered independent click points, separate from ordinary key bindings. */
    val macros: List<ClickMacro> = emptyList(),
    /** Layout 保存时的屏幕分辨率（px）。加载时若与当前屏幕不同则把坐标/尺寸等比还原；0 = 未记录。 */
    val layoutScreenWidth: Int = 0,
    val layoutScreenHeight: Int = 0,
)
