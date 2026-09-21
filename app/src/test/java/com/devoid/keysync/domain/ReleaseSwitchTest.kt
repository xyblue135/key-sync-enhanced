package com.devoid.keysync.domain

import org.junit.Assert.*
import org.junit.Test

class ReleaseSwitchTest {
    @Test fun fastSharedKeyWaitsForGameTapAndSwitchesOnlyOnce() {
        var clock = 0L
        val callbacks = mutableListOf<() -> Unit>()
        var switches = 0
        val gate = ReleaseSwitch({ clock }) { delay, task -> assertEquals(50L, delay); callbacks.add(task) }
        gate.down(52)
        clock = 10
        gate.release(52, 60) { switches++ }
        gate.release(52, 60) { switches++ }
        assertEquals(0, switches)
        callbacks.single()()
        assertEquals(1, switches)
    }
    @Test fun longHoldSwitchesOnReleaseWithoutExtraDelay() {
        var clock = 0L
        var switches = 0
        val gate = ReleaseSwitch({ clock }) { _, _ -> fail("unexpected delay") }
        gate.down(8)
        clock = 1000
        gate.release(8, 60) { switches++ }
        assertEquals(1, switches)
    }
    @Test fun layoutChangeCancelsOldPendingSwitch() {
        val callbacks = mutableListOf<() -> Unit>()
        var switches = 0
        val gate = ReleaseSwitch({ 0L }) { _, task -> callbacks.add(task) }
        gate.down(52)
        gate.release(52, 60) { switches++ }
        gate.reset()
        callbacks.single()()
        assertEquals(0, switches)
    }
    @Test fun newestRapidShortcutWins() {
        val callbacks = mutableListOf<() -> Unit>()
        var target = ""
        val gate = ReleaseSwitch({ 0L }) { _, task -> callbacks.add(task) }
        gate.down(52); gate.release(52, 60) { target = "one" }
        gate.down(8); gate.release(8, 60) { target = "two" }
        callbacks.forEach { it() }
        assertEquals("two", target)
    }
}
