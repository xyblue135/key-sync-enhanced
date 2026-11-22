package com.devoid.keysync.ui.overlay

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.devoid.keysync.ui.MouseInputPicker
import com.devoid.keysync.ui.touchModeColor
import com.devoid.keysync.domain.KEYCODE_MMC
import com.devoid.keysync.model.Profile
import com.devoid.keysync.model.AppConfig
import com.devoid.keysync.model.DraggableItem
import com.devoid.keysync.model.DraggableItemType
import com.devoid.keysync.model.ExtraTapPoint
import com.devoid.keysync.model.MAX_EXTRA_TAP_POINTS
import com.devoid.keysync.model.TouchMode
import com.devoid.keysync.model.clampToViewport
import com.devoid.keysync.model.layoutSizeForClamp
import com.devoid.keysync.util.keyCodeToString
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt


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
    onUpdateCancelableKeyCode: (Int, Int, TouchMode?) -> Unit = { _, _, _ -> },
    onUpdateItemSize: (Int, Float) -> Unit = { _, _ -> },
    pendingBindId: Int? = null,
    onBindConsumed: () -> Unit = {},
    onKeyCaptureChanged: (((Int) -> Unit)?) -> Unit = {},
    pressedKeys: Set<Int> = emptySet(),
    onRequestFocus: () -> Unit = {},
    /** 当前预设的连按落点，编辑态渲染成可拖动的小圆圈。 */
    extraTapPoints: List<ExtraTapPoint> = emptyList(),
    onAddExtraTapPoint: (Int) -> Unit = {},
    onMoveExtraTapPoint: (Int, Int, Offset) -> Unit = { _, _, _ -> },
    onClearExtraTapPoints: (Int) -> Unit = {},
    /** Alt+D 拖拽丢弃的落点（竖条中心）；只在编辑态渲染。 */
    discardPoint: Offset? = null,
    onMoveDiscardPoint: (Offset) -> Unit = {},
    /** 编辑态图层：true = 高级层（丢弃点可拖动，按键层半透明预览）。 */
    advancedLayerActive: Boolean = false,
    /** 运行时是否显示普通按键层（Enter 层级预览用）。 */
    showKeys: Boolean = true,
    /** 运行时是否显示高级层标记（丢弃点），非编辑态只读。 */
    showAdvancedMarkers: Boolean = false,
) {
    // When the user taps the gear icon on a key, this is set so we can
    // pop up a key-capture dialog bound to that specific item.
    var configuringWasd by remember { mutableStateOf<DraggableItem.WASDGroup?>(null) }
    var configuringFixedKey by remember { mutableStateOf<DraggableItem.FixedKey?>(null) }
    var configuringVariableKey by remember { mutableStateOf<DraggableItem.VariableKey?>(null) }
    var configuringCancelableKey by remember { mutableStateOf<DraggableItem.CancelableKey?>(null) }

    // 编辑态分层：高级层激活时，普通按键半透明预览且不接收拖动，避免互相抢触摸。
    val keysLayerInteractive = !advancedLayerActive
    val keysLayerAlpha = if (advancedLayerActive) 0.25f else 1f
    // 任何配置弹框打开时都禁用底层按钮的拖动，避免弹框里的手势把按钮挪走。
    val draggingEnabled = keysLayerInteractive && configuringWasd == null &&
        configuringFixedKey == null && configuringVariableKey == null &&
        configuringCancelableKey == null

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

    // The overlay window is the coordinate space the items live in. Measuring it
    // here, instead of trusting a display metric, is what keeps the clamp honest
    // when the window is smaller than the display -- which is exactly what
    // happens in landscape, where the left edge is the one that gets cut off.
    var viewport by remember { mutableStateOf(Size.Zero) }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { viewport = Size(it.width.toFloat(), it.height.toFloat()) }
    ) {
        if (showKeys) {
        containerItems.forEach { item ->
            key(item.id) {

                var position by remember(item.id, item.position) { mutableStateOf(item.position) }
                var scale by remember(item.id, (item as? DraggableItem.WASDGroup)?.scale, appConfig.buttonScale) { mutableFloatStateOf(if (item is DraggableItem.WASDGroup) item.scale else appConfig.buttonScale) }
                if (item is DraggableItem.CancelableKey) {
                    if (item.type == DraggableItemType.BAG_MAP || item.type == DraggableItemType.BACKPACK) {
                        var cancelableKeyPosition by remember(item.id, item.cancelPosition) { mutableStateOf(item.cancelPosition) }
                        val text = item.keyCode
                            ?.let { KeyEvent.keyCodeToString(it).replace("KEYCODE_", "") } ?: "?"

                        DraggableItem(
                            scale = scale,
                            id = item.id,
                            offset = position,
                            enabled = draggingEnabled,
                            alpha = keysLayerAlpha,
                            onDoubleTap = if (editing && keysLayerInteractive) ({ configuringCancelableKey = item }) else null,
                            onScreenCenter = { item.touchCenter = it; onItemMeasured(item) },
                            onOffsetChange = {
                                val target = clampToViewport(
                                    position + it,
                                    item.layoutSizeForClamp(scale),
                                    scale,
                                    viewport,
                                )
                                item.position = target
                                position = target
                                item.anchorPosition = target
                            }
                        ) {
                            BagMapKey(
                                modifier = Modifier.onSizeChanged { item.size = it.width },
                                text = text,
                                borderColor = MaterialTheme.colorScheme.primary,
                            )
                        }
                        DraggableItem(
                            scale = scale,
                            id = item.id,
                            offset = cancelableKeyPosition,
                            enabled = draggingEnabled,
                            alpha = keysLayerAlpha,
                            onScreenCenter = { item.cancelTouchCenter = it; onItemMeasured(item) },
                            onOffsetChange = {
                                val target = clampToViewport(
                                    cancelableKeyPosition + it,
                                    item.layoutSizeForClamp(scale),
                                    scale,
                                    viewport,
                                )
                                item.cancelPosition = target
                                cancelableKeyPosition = target
                            }
                        ) {
                            CancelKey(
                                borderColor = MaterialTheme.colorScheme.primary,
                                onClick = {}
                            )
                        }
                    }
                } else {
                DraggableItem(
                    scale = scale,
                    id = item.id,
                    offset = position,
                    enabled = draggingEnabled,
                    alpha = keysLayerAlpha,
                    onDoubleTap = if (editing && keysLayerInteractive) ({
                        when (item) {
                            is DraggableItem.VariableKey -> configuringVariableKey = item
                            is DraggableItem.WASDGroup -> configuringWasd = item
                            is DraggableItem.FixedKey -> configuringFixedKey = item
                            else -> Unit
                        }
                    }) else null,
                    onScreenCenter = { item.touchCenter = it; onItemMeasured(item) },
                    onOffsetChange = {
                        val target = clampToViewport(
                            position + it,
                            item.layoutSizeForClamp(scale),
                            scale,
                            viewport,
                        )
                        item.position = target
                        position = target
                        item.anchorPosition = target
                    }
                ) {
                    when (item) {
                        is DraggableItem.VariableKey -> {
                            // 通用「按钮」：双击打开设置（绑定按键 / 点击模式 / 大小 / 删除）。
                            // 未绑定前显示 "+"，绑定后显示真实键名；按下时高亮。
                            GenericFixedKey(
                                modifier = Modifier.onSizeChanged { item.size = it.width },
                                sizeDp = item.customSizeDp.dp,
                                label = item.keyCode?.keyCodeToString()?.removePrefix("KEYCODE_") ?: "+",
                                pressed = item.keyCode?.let { pressedKeys.contains(it) } ?: false,
                            )
                        }

                        is DraggableItem.WASDGroup -> {
                            WASDKeysGroup(modifier = Modifier
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
                                    // The joystick map is built from these four
                                    // points, but it is populated before the
                                    // first layout pass, so it would otherwise
                                    // keep the geometry of the previous layout.
                                    onItemMeasured(item)

                                }, onPanIconDrag = { dragAmount ->
                                val scaleAmount = scale + (dragAmount.y / 500)
                                scale = max(min(scaleAmount, 1.4f), 0.6f)
                                item.scale = scale
                            }, pressedKeys = pressedKeys, editable = editing)
                        }

                        is DraggableItem.FixedKey -> {
                            val onSizeChangeListener = { intSize: IntSize ->
                                item.size = intSize.width
                            }
                            // 所有固定键统一用文字显示其真实物理按键，避免图标或
                            // 硬编码字母与实际绑定键不一致（如 JUMP 显示 J 实为 SPACE）。
                            GenericFixedKey(
                                modifier = Modifier.onSizeChanged { onSizeChangeListener(it) },
                                sizeDp = item.customSizeDp.dp,
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
                            )
                        }

                        else -> {}
                    }

                }

                }
            }
        }
        }

        // 编辑态显示连按落点：虚线小圈直接拖动，松手才写回预设。
        if (editing && showKeys) {
            extraTapPoints.forEachIndexed { index, point ->
                key("extra-${point.keyCode}-$index") {
                    var pos by remember(point.x, point.y) { mutableStateOf(Offset(point.x, point.y)) }
                    val density = LocalDensity.current
                    val radiusPx = with(density) { 14.dp.toPx() }
                    Box(
                        modifier = Modifier
                            .offset { IntOffset((pos.x - radiusPx).toInt(), (pos.y - radiusPx).toInt()) }
                            .size(28.dp)
                            .alpha(0.75f * keysLayerAlpha)
                            .border(1.5.dp, MaterialTheme.colorScheme.tertiary, CircleShape)
                            .pointerInput(point.keyCode, point.x, point.y, keysLayerInteractive) {
                                if (!keysLayerInteractive) return@pointerInput
                                detectDragGestures(
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        pos += dragAmount
                                    },
                                    onDragEnd = {
                                        onMoveExtraTapPoint(point.keyCode, index, pos)
                                    },
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${point.keyCode.keyCodeToString().removePrefix("KEYCODE_")}·${index + 2}",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 8.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        // 编辑态显示拖拽丢弃点：竖条（约 1:5），拖动定位置；
        // 运行时的 Enter 层级预览也会渲染，但不可拖动。
        if (editing || showAdvancedMarkers) {
            val density = LocalDensity.current
            val discardBarWidthPx = with(density) { DISCARD_BAR_WIDTH_DP.dp.toPx() }
            val discardBarHeightPx = with(density) { DISCARD_BAR_HEIGHT_DP.dp.toPx() }
            discardPoint?.let { point ->
                var pos by remember(point.x, point.y) { mutableStateOf(point) }
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset((pos.x - discardBarWidthPx / 2).toInt(),
                                (pos.y - discardBarHeightPx / 2).toInt())
                        }
                        .size(DISCARD_BAR_WIDTH_DP.dp, DISCARD_BAR_HEIGHT_DP.dp)
                        .alpha(if (!editing) 1f else if (advancedLayerActive) 0.8f else 0.25f)
                        .border(1.5.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(6.dp))
                        .pointerInput(point.x, point.y, advancedLayerActive, editing) {
                            if (!editing || !advancedLayerActive) return@pointerInput
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    pos += dragAmount
                                },
                                onDragEnd = { onMoveDiscardPoint(pos) },
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "丢弃",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
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
                    Text("疾跑落点统一落在同一圈上，斜跑不再叠加两轴距离；只有 S 取消疾跑。保存后退出编辑生效。", style = MaterialTheme.typography.bodySmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(
                            onClick = {
                                onRemove(target.id)
                                configuringWasd = null
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text("删除按键") }
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
                    onDismiss = { configuringFixedKey = null },
                    onDelete = {
                        onRemove(target.id)
                        configuringFixedKey = null
                    }
                )
            } else {
                KeyCaptureDialog(
                    walkCustomization = target.type == DraggableItemType.WALK_TOGGLE,
                    onCalibrateWalkOff = onCalibrateWalkOff,
                    currentKeyCode = target.keyCode,
                    currentWheelRadius = target.wheelRadius,
                    onWheelRadiusChange = { target.wheelRadius = it },
                    currentSizeDp = target.customSizeDp,
                    onSizeDpChange = { onUpdateItemSize(target.id, it) },
                    currentTouchMode = target.touchMode,
                    onConfirm = { newCode, touchMode ->
                        onUpdateKeyCode(target.id, newCode, touchMode)
                        configuringFixedKey = null
                    },
                    onDismiss = { configuringFixedKey = null },
                    onKeyCaptureChanged = onKeyCaptureChanged,
                    onRequestFocus = onRequestFocus,
                    onDelete = {
                        onRemove(target.id)
                        configuringFixedKey = null
                    }
                )
            }
        }

        configuringVariableKey?.let { target ->
            KeyCaptureDialog(
                currentKeyCode = target.keyCode ?: KeyEvent.KEYCODE_UNKNOWN,
                currentWheelRadius = target.wheelRadius,
                onWheelRadiusChange = { target.wheelRadius = it },
                currentSizeDp = target.customSizeDp,
                onSizeDpChange = { onUpdateItemSize(target.id, it) },
                currentTouchMode = target.touchMode,
                onConfirm = { newCode, touchMode ->
                    onUpdateVariableKeyCode(target.id, newCode, touchMode)
                    configuringVariableKey = null
                },
                onDismiss = { configuringVariableKey = null },
                onKeyCaptureChanged = onKeyCaptureChanged,
                onRequestFocus = onRequestFocus,
                extraTapPoints = extraTapPoints,
                onAddExtraTap = { onAddExtraTapPoint(it) },
                onClearExtraTaps = { onClearExtraTapPoints(it) },
                conflictKeys = otherOccupiedKeys(containerItems, target.id),
                onDelete = {
                    onRemove(target.id)
                    configuringVariableKey = null
                }
            )
        }

        configuringCancelableKey?.let { target ->
            KeyCaptureDialog(
                currentKeyCode = target.keyCode ?: KeyEvent.KEYCODE_UNKNOWN,
                currentWheelRadius = target.wheelRadius,
                onWheelRadiusChange = { target.wheelRadius = it },
                currentSizeDp = target.customSizeDp,
                onSizeDpChange = { onUpdateItemSize(target.id, it) },
                currentTouchMode = target.touchMode,
                onConfirm = { newCode, touchMode ->
                    onUpdateCancelableKeyCode(target.id, newCode, touchMode)
                    configuringCancelableKey = null
                },
                onDismiss = { configuringCancelableKey = null },
                onKeyCaptureChanged = onKeyCaptureChanged,
                onRequestFocus = onRequestFocus,
                extraTapPoints = extraTapPoints,
                onAddExtraTap = { onAddExtraTapPoint(it) },
                onClearExtraTaps = { onClearExtraTapPoints(it) },
                conflictKeys = otherOccupiedKeys(containerItems, target.id),
                onDelete = {
                    onRemove(target.id)
                    configuringCancelableKey = null
                }
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
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null
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
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onDelete != null) {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("删除按键")
                }
            } else {
                Spacer(Modifier.width(4.dp))
            }
            Row {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.key_rebind_cancel))
                }
                TextButton(onClick = { onConfirm(selected) }) {
                    Text(stringResource(R.string.key_rebind_confirm))
                }
            }
        }
    }
}

/** 该按钮占用的物理按键；摇杆组按 W/A/S/D 四个方向算。 */
private fun DraggableItem.occupiedKeyCodes(): List<Int> = when (this) {
    is DraggableItem.VariableKey -> listOfNotNull(keyCode)
    is DraggableItem.FixedKey -> listOf(keyCode)
    is DraggableItem.CancelableKey -> listOfNotNull(keyCode)
    is DraggableItem.WASDGroup -> listOf(
        KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D
    )
}

/** 除自己以外，布局里已经被占用的按键集合。 */
private fun otherOccupiedKeys(items: List<DraggableItem>, selfId: Int): Set<Int> =
    items.filter { it.id != selfId }.flatMap { it.occupiedKeyCodes() }.toSet()

@Composable
fun KeyCaptureDialog(    currentWheelRadius: Float = 50f,
    onWheelRadiusChange: (Float) -> Unit = {},
    currentSizeDp: Float = 50f,
    onSizeDpChange: (Float) -> Unit = {},
    walkCustomization: Boolean = false,
    onCalibrateWalkOff: () -> Unit = {},
    currentKeyCode: Int,
    currentTouchMode: TouchMode?,
    onConfirm: (Int, TouchMode?) -> Unit,
    onDismiss: () -> Unit,
    onKeyCaptureChanged: (((Int) -> Unit)?) -> Unit = {},
    onRequestFocus: () -> Unit = {},
    /** 非空时在底部显示「删除按键」：删除入口从悬浮窗移进设置页，避免误触。 */
    onDelete: (() -> Unit)? = null,
    /** 当前预设的全部连按落点，用于显示数量与编辑。 */
    extraTapPoints: List<ExtraTapPoint> = emptyList(),
    onAddExtraTap: ((Int) -> Unit)? = null,
    onClearExtraTaps: ((Int) -> Unit)? = null,
    /** 已被其他按钮占用的按键：命中时禁止确认，避免重复键互相覆盖。 */
    conflictKeys: Set<Int> = emptySet()
) {
    var captured by remember { mutableStateOf<Int?>(null) }
    var selectedMode by remember { mutableStateOf(currentTouchMode) }
    var radius by remember { mutableFloatStateOf(currentWheelRadius.coerceIn(10f, 500f)) }
    var sizeDp by remember { mutableFloatStateOf(currentSizeDp.coerceIn(28f, 110f)) }
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
    // 待确认的按键：已被其他按钮占用时禁止确认，避免重复键在映射表里互相覆盖。
    val nextCode = captured ?: currentKeyCode
    val conflict = nextCode != KeyEvent.KEYCODE_UNKNOWN && nextCode in conflictKeys
    val nextExtraCount = extraTapPoints.count { it.keyCode == nextCode }

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

            MouseInputPicker(captured ?: currentKeyCode) { captured = it }
            Text("按钮大小：${sizeDp.roundToInt()} dp", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = sizeDp,
                onValueChange = { sizeDp = it; onSizeDpChange(it) },
                valueRange = 28f..110f
            )
            Text("侧键或滚轮可直接触发捕获，也可从鼠标绑定列表选择。滚轮绑定始终点击一次；轮盘打开时优先用于轮盘选择。",
                style = MaterialTheme.typography.bodySmall)
            if (conflict) {
                Text(
                    "该按键已被其他按钮使用，请换一个键；重复绑定会让其中一个按钮失效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (!walkCustomization && onAddExtraTap != null && onClearExtraTaps != null) {
                HorizontalDivider()
                Text(
                    "连按落点 $nextExtraCount/$MAX_EXTRA_TAP_POINTS",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "同一按键在 ${com.devoid.keysync.model.EXTRA_TAP_WINDOW_MS}ms 内连续按下时，第 2/3/4 次会额外点击一个落点，本体始终照常触发。添加后在编辑态拖动小圆圈定位。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row {
                    TextButton(
                        enabled = nextCode != KeyEvent.KEYCODE_UNKNOWN &&
                            nextExtraCount < MAX_EXTRA_TAP_POINTS,
                        onClick = { onAddExtraTap(nextCode) }
                    ) { Text("添加连按落点") }
                    TextButton(
                        enabled = nextExtraCount > 0 && nextCode != KeyEvent.KEYCODE_UNKNOWN,
                        onClick = { onClearExtraTaps(nextCode) }
                    ) { Text("清空落点") }
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
                        onClick = { selectedMode = mode },
                        colors = RadioButtonDefaults.colors(selectedColor = touchModeColor(mode))
                    )
                    Text(
                        text = touchModeLabel(mode),
                        modifier = Modifier.padding(start = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = touchModeColor(mode)
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
                    onClick = { selectedMode = null },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                Text(
                    text = stringResource(R.string.key_touch_mode_global),
                    modifier = Modifier.padding(start = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onDelete != null) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("删除按键")
                    }
                } else {
                    Spacer(Modifier.width(4.dp))
                }
                Row {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.key_rebind_cancel))
                    }
                    TextButton(
                        enabled = !conflict && (!walkCustomization || (captured ?: currentKeyCode) !in setOf(
                            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
                            KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D)),
                        onClick = { onWheelRadiusChange(radius); onConfirm(captured ?: currentKeyCode, if (walkCustomization) TouchMode.TAP else selectedMode) }
                    ) {
                        Text(stringResource(R.string.key_rebind_confirm))
                    }
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
    enabled: Boolean = true,
    alpha: Float = 1f,
    onDoubleTap: (() -> Unit)? = null,
    onOffsetChange: (Offset) -> Unit,
    onScreenCenter: (Offset) -> Unit = {},
    content: @Composable () -> Unit
) {
    // pointerInput survives recomposition. Always invoke the latest callback:
    // callers may have replaced their position state after an earlier drag event.
    val currentOnOffsetChange by rememberUpdatedState(onOffsetChange)
    val currentOnDoubleTap by rememberUpdatedState(onDoubleTap)
    Box(modifier
        .alpha(alpha)
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
        .pointerInput(id, enabled, onDoubleTap != null) {
            // 编辑态双击才打开按钮设置：单击不再弹任何悬浮图标，
            // 避免齿轮/删除按钮挡住映射或误触删除。
            if (!enabled) return@pointerInput
            val callback = currentOnDoubleTap ?: return@pointerInput
            detectTapGestures(onDoubleTap = { callback() })
        }
        .pointerInput(id, scale, enabled) {
            // 配置对话框打开时禁用拖动：此时屏幕上的手势属于对话框，
            // 绝不能让底层按钮跟着位移。
            if (!enabled) return@pointerInput
            detectDragGestures(onDragEnd = {
            }) { change, dragAmount ->
                change.consume()
                val newOffset = Offset(dragAmount.x, dragAmount.y) * scale
                currentOnOffsetChange(newOffset)
            }
        }) {
        content()
        // 编辑态恢复齿轮入口：点一下直接打开配置框，不必再记住双击。
        // 只在编辑态且该按钮有配置回调时出现；非编辑态不渲染任何额外控件。
        if (enabled && onDoubleTap != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 12.dp, y = (-12).dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { currentOnDoubleTap?.invoke() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Settings,
                    contentDescription = "配置按键",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}

/**
 * 编辑态操作条：只在编辑态渲染。非编辑态屏幕上不出现任何常驻控件，
 * 编辑 / 按键显隐 / 退出编辑统一由通知栏 action 驱动。
 */
@Composable
fun EditToolbar(
    modifier: Modifier = Modifier,
    keysVisible: Boolean,
    statusBarVisible: Boolean = true,
    advancedLayerActive: Boolean = false,
    onToggleKeysVisible: () -> Unit,
    onToggleStatusBarVisible: () -> Unit = {},
    onToggleAdvancedLayer: () -> Unit = {},
    onToggleEditMode: () -> Unit,
    onAddItem: (DraggableItemType) -> Unit,
    onAddDiscardPoint: () -> Unit = {},
) {
    var itemsMenuVisible by remember { mutableStateOf(false) }
    // 编辑条会挡住想摆放的位置，因此允许直接拖动（按 ⠿ 手柄拖动，避免和按钮点击冲突）。
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    // 默认只显示一个居中的圆球：右上角经常放自定义按键，工具栏展开会挡住它。
    // 点圆球展开完整工具栏，拖圆球可把它挪开。
    var collapsed by remember { mutableStateOf(true) }
    // 根节点必须撑满父容器：下拉菜单是本 Box 的子节点，wrap-content 时菜单展开会
    // 把 Box 撑大，而 Box 在父级又按 TopEnd 对齐，结果整条工具栏（含 ⠿ 手柄）
    // 被挤到左边——就是「重新打开配置菜单，按钮被顶到一边」。撑满后工具栏与菜单
    // 各自按 TopEnd 定位，菜单的尺寸变化不再影响工具栏位置。
    Box(modifier.fillMaxSize().offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }) {
        if (collapsed) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(8.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            dragOffset += drag
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { collapsed = false })
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Settings,
                    contentDescription = "展开编辑工具栏",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        } else {
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .clip(CircleShape.copy(CornerSize(10)))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            dragOffset += drag
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("⠿", color = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = { itemsMenuVisible = !itemsMenuVisible }) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.cd_add_item),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            TextButton(onClick = onToggleKeysVisible) {
                Text(
                    if (keysVisible) stringResource(R.string.overlay_hide_keys)
                    else stringResource(R.string.overlay_show_keys)
                )
            }
            TextButton(onClick = onToggleStatusBarVisible) {
                Text(
                    if (statusBarVisible) stringResource(R.string.overlay_hide_status)
                    else stringResource(R.string.overlay_show_status)
                )
            }
            // 编辑态图层：按键层与高级层（丢弃点/滚动锚点）分开，避免互相挡住拖动。
            TextButton(onClick = onToggleAdvancedLayer) {
                Text(if (advancedLayerActive) "高级层" else "按键层")
            }
            TextButton(onClick = onToggleEditMode) {
                Text(stringResource(R.string.overlay_done))
            }
            TextButton(onClick = { collapsed = true }) {
                Text("收起")
            }
        }
        if (itemsMenuVisible) {
            Box(modifier = Modifier.align(Alignment.Center).offset(y = 48.dp)) {
                MenuItems(onItemClick = {
                    onAddItem(it)
                    itemsMenuVisible = false
                }, onAddDiscardPoint = {
                    onAddDiscardPoint()
                    itemsMenuVisible = false
                })
            }
        }
        }
    }
}

@Composable
fun MenuItems(
    modifier: Modifier = Modifier,
    onItemClick: (DraggableItemType) -> Unit,
    onAddDiscardPoint: () -> Unit = {}
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

            // ---- Tools ----
            item("h-tools") { SectionHeader("工具") }
            item("r-tools") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MenuCell(itemModifier, symbol("▮"), "丢弃点") { onAddDiscardPoint() }
                    Spacer(modifier = Modifier.size(30.dp))
                    Spacer(modifier = Modifier.size(30.dp))
                    Spacer(modifier = Modifier.size(30.dp))
                }
            }
        }
    }
}

/** 丢弃点竖条尺寸：宽:高 ≈ 1:5。 */
private const val DISCARD_BAR_WIDTH_DP = 36
private const val DISCARD_BAR_HEIGHT_DP = 180

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


@Preview
@Composable
private fun PreviewMenuItems() {
    MenuItems(onItemClick = {})
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