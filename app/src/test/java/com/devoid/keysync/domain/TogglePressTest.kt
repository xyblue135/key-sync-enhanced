package com.devoid.keysync.domain
import org.junit.Assert.*
import org.junit.Test
class TogglePressTest {
    @Test fun holdingTogglesOnlyOnce() {
        val key = TogglePress()
        assertTrue(key.down(100, 0))
        assertFalse(key.down(100, 1))
        assertFalse(key.down(100, 20))
        assertFalse(key.down(100, 0))
    }
    @Test fun missingReleaseDoesNotBlockNextPress() {
        val key = TogglePress()
        assertTrue(key.down(100, 0))
        assertTrue(key.down(200, 0))
        assertTrue(key.down(300, 0))
    }
    @Test fun repeatAfterResetDoesNotToggle() {
        val key = TogglePress(); key.down(100, 0); key.reset()
        assertFalse(key.down(100, 2))
        assertTrue(key.down(200, 0))
    }
}
