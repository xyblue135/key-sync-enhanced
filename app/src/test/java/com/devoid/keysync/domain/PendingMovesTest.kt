package com.devoid.keysync.domain

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test

class PendingMovesTest {
    @Test fun coalescesHighRateMovement() {
        val moves = PendingMoves()
        val batch = moves.add(1, Offset(1f, 2f))!!
        repeat(999) { assertNull(moves.add(1, Offset(1f, 2f))) }
        assertEquals(Offset(1000f, 2000f), moves.take(batch))
        assertNull(moves.take(batch))
    }

    @Test fun recenterDoesNotLetOldFlushConsumeNewContactMovement() {
        val moves = PendingMoves()
        val old = moves.add(1, Offset(20f, 0f))!!
        moves.cancel(1)
        moves.seal() // UP then DOWN
        val fresh = moves.add(1, Offset(3f, 0f))!!
        assertNull(moves.take(old))
        assertNull(moves.add(1, Offset(4f, 0f)))
        assertEquals(Offset(7f, 0f), moves.take(fresh))
    }

    @Test fun buttonTransitionsSeparateMovementSegments() {
        val moves = PendingMoves()
        val before = moves.add(1, Offset(5f, 0f))!!
        moves.seal() // WASD DOWN must stay between these two MOVE events
        val after = moves.add(1, Offset(9f, 0f))!!
        assertEquals(Offset(5f, 0f), moves.take(before))
        assertEquals(Offset(9f, 0f), moves.take(after))
    }

    @Test fun clearInvalidatesEveryPendingSegmentButNotNewInput() {
        val moves = PendingMoves()
        val first = moves.add(1, Offset(5f, 0f))!!
        moves.seal()
        val second = moves.add(2, Offset(9f, 0f))!!
        moves.clear()
        val fresh = moves.add(1, Offset(1f, 1f))!!
        assertNull(moves.take(first))
        assertNull(moves.take(second))
        assertEquals(Offset(1f, 1f), moves.take(fresh))
    }
}
