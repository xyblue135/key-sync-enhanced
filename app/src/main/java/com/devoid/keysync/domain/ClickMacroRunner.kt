package com.devoid.keysync.domain

import androidx.compose.ui.geometry.Offset
import com.devoid.keysync.model.ClickMacro
import com.devoid.keysync.model.screenPosition
import com.devoid.keysync.model.validateClickMacros

/** One-shot, non-blocking sequences. Repeated presses while running are consumed, never queued. */
internal class ClickMacroRunner(
    private val schedule: (Long, () -> Unit) -> Unit,
    private val down: (Offset) -> Unit,
    private val up: () -> Unit,
) {
    private var generation = 0L
    private var touching = false
    private val held = mutableSetOf<Int>()
    var running = false
        private set

    fun handle(key: Int, pressed: Boolean, repeat: Boolean, macros: List<ClickMacro>,
               viewport: Offset, origin: Offset): Boolean {
        val macro = macros.firstOrNull { it.enabled && it.triggerKeyCode == key } ?: return false
        if (!pressed) { held.remove(key); return true }
        if (repeat || !held.add(key) || running) return true
        if (validateClickMacros(listOf(macro)) != null ||
            !viewport.x.isFinite() || !viewport.y.isFinite() || viewport.x <= 0f || viewport.y <= 0f) return true
        val steps = macro.steps.toList()
        val token = ++generation
        running = true
        fun step(index: Int) {
            if (token != generation) return
            touching = true
            down(steps[index].screenPosition(viewport, origin))
            schedule(50) {
                if (token == generation) {
                    touching = false
                    up()
                    if (index == steps.lastIndex) running = false
                    else if (steps[index].delayAfterMs == 0L) step(index + 1)
                    else schedule(steps[index].delayAfterMs) { step(index + 1) }
                }
            }
        }
        step(0)
        return true
    }

    /** Invalidate timers before releasing, so a stale callback cannot affect a later sequence. */
    fun cancel() {
        generation++
        held.clear()
        running = false
        if (touching) { touching = false; up() }
    }
}
