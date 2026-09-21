package com.devoid.keysync.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.InputDevice
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.devoid.keysync.MainActivity
import com.devoid.keysync.R
import com.devoid.keysync.model.DraggableItem
import com.devoid.keysync.util.keyCodeToString
import com.devoid.keysync.model.DraggableItemType
import com.devoid.keysync.model.TouchMode
import com.devoid.keysync.ui.overlay.FloatingBubble
import com.devoid.keysync.ui.overlay.ItemsContainer
import com.devoid.keysync.ui.overlay.MenuItems
import com.devoid.keysync.ui.overlay.ServiceLifecycleOwner
import com.devoid.keysync.ui.overlay.SettingsLayout
import com.devoid.keysync.ui.theme.KeySyncTheme
import com.devoid.keysync.util.TouchToMouseTranslator
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt


@AndroidEntryPoint
class FloatingBubbleService : Service() {
    private val TAG = "FloatingBubbleService"
    private lateinit var floatingBubbleLP: WindowManager.LayoutParams

    @Inject
    lateinit var stateManager: Lazy<FloatingWindowStateManager>

    companion object {
        val INTENT_EXTRA_PACAKAGE = "launchedPackageName"
        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()
    }

    private val lifecycleOwner = ServiceLifecycleOwner()

    private val scope = CoroutineScope(Dispatchers.Main)
    private var containerView: ComposeView? = null
    private var floatingBubbleView: ComposeView? = null
    private var overlayInitialized = false


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        _isRunning.value = true
        val channel = NotificationChannel(
            "channel1",
            "KeySync Overlay Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val launchedPackageName = intent?.getStringExtra(INTENT_EXTRA_PACAKAGE)
        launchedPackageName?.let {
            stateManager.get().loadButtonsConfig(it)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(this, "channel1").apply {
            setSmallIcon(R.drawable.ic_launcher_foreground)
            setContentTitle(ContextCompat.getString(baseContext, R.string.app_name))
            setContentText(
                "${
                    ContextCompat.getString(
                        baseContext,
                        R.string.app_name
                    )
                } Overlay service is running"
            )
        }.build()
        startForeground(
            1,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )

        if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.INITIALIZED) {
            lifecycleOwner.performRestore(null)
        }
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        if (!overlayInitialized) {
            overlayInitialized = true
            init()
        }
        return START_NOT_STICKY
    }

    private fun init() {

        floatingBubbleLP = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        floatingBubbleLP.gravity = Gravity.START or Gravity.TOP

        val itemsContainerLP = WindowManager.LayoutParams()
        itemsContainerLP.copyFrom(floatingBubbleLP)

        val sm = stateManager.get()
        val touchTranslator = TouchToMouseTranslator(
            onMouseEvent = { event -> sm.onMouseEvent(event) },
            isInIgnoreZone = { x, y -> isOnKeySyncButton(x, y, sm) },
        )

        containerView =
            getItemsContainerView(
                onRemove = { id ->
                    sm.removeItem(id)
                },
                onUpdateKeyCode = { id, newKeyCode, touchMode ->
                    sm.updateFixedKeyCode(id, newKeyCode, touchMode)
                },
                onUpdateVariableKeyCode = { id, newKeyCode, touchMode ->
                    sm.updateVariableKeyCode(id, newKeyCode, touchMode)
                },
                touchTranslator = touchTranslator,
            )

        floatingBubbleView = getFloatingBubbleView(
            onCLick = {
                stateManager.get().onFloatingBubbleClick()
            },
            onAddItem = { itemType ->
                Log.i(TAG, "init: $itemType")
                stateManager.get().addNewItem(itemType)
            }
        )
        scope.launch {
            stateManager.get().isBubbleExpanded.collect { expanded ->
                itemsContainerLP.apply {
                    width = WindowManager.LayoutParams.MATCH_PARENT
                    height = WindowManager.LayoutParams.MATCH_PARENT
                }
                if (expanded) {
                    itemsContainerLP.flags =
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    stateManager.get().windowManager.updateViewLayout(
                        containerView,
                        itemsContainerLP
                    )
                    // 展开（编辑态）：先释放指针捕获（编辑态要能触摸拖动按钮），再等
                    // updateViewLayout 生效后重新请求键盘焦点。顺序不能反——先
                    // requestFocus 再 releasePointerCapture 会把刚请求到的焦点冲掉，
                    // 导致 View 层 setOnKeyListener 收不到按键（按钮绑定一直是「+」）。
                    // requestFocus 用 post 延后到 layout 更新之后，再补一次兜底。
                    containerView?.releasePointerCapture()
                    stateManager.get().clearActivePointers()
                    containerView?.post { containerView?.requestFocus() }
                    containerView?.postDelayed({ containerView?.requestFocus() }, 150)
                } else {
                    itemsContainerLP.flags =
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    stateManager.get().windowManager.updateViewLayout(
                        containerView,
                        itemsContainerLP
                    )
                    containerView?.requestFocus()
                    containerView?.requestPointerCapture()
                }
            }
        }
        stateManager.get().windowManager.addView(containerView, itemsContainerLP)
        floatingBubbleLP.y = 200
        stateManager.get().windowManager.addView(floatingBubbleView, floatingBubbleLP)
        containerView?.postDelayed({
            containerView?.requestFocus()
            if (!stateManager.get().isBubbleExpanded.value) {
                containerView?.requestPointerCapture()
            }
        }, 1000)
    }


    private fun getFloatingBubbleView(
        onCLick: () -> Unit,
        onAddItem: (DraggableItemType) -> Unit
    ): ComposeView {
        val composeView = ComposeView(this)
        composeView.setContent {
            FloatingBubbleLayout(
                onAddItem = onAddItem,
                onBubbleClick = onCLick
            )
        }
        composeView.setViewTreeLifecycleOwner(lifecycleOwner)
        composeView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        return composeView
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun getItemsContainerView(
        onRemove: (Int) -> Unit,
        onUpdateKeyCode: (Int, Int, TouchMode?) -> Unit,
        onUpdateVariableKeyCode: (Int, Int, TouchMode?) -> Unit,
        touchTranslator: TouchToMouseTranslator
    ): ComposeView {
        val composeView = ComposeView(this)
        composeView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        composeView.setContent {
            KeySyncTheme {
                val draggableItems by stateManager.get().containerItems.collectAsState()
                val itemsContainerOpacity by stateManager.get().overlayOpacity.collectAsState()
                val isBubbleExpanded by stateManager.get().isBubbleExpanded.collectAsState()
                val isShootingMode by stateManager.get().isShootingMode.collectAsState()
                val pointerOffset by stateManager.get().pointerOffset.collectAsState()
                val keysConfig by stateManager.get().keysConfig.collectAsState()
                val keysVisible by stateManager.get().keysVisible.collectAsState()
                val pendingBindId by stateManager.get().pendingVariableKeyBind.collectAsState()
                val pressedKeys by stateManager.get().pressedKeys.collectAsState()
                val walkEnabled by stateManager.get().walkEnabled.collectAsState()
                val lastInput by stateManager.get().lastInputLabel.collectAsState()
                val profiles by stateManager.get().profiles.collectAsState()
                val activeId by stateManager.get().activeProfileId.collectAsState()
                Box {
                    if (!isBubbleExpanded && !isShootingMode) {
                        Image(///mouse pointer
                            modifier = Modifier.offset {
                                pointerOffset.let {
                                    IntOffset(
                                        it.x.toInt(),
                                        it.y.toInt()
                                    )
                                }
                            },
                            painter = painterResource(R.drawable.mouse),
                            contentDescription = "mouse pointer"
                        )
                    }
                    if (!isBubbleExpanded && itemsContainerOpacity == 0f)
                        return@Box//do not compose if user set overlay opacity to 0
                    // 一键隐藏按键：keysVisible 为 false 时不渲染按键容器。
                    if (keysVisible) {
                        ItemsContainer(
                            Modifier.alpha(if (isBubbleExpanded) 1f else itemsContainerOpacity),
                            appConfig = keysConfig,
                            editing = isBubbleExpanded,
                            walkEnabled = walkEnabled,
                            onCalibrateWalkOff = { stateManager.get().calibrateWalkOff() },
                            onItemMeasured = { stateManager.get().updateMeasuredPosition(it) },
                            containerItems = draggableItems,
                            onRemove = onRemove,
                            onUpdateKeyCode = onUpdateKeyCode,
                            onUpdateVariableKeyCode = onUpdateVariableKeyCode,
                            pendingBindId = pendingBindId,
                            onBindConsumed = { stateManager.get().consumePendingVariableKeyBind() },
                            onKeyCaptureChanged = { listener ->
                                stateManager.get().keyCaptureListener = listener
                            },
                            pressedKeys = pressedKeys,
                            onRequestFocus = { composeView.post { composeView.requestFocus() } },
                        )
                        if (!isBubbleExpanded) {
                            val toggle = draggableItems.filterIsInstance<DraggableItem.FixedKey>()
                                .firstOrNull { it.type == DraggableItemType.SHOOTING_MODE }
                            val mode = if (isShootingMode) "射击模式" else if (toggle == null)
                                "光标模式 · 请添加视角按钮" else
                                "光标模式 · 按 ${toggle.keyCode.keyCodeToString()} 开启射击"
                            val activeName = profiles.firstOrNull { it.id == activeId }?.name.orEmpty()
                            Text("$activeName · $mode · $lastInput", color = Color.White, fontSize = 12.sp,
                                modifier = Modifier.align(Alignment.TopCenter)
                                    .background(Color.Black.copy(alpha = 0.65f)))
                        }
                    }
                }
            }
        }
        composeView.setViewTreeLifecycleOwner(lifecycleOwner)
        composeView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        composeView.isFocusable = true
        composeView.isClickable = true
        composeView.isFocusableInTouchMode = true
        composeView.post {
            composeView.setOnCapturedPointerListener { _, motionEvent ->
                stateManager.get().onMouseEvent(motionEvent)
            }
        }
        composeView.setOnGenericMotionListener { _, event ->
            if (event.isFromSource(InputDevice.SOURCE_MOUSE) ||
                event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)) {
                stateManager.get().onMouseEvent(event)
            } else false
        }
        composeView.setOnKeyListener { _, _, event ->
            stateManager.get().onKeyEvent(event)
        }
        // Catch touch events injected by screen-mirror / keymapper apps
        // (e.g. 熊猫映射 / Scrcpy / GameKeyboard) so a PC mouse forwarded
        // through them can still drive the game. Only active when the user
        // has opted in via Settings -> "Screen Mirror Compat Mode".
        composeView.setOnTouchListener { _, motionEvent ->
            // Editing must receive the entire gesture, even when a drag leaves
            // the button hit area or screen-mirror compatibility is enabled.
            if (stateManager.get().isBubbleExpanded.value) return@setOnTouchListener false
            if (motionEvent.isFromSource(InputDevice.SOURCE_MOUSE) ||
                motionEvent.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)) {
                return@setOnTouchListener stateManager.get().onMouseEvent(motionEvent)
            }
            val enabled = runCatching {
                stateManager.get().keysConfig.value.screenMirrorCompatMode
            }.getOrDefault(false)
            if (enabled) {
                touchTranslator.handle(motionEvent)
            } else {
                false
            }
        }
        return composeView
    }

    /**
     * Returns true when the touch point (x,y) in container-window coordinates
     * lands on a KeySync-managed draggable item. We must not translate these
     * into mouse events, otherwise tapping a KeySync button with the PC mouse
     * would simultaneously fire the button's own click handler *and* a
     * mouse-left click on the game.
     */
    private fun isOnKeySyncButton(
        x: Float,
        y: Float,
        sm: FloatingWindowStateManager,
    ): Boolean {
        val items = sm.containerItems.value
        return items.any { item ->
            // Each DraggableItem subtype may carry size differently. WASDGroup
            // doesn't have a single size (its footprint is a square covering
            // the four W/A/S/D buttons), so we treat it as a 100dp box around
            // the position origin.
            val radius = when (item) {
                is com.devoid.keysync.model.DraggableItem.VariableKey -> item.size / 2f
                is com.devoid.keysync.model.DraggableItem.FixedKey -> item.size / 2f
                is com.devoid.keysync.model.DraggableItem.CancelableKey -> item.size / 2f
                is com.devoid.keysync.model.DraggableItem.WASDGroup -> 100f
            }
            val cx = item.position.x + radius
            val cy = item.position.y + radius
            val dx = x - cx
            val dy = y - cy
            dx * dx + dy * dy <= radius * radius
        }
    }


    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        _isRunning.value = false
        overlayInitialized = false
        removeAllViews()
        stateManager.get().onDestroy()
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        scope.cancel()
        super.onDestroy()
    }

    private fun removeAllViews() {
        containerView?.let {
            stateManager.get().windowManager.removeViewImmediate(it)
        }
        floatingBubbleView?.let {
            stateManager.get().windowManager.removeViewImmediate(it)
        }
    }

    @Composable
    private fun FloatingBubbleLayout(
        modifier: Modifier = Modifier,
        onAddItem: (DraggableItemType) -> Unit,
        onBubbleClick: () -> Unit
    ) {
        var opacity by remember { mutableFloatStateOf(0.5f) }
        val isExpanded by stateManager.get().isBubbleExpanded.collectAsState()
        val keysVisible by stateManager.get().keysVisible.collectAsState()
        var itemsMenuVisible by remember { mutableStateOf(false) }
        var settingsLayoutVisible by remember { mutableStateOf(false) }
        LaunchedEffect(isExpanded) {
            opacity = if (isExpanded) 1f else 0.5f
            itemsMenuVisible = isExpanded && itemsMenuVisible
            settingsLayoutVisible = isExpanded && settingsLayoutVisible
        }
        Box(
            modifier = modifier
                .alpha(opacity)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            opacity = 1.0f
                        }, onDragEnd = {
                            if (!isExpanded) opacity = 0.5f
                            if (floatingBubbleLP.x < baseContext.resources.displayMetrics.widthPixels / 2) {
                                floatingBubbleLP.x = 0
                                stateManager.get().windowManager.updateViewLayout(
                                    floatingBubbleView,
                                    floatingBubbleLP
                                )
                            } else {
                                floatingBubbleLP.x =
                                    baseContext.resources.displayMetrics.widthPixels - (floatingBubbleView?.width
                                        ?: 0)
                                stateManager.get().windowManager.updateViewLayout(
                                    floatingBubbleView,
                                    floatingBubbleLP
                                )
                            }
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        floatingBubbleLP.x += dragAmount.x.roundToInt()
                        floatingBubbleLP.y += dragAmount.y.roundToInt()
                        stateManager.get().windowManager.updateViewLayout(
                            floatingBubbleView,
                            floatingBubbleLP
                        )
                    }
                }
        ) {
            ConstraintLayout {
                val (background, bubbleLayout, otherLayout, bubble) = createRefs()
                Box(
                    modifier = Modifier
                        .constrainAs(background) {
                            start.linkTo(bubble.start)
                            top.linkTo(bubble.top)
                            end.linkTo(bubble.end)
                            bottom.linkTo(bubbleLayout.bottom)
                            height = Dimension.fillToConstraints
                            width = Dimension.fillToConstraints
                        }
                        .background(color = MaterialTheme.colorScheme.surface, CircleShape)
                )
                FloatingBubble(
                    modifier = Modifier
                        .size(50.dp)
                        .constrainAs(bubble) { },
                    expanded = isExpanded, onClick = onBubbleClick
                )
                AnimatedVisibility(
                    visible = isExpanded,
                    Modifier.constrainAs(bubbleLayout) {
                        top.linkTo(bubble.bottom)
                        end.linkTo(bubble.end)
                    }) {
                    Column {
                        IconButton(onClick = {
                            itemsMenuVisible = !itemsMenuVisible
                            settingsLayoutVisible = false
                        }) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = "Add Item"
                            )
                        }

                        IconButton(onClick = {
                            settingsLayoutVisible = !settingsLayoutVisible
                            itemsMenuVisible = false
                        }) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        IconButton(onClick = {
                            stateManager.get().toggleKeysVisible()
                        }) {
                            Text(
                                text = if (keysVisible) "隐" else "显",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
                AnimatedVisibility(
                    visible = (itemsMenuVisible),
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .constrainAs(otherLayout) {
                            start.linkTo(bubble.end)
                            top.linkTo(parent.top)
                            bottom.linkTo(parent.bottom)
                        }) {
                    MenuItems(onItemClick = {
                        onAddItem(it)
                        itemsMenuVisible = false
                    })
                }
                AnimatedVisibility(
                    visible = (settingsLayoutVisible),
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .constrainAs(otherLayout) {
                            start.linkTo(bubble.end)
                            top.linkTo(parent.top)
                            bottom.linkTo(parent.bottom)
                            end.linkTo(parent.end)
                            width = Dimension.preferredWrapContent
                        }) {
                    val pointerSensitivity by stateManager.get().pointerSensitivity.collectAsState()
                    val overlayOpacity by stateManager.get().overlayOpacity.collectAsState()
                    val appConfig by stateManager.get().keysConfig.collectAsState()
                    val profiles by stateManager.get().profiles.collectAsState()
                    val activeId by stateManager.get().activeProfileId.collectAsState()
                    SettingsLayout(
                        profiles = profiles,
                        activeProfileId = activeId,
                        onSwitchProfile = { stateManager.get().switchProfile(it) },
                        onSetupTwoProfiles = { stateManager.get().setupTwoProfiles() },
                        buttonScale = appConfig.buttonScale,
                        onButtonScaleChange = { stateManager.get().saveAppConfig(appConfig.copy(buttonScale = it)) },
                        pointerSensitivity = pointerSensitivity,
                        overlayOpacity = overlayOpacity,
                        onOpacityChange = { stateManager.get().overlayOpacity.value = it },
                        onPointerSensChange = { stateManager.get().savePointerSensitivity(it) },
                        onAdvancedSettingsClick = {
                            val intent = Intent(baseContext, MainActivity::class.java).apply {
                                flags =
                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                                action = MainActivity.INTENT_ACTION_SETTINGS
                            }
                            baseContext.startActivity(intent)
                            stateManager.get().onFloatingBubbleClick()
                        },
                        onCloseOverlayClick = {
                            stopSelf()
                        })
                }
            }
        }
    }
}


@Preview
@Composable
fun PreviewFloatingBubble() {
    Box(
        modifier = Modifier
    ) {
        ConstraintLayout {
            val (background, bubbleLayout, otherLayout, bubble) = createRefs()
            Box(
                modifier = Modifier
                    .constrainAs(background) {
                        start.linkTo(bubble.start)
                        top.linkTo(bubble.top)
                        end.linkTo(bubble.end)
                        bottom.linkTo(bubbleLayout.bottom)
                        height = Dimension.fillToConstraints
                        width = Dimension.fillToConstraints
                    }
                    .background(color = MaterialTheme.colorScheme.surface, CircleShape)
            )
            FloatingBubble(
                modifier = Modifier
                    .size(50.dp)
                    .constrainAs(bubble) { },
                expanded = true, onClick = { })
            AnimatedVisibility(
                visible = true,
                Modifier.constrainAs(bubbleLayout) {
                    top.linkTo(bubble.bottom)
                    end.linkTo(bubble.end)
                }) {
                Column {
                    IconButton(onClick = {
                    }) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "Add Item"
                        )
                    }

                    IconButton(onClick = {
                    }) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = (false),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .padding(start = 4.dp)
                    .constrainAs(otherLayout) {
                        start.linkTo(bubble.end)
                        top.linkTo(parent.top)
                        bottom.linkTo(parent.bottom)
                    }) {
                MenuItems(onItemClick = { })
            }
            AnimatedVisibility(
                visible = (true),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .padding(start = 4.dp)
                    .constrainAs(otherLayout) {
                        start.linkTo(bubble.end)
                        top.linkTo(parent.top)
                        bottom.linkTo(parent.bottom)
                        end.linkTo(parent.end)
                        width = Dimension.preferredWrapContent
                    }) {
                SettingsLayout(
                    onAdvancedSettingsClick = {},
                    onPointerSensChange = {},
                    onOpacityChange = { },
                    onCloseOverlayClick = {}
                )
            }
        }
    }
}