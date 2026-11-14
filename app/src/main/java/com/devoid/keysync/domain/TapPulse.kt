package com.devoid.keysync.domain

/** Non-blocking tap lifetime; stale timers cannot lift a reused pointer. */
internal class TapPulse(private val schedule: (Long, () -> Unit) -> Unit) {
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
        // 按下与抬起之间补一帧微小位移：真实手指不会在两次采样间完全静止。
        // 只是多插一条 MOVE，不改变抬起时刻，也不引入等待。
        if (mid != null) {
            schedule(25L) {
                if (pending[pointerId] === release) mid()
            }
        }
        schedule(50L) {
            if (pending[pointerId] === release) {
                pending.remove(pointerId)
                release.action()
            }
        }
    }

    // Caller cancels all injected touches before resetting the mapping.
    fun reset() { pending.clear() }
}
