package com.devoid.keysync.ui.overlay

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.platform.LocalConfiguration
import com.devoid.keysync.R
import com.devoid.keysync.ui.MouseInputPicker
import com.devoid.keysync.ui.touchModeColor
import com.devoid.keysync.domain.AimRecenterMode
import com.devoid.keysync.domain.EventHandler
import com.devoid.keysync.domain.KEYCODE_MMC
import com.devoid.keysync.model.Profile
import com.devoid.keysync.model.AppConfig
import com.devoid.keysync.model.AreaMath
import com.devoid.keysync.model.AreaPoint
import com.devoid.keysync.model.DraggableItem
import com.devoid.keysync.model.DraggableItemType
import com.devoid.keysync.model.ExtraTapPoint
import com.devoid.keysync.model.MAX_EXTRA_TAP_POINTS
import com.devoid.keysync.model.MAX_SHOOTING_AREA_POINTS
import com.devoid.keysync.model.TouchMode
import com.devoid.keysync.model.clampToViewport
import com.devoid.keysync.model.layoutSizeForClamp
import com.devoid.keysync.util.keyCodeToString
import kotlin.math.abs
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
    onRemoveExtraTapPoint: (Int, Int) -> Unit = { _, _ -> },
    onClearExtraTapPoints: (Int) -> Unit = {},
    /** Alt+D 拖拽丢弃的落点（竖条中心）；只在编辑态渲染。 */
    discardPoint: Offset? = null,
    onMoveDiscardPoint: (Offset) -> Unit = {},
    /** 进入光标模式时光标的落点；编辑态高级层可拖动，拖回中心即恢复默认。 */
    cursorSpawn: Offset? = null,
    onMoveCursorSpawn: (Offset) -> Unit = {},
    onResetCursorSpawn: () -> Unit = {},
    /** 鼠标活动范围（大范围，手绘轮廓）与回中随机落点范围（小方形），均为悬浮层本地坐标。 */
    mouseMoveAreaCenter: Offset? = null,
    mouseMoveAreaHalf: Float = 0f,
    landingAreaCenter: Offset? = null,
    landingAreaHalf: Float = 0f,
    /** ` 按钮的本地中心，用于齿轮设置里按相对偏移调整双范围。 */
    shootingModeAnchor: Offset? = null,
    onMouseAreaChange: (Offset, Float) -> Unit = { _, _ -> },
    onLandingAreaChange: (Offset, Float) -> Unit = { _, _ -> },
    /** 射击模式空闲多久后重新落指（ms），0 = 关闭；在 ` 的齿轮设置里调整。 */
    aimIdleReanchorMs: Int = 1000,
    onAimIdleReanchorMsChange: (Int) -> Unit = {},
    /** 瞄准回中预设（经典 / 即回中 / 缓冲补发）。 */
    aimRecenterMode: AimRecenterMode = AimRecenterMode.INSTANT,
    onAimRecenterModeChange: (AimRecenterMode) -> Unit = {},
    /** WASD 回中落点随机圆半径（px），编辑态在摇杆中心画出来。 */
    wasdCenterJitterPx: Int = 8,
    /** ` 键自定义区域（相对按键左上角的顶点）；空 = 默认方形。 */
    shootingArea: List<AreaPoint> = emptyList(),
    /** 正在手绘时的草稿；null = 未在绘制。 */
    shootingAreaDraft: List<AreaPoint>? = null,
    onStartShootingAreaDrawing: () -> Unit = {},
    onShootingAreaTap: (Offset) -> Unit = {},
    onUndoShootingAreaPoint: () -> Unit = {},
    onClearShootingAreaDraft: () -> Unit = {},
    onFinishShootingArea: () -> Unit = {},
    onCancelShootingArea: () -> Unit = {},
    onClearShootingArea: () -> Unit = {},
    /** 全局触摸模式：编辑态给按钮上色，便于一眼看清布局。 */
    normalTouchMode: TouchMode = TouchMode.TAP,
    cancelableTouchMode: TouchMode = TouchMode.TAP,
    /** 运行时是否显示普通按键层（Enter 层级预览用）。 */
    showKeys: Boolean = true,
    /** 运行时是否显示高级层标记（丢弃点），非编辑态只读。 */
    showAdvancedMarkers: Boolean = false,
) {
    // When the user taps the gear icon on a key, this is set so we can
    // pop up a key-capture dialog bound to that specific item.
    var configuringWasd by remember { mutableStateOf<DraggableItem.WASDGroup?>(null) }
    /** 疾跑距离滑杆的实时预览（前向, 横向），让半径圈跟着滑杆变化。 */
    var wasdSprintPreview by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    // WASD 组在拖动时 item.center 是原地修改的，不会触发重组；这里用快照让外圈跟着走。
    var wasdRingGeometry by remember { mutableStateOf<WasdRingGeometry?>(null) }
    var configuringFixedKey by remember { mutableStateOf<DraggableItem.FixedKey?>(null) }
    var configuringVariableKey by remember { mutableStateOf<DraggableItem.VariableKey?>(null) }
    var configuringCancelableKey by remember { mutableStateOf<DraggableItem.CancelableKey?>(null) }



    // 只保留一个编辑层：按键、鼠标范围、丢弃点、连按落点同时可见可拖。
    // 之前分「按键层 / 高级层」，切换状态容易丢、也容易误触，已合并。
    val keysLayerInteractive = true
    val keysLayerAlpha = 1f
    // 任何配置弹框打开时都禁用底层按钮的拖动，避免弹框里的手势把按钮挪走。
    val draggingEnabled = configuringWasd == null &&
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
        // 编辑态显示 WASD 的普通 / 疾跑半径圈，方便对照 Shift 位移设置是否合理。
        if (editing) {
            val wasdGroup = containerItems.filterIsInstance<DraggableItem.WASDGroup>().firstOrNull()
            val ring = wasdRingGeometry
            if (wasdGroup != null && ring != null) {
                WasdRadiusRings(wasdGroup, ring, wasdSprintPreview, wasdCenterJitterPx)
            }
        }
        // 鼠标双范围：大方形限制视角触点的活动范围，小方形决定每次回中的随机落点。
        // 放在最底层绘制，避免挡住按键、丢弃点、光标落点等其他可拖动元素。
        if (editing || showAdvancedMarkers) {
            val areaAlpha = if (!editing) 0.6f else 1f
            mouseMoveAreaCenter?.let { c ->
                MouseAreaBlob(
                    center = c,
                    half = mouseMoveAreaHalf,
                    label = "鼠标范围",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    alpha = areaAlpha,
                    enabled = editing,
                    onCommit = onMouseAreaChange,
                )
            }
            landingAreaCenter?.let { c ->
                MouseAreaBox(
                    center = c,
                    half = landingAreaHalf,
                    label = "回中范围",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    alpha = areaAlpha,
                    enabled = editing,
                    onCommit = onLandingAreaChange,
                )
            }
        }
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
                                borderColor = touchModeColor(item.touchMode ?: cancelableTouchMode),
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
                                borderColor = touchModeColor(item.touchMode ?: normalTouchMode),
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
                                    // 拖动/缩放时同步外圈几何，避免大外圈停留在旧位置。
                                    wasdRingGeometry = WasdRingGeometry(
                                        center = item.center,
                                        normalRx = abs(item.d.x - item.center.x).coerceAtLeast(1f),
                                        normalRy = abs(item.w.y - item.center.y).coerceAtLeast(1f),
                                    )
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
                                borderColor = touchModeColor(item.touchMode ?: normalTouchMode),
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
                    val radiusPx = with(density) { 18.dp.toPx() }
                    Box(
                        modifier = Modifier
                            .offset { IntOffset((pos.x - radiusPx).toInt(), (pos.y - radiusPx).toInt()) }
                            .size(36.dp)
                            .alpha(0.95f * keysLayerAlpha)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f),
                                CircleShape,
                            )
                            .border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
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
                            style = MaterialTheme.typography.labelMedium,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
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
                        .alpha(if (!editing) 1f else 0.8f)
                        .border(1.5.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(6.dp))
                        .pointerInput(point.x, point.y, editing) {
                            if (!editing) return@pointerInput
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

        // 光标落点：编辑态高级层可拖动；拖回屏幕中心附近即清除自定义坐标。
        if (editing || showAdvancedMarkers) {
            cursorSpawn?.let { point ->
                val density = LocalDensity.current
                val markerPx = with(density) { CURSOR_SPAWN_MARKER_DP.dp.toPx() }
                val snapBackPx = with(density) { CURSOR_SPAWN_SNAP_BACK_DP.dp.toPx() }
                var pos by remember(point.x, point.y) { mutableStateOf(point) }
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset((pos.x - markerPx / 2).toInt(), (pos.y - markerPx / 2).toInt())
                        }
                        .size(CURSOR_SPAWN_MARKER_DP.dp)
                        .alpha(if (!editing) 1f else 0.8f)
                        .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        .pointerInput(point.x, point.y, editing, viewport) {
                            if (!editing) return@pointerInput
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    pos += dragAmount
                                },
                                onDragEnd = {
                                    val center = Offset(viewport.width / 2f, viewport.height / 2f)
                                    if ((pos - center).getDistance() <= snapBackPx) {
                                        onResetCursorSpawn()
                                    } else {
                                        onMoveCursorSpawn(pos)
                                    }
                                },
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "光标",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        configuringWasd?.let { target ->
            var forward by remember(target.id) { mutableFloatStateOf(target.sprintForwardDistance ?: ((target.w - target.center).getDistance() * target.sprintScale).coerceIn(10f, 1000f)) }
            var side by remember(target.id) { mutableFloatStateOf(target.sprintSideDistance ?: ((target.d - target.center).getDistance() * target.sprintScale).coerceIn(10f, 1000f)) }
            LaunchedEffect(target.id) { wasdSprintPreview = forward to side }
            InlineDialog(onDismiss = {
                configuringWasd = null
                wasdSprintPreview = null
            }) {
                Column(Modifier.padding(24.dp).widthIn(min = 240.dp)) {
                    Text("Shift 疾跑距离", style = MaterialTheme.typography.titleMedium)
                    Text("W 前向：${forward.roundToInt()} px")
                    Slider(value = forward.coerceIn(10f, 1000f), onValueChange = {
                        forward = it
                        wasdSprintPreview = forward to side
                    }, valueRange = 10f..1000f)
                    Text("A / D 横向（共用）：${side.roundToInt()} px")
                    Slider(value = side.coerceIn(10f, 1000f), onValueChange = {
                        side = it
                        wasdSprintPreview = forward to side
                    }, valueRange = 10f..1000f)
                    Text("疾跑落点统一落在同一圈上，斜跑不再叠加两轴距离；只有 S 取消疾跑。保存后退出编辑生效。", style = MaterialTheme.typography.bodySmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(
                            onClick = {
                                onRemove(target.id)
                                configuringWasd = null
                                wasdSprintPreview = null
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text("删除按键") }
                        Row {
                            TextButton(onClick = {
                                configuringWasd = null
                                wasdSprintPreview = null
                            }) { Text("取消") }
                            TextButton(onClick = {
                                target.sprintForwardDistance = forward
                                target.sprintSideDistance = side
                                configuringWasd = null
                                wasdSprintPreview = null
                            }) { Text("保存") }
                        }
                    }
                }
            }
        }
        // ` 键自定义区域：编辑态显示已保存的多边形；绘制中显示草稿并接管全屏点击。
        if (editing) {
            val shootingKey = containerItems.filterIsInstance<DraggableItem.FixedKey>()
                .firstOrNull { it.type == DraggableItemType.SHOOTING_MODE }
            val draft = shootingAreaDraft
            if (shootingKey != null) {
                val points = draft ?: shootingArea
                if (points.isNotEmpty() || draft != null) {
                    ShootingAreaEditor(
                        key = shootingKey,
                        points = points,
                        drafting = draft != null,
                        onTap = onShootingAreaTap,
                        onUndo = onUndoShootingAreaPoint,
                        onClear = onClearShootingAreaDraft,
                        onDone = onFinishShootingArea,
                        onCancel = onCancelShootingArea,
                    )
                }
            }
        }
        configuringFixedKey?.let { target ->
            if (target.type == DraggableItemType.SHOOTING_MODE) {
                // 射击模式切换键只允许在 ` 与鼠标中键之间下拉二选一。
                ShootingModeKeyDialog(
                    currentKeyCode = target.keyCode,
                    hasShootingArea = shootingArea.isNotEmpty(),
                    onDrawShootingArea = {
                        onStartShootingAreaDrawing()
                        configuringFixedKey = null
                    },
                    onClearShootingArea = onClearShootingArea,
                    onConfirm = { newCode ->
                        onUpdateKeyCode(target.id, newCode, target.touchMode)
                        configuringFixedKey = null
                    },
                    onDismiss = { configuringFixedKey = null },
                    onDelete = {
                        onRemove(target.id)
                        configuringFixedKey = null
                    },
                    anchor = shootingModeAnchor,
                    mouseCenter = mouseMoveAreaCenter,
                    mouseHalf = mouseMoveAreaHalf,
                    landingCenter = landingAreaCenter,
                    landingHalf = landingAreaHalf,
                    onMouseAreaChange = onMouseAreaChange,
                    onLandingAreaChange = onLandingAreaChange,
                    aimIdleReanchorMs = aimIdleReanchorMs,
                    onAimIdleReanchorMsChange = onAimIdleReanchorMsChange,
                    aimRecenterMode = aimRecenterMode,
                    onAimRecenterModeChange = onAimRecenterModeChange,
                )
            } else {
                KeyCaptureDialog(
                    walkCustomization = target.type == DraggableItemType.WALK_TOGGLE,
                    onCalibrateWalkOff = onCalibrateWalkOff,
                    currentKeyCode = target.keyCode,
                    currentWheelRadius = target.wheelRadius,
                    onWheelRadiusChange = { target.wheelRadius = it },
                    currentWheelCenterOffset = target.wheelCenterOffset,
                    onWheelCenterOffsetChange = { target.wheelCenterOffset = it },
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
                currentWheelCenterOffset = target.wheelCenterOffset,
                onWheelCenterOffsetChange = { target.wheelCenterOffset = it },
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
                onRemoveExtraTap = { code, index -> onRemoveExtraTapPoint(code, index) },
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
                currentWheelCenterOffset = target.wheelCenterOffset,
                onWheelCenterOffsetChange = { target.wheelCenterOffset = it },
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
                onRemoveExtraTap = { code, index -> onRemoveExtraTapPoint(code, index) },
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
 * ` 键的自定义多边形区域：编辑态画出已保存/正在绘制的轮廓。
 * 绘制态全屏接管点击加点，顶部工具条负责撤销 / 清空 / 取消 / 完成。
 */
@Composable
private fun ShootingAreaEditor(
    key: DraggableItem.FixedKey,
    points: List<AreaPoint>,
    drafting: Boolean,
    onTap: (Offset) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    // 顶点存的是相对 ` 键左上角的偏移；绘制时换算成悬浮层绝对坐标。
    val absolute = points.map { Offset(key.position.x + it.x, key.position.y + it.y) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (drafting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.18f))
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { onTap(it) })
                    }
            )
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (absolute.isNotEmpty()) {
                val path = Path().apply {
                    moveTo(absolute.first().x, absolute.first().y)
                    absolute.drop(1).forEach { lineTo(it.x, it.y) }
                    if (absolute.size >= 3) close()
                }
                if (absolute.size >= 3) {
                    drawPath(path, color = color.copy(alpha = 0.18f))
                }
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
                    ),
                )
                absolute.forEach { drawCircle(color, radius = 5.dp.toPx(), center = it) }
                // 质心：按下 ` 时触点就从这里注入。
                if (absolute.size >= 3) {
                    val centroid = AreaMath.centroid(absolute)
                    drawCircle(color.copy(alpha = 0.9f), radius = 3.dp.toPx(), center = centroid)
                    val arm = 9.dp.toPx()
                    drawLine(
                        color,
                        centroid - Offset(arm, 0f),
                        centroid + Offset(arm, 0f),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                    drawLine(
                        color,
                        centroid - Offset(0f, arm),
                        centroid + Offset(0f, arm),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                }
            }
        }
        if (drafting) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "点击屏幕添加顶点（${points.size}/$MAX_SHOOTING_AREA_POINTS）",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onUndo, enabled = points.isNotEmpty()) { Text("撤销") }
                        TextButton(onClick = onClear, enabled = points.isNotEmpty()) { Text("清空") }
                        TextButton(onClick = onCancel) { Text("取消") }
                        TextButton(onClick = onDone) { Text("完成") }
                    }
                }
            }
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
        // 横屏高度有限，射击模式设置项又比较多；限高 + 可滚动，
        // 否则底部的「空闲重锚 / 瞄准回中预设」会被挤出屏幕，用户根本看不到。
        Surface(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.92f)
                .clickable(onClick = {}),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                content = content,
            )
        }
    }
}

@Composable
fun ShootingModeKeyDialog(
    currentKeyCode: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    /** ` 按钮的本地中心，以及当前双范围，用于在齿轮里按相对偏移调整。 */
    anchor: Offset? = null,
    mouseCenter: Offset? = null,
    mouseHalf: Float = 0f,
    landingCenter: Offset? = null,
    landingHalf: Float = 0f,
    onMouseAreaChange: ((Offset, Float) -> Unit)? = null,
    onLandingAreaChange: ((Offset, Float) -> Unit)? = null,
    /** 射击模式空闲重锚阈值（ms），0 = 关闭。 */
    aimIdleReanchorMs: Int = 1000,
    onAimIdleReanchorMsChange: (Int) -> Unit = {},
    /** 瞄准回中预设（经典 / 即回中 / 缓冲补发）。 */
    aimRecenterMode: AimRecenterMode = AimRecenterMode.INSTANT,
    onAimRecenterModeChange: (AimRecenterMode) -> Unit = {},
    /** ` 键是否已手绘自定义区域。 */
    hasShootingArea: Boolean = false,
    onDrawShootingArea: () -> Unit = {},
    onClearShootingArea: () -> Unit = {},
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
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text("` 的注入区域", style = MaterialTheme.typography.titleSmall)
            Text(
                text = if (hasShootingArea) "已手绘：按下 ` 时从区域质心注入"
                else "默认使用按钮自身方形区域，可手绘不规则多边形",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDrawShootingArea) {
                    Text(if (hasShootingArea) "重新绘制" else "手绘区域")
                }
                if (hasShootingArea) {
                    TextButton(onClick = onClearShootingArea) { Text("恢复默认") }
                }
            }
        }
        if (anchor != null && mouseCenter != null && onMouseAreaChange != null) {
            val bigAnchor = anchor
            val bigCurrent = mouseCenter
            val smallCurrent = landingCenter ?: mouseCenter
            var bigOx by remember(bigAnchor, bigCurrent) { mutableFloatStateOf(bigCurrent.x - bigAnchor.x) }
            var bigOy by remember(bigAnchor, bigCurrent) { mutableFloatStateOf(bigCurrent.y - bigAnchor.y) }
            var bigHalfValue by remember(mouseHalf) { mutableFloatStateOf(mouseHalf) }
            var smallOx by remember(bigAnchor, smallCurrent) { mutableFloatStateOf(smallCurrent.x - bigAnchor.x) }
            var smallOy by remember(bigAnchor, smallCurrent) { mutableFloatStateOf(smallCurrent.y - bigAnchor.y) }
            var smallHalfValue by remember(landingHalf) { mutableFloatStateOf(landingHalf) }
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("鼠标范围（相对 ` 的偏移）", style = MaterialTheme.typography.titleSmall)
                MouseRangeSlider("横向偏移", bigOx, -600f..600f) {
                    bigOx = it
                    onMouseAreaChange(bigAnchor + Offset(bigOx, bigOy), bigHalfValue)
                }
                MouseRangeSlider("纵向偏移", bigOy, -600f..600f) {
                    bigOy = it
                    onMouseAreaChange(bigAnchor + Offset(bigOx, bigOy), bigHalfValue)
                }
                MouseRangeSlider("大小（半边长）", bigHalfValue, 40f..700f) {
                    bigHalfValue = it
                    onMouseAreaChange(bigAnchor + Offset(bigOx, bigOy), bigHalfValue)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("回中随机落点（小方形）", style = MaterialTheme.typography.titleSmall)
                MouseRangeSlider("横向偏移", smallOx, -600f..600f) {
                    smallOx = it
                    onLandingAreaChange?.invoke(bigAnchor + Offset(smallOx, smallOy), smallHalfValue)
                }
                MouseRangeSlider("纵向偏移", smallOy, -600f..600f) {
                    smallOy = it
                    onLandingAreaChange?.invoke(bigAnchor + Offset(smallOx, smallOy), smallHalfValue)
                }
                MouseRangeSlider("大小（半边长）", smallHalfValue, 8f..400f) {
                    smallHalfValue = it
                    onLandingAreaChange?.invoke(bigAnchor + Offset(smallOx, smallOy), smallHalfValue)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("空闲重锚", style = MaterialTheme.typography.titleSmall)
                val idleMs = aimIdleReanchorMs.coerceIn(0, 5000)
                Text(
                    text = if (idleMs <= 0) "关闭：一直沿用原位置"
                    else "停手 ${idleMs}ms 后重新落指（轨迹出现断点）",
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = idleMs.toFloat(),
                    onValueChange = { onAimIdleReanchorMsChange(it.roundToInt()) },
                    valueRange = 0f..5000f,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("瞄准回中预设", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AimRecenterMode.entries.forEach { mode ->
                        FilterChip(
                            selected = aimRecenterMode == mode,
                            onClick = { onAimRecenterModeChange(mode) },
                            label = { Text(mode.displayName) },
                        )
                    }
                }
                Text(aimRecenterMode.description, style = MaterialTheme.typography.bodySmall)
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
    currentWheelCenterOffset: Offset = Offset.Zero,
    onWheelCenterOffsetChange: (Offset) -> Unit = {},
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
    /** 删除单个落点：(keyCode, 该键内的序号)。 */
    onRemoveExtraTap: ((Int, Int) -> Unit)? = null,
    /** 已被其他按钮占用的按键：命中时禁止确认，避免重复键互相覆盖。 */
    conflictKeys: Set<Int> = emptySet()
) {
    var captured by remember { mutableStateOf<Int?>(null) }
    var selectedMode by remember { mutableStateOf(currentTouchMode) }
    var radius by remember { mutableFloatStateOf(currentWheelRadius.coerceIn(10f, 500f)) }
    var wheelOffsetX by remember { mutableFloatStateOf(currentWheelCenterOffset.x) }
    var wheelOffsetY by remember { mutableFloatStateOf(currentWheelCenterOffset.y) }
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
                .padding(24.dp),
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
                    "同一按键在 ${com.devoid.keysync.model.EXTRA_TAP_WINDOW_MS}ms 内连续按下时，第 2 次起依次点击对应落点（最多 $MAX_EXTRA_TAP_POINTS 个），本体始终照常触发。" +
                        "开启设置里的「ESC 按下后批量点击落点」后，ESC 每次按下都会延迟依次点击它的全部落点。添加后在编辑态拖动小圆圈定位。",
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
                // 逐个删除：只清空全部的话，误点一次就得重新摆所有落点。
                val pointsOfKey = extraTapPoints
                    .filter { it.keyCode == nextCode }
                if (pointsOfKey.isNotEmpty()) {
                    Text(
                        "开启「按下后批量点击落点」后，每次按下该键都会依次点击以下位置；" +
                            "未开启时只有快速连按才会补点。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                pointsOfKey.forEachIndexed { index, point ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "落点 ${index + 1}：${point.x.roundToInt()}, ${point.y.roundToInt()}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(
                            enabled = onRemoveExtraTap != null,
                            onClick = { onRemoveExtraTap?.invoke(nextCode, index) }
                        ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }

            if (selectedMode == TouchMode.WHEEL) {
                Text("鼠标轮盘半径：${radius.roundToInt()} px")
                Slider(value = radius, onValueChange = { radius = it }, valueRange = 10f..500f)
                Text("旋转中心偏移：X ${wheelOffsetX.roundToInt()} / Y ${wheelOffsetY.roundToInt()} px")
                Slider(
                    value = wheelOffsetX,
                    onValueChange = { wheelOffsetX = it },
                    valueRange = -600f..600f,
                )
                Slider(
                    value = wheelOffsetY,
                    onValueChange = { wheelOffsetY = it },
                    valueRange = -600f..600f,
                )
                Text("按住此键打开轮盘，移动鼠标选择，松开确认。圆心默认是此按钮的位置，可用偏移移到屏幕中间；默认半径 50px。", style = MaterialTheme.typography.bodySmall)
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
                        onClick = { onWheelRadiusChange(radius); onWheelCenterOffsetChange(Offset(wheelOffsetX, wheelOffsetY)); onConfirm(captured ?: currentKeyCode, if (walkCustomization) TouchMode.TAP else selectedMode) }
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
            detectDragGestures { change, dragAmount ->
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
                    // 齿轮放在按钮内部右上角：按钮已经被限制在屏幕内，
                    // 这样即使按钮紧贴屏幕边缘，齿轮也不会跑到屏幕外点不到。
                    .offset(x = (-2).dp, y = 2.dp)
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
    onToggleKeysVisible: () -> Unit,
    onToggleStatusBarVisible: () -> Unit = {},
    onToggleEditMode: () -> Unit,
    onCancelEdits: () -> Unit = {},
    onAddItem: (DraggableItemType) -> Unit,
    onAddDiscardPoint: () -> Unit = {},
    onAddEscTapPoint: () -> Unit = {},
) {
    var itemsMenuVisible by remember { mutableStateOf(false) }
    // 点「完成」先弹确认：误触拖动后可以直接回退到进入编辑前的布局。
    var exitConfirmVisible by remember { mutableStateOf(false) }
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
            // 已合并为单一编辑层：按键和各种标记同时可拖，不再做层级切换。
            TextButton(onClick = { exitConfirmVisible = true }) {
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
                }, onAddEscTapPoint = {
                    onAddEscTapPoint()
                    itemsMenuVisible = false
                })
            }
        }
        // 完成前的确认：误触拖动后不必再手动拖回去，直接回退到进入编辑前的布局。
        if (exitConfirmVisible) {
            InlineDialog(onDismiss = { exitConfirmVisible = false }) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "保存本次布局修改？",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        modifier = Modifier.padding(top = 6.dp),
                        text = "如果刚才有误触拖动，可以选「回退」，恢复到进入编辑前的位置。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = {
                            exitConfirmVisible = false
                            onCancelEdits()
                        }) {
                            Text("回退")
                        }
                        TextButton(onClick = {
                            exitConfirmVisible = false
                            onToggleEditMode()
                        }) {
                            Text("确认")
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
fun MenuItems(
    modifier: Modifier = Modifier,
    onItemClick: (DraggableItemType) -> Unit,
    onAddDiscardPoint: () -> Unit = {},
    onAddEscTapPoint: () -> Unit = {}
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
                    MenuCell(itemModifier, symbol("E"), "ESC 落点") { onAddEscTapPoint() }
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
private fun MouseRangeSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Text(
        text = "$label：${value.roundToInt()} px",
        style = MaterialTheme.typography.bodySmall,
    )
    Slider(
        value = value.coerceIn(range),
        onValueChange = onChange,
        valueRange = range,
    )
}

/**
 * 高级层的鼠标活动范围：按手绘不规则轮廓绘制，拖框体移动中心，拖右下角改变半边长。
 * 与正方形相比更接近真人手部扫过的范围。
 */
@Composable
private fun MouseAreaBlob(
    center: Offset,
    half: Float,
    label: String,
    color: Color,
    alpha: Float,
    enabled: Boolean,
    onCommit: (Offset, Float) -> Unit,
) {
    if (half <= 0f) return
    val density = LocalDensity.current
    var pos by remember(center) { mutableStateOf(center) }
    var size by remember(half) { mutableFloatStateOf(half) }
    Box(
        modifier = Modifier
            .offset { IntOffset((pos.x - size).roundToInt(), (pos.y - size).roundToInt()) }
            .size(with(density) { (size * 2f).toDp() })
            .alpha(alpha)
            .drawBehind {
                val shape = EventHandler.MOUSE_AREA_SHAPE
                val count = shape.size / 2
                val stroke = 1.6.dp.toPx()
                for (i in 0 until count) {
                    val j = (i + 1) % count
                    drawLine(
                        color = color,
                        start = Offset(
                            (shape[i * 2] + 1f) / 2f * this.size.width,
                            (shape[i * 2 + 1] + 1f) / 2f * this.size.height,
                        ),
                        end = Offset(
                            (shape[j * 2] + 1f) / 2f * this.size.width,
                            (shape[j * 2 + 1] + 1f) / 2f * this.size.height,
                        ),
                        strokeWidth = stroke,
                    )
                }
            }
            .pointerInput(pos, size, enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDrag = { change, drag ->
                        change.consume()
                        pos += drag
                    },
                    onDragEnd = { onCommit(pos, size) },
                )
            }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = color,
            modifier = Modifier.padding(2.dp),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(18.dp)
                .background(color.copy(alpha = 0.35f), RoundedCornerShape(3.dp))
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDrag = { change, drag ->
                            change.consume()
                            size = (size + max(drag.x, drag.y)).coerceIn(8f, 4000f)
                        },
                        onDragEnd = { onCommit(pos, size) },
                    )
                }
        )
    }
}

/** 光标落点标记尺寸；拖到屏幕中心这么近就视为恢复默认。 */
private const val CURSOR_SPAWN_MARKER_DP = 56
private const val CURSOR_SPAWN_SNAP_BACK_DP = 40

/**
 * 高级层的鼠标范围方形：拖框体移动中心，拖右下角改变半边长。
 * 只在 [enabled] 时接收拖动，避免在按键层挡住普通按钮。
 */
@Composable
private fun MouseAreaBox(
    center: Offset,
    half: Float,
    label: String,
    color: Color,
    alpha: Float,
    enabled: Boolean,
    onCommit: (Offset, Float) -> Unit,
) {
    if (half <= 0f) return
    val density = LocalDensity.current
    var pos by remember(center) { mutableStateOf(center) }
    var size by remember(half) { mutableFloatStateOf(half) }
    Box(
        modifier = Modifier
            .offset { IntOffset((pos.x - size).roundToInt(), (pos.y - size).roundToInt()) }
            .size(with(density) { (size * 2f).toDp() })
            .alpha(alpha)
            .background(color.copy(alpha = 0.06f), RoundedCornerShape(4.dp))
            .border(1.5.dp, color, RoundedCornerShape(4.dp))
            .pointerInput(pos, size, enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDrag = { change, drag ->
                        change.consume()
                        pos += drag
                    },
                    onDragEnd = { onCommit(pos, size) },
                )
            }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = color,
            modifier = Modifier.padding(2.dp),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(18.dp)
                .background(color.copy(alpha = 0.35f), RoundedCornerShape(3.dp))
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDrag = { change, drag ->
                            change.consume()
                            size = (size + max(drag.x, drag.y)).coerceIn(8f, 4000f)
                        },
                        onDragEnd = { onCommit(pos, size) },
                    )
                }
        )
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


private data class WasdRingGeometry(
    val center: Offset,
    val normalRx: Float,
    val normalRy: Float,
)

@Composable
private fun WasdRadiusRings(
    item: DraggableItem.WASDGroup,
    geometry: WasdRingGeometry,
    preview: Pair<Float, Float>? = null,
    centerJitterPx: Int = 8,
) {
    val center = geometry.center
    val normalRx = geometry.normalRx.coerceAtLeast(1f)
    val normalRy = geometry.normalRy.coerceAtLeast(1f)
    val sprintRx = (preview?.second ?: item.sprintSideDistance ?: (normalRx * item.sprintScale))
        .coerceAtLeast(1f)
    val sprintRy = (preview?.first ?: item.sprintForwardDistance ?: (normalRy * item.sprintScale))
        .coerceAtLeast(1f)
    val normalColor = Color.White.copy(alpha = 0.35f)
    val sprintColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                // 回中随机范围：松手后不会每次都收回同一个像素。
                val jitter = centerJitterPx.coerceIn(0, 40).toFloat()
                if (jitter > 0f) {
                    drawCircle(
                        color = sprintColor.copy(alpha = 0.12f),
                        radius = jitter,
                        center = center,
                    )
                    drawCircle(
                        color = sprintColor.copy(alpha = 0.55f),
                        radius = jitter,
                        center = center,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
                drawOval(
                    color = normalColor,
                    topLeft = Offset(center.x - normalRx, center.y - normalRy),
                    size = Size(normalRx * 2, normalRy * 2),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = dash),
                )
                drawOval(
                    color = sprintColor,
                    topLeft = Offset(center.x - sprintRx, center.y - sprintRy),
                    size = Size(sprintRx * 2, sprintRy * 2),
                    style = Stroke(width = 2.dp.toPx(), pathEffect = dash),
                )
            }
    )
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