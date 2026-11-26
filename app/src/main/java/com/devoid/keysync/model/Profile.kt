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
    /** 预设级快捷换位：1/2 让 R 与鼠标右键按钮交换位置，X 还原；3/4 固定用于近战流程。默认开启。 */
    val quickSwapEnabled: Boolean = true,
    /** 快捷换位锁存：已换位时再按 1/2 不再交换，直到 X 还原。持久化避免重启后误判。 */
    val quickSwapLatched: Boolean = false,
    /** 按 3 时依次点击 2 → 4 → 3：三种武器布局的近战位在这轮里都只出现一次，最终必落在近战。默认开启。 */
    val tapSequence234Enabled: Boolean = true,
    /** 切近战序列相邻触点之间的随机延迟下限（ms），默认 20。 */
    val tapSequence234GapMinMs: Int = 20,
    /** 切近战序列相邻触点之间的随机延迟上限（ms），默认 30。 */
    val tapSequence234GapMaxMs: Int = 30,
    /** 快速切枪宏：按 Alt+9 执行自定义按键序列，预设级。 */
    val keyMacro: KeySeqMacro = KeySeqMacro(),
    /** 快速合盾宏：默认 Alt+8。 */
    val shieldMacro: KeySeqMacro = defaultShieldMacro(),
    /** 扇贝盾宏：默认 Alt+7。 */
    val scallopMacro: KeySeqMacro = defaultScallopMacro(),
    /** 扇贝盾冲刺宏：默认 Alt+6。 */
    val scallopDashMacro: KeySeqMacro = defaultScallopDashMacro(),
    /** 快速近战宏：默认 Alt+0，点一次近战键 → 延迟 → 点一次 1；按住触发键循环。 */
    val quickMeleeMacro: QuickMeleeMacro = QuickMeleeMacro(),
    /** Shift 按下时取消我们自己开启的静步（WALK_TOGGLE）。默认开启。 */
    val shiftCancelWalkEnabled: Boolean = true,
    /** Ordered independent click points, separate from ordinary key bindings. */
    val macros: List<ClickMacro> = emptyList(),
    /**
     * 连按落点：同一按键快速连按时，第 2/3/4 次按下额外点击的坐标（屏幕 px）。
     * 每个按键最多 [MAX_EXTRA_TAP_POINTS] 个；本体始终照常触发。
     */
    val extraTapPoints: List<ExtraTapPoint> = emptyList(),
    /** 拖拽丢弃落点（悬浮层坐标，竖条中心）。null = 尚未放置。 */
    val discardX: Float? = null,
    val discardY: Float? = null,
    /**
     * ` 键自定义区域：相对于该键左上角的顶点偏移（屏幕 px）。
     * 空 = 使用默认方形；按下 ` 时从多边形质心注入。
     */
    val shootingAreaPoints: List<AreaPoint> = emptyList(),
    /** 进入光标模式时光标出现的位置（悬浮层坐标）。null = 使用屏幕中心。 */
    val cursorSpawnX: Float? = null,
    val cursorSpawnY: Float? = null,
    /** F 光标滚轮操控：左侧滚动锚点（悬浮层坐标）。null = 尚未放置。 */
    val scrollLeftX: Float? = null,
    val scrollLeftY: Float? = null,
    /** F 光标滚轮操控：右侧滚动锚点（悬浮层坐标）。null = 尚未放置。 */
    val scrollRightX: Float? = null,
    val scrollRightY: Float? = null,
)

/** 连按落点：归属于某个物理按键的额外触点。 */
@Serializable
data class ExtraTapPoint(
    val keyCode: Int,
    val x: Float,
    val y: Float,
)

/** 自定义区域的一个顶点（相对所属按键左上角的偏移）。 */
@Serializable
data class AreaPoint(
    val x: Float,
    val y: Float,
)

/** 每个按键最多保留的连按落点数。 */
const val MAX_EXTRA_TAP_POINTS = 6

/** ` 键手绘区域的最大顶点数。 */
const val MAX_SHOOTING_AREA_POINTS = 12

/** 连按判定窗口（ms）：两次按下间隔超过该值就重新从第 1 次开始计数。 */
const val EXTRA_TAP_WINDOW_MS = 250L
