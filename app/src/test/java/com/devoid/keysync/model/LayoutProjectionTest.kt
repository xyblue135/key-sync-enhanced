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
    @Test fun portraitToLandscapeRotatesInsteadOfStretching() {
        val button = DraggableItem.FixedKey(1, Offset(700f, 1700f), DraggableItemType.KEY, 35, 100)
        val projected = projectLayout(listOf(button), Offset(1080f, 2400f), Offset(2400f, 1080f)).single() as DraggableItem.FixedKey
        assertEquals(1700f, projected.position.x, 0.01f)
        assertEquals(380f, projected.position.y, 0.01f)
    }

    @Test fun landscapeToPortraitRotatesInsteadOfStretching() {
        val button = DraggableItem.FixedKey(1, Offset(2500f, 900f), DraggableItemType.KEY, 35, 100)
        val projected = projectLayout(listOf(button), Offset(2608f, 1200f), Offset(1200f, 2608f)).single() as DraggableItem.FixedKey
        assertEquals(300f, projected.position.x, 0.01f)
        assertEquals(2500f, projected.position.y, 0.01f)
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
    @Test fun sameSizeProjectionKeepsEdgeButtonsExactlyInPlace() {
        // 重启时曾经先用整屏 bounds 投影，再被首帧写盘：y<0 的按钮被 coerceIn
        // 压到 margin，永久下移。同尺寸下必须原样返回，包括负坐标。
        val topEdge = DraggableItem.FixedKey(1, Offset(900f, -12f), DraggableItemType.KEY, 35, 100)
        val projected = projectLayout(
            listOf(topEdge), Offset(1080f, 2210f), Offset(1080f, 2210f)
        ).single()
        assertEquals(-12f, projected.position.y, 0.01f)
        assertEquals(900f, projected.position.x, 0.01f)
    }

    @Test fun keyCannotBeProjectedPastTheLeftOrTopEdge() {
        // 完全跑到屏幕外的旧坐标（-500）仍要拉回来，但允许保留半个按钮的
        // 越界量——顶部按钮位置常常就是 y<0，不能一律压到 0 造成永久下移。
        val button = DraggableItem.VariableKey(1, Offset(-500f, -500f), 35, 100)
        val projected = projectLayout(listOf(button), Offset(2608f, 1200f), Offset(2400f, 1080f)).single()
        assertTrue(projected.position.x >= -50f)
        assertTrue(projected.position.y >= -50f)
    }

    @Test fun slightlyNegativeTopEdgeSurvivesResolutionChange() {
        // 重启时视口从 2400 变成 2330（状态栏/insets）也不该把顶部按钮压进屏幕。
        val topEdge = DraggableItem.FixedKey(1, Offset(900f, -12f), DraggableItemType.KEY, 35, 100)
        val projected = projectLayout(
            listOf(topEdge), Offset(1080f, 2400f), Offset(1080f, 2330f)
        ).single()
        assertTrue("y 应保持在边缘外，而不是被压到 0", projected.position.y < 0f)
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
