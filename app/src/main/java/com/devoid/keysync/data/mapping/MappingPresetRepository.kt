package com.devoid.keysync.data.mapping

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import dagger.hilt.android.qualifiers.ApplicationContext
import com.devoid.keysync.model.DraggableItem
import com.devoid.keysync.model.DraggableItemType
import com.devoid.keysync.model.defaultKeyCode
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/** Reads bundled presets and converts them into the current KeySync model. */
@Singleton
class MappingPresetRepository @Inject constructor(@ApplicationContext private val context: Context) {
    companion object {
        private const val TAG = "MappingPresetRepository"
        private const val INDEX_PATH = "mapping/index.json"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    fun listPresets(): List<MappingPreset> {
        return try {
            val index = context.assets.open(INDEX_PATH).bufferedReader().use { json.decodeFromString<MappingPresetIndex>(it.readText()) }
            index.presets.mapNotNull { entry ->
                try {
                    context.assets.open("mapping/${entry.file}").bufferedReader().use {
                        val preset = json.decodeFromString<MappingPreset>(it.readText())
                        val errors = MappingPresetValidator.validate(preset)
                        if (errors.isNotEmpty()) {
                            Log.w(TAG, "Preset ${preset.id} has validation warnings: ${errors.joinToString()}")
                        }
                        if (MappingPresetValidator.hasFatalErrors(preset)) null else preset
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load preset ${entry.id}", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load preset index", e)
            emptyList()
        }
    }

    /**
     * Converts a preset to runtime items. Coordinates are normalized by
     * default, which makes the same preset usable on different resolutions.
     */
    fun toRuntimeItems(
        preset: MappingPreset,
        screenWidth: Int,
        screenHeight: Int,
    ): List<DraggableItem> {
        val pixelScaleX = if (preset.coordinateMode.equals("pixel", true)) {
            screenWidth.toFloat() / (preset.targetWidth?.takeIf { it > 0 } ?: screenWidth)
        } else 1f
        val pixelScaleY = if (preset.coordinateMode.equals("pixel", true)) {
            screenHeight.toFloat() / (preset.targetHeight?.takeIf { it > 0 } ?: screenHeight)
        } else 1f
        val pixelScaleSize = min(pixelScaleX, pixelScaleY)

        fun x(value: Float): Float = if (preset.coordinateMode.equals("pixel", true)) value * pixelScaleX else value * screenWidth
        fun y(value: Float): Float = if (preset.coordinateMode.equals("pixel", true)) value * pixelScaleY else value * screenHeight
        fun size(value: Float): Int = if (preset.coordinateMode.equals("pixel", true)) {
            (value * pixelScaleSize).coerceAtLeast(1f).toInt()
        } else {
            (value * min(screenWidth, screenHeight)).coerceAtLeast(1f).toInt()
        }

        return preset.items.mapIndexedNotNull { index, item ->
            try {
                val id = index + 1
                when (item.type.uppercase(Locale.ROOT)) {
                    "KEY", "VARIABLE_KEY" -> {
                        val keyCode = resolveKeyCode(item.keyCode) ?: return@mapIndexedNotNull null
                        DraggableItem.VariableKey(id, Offset(x(item.x), y(item.y)), keyCode, size(item.size))
                    }
                    "WASD", "WASD_GROUP" -> {
                        val center = Offset(x(item.centerX ?: (item.x + 0.05f)), y(item.centerY ?: (item.y + 0.05f)))
                        DraggableItem.WASDGroup(
                            id = id,
                            position = Offset(x(item.x), y(item.y)),
                            scale = item.scale,
                            center = center,
                            w = Offset(x(item.wX ?: item.x), y(item.wY ?: item.y)),
                            a = Offset(x(item.aX ?: item.x), y(item.aY ?: item.y)),
                            s = Offset(x(item.sX ?: item.x), y(item.sY ?: item.y)),
                            d = Offset(x(item.dX ?: item.x), y(item.dY ?: item.y))
                        )
                    }
                    "FIXED", "FIXED_KEY" -> {
                        val semanticType = item.itemType?.uppercase(Locale.ROOT)
                        val type = semanticType?.let { runCatching { DraggableItemType.valueOf(it) }.getOrNull() }
                            ?: DraggableItemType.KEY
                        // FixedKey now carries its own keyCode (the user can
                        // rebind it from the gear-icon dialog). The preset's
                        // `keyCode` is used verbatim, falling back to the
                        // type's default when missing.
                        val code = resolveKeyCode(item.keyCode) ?: type.defaultKeyCode()
                        DraggableItem.FixedKey(id, Offset(x(item.x), y(item.y)), type, code, size(item.size))
                    }
                    "CANCELABLE", "CANCELABLE_KEY" -> {
                        val code = resolveKeyCode(item.keyCode) ?: return@mapIndexedNotNull null
                        DraggableItem.CancelableKey(
                            id = id,
                            position = Offset(x(item.x), y(item.y)),
                            cancelPosition = Offset(x(item.cancelX ?: item.x), y(item.cancelY ?: item.y)),
                            type = DraggableItemType.BAG_MAP,
                            keyCode = code,
                            size = size(item.size)
                        )
                    }
                    else -> null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Skipping invalid item #$index in ${preset.id}", e)
                null
            }
        }
    }

    private fun resolveKeyCode(value: String?): Int? {
        if (value.isNullOrBlank()) return null
        if (value.startsWith("#")) value.substring(1).toIntOrNull()?.let { return it }
        if (value.length == 1 && value[0] in '0'..'9') {
            return KeyEvent.KEYCODE_0 + (value[0] - '0')
        }
        value.toIntOrNull()?.let { return it }
        val normalized = value.removePrefix("KEYCODE_").uppercase(Locale.ROOT)
        val aliases = mapOf(
            "ESC" to KeyEvent.KEYCODE_ESCAPE,
            "ESCAPE" to KeyEvent.KEYCODE_ESCAPE,
            "SPACE" to KeyEvent.KEYCODE_SPACE,
            "ENTER" to KeyEvent.KEYCODE_ENTER,
            "RETURN" to KeyEvent.KEYCODE_ENTER,
            "TAB" to KeyEvent.KEYCODE_TAB,
            "SHIFT" to KeyEvent.KEYCODE_SHIFT_LEFT,
            "CTRL" to KeyEvent.KEYCODE_CTRL_LEFT,
            "CONTROL" to KeyEvent.KEYCODE_CTRL_LEFT,
            "ALT" to KeyEvent.KEYCODE_ALT_LEFT,
            "BACKSPACE" to KeyEvent.KEYCODE_DEL,
            "DELETE" to KeyEvent.KEYCODE_FORWARD_DEL,
            "DEL" to KeyEvent.KEYCODE_DEL,
            "UP" to KeyEvent.KEYCODE_DPAD_UP,
            "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
            "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
            "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "LCTRL" to KeyEvent.KEYCODE_CTRL_LEFT,
            "RCTRL" to KeyEvent.KEYCODE_CTRL_RIGHT,
            "LSHIFT" to KeyEvent.KEYCODE_SHIFT_LEFT,
            "RSHIFT" to KeyEvent.KEYCODE_SHIFT_RIGHT,
            "LALT" to KeyEvent.KEYCODE_ALT_LEFT,
            "RALT" to KeyEvent.KEYCODE_ALT_RIGHT
        )
        aliases[normalized]?.let { return it }
        if (normalized.length == 1) {
            val c = normalized[0]
            if (c in 'A'..'Z') return KeyEvent.KEYCODE_A + (c - 'A')
            if (c in '0'..'9') return KeyEvent.KEYCODE_0 + (c - '0')
        }
        return runCatching {
            KeyEvent::class.java.getField("KEYCODE_$normalized").getInt(null)
        }.getOrNull()
    }
}
