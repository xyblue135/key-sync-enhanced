package com.devoid.keysync.model
import androidx.compose.ui.geometry.Offset
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
class MovementSettingsTest {
    @Test fun distancesSurviveCopyAndSerialization() {
        val item: DraggableItem = DraggableItem.WASDGroup(1, Offset.Zero,
            sprintForwardDistance = 220f, sprintSideDistance = 95f)
        val restored = Json.decodeFromString<DraggableItem>(Json.encodeToString(item.copy())) as DraggableItem.WASDGroup
        assertEquals(220f, restored.sprintForwardDistance)
        assertEquals(95f, restored.sprintSideDistance)
    }
    @Test fun wheelRadiusSurvivesCopyAndSerialization() {
        val item: DraggableItem = DraggableItem.VariableKey(1, Offset.Zero, 35, 60, TouchMode.WHEEL, 85f)
        val restored = Json.decodeFromString<DraggableItem>(Json.encodeToString(item.copy())) as DraggableItem.VariableKey
        assertEquals(85f, restored.wheelRadius)
        assertEquals(TouchMode.WHEEL, restored.touchMode)
    }
    @Test fun oldButtonsDefaultToFiftyPixels() {
        val item: DraggableItem = DraggableItem.VariableKey(1, Offset.Zero, 35, 60)
        val json = Json.encodeToString(item)
        assertFalse(json.contains("wheelRadius"))
        assertEquals(50f, (Json.decodeFromString<DraggableItem>(json) as DraggableItem.VariableKey).wheelRadius)
    }
}
