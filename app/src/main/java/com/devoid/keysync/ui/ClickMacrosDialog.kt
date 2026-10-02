package com.devoid.keysync.ui

import android.view.KeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.devoid.keysync.domain.KEYCODE_MOUSE_BACK
import com.devoid.keysync.domain.mouseKeyAlias
import com.devoid.keysync.domain.mouseButtonKeyCode
import com.devoid.keysync.model.*
import com.devoid.keysync.util.keyCodeToString
import java.util.UUID
import kotlin.math.roundToInt

@Composable
fun ClickMacrosDialog(profile: Profile, onDismiss: () -> Unit, onSave: (List<ClickMacro>) -> Unit) {
    var macros by remember(profile.id) { mutableStateOf(profile.macros) }
    var selected by remember { mutableStateOf(macros.firstOrNull()?.id) }
    val macro = macros.firstOrNull { it.id == selected }
    val error = validateClickMacros(macros)
    fun change(value: ClickMacro) { macros = macros.map { if (it.id == value.id) value else it } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("点击宏 · ${profile.name}") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("按一次触发键，依次点击 1、2、3……。保存后，在游戏中打开「编辑布局」，选择宏并拖动编号点。宏不依赖普通按键的位置。",
                    style = MaterialTheme.typography.bodySmall)
                macros.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(item.id == selected, onClick = { selected = item.id })
                        TextButton(onClick = { selected = item.id }, modifier = Modifier.weight(1f)) {
                            Text(item.name + if (item.enabled) "（已启用）" else "（已停用）")
                        }
                        TextButton(onClick = {
                            macros = macros.filterNot { it.id == item.id }
                            if (selected == item.id) selected = macros.firstOrNull()?.id
                        }) { Text("删除") }
                    }
                }
                TextButton(enabled = macros.size < MAX_MACROS, onClick = {
                    val created = ClickMacro(UUID.randomUUID().toString(), "点击宏 ${macros.size + 1}",
                        KEYCODE_MOUSE_BACK, listOf(
                            MacroStep(UUID.randomUUID().toString(), 0.35f, 0.5f),
                            MacroStep(UUID.randomUUID().toString(), 0.65f, 0.5f)))
                    macros = macros + created
                    selected = created.id
                }) { Text("＋ 新建宏") }
                macro?.let { current ->
                    HorizontalDivider()
                    OutlinedTextField(current.name, onValueChange = { change(current.copy(name = it)) },
                        label = { Text("宏名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("启用此宏", Modifier.weight(1f))
                        Switch(current.enabled, onCheckedChange = { change(current.copy(enabled = it)) })
                    }
                    MouseInputPicker(current.triggerKeyCode) { change(current.copy(triggerKeyCode = it)) }
                    KeyConfigTextField(title = "或按键盘键", value = current.triggerKeyCode.keyCodeToString(),
                        onKeyEvent = {
                            val native = it.nativeKeyEvent
                            if (native.action == KeyEvent.ACTION_DOWN && native.keyCode > 0) {
                                val code = mouseKeyAlias(native.keyCode, native.source)?.let(::mouseButtonKeyCode)
                                    ?: native.keyCode
                                change(current.copy(triggerKeyCode = code))
                            }
                            true
                        })
                    Text("宏优先于同键普通映射。每次只运行一个宏，执行期间再次触发不会排队。每个点击保持 50 毫秒，下面的间隔从松开后计算。",
                        style = MaterialTheme.typography.bodySmall)
                    current.steps.forEachIndexed { index, step ->
                        key(step.id) {
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(8.dp)) {
                                    Text("位置 ${index + 1} · X ${(step.x * 100).roundToInt()}% / Y ${(step.y * 100).roundToInt()}%")
                                    Row {
                                        TextButton(enabled = index > 0, onClick = {
                                            val reordered = current.steps.toMutableList()
                                            reordered[index] = reordered[index - 1]
                                            reordered[index - 1] = step
                                            change(current.copy(steps = reordered))
                                        }) { Text("上移") }
                                        TextButton(enabled = current.steps.size > 1, onClick = {
                                            change(current.copy(steps = current.steps.filterNot { it.id == step.id }))
                                        }) { Text("删除位置") }
                                    }
                                    if (index < current.steps.lastIndex) {
                                        Text("到下一步的间隔：${step.delayAfterMs} 毫秒")
                                        Slider(step.delayAfterMs.toFloat(), onValueChange = { value ->
                                            change(current.copy(steps = current.steps.map {
                                                if (it.id == step.id) it.copy(delayAfterMs = value.roundToInt().toLong()) else it
                                            }))
                                        }, valueRange = 0f..MAX_MACRO_DELAY_MS.toFloat(), steps = 99)
                                    }
                                }
                            }
                        }
                    }
                    TextButton(enabled = current.steps.size < MAX_MACRO_STEPS, onClick = {
                        val i = current.steps.size
                        change(current.copy(steps = current.steps + MacroStep(UUID.randomUUID().toString(),
                            0.2f + (i % 4) * 0.2f, 0.3f + (i / 4) * 0.15f)))
                    }) { Text("＋ 添加点击位置") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = error == null, onClick = { onSave(macros); onDismiss() }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
