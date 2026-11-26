package com.devoid.keysync.domain

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import com.devoid.keysync.model.EventInjector
import com.devoid.keysync.model.KeyMap
import com.devoid.keysync.model.MultiModeTouchHandler

/**
 * Every handler returns whether the key is still considered "pressed" after the
 * event. That flag must reliably fall back to `false` once the finger is gone:
 * if it stays `true`, the next press takes the "already pressed" branch, lifts
 * the pointer instead of pressing it, and the contact is left stuck down. A
 * stuck pointer means the pointer count never returns to zero, so the *first*
 * finger of the next gesture is sent as ACTION_POINTER_DOWN — an invalid
 * gesture that the receiving app throws away, which is exactly the
 * "only one finger registers" symptom.
 */
internal val mainHandler = Handler(Looper.getMainLooper())

class TapModeTouchHandler(
    private val eventInjector: EventInjector,
    private val midMove: ((Int, KeyMap) -> Unit)? = null,
) : MultiModeTouchHandler {
    private val pulse = TapPulse(schedule = { delay, action -> mainHandler.postDelayed({ action() }, delay) })
    fun resetPendingActions() = pulse.reset()

    override fun handleTouchEvent(
        keyEvent: KeyEvent,
        isPressed: Boolean,
        pointerId: Int,
        keyMap: KeyMap
    ): Boolean {
        if (keyEvent.action == MotionEvent.ACTION_DOWN) {
            pulse.tap(pointerId,
                down = { eventInjector.injectPointer(pointerId, keyMap.position, keyMap.end!!) },
                up = { eventInjector.releasePointer(pointerId) },
                mid = midMove?.let { move -> { move(pointerId, keyMap) } })
        }
        // Physical UP does not shorten the pulse; its timer owns the release.
        return false
    }
}

class HoldModeTouchHandler(private val eventInjector: EventInjector) : MultiModeTouchHandler {
    override fun handleTouchEvent(
        keyEvent: KeyEvent,
        isPressed: Boolean,
        pointerId: Int,
        keyMap: KeyMap
    ): Boolean {
        return if (keyEvent.action == MotionEvent.ACTION_DOWN) {
            eventInjector.injectPointer(pointerId, keyMap.position, keyMap.end!!)
            true
        } else if (keyEvent.action == MotionEvent.ACTION_UP) {
            eventInjector.releasePointer(pointerId)
            false
        } else
            isPressed
    }
}

