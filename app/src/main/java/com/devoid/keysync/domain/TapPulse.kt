package com.devoid.keysync.domain

/** Non-blocking tap lifetime; stale timers cannot lift a reused pointer. */
internal class TapPulse(private val schedule: (Long, () -> Unit) -> Unit) {
    private class Release(val action: () -> Unit)
    private val pending = mutableMapOf<Int, Release>()

    fun tap(pointerId: Int, down: () -> Unit, up: () -> Unit) {
        pending.remove(pointerId)?.action?.invoke()
        down()
        val release = Release(up)
        pending[pointerId] = release
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
