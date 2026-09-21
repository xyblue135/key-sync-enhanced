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

class TapModeTouchHandler(private val eventInjector: EventInjector) : MultiModeTouchHandler {
    private val pulse = TapPulse { delay, action -> mainHandler.postDelayed({ action() }, delay) }
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
                up = { eventInjector.releasePointer(pointerId) })
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

class MixedModeTouchHandler(private val eventInjector: EventInjector) : MultiModeTouchHandler {
    private var lastKeyDownMap = hashMapOf<Int, Long>()
    private val minDifference = 200L
    private var generation = 0L

    /**
     * Invalidate delayed releases created by the previous mapping generation.
     * Pointer ids are currently re-assigned when a profile/layout changes, so
     * an old 200 ms callback must never release a newly assigned contact that
     * happens to reuse the same numeric id.
     */
    fun resetPendingActions() {
        generation++
        lastKeyDownMap.clear()
    }

    override fun handleTouchEvent(
        keyEvent: KeyEvent,
        isPressed: Boolean,
        pointerId: Int,
        keyMap: KeyMap
    ): Boolean {
        if (keyEvent.action == MotionEvent.ACTION_DOWN) {
            lastKeyDownMap[pointerId] = System.currentTimeMillis()
            if (isPressed) {
                // Recovering from a stale state: lift the old contact before
                // pressing again so we never end up with two DOWNs for one id.
                eventInjector.releasePointer(pointerId)
            }
            eventInjector.injectPointer(pointerId, keyMap.position, keyMap.end!!)
            return true
        }

        if (keyEvent.action == MotionEvent.ACTION_UP) {
            val downAt = lastKeyDownMap.remove(pointerId) ?: System.currentTimeMillis()
            val held = System.currentTimeMillis() - downAt
            if (held < minDifference) {
                // Very short tap: keep the contact alive for the minimum pulse
                // so the game has a chance to sample it, then lift it.
                val scheduledGeneration = generation
                mainHandler.postDelayed({
                    if (generation == scheduledGeneration) {
                        eventInjector.releasePointer(pointerId)
                    }
                }, minDifference - held)
            } else {
                eventInjector.releasePointer(pointerId)
            }
            return false
        }

        return isPressed
    }
}
