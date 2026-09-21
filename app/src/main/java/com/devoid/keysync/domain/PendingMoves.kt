package com.devoid.keysync.domain

import androidx.compose.ui.geometry.Offset

/** Batches may coalesce only within one uninterrupted segment of the input queue. */
internal class PendingMoves {
    class Batch(val pointerId: Int, var delta: Offset)
    private val open = mutableMapOf<Int, Batch>()
    private val pending = mutableSetOf<Batch>()

    @Synchronized fun add(pointerId: Int, delta: Offset): Batch? {
        open[pointerId]?.let { it.delta += delta; return null }
        return Batch(pointerId, delta).also { open[pointerId] = it; pending.add(it) }
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
