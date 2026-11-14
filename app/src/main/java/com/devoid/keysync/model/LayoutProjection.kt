package com.devoid.keysync.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/** Project a saved layout independently along each axis; never mutate the saved items. */
internal fun projectLayout(items: List<DraggableItem>, from: Offset, to: Offset, buttonScale: Float = 1f): List<DraggableItem> {
    require(from.x > 0f && from.y > 0f && to.x > 0f && to.y > 0f)
    // 横竖屏交叉时必须整体旋转 90°，而不是把两个轴各自拉伸：后者会把横屏布局
    // 直接"搬"到竖屏上，左右两侧的按钮全部错位。旋转方向取互为逆运算的那一侧，
    // 保证 横→竖→横 能回到原坐标。
    val rotate = (from.x > from.y) != (to.x > to.y)
    val rotatedFrom = if (rotate) Offset(from.y, from.x) else from

    fun orient(p: Offset): Offset = when {
        !rotate -> p
        from.x > from.y -> Offset(from.y - p.y, p.x)   // 横 → 竖：顺时针
        else -> Offset(p.y, from.x - p.x)              // 竖 → 横：逆时针
    }

    fun point(p: Offset): Offset {
        val q = orient(p)
        return Offset(q.x * to.x / rotatedFrom.x, q.y * to.y / rotatedFrom.y)
    }
    // Button dimensions are measured again by Compose in dp. Scaling that cached
    // measurement shrinks it repeatedly across restarts and does not resize the UI.
    fun position(p: Offset, size: Int): Offset {
        val q = orient(p)
        fun axis(value: Float, old: Float, new: Float): Float {
            val scale = buttonScale.coerceIn(0.6f, 2f)
            val margin = size * (scale - 1f) / 2f
            val oldRange = (old - size * scale).coerceAtLeast(1f)
            val newRange = (new - size * scale).coerceAtLeast(0f)
            return margin + ((value - margin) / oldRange).coerceIn(0f, 1f) * newRange
        }
        return Offset(axis(q.x, rotatedFrom.x, to.x), axis(q.y, rotatedFrom.y, to.y))
    }
    return items.map { item ->
        when (item) {
            is DraggableItem.VariableKey -> item.copy(position = position(item.position, item.size),
                anchorPosition = item.anchorPosition?.let { position(it, item.size) })
            is DraggableItem.FixedKey -> item.copy(position = position(item.position, item.size),
                anchorPosition = item.anchorPosition?.let { position(it, item.size) })
            is DraggableItem.CancelableKey -> item.copy(position = position(item.position, item.size),
                cancelPosition = position(item.cancelPosition, item.size),
                anchorPosition = item.anchorPosition?.let { position(it, item.size) })
            is DraggableItem.WASDGroup -> {
                // A WASD group has no cached size, so its extent comes from the
                // geometry it last reported. Without this an in-range source can
                // still project to a position whose drawn box leaves the screen.
                val extent = item.layoutSizeForClamp(item.scale)
                val limit = Size(to.x, to.y)
                // center/w/a/s/d are absolute screen coordinates that Compose
                // re-measures from the real layout (the WASD group's
                // onGloballyPositioned). They are not offsets, so scaling them
                // about the screen origin would leave the joystick origin and
                // deflection detached from the drawn group. The measurement
                // callback republishes them instead, so only the layout offset
                // is projected here.
                item.copy(
                    position = clampToViewport(point(item.position), extent, item.scale, limit),
                    anchorPosition = item.anchorPosition
                        ?.let { clampToViewport(point(it), extent, item.scale, limit) })
            }
        }
    }
}
