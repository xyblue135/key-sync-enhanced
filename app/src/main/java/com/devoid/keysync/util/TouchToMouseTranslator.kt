package com.devoid.keysync.util

import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.hypot

/**
 * Translates raw touch events arriving on the floating overlay (typically
 * injected by a screen-mirror / external-keymapper such as 熊猫映射 / Scrcpy
 * / GameKeyboard) into synthetic mouse events that the rest of KeySync
 * (FloatingWindowStateManager.onMouseEvent -> EventHandler.handleMouseButton)
 * already knows how to dispatch to the target game.
 *
 * Why we need this:
 *   - When a PC mouse is forwarded to the phone by 熊猫映射-style tools, the
 *     app's `setOnCapturedPointerListener` does NOT see a real
 *     `MotionEvent.BUTTON_TERTIARY` because those tools inject `ACTION_DOWN/UP`
 *     touch events, not mouse button events.
 *   - Until this translator exists the user can drive the game with the PC
 *     keyboard (WASD + hotkeys) but the PC mouse has no effect.
 *
 * Gesture mapping:
 *   - 1-finger tap               -> left mouse click
 *   - 1-finger long-press        -> middle mouse button (press/release on
 *                                   press/up). Useful for the
 *                                   `shootingModeKeyCode = KEYCODE_MMC` path
 *                                   so that holding the PC mouse middle button
 *                                   toggles shooting-mode just like a physical
 *                                   middle button would.
 *   - 1-finger move              -> mouse move (sends ACTION_HOVER_MOVE so
 *                                   the on-screen mouse pointer tracks it)
 *   - 2-finger tap (both up)     -> right mouse click
 *   - everything else            -> ignored / forwarded as move
 *
 * Zone exclusion:
 *   - The caller supplies `isInIgnoreZone(x, y)` which should return true
 *     when the touch lands on any KeySync-managed UI element (the floating
 *     bubble, menu items, draggable buttons). The translator returns false
 *     in that case so the underlying `clickable` modifier still wins and
 *     the user is not fighting double-triggered input.
 */
class TouchToMouseTranslator(
    private val onMouseEvent: (MotionEvent) -> Boolean,
    private val isInIgnoreZone: (x: Float, y: Float) -> Boolean,
) {
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var maxMoveDist = 0f
    private var pointerCount = 0
    private var twoFingerTapCandidate = false
    private var longPressFired = false
    private var middleButtonHeld = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var longPressEvent: MotionEvent? = null
    private val longPressRunnable = Runnable {
        val event = longPressEvent ?: return@Runnable
        longPressEvent = null
        if (pointerCount == 1 &&
            !longPressFired &&
            !twoFingerTapCandidate &&
            maxMoveDist <= tapSlopPx
        ) {
            longPressFired = true
            pressMiddleButton(event)
        }
        event.recycle()
    }

    /** px threshold for "is this a tap or a drag". ~24dp at 1x density. */
    private val tapSlopPx = 48f
    private val tapMaxDurationMs = 250L
    private val longPressTimeoutMs = 450L
    /** Max delay between the two fingers going up to still count as a 2-finger tap. */
    private val twoFingerTapMaxDurationMs = 350L
    private var secondFingerUpTime = 0L

    fun handle(event: MotionEvent): Boolean {
        // Never translate events that fall on KeySync's own UI elements.
        if (isInIgnoreZone(event.x, event.y)) {
            return false
        }

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleActionDown(event)
            MotionEvent.ACTION_POINTER_DOWN -> handlePointerDown(event)
            MotionEvent.ACTION_MOVE -> handleMove(event)
            MotionEvent.ACTION_POINTER_UP -> handlePointerUp(event)
            MotionEvent.ACTION_UP -> handleActionUp(event)
            MotionEvent.ACTION_CANCEL -> handleCancel()
            else -> false
        }
    }

    private fun handleActionDown(event: MotionEvent): Boolean {
        downTime = event.eventTime
        downX = event.x
        downY = event.y
        maxMoveDist = 0f
        pointerCount = 1
        twoFingerTapCandidate = false
        longPressFired = false
        scheduleLongPress(event)
        // Send a hover-move so the on-screen mouse pointer tracks where the
        // touch began even before any button is "pressed".
        dispatchSynthetic(event, MotionEvent.ACTION_HOVER_MOVE, 0)
        return true
    }

    private fun handlePointerDown(event: MotionEvent): Boolean {
        pointerCount = event.pointerCount
        if (pointerCount == 2) {
            twoFingerTapCandidate = true
            cancelLongPressCandidate()
            // Cancel any pending long-press: a second finger is now on screen.
            if (middleButtonHeld) {
                releaseMiddleButton(event)
            }
            longPressFired = true // suppress 1-finger tap
        }
        return true
    }

    private fun handleMove(event: MotionEvent): Boolean {
        val dx = event.x - downX
        val dy = event.y - downY
        val dist = hypot(dx, dy)
        if (dist > maxMoveDist) maxMoveDist = dist
        if (maxMoveDist > tapSlopPx) cancelLongPressCandidate()

        // Always forward as hover-move so the on-screen mouse pointer follows.
        dispatchSynthetic(event, MotionEvent.ACTION_HOVER_MOVE, 0)
        return true
    }

    private fun handlePointerUp(event: MotionEvent): Boolean {
        // 2nd finger lifted, 1st still down -> remember its up-time for tap.
        if (event.pointerCount == 2) {
            secondFingerUpTime = event.eventTime
        }
        return true
    }

    private fun handleActionUp(event: MotionEvent): Boolean {
        cancelLongPressCandidate()
        val duration = event.eventTime - downTime

        when {
            // Long-press was active: release the middle button.
            middleButtonHeld -> {
                releaseMiddleButton(event)
            }
            // 2-finger tap (right click).
            twoFingerTapCandidate &&
                secondFingerUpTime > 0 &&
                event.eventTime - secondFingerUpTime <= twoFingerTapMaxDurationMs &&
                maxMoveDist <= tapSlopPx * 2 -> {
                pressAndReleaseButton(event, MotionEvent.BUTTON_SECONDARY)
            }
            // 1-finger tap (left click).
            !longPressFired &&
                pointerCount <= 1 &&
                maxMoveDist <= tapSlopPx &&
                duration <= tapMaxDurationMs -> {
                pressAndReleaseButton(event, MotionEvent.BUTTON_PRIMARY)
            }
            // Otherwise it was a drag / hold / etc — already streamed as hover.
        }
        // Touch itself just ended — final hover position with no buttons.
        dispatchSynthetic(event, MotionEvent.ACTION_HOVER_MOVE, 0)
        reset()
        return true
    }

    private fun handleCancel(): Boolean {
        if (middleButtonHeld) {
            // No reliable event time/position on CANCEL, fabricate from now.
            val now = SystemClock.uptimeMillis()
            val fake = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_CANCEL,
                downX, downY, 0
            )
            releaseMiddleButton(fake)
            fake.recycle()
        }
        reset()
        return true
    }

    private fun reset() {
        cancelLongPressCandidate()
        downTime = 0L
        downX = 0f
        downY = 0f
        maxMoveDist = 0f
        pointerCount = 0
        twoFingerTapCandidate = false
        longPressFired = false
        middleButtonHeld = false
        secondFingerUpTime = 0L
    }

    private fun pressMiddleButton(src: MotionEvent) {
        middleButtonHeld = true
        dispatchSynthetic(
            src,
            MotionEvent.ACTION_BUTTON_PRESS,
            MotionEvent.BUTTON_TERTIARY
        )
    }

    private fun releaseMiddleButton(src: MotionEvent) {
        middleButtonHeld = false
        // For synthetic ACTION_BUTTON_RELEASE, keep the modified button in
        // buttonState as an internal fallback. MotionEvent.actionButton cannot
        // be set through the public Android API.
        dispatchSynthetic(
            src,
            MotionEvent.ACTION_BUTTON_RELEASE,
            MotionEvent.BUTTON_TERTIARY
        )
    }

    private fun pressAndReleaseButton(src: MotionEvent, button: Int) {
        dispatchSynthetic(src, MotionEvent.ACTION_BUTTON_PRESS, button)
        dispatchSynthetic(src, MotionEvent.ACTION_BUTTON_RELEASE, button)
    }

    private fun scheduleLongPress(src: MotionEvent) {
        cancelLongPressCandidate()
        longPressEvent = MotionEvent.obtain(src)
        mainHandler.postDelayed(longPressRunnable, longPressTimeoutMs)
    }

    private fun cancelLongPressCandidate() {
        mainHandler.removeCallbacks(longPressRunnable)
        longPressEvent?.recycle()
        longPressEvent = null
    }

    private fun dispatchSynthetic(src: MotionEvent, action: Int, buttonState: Int): Boolean {
        val event = synthesize(src, action, buttonState)
        return try {
            onMouseEvent(event)
        } finally {
            event.recycle()
        }
    }

    /**
     * Build a mouse-flavoured MotionEvent that mirrors [src]'s timing and
     * position. Source is forced to SOURCE_MOUSE so the rest of KeySync's
     * mouse pipeline treats it as a real mouse.
     */
    private fun synthesize(
        src: MotionEvent,
        action: Int,
        buttonState: Int,
    ): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties())
        props[0].id = src.getPointerId(src.actionIndex.coerceAtLeast(0))
        props[0].toolType = MotionEvent.TOOL_TYPE_MOUSE

        val coords = arrayOf(MotionEvent.PointerCoords())
        coords[0].x = src.x
        coords[0].y = src.y
        coords[0].pressure = if (action == MotionEvent.ACTION_BUTTON_PRESS) 1f else 0f
        coords[0].size = 1f

        return MotionEvent.obtain(
            src.downTime,
            src.eventTime,
            action,
            1, // pointerCount
            props, coords,
            0, // metaState
            buttonState, // buttonState
            1f, 1f, // xPrecision, yPrecision
            0, // deviceId
            0, // edgeFlags
            InputDevice.SOURCE_MOUSE, // source <- key for compat
            0  // flags
        )
    }
}
