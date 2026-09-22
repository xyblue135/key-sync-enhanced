package com.devoid.keysync.domain

/** Android finger ids describe active touches, not the number of saved buttons. */
internal class PointerSlots(private val capacity: Int = 16) {
    private val slots = mutableMapOf<Int, Int>()
    fun acquire(logicalId: Int): Int? = slots[logicalId] ?: (0 until capacity)
        .firstOrNull { it !in slots.values }?.also { slots[logicalId] = it }
    fun release(logicalId: Int) { slots.remove(logicalId) }
    fun clear() { slots.clear() }
}
