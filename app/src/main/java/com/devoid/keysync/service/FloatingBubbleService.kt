@file:OptIn(kotlinx.coroutines.FlowPreview::class)

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
import androidx.compose.ui.geometry.Offset
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
import com.devoid.keysync.domain.TrajectoryItemKind
import com.devoid.keysync.domain.TrajectoryItemSnapshot
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
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.sample
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
        /** 关闭悬浮窗：与设置页「恢复默认」上方的「关闭悬浮窗」同一行为。 */
        private const val ACTION_CLOSE_OVERLAY = "com.devoid.keysync.action.CLOSE_OVERLAY"
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
            ACTION_CLOSE_OVERLAY -> {
                stopSelf()
                return START_NOT_STICKY
            }
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
        val pauseLabel = getString(R.string.overlay_close_overlay)
        return NotificationCompat.Builder(this, CHANNEL_ID).apply {
            setSmallIcon(R.drawable.ic_launcher_foreground)
            setContentTitle(getString(R.string.app_name))
            setContentText(
                getString(
                    if (sm.mappingPaused.value) R.string.notification_text_paused
                    else R.string.notification_text_overlay_running
                )
            )
            // 折叠态只放得下 3 个 action：关闭悬浮窗 / 隐藏按键 / 录制轨迹。
            // 「关闭悬浮窗」等价于设置页「恢复默认」上方的同名按钮（停止前台服务），
            // 暂停映射不再单独提供：暂停后没有可靠的恢复入口，直接关闭更符合直觉。
            // 添加按键已移到编辑态工具条的「＋」；编辑布局与关闭放到展开态，
            // 其中「关闭」误触代价最大，故意藏一层。总共 5 个，避免第 6 个被系统吞掉。
            setStyle(NotificationCompat.BigTextStyle())
            setContentIntent(settingsPendingIntent())
            setOngoing(true)
            setSilent(true)
            setOnlyAlertOnce(true)
            setPriority(NotificationCompat.PRIORITY_LOW)
            addAction(0, pauseLabel, servicePendingIntent(ACTION_CLOSE_OVERLAY, 106))
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
     * 录制会收下所有实际注入的触点（普通按键、WASD、准心、宏、连点、F 滑动等）
     * 以及所有按下的物理键；停止后在后台渲染全屏映射回放视频。
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
        val sm = stateManager.get()
        val viewport = sm.viewportForLayout
        val recording = TrajectoryRecorder.stop(viewport.x, viewport.y)
        refreshOverlayNotification()
        if (recording == null || recording.isEmpty) {
            Toast.makeText(this, R.string.trajectory_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val presetName = sm.profiles.value
            .firstOrNull { it.id == sm.activeProfileId.value }
            ?.name
            .orEmpty()
        renderTrajectory(recording, snapshotTrajectoryLayout(), presetName)
    }

    /**
     * 把当前悬浮层布局压成回放用的几何快照。
     *
     * 只读一次 [FloatingWindowStateManager.containerItems]，之后即使退出编辑态或换预设，
     * 后台渲染拿到的仍是录制结束那一刻的按钮位置与大小。
     */
    private fun snapshotTrajectoryLayout(): List<TrajectoryItemSnapshot> {
        val sm = stateManager.get()
        val density = resources.displayMetrics.density
        return sm.containerItems.value.mapNotNull { item ->
            when (item) {
                is DraggableItem.WASDGroup -> TrajectoryItemSnapshot(
                    kind = TrajectoryItemKind.WASD,
                    label = "WASD",
                    centerX = item.center.x,
                    centerY = item.center.y,
                    radiusX = ((item.d.x - item.a.x) / 2f).coerceAtLeast(20f),
                    radiusY = ((item.s.y - item.w.y) / 2f).coerceAtLeast(20f),
                )

                is DraggableItem.VariableKey -> {
                    val size = item.size.takeIf { it > 0 }?.toFloat()
                        ?: (item.customSizeDp * density)
                    val center = item.touchCenter
                        ?: (item.position + Offset(size / 2f, size / 2f))
                    TrajectoryItemSnapshot(
                        kind = TrajectoryItemKind.BUTTON,
                        label = item.keyCode?.keyCodeToString()?.removePrefix("KEYCODE_") ?: "+",
                        centerX = center.x,
                        centerY = center.y,
                        radiusX = size / 2f,
                        radiusY = size / 2f,
                    )
                }

                is DraggableItem.FixedKey -> {
                    val size = item.size.takeIf { it > 0 }?.toFloat()
                        ?: (item.customSizeDp * density)
                    val center = item.touchCenter
                        ?: (item.position + Offset(size / 2f, size / 2f))
                    TrajectoryItemSnapshot(
                        kind = TrajectoryItemKind.BUTTON,
                        label = item.keyCode.keyCodeToString().removePrefix("KEYCODE_"),
                        centerX = center.x,
                        centerY = center.y,
                        radiusX = size / 2f,
                        radiusY = size / 2f,
                    )
                }

                is DraggableItem.CancelableKey -> {
                    val size = item.size.takeIf { it > 0 }?.toFloat()
                        ?: (item.customSizeDp * density)
                    val center = item.cancelTouchCenter
                        ?: (item.position + Offset(size / 2f, size / 2f))
                    TrajectoryItemSnapshot(
                        kind = TrajectoryItemKind.BUTTON,
                        label = item.keyCode?.keyCodeToString()?.removePrefix("KEYCODE_") ?: "?",
                        centerX = center.x,
                        centerY = center.y,
                        radiusX = size / 2f,
                        radiusY = size / 2f,
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshOverlayNotification() {
        if (!overlayInitialized) return
        if (!canPostNotifications()) return
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun renderTrajectory(
        recording: TrajectoryRecording,
        layout: List<TrajectoryItemSnapshot>,
        presetName: String,
    ) {
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
            val ok = TrajectoryVideoRenderer.render(recording, layout, presetName, temp) { progress ->
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
            // 明确不拟合任何系统栏 inset。否则窗口在某些启动时序下（例如刚装完更新、
            // 状态栏还显示时创建）会被整体下移一个状态栏高度，看起来就是「布局往下偏」；
            // 重启后状态栏已隐藏、窗口从 0 开始，所以重启又能恢复。
            baseLP.fitInsetsTypes = 0
            baseLP.fitInsetsSides = 0
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            baseLP.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val itemsContainerLP = WindowManager.LayoutParams()
        itemsContainerLP.copyFrom(baseLP)
        // MIUI 等系统即使关闭 inset 拟合，也可能把 MATCH_PARENT 悬浮窗整体下移几像素；
        // 直接用物理屏幕像素铺满，避免映射按键整体被顶下来。
        fillPhysicalDisplay(itemsContainerLP)

        val sm = stateManager.get()
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
            )

        scope.launch {
            combine(
                stateManager.get().isEditMode,
                stateManager.get().mappingPaused
            ) { edit, paused -> edit to paused }.collect { (expanded, paused) ->
                itemsContainerLP.apply {
                    fillPhysicalDisplay(this)
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


    /** 用物理屏幕像素铺满悬浮窗，绕过 OEM 对系统栏 inset 的额外下移。 */
    private fun fillPhysicalDisplay(lp: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            lp.width = bounds.width()
            lp.height = bounds.height()
            lp.x = bounds.left
            lp.y = bounds.top
        } else {
            @Suppress("DEPRECATION")
            val metrics = resources.displayMetrics
            lp.width = metrics.widthPixels
            lp.height = metrics.heightPixels
            lp.x = 0
            lp.y = 0
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun getItemsContainerView(
        onRemove: (Int) -> Unit,
        onUpdateKeyCode: (Int, Int, TouchMode?) -> Unit,
        onUpdateVariableKeyCode: (Int, Int, TouchMode?) -> Unit,
        onUpdateItemSize: (Int, Float) -> Unit,
    ): ComposeView {
        val composeView = ComposeView(this)
        // Measure the actual overlay viewport, including changes from rotation/insets.
        composeView.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            view.post {
                if (view.isAttachedToWindow) {
                    val origin = IntArray(2)
                    view.getLocationOnScreen(origin)
                    // 自愈：旋转或 OEM inset 导致窗口被下移/缩小时，重新按物理屏幕铺满。
                    val wm = getSystemService(WindowManager::class.java)
                    val target = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        wm.maximumWindowMetrics.bounds
                    } else {
                        @Suppress("DEPRECATION")
                        val m = resources.displayMetrics
                        android.graphics.Rect(0, 0, m.widthPixels, m.heightPixels)
                    }
                    if (view.width != target.width() || view.height != target.height() ||
                        origin[0] != target.left || origin[1] != target.top
                    ) {
                        val lp = containerView?.layoutParams as? WindowManager.LayoutParams
                        if (lp != null) {
                            fillPhysicalDisplay(lp)
                            wm.updateViewLayout(containerView, lp)
                        }
                    }
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
                val cursorRenderHz by stateManager.get().cursorRenderHz.collectAsState()
                // 光标显示按自定义刷新率采样，逻辑坐标仍全速更新，避免每个鼠标事件都重绘全屏层。
                val pointerOffsetFlow = stateManager.get().pointerOffset
                val pointerOffset by remember(pointerOffsetFlow, cursorRenderHz) {
                    pointerOffsetFlow.sample(1000L / cursorRenderHz.coerceIn(15, 240))
                }.collectAsState(initial = stateManager.get().currentPointerOffset())
                val keysConfig by stateManager.get().keysConfig.collectAsState()
                val keysVisible by stateManager.get().keysVisible.collectAsState()
                val advancedVisible by stateManager.get().advancedVisible.collectAsState()
                val mappingPaused by stateManager.get().mappingPaused.collectAsState()
                val statusBarVisible by stateManager.get().statusBarVisible.collectAsState()
                val pendingBindId by stateManager.get().pendingVariableKeyBind.collectAsState()
                val pressedKeys by stateManager.get().pressedKeys.collectAsState()
                val wheelCursor by stateManager.get().wheelCursor.collectAsState()
                val walkEnabled by stateManager.get().walkEnabled.collectAsState()
                val lastInput by stateManager.get().lastInputLabel.collectAsState()
                val profiles by stateManager.get().profiles.collectAsState()
                val activeId by stateManager.get().activeProfileId.collectAsState()
                val advancedLayerActive by stateManager.get().editAdvancedLayer.collectAsState()
                val mouseAreaCenter by stateManager.get().mouseAreaCenter.collectAsState()
                val mouseAreaHalf by stateManager.get().mouseAreaHalf.collectAsState()
                val landingAreaCenter by stateManager.get().landingAreaCenter.collectAsState()
                val landingAreaHalf by stateManager.get().landingAreaHalf.collectAsState()
                val aimIdleReanchorMs by stateManager.get().aimIdleReanchorMs.collectAsState()
                val aimRecenterMode by stateManager.get().aimRecenterMode.collectAsState()
                val overlayOrigin by stateManager.get().overlayOriginFlow.collectAsState()
                val shootingAnchor by stateManager.get().shootingAnchorFlow.collectAsState()
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
                        )
                    }
                    if (!isEditMode && itemsContainerOpacity == 0f)
                        return@Box//do not compose if user set overlay opacity to 0
                    // 一键隐藏按键：keysVisible 为 false 时不渲染按键容器。
                    // 暂停映射时同样不渲染——此时窗口不可触摸，留着按钮只会误导。
                    // Enter 层级预览时只渲染普通按键层或高级层标记。
                    val keysLayerVisible = isEditMode || keysVisible
                    val advancedLayerVisible = isEditMode || advancedVisible
                    if ((keysLayerVisible || advancedLayerVisible) && !mappingPaused) {
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
                            extraTapPoints = profiles.firstOrNull { it.id == activeId }
                                ?.extraTapPoints.orEmpty(),
                            onAddExtraTapPoint = { stateManager.get().addExtraTapPoint(it) },
                            onMoveExtraTapPoint = { keyCode, index, point ->
                                stateManager.get().moveExtraTapPoint(keyCode, index, point)
                            },
                            onClearExtraTapPoints = { stateManager.get().clearExtraTapPoints(it) },
                            discardPoint = profiles.firstOrNull { it.id == activeId }?.let { p ->
                                val x = p.discardX
                                val y = p.discardY
                                if (x != null && y != null) androidx.compose.ui.geometry.Offset(x, y) else null
                            },
                            onMoveDiscardPoint = { stateManager.get().moveDiscardPoint(it) },
                            cursorSpawn = stateManager.get().cursorSpawnPoint(),
                            onMoveCursorSpawn = { stateManager.get().moveCursorSpawn(it) },
                            onResetCursorSpawn = { stateManager.get().resetCursorSpawn() },
                            mouseMoveAreaCenter = if (mouseAreaHalf > 0f)
                                mouseAreaCenter - overlayOrigin else null,
                            mouseMoveAreaHalf = mouseAreaHalf,
                            landingAreaCenter = if (landingAreaHalf > 0f)
                                landingAreaCenter - overlayOrigin else null,
                            landingAreaHalf = landingAreaHalf,
                            shootingModeAnchor = shootingAnchor?.minus(overlayOrigin),
                            onMouseAreaChange = { center, half ->
                                stateManager.get().setMouseArea(center + overlayOrigin, half)
                            },
                            onLandingAreaChange = { center, half ->
                                stateManager.get().setLandingArea(center + overlayOrigin, half)
                            },
                            aimIdleReanchorMs = aimIdleReanchorMs,
                            onAimIdleReanchorMsChange = { ms ->
                                stateManager.get().setAimIdleReanchorMs(ms)
                            },
                            aimRecenterMode = aimRecenterMode,
                            onAimRecenterModeChange = { mode ->
                                stateManager.get().setAimRecenterMode(mode)
                            },
                            advancedLayerActive = advancedLayerActive,
                            // 滚轮操控已改为「光标位置全屏生效」，不再使用左右锚点。
                            showKeys = keysLayerVisible,
                            showAdvancedMarkers = advancedLayerVisible,
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
                            advancedLayerActive = advancedLayerActive,
                            onToggleAdvancedLayer = {
                                stateManager.get().setEditAdvancedLayer(!advancedLayerActive)
                            },
                            onToggleKeysVisible = { stateManager.get().toggleKeysVisible() },
                            onToggleStatusBarVisible = { stateManager.get().toggleStatusBarVisible() },
                            onToggleEditMode = { stateManager.get().toggleEditMode() },
                            onCancelEdits = { stateManager.get().cancelEditMode() },
                            onAddItem = { stateManager.get().addNewItem(it) },
                            onAddDiscardPoint = { stateManager.get().addDiscardPoint() },
                            onAddEscTapPoint = {
                                stateManager.get().addExtraTapPoint(android.view.KeyEvent.KEYCODE_ESCAPE)
                            },
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
            false
        }
        return composeView
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
        if (TrajectoryRecorder.isRecording) TrajectoryRecorder.stop(0f, 0f)
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
