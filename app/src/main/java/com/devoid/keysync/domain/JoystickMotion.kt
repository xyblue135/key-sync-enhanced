package com.devoid.keysync.domain

import androidx.compose.ui.geometry.Offset

/** Re-anchor sprint transitions so a floating joystick cannot retain a shifted origin. */
internal class JoystickMotion(
    private val sprintBit: Int,
    private val release: (Int) -> Unit,
    private val start: (Int, Offset, Offset) -> Unit,
    private val move: (Int, Offset) -> Unit,
) {
    private var lastMask = 0
    private var lastPointer = -1
    private var lastCenter = Offset.Zero
    private var lastTarget = Offset.Zero

    fun update(pointer: Int, mask: Int, center: Offset, target: Offset) {
        if (mask == 0) {
            if (lastMask != 0) release(lastPointer)
            reset()
            return
        }
        if (lastMask == mask && lastPointer == pointer && lastCenter == center && lastTarget == target) return
        val reanchor = lastMask != 0 && (
            (lastMask or mask) and sprintBit != 0 || lastPointer != pointer || lastCenter != center)
        if (reanchor) release(lastPointer)
        if (lastMask == 0 || reanchor) start(pointer, center, target) else move(pointer, target)
        lastMask = mask
        lastPointer = pointer
        lastCenter = center
        lastTarget = target
    }

    /**
     * Re-issues a MOVE with a fresh [target] for the already-running contact.
     *
     * Used by the humanization jitter loop. Unlike [update] it never re-anchors:
     * while the sprint bit is present [update] treats *every* call as a sprint
     * transition and lifts + re-presses the contact, so a periodic caller makes
     * the game see the stick let go and restart from neutral on each tick — that
     * is the "跑步时向后顿一下" stutter. Jitter must only drag the live contact.
     */
    fun jitter(pointer: Int, mask: Int, target: Offset) {
        if (lastMask == 0 || lastMask != mask || lastPointer != pointer) return
        if (lastTarget == target) return
        move(pointer, target)
        lastTarget = target
    }

    // The caller already cancelled contacts when replacing mappings or entering the editor.
    fun reset() { lastMask = 0; lastPointer = -1 }
}
