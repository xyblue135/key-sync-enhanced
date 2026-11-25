package com.devoid.keysync.domain

import android.os.SystemClock

/**
 * 输入链路诊断统计（仅内存，供 debug 面板读取）。
 *
 * 目标不是做完整监控，而是回答三个问题：
 * 1. 事件在串行注入队列里等了多久（P50/P95/P99）——判断是不是注入吞吐不足。
 * 2. 实际每秒注入多少事件——和鼠标回报率对比。
 * 3. WASD 过渡配置时长与实际时长差多少、回中间隔多少——判断手感问题在算法还是调度。
 *
 * 采样用固定大小的环形数组，写入只做一次数组赋值，避免在高频鼠标路径上
 * 引入锁竞争以外的额外开销。
 */
object InputStats {
    private const val CAPACITY = 256
    private const val RATE_WINDOW_MS = 1000L

    private val lock = Any()
    private val waits = LongArray(CAPACITY)
    private var waitIndex = 0
    private var waitCount = 0
    private val injections = LongArray(CAPACITY)
    private var injectionIndex = 0
    private var injectionCount = 0

    @Volatile private var enqueued = 0L
    @Volatile private var dequeued = 0L
    @Volatile private var transitionConfiguredMs = 0
    @Volatile private var transitionActualMs = 0L
    @Volatile private var recenterCount = 0L
    @Volatile private var lastRecenterIntervalMs = 0L
    @Volatile private var mouseAxisMode = "-"

    data class Snapshot(
        val enqueued: Long,
        val dequeued: Long,
        val waitP50: Long,
        val waitP95: Long,
        val waitP99: Long,
        val waitMax: Long,
        val injectPerSec: Int,
        val transitionConfiguredMs: Int,
        val transitionActualMs: Long,
        val recenterCount: Long,
        val recenterIntervalMs: Long,
        val mouseAxisMode: String,
    )

    /** 入队时调用，返回时间戳供出队时计算等待。 */
    fun onEnqueued(): Long {
        enqueued++
        return SystemClock.uptimeMillis()
    }

    /** 出队并完成注入后调用。 */
    fun onDequeued(enqueuedAt: Long) {
        val now = SystemClock.uptimeMillis()
        dequeued++
        synchronized(lock) {
            waits[waitIndex] = (now - enqueuedAt).coerceAtLeast(0L)
            waitIndex = (waitIndex + 1) % CAPACITY
            if (waitCount < CAPACITY) waitCount++
            injections[injectionIndex] = now
            injectionIndex = (injectionIndex + 1) % CAPACITY
            if (injectionCount < CAPACITY) injectionCount++
        }
    }

    fun onTransition(configuredMs: Int, actualMs: Long) {
        transitionConfiguredMs = configuredMs
        transitionActualMs = actualMs
    }

    fun onRecenter(intervalMs: Long) {
        recenterCount++
        lastRecenterIntervalMs = intervalMs.coerceAtLeast(0L)
    }

    fun setMouseAxisMode(mode: String) {
        mouseAxisMode = mode
    }

    fun snapshot(): Snapshot {
        val waitCopy: LongArray
        val recentInjections: Int
        val now = SystemClock.uptimeMillis()
        synchronized(lock) {
            waitCopy = waits.copyOf(waitCount)
            var count = 0
            for (i in 0 until injectionCount) {
                if (now - injections[i] <= RATE_WINDOW_MS) count++
            }
            recentInjections = count
        }
        waitCopy.sort()
        fun pct(p: Double): Long =
            if (waitCopy.isEmpty()) 0L
            else waitCopy[((waitCopy.size - 1) * p).toInt().coerceIn(0, waitCopy.size - 1)]
        return Snapshot(
            enqueued = enqueued,
            dequeued = dequeued,
            waitP50 = pct(0.50),
            waitP95 = pct(0.95),
            waitP99 = pct(0.99),
            waitMax = waitCopy.lastOrNull() ?: 0L,
            injectPerSec = recentInjections,
            transitionConfiguredMs = transitionConfiguredMs,
            transitionActualMs = transitionActualMs,
            recenterCount = recenterCount,
            recenterIntervalMs = lastRecenterIntervalMs,
            mouseAxisMode = mouseAxisMode,
        )
    }
}
