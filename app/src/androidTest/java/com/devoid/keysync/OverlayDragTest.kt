package com.devoid.keysync

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.moveBy
import com.devoid.keysync.ui.overlay.DraggableItem
import com.devoid.keysync.ui.overlay.GenericFixedKey
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OverlayDragTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dragContinuesAfterPositionStateIsReplaced() {
        var storedPosition = Offset(100f, 100f)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    // Same state lifetime as ItemsContainer: each position update
                    // creates new remembered state while pointerInput stays alive.
                    var position by remember(storedPosition) { mutableStateOf(storedPosition) }
                    DraggableItem(
                        modifier = Modifier.testTag("key"), id = 1,
                        offset = position, scale = 1f,
                        onOffsetChange = {
                            storedPosition += it
                            position += it
                        }
                    ) {
                        GenericFixedKey(label = "W", onRemove = {}, onConfigure = {})
                    }
                }
            }
        }
        val node = compose.onNodeWithTag("key")
        node.performTouchInput {
            down(center)
            moveBy(Offset(60f, 0f))
        }
        compose.waitForIdle()
        val firstX = node.fetchSemanticsNode().boundsInRoot.left
        node.performTouchInput { moveBy(Offset(40f, 0f)) }
        compose.waitForIdle()
        val secondX = node.fetchSemanticsNode().boundsInRoot.left
        node.performTouchInput { moveBy(Offset(40f, 0f)) }
        compose.waitForIdle()
        val thirdX = node.fetchSemanticsNode().boundsInRoot.left
        node.performTouchInput { up() }
        assertTrue("Second drag update must move the visible key", secondX > firstX + 20f)
        assertTrue("Third drag update must still move the visible key", thirdX > secondX + 20f)
    }
}
