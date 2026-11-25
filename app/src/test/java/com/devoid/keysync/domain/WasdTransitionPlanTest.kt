package com.devoid.keysync.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WasdTransitionPlanTest {
    @Test fun stepsNeverInflateConfiguredDuration() {
        // 旧实现固定 6–18 步、每步最少 6ms，60ms 会被拖到 108ms。
        val steps = WasdTransitionPlan.steps(durationMs = 60, pathLength = 900f)
        val start = 10_000L
        val finish = WasdTransitionPlan.dueTimeMs(start, durationMs = 60, steps = steps, index = steps)
        assertEquals(60L, finish - start)
    }

    @Test fun longPathGetsMoreSamplesWithoutMoreTime() {
        val short = WasdTransitionPlan.steps(60, 60f)
        val long = WasdTransitionPlan.steps(60, 600f)
        assertTrue(long > short)
        assertTrue(long <= 40)
    }

    @Test fun shortPathsStillGetMultipleSamples() {
        // 40ms / 8ms ≈ 5 步；短路径靠时间步数保证采样密度。
        assertEquals(5, WasdTransitionPlan.steps(40, 1f))
    }

    @Test fun dueTimesAreMonotonicAndEndExactly() {
        val steps = 8
        val start = 1_000L
        var prev = start
        for (i in 1..steps) {
            val due = WasdTransitionPlan.dueTimeMs(start, 80, steps, i)
            assertTrue(due >= prev)
            prev = due
        }
        assertEquals(start + 80L, prev)
    }
}
