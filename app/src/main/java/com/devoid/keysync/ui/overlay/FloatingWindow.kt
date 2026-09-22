package com.devoid.keysync.ui.overlay

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.devoid.keysync.R
import com.devoid.keysync.domain.KEYCODE_MMC
import com.devoid.keysync.model.Profile
import com.devoid.keysync.model.AppConfig
import com.devoid.keysync.model.DraggableItem
import com.devoid.keysync.model.DraggableItemType
import com.devoid.keysync.model.TouchMode
import com.devoid.keysync.util.keyCodeToString
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt


@Composable
fun FloatingBubble(
    expanded: Boolean = false,
    modifier: Modifier = Modifier.size(50.dp),
    onClick: () -> Unit = {}
) {
    Column(modifier = modifier
        .clip(CircleShape)
        .clickable {
            onClick()
        }) {
        if (expanded) {
            Image(
                imageVector = Icons.Rounded.Done,
                contentDescription = "",
                modifier = modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                    .padding(8.dp)
            )
        } else {
            Image(
                painter = painterResource(R.drawable.logo_raw),
                contentDescription = "",
                modifier = modifier
                    .clip(CircleShape)
                    .background(Color.Black)
            )
        }
    }
}

@SuppressLint("UnrememberedMutableState")
@Composable
fun ItemsContainer(
    modifier: Modifier = Modifier,
    containerItems: List<DraggableItem>,
    appConfig: AppConfig = AppConfig.Default,
    editing: Boolean = true,
    walkEnabled: Boolean = false,
    onCalibrateWalkOff: () -> Unit = {},
    onItemMeasured: (DraggableItem) -> Unit = {},
    onRemove: (Int) -> Unit,
    onUpdateKeyCode: (Int, Int, TouchMode?) -> Unit = { _, _, _ -> },
    onUpdateVariableKeyCode: (Int, Int, TouchMode?) -> Unit = { _, _, _ -> },
    pendingBindId: Int? = null,
    onBindConsumed: () -> Unit = {},
    onKeyCaptureChanged: (((Int) -> Unit)?) -> Unit = {},
    pressedKeys: Set<Int> = emptySet(),
    onRequestFocus: () -> Unit = {}
) {
    // When the user taps the gear icon on a key, this is set so we can
    // pop up a key-capture dialog bound to that specific item.
    var configuringWasd by remember { mutableStateOf<DraggableItem.WASDGroup?>(null) }
    var configuringFixedKey by remember { mutableStateOf<DraggableItem.FixedKey?>(null) }
    var configuringVariableKey by remember { mutableStateOf<DraggableItem.VariableKey?>(null) }

    // 新建「按钮」后自动弹出绑定框：StateManager 会发出 pendingBindId 信号，
    // 这里捕获后直接打开对应按钮的按键捕获对话框，避免留下一个显示 "?" 的空按钮。
    LaunchedEffect(pendingBindId) {
        val id = pendingBindId ?: return@LaunchedEffect
        val target = containerItems.filterIsInstance<DraggableItem.VariableKey>()
            .firstOrNull { it.id == id }
        if (target != null) {
            configuringVariableKey = target
        }
        onBindConsumed()
    }

    Box(
        modifier
            .fillMaxSize()
    ) {
        containerItems.forEach { item ->
            key(item.id) {

                var position by remember(item.id, item.position) { mutableStateOf(item.position) }
                var scale by remember(item.id, (item as? DraggableItem.WASDGroup)?.scale, appConfig.buttonScale) { mutableFloatStateOf(if (item is DraggableItem.WASDGroup) item.scale else appConfig.buttonScale) }
                if (item is DraggableItem.CancelableKey) {
                    if (item.type == DraggableItemType.BAG_MAP || item.type == DraggableItemType.BACKPACK) {
                        var cancelableKeyPosition by remember(item.id, item.cancelPosition) { mutableStateOf(item.cancelPosition) }
                        var hasFocus by remember { mutableStateOf(false) }
                        val keyFocusRequester = remember { FocusRequester() }
                        var keyCode by remember(item.id, item.keyCode) { mutableStateOf(item.keyCode) }
                        val text by remember(item.id) {
                            derivedStateOf {
                                keyCode?.let {
                                    KeyEvent.keyCodeToString(it).replace("KEYCODE_", "")
                                } ?: "?"
                            }
                        }

                        DraggableItem(
                            scale = scale,
                            id = item.id,
                            offset = position,
                            onScreenCenter = { item.touchCenter = it; onItemMeasured(item) },
                            onOffsetChange = {
                                item.position += it
                                position += it
                            }
                        ) {
                            BagMapKey(
                                modifier = Modifier
                                    .onFocusChanged {
                                        hasFocus = it.hasFocus
                                    }
                                    .onSizeChanged {
                                        item.size = it.width
                                    }
                                    .onKeyEvent { keyEvent ->
                                        val nativeKeyCode = keyEvent.key.nativeKeyCode
                                        if (nativeKeyCode >= KeyEvent.KEYCODE_DPAD_UP && nativeKeyCode <= KeyEvent.KEYCODE_DPAD_CENTER)
                                            return@onKeyEvent false
                                        item.keyCode = nativeKeyCode
                                        keyCode = nativeKeyCode
                                        true
                                    }
                                    .focusRequester(keyFocusRequester)
                                    .focusable(),
                                text = text,
                                borderColor = if (hasFocus) Color.Cyan else MaterialTheme.colorScheme.primary,
                                onClick = { keyFocusRequester.requestFocus() }) {
                                onRemove(item.id)
                            }
                        }
                        DraggableItem(
                            scale = scale,
                            id = item.id,
                            offset = cancelableKeyPosition,
                            onScreenCenter = { item.cancelTouchCenter = it; onItemMeasured(item) },
                            onOffsetChange = {
                                item.cancelPosition += it
                                cancelableKeyPosition += it
                            }
                        ) {
                            CancelKey(
                                borderColor = if (hasFocus) Color.Cyan else MaterialTheme.colorScheme.primary,
                                onClick = { keyFocusRequester.requestFocus() }
                            )
                        }
                    }
                } else {
                DraggableItem(
                    scale = scale,
                    id = item.id,
                    offset = position,
                    onScreenCenter = { item.touchCenter = it; onItemMeasured(item) },
                    onOffsetChange = {
                        item.position += it
                        position += it
                    }
                ) {
                    when (item) {
                        is DraggableItem.VariableKey -> {
                            // 通用「按钮」：齿轮对话框绑定按键 + 切换点击/按住模式，与固定键一致。
                            // 未绑定前显示 "+"（点齿轮绑定），绑定后显示真实键名；按下时高亮。
                            GenericFixedKey(
                                modifier = Modifier.onSizeChanged { item.size = it.width },
                                editable = editing,
                                label = item.keyCode?.keyCodeToString()?.removePrefix("KEYCODE_") ?: "+",
                                pressed = item.keyCode?.let { pressedKeys.contains(it) } ?: false,
                                onRemove = { onRemove(item.id) },
                                onConfigure = { configuringVariableKey = item }
                            )
                        }

                        is DraggableItem.WASDGroup -> {
                            WASDKeysGroup(onRemove = {
                                onRemove(item.id)
                            }, modifier = Modifier
                                .onGloballyPositioned { coordinates ->
                                    val layoutX = coordinates.positionOnScreen().x
                                    val layoutY = coordinates.positionOnScreen().y
                                    item.center =
                                        coordinates.positionOnScreen() + Offset(
                                            coordinates.size.width * scale / 2f,
                                            coordinates.size.height * scale / 2f
                                        )
                                    item.w = Offset(item.center.x, layoutY)
                                    item.a = Offset(layoutX, item.center.y)
                                    item.s = Offset(item.center.x, layoutY + coordinates.size.height * scale)
                                    item.d = Offset(layoutX + coordinates.size.width * scale, item.center.y)

                                }, onPanIconDrag = { dragAmount ->
                                val scaleAmount = scale + (dragAmount.y / 500)
                                scale = max(min(scaleAmount, 1.4f), 0.6f)
                                item.scale = scale
                            }, onConfigure = { configuringWasd = item }, pressedKeys = pressedKeys, editable = editing)
                        }

                        is DraggableItem.FixedKey -> {
                            val onSizeChangeListener = { intSize: IntSize ->
                                item.size = intSize.width
                            }
                            // 所有固定键统一用文字显示其真实物理按键，避免图标或
                            // 硬编码字母与实际绑定键不一致（如 JUMP 显示 J 实为 SPACE）。
                            GenericFixedKey(
                                modifier = Modifier.onSizeChanged { onSizeChangeListener(it) },
                                editable = editing,
                                label = if (item.type == DraggableItemType.WALK_TOGGLE)
                                    (if (walkEnabled) "静步 开" else "静步 关") else item.keyCode.keyCodeToString(),
                                iconRes = when (item.type) {
                                    DraggableItemType.SHOOTING_MODE -> R.drawable.move
                                    DraggableItemType.FIRE -> R.drawable.bullet
                                    DraggableItemType.SCOPE -> R.drawable.scope
                                    else -> null
                                },
                                pressed = pressedKeys.contains(item.keyCode) ||
                                    (item.type == DraggableItemType.WALK_TOGGLE && walkEnabled),
                                onRemove = { onRemove(item.id) },
                                onConfigure = { configuringFixedKey = item }
                            )
                        }

                        else -> {}
                    }

                }

                }
            }
        }

        configuringWasd?.let { target ->
            var forward by remember(target.id) { mutableFloatStateOf(target.sprintForwardDistance ?: ((target.w - target.center).getDistance() * target.sprintScale).coerceIn(10f, 1000f)) }
            var side by remember(target.id) { mutableFloatStateOf(target.sprintSideDistance ?: ((target.d - target.center).getDistance() * target.sprintScale).coerceIn(10f, 1000f)) }
            InlineDialog(onDismiss = { configuringWasd = null }) {
                Column(Modifier.padding(24.dp).widthIn(min = 240.dp).verticalScroll(rememberScrollState())) {
                    Text("Shift 疾跑距离", style = MaterialTheme.typography.titleMedium)
                    Text("W 前向：${forward.roundToInt()} px")
                    Slider(value = forward.coerceIn(10f, 1000f), onValueChange = { forward = it }, valueRange = 10f..1000f)
                    Text("A / D 横向（共用）：${side.roundToInt()} px")
                    Slider(value = side.coerceIn(10f, 1000f), onValueChange = { side = it }, valueRange = 10f..1000f)
                    Text("斜跑保留完整前向距离；只有 S 取消疾跑。保存后退出编辑生效。", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { configuringWasd = null }) { Text("取消") }
                        TextButton(onClick = {
                            target.sprintForwardDistance = forward
                            target.sprintSideDistance = side
                            configuringWasd = null
                        }) { Text("保存") }
                    }
                }
            }
        }
        configuringFixedKey?.let { target ->
            if (target.type == DraggableItemType.SHOOTING_MODE) {
                // 射击模式切换键只允许在 ` 与鼠标中键之间下拉二选一。
                ShootingModeKeyDialog(
                    currentKeyCode = target.keyCode,
                    onConfirm = { newCode ->
                        onUpdateKeyCode(target.id, newCode, target.touchMode)
                        configuringFixedKey = null
                    },
                    onDismiss = { configuringFixedKey = null }
                )
            } else {
                KeyCaptureDialog(
                    walkCustomization = target.type == DraggableItemType.WALK_TOGGLE,
                    onCalibrateWalkOff = onCalibrateWalkOff,
                    currentKeyCode = target.keyCode,
                    currentWheelRadius = target.wheelRadius,
                    onWheelRadiusChange = { target.wheelRadius = it },
                    currentTouchMode = target.touchMode,
                    onConfirm = { newCode, touchMode ->
                        onUpdateKeyCode(target.id, newCode, touchMode)
                        configuringFixedKey = null
                    },
                    onDismiss = { configuringFixedKey = null },
                    onKeyCaptureChanged = onKeyCaptureChanged,
                    onRequestFocus = onRequestFocus
                )
            }
        }

        configuringVariableKey?.let { target ->
            KeyCaptureDialog(
                currentKeyCode = target.keyCode ?: KeyEvent.KEYCODE_UNKNOWN,
                currentWheelRadius = target.wheelRadius,
                onWheelRadiusChange = { target.wheelRadius = it },
                currentTouchMode = target.touchMode,
                onConfirm = { newCode, touchMode ->
                    onUpdateVariableKeyCode(target.id, newCode, touchMode)
                    configuringVariableKey = null
                },
                onDismiss = { configuringVariableKey = null },
                onKeyCaptureChanged = onKeyCaptureChanged,
                onRequestFocus = onRequestFocus
            )
        }
    }
}

/**
 * 悬浮窗专用的内联对话框：渲染在当前 Compose 树内，不创建独立 window。
 *
 * 悬浮窗跑在 Service 里（没有 Activity 的 window token），Material3 的
 * AlertDialog 内部靠 androidx.compose.ui.window.Dialog 弹独立窗口，会抛
 * BadTokenException，把整个悬浮窗内容打崩——这正是「新建按钮后所有 UI /
 * 布局瞬间消失」的根因。这里改用 Box + Surface 的内联浮层，避开该崩溃。
 */
@Composable
private fun InlineDialog(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clickable(onClick = {}),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(content = content)
        }
    }
}

@Composable
fun ShootingModeKeyDialog(
    currentKeyCode: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(currentKeyCode) }
    InlineDialog(onDismiss = onDismiss) {
        Text(
            text = stringResource(R.string.shooting_mode_key_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp)
        )
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selected = KeyEvent.KEYCODE_GRAVE },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selected == KeyEvent.KEYCODE_GRAVE,
                    onClick = { selected = KeyEvent.KEYCODE_GRAVE }
                )
                Text(
                    text = stringResource(R.string.shooting_mode_key_grave),
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selected = KEYCODE_MMC },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selected == KEYCODE_MMC,
                    onClick = { selected = KEYCODE_MMC }
                )
                Text(
                    text = stringResource(R.string.shooting_mode_key_mmb),
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.key_rebind_cancel))
            }
            TextButton(onClick = { onConfirm(selected) }) {
                Text(stringResource(R.string.key_rebind_confirm))
            }
        }
    }
}

@Composable
fun KeyCaptureDialog(
    currentWheelRadius: Float = 50f,
    onWheelRadiusChange: (Float) -> Unit = {},
    walkCustomization: Boolean = false,
    onCalibrateWalkOff: () -> Unit = {},
    currentKeyCode: Int,
    currentTouchMode: TouchMode?,
    onConfirm: (Int, TouchMode?) -> Unit,
    onDismiss: () -> Unit,
    onKeyCaptureChanged: (((Int) -> Unit)?) -> Unit = {},
    onRequestFocus: () -> Unit = {}
) {
    var captured by remember { mutableStateOf<Int?>(null) }
    var selectedMode by remember { mutableStateOf(currentTouchMode) }
    var radius by remember { mutableFloatStateOf(currentWheelRadius.coerceIn(10f, 500f)) }
    val borderColor = MaterialTheme.colorScheme.outline

    // 悬浮窗跑在 Service 里没有 Activity 焦点，Compose 的 onKeyEvent 收不到物理按键
    // （这就是「新建按钮永远显示 +、绑定不上」的根因）。改由 View 层 setOnKeyListener
    // 在气泡展开时把按键路由进 StateManager.keyCaptureListener，这里只负责挂/摘回调。
    // 挂载时主动 requestFocus：展开态 releasePointerCapture 可能把窗口焦点冲掉，
    // 导致 setOnKeyListener 收不到按键，弹框时重新抢一次焦点。
    DisposableEffect(Unit) {
        onRequestFocus()
        onKeyCaptureChanged { keyCode ->
            if (!(keyCode >= KeyEvent.KEYCODE_DPAD_UP && keyCode <= KeyEvent.KEYCODE_DPAD_CENTER)) {
                captured = keyCode
            }
        }
        onDispose { onKeyCaptureChanged(null) }
    }

    val display = captured?.let { it.keyCodeToString().removePrefix("KEYCODE_") }
        ?: currentKeyCode.keyCodeToString().removePrefix("KEYCODE_")

    InlineDialog(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.key_rebind_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.key_rebind_current, display),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(R.string.key_rebind_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedCard(
                border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = captured?.let {
                            stringResource(
                                R.string.key_rebind_captured,
                                it.keyCodeToString().removePrefix("KEYCODE_")
                            )
                        } ?: stringResource(R.string.key_rebind_capture),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (selectedMode == TouchMode.WHEEL) {
                Text("鼠标轮盘半径：${radius.roundToInt()} px")
                Slider(value = radius, onValueChange = { radius = it }, valueRange = 10f..500f)
                Text("按住此键打开轮盘，移动鼠标选择，松开确认。圆心为此轮盘按钮的位置，默认半径 50px。", style = MaterialTheme.typography.bodySmall)
            }
            if (walkCustomization) {
                Text("静步定制化：按一次开启，再按一次关闭；开启后按 Shift 自动取消。默认 Caps Lock。不能绑定 Shift 或 WASD。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onCalibrateWalkOff) { Text("游戏已关闭静步：校准为关闭") }
            } else {
            HorizontalDivider()

            Text(
                text = stringResource(R.string.key_touch_mode_title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            TouchMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedMode = mode },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedMode == mode,
                        onClick = { selectedMode = mode }
                    )
                    Text(
                        text = touchModeLabel(mode),
                        modifier = Modifier.padding(start = 4.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectedMode = null },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selectedMode == null,
                    onClick = { selectedMode = null }
                )
                Text(
                    text = stringResource(R.string.key_touch_mode_global),
                    modifier = Modifier.padding(start = 4.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.key_rebind_cancel))
                }
                TextButton(
                    enabled = !walkCustomization || (captured ?: currentKeyCode) !in setOf(
                        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
                        KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D),
                    onClick = { onWheelRadiusChange(radius); onConfirm(captured ?: currentKeyCode, if (walkCustomization) TouchMode.TAP else selectedMode) }
                ) {
                    Text(stringResource(R.string.key_rebind_confirm))
                }
            }
        }
    }
}

@Composable
private fun touchModeLabel(mode: TouchMode): String = when (mode) {
    TouchMode.TAP -> stringResource(R.string.key_touch_mode_tap)
    TouchMode.HOLD -> stringResource(R.string.key_touch_mode_hold)
    TouchMode.MIXED -> stringResource(R.string.key_touch_mode_mixed)
    TouchMode.WHEEL -> stringResource(R.string.key_touch_mode_wheel)
}

@Composable
fun DraggableItem(
    modifier: Modifier = Modifier,
    id: Int,
    offset: Offset,
    scale: Float,
    onOffsetChange: (Offset) -> Unit,
    onScreenCenter: (Offset) -> Unit = {},
    content: @Composable () -> Unit
) {
    // pointerInput survives recomposition. Always invoke the latest callback:
    // callers may have replaced their position state after an earlier drag event.
    val currentOnOffsetChange by rememberUpdatedState(onOffsetChange)
    Box(modifier
        .offset {
            IntOffset(
                offset.x.roundToInt(),
                offset.y.roundToInt()
            )
        }
        .scale(scale)
        .onGloballyPositioned { coordinates ->
            onScreenCenter(coordinates.localToScreen(
                Offset(coordinates.size.width / 2f, coordinates.size.height / 2f)))
        }
        .pointerInput(id, scale) {
            detectDragGestures(onDragEnd = {
            }) { change, dragAmount ->
                change.consume()
                val newOffset = Offset(dragAmount.x, dragAmount.y) * scale
                currentOnOffsetChange(newOffset)
            }
        }) {
        content()
    }
}

@Composable
fun MenuItems(
    modifier: Modifier = Modifier,
    onItemClick: (DraggableItemType) -> Unit
) {
    val itemModifier = Modifier
        .size(30.dp)
        .clip(CircleShape)
        .background(Color.Black)

    // 射击模式切换键用波浪键符号（`）作图标，让用户一眼认出它绑定的是反引号键。
    val shootingMode: @Composable () -> Unit = {
        Text("`", color = Color.Cyan, style = MaterialTheme.typography.titleLarge)
    }
    val fire: @Composable () -> Unit = {
        Image(
            modifier = Modifier.fillMaxSize().padding(4.dp),
            painter = painterResource(R.drawable.bullet),
            contentDescription = stringResource(R.string.cd_fire),
            colorFilter = ColorFilter.tint(Color.Cyan)
        )
    }
    val scopeImg: @Composable () -> Unit = {
        Image(
            modifier = Modifier.fillMaxSize().padding(4.dp),
            painter = painterResource(R.drawable.scope),
            contentDescription = stringResource(R.string.cd_scope),
            colorFilter = ColorFilter.tint(Color.Cyan)
        )
    }
    val wasd: @Composable () -> Unit = {
        Box {
            Text("W\nA S D", color = Color.Cyan, textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall)
        }
    }

    fun symbol(symbol: String): @Composable () -> Unit = {
        Text(symbol, color = Color.Cyan, style = MaterialTheme.typography.titleSmall)
    }

    Column(
        modifier = modifier.background(
            MaterialTheme.colorScheme.surface,
            CircleShape.copy(CornerSize(10))
        )
    ) {
        Text(
            modifier = Modifier.padding(top = 4.dp, start = 8.dp),
            text = stringResource(R.string.dialog_add_keymap_for),
            style = MaterialTheme.typography.labelSmall
        )
        LazyColumn(
            modifier = Modifier
                .heightIn(max = 480.dp)
                .widthIn(max = 220.dp)
                .padding(vertical = 4.dp)
        ) {
            // ---- Movement ----
            item("h-move") { SectionHeader(stringResource(R.string.section_movement)) }
            item("r-move") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MenuCell(itemModifier, wasd, stringResource(R.string.label_compass)) { onItemClick(DraggableItemType.WASD_KEY) }
                    // 通用「按钮」：新建后点齿轮绑定按键 + 选点击/按住模式。
                    MenuCell(itemModifier, symbol("KEY"), stringResource(R.string.label_key)) { onItemClick(DraggableItemType.KEY) }
                    MenuCell(itemModifier, symbol("静"), "静步定制化") { onItemClick(DraggableItemType.WALK_TOGGLE) }
                    Spacer(modifier = Modifier.size(30.dp))
                }
            }

            // ---- Combat ----
            item("h-combat") { SectionHeader(stringResource(R.string.section_combat)) }
            item("r-combat") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MenuCell(itemModifier, fire, stringResource(R.string.label_fire)) { onItemClick(DraggableItemType.FIRE) }
                    MenuCell(itemModifier, shootingMode, stringResource(R.string.label_shoot_mode)) { onItemClick(DraggableItemType.SHOOTING_MODE) }
                    MenuCell(itemModifier, scopeImg, stringResource(R.string.label_scope)) { onItemClick(DraggableItemType.SCOPE) }
                    Spacer(modifier = Modifier.size(30.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 2.dp),
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun MenuCell(
    boxModifier: Modifier,
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(48.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = boxModifier, contentAlignment = Alignment.Center) { icon() }
        Text(
            label,
            style = TextStyle(fontSize = 8.sp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}


@Composable
fun SettingsLayout(
    modifier: Modifier = Modifier,
    pointerSensitivity: Float = 0.5f,
    overlayOpacity: Float = 0.5f,
    profiles: List<Profile> = emptyList(),
    activeProfileId: String? = null,
    onSwitchProfile: (String) -> Unit = {},
    onSetupTwoProfiles: () -> Unit = {},
    buttonScale: Float = 1f,
    onButtonScaleChange: (Float) -> Unit = {},
    onAdvancedSettingsClick: () -> Unit,
    onCloseOverlayClick: () -> Unit,
    onPointerSensChange: (Float) -> Unit,
    onOpacityChange: (Float) -> Unit
) {
    Column(
        modifier
            .clip(CircleShape.copy(CornerSize(10)))
            .background(color = MaterialTheme.colorScheme.surface)
            .padding(8.dp)
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("同一游戏 · 多套预设", style = MaterialTheme.typography.titleMedium)
        profiles.forEach { profile ->
            TextButton(onClick = { onSwitchProfile(profile.id) }) {
                val shortcut = if (profile.activationHotkeyEnabled) profile.activationKeyCode?.keyCodeToString() else null
                Text((if (profile.id == activeProfileId) "✓ " else "") + profile.name +
                    (shortcut?.let { "  [$it]" } ?: ""))
            }
        }
        if (profiles.size < 2) TextButton(onClick = onSetupTwoProfiles) { Text("复制当前布局，建立 X / 1 两套预设") }
        HorizontalDivider()
        Text("按钮大小 ${(buttonScale * 100).roundToInt()}%",
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Slider(value = buttonScale.coerceIn(0.6f, 2f), onValueChange = onButtonScaleChange,
            valueRange = 0.6f..2f, steps = 13)
        Text("调整普通按钮显示大小；WASD 用右下角手柄缩放", style = MaterialTheme.typography.labelSmall)
        Row {
            Text(
                text = stringResource(R.string.slider_pointer_sensitivity),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
            )
            Text(
                "${(pointerSensitivity * 100).roundToInt()} %",
                modifier = Modifier
                    .padding(start = 16.dp)
                    .align(Alignment.CenterVertically),
                style = TextStyle(color = MaterialTheme.colorScheme.tertiary)
            )
        }

        Slider(
            value = pointerSensitivity,
            onValueChange = onPointerSensChange,
            valueRange = 0.1f..1.0f,
            steps = 8
        )
        Row {
            Text(
                text = stringResource(R.string.slider_overlay_opacity),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
            )
            Text(
                "${(overlayOpacity * 100).roundToInt()} %",
                modifier = Modifier
                    .padding(start = 16.dp)
                    .align(Alignment.CenterVertically),
                style = TextStyle(color = MaterialTheme.colorScheme.tertiary)
            )
        }
        Slider(
            value = overlayOpacity,
            onValueChange = onOpacityChange,
            valueRange = 0.0f..1.0f,
            steps = 9
        )
        HorizontalDivider()
        TextButton(onClick = onAdvancedSettingsClick) {
            Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.cd_advanced_settings))
            Text(
                "Advanced Settings",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        }
        TextButton(onClick = onCloseOverlayClick) {
            Icon(
                Icons.AutoMirrored.Rounded.ExitToApp,
                contentDescription = stringResource(R.string.cd_close),
                tint = MaterialTheme.colorScheme.tertiary
            )
            Text(
                "Close Overlay",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        }
    }
}


@Preview
@Composable
private fun PreviewMenuItems() {
    MenuItems(onItemClick = {})
}

@Preview()
@Composable
private fun PreviewSettingsLayout() {
    SettingsLayout(
        Modifier.clip(
            CircleShape.copy(CornerSize(10))
        ),
        onAdvancedSettingsClick = { },
        onPointerSensChange = { },
        onOpacityChange = { },
        onCloseOverlayClick = {}
    )
}

internal class ServiceLifecycleOwner : SavedStateRegistryOwner {
    private var mSavedStateRegistryController: SavedStateRegistryController =
        SavedStateRegistryController.create(this)

    private var lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = mSavedStateRegistryController.savedStateRegistry

    fun setCurrentState(state: Lifecycle.State) {
        lifecycleRegistry.currentState = state
    }

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }

    fun performRestore(savedState: Bundle?) {
        mSavedStateRegistryController.performRestore(savedState)
    }

    fun performSave(outBundle: Bundle) {
        mSavedStateRegistryController.performSave(outBundle)
    }
}