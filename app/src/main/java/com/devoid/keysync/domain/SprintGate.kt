package com.devoid.keysync.domain

internal class SprintGate {
    private var cancelled = false
    fun shiftPressed() { cancelled = false }
    fun directionPressed(backward: Boolean) { if (backward) cancelled = true }
    fun allowed(backwardHeld: Boolean): Boolean = !backwardHeld && !cancelled
}
