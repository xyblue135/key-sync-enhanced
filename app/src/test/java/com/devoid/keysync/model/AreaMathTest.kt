package com.devoid.keysync.model

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class AreaMathTest {
    private fun assertOffset(expected: Offset, actual: Offset, delta: Float = 0.01f) {
        assertEquals(expected.x, actual.x, delta)
        assertEquals(expected.y, actual.y, delta)
    }

    @Test
    fun rectangleCentroidIsCenter() {
        val points = listOf(
            Offset(0f, 0f),
            Offset(10f, 0f),
            Offset(10f, 10f),
            Offset(0f, 10f),
        )
        assertOffset(Offset(5f, 5f), AreaMath.centroid(points))
    }

    @Test
    fun triangleCentroidIsAverageOfVertices() {
        val points = listOf(Offset(0f, 0f), Offset(6f, 0f), Offset(0f, 6f))
        assertOffset(Offset(2f, 2f), AreaMath.centroid(points))
    }

    @Test
    fun fewerThanThreePointsFallsBackToAverage() {
        assertOffset(Offset(1.5f, 2.5f), AreaMath.centroid(listOf(Offset(1f, 2f), Offset(2f, 3f))))
    }

    @Test
    fun degeneratePolygonFallsBackToAverage() {
        // 三点共线：面积为零，退回平均点而不是原点。
        val points = listOf(Offset(0f, 0f), Offset(5f, 5f), Offset(10f, 10f))
        assertOffset(Offset(5f, 5f), AreaMath.centroid(points))
    }

    @Test
    fun emptyReturnsZero() {
        assertOffset(Offset.Zero, AreaMath.centroid(emptyList()))
    }
}
