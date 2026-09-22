package com.devoid.keysync.domain
import org.junit.Assert.*
import org.junit.Test
class SprintGateTest {
    @Test fun backwardCancelsUntilShiftIsPressedAgain() {
        val gate = SprintGate(); gate.shiftPressed()
        assertTrue(gate.allowed(false))
        gate.directionPressed(backward = true)
        assertFalse(gate.allowed(true)); assertFalse(gate.allowed(false))
        gate.shiftPressed(); assertTrue(gate.allowed(false))
    }
    @Test fun forwardAndStrafePreserveSprint() {
        val gate = SprintGate(); gate.shiftPressed()
        repeat(3) {
            gate.directionPressed(backward = false)
            assertTrue(gate.allowed(false))
        }
    }
    @Test fun shiftWhileBackwardHeldDoesNotSprintBackward() {
        val gate = SprintGate(); gate.directionPressed(backward = true); gate.shiftPressed()
        assertFalse(gate.allowed(true))
    }
}
