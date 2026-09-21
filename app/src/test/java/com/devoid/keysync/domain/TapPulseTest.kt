package com.devoid.keysync.domain
import org.junit.Assert.*
import org.junit.Test

class TapPulseTest {
    @Test fun tapRemainsDownUntilItsScheduledRelease() {
        val tasks = mutableListOf<() -> Unit>()
        val events = mutableListOf<String>()
        val pulse = TapPulse { delay, task -> assertEquals(50L, delay); tasks.add(task) }
        pulse.tap(1, { events.add("down") }, { events.add("up") })
        assertEquals(listOf("down"), events)
        tasks.single()()
        assertEquals(listOf("down", "up"), events)
    }
    @Test fun rapidRepeatDoesNotLetOldTimerReleaseNewTouch() {
        val tasks = mutableListOf<() -> Unit>()
        val events = mutableListOf<String>()
        val pulse = TapPulse { _, task -> tasks.add(task) }
        repeat(2) { pulse.tap(1, { events.add("down") }, { events.add("up") }) }
        tasks[0]()
        assertEquals(listOf("down", "up", "down"), events)
        tasks[1]()
        assertEquals(listOf("down", "up", "down", "up"), events)
    }
    @Test fun mappingResetInvalidatesReleaseForReusedPointer() {
        val tasks = mutableListOf<() -> Unit>()
        var releases = 0
        val pulse = TapPulse { _, task -> tasks.add(task) }
        pulse.tap(1, {}, { releases++ })
        pulse.reset()
        pulse.tap(1, {}, { releases++ })
        tasks[0]()
        assertEquals(0, releases)
        tasks[1]()
        assertEquals(1, releases)
    }
}
