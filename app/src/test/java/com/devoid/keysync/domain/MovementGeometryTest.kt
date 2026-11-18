package com.devoid.keysync.domain
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test
class MovementGeometryTest {
    @Test fun sprintStaysOnOneEllipseForEveryDirection() {
        // 正方向直接落在半轴上。
        assertEquals(Offset(0f, -200f), sprintOffset(1, 0, 200f, 90f))
        // 斜向取椭圆上的点，而不是把两轴距离直接相加（原来会到 ~219px）。
        val wa = sprintOffset(1, -1, 200f, 90f)
        assertEquals(1f, wa.x / wa.y, 0.001f)
        val radius = 1f / kotlin.math.sqrt(0.5f / (90f * 90f) + 0.5f / (200f * 200f))
        assertEquals(radius, wa.getDistance(), 0.01f)
    }

    @Test fun sprintEllipseIsCircularWhenDistancesMatch() {
        val cardinal = sprintOffset(1, 0, 200f, 200f)
        val diagonal = sprintOffset(1, 1, 200f, 200f)
        assertEquals(200f, cardinal.getDistance(), 0.01f)
        assertEquals(200f, diagonal.getDistance(), 0.01f)
        assertEquals(-45f, Math.toDegrees(kotlin.math.atan2(diagonal.y, diagonal.x).toDouble()).toFloat(), 0.01f)
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
