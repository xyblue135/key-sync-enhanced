package com.devoid.keysync

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.moveBy
import com.devoid.keysync.domain.KEYCODE_MOUSE_BACK
import com.devoid.keysync.model.ClickMacro
import com.devoid.keysync.model.MacroStep
import com.devoid.keysync.ui.overlay.MacroPointsOverlay
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MacroPointsDragTest {
    @get:Rule val compose = createComposeRule()
    @Test fun numberedPointKeepsDraggingAcrossProfileStateReplacement() {
        var saved = 0
        var position = Offset(0.4f, 0.4f)
        compose.setContent {
            var macros by remember { mutableStateOf(listOf(ClickMacro("macro", "Sequence", KEYCODE_MOUSE_BACK,
                listOf(MacroStep("point", position.x, position.y))))) }
            MaterialTheme {
                MacroPointsOverlay(macros, onMove = { _, _, x, y ->
                    position = Offset(x, y)
                    macros = macros.map { it.copy(steps = listOf(MacroStep("point", x, y))) }
                }, onDragFinished = { saved++ })
            }
        }
        val node = compose.onNodeWithTag("macro-point-point")
        node.performTouchInput { down(center); moveBy(Offset(45f, 0f)) }
        compose.waitForIdle()
        val first = position.x
        node.performTouchInput { moveBy(Offset(35f, 0f)) }
        compose.waitForIdle()
        assertTrue(position.x > first)
        node.performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(1, saved)
        assertTrue(position.x in 0f..1f && position.y in 0f..1f)
    }
}
