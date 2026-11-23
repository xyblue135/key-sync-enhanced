package com.devoid.keysync.domain

import kotlin.math.hypot

/** 触点的一个采样点：屏幕坐标（px），时间相对录制开始。 */
data class TrajectoryPointSample(val timeMs: Long, val x: Float, val y: Float)

/**
 * 一个触点的完整轨迹：按下 → 移动 → 抬起。
 *
 * [upTimeMs] 为 null 表示录制结束时触点仍然按住。
 * 触点来源包括：普通按键、WASD 摇杆、准心、轮盘、点击宏、连按落点、F 滑动、光标拖拽。
 */
data class TrajectoryPointerTrack(
    val pointerId: Int,
    val downTimeMs: Long,
    val upTimeMs: Long?,
    val samples: List<TrajectoryPointSample>,
) {
    fun isActiveAt(timeMs: Long): Boolean =
        timeMs >= downTimeMs && (upTimeMs == null || timeMs < upTimeMs)

    /** 该时刻触点的位置：取时间戳不超过 [timeMs] 的最后一个采样点。 */
    fun positionAt(timeMs: Long): TrajectoryPointSample? {
        var low = 0
        var high = samples.lastIndex
        var result: TrajectoryPointSample? = null
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (samples[mid].timeMs <= timeMs) {
                result = samples[mid]
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }
}

/** 物理按键 / 鼠标按键的按下与抬起，用于回放里的按键条。 */
data class TrajectoryKeyEvent(val timeMs: Long, val keyCode: Int, val pressed: Boolean)

/** 回放用的按钮类型：WASD 摇杆按椭圆绘制，其余按键按方形/圆形绘制。 */
enum class TrajectoryItemKind { WASD, BUTTON }

/**
 * 停止录制时的布局快照（屏幕像素坐标）。
 *
 * 只保留回放需要的几何与文案，不持有可变的 [com.devoid.keysync.model.DraggableItem]。
 */
data class TrajectoryItemSnapshot(
    val kind: TrajectoryItemKind,
    val label: String,
    val centerX: Float,
    val centerY: Float,
    val radiusX: Float,
    val radiusY: Float,
)

data class TrajectoryRecording(
    val durationMs: Long,
    val viewportWidth: Float,
    val viewportHeight: Float,
    val tracks: List<TrajectoryPointerTrack>,
    val keys: List<TrajectoryKeyEvent>,
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && keys.isEmpty()
}

/**
 * 轨迹录制器：记录所有实际注入的触点与所有按下的物理键。
 *
 * - 只记录「注入给游戏的触点坐标」，因此宏、连点、F 滑动、滚轮、准心回中都能覆盖到。
 * - 全部数据只存在内存里，停止后交给渲染器，不落盘。
 * - 单次最长 [MAX_DURATION_MS]，超时后不再接受新样本，由服务负责自动停止。
 * - 移动按时间 + 距离双阈值抽稀，避免 1000Hz 鼠标把内存打满。
 */
object TrajectoryRecorder {
    const val MAX_DURATION_MS = 60_000L

    private const val MAX_SAMPLES = 90_000
    private const val MAX_TRACK_SAMPLES = 15_000
    private const val MAX_KEY_EVENTS = 4_000
    private const val MIN_MOVE_INTERVAL_MS = 6L
    private const val MIN_MOVE_DISTANCE_PX = 1.2f

    private class OpenTrack(
        val id: Int,
        val downMs: Long,
        val points: ArrayList<TrajectoryPointSample> = ArrayList(16),
    )

    private val lock = Any()
    private val finished = ArrayList<TrajectoryPointerTrack>()
    private val open = LinkedHashMap<Int, OpenTrack>()
    private val keys = ArrayList<TrajectoryKeyEvent>()

    private var sampleCount = 0
    private var startedAtNs = 0L

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
        finished.clear()
        open.clear()
        keys.clear()
        sampleCount = 0
        startedAtNs = System.nanoTime()
        recording = true
        true
    }

    /**
     * 停止并返回快照；没有有效轨迹或按键时返回 null。
     *
     * [viewportWidth] / [viewportHeight] 是录制时悬浮层覆盖的屏幕尺寸（px），
     * 回放按它把触点坐标缩放成视频坐标。
     */
    fun stop(viewportWidth: Float, viewportHeight: Float): TrajectoryRecording? =
        synchronized(lock) {
            if (!recording) return null
            val duration = elapsedLocked().coerceAtMost(MAX_DURATION_MS)
            recording = false
            open.values.toList().forEach { finished += it.toTrack(null) }
            open.clear()
            val tracks = finished.sortedBy { it.downTimeMs }
            finished.clear()
            if (tracks.isEmpty() && keys.isEmpty()) return null
            val snapshot = TrajectoryRecording(
                durationMs = duration,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                tracks = tracks,
                keys = keys.toList(),
            )
            keys.clear()
            snapshot
        }

    /** 触点按下（同一 id 未抬起就再次按下时按移动处理，避免伪造一个抬起）。 */
    fun recordDown(pointerId: Int, x: Float, y: Float) {
        if (!recording) return
        if (!x.isFinite() || !y.isFinite()) return
        synchronized(lock) {
            if (!recording) return
            val now = elapsedLocked()
            if (now > MAX_DURATION_MS) return
            val existing = open[pointerId]
            if (existing != null) {
                appendLocked(existing, now, x, y, force = true)
                return
            }
            if (!reserveSampleLocked()) return
            val track = OpenTrack(pointerId, now)
            track.points += TrajectoryPointSample(now, x, y)
            open[pointerId] = track
        }
    }

    /** 触点移动到绝对屏幕坐标。 */
    fun recordMoveTo(pointerId: Int, x: Float, y: Float) {
        if (!recording) return
        if (!x.isFinite() || !y.isFinite()) return
        synchronized(lock) {
            if (!recording) return
            val track = open[pointerId] ?: return
            val now = elapsedLocked()
            if (now > MAX_DURATION_MS) return
            appendLocked(track, now, x, y, force = false)
        }
    }

    /** 触点按增量移动（鼠标/准心/轮盘用的是偏移量）。 */
    fun recordMoveBy(pointerId: Int, dx: Float, dy: Float) {
        if (!recording) return
        if (!dx.isFinite() || !dy.isFinite()) return
        synchronized(lock) {
            if (!recording) return
            val track = open[pointerId] ?: return
            val last = track.points.lastOrNull() ?: return
            val now = elapsedLocked()
            if (now > MAX_DURATION_MS) return
            appendLocked(track, now, last.x + dx, last.y + dy, force = false)
        }
    }

    /** 触点抬起。对未记录的 id 幂等。 */
    fun recordUp(pointerId: Int) {
        if (!recording) return
        synchronized(lock) {
            val track = open.remove(pointerId) ?: return
            finished += track.toTrack(elapsedLocked())
        }
    }

    /** 抬起所有仍在按住的触点（布局重建 / 服务销毁时调用）。 */
    fun recordAllUp() {
        if (!recording) return
        synchronized(lock) {
            if (open.isEmpty()) return
            val now = elapsedLocked()
            open.values.forEach { finished += it.toTrack(now) }
            open.clear()
        }
    }

    /** 记录一次物理按键 / 鼠标按键。自动重复的按下只记第一次。 */
    fun recordKey(keyCode: Int, pressed: Boolean) {
        if (!recording) return
        synchronized(lock) {
            if (!recording) return
            if (keys.size >= MAX_KEY_EVENTS) return
            val now = elapsedLocked()
            if (now > MAX_DURATION_MS) return
            keys += TrajectoryKeyEvent(now, keyCode, pressed)
        }
    }

    private fun reserveSampleLocked(): Boolean {
        if (sampleCount >= MAX_SAMPLES) return false
        sampleCount++
        return true
    }

    private fun appendLocked(track: OpenTrack, now: Long, x: Float, y: Float, force: Boolean) {
        if (track.points.size >= MAX_TRACK_SAMPLES) return
        val last = track.points.last()
        if (!force) {
            val dt = now - last.timeMs
            val distance = hypot(x - last.x, y - last.y)
            if (dt < MIN_MOVE_INTERVAL_MS && distance < MIN_MOVE_DISTANCE_PX) return
        }
        if (!reserveSampleLocked()) return
        track.points += TrajectoryPointSample(now, x, y)
    }

    private fun OpenTrack.toTrack(upTimeMs: Long?): TrajectoryPointerTrack =
        TrajectoryPointerTrack(
            pointerId = id,
            downTimeMs = downMs,
            upTimeMs = upTimeMs?.coerceAtLeast(downMs),
            samples = points.toList(),
        )

    private fun elapsedLocked(): Long =
        ((System.nanoTime() - startedAtNs) / 1_000_000L).coerceAtLeast(0L)
}
