package com.devoid.keysync.domain

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WasdScribblePathTest {

    private val center = Offset(500f, 500f)
    private val joystickRadius = 200f
    private val intent = Offset(680f, 500f)

    @Test
    fun generatesRequestedSampleCount() {
        val points = WasdScribblePath.generate(
            center = center,
            intent = intent,
            joystickRadius = joystickRadius,
            amplitude = 60f,
            samples = 240,
            random = Random(1),
        )
        assertEquals(240, points.size)
    }

    @Test
    fun allPointsStayInsideJoystickAnnulus() {
        val points = WasdScribblePath.generate(
            center = center,
            intent = intent,
            joystickRadius = joystickRadius,
            amplitude = 90f,
            samples = 1200,
            random = Random(2),
        )
        val minRadius = joystickRadius * 0.30f
        val maxRadius = joystickRadius * 0.98f
        points.forEach { point ->
            val distance = (point - center).getDistance()
            assertTrue("distance=$distance", distance >= minRadius - 0.5f)
            assertTrue("distance=$distance", distance <= maxRadius + 0.5f)
        }
    }

    @Test
    fun pathStaysBiasedTowardIntentDirection() {
        val points = WasdScribblePath.generate(
            center = center,
            intent = intent,
            joystickRadius = joystickRadius,
            amplitude = 45f,
            samples = 1200,
            random = Random(7),
        )
        val mean = points.fold(Offset.Zero) { acc, point -> acc + point } / points.size.toFloat()
        // 意图点在右侧：均值必须明显偏右，否则按 W 时角色会原地打转。
        assertTrue("mean=$mean", mean.x > center.x + joystickRadius * 0.25f)
    }

    @Test
    fun zeroAmplitudeCollapsesToIntent() {
        val points = WasdScribblePath.generate(
            center = center,
            intent = intent,
            joystickRadius = joystickRadius,
            amplitude = 0f,
            samples = 100,
            random = Random(3),
        )
        assertEquals(100, points.size)
        points.forEach { assertEquals(intent, it) }
    }

    @Test
    fun sameSeedProducesSamePath() {
        val first = WasdScribblePath.generate(center, intent, joystickRadius, 50f, 300, Random(42))
        val second = WasdScribblePath.generate(center, intent, joystickRadius, 50f, 300, Random(42))
        assertEquals(first, second)
    }

    @Test
    fun differentSeedsProduceDifferentPaths() {
        val first = WasdScribblePath.generate(center, intent, joystickRadius, 50f, 300, Random(1))
        val second = WasdScribblePath.generate(center, intent, joystickRadius, 50f, 300, Random(2))
        assertNotEquals(first, second)
    }

    @Test
    fun consecutivePointsKeepMoving() {
        val points = WasdScribblePath.generate(center, intent, joystickRadius, 70f, 600, Random(11))
        val moving = points.zipWithNext().count { (a, b) -> (b - a).getDistance() > 0.5f }
        assertTrue("moving=$moving", moving > points.size / 2)
    }
}
