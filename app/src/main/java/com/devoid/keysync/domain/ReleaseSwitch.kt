package com.devoid.keysync.domain

/** Preserve a shared key's touch pulse before changing the mapping. */
internal class ReleaseSwitch(
    private val now: () -> Long,
    private val schedule: (Long, () -> Unit) -> Unit,
) {
    private val downs = mutableMapOf<Int, Long>()
    private var generation = 0
    fun down(key: Int) { downs[key] = now() }
    fun release(key: Int, minimumPulseMs: Long, switch: () -> Unit) {
        val started = downs.remove(key) ?: return
        val token = ++generation
        val remaining = (minimumPulseMs - (now() - started)).coerceAtLeast(0)
        if (remaining == 0L) switch() else schedule(remaining) {
            if (generation == token) switch()
        }
    }
    fun reset() { generation++; downs.clear() }
}
