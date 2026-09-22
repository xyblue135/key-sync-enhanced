package com.devoid.keysync.domain

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test

class JoystickMotionTest {
    private val sprint = 512
    private val center = Offset(300f, 700f)
    private class Rig {
        val events = mutableListOf<String>()
        var anchor = Offset.Zero
        var target = Offset.Zero
        val motion = JoystickMotion(512,
            release = { events.add("up:$it") },
            start = { id, c, p -> anchor = c; target = p; events.add("down:$id") },
            move = { id, p -> target = p; events.add("move:$id") })
    }
    @Test fun repeatedLeftAndRightSprintAlwaysReanchorAndReturnStraight() {
        for (side in listOf(-1, 1)) {
            val rig = Rig()
            val forward = center + sprintOffset(1, 0, 220f, 120f)
            val diagonal = center + sprintOffset(1, side, 220f, 120f)
            rig.motion.update(3, 1 or sprint, center, forward)
            repeat(100) {
                rig.motion.update(3, 1 or (if (side < 0) 2 else 8) or sprint, center, diagonal)
                assertEquals(center, rig.anchor)
                // Model a game that lets its floating origin follow a far drag.
                rig.anchor = center + Offset(side * 70f, 0f)
                rig.motion.update(3, 1 or sprint, center, forward)
                assertEquals(Offset(0f, -220f), rig.target - rig.anchor)
                assertEquals(listOf("up:3", "down:3"), rig.events.takeLast(2))
            }
        }
    }
    @Test fun steadySprintDoesNotRestartAndOnlyJoystickIsReleased() {
        val rig = Rig()
        repeat(100) { rig.motion.update(3, 1 or sprint, center, Offset(300f, 480f)) }
        assertEquals(listOf("down:3"), rig.events)
        rig.motion.update(3, 0, center, center)
        assertEquals(listOf("down:3", "up:3"), rig.events)
    }
    @Test fun walkingKeepsContactButEnteringAndLeavingSprintReanchor() {
        val rig = Rig()
        rig.motion.update(3, 1, center, Offset(300f, 600f))
        rig.motion.update(3, 9, center, Offset(350f, 650f))
        assertEquals("move:3", rig.events.last())
        rig.motion.update(3, 9 or sprint, center, Offset(420f, 480f))
        assertEquals(listOf("up:3", "down:3"), rig.events.takeLast(2))
        rig.motion.update(3, 1, center, Offset(300f, 600f))
        assertEquals(listOf("up:3", "down:3"), rig.events.takeLast(2))
        rig.motion.reset(); rig.events.clear()
        rig.motion.update(4, 1 or sprint, center, Offset(300f, 480f))
        assertEquals(listOf("down:4"), rig.events)
    }
}
