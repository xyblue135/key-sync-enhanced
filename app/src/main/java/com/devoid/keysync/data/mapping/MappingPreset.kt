package com.devoid.keysync.data.mapping

import kotlinx.serialization.Serializable

/**
 * Human-editable preset format.  It deliberately does not serialize the
 * internal DraggableItem sealed class, so future model changes do not break
 * user-maintained preset files.
 */
@Serializable
data class MappingPreset(
    val schemaVersion: Int = 1,
    val id: String,
    val name: String,
    val category: String = "universal",
    val description: String = "",
    val coordinateMode: String = "normalized",
    val targetWidth: Int? = null,
    val targetHeight: Int? = null,
    val items: List<PresetItem> = emptyList()
)

@Serializable
data class PresetItem(
    val type: String,
    val x: Float = 0f,
    val y: Float = 0f,
    val size: Float = 0.05f,
    val scale: Float = 1f,
    val keyCode: String? = null,
    val itemType: String? = null,
    val cancelX: Float? = null,
    val cancelY: Float? = null,
    val centerX: Float? = null,
    val centerY: Float? = null,
    val wX: Float? = null,
    val wY: Float? = null,
    val aX: Float? = null,
    val aY: Float? = null,
    val sX: Float? = null,
    val sY: Float? = null,
    val dX: Float? = null,
    val dY: Float? = null
)

@Serializable
data class MappingPresetIndex(
    val schemaVersion: Int = 1,
    val presets: List<MappingPresetIndexEntry> = emptyList()
)

@Serializable
data class MappingPresetIndexEntry(
    val id: String,
    val file: String,
    val category: String = "universal"
)
