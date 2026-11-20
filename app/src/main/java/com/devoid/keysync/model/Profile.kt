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
    /** 预设级快捷换位：1/2/4 让 R 与鼠标右键按钮交换位置，X 还原；3 固定切近战。默认开启。 */
    val quickSwapEnabled: Boolean = true,
    /** 快捷换位锁存：已换位时再按 1/2/4 不再交换，直到 X 还原。持久化避免重启后误判。 */
    val quickSwapLatched: Boolean = false,
    /** 按 3 时依次点击 2 → 4 → 3，保证无论武器如何重排都切到近战。默认开启。 */
    val tapSequence234Enabled: Boolean = true,
    /** 切近战序列每个触点之间的间隔（ms），越小越快。默认 10。 */
    val tapSequence234GapMs: Int = 10,
    /** 两个按钮处于对调状态（通常是开大后）时，按 3 先等待的卸盾延迟（ms）。默认 1000。 */
    val meleeSwapDelayMs: Int = 1000,
    /** 快速切枪宏：按 Alt+9 执行自定义按键序列，预设级。 */
    val keyMacro: KeySeqMacro = KeySeqMacro(),
    /** 快速合盾宏：默认 Alt+8。 */
    val shieldMacro: KeySeqMacro = defaultShieldMacro(),
    /** 扇贝盾宏：默认 Alt+7。 */
    val scallopMacro: KeySeqMacro = defaultScallopMacro(),
    /** 扇贝盾冲刺宏：默认 Alt+6。 */
    val scallopDashMacro: KeySeqMacro = defaultScallopDashMacro(),
    /** Tab 防连按：开启后重复 Tab 在间隔内直接吞掉，避免手速过快时多余的 Tab 落到游戏里。默认开启。 */
    val tabDebounceEnabled: Boolean = true,
    /** Tab 防连按间隔（ms）：两次有效 Tab 按下之间的最小间隔。默认 1000。 */
    val tabDebounceMs: Int = 1000,
    /** Shift 按下时取消最后按下的蹲/趴姿态（C / Z / Ctrl）。默认开启。 */
    val stanceAssistEnabled: Boolean = true,
    /** Ordered independent click points, separate from ordinary key bindings. */
    val macros: List<ClickMacro> = emptyList(),
    /** Layout 保存时的屏幕分辨率（px）。加载时若与当前屏幕不同则把坐标/尺寸等比还原；0 = 未记录。 */
    val layoutScreenWidth: Int = 0,
    val layoutScreenHeight: Int = 0,
    /**
     * 连按落点：同一按键快速连按时，第 2/3/4 次按下额外点击的坐标（屏幕 px）。
     * 每个按键最多 [MAX_EXTRA_TAP_POINTS] 个；本体始终照常触发。
     */
    val extraTapPoints: List<ExtraTapPoint> = emptyList(),
    /** 拖拽丢弃落点（悬浮层坐标，竖条中心）。null = 尚未放置。 */
    val discardX: Float? = null,
    val discardY: Float? = null,
)

/** 连按落点：归属于某个物理按键的额外触点。 */
@Serializable
data class ExtraTapPoint(
    val keyCode: Int,
    val x: Float,
    val y: Float,
)

/** 每个按键最多保留的连按落点数。 */
const val MAX_EXTRA_TAP_POINTS = 3

/** 连按判定窗口（ms）：两次按下间隔超过该值就重新从第 1 次开始计数。 */
const val EXTRA_TAP_WINDOW_MS = 250L
