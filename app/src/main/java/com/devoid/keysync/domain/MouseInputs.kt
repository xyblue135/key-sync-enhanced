package com.devoid.keysync.domain

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

// Private namespace: never overlap Android keyboard codes or the WASD/walk ids.
const val KEYCODE_MOUSE_BACK = -10_006
const val KEYCODE_MOUSE_FORWARD = -10_007
const val KEYCODE_SCROLL_UP = -10_008
const val KEYCODE_SCROLL_DOWN = -10_009
const val KEYCODE_SCROLL_LEFT = -10_010
const val KEYCODE_SCROLL_RIGHT = -10_011

val mouseInputKeyCodes = listOf(KEYCODE_LMC, KEYCODE_RMC, KEYCODE_MMC,
    KEYCODE_MOUSE_BACK, KEYCODE_MOUSE_FORWARD,
    KEYCODE_SCROLL_UP, KEYCODE_SCROLL_DOWN, KEYCODE_SCROLL_LEFT, KEYCODE_SCROLL_RIGHT)

fun mouseButtonKeyCode(button: Int): Int? = when (button) {
    MotionEvent.BUTTON_PRIMARY -> KEYCODE_LMC
    MotionEvent.BUTTON_SECONDARY -> KEYCODE_RMC
    MotionEvent.BUTTON_TERTIARY -> KEYCODE_MMC
    MotionEvent.BUTTON_BACK -> KEYCODE_MOUSE_BACK
    MotionEvent.BUTTON_FORWARD -> KEYCODE_MOUSE_FORWARD
    else -> null
}

/** Only mouse-origin Back/Forward are aliases; keyboard Escape/Back stay independent. */
internal fun mouseKeyAlias(keyCode: Int, source: Int): Int? {
    if (source and InputDevice.SOURCE_MOUSE != InputDevice.SOURCE_MOUSE &&
        source and InputDevice.SOURCE_MOUSE_RELATIVE != InputDevice.SOURCE_MOUSE_RELATIVE) return null
    return when (keyCode) {
        KeyEvent.KEYCODE_BACK -> MotionEvent.BUTTON_BACK
        KeyEvent.KEYCODE_FORWARD -> MotionEvent.BUTTON_FORWARD
        else -> null
    }
}

/** Accumulate high-resolution wheels; one complete unit produces one pulse. */
internal class ScrollTracker {
    private var vertical = 0f
    private var horizontal = 0f
    fun reset() { vertical = 0f; horizontal = 0f }

    fun update(v: Float, h: Float): List<Int> {
        if (!v.isFinite() || !h.isFinite()) return emptyList()
        fun accumulate(previous: Float, delta: Float): Float =
            ((if (previous * delta < 0f) 0f else previous) + delta).coerceIn(-16f, 16f)
        vertical = accumulate(vertical, v)
        horizontal = accumulate(horizontal, h)
        val result = mutableListOf<Int>()
        val vCount = vertical.toInt()
        val hCount = horizontal.toInt()
        repeat(kotlin.math.abs(vCount)) { result += if (vCount > 0) KEYCODE_SCROLL_UP else KEYCODE_SCROLL_DOWN }
        repeat(kotlin.math.abs(hCount)) { result += if (hCount > 0) KEYCODE_SCROLL_RIGHT else KEYCODE_SCROLL_LEFT }
        vertical -= vCount
        horizontal -= hCount
        return result
    }
}
