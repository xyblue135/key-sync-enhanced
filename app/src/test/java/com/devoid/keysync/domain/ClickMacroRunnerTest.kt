package com.devoid.keysync.domain

import androidx.compose.ui.geometry.Offset
import com.devoid.keysync.model.*
import org.junit.Assert.*
import org.junit.Test

class ClickMacroRunnerTest {
    private class Fixture {
        data class Task(val at: Long, val action: () -> Unit)
        var now = 0L
        val tasks = mutableListOf<Task>()
        val events = mutableListOf<String>()
        val positions = mutableListOf<Offset>()
        val runner = ClickMacroRunner(
            schedule = { delay, action -> tasks += Task(now + delay, action) },
            down = { positions += it; events += "down@$now" },
            up = { events += "up@$now" },
        )
        fun advance(to: Long) {
            while (tasks.any { it.at <= to }) {
                val task = tasks.minBy { it.at }
                tasks.remove(task)
                now = task.at
                task.action()
            }
            now = to
        }
        val macro = ClickMacro("macro", "Sequence", KEYCODE_MOUSE_BACK, listOf(
            MacroStep("one", 0.25f, 0.5f, 100),
            MacroStep("two", 0.75f, 0.5f, 5000)), enabled = true)
        fun press(m: ClickMacro = macro, repeat: Boolean = false) = runner.handle(m.triggerKeyCode, true, repeat,
            listOf(m), Offset(1000f, 500f), Offset(10f, 20f))
        fun release(m: ClickMacro = macro) = runner.handle(m.triggerKeyCode, false, false,
            listOf(m), Offset(1000f, 500f), Offset(10f, 20f))
    }

    @Test fun firstClickIsImmediateAndStepsUseReleaseThenConfiguredInterval() {
        val f = Fixture()
        assertTrue(f.press())
        assertEquals(listOf("down@0"), f.events)
        f.release()
        f.advance(49)
        assertEquals(1, f.events.size)
        f.advance(149)
        assertEquals(listOf("down@0", "up@50"), f.events)
        f.advance(200)
        assertEquals(listOf("down@0", "up@50", "down@150", "up@200"), f.events)
        assertEquals(listOf(Offset(260f, 270f), Offset(760f, 270f)), f.positions)
        assertFalse(f.runner.running) // Last step's delay is deliberately ignored.
    }

    @Test fun holdAndKeyboardRepeatDoNotRunASecondSequence() {
        val f = Fixture()
        f.press()
        f.advance(300)
        f.press()
        f.press(repeat = true)
        assertEquals(2, f.positions.size)
        f.release()
        f.press()
        assertEquals(3, f.positions.size)
    }

    @Test fun busyTriggerIsConsumedWithoutQueuingOrInterrupting() {
        val f = Fixture()
        f.press(); f.release()
        f.advance(80)
        assertTrue(f.press()); f.release()
        f.advance(10000)
        assertEquals(2, f.positions.size)
    }

    @Test fun cancelReleasesCurrentTouchAndInvalidatesEveryRemainingStep() {
        val f = Fixture()
        f.press()
        f.advance(20)
        f.runner.cancel()
        f.advance(10000)
        assertEquals(listOf("down@0", "up@20"), f.events)
        assertFalse(f.runner.running)
    }

    @Test fun cancellationDuringGapDoesNotSendAnExtraUpOrNextDown() {
        val f = Fixture()
        f.press(); f.release()
        f.advance(80)
        f.runner.cancel()
        f.advance(10000)
        assertEquals(listOf("down@0", "up@50"), f.events)
    }

    @Test fun oldTimerCannotReleaseNewSequenceAfterFocusOrProfileReset() {
        val f = Fixture()
        f.press()
        f.advance(20)
        f.runner.cancel()
        f.press()
        f.advance(50)
        assertEquals(listOf("down@0", "up@20", "down@20"), f.events)
        f.advance(70)
        assertEquals("up@70", f.events.last())
    }

    @Test fun disabledMacroAllowsOrdinaryBindingToHandleInput() {
        val f = Fixture()
        assertFalse(f.press(f.macro.copy(enabled = false)))
        assertTrue(f.events.isEmpty())
    }

    @Test fun invalidImportedPointNeverInjectsOrPartiallyRuns() {
        val f = Fixture()
        assertTrue(f.press(f.macro.copy(steps = f.macro.steps + MacroStep("bad", Float.NaN))))
        assertTrue(f.events.isEmpty())
    }

    @Test fun zeroIntervalStillLiftsEachClickBeforeStartingNext() {
        val f = Fixture()
        f.press(f.macro.copy(steps = f.macro.steps.map { it.copy(delayAfterMs = 0) }))
        f.advance(100)
        assertEquals(listOf("down@0", "up@50", "down@50", "up@100"), f.events)
    }
}
