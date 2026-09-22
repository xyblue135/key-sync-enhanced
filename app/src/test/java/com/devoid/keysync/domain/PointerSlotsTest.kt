package com.devoid.keysync.domain
import org.junit.Assert.*
import org.junit.Test
class PointerSlotsTest {
    @Test fun highMappingNumbersUseValidFingerIds() {
        val slots = PointerSlots()
        assertEquals(0, slots.acquire(90))
        assertEquals(1, slots.acquire(300))
        assertEquals(0, slots.acquire(90))
    }
    @Test fun releasingMiddleFingerDoesNotRenumberOthers() {
        val slots = PointerSlots()
        slots.acquire(40); slots.acquire(50); slots.acquire(60)
        slots.release(50)
        assertEquals(2, slots.acquire(60))
        assertEquals(1, slots.acquire(70))
    }
    @Test fun limitAppliesOnlyToSimultaneousTouches() {
        val slots = PointerSlots()
        repeat(16) { assertEquals(it, slots.acquire(100 + it)) }
        assertNull(slots.acquire(999))
        slots.release(105); assertEquals(5, slots.acquire(999))
        slots.clear(); assertEquals(0, slots.acquire(10000))
    }
}
