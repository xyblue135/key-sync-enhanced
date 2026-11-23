package com.devoid.keysync

import android.content.Context
import android.content.Context.INPUT_SERVICE
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devoid.keysync.data.external.GitHubReleaseClient
import com.devoid.keysync.data.local.DataStoreManager
import com.devoid.keysync.data.mapping.MappingPreset
import com.devoid.keysync.data.mapping.MappingPresetRepository
import com.devoid.keysync.model.AppConfig
import com.devoid.keysync.model.Profile
import com.devoid.keysync.model.ProfileAssist
import com.devoid.keysync.data.remote.ApkInstaller
import com.devoid.keysync.model.UpdateState
import com.devoid.keysync.service.FloatingBubbleService
import com.devoid.keysync.service.FloatingWindowStateManager
import com.devoid.keysync.util.VersionComparator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** One row of the "add game" sheet. */
data class AppInfo(val packageName: String, val label: String)

@HiltViewModel
class MainActivityViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStoreManager: DataStoreManager,
    private val presetRepository: MappingPresetRepository,
    private val stateManager: FloatingWindowStateManager,
) :
    ViewModel(), Shizuku.OnRequestPermissionResultListener {
    private val TAG = "MainActivityViewModel"
    private val SHIZUKU_REQ_CODE = 10
    private val _shizukuState = MutableStateFlow<UiState>(UiState.ShizukuNotRunning)
    val shizukuState = _shizukuState.asStateFlow()

    private val _addedPackages = MutableStateFlow<List<String>>(listOf())
    val addedPackages = _addedPackages.asStateFlow()

    /**
     * Runtime configuration always mirrors the ACTIVE PROFILE.
     *
     * This used to be fed from the legacy global `KEYS_CONFIG` key, which a
     * profile switch does not update. After switching profiles the settings
     * screen therefore showed the previous profile's theme / touch modes /
     * sensitivity, and pressing Save wrote those stale values into the newly
     * active profile, silently reverting it.
     */
    val appConfig: StateFlow<AppConfig> = stateManager.keysConfig

    /** Set when stored profiles existed but could not be decoded. */
    val profilesLoadError: StateFlow<String?> = stateManager.profilesLoadError

    val canSaveProfiles: Boolean get() = dataStoreManager.canWriteProfiles

    fun consumeProfilesLoadError() = stateManager.consumeProfilesLoadError()


    private val _connectedDevices = MutableStateFlow<Map<Int, String>>(hashMapOf())
    val connectedDevices = _connectedDevices.asStateFlow()

    private val _presets = MutableStateFlow<List<MappingPreset>>(emptyList())
    val presets = _presets.asStateFlow()

    /** Profile state lives in [FloatingWindowStateManager] but is mirrored here
     *  for the settings UI to consume via [collectAsState]. */
    val profiles: StateFlow<List<Profile>> = stateManager.profiles
    val activeProfileId: StateFlow<String?> = stateManager.activeProfileId

    /** 按键拟人化（随机偏移）全局设置，供设置页收集。 */
    val wasdHumanization: StateFlow<Boolean> = stateManager.wasdHumanization
    val keyHumanization: StateFlow<Boolean> = stateManager.keyHumanization
    val humanizationStrength: StateFlow<Float> = stateManager.humanizationStrength
    val humanizationInterval: StateFlow<Long> = stateManager.humanizationInterval
    /** WASD 专用随机偏移间隔（全局）。 */
    val wasdJitterRandomEnabled: StateFlow<Boolean> = stateManager.wasdJitterRandomEnabled
    val wasdJitterMinMs: StateFlow<Int> = stateManager.wasdJitterMinMs
    val wasdJitterMaxMs: StateFlow<Int> = stateManager.wasdJitterMaxMs
    val wasdSway: StateFlow<Float> = stateManager.wasdSway
    /** WASD 换向弧线过渡（全局）。 */
    val wasdTransition: StateFlow<Boolean> = stateManager.wasdTransition
    val wasdTransitionMs: StateFlow<Int> = stateManager.wasdTransitionMs
    /** 悬浮窗透明度（全局设置），设置页用；改动立即持久化。 */
    val overlayOpacity: StateFlow<Float> = stateManager.overlayOpacity
    /** 光标渲染刷新率（Hz），默认 60。 */
    val cursorRenderHz: StateFlow<Int> = stateManager.cursorRenderHz
    /** 顶部状态提示条（预设名 · 模式 · 最近输入）的显隐；全局设置，立即持久化。 */
    val statusBarVisible: StateFlow<Boolean> = stateManager.statusBarVisible

    /* -------- 鼠标高级参数（全局，设置页「兼容与高级」） -------- */
    val mouseJumpThresholdPx: StateFlow<Int> = stateManager.mouseJumpThresholdPx
    val mouseModeLatch: StateFlow<Boolean> = stateManager.mouseModeLatch
    val screenSizeCacheEnabled: StateFlow<Boolean> = stateManager.screenSizeCacheEnabled
    /** 「防止大幅甩动导致视角问题」总开关。 */
    val mouseGuardEnabled: StateFlow<Boolean> = stateManager.mouseGuardEnabled
    val discardDragEnabled: StateFlow<Boolean> = stateManager.discardDragEnabled
    /** Alt+D 拖拽速度倍数（1–10，默认 5）。 */
    val discardDragSpeed: StateFlow<Float> = stateManager.discardDragSpeed
    /** M 键开关地图并切换鼠标（全局，默认开启）。 */
    val mapMouseToggleEnabled: StateFlow<Boolean> = stateManager.mapMouseToggleEnabled
    val fCursorClickEnabled: StateFlow<Boolean> = stateManager.fCursorClickEnabled
    /** F 交互后左键唤出光标的窗口（ms）。 */
    val fCursorClickWindowMs: StateFlow<Int> = stateManager.fCursorClickWindowMs
    /** ESC / Tab 关闭界面后自动回射击模式（全局，默认开启）。 */
    val tabEscAimEnabled: StateFlow<Boolean> = stateManager.tabEscAimEnabled
    /** ESC / Tab 回瞄准延迟（ms）。 */
    val tabEscAimDelayMs: StateFlow<Int> = stateManager.tabEscAimDelayMs
    /** ESC 按下后批量点击落点（全局，默认关闭）。 */
    val escAfterTapEnabled: StateFlow<Boolean> = stateManager.escAfterTapEnabled
    val escAfterTapDelayMs: StateFlow<Int> = stateManager.escAfterTapDelayMs
    val escAfterTapIntervalMs: StateFlow<Int> = stateManager.escAfterTapIntervalMs
    /** F 光标滚轮操控（全局，默认开启）。 */
    val fScrollControlEnabled: StateFlow<Boolean> = stateManager.fScrollControlEnabled
    /** 每格滚轮的短滑距离（px）。 */
    val fScrollSwipePx: StateFlow<Float> = stateManager.fScrollSwipePx
    /** 滚轮方向反转。 */
    val fScrollInvert: StateFlow<Boolean> = stateManager.fScrollInvert
    /** F 滚轮每小步延迟（ms）。 */
    val fScrollStepDelayMs: StateFlow<Int> = stateManager.fScrollStepDelayMs
    /** F 滚轮操控上/下滑触发键（默认鼠标滚轮上/下）。 */
    val fScrollUpKeyCode: StateFlow<Int> = stateManager.fScrollUpKeyCode
    val fScrollDownKeyCode: StateFlow<Int> = stateManager.fScrollDownKeyCode
    /** 玩法模式：简易 + 高级 A/B。 */
    val playModes: StateFlow<List<com.devoid.keysync.model.PlayMode>> = stateManager.playModes
    val currentPlayModeIndex: StateFlow<Int> = stateManager.currentPlayModeIndex

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    /** 「检查更新 / 下载并安装」的状态。 */
    val updateState = _updateState.asStateFlow()

    private val apkInstaller = ApkInstaller(context)

    /**
     * Device/layout numbers for the home screen.
     *
     * [overlayViewport] is the overlay window's own pixel size and is null until
     * the service reports its first frame. [activeLayoutBaseline] is the pixel
     * size the active profile's coordinates were saved against. When the two
     * disagree, [projectLayout] rescales every saved position on load, which is
     * the usual explanation for "my buttons moved after rotating / changing
     * device" -- so the home screen shows both instead of just the display size.
     */
    val overlayViewport: StateFlow<Offset?> = stateManager.reportedViewport

    val activeLayoutBaseline: StateFlow<Offset?> = combine(
        stateManager.profiles,
        stateManager.activeProfileId,
    ) { profiles, activeId ->
        val active = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
        if (active == null || active.layoutScreenWidth <= 0 || active.layoutScreenHeight <= 0) {
            null
        } else {
            Offset(active.layoutScreenWidth.toFloat(), active.layoutScreenHeight.toFloat())
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Resolve an app label once per package; the sheet re-renders on every
     *  keystroke of its search field and PackageManager lookups are not free. */
    private val labelCache = ConcurrentHashMap<String, String>()

    private var deviceListener: InputManager.InputDeviceListener? = null
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        val binder = Shizuku.getBinder()
        binder?.let {
            _shizukuState.value = UiState.ShizukuRunning(it)
        }
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        _shizukuState.value = UiState.ShizukuNotRunning
        // A dead injector must not leave virtual fingers held in the game.
        stateManager.clearActivePointers()
    }

    init {
        Shizuku.addRequestPermissionResultListener(this)
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        getShizukuBinder()
        getConnectedExternalDevices()
        viewModelScope.launch(Dispatchers.IO) {
            _presets.value = presetRepository.listPresets()
        }
        viewModelScope.launch {
            _addedPackages.value = dataStoreManager.getList(DataStoreManager.ADDED_PACKAGES).first()
        }
    }

    fun saveKeyConfig(newAppConfig: AppConfig) {
        // The 3 keyCode fields used to live on the global AppConfig; they
        // are now per-button (DraggableItem.FixedKey.keyCode) and editable
        // from the floating window's gear-icon dialog. Settings only manages
        // the remaining AppConfig fields, so just persist into the active
        // profile via FloatingWindowStateManager.
        // The legacy global KEYS_CONFIG key is deliberately not written any more.
        // It is read only by the one-time migration in FloatingWindowStateManager,
        // and keeping it in sync is what let a profile switch revert itself.
        stateManager.saveAppConfig(newAppConfig)
    }

    /* ----------------- profile helpers ----------------- */

    fun exportAllProfilesJson(): String = stateManager.exportAllProfilesJson()

    fun createProfile(name: String): String = stateManager.createProfile(name)

    fun switchProfile(id: String) {
        stateManager.switchProfile(id)
    }

    fun renameProfile(id: String, newName: String) {
        stateManager.renameProfile(id, newName)
    }

    fun deleteProfile(id: String) {
        stateManager.deleteProfile(id)
    }

    /** Creates a copy of [id] so a variant can be built on an existing layout. */
    fun duplicateProfile(id: String) {
        stateManager.duplicateProfile(id)
    }

    /** Profile serialised as JSON, ready for the clipboard. */
    fun exportProfileJson(id: String): String? = stateManager.exportProfileJson(id)

    /** @return null on success, otherwise a user-facing error message. */
    fun importProfileJson(raw: String): String? = stateManager.importProfileJson(raw)

    /** 设置页「保存」时一次性写入当前预设的按键辅助草稿。 */
    fun saveProfileAssist(profileId: String, assist: ProfileAssist) {
        stateManager.saveProfileAssist(profileId, assist)
    }

    fun setWasdHumanization(enabled: Boolean) = stateManager.setWasdHumanization(enabled)
    fun setKeyHumanization(enabled: Boolean) = stateManager.setKeyHumanization(enabled)
    fun setHumanizationStrength(value: Float) = stateManager.setHumanizationStrength(value)
    fun setHumanizationInterval(intervalMs: Long) = stateManager.setHumanizationInterval(intervalMs)
    fun setWasdJitterRandomEnabled(enabled: Boolean) =
        stateManager.setWasdJitterRandomEnabled(enabled)
    fun setWasdJitterRange(minMs: Int, maxMs: Int) =
        stateManager.setWasdJitterRange(minMs, maxMs)
    fun setWasdSway(value: Float) = stateManager.setWasdSway(value)
    fun setWasdTransition(enabled: Boolean) = stateManager.setWasdTransition(enabled)
    fun setWasdTransitionMs(ms: Int) = stateManager.setWasdTransitionMs(ms)
    fun setOverlayOpacity(value: Float) = stateManager.saveOverlayOpacity(value)
    fun setCursorRenderHz(hz: Int) = stateManager.setCursorRenderHz(hz)
    fun setStatusBarVisible(visible: Boolean) = stateManager.setStatusBarVisible(visible)
    fun setMouseJumpThreshold(px: Int) = stateManager.setMouseJumpThreshold(px)
    fun setMouseModeLatch(enabled: Boolean) = stateManager.setMouseModeLatch(enabled)
    fun setScreenSizeCache(enabled: Boolean) = stateManager.setScreenSizeCache(enabled)
    fun setMouseGuardEnabled(enabled: Boolean) = stateManager.setMouseGuardEnabled(enabled)

    fun setDiscardDragEnabled(enabled: Boolean) = stateManager.setDiscardDragEnabled(enabled)
    fun setDiscardDragSpeed(speed: Float) = stateManager.setDiscardDragSpeed(speed)
    fun setMapMouseToggleEnabled(enabled: Boolean) = stateManager.setMapMouseToggleEnabled(enabled)
    fun setFScrollTriggerKeys(upKeyCode: Int, downKeyCode: Int) =
        stateManager.setFScrollTriggerKeys(upKeyCode, downKeyCode)
    fun setFCursorClickEnabled(enabled: Boolean) = stateManager.setFCursorClickEnabled(enabled)
    fun setFCursorClickWindowMs(ms: Int) = stateManager.setFCursorClickWindowMs(ms)
    fun setTabEscAimEnabled(enabled: Boolean) = stateManager.setTabEscAimEnabled(enabled)
    fun setTabEscAimDelayMs(ms: Int) = stateManager.setTabEscAimDelayMs(ms)
    fun setEscAfterTapEnabled(enabled: Boolean) = stateManager.setEscAfterTapEnabled(enabled)
    fun setEscAfterTapDelayMs(ms: Int) = stateManager.setEscAfterTapDelayMs(ms)
    fun setEscAfterTapIntervalMs(ms: Int) = stateManager.setEscAfterTapIntervalMs(ms)
    fun setFScrollControlEnabled(enabled: Boolean) = stateManager.setFScrollControlEnabled(enabled)
    fun setFScrollSwipePx(px: Float) = stateManager.setFScrollSwipePx(px)
    fun setFScrollInvert(invert: Boolean) = stateManager.setFScrollInvert(invert)
    fun setFScrollStepDelayMs(ms: Int) = stateManager.setFScrollStepDelayMs(ms)
    fun setCurrentPlayMode(index: Int) = stateManager.setCurrentPlayMode(index)
    fun updatePlayMode(index: Int, mode: com.devoid.keysync.model.PlayMode) =
        stateManager.updatePlayMode(index, mode)
    fun addPlayMode() = stateManager.addPlayMode()

    /**
     * Removes games by package name.
     *
     * Selection used to be tracked by list index, which silently pointed at a
     * different app as soon as the list shifted (e.g. after an earlier remove),
     * so callers now pass the package names they actually mean.
     */
    fun removePackages(packageNames: Set<String>) {
        if (packageNames.isEmpty()) return
        viewModelScope.launch {
            val remaining = _addedPackages.value.filterNot { it in packageNames }
            val removed = _addedPackages.value.filter { it in packageNames }
            if (appConfig.value.deleteDataOnRemove) {
                removed.forEach {
                    dataStoreManager.remove(DataStoreManager.getButtonsConfigKey(it))
                }
            }
            _addedPackages.value = remaining
            dataStoreManager.saveList(DataStoreManager.ADDED_PACKAGES, remaining)
        }
    }

    /** @return false when the package was already in the list. */
    fun addPackage(packageName: String): Boolean {
        if (_addedPackages.value.contains(packageName))
            return false
        _addedPackages.value = _addedPackages.value.plus(packageName)
        viewModelScope.launch {
            dataStoreManager.saveList(DataStoreManager.ADDED_PACKAGES, _addedPackages.value)
        }
        return true
    }

    /**
     * Scans launchable, non-system apps on [Dispatchers.IO].
     *
     * The sheet used to call this straight from the composition, which blocked
     * the main thread for the whole scan and re-ran on every recomposition.
     */
    suspend fun loadInstalledApps(exclude: Set<String>): List<AppInfo> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { it.enabled }
                .filter { it.packageName != context.packageName }
                .filter { it.packageName !in exclude }
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { AppInfo(it.packageName, pm.getApplicationLabel(it).toString()) }
                .sortedBy { it.label.lowercase() }
                .toList()
            // Warm the cache so the grid does not hit PackageManager per item.
            apps.forEach { labelCache.putIfAbsent(it.packageName, it.label) }
            apps
        }

    fun getPackageLabel(packageName: String): String =
        labelCache.getOrPut(packageName) {
            try {
                val info = context.packageManager.getApplicationInfo(packageName, 0)
                context.packageManager.getApplicationLabel(info).toString()
            } catch (e: Exception) {
                packageName
            }
        }

    private fun getConnectedExternalDevices() {
        val inputManager = context.getSystemService(INPUT_SERVICE) as InputManager
        val deviceIds = inputManager.inputDeviceIds
        val devices = HashMap<Int, String>()
        deviceIds.forEach { deviceId ->
            val device = inputManager.getInputDevice(deviceId)
            device?.let {
                if (it.isExternal) {
                    devices[it.id] = getDeviceName(it)
                }
            }
        }
        _connectedDevices.value = devices
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {
                val device = inputManager.getInputDevice(deviceId)
                device?.let {
                    if (it.isExternal) {
                        _connectedDevices.value =
                            _connectedDevices.value.plus(it.id to getDeviceName(it))
                    }
                }
            }

            override fun onInputDeviceRemoved(deviceId: Int) {
                _connectedDevices.value = _connectedDevices.value.minus(deviceId)

            }

            override fun onInputDeviceChanged(deviceId: Int) {
                val device = inputManager.getInputDevice(deviceId)
                device?.let {
                    if (it.isExternal) {
                        _connectedDevices.value =
                            _connectedDevices.value.plus(it.id to getDeviceName(it))
                    }
                }
            }
        }
        deviceListener = listener
        inputManager.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
    }

    private fun getDeviceName(inputDevice: InputDevice): String {
        return inputDevice.name.takeIf { name -> name.isNotEmpty() } ?: when (inputDevice.sources) {
            InputDevice.SOURCE_MOUSE -> "Mouse"
            InputDevice.SOURCE_KEYBOARD -> "Keyboard"
            else -> "Unknown Device"
        }

    }

    suspend fun getPackageIcon(packageName: String): Drawable {
        return withContext(Dispatchers.IO) {
            try {
                return@withContext context.packageManager.getApplicationIcon(packageName)
            } catch (e: Exception) {
                return@withContext context.getDrawable(R.drawable.apk_document)!!
            }
        }
    }

    private fun getShizukuBinder() {
        Shizuku.getBinder()?.let {
            _shizukuState.value = UiState.ShizukuRunning(it)
        } ?: run {
            _shizukuState.value = UiState.ShizukuNotRunning
        }
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        if (granted) {
            Shizuku.getBinder()?.let {
                _shizukuState.value = UiState.ShizukuRunning(it)
            } ?: run { _shizukuState.value = UiState.ShizukuNotRunning }
        }
    }

    fun requestShizukuPermission(): Boolean {
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            return false
        }
        // Request the permission
        Shizuku.requestPermission(SHIZUKU_REQ_CODE)
        return true
    }

    fun openGithub() {
        openUrl(GitHubReleaseClient.REPO_URL)
    }

    fun openUrl(url: String) {
        val i = Intent(Intent.ACTION_VIEW)
        i.setData(Uri.parse(url))
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }

    /**
     * 手动检查更新：查询本仓库的最新 Release（或标签），与本地 versionName 比较。
     *
     * 不做后台轮询、不自动下载；查询只由用户点击触发，避免在游戏过程中产生任何网络开销。
     */
    fun checkForUpdates() {
        if (_updateState.value == UpdateState.Checking) return
        _updateState.value = UpdateState.Checking
        viewModelScope.launch {
            val current = currentVersionName()
            _updateState.value = when (val result = GitHubReleaseClient.latestTag()) {
                is GitHubReleaseClient.Result.Success -> {
                    val latest = result.tag
                    if (VersionComparator.isNewer(current, latest)) {
                        UpdateState.Available(
                            current = current,
                            latest = VersionComparator.normalize(latest),
                            releaseUrl = result.htmlUrl,
                            apkUrl = result.apkUrl,
                            notes = result.notes,
                        )
                    } else {
                        UpdateState.UpToDate(current)
                    }
                }
                is GitHubReleaseClient.Result.Failure -> UpdateState.Failed(result.message)
            }
        }
    }

    /**
     * 下载并安装检测到的新版本。
     *
     * 走系统 DownloadManager 下载 GitHub Release 里的 APK，再用 FileProvider 拉起安装器；
     * 没有 APK 直链（只有标签）或未授予安装权限时，分别回退到 Release 页面与授权页。
     */
    fun downloadAndInstall() {
        val available = _updateState.value as? UpdateState.Available ?: return
        val apkUrl = available.apkUrl
        if (apkUrl.isNullOrBlank()) {
            openUrl(available.releaseUrl)
            return
        }
        if (!apkInstaller.canInstall()) {
            apkInstaller.openInstallPermission()
            _updateState.value = UpdateState.Failed("请在系统设置里允许本应用安装应用，然后再点一次「下载并安装」")
            return
        }
        _updateState.value = UpdateState.Downloading(-1)
        viewModelScope.launch {
            apkInstaller.download(available.latest, apkUrl) { progress ->
                _updateState.value = UpdateState.Downloading(progress)
            }.onSuccess { file ->
                _updateState.value = available
                runCatching { apkInstaller.install(file) }
                    .onFailure {
                        _updateState.value = UpdateState.Failed("无法启动安装：${it.message}")
                    }
            }.onFailure {
                _updateState.value = UpdateState.Failed("下载失败：${it.message}")
            }
        }
    }

    private fun currentVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().removeSuffix("-debug").ifBlank { "0.0" }

    sealed interface UiState {
        data object ShizukuNotRunning : UiState
        data class ShizukuRunning(val binder: IBinder) : UiState
    }

    override fun onCleared() {
        Shizuku.removeRequestPermissionResultListener(this)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        deviceListener?.let {
            (context.getSystemService(INPUT_SERVICE) as? InputManager)
                ?.unregisterInputDeviceListener(it)
        }
        deviceListener = null
        Log.i("MainActivityViewModel", "onCleared: main viewModelCleared ")
    }

    /**
     * Replaces the active profile's layout with a bundled preset.
     *
     * Applying used to *append* the preset items, so applying the same preset
     * twice stacked a second copy of every button on top of the first one.
     * A preset describes a complete layout, so it replaces instead.
     *
     * @return null on success, otherwise a user-facing error message.
     */
    fun applyPreset(packageName: String, presetId: String): String? {
        val preset = _presets.value.firstOrNull { it.id == presetId }
            ?: return context.getString(R.string.main_preset_not_found)
        // A preset's normalized coordinates are relative to the space the items
        // are drawn in, which is the overlay window -- not the Activity's display
        // metrics. The two disagree about system bars and about orientation, and
        // the overlay is the one the layout is persisted against.
        val viewport = stateManager.viewportForLayout
        return try {
            val items = presetRepository.toRuntimeItems(
                preset = preset,
                screenWidth = viewport.x.toInt(),
                screenHeight = viewport.y.toInt(),
            )
            if (items.isEmpty()) {
                context.getString(R.string.main_preset_empty)
            } else {
                stateManager.replaceAllItems(items)
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply preset $presetId to $packageName", e)
            context.getString(R.string.main_preset_failed, e.message ?: "")
        }
    }

    /**
     * @return false when the package has no launch intent or the launch threw
     * (uninstalled / disabled app), so the caller can tell the user.
     */
    fun launchPackage(
        packageName: String,
        packageManager: PackageManager,
        isServiceRunning: Boolean
    ): Boolean {
        val packageIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return false
        return try {
            packageIntent.flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            context.startActivity(packageIntent)
            if (!isServiceRunning) {
                launchFloatingBubbleService(packageName)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "failed to launch package", e)
            false
        }
    }

    private fun launchFloatingBubbleService(packageName: String) {
        val serviceIntent = Intent(
            context,
            FloatingBubbleService::class.java
        )
        serviceIntent.putExtra(FloatingBubbleService.INTENT_EXTRA_PACAKAGE, packageName)
        context.startForegroundService(serviceIntent)
    }
}
