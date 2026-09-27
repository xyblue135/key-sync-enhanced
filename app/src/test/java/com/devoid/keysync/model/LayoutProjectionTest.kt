package com.devoid.keysync.model

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test

class LayoutProjectionTest {
    @Test fun repeatedRotationPreservesPositionSizeAndSwapAnchor() {
        val source = DraggableItem.VariableKey(1, Offset(1900f, 700f), 35, 100,
            anchorPosition = Offset(1600f, 800f))
        val landscape = Offset(2608f, 1200f)
        val portrait = Offset(1200f, 2608f)
        var current: List<DraggableItem> = listOf(source)
        repeat(100) {
            current = projectLayout(current, landscape, portrait)
            current = projectLayout(current, portrait, landscape)
        }
        val result = current.single() as DraggableItem.VariableKey
        assertEquals(source.position.x, result.position.x, 0.1f)
        assertEquals(source.position.y, result.position.y, 0.1f)
        assertEquals(source.anchorPosition!!.x, result.anchorPosition!!.x, 0.1f)
        assertEquals(100, result.size)
        result.position = Offset.Zero
        assertEquals(Offset(1900f, 700f), source.position)
    }
    @Test fun enlargedButtonFitsVisibleRightEdge() {
        val source = DraggableItem.VariableKey(1, Offset(2500f, 1000f), 35, 100)
        val result = projectLayout(listOf(source), Offset(2608f, 1200f), Offset(2400f, 1080f), 2f).single()
        // graphicsLayer scales around the center: right = offset + size + extra half-size.
        assertTrue(result.position.x + 150f <= 2400f)
        assertTrue(result.position.y + 150f <= 1080f)
    }

    @Test fun landscapeToLandscapeKeepsRightEdgeVisible() {
        val button = DraggableItem.FixedKey(1, Offset(2500f, 1100f), DraggableItemType.KEY, 35, 80)
        val projected = projectLayout(listOf(button), Offset(2608f, 1200f), Offset(2400f, 1080f)).single() as DraggableItem.FixedKey
        assertTrue(projected.position.x + projected.size <= 2400f)
        assertTrue(projected.position.y + projected.size <= 1080f)
    }
    @Test fun portraitToLandscapeProjectsAxesWithoutKeepingStalePortraitPixels() {
        val button = DraggableItem.FixedKey(1, Offset(700f, 1700f), DraggableItemType.KEY, 35, 100)
        val projected = projectLayout(listOf(button), Offset(1080f, 2400f), Offset(2400f, 1080f)).single() as DraggableItem.FixedKey
        assertEquals(1642.857f, projected.position.x, 0.01f)
        assertEquals(724.348f, projected.position.y, 0.01f)
    }
    @Test fun wasdGeometryScalesWithOrientation() {
        val group = DraggableItem.WASDGroup(1, Offset.Zero,
            center = Offset(300f, 700f), w = Offset(300f, 600f), a = Offset(200f, 700f),
            s = Offset(300f, 800f), d = Offset(400f, 700f))
        val projected = projectLayout(listOf(group), Offset(1000f, 1000f), Offset(2000f, 1000f)).single() as DraggableItem.WASDGroup
        assertEquals(600f, projected.center.x, 0.01f)
        assertEquals(600f, projected.w.y, 0.01f)
        assertEquals(800f, projected.d.x, 0.01f)
    }
}
