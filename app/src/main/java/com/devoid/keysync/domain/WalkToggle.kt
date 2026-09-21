package com.devoid.keysync.domain

/** Serializes toggle taps; Shift only cancels a walk that we enabled. */
internal class WalkToggle(
    private val schedule: (Long, () -> Unit) -> Unit,
    private val changed: (Boolean) -> Unit = {},
) {
    var enabled = false
        private set
    private var issued = false
    private var generation = 0
    private var busy = false
    private val pending = ArrayDeque<Pair<() -> Unit, () -> Unit>>()

    fun toggle(down: () -> Unit, up: () -> Unit) {
        enabled = !enabled
        changed(enabled)
        pending.addLast(down to up)
        drain()
    }

    fun sprint(down: () -> Unit, up: () -> Unit) {
        if (enabled) toggle(down, up)
    }

    private fun drain() {
        if (busy || pending.isEmpty()) return
        busy = true
        val (down, up) = pending.removeFirst()
        val token = generation
        down()
        issued = !issued
        schedule(60L) {
            if (token == generation) {
                up()
                schedule(40L) {
                    if (token == generation) { busy = false; drain() }
                }
            }
        }
    }

    fun whenIdle(action: () -> Unit) {
        val token = generation
        if (!busy) action() else schedule(20L) {
            if (token == generation) whenIdle(action)
        }
    }

    // Caller clears contacts. Preserve toggles already sent to the game.
    fun interrupt() {
        generation++
        pending.clear()
        busy = false
        enabled = issued
        changed(enabled)
    }

    fun calibrateOff() {
        interrupt()
        issued = false
        enabled = false
        changed(false)
    }
}
