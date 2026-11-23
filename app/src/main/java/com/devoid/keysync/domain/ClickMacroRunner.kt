package com.devoid.keysync.domain

import androidx.compose.ui.geometry.Offset
import com.devoid.keysync.model.ClickMacro
import com.devoid.keysync.model.jitteredIntervalAfter
import com.devoid.keysync.model.screenPosition
import com.devoid.keysync.model.validateClickMacros

/** One-shot, non-blocking sequences. Repeated presses while running are consumed, never queued. */
internal class ClickMacroRunner(
    private val schedule: (Long, () -> Unit) -> Unit,
    private val down: (Offset) -> Unit,
    private val up: () -> Unit,
    /** 点击按住时长采样；测试可注入固定值。 */
    private val sampleHoldMs: () -> Long = ::sampleMacroHoldDuration,
) {
    private var generation = 0L
    private var touching = false
    private val held = mutableSetOf<Int>()

    /** 调度回调在后台线程，handle/cancel 在主线程；用锁保护 running/touching 状态。 */
    private val lock = Any()

    var running = false
        private set

    fun handle(key: Int, pressed: Boolean, repeat: Boolean, macros: List<ClickMacro>,
               viewport: Offset, origin: Offset): Boolean {
        val macro = macros.firstOrNull { it.enabled && it.triggerKeyCode == key } ?: return false
        if (!pressed) {
            synchronized(lock) { held.remove(key) }
            return true
        }
        if (repeat) return true
        synchronized(lock) {
            if (!held.add(key) || running) return true
            if (validateClickMacros(listOf(macro)) != null ||
                !viewport.x.isFinite() || !viewport.y.isFinite() || viewport.x <= 0f || viewport.y <= 0f) {
                return true
            }
            val steps = macro.steps.toList()
            val token = ++generation
            running = true
            fun step(index: Int) {
                synchronized(lock) {
                    if (token != generation) return
                    touching = true
                    down(steps[index].screenPosition(viewport, origin))
                    // 宏的点击也按人手时长采样，不再固定 50ms。
                    schedule(sampleHoldMs().coerceIn(30L, 200L)) {
                        synchronized(lock) {
                            if (token == generation) {
                                touching = false
                                up()
                                if (index == steps.lastIndex) running = false
                                else {
                                    val delay = macro.jitteredIntervalAfter(index)
                                    if (delay == 0L) step(index + 1)
                                    else schedule(delay) { step(index + 1) }
                                }
                            }
                        }
                    }
                }
            }
            step(0)
        }
        return true
    }

    /** Invalidate timers before releasing, so a stale callback cannot affect a later sequence. */
    fun cancel() {
        synchronized(lock) {
            generation++
            held.clear()
            running = false
            if (touching) { touching = false; up() }
        }
    }
}

/** 宏点击按住时长：多数 45–75ms，约 15% 为 75–95ms。 */
internal fun sampleMacroHoldDuration(): Long =
    if (kotlin.random.Random.nextFloat() < 0.15f) kotlin.random.Random.nextLong(75L, 96L)
    else kotlin.random.Random.nextLong(45L, 76L)
