package com.devoid.keysync.domain

import org.junit.Assert.*
import org.junit.Test

class WalkToggleTest {
    private class Rig {
        var now = 0L
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        val events = mutableListOf<String>()
        val walk = WalkToggle({ delay, action -> scheduled.add(now + delay to action); Unit })
        val down: () -> Unit = { events.add("down@$now"); Unit }
        val up: () -> Unit = { events.add("up@$now"); Unit }
        fun finish() {
            while (scheduled.isNotEmpty()) {
                val next = scheduled.minBy { it.first }
                scheduled.remove(next); now = next.first; next.second()
            }
        }
    }
    @Test fun sprintWhileOffDoesNotTap() {
        val r = Rig(); repeat(5) { r.walk.sprint(r.down, r.up) }; r.finish()
        assertFalse(r.walk.enabled); assertTrue(r.events.isEmpty())
    }
    @Test fun toggleCyclesAndSprintCancelsOnlyOnce() {
        val r = Rig(); r.walk.toggle(r.down, r.up); r.finish()
        assertTrue(r.walk.enabled)
        repeat(4) { r.walk.sprint(r.down, r.up) }; r.finish()
        assertFalse(r.walk.enabled); assertEquals(4, r.events.size)
        r.walk.toggle(r.down, r.up); r.finish(); assertTrue(r.walk.enabled)
        r.walk.toggle(r.down, r.up); r.finish(); assertFalse(r.walk.enabled)
    }
    @Test fun immediateSprintKeepsTwoCompletePulses() {
        val r = Rig(); r.walk.toggle(r.down, r.up); r.walk.sprint(r.down, r.up)
        r.finish()
        assertEquals(listOf("down@0", "up@60", "down@100", "up@160"), r.events)
        assertFalse(r.walk.enabled)
    }
    @Test fun profileSwitchWaitsForCancellationThenKeepsState() {
        val r = Rig(); r.walk.toggle(r.down, r.up); r.walk.sprint(r.down, r.up)
        r.walk.whenIdle { r.events.add("switch"); r.walk.interrupt() }; r.finish()
        assertEquals("switch", r.events.last()); assertFalse(r.walk.enabled)
    }
    @Test fun editingPreservesWalkAndCalibrationClearsWithoutTap() {
        val r = Rig(); r.walk.toggle(r.down, r.up); r.finish(); r.walk.interrupt()
        assertTrue(r.walk.enabled)
        r.walk.calibrateOff(); r.walk.sprint(r.down, r.up); r.finish()
        assertFalse(r.walk.enabled); assertEquals(2, r.events.size)
    }
}
