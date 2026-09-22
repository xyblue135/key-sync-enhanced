package com.devoid.keysync.domain

/** Recognize a new physical press even if focus loss swallowed the previous UP. */
internal class TogglePress {
    private var lastDownTime: Long? = null
    fun down(downTime: Long, repeatCount: Int): Boolean {
        if (repeatCount != 0 || lastDownTime == downTime) return false
        lastDownTime = downTime
        return true
    }
    fun reset() { lastDownTime = null }
}
