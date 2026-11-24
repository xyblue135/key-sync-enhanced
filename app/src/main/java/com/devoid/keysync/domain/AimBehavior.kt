package com.devoid.keysync.domain

/**
 * 射击模式鼠标瞄准的「回中 / 位移」预设。
 *
 * 所有影响鼠标位移手感的策略参数集中在这里，EventHandler 只按当前预设执行，
 * 因此几种手感可以随时切换、互不干扰。想再加一种手感，只需在这里加一项并给出参数。
 */
enum class AimRecenterMode {
    /** 早期版本：顶边立即同步回中，超出边界的位移直接丢弃，不缓存、不补发。 */
    CLASSIC,

    /** 顶边即回中 + 补发溢出；落点深度反向偏置，单次回中换来的连续行程最长。 */
    INSTANT,

    /** 累计溢出约 30% 半宽才回中补发；小溢出直接忽略，落点轻度反向偏置。 */
    BUFFERED,
    ;

    val displayName: String
        get() = when (this) {
            CLASSIC -> "经典"
            INSTANT -> "即回中"
            BUFFERED -> "缓冲补发"
        }

    val description: String
        get() = when (this) {
            CLASSIC -> "顶边立刻回中，超出部分丢弃。最接近早期版本手感；快速甩枪会少转一点。"
            INSTANT -> "顶边立刻回中并补发溢出，落点靠对侧。持续转向最不容易停顿。"
            BUFFERED -> "攒到约 30% 范围才回中补发，抬指次数最少，但顶边时会短暂停一下。"
        }

    /** 是否缓存溢出并在回中后补发；CLASSIC 直接丢弃。 */
    val replaysOverflow: Boolean get() = this != CLASSIC

    /** 触发一次回中所需的累计溢出量（px）。 */
    fun overflowThresholdPx(areaHalf: Float): Float = when (this) {
        BUFFERED -> if (areaHalf > 0f) {
            (areaHalf * BUFFERED_OVERFLOW_RATIO).coerceAtLeast(BUFFERED_MIN_OVERFLOW_PX)
        } else {
            BUFFERED_MIN_OVERFLOW_PX * 2f
        }

        else -> INSTANT_MIN_OVERFLOW_PX
    }

    /** 回中落点朝溢出反方向的偏置比例（相对活动范围半宽）；CLASSIC 固定回中心。 */
    fun landingBiasRatio(random: Float): Float = when (this) {
        INSTANT -> 0.78f + random * 0.12f
        BUFFERED -> 0.55f + random * 0.10f
        CLASSIC -> 0f
    }

    companion object {
        const val INSTANT_MIN_OVERFLOW_PX = 2f
        const val BUFFERED_OVERFLOW_RATIO = 0.30f
        const val BUFFERED_MIN_OVERFLOW_PX = 24f
    }
}
