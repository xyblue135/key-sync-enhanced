package com.devoid.keysync.domain
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test

class TapPulseTest {
    @Test fun tapRemainsDownUntilItsScheduledRelease() {
        val tasks = mutableListOf<() -> Unit>()
        val events = mutableListOf<String>()
        // 固定采样，断言时序契约本身（真实采样范围见下一个用例）。
        val pulse = TapPulse({ delay, task -> assertEquals(50L, delay); tasks.add(task) }, { 50L })
        pulse.tap(1, { events.add("down") }, { events.add("up") })
        assertEquals(listOf("down"), events)
        tasks.single()()
        assertEquals(listOf("down", "up"), events)
    }

    @Test fun sampledHoldStaysInsideHumanRangeAndIsNotConstant() {
        val samples = (1..200).map { sampleTapHoldDuration() }
        assertTrue(samples.all { it in 50L..125L })
        // 200 次采样几乎不可能全部相同；固定值会让点击节拍可被提取。
        assertTrue(samples.toSet().size > 1)
    }

    @Test fun midMoveFiresBeforeRelease() {
        val tasks = mutableListOf<() -> Unit>()
        val events = mutableListOf<String>()
        val pulse = TapPulse({ _, task -> tasks.add(task) }, { 100L })
        pulse.tap(1, { events.add("down") }, { events.add("up") }, mid = { events.add("mid") })
        assertEquals(listOf("down"), events)
        tasks[0]()
        assertEquals(listOf("down", "mid"), events)
        tasks[1]()
        assertEquals(listOf("down", "mid", "up"), events)
    }

    @Test fun rapidRepeatDoesNotLetOldTimerReleaseNewTouch() {
        val tasks = mutableListOf<() -> Unit>()
        val events = mutableListOf<String>()
        val pulse = TapPulse(schedule = { _, task -> tasks.add(task) })
        repeat(2) { pulse.tap(1, { events.add("down") }, { events.add("up") }) }
        tasks[0]()
        assertEquals(listOf("down", "up", "down"), events)
        tasks[1]()
        assertEquals(listOf("down", "up", "down", "up"), events)
    }
    @Test fun mappingResetInvalidatesReleaseForReusedPointer() {
        val tasks = mutableListOf<() -> Unit>()
        var releases = 0
        val pulse = TapPulse(schedule = { _, task -> tasks.add(task) })
        pulse.tap(1, {}, { releases++ })
        pulse.reset()
        pulse.tap(1, {}, { releases++ })
        tasks[0]()
        assertEquals(0, releases)
        tasks[1]()
        assertEquals(1, releases)
    }

    @Test fun directionChangeInsertsIntermediateMoveWithoutReanchor() {
        val released = mutableListOf<Int>()
        val started = mutableListOf<Offset>()
        val moved = mutableListOf<Offset>()
        val motion = JoystickMotion(
            sprintBit = 1 shl 9,
            release = { released.add(it) },
            start = { _, _, target -> started.add(target) },
            move = { _, target -> moved.add(target) },
        )
        val center = Offset(100f, 100f)
        motion.update(1, 1, center, Offset(100f, 50f))
        assertEquals(1, started.size)
        motion.update(1, 2, center, Offset(50f, 100f))
        // 方向切换：先中点，再到达目标；且不重新起按（不 release）。
        assertEquals(listOf(Offset(75f, 75f), Offset(50f, 100f)), moved)
        assertTrue(released.isEmpty())
    }

    @Test fun sprintTransitionStillReanchors() {
        val released = mutableListOf<Int>()
        val started = mutableListOf<Offset>()
        val motion = JoystickMotion(
            sprintBit = 1 shl 9,
            release = { released.add(it) },
            start = { _, _, target -> started.add(target) },
            move = { _, _ -> fail("sprint transition must not send a bare MOVE") },
        )
        val center = Offset(100f, 100f)
        motion.update(1, 1, center, Offset(100f, 50f))
        motion.update(1, 1 or (1 shl 9), center, Offset(100f, 10f))
        assertEquals(listOf(1), released)
        assertEquals(2, started.size)
    }
}
