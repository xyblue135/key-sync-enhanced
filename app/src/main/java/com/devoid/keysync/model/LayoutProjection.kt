package com.devoid.keysync.model

import androidx.compose.ui.geometry.Offset

/** Project a saved layout independently along each axis; never mutate the saved items. */
internal fun projectLayout(items: List<DraggableItem>, from: Offset, to: Offset, buttonScale: Float = 1f): List<DraggableItem> {
    require(from.x > 0f && from.y > 0f && to.x > 0f && to.y > 0f)
    fun point(p: Offset) = Offset(p.x * to.x / from.x, p.y * to.y / from.y)
    // Button dimensions are measured again by Compose in dp. Scaling that cached
    // measurement shrinks it repeatedly across restarts and does not resize the UI.
    fun position(p: Offset, size: Int): Offset {
        fun axis(value: Float, old: Float, new: Float): Float {
            val scale = buttonScale.coerceIn(0.6f, 2f)
            val margin = size * (scale - 1f) / 2f
            val oldRange = (old - size * scale).coerceAtLeast(1f)
            val newRange = (new - size * scale).coerceAtLeast(0f)
            return margin + ((value - margin) / oldRange).coerceIn(0f, 1f) * newRange
        }
        return Offset(axis(p.x, from.x, to.x), axis(p.y, from.y, to.y))
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
            is DraggableItem.WASDGroup -> item.copy(position = point(item.position),
                center = point(item.center), w = point(item.w), a = point(item.a),
                s = point(item.s), d = point(item.d), anchorPosition = item.anchorPosition?.let(::point))
        }
    }
}
