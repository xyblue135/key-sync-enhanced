package com.devoid.keysync.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * Keeps a button inside the visible overlay.
 *
 * [position] is the item's layout offset and [size] the layout box Compose
 * measured for it. `Modifier.scale` scales about the node centre, so a node at
 * [position] with [size] and [scale] draws the box
 * `[position + size*(1-scale)/2, position + size*(1+scale)/2]`. Requiring that
 * box to stay inside [viewport] gives
 * `position in [size*(scale-1)/2, viewport - size*(scale+1)/2]`.
 *
 * This runs from a drag handler, which can fire before the first layout pass.
 * An unknown size or viewport therefore leaves the position untouched instead
 * of snapping the button somewhere it was never dragged to.
 */
internal fun clampToViewport(
    position: Offset,
    size: Size,
    scale: Float,
    viewport: Size,
): Offset {
    if (!position.x.isFinite() || !position.y.isFinite()) return Offset.Zero
    if (size.width <= 0f || size.height <= 0f) return position
    if (viewport.width <= 0f || viewport.height <= 0f) return position
    val factor = if (scale.isFinite() && scale > 0f) scale else 1f
    fun axis(value: Float, extent: Float, limit: Float): Float {
        val low = extent * (factor - 1f) / 2f
        val high = (limit - extent * (factor + 1f) / 2f).coerceAtLeast(low)
        return value.coerceIn(low, high)
    }
    return Offset(
        axis(position.x, size.width, viewport.width),
        axis(position.y, size.height, viewport.height),
    )
}

/**
 * The layout box Compose measured for this item, in px, or [Size.Zero] when it
 * is not known yet.
 *
 * [DraggableItem.WASDGroup] never records a measured size: it only reports
 * geometry, and the geometry it reports from `onGloballyPositioned` is already
 * scaled. Dividing the cross distances by the current scale recovers the
 * unscaled layout box that `Modifier.scale` expands.
 */
internal fun DraggableItem.layoutSizeForClamp(scale: Float): Size {
    val item = this
    return when (item) {
        is DraggableItem.VariableKey -> Size(item.size.toFloat(), item.size.toFloat())
        is DraggableItem.FixedKey -> Size(item.size.toFloat(), item.size.toFloat())
        is DraggableItem.CancelableKey -> Size(item.size.toFloat(), item.size.toFloat())
        is DraggableItem.WASDGroup -> {
            val factor = if (scale.isFinite() && scale > 0f) scale else 1f
            val width = (item.d.x - item.a.x) / factor
            val height = (item.s.y - item.w.y) / factor
            if (width.isFinite() && height.isFinite() && width > 0f && height > 0f) {
                Size(width, height)
            } else {
                Size.Zero
            }
        }
    }
}
