package com.devoid.keysync.domain

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent.*
import org.junit.Assert.*
import org.junit.Test

class MouseInputsTest {
    @Test fun sideButtonMotionAndKeyAliasesProduceOnePressAndRelease() {
        val tracker = MouseButtonTracker()
        val alias = mouseKeyAlias(KeyEvent.KEYCODE_BACK, InputDevice.SOURCE_MOUSE)!!
        assertEquals(BUTTON_BACK, alias)
        assertEquals(listOf(BUTTON_BACK to true), tracker.update(ACTION_BUTTON_PRESS, BUTTON_BACK, BUTTON_BACK))
        assertTrue(tracker.update(ACTION_BUTTON_PRESS, alias, alias).isEmpty())
        assertEquals(listOf(BUTTON_BACK to false), tracker.update(ACTION_BUTTON_RELEASE, 0, alias))
        assertTrue(tracker.update(ACTION_BUTTON_RELEASE, 0, BUTTON_BACK).isEmpty())
    }

    @Test fun releasingSideButtonDoesNotLiftFire() {
        val tracker = MouseButtonTracker()
        tracker.update(ACTION_MOVE, BUTTON_PRIMARY or BUTTON_BACK or BUTTON_FORWARD, 0)
        assertEquals(listOf(BUTTON_BACK to false), tracker.update(ACTION_BUTTON_RELEASE, BUTTON_PRIMARY or BUTTON_FORWARD, BUTTON_BACK))
        assertEquals(listOf(BUTTON_PRIMARY to false, BUTTON_FORWARD to false), tracker.update(ACTION_CANCEL, 0, 0))
    }

    @Test fun keyboardBackAndEscapeAreNotMouseAliases() {
        assertNull(mouseKeyAlias(KeyEvent.KEYCODE_BACK, InputDevice.SOURCE_KEYBOARD))
        assertNull(mouseKeyAlias(KeyEvent.KEYCODE_ESCAPE, InputDevice.SOURCE_MOUSE))
        assertEquals(BUTTON_FORWARD, mouseKeyAlias(KeyEvent.KEYCODE_FORWARD, InputDevice.SOURCE_MOUSE_RELATIVE))
    }

    @Test fun privateCodesAreUniqueAndBothSideButtonsHaveMappings() {
        assertEquals(mouseInputKeyCodes.size, mouseInputKeyCodes.distinct().size)
        assertTrue(mouseInputKeyCodes.all { it < 0 && it != KEYCODE_WASD && it != -10_005 })
        assertEquals(KEYCODE_MOUSE_BACK, mouseButtonKeyCode(BUTTON_BACK))
        assertEquals(KEYCODE_MOUSE_FORWARD, mouseButtonKeyCode(BUTTON_FORWARD))
    }

    @Test fun highResolutionWheelAccumulatesFractionsAndMultipleNotches() {
        val wheel = ScrollTracker()
        assertTrue(wheel.update(0.25f, 0f).isEmpty())
        assertTrue(wheel.update(0.25f, 0f).isEmpty())
        assertEquals(listOf(KEYCODE_SCROLL_UP), wheel.update(0.5f, 0f))
        assertEquals(List(3) { KEYCODE_SCROLL_DOWN }, wheel.update(-3f, 0f))
    }

    @Test fun directionReversalDoesNotRequireCancellingOldFraction() {
        val wheel = ScrollTracker()
        wheel.update(0.75f, 0f)
        assertEquals(listOf(KEYCODE_SCROLL_DOWN), wheel.update(-1f, 0f))
    }

    @Test fun horizontalAndVerticalDirectionsAreIndependent() {
        val wheel = ScrollTracker()
        assertEquals(listOf(KEYCODE_SCROLL_UP, KEYCODE_SCROLL_LEFT), wheel.update(1f, -1f))
        assertEquals(listOf(KEYCODE_SCROLL_RIGHT), wheel.update(0f, 1f))
    }

    @Test fun resetDropsPartialMovementFromPreviousMode() {
        val wheel = ScrollTracker()
        wheel.update(0.75f, 0.75f)
        wheel.reset()
        assertTrue(wheel.update(0.25f, 0.25f).isEmpty())
    }

    @Test fun invalidOrExtremeDeviceValuesCannotCreateUnboundedWork() {
        val wheel = ScrollTracker()
        assertTrue(wheel.update(Float.NaN, 0f).isEmpty())
        assertTrue(wheel.update(0f, Float.POSITIVE_INFINITY).isEmpty())
        assertEquals(16, wheel.update(Float.MAX_VALUE, 0f).size)
        assertTrue(wheel.update(0f, 0f).isEmpty())
    }
}
