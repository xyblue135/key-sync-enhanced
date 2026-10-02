package com.devoid.keysync.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.devoid.keysync.model.ClickMacro
import kotlin.math.roundToInt

/** Editing-only numbered points. Ordinary key visibility does not hide macro editing controls. */
@Composable
fun MacroPointsOverlay(
    macros: List<ClickMacro>,
    onMove: (macroId: String, stepId: String, x: Float, y: Float) -> Unit,
    onDragFinished: () -> Unit,
) {
    if (macros.isEmpty()) return
    var selected by remember { mutableStateOf(macros.first().id) }
    var expanded by remember { mutableStateOf(false) }
    val macro = macros.firstOrNull { it.id == selected } ?: macros.first()
    val move by rememberUpdatedState(onMove)
    val finish by rememberUpdatedState(onDragFinished)
    val radius = with(LocalDensity.current) { 24.dp.toPx() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        if (width <= 0 || height <= 0) return@BoxWithConstraints
        macro.steps.forEachIndexed { index, step ->
            key(macro.id, step.id) {
                val current by rememberUpdatedState(Offset(step.x * width, step.y * height))
                Box(Modifier
                    .offset { IntOffset((current.x - radius).roundToInt(), (current.y - radius).roundToInt()) }
                    .size(48.dp)
                    .testTag("macro-point-${step.id}")
                    .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.tertiary, CircleShape)
                    .pointerInput(macro.id, step.id, width, height) {
                        var position = current
                        detectDragGestures(
                            onDragStart = { position = current },
                            onDragEnd = { finish() },
                            onDragCancel = { finish() },
                        ) { change, delta ->
                            change.consume()
                            position = Offset((position.x + delta.x).coerceIn(0f, width - 1f),
                                (position.y + delta.y).coerceIn(0f, height - 1f))
                            move(macro.id, step.id, position.x / width, position.y / height)
                        }
                    }, contentAlignment = Alignment.Center) {
                    Text("${index + 1}", color = MaterialTheme.colorScheme.onTertiaryContainer,
                        style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(8.dp)) {
            FilledTonalButton(onClick = { expanded = true }) { Text("宏点：${macro.name} · 拖动编号设置位置") }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                macros.forEach { item ->
                    DropdownMenuItem(text = { Text(item.name) }, onClick = { selected = item.id; expanded = false })
                }
            }
        }
    }
}
