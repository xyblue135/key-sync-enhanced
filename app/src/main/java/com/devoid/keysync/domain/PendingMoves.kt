package com.devoid.keysync.domain

import androidx.compose.ui.geometry.Offset

/** Batches may coalesce only within one uninterrupted segment of the input queue. */
internal class PendingMoves {
    /**
     * [generation] 用于摇杆类绝对目标：同一触点出现更新的一代时，旧代尚未执行的
     * MOVE 必须被丢弃，否则快速换向会先走完上一段旧轨迹，表现为拖尾。
     */
    class Batch(val pointerId: Int, var delta: Offset, var generation: Long = 0L)

    private val open = mutableMapOf<Int, Batch>()
    private val pending = mutableSetOf<Batch>()

    @Synchronized fun add(pointerId: Int, delta: Offset, generation: Long = 0L): Batch? {
        open[pointerId]?.let {
            it.delta += delta
            if (generation > it.generation) it.generation = generation
            return null
        }
        return Batch(pointerId, delta, generation).also { open[pointerId] = it; pending.add(it) }
    }

    @Synchronized fun take(batch: Batch): Offset? {
        if (open[batch.pointerId] === batch) open.remove(batch.pointerId)
        return if (pending.remove(batch)) batch.delta else null
    }

    // Keep already queued data, but later moves must get a new queue entry.
    @Synchronized fun seal() { open.clear() }

    @Synchronized fun cancel(pointerId: Int) {
        open.remove(pointerId)
        pending.removeAll { it.pointerId == pointerId }
    }

    @Synchronized fun clear() { open.clear(); pending.clear() }
}
