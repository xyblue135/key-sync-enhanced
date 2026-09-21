package com.devoid.keysync.domain
import android.view.MotionEvent.*
import org.junit.Assert.*
import org.junit.Test

class MouseButtonTrackerTest {
    @Test fun downAndButtonPressDoNotDoubleFire() {
        val tracker = MouseButtonTracker()
        assertEquals(listOf(BUTTON_PRIMARY to true), tracker.update(ACTION_DOWN, BUTTON_PRIMARY, 0))
        assertTrue(tracker.update(ACTION_BUTTON_PRESS, BUTTON_PRIMARY, BUTTON_PRIMARY).isEmpty())
        assertEquals(listOf(BUTTON_PRIMARY to false), tracker.update(ACTION_UP, 0, 0))
        assertTrue(tracker.update(ACTION_BUTTON_RELEASE, 0, BUTTON_PRIMARY).isEmpty())
    }
    @Test fun aimReleaseKeepsFireHeld() {
        val tracker = MouseButtonTracker()
        tracker.update(ACTION_MOVE, BUTTON_PRIMARY or BUTTON_SECONDARY, 0)
        assertEquals(listOf(BUTTON_SECONDARY to false), tracker.update(ACTION_MOVE, BUTTON_PRIMARY, 0))
        assertEquals(listOf(BUTTON_PRIMARY to false), tracker.update(ACTION_CANCEL, 0, 0))
    }
    @Test fun translatorReleaseFallbackAndReset() {
        val tracker = MouseButtonTracker()
        assertEquals(listOf(BUTTON_SECONDARY to true), tracker.update(ACTION_BUTTON_PRESS, BUTTON_SECONDARY, 0))
        assertEquals(listOf(BUTTON_SECONDARY to false), tracker.update(ACTION_BUTTON_RELEASE, BUTTON_SECONDARY, 0))
        tracker.update(ACTION_DOWN, BUTTON_PRIMARY, 0)
        tracker.reset()
        assertEquals(listOf(BUTTON_PRIMARY to true), tracker.update(ACTION_DOWN, BUTTON_PRIMARY, 0))
    }
}
