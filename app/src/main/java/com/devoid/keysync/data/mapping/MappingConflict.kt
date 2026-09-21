package com.devoid.keysync.data.mapping

import com.devoid.keysync.model.DraggableItem

/** Detects collisions that are otherwise very hard to diagnose on a running overlay. */
object MappingConflictDetector {
    data class Conflict(val keyCode: Int, val firstIndex: Int, val secondIndex: Int, val message: String)

    fun detect(items: List<DraggableItem>): List<Conflict> {
        val owners = LinkedHashMap<Int, Int>()
        val conflicts = mutableListOf<Conflict>()
        items.forEachIndexed { index, item ->
            val codes = when (item) {
                is DraggableItem.VariableKey -> listOfNotNull(item.keyCode)
                // Every FixedKey occupies a slot in the runtime map, whatever its
                // button type is. Two of them sharing a keyCode means the second
                // one silently overwrites the first and can never be triggered —
                // previously only type == KEY was checked, which never happens,
                // so duplicate FIRE / SCOPE / MOUSE_MID bindings went unreported.
                is DraggableItem.FixedKey -> listOf(item.keyCode)
                is DraggableItem.CancelableKey -> listOfNotNull(item.keyCode)
                is DraggableItem.WASDGroup -> listOf(
                    android.view.KeyEvent.KEYCODE_W,
                    android.view.KeyEvent.KEYCODE_A,
                    android.view.KeyEvent.KEYCODE_S,
                    android.view.KeyEvent.KEYCODE_D
                )
            }
            codes.forEach { code ->
                val first = owners.putIfAbsent(code, index)
                if (first != null && first != index) {
                    conflicts += Conflict(
                        code,
                        first,
                        index,
                        "keyCode $code is mapped by items $first and $index"
                    )
                }
            }
        }
        return conflicts
    }
}
