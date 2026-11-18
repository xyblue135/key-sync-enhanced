package com.devoid.keysync.domain

/** WASD 摇杆向量样本：x 为右正、y 为下正，长度约 0–1.3（疾跑会超过 1）。 */
data class WasdTrajectorySample(val timeMs: Long, val x: Float, val y: Float)

/** 射击模式下的鼠标增量样本，单位是注入给游戏的屏幕像素。 */
data class MouseTrajectorySample(val timeMs: Long, val dx: Float, val dy: Float)

/** 鼠标位移积分后的点，用于绘制视角移动轨迹。 */
data class TrajectoryPoint(val timeMs: Long, val x: Float, val y: Float)

data class TrajectoryRecording(
    val durationMs: Long,
    val wasd: List<WasdTrajectorySample>,
    val mouse: List<MouseTrajectorySample>,
) {
    val isEmpty: Boolean get() = wasd.isEmpty() && mouse.isEmpty()

    /** 把鼠标增量累加成一条从 (0,0) 开始的路径。 */
    fun mousePath(): List<TrajectoryPoint> {
        if (mouse.isEmpty()) return emptyList()
        val points = ArrayList<TrajectoryPoint>(mouse.size + 1)
        var x = 0f
        var y = 0f
        points += TrajectoryPoint(mouse.first().timeMs, 0f, 0f)
        mouse.forEach {
            x += it.dx
            y += it.dy
            points += TrajectoryPoint(it.timeMs, x, y)
        }
        return points
    }
}

/**
 * 轨迹录制器：只记录 WASD 摇杆向量与射击模式的鼠标增量，不记录其他按键。
 *
 * - 全部数据只存在内存里，停止后交给渲染器，不落盘。
 * - 单次最长 [MAX_DURATION_MS]，超时后忽略后续样本，由服务负责自动停止。
 * - 鼠标按至少 [MOUSE_MIN_INTERVAL_MS] 采样，避免 1000Hz 鼠标把内存打满。
 */
object TrajectoryRecorder {
    const val MAX_DURATION_MS = 60_000L

    private const val MAX_WASD_SAMPLES = 30_000
    private const val MAX_MOUSE_SAMPLES = 15_000
    private const val MOUSE_MIN_INTERVAL_MS = 8L
    private const val WASD_MIN_INTERVAL_MS = 16L
    private const val WASD_EPSILON = 0.004f

    private val lock = Any()
    private val wasdSamples = ArrayList<WasdTrajectorySample>(2048)
    private val mouseSamples = ArrayList<MouseTrajectorySample>(1024)

    private var startedAtNs = 0L
    private var lastMouseAtMs = -1L
    private var lastWasdAtMs = -1L
    private var lastWasdX = 0f
    private var lastWasdY = 0f

    @Volatile
    private var recording = false

    val isRecording: Boolean get() = recording

    fun elapsedMs(): Long = synchronized(lock) {
        if (!recording) return 0L
        elapsedLocked()
    }

    /** 开始新录制；已经在录制时返回 false。 */
    fun start(): Boolean = synchronized(lock) {
        if (recording) return false
        wasdSamples.clear()
        mouseSamples.clear()
        startedAtNs = System.nanoTime()
        lastMouseAtMs = -1L
        lastWasdAtMs = -1L
        lastWasdX = 0f
        lastWasdY = 0f
        recording = true
        true
    }

    /** 停止并返回快照；没有有效样本时返回 null。 */
    fun stop(): TrajectoryRecording? = synchronized(lock) {
        if (!recording) return null
        val duration = elapsedLocked()
        recording = false
        if (wasdSamples.isEmpty() && mouseSamples.isEmpty()) return null
        TrajectoryRecording(
            durationMs = duration,
            wasd = wasdSamples.toList(),
            mouse = mouseSamples.toList(),
        )
    }

    /** 记录一帧 WASD 向量（已归一化）。 */
    fun recordWasd(x: Float, y: Float) {
        if (!recording) return
        synchronized(lock) {
            if (!recording) return
            val now = elapsedLocked()
            if (now > MAX_DURATION_MS || wasdSamples.size >= MAX_WASD_SAMPLES) return
            val samePoint = kotlin.math.abs(x - lastWasdX) < WASD_EPSILON &&
                kotlin.math.abs(y - lastWasdY) < WASD_EPSILON
            val tooSoon = lastWasdAtMs >= 0 && now - lastWasdAtMs < WASD_MIN_INTERVAL_MS
            if (samePoint && tooSoon) return
            wasdSamples += WasdTrajectorySample(now, x, y)
            lastWasdAtMs = now
            lastWasdX = x
            lastWasdY = y
        }
    }

    /** 记录射击模式实际注入的鼠标增量。 */
    fun recordMouse(dx: Float, dy: Float) {
        if (!recording) return
        if (dx == 0f && dy == 0f) return
        synchronized(lock) {
            if (!recording) return
            val now = elapsedLocked()
            if (now > MAX_DURATION_MS || mouseSamples.size >= MAX_MOUSE_SAMPLES) return
            if (lastMouseAtMs >= 0 && now - lastMouseAtMs < MOUSE_MIN_INTERVAL_MS) {
                // 合并到上一个采样点，保留总位移，避免高频鼠标丢位移。
                val last = mouseSamples.lastOrNull() ?: return
                mouseSamples[mouseSamples.lastIndex] = last.copy(
                    dx = last.dx + dx,
                    dy = last.dy + dy,
                )
                return
            }
            mouseSamples += MouseTrajectorySample(now, dx, dy)
            lastMouseAtMs = now
        }
    }

    private fun elapsedLocked(): Long =
        ((System.nanoTime() - startedAtNs) / 1_000_000L).coerceAtLeast(0L)
}
