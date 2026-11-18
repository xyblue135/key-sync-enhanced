package com.devoid.keysync.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.ContentValues
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import android.view.MotionEvent
import android.view.InputDevice
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.devoid.keysync.MainActivity
import com.devoid.keysync.R
import com.devoid.keysync.domain.TrajectoryRecorder
import com.devoid.keysync.domain.TrajectoryRecording
import com.devoid.keysync.model.DraggableItem
import com.devoid.keysync.util.keyCodeToString
import com.devoid.keysync.model.DraggableItemType
import com.devoid.keysync.model.TouchMode
import com.devoid.keysync.ui.overlay.MacroPointsOverlay
import com.devoid.keysync.ui.overlay.EditToolbar
import com.devoid.keysync.ui.overlay.ItemsContainer
import com.devoid.keysync.ui.overlay.ServiceLifecycleOwner
import com.devoid.keysync.ui.theme.KeySyncTheme
import com.devoid.keysync.util.TouchToMouseTranslator
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt


@AndroidEntryPoint
class FloatingBubbleService : Service() {
    private val TAG = "FloatingBubbleService"

    @Inject
    lateinit var stateManager: Lazy<FloatingWindowStateManager>

    companion object {
        val INTENT_EXTRA_PACAKAGE = "launchedPackageName"

        /** 前台通知 id 与频道。频道沿用旧的 "channel1"，只更新对外显示名。 */
        private const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "channel1"

        /** 通知栏 action：全部回投到本 Service，由 onStartCommand 分发。 */
        private const val ACTION_TOGGLE_EDIT = "com.devoid.keysync.action.TOGGLE_EDIT"
        private const val ACTION_TOGGLE_KEYS = "com.devoid.keysync.action.TOGGLE_KEYS"
        private const val ACTION_TOGGLE_PAUSE = "com.devoid.keysync.action.TOGGLE_PAUSE"
        private const val ACTION_TOGGLE_TRAJECTORY = "com.devoid.keysync.action.TOGGLE_TRAJECTORY"

        /** 轨迹视频导出通知：与悬浮窗常驻通知分开，避免互相覆盖。 */
        private const val TRAJECTORY_NOTIFICATION_ID = 2
        private const val TRAJECTORY_CHANNEL_ID = "trajectory_export"

        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()
    }

    private val lifecycleOwner = ServiceLifecycleOwner()

    // SupervisorJob: the notification-label observer and the edit-mode observer
    // are siblings. Without it one failing child cancels the other and the
    // notification actions silently stop reflecting the current state.
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var containerView: ComposeView? = null
    private var overlayInitialized = false
    // 通知刷新监听只允许挂一次：每个通知 action 都会重入 onStartCommand。
    private var notificationObserverStarted = false
    private var trajectoryAutoStopJob: Job? = null
    private var trajectoryRenderJob: Job? = null


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 屏幕上的气泡已删除，编辑态 / 按键显隐改由通知栏 action 驱动。
        // 先落状态再建通知，让这一次 startForeground 就带上正确的 action 标签。
        when (intent?.action) {
            ACTION_TOGGLE_EDIT -> stateManager.get().toggleEditMode()
            ACTION_TOGGLE_KEYS -> stateManager.get().toggleKeysVisible()
            ACTION_TOGGLE_PAUSE -> stateManager.get().toggleMappingPaused()
            ACTION_TOGGLE_TRAJECTORY -> toggleTrajectoryRecording()
        }

        _isRunning.value = true
        val launchedPackageName = intent?.getStringExtra(INTENT_EXTRA_PACAKAGE)
        launchedPackageName?.let {
            stateManager.get().loadButtonsConfig(it)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(notificationChannel())
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            if (android.os.Build.VERSION.SDK_INT >= 34)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        observeNotificationState()

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

    /* ---------- 通知栏（唯一的常驻控制面） ---------- */

    private fun notificationChannel() = NotificationChannel(
        CHANNEL_ID,
        getString(R.string.notification_channel_overlay),
        NotificationManager.IMPORTANCE_LOW
    ).apply {
        description = getString(R.string.notification_channel_overlay_desc)
        setShowBadge(false)
    }

    private fun buildNotification(): Notification {
        val sm = stateManager.get()
        val editLabel = getString(
            if (sm.isEditMode.value) R.string.notification_action_exit_edit
            else R.string.notification_action_edit
        )
        val keysLabel = getString(
            if (sm.keysVisible.value) R.string.notification_action_hide_keys
            else R.string.notification_action_show_keys
        )
        val pauseLabel = getString(
            if (sm.mappingPaused.value) R.string.notification_action_resume
            else R.string.notification_action_pause
        )
        return NotificationCompat.Builder(this, CHANNEL_ID).apply {
            setSmallIcon(R.drawable.ic_launcher_foreground)
            setContentTitle(getString(R.string.app_name))
            setContentText(
                getString(
                    if (sm.mappingPaused.value) R.string.notification_text_paused
                    else R.string.notification_text_overlay_running
                )
            )
            // 折叠态只放得下 3 个 action，按使用频率排序：暂停 / 隐藏按键 / 录制轨迹。
            // 添加按键已移到编辑态工具条的「＋」；编辑布局与关闭放到展开态，
            // 其中「关闭」误触代价最大，故意藏一层。总共 5 个，避免第 6 个被系统吞掉。
            setStyle(NotificationCompat.BigTextStyle())
            setContentIntent(settingsPendingIntent())
            setOngoing(true)
            setSilent(true)
            setOnlyAlertOnce(true)
            setPriority(NotificationCompat.PRIORITY_LOW)
            addAction(0, pauseLabel, servicePendingIntent(ACTION_TOGGLE_PAUSE, 106))
            addAction(0, keysLabel, servicePendingIntent(ACTION_TOGGLE_KEYS, 102))
            addAction(
                0,
                getString(
                    if (TrajectoryRecorder.isRecording) R.string.notification_action_stop_trajectory
                    else R.string.notification_action_start_trajectory
                ),
                servicePendingIntent(ACTION_TOGGLE_TRAJECTORY, 107)
            )
            addAction(0, editLabel, servicePendingIntent(ACTION_TOGGLE_EDIT, 101))
        }.build()
    }

    /**
     * 通知栏「录制轨迹 / 停止并生成」。
     *
     * 录制只记 WASD 摇杆向量与射击模式鼠标增量；停止后在后台渲染 720p 视频，
     * 完成时用单独的通知提示，点击可直接打开。
     */
    private fun toggleTrajectoryRecording() {
        if (TrajectoryRecorder.isRecording) {
            finishTrajectoryRecording()
            return
        }
        if (!TrajectoryRecorder.start()) return
        Toast.makeText(this, R.string.trajectory_started, Toast.LENGTH_SHORT).show()
        refreshOverlayNotification()
        trajectoryAutoStopJob?.cancel()
        trajectoryAutoStopJob = scope.launch {
            delay(TrajectoryRecorder.MAX_DURATION_MS)
            if (TrajectoryRecorder.isRecording) finishTrajectoryRecording()
        }
    }

    private fun finishTrajectoryRecording() {
        trajectoryAutoStopJob?.cancel()
        trajectoryAutoStopJob = null
        val recording = TrajectoryRecorder.stop()
        refreshOverlayNotification()
        if (recording == null || recording.isEmpty) {
            Toast.makeText(this, R.string.trajectory_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val sm = stateManager.get()
        val presetName = sm.profiles.value
            .firstOrNull { it.id == sm.activeProfileId.value }
            ?.name
            .orEmpty()
        renderTrajectory(recording, presetName)
    }

    @SuppressLint("MissingPermission")
    private fun refreshOverlayNotification() {
        if (!overlayInitialized) return
        if (!canPostNotifications()) return
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun renderTrajectory(recording: TrajectoryRecording, presetName: String) {
        if (trajectoryRenderJob?.isActive == true) return
        createTrajectoryChannel()
        showTrajectoryProgress(0)
        trajectoryRenderJob = scope.launch {
            val tempDir = File(cacheDir, "trajectory")
            if (!tempDir.exists() && !tempDir.mkdirs()) {
                showTrajectoryResult(null, false, false)
                return@launch
            }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val displayName = "trajectory-$stamp.mp4"
            val temp = File(tempDir, displayName)
            val ok = TrajectoryVideoRenderer.render(recording, presetName, temp) { progress ->
                showTrajectoryProgress(progress)
            }
            if (!ok) {
                temp.delete()
                showTrajectoryResult(null, false, false)
                return@launch
            }

            // 优先写入系统媒体库 Movies/KeySync，相册和文件管理器都能直接看到；
            // 媒体库不可用时退回应用自己的 external files 目录。
            val galleryUri = exportToGallery(temp, displayName)
            if (galleryUri != null) {
                temp.delete()
                showTrajectoryResult(galleryUri, true, true)
            } else {
                val fallbackDir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "KeySync")
                val fallback = File(fallbackDir, displayName)
                val copied = runCatching {
                    fallbackDir.mkdirs()
                    temp.copyTo(fallback, overwrite = true)
                }.isSuccess
                temp.delete()
                if (!copied) {
                    showTrajectoryResult(null, false, false)
                    return@launch
                }
                val uri = runCatching {
                    FileProvider.getUriForFile(this@FloatingBubbleService, "$packageName.updates", fallback)
                }.getOrNull()
                showTrajectoryResult(uri, uri != null, false)
            }
        }
    }

    /** 把渲染好的临时文件写入系统媒体库，返回可直接播放的 content:// Uri。 */
    private fun exportToGallery(source: File, displayName: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/KeySync")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val uri = runCatching { contentResolver.insert(collection, values) }.getOrNull() ?: return null
        return runCatching {
            contentResolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("openOutputStream returned null")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            uri
        }.getOrElse {
            runCatching { contentResolver.delete(uri, null, null) }
            null
        }
    }

    private fun createTrajectoryChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(TRAJECTORY_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            TRAJECTORY_CHANNEL_ID,
            getString(R.string.trajectory_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.trajectory_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    @SuppressLint("MissingPermission")
    private fun showTrajectoryProgress(progress: Int) {
        if (!canPostNotifications()) return
        val notification = NotificationCompat.Builder(this, TRAJECTORY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.trajectory_rendering))
            .setContentText("$progress%")
            .setProgress(100, progress.coerceIn(0, 100), false)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching {
            NotificationManagerCompat.from(this).notify(TRAJECTORY_NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("MissingPermission")
    private fun showTrajectoryResult(uri: Uri?, ok: Boolean, inGallery: Boolean) {
        if (!canPostNotifications()) return
        val text = when {
            !ok -> ""
            inGallery -> getString(R.string.trajectory_saved_gallery)
            else -> getString(R.string.trajectory_saved_app)
        }
        val builder = NotificationCompat.Builder(this, TRAJECTORY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(
                getString(if (ok) R.string.trajectory_done else R.string.trajectory_failed)
            )
            .setContentText(text)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (uri != null) {
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(view, getString(R.string.trajectory_open)).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pending = PendingIntent.getActivity(
                this,
                110,
                chooser,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.setContentIntent(pending)
            builder.addAction(0, getString(R.string.trajectory_open), pending)
        }
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(TRAJECTORY_NOTIFICATION_ID, builder.build())
        }
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * 点击通知正文 → App 设置页。
     *
     * 必须用 getActivity：Android 12+ 的 notification trampoline 限制禁止
     * contentIntent 先跳 Service/Receiver 再 startActivity，那样点击不会拉起界面。
     */
    private fun settingsPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = MainActivity.INTENT_ACTION_SETTINGS
        }
        return PendingIntent.getActivity(
            this,
            100,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * 用 getService（startService 语义）而非 getForegroundService：前者不引入
     * 「5 秒内必须 startForeground」的义务，避免被系统判定超时崩溃。
     */
    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, FloatingBubbleService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** 编辑态 / 按键显隐 / 提示条显隐变化时刷新通知，让 action 标签与实际状态保持一致。 */
    private fun observeNotificationState() {
        if (notificationObserverStarted) return
        notificationObserverStarted = true
        scope.launch {
            combine(
                stateManager.get().isEditMode,
                stateManager.get().keysVisible,
                stateManager.get().statusBarVisible,
                stateManager.get().mappingPaused
            ) { _, _, _, _ -> Unit }.collect {
                // startForeground 跑过之后才投递，避免「先 notify 后 startForeground」。
                if (!overlayInitialized) return@collect
                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED) return@collect
                runCatching {
                    NotificationManagerCompat.from(this@FloatingBubbleService)
                        .notify(NOTIFICATION_ID, buildNotification())
                }
            }
        }
    }

    private fun init() {

        // 屏幕上只保留按键容器这一个窗口（气泡已删除，控制入口收进通知栏）。
        // FLAG_LAYOUT_NO_LIMITS let this window be laid out larger than, or
        // offset from, the visible screen. The Compose tree then measured
        // whatever rectangle it was handed and every button coordinate was
        // expressed against that phantom space -- in landscape the left strip
        // fell outside it, so anything dragged there was drawn clipped. The
        // window is MATCH_PARENT, so the flag bought nothing and only broke the
        // coordinate space.
        val baseLP = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        baseLP.gravity = Gravity.START or Gravity.TOP
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Without this the window is inset around a display cutout, which
            // reintroduces the same unreachable strip on the cutout side.
            baseLP.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            baseLP.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val itemsContainerLP = WindowManager.LayoutParams()
        itemsContainerLP.copyFrom(baseLP)

        val sm = stateManager.get()
        val touchTranslator = TouchToMouseTranslator(
            onMouseEvent = { event -> sm.onMouseEvent(event) },
            isInIgnoreZone = { x, y -> isOnKeySyncButton(x, y, sm) },
            onGestureStart = { sm.resetMouseRawBaseline() },
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
                onUpdateItemSize = { id, sizeDp ->
                    sm.updateItemSize(id, sizeDp)
                },
                touchTranslator = touchTranslator,
            )

        scope.launch {
            combine(
                stateManager.get().isEditMode,
                stateManager.get().mappingPaused
            ) { edit, paused -> edit to paused }.collect { (expanded, paused) ->
                itemsContainerLP.apply {
                    width = WindowManager.LayoutParams.MATCH_PARENT
                    height = WindowManager.LayoutParams.MATCH_PARENT
                }
                when {
                    // 暂停映射：窗口完全不可触摸，触控与键盘穿透到当前应用，
                    // 同时释放指针捕获——否则鼠标事件仍会被本窗口截走。
                    paused -> {
                        itemsContainerLP.flags =
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        stateManager.get().windowManager.updateViewLayout(
                            containerView,
                            itemsContainerLP
                        )
                        containerView?.releasePointerCapture()
                    }
                    expanded -> {
                        itemsContainerLP.flags =
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
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
                        // 从通知栏进编辑态时通知栏正在收起，可能还要再抢一次焦点。
                        containerView?.postDelayed({ containerView?.requestFocus() }, 500)
                    }
                    else -> {
                        itemsContainerLP.flags =
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        stateManager.get().windowManager.updateViewLayout(
                            containerView,
                            itemsContainerLP
                        )
                        containerView?.requestFocus()
                        containerView?.requestPointerCapture()
                    }
                }
            }
        }
        stateManager.get().windowManager.addView(containerView, itemsContainerLP)
        containerView?.postDelayed({
            containerView?.requestFocus()
            if (!stateManager.get().isEditMode.value) {
                containerView?.requestPointerCapture()
            }
        }, 1000)
    }


    @SuppressLint("ClickableViewAccessibility")
    private fun getItemsContainerView(
        onRemove: (Int) -> Unit,
        onUpdateKeyCode: (Int, Int, TouchMode?) -> Unit,
        onUpdateVariableKeyCode: (Int, Int, TouchMode?) -> Unit,
        onUpdateItemSize: (Int, Float) -> Unit,
        touchTranslator: TouchToMouseTranslator
    ): ComposeView {
        val composeView = ComposeView(this)
        // Measure the actual overlay viewport, including changes from rotation/insets.
        composeView.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            view.post {
                if (view.isAttachedToWindow) {
                    val origin = IntArray(2)
                    view.getLocationOnScreen(origin)
                    stateManager.get().onOverlayFrame(view.width, view.height,
                        androidx.compose.ui.geometry.Offset(origin[0].toFloat(), origin[1].toFloat()))
                }
            }
        }
        composeView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        composeView.setContent {
            KeySyncTheme {
                val draggableItems by stateManager.get().containerItems.collectAsState()
                val itemsContainerOpacity by stateManager.get().overlayOpacity.collectAsState()
                val isEditMode by stateManager.get().isEditMode.collectAsState()
                val isShootingMode by stateManager.get().isShootingMode.collectAsState()
                val pointerOffset by stateManager.get().pointerOffset.collectAsState()
                val keysConfig by stateManager.get().keysConfig.collectAsState()
                val keysVisible by stateManager.get().keysVisible.collectAsState()
                val mappingPaused by stateManager.get().mappingPaused.collectAsState()
                val statusBarVisible by stateManager.get().statusBarVisible.collectAsState()
                val pendingBindId by stateManager.get().pendingVariableKeyBind.collectAsState()
                val pressedKeys by stateManager.get().pressedKeys.collectAsState()
                val wheelCursor by stateManager.get().wheelCursor.collectAsState()
                val walkEnabled by stateManager.get().walkEnabled.collectAsState()
                val lastInput by stateManager.get().lastInputLabel.collectAsState()
                val profiles by stateManager.get().profiles.collectAsState()
                val activeId by stateManager.get().activeProfileId.collectAsState()
                Box {
                    // 光标：用半透明触摸指示圈代替箭头，视觉上更接近"手指触点"。
                    if (!isEditMode && !mappingPaused && !isShootingMode && wheelCursor == null) {
                        val diameter = 26.dp
                        val radiusPx = with(LocalDensity.current) { (diameter / 2).toPx() }
                        Box(
                            modifier = Modifier
                                .offset {
                                    IntOffset(
                                        (pointerOffset.x - radiusPx).toInt(),
                                        (pointerOffset.y - radiusPx).toInt()
                                    )
                                }
                                .size(diameter)
                                .background(Color.White.copy(alpha = 0.16f), CircleShape)
                                .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                        ) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(3.dp)
                                    .background(Color.White.copy(alpha = 0.9f), CircleShape)
                            )
                        }
                    }
                    if (!isEditMode && itemsContainerOpacity == 0f)
                        return@Box//do not compose if user set overlay opacity to 0
                    // 一键隐藏按键：keysVisible 为 false 时不渲染按键容器。
                    // 暂停映射时同样不渲染——此时窗口不可触摸，留着按钮只会误导。
                    if (keysVisible && !mappingPaused) {
                        ItemsContainer(
                            Modifier.alpha(if (isEditMode) 1f else itemsContainerOpacity),
                            appConfig = keysConfig,
                            editing = isEditMode,
                            walkEnabled = walkEnabled,
                            onCalibrateWalkOff = { stateManager.get().calibrateWalkOff() },
                            onItemMeasured = { stateManager.get().updateMeasuredPosition(it) },
                            containerItems = draggableItems,
                            onRemove = onRemove,
                            onUpdateKeyCode = onUpdateKeyCode,
                            onUpdateVariableKeyCode = onUpdateVariableKeyCode,
                            onUpdateCancelableKeyCode = { id, newKeyCode, touchMode ->
                                stateManager.get().updateCancelableKeyCode(id, newKeyCode, touchMode)
                            },
                            onUpdateItemSize = onUpdateItemSize,
                            pendingBindId = pendingBindId,
                            onBindConsumed = { stateManager.get().consumePendingVariableKeyBind() },
                            onKeyCaptureChanged = { listener ->
                                stateManager.get().keyCaptureListener = listener
                            },
                            pressedKeys = pressedKeys,
                            onRequestFocus = { composeView.post { composeView.requestFocus() } },
                        )
                        // 顶部状态提示条：预设名 · 模式 · 最近输入。
                        // 通知栏的「隐藏提示」action 只关这一行，不影响按键容器。
                        if (!isEditMode && statusBarVisible) {
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
                    // 编辑态操作条。非编辑态完全不渲染 —— 游戏时屏幕上不会有任何常驻控件。
                    if (isEditMode) {
                        MacroPointsOverlay(
                            macros = profiles.firstOrNull { it.id == activeId }?.macros.orEmpty(),
                            onMove = { macro, step, x, y -> stateManager.get().updateMacroPoint(macro, step, x, y) },
                            onDragFinished = { stateManager.get().saveMacroPoints() },
                        )
                        EditToolbar(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp),
                            keysVisible = keysVisible,
                            statusBarVisible = statusBarVisible,
                            onToggleKeysVisible = { stateManager.get().toggleKeysVisible() },
                            onToggleStatusBarVisible = { stateManager.get().toggleStatusBarVisible() },
                            onToggleEditMode = { stateManager.get().toggleEditMode() },
                            onAddItem = { stateManager.get().addNewItem(it) },
                        )
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
        fun recoverPointerCapture() {
            composeView.post {
                if (composeView.isAttachedToWindow && composeView.hasWindowFocus() &&
                    !stateManager.get().isEditMode.value && !composeView.hasPointerCapture()) {
                    composeView.requestFocus()
                    composeView.requestPointerCapture()
                }
            }
        }
        composeView.viewTreeObserver.addOnWindowFocusChangeListener { focused ->
            if (!focused) {
                // Key/button UP may go to the notification shade or another window.
                // End the gesture now; never replay stale held keys on focus recovery.
                stateManager.get().clearActivePointers()
                composeView.releasePointerCapture()
            } else {
                if (stateManager.get().isEditMode.value) {
                    // 从通知栏进编辑态时，通知栏收起的过程中可能抢走焦点。编辑态
                    // 一旦丢焦点，View 层 setOnKeyListener 就收不到按键（按钮一直显示
                    // ＋）；而 recoverPointerCapture 只在非编辑态恢复，所以这里补一次。
                    composeView.requestFocus()
                    composeView.releasePointerCapture()
                    stateManager.get().clearActivePointers()
                } else {
                    recoverPointerCapture()
                }
            }
        }
        composeView.setOnKeyListener { _, _, event ->
            val handled = stateManager.get().onKeyEvent(event)
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0)
                recoverPointerCapture()
            handled
        }
        // Catch touch events injected by screen-mirror / keymapper apps
        // (e.g. 熊猫映射 / Scrcpy / GameKeyboard) so a PC mouse forwarded
        // through them can still drive the game. Only active when the user
        // has opted in via Settings -> "Screen Mirror Compat Mode".
        composeView.setOnTouchListener { _, motionEvent ->
            // Editing must receive the entire gesture, even when a drag leaves
            // the button hit area or screen-mirror compatibility is enabled.
            if (stateManager.get().isEditMode.value) {
                val sm = stateManager.get()
                if (sm.keyCaptureListener != null && motionEvent.isFromSource(InputDevice.SOURCE_MOUSE) &&
                    motionEvent.actionMasked == MotionEvent.ACTION_DOWN &&
                    motionEvent.buttonState and (MotionEvent.BUTTON_TERTIARY or MotionEvent.BUTTON_BACK or MotionEvent.BUTTON_FORWARD) != 0) {
                    return@setOnTouchListener sm.onMouseEvent(motionEvent)
                }
                return@setOnTouchListener false
            }
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转/分屏会改变屏幕尺寸，丢掉鼠标路径缓存的尺寸，下次事件重新测量。
        stateManager.get().invalidateScreenSizeCache()
    }

    override fun onDestroy() {
        _isRunning.value = false
        overlayInitialized = false
        trajectoryAutoStopJob?.cancel()
        trajectoryRenderJob?.cancel()
        if (TrajectoryRecorder.isRecording) TrajectoryRecorder.stop()
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
    }

}
