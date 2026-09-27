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

    /**
     * A WASD group takes over the physical W/A/S/D keys (and Shift for sprint),
     * so any other item bound to one of them can never fire. This shipped in
     * `data/mapping/moba/moba-skill-cast.json`, where a "D" skill button sat
     * next to a WASD group and did nothing in game.
     */
    @Test
    fun keyBoundToAKeyTheWasdGroupOwnsIsReported() {
        val preset = MappingPreset(
            id = "test",
            name = "Test",
            items = listOf(
                PresetItem(type = "WASD", x = 0.1f, y = 0.6f),
                PresetItem(type = "KEY", x = 0.8f, y = 0.7f, keyCode = "D"),
            )
        )
        assertTrue(
            MappingPresetValidator.validate(preset).any { it.contains("claimed by the WASD group") }
        )
    }

    @Test
    fun sprintModifierIsAlsoReportedAsClaimed() {
        val preset = MappingPreset(
            id = "test",
            name = "Test",
            items = listOf(
                PresetItem(type = "WASD", x = 0.1f, y = 0.6f),
                PresetItem(type = "KEY", x = 0.8f, y = 0.7f, keyCode = "SHIFT"),
            )
        )
        assertTrue(
            MappingPresetValidator.validate(preset).any { it.contains("claimed by the WASD group") }
        )
    }

    @Test
    fun freeKeyAlongsideAWasdGroupIsNotReported() {
        val preset = MappingPreset(
            id = "test",
            name = "Test",
            items = listOf(
                PresetItem(type = "WASD", x = 0.1f, y = 0.6f),
                PresetItem(type = "KEY", x = 0.8f, y = 0.7f, keyCode = "F"),
            )
        )
        assertTrue(
            MappingPresetValidator.validate(preset).none { it.contains("claimed by the WASD group") }
        )
    }

    @Test
    fun wasdOwnedKeyIsFineWhenThereIsNoWasdGroup() {
        val preset = MappingPreset(
            id = "test",
            name = "Test",
            items = listOf(PresetItem(type = "KEY", x = 0.8f, y = 0.7f, keyCode = "D"))
        )
        assertTrue(
            MappingPresetValidator.validate(preset).none { it.contains("claimed by the WASD group") }
        )
    }
}
