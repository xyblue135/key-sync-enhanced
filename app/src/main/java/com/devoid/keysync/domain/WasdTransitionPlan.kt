package com.devoid.keysync.domain

import kotlin.math.ceil

/**
 * WASD 换向过渡的时间规划。
 *
 * 关键原则：**配置时长就是总时长上限**。以前按路程固定 6–18 步、每步最少 6ms，
 * 路径一长就会把 60ms 拖成 108ms。这里改成：
 * - 步数只由「时长 / 目标帧间隔」和「路径 / 最大段长」共同决定，绝不为凑步数拉长总时长；
 * - 每一步按绝对时间点对齐（[dueTimeMs]），避免 delay 漂移累积。
 *
 * 纯函数，便于单测。
 */
object WasdTransitionPlan {
    /** 目标步进间隔；约一帧，兼顾平滑与开销量。 */
    const val TARGET_STEP_MS = 8f

    /** 每段最大像素长度，路径长时增加采样点但不延长总时长。 */
    const val MAX_SEGMENT_PX = 34f

    private const val MIN_STEPS = 2
    private const val MAX_STEPS = 40

    fun steps(durationMs: Int, pathLength: Float): Int {
        val duration = durationMs.coerceAtLeast(1)
        val byTime = ceil(duration / TARGET_STEP_MS).toInt()
        val byPath = if (pathLength > 0f) {
            ceil(pathLength / MAX_SEGMENT_PX).toInt()
        } else 0
        return maxOf(byTime, byPath).coerceIn(MIN_STEPS, MAX_STEPS)
    }

    /** 第 [index] 步（1 起）应到达的绝对时间点。 */
    fun dueTimeMs(startedAt: Long, durationMs: Int, steps: Int, index: Int): Long =
        startedAt + durationMs.toLong() * index.coerceIn(0, steps) / steps.coerceAtLeast(1)
}
