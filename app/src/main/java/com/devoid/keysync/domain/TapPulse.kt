package com.devoid.keysync.domain

import kotlin.random.Random

/**
 * Non-blocking tap lifetime; stale timers cannot lift a reused pointer.
 *
 * 按住时长不再固定 50ms：真人点击的 down→up 时长有分布（多数 50–85ms，偶尔 85–125ms），
 * 固定值本身就是可提取的时序特征。只改抬起时刻，不阻塞按键响应路径。
 */
internal class TapPulse(
    private val schedule: (Long, () -> Unit) -> Unit,
    private val sampleHoldMs: () -> Long = ::sampleTapHoldDuration,
) {
    private class Release(val action: () -> Unit)
    private val pending = mutableMapOf<Int, Release>()

    fun tap(
        pointerId: Int,
        down: () -> Unit,
        up: () -> Unit,
        mid: (() -> Unit)? = null,
    ) {
        pending.remove(pointerId)?.action?.invoke()
        down()
        val release = Release(up)
        pending[pointerId] = release
        val holdMs = sampleHoldMs().coerceIn(30L, 200L)
        // 按下与抬起之间补一帧微小位移：真实手指不会在两次采样间完全静止。
        // 只是多插一条 MOVE，不改变抬起时刻，也不引入等待。
        if (mid != null) {
            schedule((holdMs / 2).coerceAtLeast(10L)) {
                if (pending[pointerId] === release) mid()
            }
        }
        schedule(holdMs) {
            if (pending[pointerId] === release) {
                pending.remove(pointerId)
                release.action()
            }
        }
    }

    // Caller cancels all injected touches before resetting the mapping.
    fun reset() { pending.clear() }
}

/** 点击按住时长：多数落在 50–85ms，约 15% 为 85–125ms，形成真实的长尾。 */
internal fun sampleTapHoldDuration(): Long =
    if (Random.nextFloat() < 0.15f) Random.nextLong(85L, 126L)
    else Random.nextLong(50L, 86L)
