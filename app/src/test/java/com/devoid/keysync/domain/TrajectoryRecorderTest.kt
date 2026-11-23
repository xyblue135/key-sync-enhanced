package com.devoid.keysync.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrajectoryRecorderTest {

    @Test
    fun stopReturnsNullWhenNothingRecorded() {
        assertTrue(TrajectoryRecorder.start())
        assertNull(TrajectoryRecorder.stop(1080f, 1920f))
    }

    @Test
    fun recordsPointerTrackAndKeys() {
        assertTrue(TrajectoryRecorder.start())
        TrajectoryRecorder.recordDown(3, 100f, 200f)
        TrajectoryRecorder.recordMoveTo(3, 160f, 260f)
        TrajectoryRecorder.recordKey(51, true)
        TrajectoryRecorder.recordUp(3)
        TrajectoryRecorder.recordKey(51, false)

        val recording = TrajectoryRecorder.stop(1080f, 2400f)

        assertNotNull(recording)
        recording!!
        assertEquals(1080f, recording.viewportWidth, 0.001f)
        assertEquals(2400f, recording.viewportHeight, 0.001f)
        assertEquals(1, recording.tracks.size)
        val track = recording.tracks.first()
        assertEquals(3, track.pointerId)
        assertTrue(track.samples.size >= 2)
        assertEquals(100f, track.samples.first().x, 0.001f)
        assertEquals(160f, track.samples.last().x, 0.001f)
        assertNotNull(track.upTimeMs)
        assertEquals(2, recording.keys.size)
        assertTrue(recording.keys.first().pressed)
        assertFalse(recording.keys.last().pressed)
    }

    @Test
    fun recordMoveByAccumulatesFromLastPoint() {
        assertTrue(TrajectoryRecorder.start())
        TrajectoryRecorder.recordDown(9, 500f, 500f)
        TrajectoryRecorder.recordMoveBy(9, 12f, -5f)
        TrajectoryRecorder.recordMoveBy(9, 4f, 2f)

        val track = TrajectoryRecorder.stop(1000f, 1000f)!!.tracks.single()

        assertEquals(516f, track.samples.last().x, 0.001f)
        assertEquals(497f, track.samples.last().y, 0.001f)
    }

    @Test
    fun reDownWithoutUpKeepsSingleTrack() {
        assertTrue(TrajectoryRecorder.start())
        TrajectoryRecorder.recordDown(1, 10f, 10f)
        TrajectoryRecorder.recordDown(1, 40f, 40f)
        TrajectoryRecorder.recordUp(1)

        val recording = TrajectoryRecorder.stop(100f, 100f)!!

        assertEquals(1, recording.tracks.size)
        assertEquals(2, recording.tracks.single().samples.size)
    }

    @Test
    fun stopClosesTrackStillHeld() {
        assertTrue(TrajectoryRecorder.start())
        TrajectoryRecorder.recordDown(4, 1f, 2f)

        val track = TrajectoryRecorder.stop(100f, 200f)!!.tracks.single()

        assertNull(track.upTimeMs)
        assertTrue(track.isActiveAt(0))
    }
}
