package com.devoid.keysync.data.mapping

import org.junit.Assert.assertTrue
import org.junit.Test

class MappingPresetValidatorTest {
    @Test
    fun normalizedPresetIsAccepted() {
        val preset = MappingPreset(
            id = "test",
            name = "Test",
            items = listOf(PresetItem(type = "KEY", x = 0.5f, y = 0.5f, keyCode = "SPACE"))
        )
        assertTrue(MappingPresetValidator.validate(preset).isEmpty())
    }

    @Test
    fun invalidCoordinateIsReported() {
        val preset = MappingPreset(
            id = "test",
            name = "Test",
            items = listOf(PresetItem(type = "KEY", x = 1.2f, y = 0.5f, keyCode = "SPACE"))
        )
        assertTrue(MappingPresetValidator.validate(preset).any { it.contains("x must be between") })
    }
}
