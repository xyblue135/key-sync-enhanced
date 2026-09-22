package com.devoid.keysync.domain
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test
class MovementGeometryTest {
    @Test fun diagonalKeepsFullForwardSprintDistance() {
        assertEquals(Offset(0f, -200f), sprintOffset(1, 0, 200f, 90f))
        assertEquals(Offset(-90f, -200f), sprintOffset(1, -1, 200f, 90f))
        assertEquals(Offset(90f, -200f), sprintOffset(1, 1, 200f, 90f))
    }
    @Test fun wheelClampsToCircleRatherThanSquare() {
        val clamped = withinWheel(Offset(100f, 100f), 50f)
        assertEquals(50f, clamped.getDistance(), 0.001f)
        assertEquals(clamped.x, clamped.y, 0.001f)
    }
    @Test fun wheelAllowsInnerMovementAndReturningFromEdge() {
        assertEquals(Offset(10f, -20f), withinWheel(Offset(10f, -20f), 50f))
        val edge = withinWheel(Offset(200f, 0f), 50f)
        assertEquals(Offset(40f, 0f), withinWheel(edge + Offset(-10f, 0f), 50f))
    }
}
