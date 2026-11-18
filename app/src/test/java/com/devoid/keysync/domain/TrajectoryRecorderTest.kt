package com.devoid.keysync.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrajectoryRecorderTest {

    @Test
    fun mousePathIntegratesDeltas() {
        val recording = TrajectoryRecording(
            durationMs = 100,
            wasd = emptyList(),
            mouse = listOf(
                MouseTrajectorySample(0, 10f, -5f),
                MouseTrajectorySample(16, 4f, 2f),
            ),
        )

        val path = recording.mousePath()

        assertEquals(3, path.size)
        assertEquals(0f, path[0].x, 0.001f)
        assertEquals(0f, path[0].y, 0.001f)
        assertEquals(10f, path[1].x, 0.001f)
        assertEquals(-5f, path[1].y, 0.001f)
        assertEquals(14f, path[2].x, 0.001f)
        assertEquals(-3f, path[2].y, 0.001f)
    }

    @Test
    fun stopReturnsNullWhenNothingRecorded() {
        assertTrue(TrajectoryRecorder.start())
        assertNull(TrajectoryRecorder.stop())
    }

    @Test
    fun recordsWasdAndMouseSamples() {
        assertTrue(TrajectoryRecorder.start())
        TrajectoryRecorder.recordWasd(1f, 0f)
        TrajectoryRecorder.recordMouse(12f, -3f)
        Thread.sleep(20)
        TrajectoryRecorder.recordWasd(0.7f, -0.7f)

        val recording = TrajectoryRecorder.stop()

        assertTrue(recording != null)
        assertEquals(2, recording!!.wasd.size)
        assertEquals(1f, recording.wasd.first().x, 0.001f)
        assertEquals(1, recording.mouse.size)
        assertEquals(12f, recording.mouse.first().dx, 0.001f)
    }
}
