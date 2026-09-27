package com.devoid.keysync.data.mapping

import java.util.Locale

/** Lightweight validation used before a bundled or imported preset is exposed. */
object MappingPresetValidator {
    private val supportedItemTypes = setOf("KEY", "VARIABLE_KEY", "WASD", "WASD_GROUP", "FIXED", "FIXED_KEY", "CANCELABLE", "CANCELABLE_KEY")
    private val supportedSemanticTypes = setOf("KEY", "FIRE", "SHOOTING_MODE", "SCOPE")
    private val WASD_TYPES = setOf("WASD", "WASD_GROUP")

    /**
     * Physical keys a WASD group takes over: W/A/S/D drive the stick and Shift is
     * its sprint modifier. EventHandler routes these key codes to the joystick
     * before it ever consults a per-key binding.
     */
    private val WASD_CLAIMED_KEYS = setOf(
        "W", "A", "S", "D",
        "SHIFT", "LSHIFT", "RSHIFT",
    )

    fun validate(preset: MappingPreset): List<String> {
        val errors = mutableListOf<String>()
        if (preset.schemaVersion != 1) errors += "unsupported schemaVersion=${preset.schemaVersion}"
        if (preset.id.isBlank()) errors += "id is empty"
        if (preset.name.isBlank()) errors += "name is empty"
        if (preset.coordinateMode.lowercase(Locale.ROOT) !in setOf("normalized", "pixel")) {
            errors += "coordinateMode must be normalized or pixel"
        }
        if (preset.coordinateMode.equals("pixel", true) &&
            ((preset.targetWidth ?: 0) <= 0 || (preset.targetHeight ?: 0) <= 0)
        ) errors += "pixel presets require targetWidth/targetHeight"

        preset.items.forEachIndexed { index, item ->
            val type = item.type.uppercase(Locale.ROOT)
            if (type !in supportedItemTypes) errors += "item[$index]: unsupported type=$type"
            if (preset.coordinateMode.equals("normalized", true)) {
                listOf("x" to item.x, "y" to item.y).forEach { (name, value) ->
                    if (value !in 0f..1f) errors += "item[$index]: $name must be between 0 and 1"
                }
            }
            if (item.size < 0f) errors += "item[$index]: size must not be negative"
            if (type.startsWith("FIXED")) {
                val semantic = item.itemType?.uppercase(Locale.ROOT)
                if (semantic != null && semantic !in supportedSemanticTypes) {
                    errors += "item[$index]: unsupported itemType=$semantic"
                }
                if (semantic == "KEY" && item.keyCode.isNullOrBlank()) errors += "item[$index]: KEY fixed item requires keyCode"
            }
            if (type.startsWith("CANCELABLE") && item.keyCode.isNullOrBlank()) {
                errors += "item[$index]: cancelable item requires keyCode"
            }
        }

        // Any other item bound to a key the WASD group already owns is dead on
        // arrival: it silently does nothing in game. Report it so the mistake is
        // caught when a preset is authored rather than by a player mid-match.
        if (preset.items.any { it.type.uppercase(Locale.ROOT) in WASD_TYPES }) {
            preset.items.forEachIndexed { index, item ->
                if (item.type.uppercase(Locale.ROOT) in WASD_TYPES) return@forEachIndexed
                val raw = item.keyCode
                    ?.uppercase(Locale.ROOT)
                    ?.removePrefix("KEYCODE_")
                    ?: return@forEachIndexed
                if (raw !in WASD_CLAIMED_KEYS) return@forEachIndexed
                errors += "item[$index]: keyCode=$raw is claimed by the WASD group and will never fire"
            }
        }
        return errors
    }

    fun hasFatalErrors(preset: MappingPreset): Boolean = validate(preset).any {
        it.startsWith("unsupported schemaVersion") || it == "id is empty" || it == "name is empty"
    }
}
