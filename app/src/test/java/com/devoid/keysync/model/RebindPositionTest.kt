package com.devoid.keysync.model
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test
class RebindPositionTest {
    @Test fun newlyBoundGKeepsMeasuredInsetInsteadOfLocalPosition() {
        val item = DraggableItem.VariableKey(1, Offset(100f, 200f), null, 60)
        item.touchCenter = Offset(274f, 230f)
        val rebound = item.copy(keyCode = 35).withMeasuredPositionFrom(item)
        assertEquals(item.touchCenter, rebound.touchCenter)
        assertEquals(35, rebound.keyCode)
        assertNull(item.keyCode)
    }
}
