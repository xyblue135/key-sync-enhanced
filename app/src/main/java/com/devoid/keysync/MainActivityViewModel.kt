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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devoid.keysync.data.local.DataStoreManager
import com.devoid.keysync.data.mapping.MappingPreset
import com.devoid.keysync.data.mapping.MappingPresetRepository
import com.devoid.keysync.model.AppConfig
import com.devoid.keysync.model.Profile
import com.devoid.keysync.model.SwapPair
import com.devoid.keysync.service.FloatingBubbleService
import com.devoid.keysync.service.FloatingWindowStateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    private val _appConfig = MutableStateFlow(AppConfig.Default)
    val appConfig = _appConfig.asStateFlow()


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
    /** 悬浮窗透明度（全局设置），设置页用；改动立即持久化。 */
    val overlayOpacity: StateFlow<Float> = stateManager.overlayOpacity

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
            _appConfig.value = dataStoreManager.getKeyConfig(DataStoreManager.KEYS_CONFIG).first()
        }
    }

    fun saveKeyConfig(newAppConfig: AppConfig) {
        // The 3 keyCode fields used to live on the global AppConfig; they
        // are now per-button (DraggableItem.FixedKey.keyCode) and editable
        // from the floating window's gear-icon dialog. Settings only manages
        // the remaining AppConfig fields, so just persist into the active
        // profile via FloatingWindowStateManager.
        _appConfig.value = newAppConfig
        stateManager.saveAppConfig(newAppConfig)
        viewModelScope.launch {
            dataStoreManager.save(DataStoreManager.KEYS_CONFIG, newAppConfig)
        }
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

    fun setSwapPairs(profileId: String, pairs: List<SwapPair>) {
        stateManager.setSwapPairs(profileId, pairs)
    }

    fun setWasdHumanization(enabled: Boolean) = stateManager.setWasdHumanization(enabled)
    fun setKeyHumanization(enabled: Boolean) = stateManager.setKeyHumanization(enabled)
    fun setHumanizationStrength(value: Float) = stateManager.setHumanizationStrength(value)
    fun setOverlayOpacity(value: Float) = stateManager.saveOverlayOpacity(value)

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
        val url = "https://github.com/aka-munan/keysync"
        val i = Intent(Intent.ACTION_VIEW)
        i.setData(Uri.parse(url))
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }

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
        val metrics = context.resources.displayMetrics
        return try {
            val items = presetRepository.toRuntimeItems(
                preset = preset,
                screenWidth = metrics.widthPixels,
                screenHeight = metrics.heightPixels,
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
