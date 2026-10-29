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
    @Test fun projectionLeavesReportedWasdGeometryAlone() {
        // center/w/a/s/d are absolute screen coordinates that Compose re-measures
        // from the real layout. Projecting them scaled them about the screen
        // origin, which detached the joystick origin and deflection from the
        // drawn group: walking survived that, sprint did not.
        val group = DraggableItem.WASDGroup(1, Offset.Zero,
            center = Offset(300f, 700f), w = Offset(300f, 600f), a = Offset(200f, 700f),
            s = Offset(300f, 800f), d = Offset(400f, 700f))
        val projected = projectLayout(listOf(group), Offset(1000f, 1000f), Offset(2000f, 1000f)).single() as DraggableItem.WASDGroup
        assertEquals(Offset(300f, 700f), projected.center)
        assertEquals(Offset(300f, 600f), projected.w)
        assertEquals(Offset(200f, 700f), projected.a)
        assertEquals(Offset(300f, 800f), projected.s)
        assertEquals(Offset(400f, 700f), projected.d)
    }

    @Test fun projectionKeepsTheSprintDeflectionIntact() {
        // The sprint target is derived from (w - center) and (d - center). If the
        // projection scaled those, the deflection would shrink and the game would
        // keep registering a walk while no longer registering a sprint.
        val group = DraggableItem.WASDGroup(1, Offset(100f, 600f), scale = 1f,
            center = Offset(250f, 750f), w = Offset(250f, 600f), a = Offset(100f, 750f),
            s = Offset(250f, 900f), d = Offset(400f, 750f))
        val projected = projectLayout(listOf(group), Offset(2608f, 1200f), Offset(2400f, 1080f)).single() as DraggableItem.WASDGroup
        assertEquals((group.w - group.center).getDistance(), (projected.w - projected.center).getDistance(), 0.01f)
        assertEquals((group.d - group.center).getDistance(), (projected.d - projected.center).getDistance(), 0.01f)
    }
    @Test fun keyCannotBeProjectedPastTheLeftOrTopEdge() {
        // A button saved off the left edge -- the classic landscape symptom --
        // must come back inside the viewport instead of staying cut off.
        val button = DraggableItem.VariableKey(1, Offset(-500f, -500f), 35, 100)
        val projected = projectLayout(listOf(button), Offset(2608f, 1200f), Offset(2400f, 1080f)).single()
        assertTrue(projected.position.x >= 0f)
        assertTrue(projected.position.y >= 0f)
    }
    @Test fun wasdGroupIsKeptInsideTheProjectedViewport() {
        // The WASD group has no cached size, so its extent comes from the
        // geometry it reported; without that it projected straight off the edge.
        val group = DraggableItem.WASDGroup(1, Offset(5000f, 5000f), scale = 1f,
            center = Offset(5100f, 5100f), w = Offset(5100f, 5000f), a = Offset(5000f, 5100f),
            s = Offset(5100f, 5200f), d = Offset(5200f, 5100f))
        val projected = projectLayout(listOf(group), Offset(2608f, 1200f), Offset(2400f, 1080f)).single()
        assertTrue(projected.position.x >= 0f)
        assertTrue(projected.position.y >= 0f)
        assertTrue(projected.position.x + 200f <= 2400f)
        assertTrue(projected.position.y + 200f <= 1080f)
    }
}
