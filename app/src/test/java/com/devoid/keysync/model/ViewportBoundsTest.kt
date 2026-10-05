package com.devoid.keysync.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overlay is the coordinate space buttons live in, so "off the edge" means
 * off the overlay. These cases pin the left and top edges as well: the
 * projection tests only ever asserted the right and bottom ones, which is how a
 * layout could pass every test and still be cut off on the left in landscape.
 */
class ViewportBoundsTest {

    private val landscape = Size(2400f, 1080f)
    private val button = Size(100f, 100f)

    @Test
    fun buttonCannotBeDraggedPastTheLeftEdge() {
        val clamped = clampToViewport(Offset(-400f, 500f), button, 1f, landscape)
        assertEquals(0f, clamped.x, 0.001f)
        assertEquals(500f, clamped.y, 0.001f)
    }

    @Test
    fun buttonCannotBeDraggedPastTheTopEdge() {
        val clamped = clampToViewport(Offset(1000f, -400f), button, 1f, landscape)
        assertEquals(1000f, clamped.x, 0.001f)
        assertEquals(0f, clamped.y, 0.001f)
    }

    @Test
    fun buttonCannotBeDraggedPastTheRightOrBottomEdge() {
        val clamped = clampToViewport(Offset(9000f, 9000f), button, 1f, landscape)
        assertEquals(2300f, clamped.x, 0.001f)
        assertEquals(980f, clamped.y, 0.001f)
    }

    @Test
    fun positionInsideTheViewportIsLeftAlone() {
        val position = Offset(1000f, 500f)
        assertEquals(position, clampToViewport(position, button, 1f, landscape))
    }

    @Test
    fun scaledButtonKeepsItsDrawnBoxInsideTheViewport() {
        // Modifier.scale grows the node about its centre, so the drawn box sticks
        // out past the layout box on both sides.
        val clamped = clampToViewport(Offset(-900f, 9000f), button, 1.5f, landscape)
        assertEquals(25f, clamped.x, 0.001f)
        assertEquals(955f, clamped.y, 0.001f)
    }

    @Test
    fun unknownSizeOrViewportLeavesThePositionUntouched() {
        val position = Offset(-400f, -400f)
        assertEquals(position, clampToViewport(position, Size.Zero, 1f, landscape))
        assertEquals(position, clampToViewport(position, button, 1f, Size.Zero))
    }

    @Test
    fun nonFinitePositionFallsBackToTheOrigin() {
        assertEquals(Offset.Zero, clampToViewport(Offset(Float.NaN, 5f), button, 1f, landscape))
    }

    @Test
    fun itemLargerThanTheViewportIsPinnedToTheLeadingEdge() {
        val huge = Size(3000f, 2000f)
        val clamped = clampToViewport(Offset(500f, 500f), huge, 1f, landscape)
        assertEquals(0f, clamped.x, 0.001f)
        assertEquals(0f, clamped.y, 0.001f)
    }

    @Test
    fun wasdGroupDerivesItsLayoutSizeFromReportedGeometry() {
        val group = wasdGroup(scale = 1f, left = 100f, right = 400f, top = 100f, bottom = 300f)
        val size = group.layoutSizeForClamp(group.scale)
        assertEquals(300f, size.width, 0.001f)
        assertEquals(200f, size.height, 0.001f)
    }

    @Test
    fun wasdGroupDividesTheReportedGeometryByItsScale() {
        // onGloballyPositioned reports scaled geometry, so a 2x group reports a
        // 600px span for a 300px layout box.
        val group = wasdGroup(scale = 2f, left = 100f, right = 700f, top = 100f, bottom = 500f)
        val size = group.layoutSizeForClamp(group.scale)
        assertEquals(300f, size.width, 0.001f)
        assertEquals(200f, size.height, 0.001f)
    }

    @Test
    fun freshWasdGroupWithoutGeometryIsNotClamped() {
        val group = DraggableItem.WASDGroup(id = 1, position = Offset(-500f, -500f))
        val clamped = clampToViewport(
            group.position,
            group.layoutSizeForClamp(group.scale),
            group.scale,
            landscape,
        )
        assertEquals(group.position, clamped)
    }

    @Test
    fun wasdGroupCannotBeDraggedPastTheLeftEdge() {
        val group = wasdGroup(scale = 1f, left = 100f, right = 400f, top = 100f, bottom = 300f)
        val clamped = clampToViewport(
            Offset(-50f, 50f),
            group.layoutSizeForClamp(group.scale),
            group.scale,
            landscape,
        )
        assertEquals(0f, clamped.x, 0.001f)
        assertEquals(50f, clamped.y, 0.001f)
        assertTrue(clamped.x + 300f <= landscape.width)
    }

    private fun wasdGroup(
        scale: Float,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
    ): DraggableItem.WASDGroup {
        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f
        return DraggableItem.WASDGroup(
            id = 1,
            position = Offset.Zero,
            scale = scale,
            center = Offset(centerX, centerY),
            w = Offset(centerX, top),
            a = Offset(left, centerY),
            s = Offset(centerX, bottom),
            d = Offset(right, centerY),
        )
    }
}
