package com.devoid.keysync.domain

import android.view.MotionEvent

/** Normalize DOWN/UP and BUTTON_* without double-firing the same physical edge. */
internal class MouseButtonTracker {
    private var held = 0
    fun reset() { held = 0 }
    fun update(action: Int, buttons: Int, actionButton: Int): List<Pair<Int, Boolean>> {
        val supported = MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_TERTIARY
        val changed = (if (actionButton != 0) actionButton else buttons) and supported
        val next = when (action) {
            MotionEvent.ACTION_BUTTON_PRESS -> held or changed
            MotionEvent.ACTION_BUTTON_RELEASE -> held and changed.inv()
            MotionEvent.ACTION_CANCEL -> 0
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP,
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> buttons and supported
            else -> held
        }
        val edges = listOf(MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_TERTIARY)
            .filter { (held xor next) and it != 0 }
            .map { it to (next and it != 0) }
        held = next
        return edges
    }
}
