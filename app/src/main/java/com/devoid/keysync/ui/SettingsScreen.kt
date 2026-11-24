package com.devoid.keysync.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.devoid.keysync.R
import com.devoid.keysync.model.AppConfig
import com.devoid.keysync.model.DraggableItem
import com.devoid.keysync.model.Profile
import com.devoid.keysync.model.ProfileAssist
import com.devoid.keysync.model.PlayMode
import com.devoid.keysync.model.DefaultPlayModes
import com.devoid.keysync.model.QuickMeleeMacro
import com.devoid.keysync.model.assist
import com.devoid.keysync.model.defaultQuickSwapSteps
import com.devoid.keysync.model.defaultScallopDashSteps
import com.devoid.keysync.model.defaultScallopSteps
import com.devoid.keysync.model.defaultShieldSteps
import com.devoid.keysync.model.KeySeqMacro
import com.devoid.keysync.model.KeySeqStep
import com.devoid.keysync.model.keyCodeForName
import com.devoid.keysync.model.keyName
import com.devoid.keysync.model.keySequenceToText
import com.devoid.keysync.model.parseKeySequence
import com.devoid.keysync.model.withAssist
import com.devoid.keysync.model.TouchMode
import com.devoid.keysync.util.keyCodeToString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    appConfig: AppConfig = AppConfig.Default,
    profiles: List<Profile> = emptyList(),
    activeProfileId: String? = null,
    onSave: (AppConfig) -> Unit = {},
    onCreateProfile: (String) -> Unit = {},
    onExportAllProfiles: () -> String = { "" },
    onSwitchProfile: (String) -> Unit = {},
    onRenameProfile: (String, String) -> Unit = { _, _ -> },
    onDeleteProfile: (String) -> Unit = {},
    onDuplicateProfile: (String) -> Unit = {},
    onExportProfile: (String) -> String? = { null },
    onImportProfile: (String) -> String? = { null },
    onSaveProfileAssist: (String, ProfileAssist) -> Unit = { _, _ -> },
    overlayOpacity: Float = 0.5f,
    onSetOverlayOpacity: (Float) -> Unit = {},
    /** 光标渲染刷新率（Hz），默认 60；只影响显示。 */
    cursorRenderHz: Int = 60,
    onSetCursorRenderHz: (Int) -> Unit = {},
    statusBarVisible: Boolean = true,
    onSetStatusBarVisible: (Boolean) -> Unit = {},
    mouseJumpThresholdPx: Int = 240,
    onSetMouseJumpThreshold: (Int) -> Unit = {},
    mouseModeLatch: Boolean = true,
    onSetMouseModeLatch: (Boolean) -> Unit = {},
    screenSizeCacheEnabled: Boolean = true,
    onSetScreenSizeCache: (Boolean) -> Unit = {},
    mouseGuardEnabled: Boolean = true,
    onSetMouseGuardEnabled: (Boolean) -> Unit = {},
    discardDragEnabled: Boolean = false,
    onSetDiscardDragEnabled: (Boolean) -> Unit = {},
    discardDragSpeed: Float = 25f,
    onSetDiscardDragSpeed: (Float) -> Unit = {},
    fScrollControlEnabled: Boolean = true,
    onSetFScrollControlEnabled: (Boolean) -> Unit = {},
    fScrollSwipePx: Float = 120f,
    onSetFScrollSwipePx: (Float) -> Unit = {},
    fScrollInvert: Boolean = false,
    onSetFScrollInvert: (Boolean) -> Unit = {},
    fScrollStepDelayMs: Int = 7,
    onSetFScrollStepDelayMs: (Int) -> Unit = {},
    fScrollUpKeyCode: Int = 0,
    fScrollDownKeyCode: Int = 0,
    onSetFScrollTriggerKeys: (Int, Int) -> Unit = { _, _ -> },
    playModes: List<PlayMode> = DefaultPlayModes,
    currentPlayModeIndex: Int = 1,
    onSetCurrentPlayMode: (Int) -> Unit = {},
    onUpdatePlayMode: (Int, PlayMode) -> Unit = { _, _ -> },
    onAddPlayMode: () -> Unit = {},
    mapMouseToggleEnabled: Boolean = true,
    onSetMapMouseToggleEnabled: (Boolean) -> Unit = {},
    fCursorClickEnabled: Boolean = true,
    onSetFCursorClickEnabled: (Boolean) -> Unit = {},
    fCursorClickWindowMs: Int = 1000,
    onSetFCursorClickWindowMs: (Int) -> Unit = {},
    tabEscAimEnabled: Boolean = true,
    onSetTabEscAimEnabled: (Boolean) -> Unit = {},
    tabEscAimDelayMs: Int = 110,
    onSetTabEscAimDelayMs: (Int) -> Unit = {},
    escAfterTapEnabled: Boolean = false,
    onSetEscAfterTapEnabled: (Boolean) -> Unit = {},
    escAfterTapDelayMs: Int = 100,
    onSetEscAfterTapDelayMs: (Int) -> Unit = {},
    escAfterTapIntervalMs: Int = 50,
    onSetEscAfterTapIntervalMs: (Int) -> Unit = {},
    onStopOverlay: () -> Unit = {},
    wasdHumanization: Boolean = false,
    keyHumanization: Boolean = false,
    humanizationStrength: Float = 0f,
    humanizationInterval: Long = 1000L,
    wasdJitterRandomEnabled: Boolean = true,
    wasdJitterMinMs: Int = 200,
    wasdJitterMaxMs: Int = 300,
    wasdSway: Float = 50f,
    wasdTransition: Boolean = true,
    wasdTransitionMs: Int = 80,
    onSetWasdHumanization: (Boolean) -> Unit = {},
    onSetKeyHumanization: (Boolean) -> Unit = {},
    onSetHumanizationStrength: (Float) -> Unit = {},
    onSetHumanizationInterval: (Long) -> Unit = {},
    onSetWasdJitterRandomEnabled: (Boolean) -> Unit = {},
    onSetWasdJitterRange: (Int, Int) -> Unit = { _, _ -> },
    onSetWasdSway: (Float) -> Unit = {},
    onSetWasdTransition: (Boolean) -> Unit = {},
    onSetWasdTransitionMs: (Int) -> Unit = {},
    onNavigateBack: () -> Unit = {},
    onNavigateAbout: () -> Unit = {}
) {
    // Local working copy. Re-seeded only when the backing config actually
    // changes underneath us (first load, or a save we performed ourselves).
    // Every control on this screen edits this copy, including the
    // profile-switch master switch — writing that one straight through used to
    // change `appConfig`, which re-seeded this state and silently threw away
    // any unsaved slider / theme / touch-mode edits.
    var newConfig by remember(appConfig) { mutableStateOf(appConfig) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    val activeProfile = profiles.firstOrNull { it.id == activeProfileId }
    // 「按键辅助」草稿：和 newConfig 一样先收在本地，点保存才写回预设。
    var assistDraft by remember(activeProfileId, activeProfile) {
        mutableStateOf(activeProfile?.assist() ?: ProfileAssist())
    }
    var pendingSwitch by remember { mutableStateOf<(() -> Unit)?>(null) }

    val assistDirty = activeProfile != null && assistDraft != activeProfile.assist()
    val isDirty = newConfig != appConfig || assistDirty

    /** 保存当前页面的全部草稿（应用配置 + 当前预设的按键辅助）。 */
    fun saveStaged() {
        onSave(newConfig)
        if (activeProfile != null && assistDraft != activeProfile.assist()) {
            onSaveProfileAssist(activeProfile.id, assistDraft)
        }
    }

    /** 切换预设/新建会换掉草稿归属，有未保存修改时先确认。 */
    fun runAfterDirtyCheck(action: () -> Unit) {
        if (isDirty) pendingSwitch = action else action()
    }

    // Leaving with unsaved edits used to be silent: the back arrow just
    // popped the stack and every pending change vanished.
    BackHandler(enabled = isDirty) { showDiscardConfirm = true }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(stringResource(R.string.settings_discard_title)) },
            text = { Text(stringResource(R.string.settings_discard_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardConfirm = false
                    onNavigateBack()
                }) { Text(stringResource(R.string.settings_discard_leave)) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text(stringResource(R.string.settings_discard_keep))
                }
            }
        )
    }

    if (pendingSwitch != null) {
        AlertDialog(
            onDismissRequest = { pendingSwitch = null },
            title = { Text("有未保存的修改") },
            text = { Text("切换预设或新建会离开当前预设。要先保存这里的修改吗？") },
            confirmButton = {
                TextButton(onClick = {
                    val action = pendingSwitch
                    pendingSwitch = null
                    saveStaged()
                    action?.invoke()
                }) { Text("保存并继续") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val action = pendingSwitch
                        pendingSwitch = null
                        action?.invoke()
                    }) { Text("放弃修改") }
                    TextButton(onClick = { pendingSwitch = null }) { Text("取消") }
                }
            }
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.settings_reset_title)) },
            text = { Text(stringResource(R.string.settings_reset_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    // Saved straight away: this reads as an action button, and
                    // only staging the defaults made it look like it did
                    // nothing until the user also pressed 保存.
                    newConfig = AppConfig.Default
                    onSave(AppConfig.Default)
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    var detail by remember { mutableStateOf<SettingsDetail?>(null) }
    val pagerState = rememberPagerState(pageCount = { 4 })
    val pageScope = rememberCoroutineScope()
    // 齿轮详情页：系统返回先回到「模式」，不直接退出设置。
    // 只清空 detail，翻页统一交给下面的 LaunchedEffect，避免两处同时动画互相取消后卡在详情页。
    BackHandler(enabled = pagerState.currentPage == 3) {
        detail = null
    }
    // 详情页只能由齿轮进入；detail 清空后自动退回「模式」。
    // 只以 detail 作为 key：若把 currentPage 也放进 key，翻页过程中 key 变化会取消动画，停在空白页。
    LaunchedEffect(detail) {
        if (detail == null && pagerState.currentPage == 3) {
            pagerState.animateScrollToPage(1)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(text = detail?.title ?: stringResource(R.string.settings_title))
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (pagerState.currentPage == 3) {
                            detail = null
                        } else if (isDirty) {
                            showDiscardConfirm = true
                        } else {
                            onNavigateBack()
                        }
                    }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                }, actions = {
                    Button(
                        modifier = Modifier.padding(end = 8.dp),
                        enabled = isDirty,
                        onClick = {
                            saveStaged()
                            // 保存后停留在当前页，方便继续调整；返回上一级只走左上角箭头。
                        }
                    ) {
                        Text(stringResource(R.string.settings_save))
                    }
                })
        }
    ) { innerPadding ->
        // 设置页按主题分区，顶部标签或左右滑动切换；每页各自滚动。
        val pageTitles = listOf("预设", "模式", "维护")
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            ScrollableTabRow(
                // 详情页（page 3）仍高亮「模式」，让用户感觉只是进了子页面。
                selectedTabIndex = if (pagerState.currentPage == 3) 1 else pagerState.currentPage,
                edgePadding = 8.dp,
            ) {
                pageTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            pageScope.launch { pagerState.animateScrollToPage(index) }
                        },
                        text = { Text(title) },
                    )
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = pagerState.currentPage < 2,
            ) { page ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (page == 0) {
                        ProfileManagerCard(
                    profiles = profiles,
                    activeProfileId = activeProfileId,
                    onCreate = { name -> runAfterDirtyCheck { onCreateProfile(name) } },
                    onExportAllProfiles = onExportAllProfiles,
                    onSwitch = { id -> runAfterDirtyCheck { onSwitchProfile(id) } },
                    onRename = onRenameProfile,
                    onDelete = onDeleteProfile,
                    onDuplicate = onDuplicateProfile,
                    onExport = onExportProfile,
                    onImport = onImportProfile,
                        )
                    }
                    if (page == 1) {
                        PlayModeCard(
                            modes = playModes,
                            currentIndex = currentPlayModeIndex,
                            onSelect = onSetCurrentPlayMode,
                            onUpdate = onUpdatePlayMode,
                            onAdd = onAddPlayMode,
                            onOpenDetail = { d ->
                                detail = d
                                pageScope.launch { pagerState.animateScrollToPage(3) }
                            },
                            tabEscAimEnabled = tabEscAimEnabled,
                            onSetTabEscAimEnabled = onSetTabEscAimEnabled,
                            mapMouseToggleEnabled = mapMouseToggleEnabled,
                            onSetMapMouseToggleEnabled = onSetMapMouseToggleEnabled,
                            mouseGuardEnabled = mouseGuardEnabled,
                            onSetMouseGuardEnabled = onSetMouseGuardEnabled,
                        )
                    }
                    if (page == 3 && detail == SettingsDetail.DISPLAY) {
                        SettingsSectionCard(
                            title = "显示",
                    scope = SettingsScope.GLOBAL,
                    hint = "全局 · 立即生效并保存",
                    initiallyExpanded = true,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.slider_overlay_opacity),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text("${(overlayOpacity * 100).roundToInt()} %")
                    }
                    Slider(
                        value = overlayOpacity,
                        valueRange = 0f..1f,
                        steps = 9,
                        onValueChange = onSetOverlayOpacity
                    )
                    Text(
                        "0% 时按键完全不显示；改动立即生效，无需点保存。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "光标渲染刷新率",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text("${cursorRenderHz} Hz")
                    }
                    Slider(
                        value = cursorRenderHz.toFloat().coerceIn(15f, 240f),
                        valueRange = 15f..240f,
                        onValueChange = { onSetCursorRenderHz(it.roundToInt()) }
                    )
                    Text(
                        "默认 60Hz。只影响光标圆点的显示刷新；鼠标坐标、视角、点击、滚轮和宏始终按事件全速注入，" +
                            "不受这个值限制。上限 240Hz，实际还受屏幕刷新率限制；调低可减少悬浮层重绘。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "顶部状态提示条",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "屏幕最上边那行「预设名 · 模式 · 最近输入」。关掉后游戏画面上不再出现，通知栏也有同名按钮，改动立即生效并会保存。",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = statusBarVisible,
                            onCheckedChange = onSetStatusBarVisible
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
                SettingsSectionCard(
                    title = "按钮与操作",
                    scope = SettingsScope.PRESET,
                    hint = "跟随当前预设 · 改动需点右上角保存",
                    initiallyExpanded = true,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.settings_button_size),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(stringResource(R.string.settings_button_size_value, (newConfig.buttonScale * 100).roundToInt()))
                    }
                    // 0.5 lower bound: at 0 the overlay buttons shrink to
                    // nothing, leaving no handle to drag or remove them
                    // again, and no way to recover short of a reinstall.
                    Slider(
                        value = newConfig.buttonScale,
                        valueRange = 0.5f..2f,
                        steps = 14,
                        onValueChange = {
                            newConfig = newConfig.copy(buttonScale = it)
                        })
                    Text(
                        "所有按钮一起缩放；单个按钮可在编辑态双击它单独调整。改动需点保存生效。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.slider_pointer_sensitivity),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text("${(newConfig.pointerSensitivity * 100).roundToInt()} %")
                    }
                    Slider(
                        value = newConfig.pointerSensitivity,
                        valueRange = 0.1f..1f,
                        steps = 8,
                        onValueChange = {
                            newConfig = newConfig.copy(pointerSensitivity = it)
                        })
                    Text(
                        "跟随当前预设保存，切换预设会各自恢复；改动需点保存生效。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                    }
                    if (page == 3 && detail in setOf(
                            SettingsDetail.MACROS,
                            SettingsDetail.ASSIST,
                        )
                    ) {
                        ProfileAssistCard(
                            profile = activeProfile,
                            draft = assistDraft,
                            onDraftChange = { assistDraft = it },
                            title = detail?.title ?: "按键辅助",
                            sections = when (detail) {
                                SettingsDetail.MACROS -> setOf(AssistSection.MACROS)
                                else -> setOf(AssistSection.ASSIST)
                            },
                        )
                    }
                    if (page == 3 && detail in setOf(
                            SettingsDetail.MOUSE_GUARD,
                            SettingsDetail.ESC_TAB_AIM,
                            SettingsDetail.MAP_MOUSE,
                            SettingsDetail.F_CURSOR,
                            SettingsDetail.DISCARD,
                            SettingsDetail.SCROLL,
                        )
                    ) {
                        SettingsSectionCard(
                            title = detail?.title ?: "鼠标与兼容",
                    scope = SettingsScope.GLOBAL,
                    hint = "全局 · 立即生效并保存",
                    initiallyExpanded = true,
                ) {
                    if (detail == SettingsDetail.MOUSE_GUARD) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "单帧断点阈值",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text("${mouseJumpThresholdPx}px")
                    }
                    Slider(
                        value = mouseJumpThresholdPx.toFloat().coerceIn(40f, 800f),
                        valueRange = 40f..800f,
                        steps = 37,
                        onValueChange = { onSetMouseJumpThreshold(it.roundToInt()) }
                    )
                    Text(
                        "鼠标单帧位移超过该值就当作光标跳变丢弃，不参与瞄准或光标移动。默认 240px；" +
                            "正常手速远低于它，投屏端或高 DPI 鼠标可调高。逐事件判定，本身不引入延迟。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "锁定鼠标输入模式",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "开启后同一段操作只采用一种解析方式（相对轴或绝对坐标），避免设备偶尔上报相对轴时" +
                                    "速度忽快忽慢；关闭后每个鼠标事件各自判断。默认开启，切换设备后重新选择。",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = mouseModeLatch,
                            onCheckedChange = onSetMouseModeLatch
                        )
                    }
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "缓存屏幕尺寸",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "缓存当前屏幕尺寸，避免每个鼠标事件都查询系统窗口；默认开启。" +
                                    "屏幕旋转或分辨率变化时自动失效并重新读取，不会用旧尺寸算错坐标。",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = screenSizeCacheEnabled,
                            onCheckedChange = onSetScreenSizeCache
                        )
                    }
                    Text(
                        "关闭「防止大幅甩动导致视角问题」总开关后，以上三项都不生效。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    }
                    if (detail == SettingsDetail.ESC_TAB_AIM) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "ESC / Tab 关闭界面后回瞄准",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "按物理 ESC 或 Tab 关掉背包/地图后，等待设定时间切回射击模式，再等 150ms 幂等重建准心触点，" +
                                    "避免界面切换瞬间注入的触点被游戏丢掉。默认 110ms；已在射击模式时不动作；不吞按键；" +
                                    "光标模式下按 ` 仍可手动切回。",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = tabEscAimEnabled,
                            onCheckedChange = onSetTabEscAimEnabled
                        )
                    }
                    if (tabEscAimEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "回瞄准延迟",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text("${tabEscAimDelayMs} ms", style = MaterialTheme.typography.bodyMedium)
                        }
                        Slider(
                            value = tabEscAimDelayMs.toFloat().coerceIn(50f, 1000f),
                            valueRange = 50f..1000f,
                            steps = 94,
                            onValueChange = { onSetTabEscAimDelayMs(it.roundToInt()) }
                        )
                        Text(
                            "界面关闭动画较长或回瞄准总被吞时可调高；默认 110ms。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "ESC 按下后批量点击落点",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "物理 ESC 本体映射照常触发；按下后等待设定时间，再依次点击为 ESC 配置的全部「连按落点」，" +
                                    "落点之间间隔可调。适合关背包后补点两个位置。落点在 ESC 的齿轮里或工具菜单的「ESC 落点」添加。",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = escAfterTapEnabled,
                            onCheckedChange = onSetEscAfterTapEnabled
                        )
                    }
                    if (escAfterTapEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "首次点击延迟",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text("${escAfterTapDelayMs} ms", style = MaterialTheme.typography.bodyMedium)
                        }
                        Slider(
                            value = escAfterTapDelayMs.toFloat().coerceIn(0f, 1000f),
                            valueRange = 0f..1000f,
                            steps = 19,
                            onValueChange = { onSetEscAfterTapDelayMs(it.roundToInt()) }
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "落点间隔",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text("${escAfterTapIntervalMs} ms", style = MaterialTheme.typography.bodyMedium)
                        }
                        Slider(
                            value = escAfterTapIntervalMs.toFloat().coerceIn(0f, 500f),
                            valueRange = 0f..500f,
                            steps = 9,
                            onValueChange = { onSetEscAfterTapIntervalMs(it.roundToInt()) }
                        )
                        Text(
                            "默认：按下后 100ms 点第一个，之后每 50ms 点下一个；每次按下保持 45–95ms。" +
                                "快速连按 ESC 会取消上一轮并从最新一次重新计时。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    }
                    if (detail == SettingsDetail.MAP_MOUSE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "M 键开关地图并切光标",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "第一下按 M 正常触发 M 映射（开图）并立即切出鼠标；第二下不再按 M，" +
                                    "改为点击 ESC 按钮的映射位置（按下约 50–125ms 后抬起）再收回鼠标。" +
                                    "预设里没有 ESC 按钮时只切鼠标模式。",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = mapMouseToggleEnabled,
                            onCheckedChange = onSetMapMouseToggleEnabled
                        )
                    }
                    }
                    if (detail == SettingsDetail.F_CURSOR) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "F 交互后左键唤出光标",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "射击模式下按 F 后的设定时间内，第一次点鼠标左键会直接切出光标，并把这一下变成" +
                                    "光标位置点击（适合开箱子/拾取）；默认 1000ms；每轮只触发一次，狂按 F 不会叠加。" +
                                    "按 Tab/ESC 回射击模式后立即失效。",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = fCursorClickEnabled,
                            onCheckedChange = onSetFCursorClickEnabled
                        )
                    }
                    if (fCursorClickEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "左键触发间隔",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text("${fCursorClickWindowMs} ms", style = MaterialTheme.typography.bodyMedium)
                        }
                        Slider(
                            value = fCursorClickWindowMs.toFloat().coerceIn(100f, 3000f),
                            valueRange = 100f..3000f,
                            steps = 28,
                            onValueChange = { onSetFCursorClickWindowMs((it / 100f).roundToInt() * 100) }
                        )
                        Text(
                            "按 F 后多久内点左键仍算唤出光标；默认 1000ms，可调 100–3000ms（100ms 步进）。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    }
                    if (detail == SettingsDetail.DISCARD) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Alt+D 拖拽丢弃",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "光标模式下按住 Alt 再按 D：在光标处按住物品，分 12 步拖到编辑态放置的「丢弃点」" +
                                    "（默认 ×25 速，约为旧默认的一半），停顿 70ms 后松开，再按同样速度把光标送回原位。" +
                                    "射击模式、还没出现过鼠标、或没按住 Alt 时不生效。",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = discardDragEnabled,
                            onCheckedChange = onSetDiscardDragEnabled
                        )
                    }
                    if (discardDragEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "拖拽速度",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "×${String.format(java.util.Locale.US, "%.0f", discardDragSpeed)}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Slider(
                            value = discardDragSpeed.coerceIn(1f, 100f),
                            valueRange = 1f..100f,
                            steps = 98,
                            onValueChange = {
                                onSetDiscardDragSpeed(it.roundToInt().toFloat())
                            }
                        )
                        Text(
                            "1 = 原始速度（每步 14ms），25 = 25 倍速（默认），100 = 100 倍速（约 2ms 走完）。" +
                                "只影响移动过程；按下前 40ms、松开前 70ms 的停顿保持不变。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    }
                    if (detail == SettingsDetail.SCROLL) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "F 光标滚轮操控",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "F 后左键唤出光标时接管滚轮：直接在光标当前位置向上/下滑一小段，每格结束复位，" +
                                    "不再区分左右半屏，也不需要放置锚点。按 ESC / Tab 或切回射击模式后立即恢复原来的滚轮映射。",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = fScrollControlEnabled,
                            onCheckedChange = onSetFScrollControlEnabled
                        )
                    }
                    if (fScrollControlEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "每格滑动距离",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "${fScrollSwipePx.roundToInt()} px",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Slider(
                            value = fScrollSwipePx.coerceIn(40f, 400f),
                            valueRange = 40f..400f,
                            steps = 17,
                            onValueChange = { onSetFScrollSwipePx(it.roundToInt().toFloat()) }
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "每格延迟",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text("${fScrollStepDelayMs} ms", style = MaterialTheme.typography.bodyMedium)
                        }
                        Slider(
                            value = fScrollStepDelayMs.toFloat().coerceIn(2f, 40f),
                            valueRange = 2f..40f,
                            steps = 37,
                            onValueChange = { onSetFScrollStepDelayMs(it.roundToInt()) }
                        )
                        Text(
                            "每格滑动分 5 小步，这是每小步的间隔；默认 7ms。慢一点更容易被游戏识别，快一点更跟手。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("方向反转", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "默认滚轮向下 = 手指向下滑；如果列表方向相反就打开。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = fScrollInvert, onCheckedChange = onSetFScrollInvert)
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text("上 / 下滑触发键", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "默认就是鼠标滚轮向上 / 向下；也可以改成其他鼠标键。只在光标模式接管滚轮期间生效。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("向上滑", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            MouseInputPicker(fScrollUpKeyCode) {
                                onSetFScrollTriggerKeys(it, fScrollDownKeyCode)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("向下滑", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            MouseInputPicker(fScrollDownKeyCode) {
                                onSetFScrollTriggerKeys(fScrollUpKeyCode, it)
                            }
                        }
                    }
                    }
                }
                    }
                    if (page == 3 && detail == SettingsDetail.HUMANIZATION) {
                        SettingsSectionCard(
                            title = "按键拟人化",
                    scope = SettingsScope.GLOBAL,
                    hint = "全局 · 立即生效并保存",
                    initiallyExpanded = true,
                ) {
                        Text(
                            "给注入的触控位置加随机抖动，避免被识别成机械死板的操作。全局生效，强度最低 1，最高 100。",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("WASD 拟人化", style = MaterialTheme.typography.titleMedium)
                            }
                            Switch(checked = wasdHumanization, onCheckedChange = onSetWasdHumanization)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("其他按键拟人化（开火 / 自定义等）", style = MaterialTheme.typography.titleMedium)
                            }
                            Switch(checked = keyHumanization, onCheckedChange = onSetKeyHumanization)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "偏移强度（1–100）",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text("${humanizationStrength.roundToInt()} / 100")
                        }
                        Slider(
                            enabled = wasdHumanization || keyHumanization,
                            value = humanizationStrength.coerceIn(1f, 100f),
                            valueRange = 1f..100f,
                            steps = 98,
                            onValueChange = { onSetHumanizationStrength(it.roundToInt().toFloat()) }
                        )
                        Text(
                            "默认 50。普通按键落点最大 8px；WASD 摆头由下方独立滑杆控制。两个开关都关着时，下面三项不生效。",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "WASD 方向晃动（1–100）",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text("${wasdSway.roundToInt()} / 100")
                        }
                        Slider(
                            enabled = wasdHumanization,
                            value = wasdSway.coerceIn(1f, 100f),
                            valueRange = 1f..100f,
                            steps = 98,
                            onValueChange = { onSetWasdSway(it.roundToInt().toFloat()) }
                        )
                        Text(
                            "只影响 WASD 摇杆按住时的轻微摆头，与整体偏移强度独立。" +
                                "满值约 ±1.5°，默认 50；跑动时觉得方向飘就把它调小。",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("WASD 换向 / 疾跑推杆", style = MaterialTheme.typography.titleMedium)
                            }
                            Switch(
                                checked = wasdTransition,
                                onCheckedChange = onSetWasdTransition
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "过渡时长",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text("$wasdTransitionMs ms")
                        }
                        Slider(
                            enabled = wasdTransition,
                            value = wasdTransitionMs.toFloat().coerceIn(40f, 200f),
                            valueRange = 40f..200f,
                            steps = 15,
                            onValueChange = { onSetWasdTransitionMs(it.roundToInt()) }
                        )
                    Text(
                        "换向时补 2–8 个中间点：角度 ≥ 90°（如 A↔D、W↔S）走直线穿过中心，中段推动量保留 18%；" +
                            "小角度仍沿圆弧。按下/松开 Shift 时沿同一方向缓慢拉到疾跑半径，而不是瞬移；松手立即停止。" +
                            "默认 60ms；调小更跟手，调大更像拇指推杆。关闭后换向与疾跑切换都恢复瞬移。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("WASD 随机偏移间隔", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "打开后，WASD 抖动间隔在下面的区间内随机，默认 200–300ms；" +
                                        "关闭时回落到下面的通用间隔。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = wasdJitterRandomEnabled,
                                onCheckedChange = onSetWasdJitterRandomEnabled
                            )
                        }
                        if (wasdJitterRandomEnabled) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "间隔下限",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text("${wasdJitterMinMs} ms")
                            }
                            Slider(
                                value = wasdJitterMinMs.toFloat().coerceIn(50f, 1000f),
                                valueRange = 50f..1000f,
                                onValueChange = {
                                    onSetWasdJitterRange(it.roundToInt(), wasdJitterMaxMs)
                                }
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "间隔上限",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text("${wasdJitterMaxMs} ms")
                            }
                            Slider(
                                value = wasdJitterMaxMs.toFloat().coerceIn(50f, 1000f),
                                valueRange = 50f..1000f,
                                onValueChange = {
                                    onSetWasdJitterRange(wasdJitterMinMs, it.roundToInt())
                                }
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "通用随机偏移间隔（其他按键）",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                if (humanizationInterval >= 1000L)
                                    "${humanizationInterval / 1000f} 秒"
                                else "${humanizationInterval} ms"
                            )
                        }
                        Slider(
                            enabled = keyHumanization,
                            value = humanizationInterval.toFloat().coerceIn(200f, 3000f),
                            valueRange = 200f..3000f,
                            steps = 27,
                            onValueChange = { onSetHumanizationInterval(it.roundToInt().toLong()) }
                        )
                        Text(
                            "用于「其他按键拟人化」：按住普通按键期间按这个间隔轻微抖动；" +
                                "关闭 WASD 随机间隔时，WASD 也使用它。默认 1000ms（约每秒一次）。",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                }
                    }
                    if (page == 2) {
                SettingsSectionCard(
                    title = "维护",
                    scope = SettingsScope.NONE,
                    hint = "低频与危险操作",
                    initiallyExpanded = true,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.settings_delete_data),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                modifier = Modifier.padding(top = 4.dp),
                                text = stringResource(R.string.settings_delete_keymap),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = newConfig.deleteDataOnRemove, onCheckedChange = {
                            newConfig = newConfig.copy(deleteDataOnRemove = it)
                        })
                    }
                    HorizontalDivider()
                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onStopOverlay
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ExitToApp, contentDescription = null)
                            Text(
                                modifier = Modifier.padding(start = 16.dp),
                                text = stringResource(R.string.overlay_close_overlay),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        onClick = { showResetConfirm = true }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Text(
                                modifier = Modifier.padding(start = 16.dp),
                                text = stringResource(R.string.settings_reset_defaults),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = onNavigateAbout) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            Icon(Icons.Rounded.Info, contentDescription = null)
                            Text(
                                modifier = Modifier.padding(start = 16.dp),
                                text = stringResource(R.string.settings_about),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                    }
                }
            }
        }
    }
}

/** 设置页的齿轮详情页：从「模式」里的功能行进入，复用原来的分区内容。 */
private enum class SettingsDetail(val title: String) {
    MACROS("宏"),
    ASSIST("按键辅助"),
    F_CURSOR("F 交互唤出光标"),
    SCROLL("F 光标滚轮操控"),
    DISCARD("Alt+D 拖拽丢弃"),
    HUMANIZATION("拟人化"),
    DISPLAY("显示与操作"),
    MOUSE_GUARD("防止大幅甩动视角问题"),
    ESC_TAB_AIM("ESC / Tab 回瞄准"),
    MAP_MOUSE("M 键地图光标"),
}

/** 按键辅助卡按齿轮拆成两组：辅助 / 宏（快速近战也收在宏里）。 */
private enum class AssistSection { ASSIST, MACROS }

/** 设置分区的作用域标签：预设级跟随当前预设，全局级对所有预设统一生效。 */
private enum class SettingsScope(val label: String) {
    PRESET("预设级"),
    GLOBAL("全局"),
    NONE(""),
}

/**
 * 玩法模式：简易 + 两个可编辑高级槽。模式只做运行时门控，
 * 不覆盖各功能自己的开关，所以来回切换不会丢单项配置。
 */
@Composable
private fun PlayModeCard(
    modes: List<PlayMode>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onUpdate: (Int, PlayMode) -> Unit,
    onAdd: () -> Unit,
    onOpenDetail: (SettingsDetail) -> Unit,
    tabEscAimEnabled: Boolean,
    onSetTabEscAimEnabled: (Boolean) -> Unit,
    mapMouseToggleEnabled: Boolean,
    onSetMapMouseToggleEnabled: (Boolean) -> Unit,
    mouseGuardEnabled: Boolean,
    onSetMouseGuardEnabled: (Boolean) -> Unit,
) {
    SettingsSectionCard(
        title = "玩法模式",
        scope = SettingsScope.GLOBAL,
        hint = "全局 · 立即生效并保存",
        initiallyExpanded = true,
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            modes.forEachIndexed { index, mode ->
                ModeChip(
                    text = mode.name,
                    selected = currentIndex == index,
                    onClick = { onSelect(index) },
                )
            }
            ModeAddChip(onClick = onAdd)
        }
        Text(
            "简易模式只保留基础映射和鼠标；方案里的勾选决定运行时启用哪些功能。" +
                "模式不会覆盖各功能原本的开关，切回来不会丢设置。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val mode = modes.getOrNull(currentIndex) ?: modes[1]
        val editable = currentIndex != 0
        if (editable) {
            OutlinedTextField(
                value = mode.name,
                onValueChange = { onUpdate(currentIndex, mode.copy(name = it.take(8))) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("方案名称") },
                singleLine = true,
            )
        } else {
            Text(
                "当前：简易模式，下面只保留基础设置；切到方案后可查看并配置高级功能。",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (editable) {
        ModeFeatureRow(
            label = "宏（切枪 / 合盾 / 扇贝 / 冲刺）",
            checked = mode.macros,
            enabled = editable,
            onCheckedChange = { onUpdate(currentIndex, mode.copy(macros = it)) },
            onGear = { onOpenDetail(SettingsDetail.MACROS) },
        )
        ModeFeatureRow(
            label = "按键辅助（换位 / 2-3-4 / 静步）",
            checked = mode.assists,
            enabled = editable,
            onCheckedChange = { onUpdate(currentIndex, mode.copy(assists = it)) },
            onGear = { onOpenDetail(SettingsDetail.ASSIST) },
        )
        ModeFeatureRow(
            label = "F 交互后左键唤出光标",
            checked = mode.fCursor,
            enabled = editable,
            onCheckedChange = { onUpdate(currentIndex, mode.copy(fCursor = it)) },
            onGear = { onOpenDetail(SettingsDetail.F_CURSOR) },
        )
        ModeFeatureRow(
            label = "F 光标滚轮操控",
            checked = mode.scrollControl,
            enabled = editable,
            onCheckedChange = { onUpdate(currentIndex, mode.copy(scrollControl = it)) },
            onGear = { onOpenDetail(SettingsDetail.SCROLL) },
        )
        ModeFeatureRow(
            label = "Alt+D 拖拽丢弃",
            checked = mode.discard,
            enabled = editable,
            onCheckedChange = { onUpdate(currentIndex, mode.copy(discard = it)) },
            onGear = { onOpenDetail(SettingsDetail.DISCARD) },
        )
        ModeFeatureRow(
            label = "拟人化抖动",
            checked = mode.humanization,
            enabled = editable,
            onCheckedChange = { onUpdate(currentIndex, mode.copy(humanization = it)) },
            onGear = { onOpenDetail(SettingsDetail.HUMANIZATION) },
        )
        }
        if (editable) {
        ModeFeatureRow(
            label = "ESC / Tab 关闭背包回瞄准",
            checked = tabEscAimEnabled,
            enabled = true,
            onCheckedChange = onSetTabEscAimEnabled,
            onGear = { onOpenDetail(SettingsDetail.ESC_TAB_AIM) },
        )
        ModeFeatureRow(
            label = "M 键开关地图并切光标",
            checked = mapMouseToggleEnabled,
            enabled = true,
            onCheckedChange = onSetMapMouseToggleEnabled,
            onGear = { onOpenDetail(SettingsDetail.MAP_MOUSE) },
        )
        ModeFeatureRow(
            label = "防止大幅甩动导致视角问题",
            checked = mouseGuardEnabled,
            enabled = true,
            onCheckedChange = onSetMouseGuardEnabled,
            onGear = { onOpenDetail(SettingsDetail.MOUSE_GUARD) },
        )
        }
        BasicSettingRow(
            title = "显示与操作",
            desc = "透明度、顶部状态条、按钮大小、指针灵敏度",
            onGear = { onOpenDetail(SettingsDetail.DISPLAY) },
        )
    }
}

@Composable
private fun ModeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    // 选中才有主色边框；未选中只留浅色底，避免「没用到的方案反而更显眼」。
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ModeAddChip(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Icon(
            Icons.Rounded.Add,
            contentDescription = "添加方案",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp).size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModeFeatureRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onGear: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        IconButton(onClick = onGear) {
            Icon(Icons.Rounded.Settings, contentDescription = "配置")
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun BasicSettingRow(title: String, desc: String, onGear: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onGear) {
            Icon(Icons.Rounded.Settings, contentDescription = "配置")
        }
    }
}

/**
 * 设置页统一分区卡片：标题 + 作用域标签 + 说明，点标题行折叠/展开。
 *
 * 折叠后只保留标题行，避免低频设置（拟人化、兼容、维护）把常用项挤到很下面。
 */
@Composable
private fun SettingsSectionCard(
    title: String,
    scope: SettingsScope = SettingsScope.NONE,
    hint: String = "",
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 用标题做 key：不同分区互不影响，重建页面时保持各自的展开状态。
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (scope != SettingsScope.NONE) {
                    Text(
                        modifier = Modifier.padding(start = 8.dp),
                        text = scope.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (scope == SettingsScope.PRESET)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    modifier = Modifier.rotate(if (expanded) 180f else 0f)
                )
            }
            if (hint.isNotBlank()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
fun KeyConfigTextField(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    onKeyEvent: (KeyEvent) -> Boolean
) {
    val focusRequester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    Row {
        Text(
            modifier = modifier
                .fillMaxWidth()
                .weight(1f),
            text = title,
            style = MaterialTheme.typography.titleMedium
        )
        OutlinedCard(border = BorderStroke(
            1.dp,
            // Was Color.Cyan: unreadable on a light dynamic-colour surface.
            color = if (hasFocus) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline
        ),
            modifier = Modifier.clickable { focusRequester.requestFocus() }) {
            Text(
                modifier = Modifier
                    .padding(vertical = 8.dp, horizontal = 16.dp)
                    .focusRequester(focusRequester)
                    .onKeyEvent { onKeyEvent(it) }
                    .onFocusChanged { hasFocus = it.isFocused }
                    .focusable(),
                text = value,
                style = TextStyle(color = LocalContentColor.current),
            )
        }
    }
}

/**
 * 按键辅助：这些开关都跟随「当前预设」，与具体按键绑定无关。
 * 改动先写入 [draft]，点设置页右上角保存时才由调用方一次性写回预设，
 * 这样返回或切换预设时「放弃修改」才能真正放弃。
 */
@Composable
private fun ProfileAssistCard(
    profile: Profile?,
    draft: ProfileAssist,
    onDraftChange: (ProfileAssist) -> Unit,
    title: String = "按键辅助",
    sections: Set<AssistSection> = setOf(
        AssistSection.ASSIST,
        AssistSection.MACROS,
    ),
) {
    var showMacros by remember { mutableStateOf(false) }

    SettingsSectionCard(
        title = title,
        scope = SettingsScope.PRESET,
        hint = if (profile == null) "先在「预设」里选择一个预设"
        else "当前预设：${profile.name} · 改动需点右上角保存",
        initiallyExpanded = true,
    ) {
            if (profile == null) {
                Text(
                    "先在「预设」里选择一个预设，这些辅助会跟随该预设保存。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@SettingsSectionCard
            }
            if (AssistSection.ASSIST in sections) {
            AssistSwitchRow(
                title = "R ↔ 右键（1/2 换位 / X 还原）",
                desc = "按 1、2 任意一个让 R 与鼠标右键按钮交换位置，按 X 各自归位。交换立即生效、不重建触点，" +
                    "所以不会断触或卡刀；3/4 固定用于近战流程，不参与换位；已换位时再按只当普通按键透传。锁存状态随预设保存，重启后保持。",
                checked = draft.quickSwapEnabled,
                onToggle = {
                    onDraftChange(draft.copy(quickSwapEnabled = !draft.quickSwapEnabled))
                }
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            AssistSwitchRow(
                title = "按 3 切近战（2 → 4 → 3）",
                desc = "按 3 时依次点击 2 → 4 → 3。游戏会按携带情况重排格子：单枪时近战在 2、双枪时在 4、" +
                    "双枪+手枪时在 3，三种布局的近战位在这个固定顺序里都只出现一次，所以最终一定停在近战；" +
                    "点空位不会切走，没有副作用。真正的问题是近战位被点第二次——刀已经在手上时再点同一格，" +
                    "会收回刀、切回主武器 1，所以每轮只点一次。另外请关闭游戏内的「快速切枪」，否则会自动切回主武器。" +
                    "连点延迟在下方区间内随机（默认 20–30ms）；2/3/4 缺任意一个按钮时不接管。",
                checked = draft.tapSequence234Enabled,
                onToggle = {
                    onDraftChange(draft.copy(tapSequence234Enabled = !draft.tapSequence234Enabled))
                }
            )
            if (draft.tapSequence234Enabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "连点延迟下限",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text("${draft.tapSequence234GapMinMs} ms", style = MaterialTheme.typography.bodyMedium)
                }
                Slider(
                    value = draft.tapSequence234GapMinMs.toFloat().coerceIn(5f, 500f),
                    valueRange = 5f..500f,
                    onValueChange = {
                        val min = it.roundToInt()
                        onDraftChange(
                            draft.copy(
                                tapSequence234GapMinMs = min,
                                tapSequence234GapMaxMs = maxOf(min, draft.tapSequence234GapMaxMs),
                            )
                        )
                    }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "连点延迟上限",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text("${draft.tapSequence234GapMaxMs} ms", style = MaterialTheme.typography.bodyMedium)
                }
                Slider(
                    value = draft.tapSequence234GapMaxMs.toFloat().coerceIn(5f, 500f),
                    valueRange = 5f..500f,
                    onValueChange = {
                        val max = it.roundToInt()
                        onDraftChange(
                            draft.copy(
                                tapSequence234GapMaxMs = max.coerceAtLeast(draft.tapSequence234GapMinMs),
                            )
                        )
                    }
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            AssistSwitchRow(
                title = "Shift 取消静步",
                desc = "按 Shift 起跑时，如果静步是我们帮你点开的，会自动补点一次静步键取消掉，避免一跑就变成慢走。" +
                    "你自己手动开的静步不介入；预设里没有静步按钮或当前模式关闭按键辅助时不生效。",
                checked = draft.shiftCancelWalkEnabled,
                onToggle = {
                    onDraftChange(draft.copy(shiftCancelWalkEnabled = !draft.shiftCancelWalkEnabled))
                }
            )
            }
            if (AssistSection.MACROS in sections) {
            AssistSwitchRow(
                title = "快速切枪宏（默认 Alt+9）",
                desc = "按触发键执行自定义按键序列（默认内置 G502 快速切枪序列）。中间每一步直接注入触点，" +
                    "不参与 R/右键换位、不改变锁存，宏里的 1/X 不会把按钮翻来翻去；" +
                    "宏正常跑完后，如果最后一步是 1/2，会补一次换位（默认序列以 1 结尾）；最多 128 步。",
                checked = draft.keyMacro.enabled,
                onToggle = {
                    onDraftChange(
                        draft.copy(keyMacro = draft.keyMacro.copy(enabled = !draft.keyMacro.enabled))
                    )
                }
            )
            if (draft.keyMacro.enabled) {
                var showTriggerDialog by remember { mutableStateOf(false) }
                var showStepsDialog by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("触发键", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            (if (draft.keyMacro.requireAlt) "Alt + " else "") +
                                keyName(draft.keyMacro.triggerKeyCode),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { showTriggerDialog = true }) { Text("修改") }
                    TextButton(onClick = { showStepsDialog = true }) {
                        Text("编辑序列（${draft.keyMacro.steps.size} 步）")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "每步按住时长",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text("${draft.keyMacro.holdMs} ms", style = MaterialTheme.typography.bodyMedium)
                }
                Slider(
                    value = draft.keyMacro.holdMs.toFloat().coerceIn(5f, 100f),
                    valueRange = 5f..100f,
                    steps = 18,
                    onValueChange = {
                        onDraftChange(
                            draft.copy(keyMacro = draft.keyMacro.copy(holdMs = it.roundToInt()))
                        )
                    }
                )
                if (showTriggerDialog) {
                    var triggerName by remember { mutableStateOf(keyName(draft.keyMacro.triggerKeyCode)) }
                    var triggerAlt by remember { mutableStateOf(draft.keyMacro.requireAlt) }
                    var triggerError by remember { mutableStateOf(false) }
                    AlertDialog(
                        onDismissRequest = { showTriggerDialog = false },
                        title = { Text("快速切枪触发键") },
                        text = {
                            Column {
                                OutlinedTextField(
                                    value = triggerName,
                                    onValueChange = {
                                        triggerName = it
                                        triggerError = false
                                    },
                                    label = { Text("按键名，如 9、F5、X（1–9 指主键盘左侧数字行）") },
                                    singleLine = true,
                                    isError = triggerError
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("需要按住 Alt", modifier = Modifier.weight(1f))
                                    Switch(
                                        checked = triggerAlt,
                                        onCheckedChange = { triggerAlt = it }
                                    )
                                }
                                if (triggerError) {
                                    Text(
                                        "无法识别这个键名",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val code = keyCodeForName(triggerName)
                                if (code == null) {
                                    triggerError = true
                                } else {
                                    onDraftChange(
                                        draft.copy(
                                            keyMacro = draft.keyMacro.copy(
                                                triggerKeyCode = code,
                                                requireAlt = triggerAlt
                                            )
                                        )
                                    )
                                    showTriggerDialog = false
                                }
                            }) { Text("保存") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showTriggerDialog = false }) { Text("取消") }
                        }
                    )
                }
                if (showStepsDialog) {
                    var seqText by remember {
                        mutableStateOf(keySequenceToText(draft.keyMacro.steps))
                    }
                    var seqError by remember { mutableStateOf<String?>(null) }
                    AlertDialog(
                        onDismissRequest = { showStepsDialog = false },
                        title = { Text("快速切枪序列") },
                        text = {
                            Column {
                                Text(
                                    "每行 D/U/T/W：D 1 50=按下1等50ms，U 1 50=放开1，T 1 50=点击1，W 50=纯等待；RMB 为鼠标右键，# 开头为注释。1–9 指主键盘左侧数字行，小键盘数字不推荐（NumLock 关闭时可能无效）。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedTextField(
                                    value = seqText,
                                    onValueChange = {
                                        seqText = it
                                        seqError = null
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp),
                                    textStyle = MaterialTheme.typography.bodySmall
                                )
                                seqError?.let {
                                    Text(
                                        it,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                TextButton(onClick = {
                                    seqText = keySequenceToText(defaultQuickSwapSteps())
                                    seqError = null
                                }) { Text("载入默认序列") }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val steps = parseKeySequence(seqText)
                                if (steps == null) {
                                    seqError = "格式不对：每行 D/U/T/W，最多 128 步，延迟 0–5000"
                                } else {
                                    onDraftChange(
                                        draft.copy(keyMacro = draft.keyMacro.copy(steps = steps))
                                    )
                                    showStepsDialog = false
                                }
                            }) { Text("保存") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showStepsDialog = false }) { Text("取消") }
                        }
                    )
                }
            }
            KeyMacroSection(
                title = "快速合盾（默认 Alt+8）",
                desc = "按住 V 点一下 X 再松开 V，然后按住 G 点一下 X 再松开 G；每步按住默认 10ms，可单独调整。",
                macro = draft.shieldMacro,
                defaultSteps = { defaultShieldSteps() },
                onChange = { onDraftChange(draft.copy(shieldMacro = it)) }
            )
            KeyMacroSection(
                title = "扇贝盾（默认 Alt+7）",
                desc = "按 1 → G → V → X；每步按住默认 10ms，步骤间隔默认 5ms，都可单独调整。",
                macro = draft.scallopMacro,
                defaultSteps = { defaultScallopSteps() },
                onChange = { onDraftChange(draft.copy(scallopMacro = it)) }
            )
            KeyMacroSection(
                title = "扇贝盾冲刺（默认 Alt+6）",
                desc = "按 1 → G → V → X → 鼠标右键；每步按住默认 10ms，步骤间隔默认 5ms。",
                macro = draft.scallopDashMacro,
                defaultSteps = { defaultScallopDashSteps() },
                onChange = { onDraftChange(draft.copy(scallopDashMacro = it)) }
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            QuickMeleeSection(
                macro = draft.quickMeleeMacro,
                onChange = { onDraftChange(draft.copy(quickMeleeMacro = it)) }
            )
            }
            if (AssistSection.MACROS in sections) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("点击宏（顺序点位）", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "按顺序依次点击屏幕上的点位，与普通键位绑定互不影响；每条宏可单独设置触发键和各点坐标，" +
                            "适合连续开箱、连点确认这类操作。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { showMacros = true }) {
                    Text(if (draft.macros.isEmpty()) "添加" else "编辑（${draft.macros.size}）")
                }
            }
            }
    }

    if (profile == null) return
    if (showMacros) {
        ClickMacrosDialog(
            profile = profile.withAssist(draft),
            onDismiss = { showMacros = false },
            onSave = { onDraftChange(draft.copy(macros = it)) }
        )
    }
}

/** 按键辅助里的一行开关：点整行或右侧 Switch 都能切换。 */
@Composable
private fun AssistSwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                desc,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

/**
 * Card for managing multiple keymap profiles. Each row in the list is a
 * radio + name + (rename, delete) actions; a "+ 新建" button lets the user
 * create a fresh empty profile and immediately switch to it.
 */
@Composable
fun ProfileManagerCard(
    profiles: List<Profile>,
    activeProfileId: String?,
    onCreate: (String) -> Unit,
    onExportAllProfiles: () -> String,
    onSwitch: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onExport: (String) -> String?,
    onImport: (String) -> String?,
) {
    var showNewDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<Profile?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Profile?>(null) }
    var menuProfile by remember { mutableStateOf<Profile?>(null) }
    var exportText by remember { mutableStateOf<String?>(null) }
    var showImportNotice by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<(() -> Unit)?>(null) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fileError by remember { mutableStateOf<String?>(null) }

    // 导出为文件：预设数据在系统文件选择器里落盘，换设备时拷走这个 JSON 即可。
    val exportAllLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val json = onExportAllProfiles()
            scope.launch {
                val failure = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(json.toByteArray(Charsets.UTF_8))
                        } ?: error("无法打开输出文件")
                    }.exceptionOrNull()?.let { "导出失败：${it.message ?: "无法写入所选文件"}" }
                }
                if (failure != null) fileError = failure
            }
        }
    }

    // 从文件导入：读取 KeySync 导出的 JSON，交给与剪贴板导入相同的校验流程。
    val importFromFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    }.getOrNull()
                }
                fileError = if (text.isNullOrBlank()) {
                    "读取文件失败：请选择 KeySync 导出的 JSON 文件"
                } else {
                    onImport(text)
                }
            }
        }
    }

    if (showImportNotice) {
        AlertDialog(
            onDismissRequest = {
                showImportNotice = false
                pendingImport = null
            },
            title = { Text("导入提示") },
            text = {
                Text(
                    "预设里的按键位置按「悬浮窗可用区域」的比例保存，不是按手机分辨率。" +
                        "换设备或可用区域比例不同时可能错位，导入后请进编辑态核对位置。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showImportNotice = false
                    val action = pendingImport
                    pendingImport = null
                    action?.invoke()
                }) { Text("继续导入") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImportNotice = false
                    pendingImport = null
                }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.profile_manager_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                FilledTonalButton(onClick = { showNewDialog = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.widthIn(4.dp))
                    Text(stringResource(R.string.profile_manager_new))
                }
            }
            Text(
                text = stringResource(R.string.profile_manager_hint_hotkey),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(onClick = {
                    pendingImport = { showImportDialog = true }
                    showImportNotice = true
                }) {
                    Text(stringResource(R.string.profile_import))
                }
                TextButton(onClick = {
                    pendingImport = { importFromFileLauncher.launch(arrayOf("*/*")) }
                    showImportNotice = true
                }) {
                    Text("从文件导入")
                }
                TextButton(onClick = { exportText = onExportAllProfiles() }) { Text("复制全部预设") }
                TextButton(onClick = { exportAllLauncher.launch("keysync-profiles.json") }) {
                    Text("导出到文件")
                }
            }
            HorizontalDivider()
            // LazyColumn, not Column: with a plain Column the rows overflowed
            // the 320dp cap and profiles past the cut-off were neither visible
            // nor reachable (the parent Column has no scroll modifier).
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
            ) {
                items(items = profiles, key = { it.id }) { profile ->
                    ProfileRow(
                        profile = profile,
                        isActive = profile.id == activeProfileId,
                        canDelete = profiles.size > 1,
                        menuExpanded = menuProfile?.id == profile.id,
                        onMenuClick = { menuProfile = profile },
                        onMenuDismiss = { menuProfile = null },
                        onSwitch = onSwitch,
                        onRename = {
                            renameTarget = profile
                            renameText = profile.name
                        },
                        onDuplicate = { onDuplicate(profile.id) },
                        onExport = { exportText = onExport(profile.id) },
                        onDelete = { deleteTarget = profile }
                    )
                }
            }

            HorizontalDivider()
        }
    }


    if (showNewDialog) {
        val trimmedName = newName.trim()
        // Duplicate names are legal but make the profile list and the hotkey
        // target picker unreadable, so block them up front.
        val isDuplicate = trimmedName.isNotBlank() &&
            profiles.any { it.name.equals(trimmedName, ignoreCase = true) }
        AlertDialog(
            onDismissRequest = { showNewDialog = false; newName = "" },
            title = { Text(stringResource(R.string.profile_manager_new)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(stringResource(R.string.profile_new_name_hint)) },
                        singleLine = true,
                        isError = isDuplicate,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (isDuplicate) {
                        Text(
                            modifier = Modifier.padding(top = 8.dp),
                            text = stringResource(R.string.settings_profile_name_duplicate),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isDuplicate,
                    onClick = {
                        onCreate(newName)
                        newName = ""
                        showNewDialog = false
                    }
                ) { Text(stringResource(R.string.key_rebind_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showNewDialog = false }) {
                    Text(stringResource(R.string.key_rebind_cancel))
                }
            }
        )
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.profile_manager_rename)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(target.id, renameText)
                    renameTarget = null
                }) { Text(stringResource(R.string.key_rebind_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.key_rebind_cancel))
                }
            }
        )
    }

    deleteTarget?.let { target ->
        val fallback = profiles.firstOrNull { it.id != target.id }
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.profile_manager_delete)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_profile_delete_message, target.name))
                    // Deleting the active profile silently jumps to another
                    // one, so say which before the user commits.
                    if (target.id == activeProfileId && fallback != null) {
                        Text(
                            modifier = Modifier.padding(top = 8.dp),
                            text = stringResource(
                                R.string.settings_profile_delete_active,
                                fallback.name
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target.id)
                    deleteTarget = null
                }) { Text(stringResource(R.string.key_rebind_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.key_rebind_cancel))
                }
            }
        )
    }

    if (showImportDialog) {
        ProfileImportDialog(
            onDismiss = { showImportDialog = false },
            onImport = onImport
        )
    }

    exportText?.let { json ->
        ProfileExportDialog(json = json, onDismiss = { exportText = null })
    }

    fileError?.let { message ->
        AlertDialog(
            onDismissRequest = { fileError = null },
            title = { Text("预设文件") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { fileError = null }) {
                    Text(stringResource(R.string.common_dismiss))
                }
            }
        )
    }


}

/**
 * One profile row: radio + name + active badge + overflow menu.
 *
 * Extracted so the list can be a LazyColumn; the menu callbacks are bound per
 * row so the "which row owns the open menu" state stays in the parent.
 */
@Composable
private fun ProfileRow(
    profile: Profile,
    isActive: Boolean,
    canDelete: Boolean,
    menuExpanded: Boolean,
    onMenuClick: () -> Unit,
    onMenuDismiss: () -> Unit,
    onSwitch: (String) -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSwitch(profile.id) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isActive,
            onClick = { onSwitch(profile.id) }
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp)
        ) {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.titleMedium
            )
            if (profile.layoutScreenWidth > 0 && profile.layoutScreenHeight > 0) {
                Text(
                    text = "${profile.layoutScreenWidth} x ${profile.layoutScreenHeight}（悬浮层可用区域，不含系统栏）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (isActive) {
            Text(
                text = stringResource(R.string.profile_manager_active),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.widthIn(8.dp))
        }
        Box {
            IconButton(onClick = onMenuClick) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.profile_manager_more)
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = onMenuDismiss
            ) {
                if (!isActive) {
                    DropdownMenuItem(
                        text = { Text("切换到此预设") },
                        onClick = {
                            onMenuDismiss()
                            onSwitch(profile.id)
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.profile_manager_rename)) },
                    onClick = {
                        onMenuDismiss()
                        onRename()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.profile_duplicate)) },
                    onClick = {
                        onMenuDismiss()
                        onDuplicate()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.profile_export)) },
                    onClick = {
                        onMenuDismiss()
                        onExport()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.profile_manager_delete)) },
                    enabled = canDelete,
                    onClick = {
                        onMenuDismiss()
                        onDelete()
                    }
                )
            }
        }
    }
}







/** Shows a profile as JSON so it can be copied out and shared or backed up. */
@Composable
fun ProfileExportDialog(json: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saveError by remember { mutableStateOf<String?>(null) }
    val saveFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                saveError = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(json.toByteArray(Charsets.UTF_8))
                        } ?: error("无法打开输出文件")
                    }.exceptionOrNull()?.let { "保存失败：${it.message ?: "无法写入所选文件"}" }
                }
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.profile_export_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = json,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp),
                    textStyle = MaterialTheme.typography.bodySmall
                )
                saveError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    text = "保存为 .json 文件后可以拷到其他设备，在「导入」里选择该文件即可加入。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(json))
                onDismiss()
            }) { Text(stringResource(R.string.profile_copy_to_clipboard)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { saveFileLauncher.launch("keysync-profile.json") }) {
                    Text("保存为文件")
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.key_rebind_cancel)) }
            }
        }
    )
}

/** Pastes a profile exported by [ProfileExportDialog] and adds it as a new one. */
@Composable
fun ProfileImportDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> String?,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val pickFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    }.getOrNull()
                }
                if (loaded.isNullOrBlank()) {
                    error = "读取文件失败：请选择 KeySync 导出的 JSON 文件"
                } else {
                    text = loaded
                    error = null
                }
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.profile_import_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp),
                    textStyle = MaterialTheme.typography.bodySmall
                )
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val failure = onImport(text)
                if (failure == null) onDismiss() else error = failure
            }) { Text(stringResource(R.string.profile_import)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { pickFileLauncher.launch(arrayOf("*/*")) }) {
                    Text("从文件选择")
                }
                TextButton(onClick = {
                    val pasted = clipboard.getText()?.text.orEmpty()
                    if (pasted.isNotBlank()) {
                        text = pasted
                        error = null
                    }
                }) { Text(stringResource(R.string.profile_paste_from_clipboard)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.key_rebind_cancel)) }
            }
        }
    )
}

/** 一个按键序列宏的通用编辑卡：开关、触发键、按住时长、文本序列。 */
@Composable
private fun KeyMacroSection(
    title: String,
    desc: String,
    macro: KeySeqMacro,
    defaultSteps: () -> List<KeySeqStep>,
    onChange: (KeySeqMacro) -> Unit,
) {
    AssistSwitchRow(
        title = title,
        desc = desc,
        checked = macro.enabled,
        onToggle = { onChange(macro.copy(enabled = !macro.enabled)) }
    )
    if (!macro.enabled) return
    var showTriggerDialog by remember { mutableStateOf(false) }
    var showStepsDialog by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("触发键", style = MaterialTheme.typography.bodyMedium)
            Text(
                (if (macro.requireAlt) "Alt + " else "") + keyName(macro.triggerKeyCode),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = { showTriggerDialog = true }) { Text("修改") }
        TextButton(onClick = { showStepsDialog = true }) {
            Text("编辑序列（${macro.steps.size} 步）")
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "每步按住时长",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        Text("${macro.holdMs} ms", style = MaterialTheme.typography.bodyMedium)
    }
    Slider(
        value = macro.holdMs.toFloat().coerceIn(5f, 100f),
        valueRange = 5f..100f,
        steps = 18,
        onValueChange = { onChange(macro.copy(holdMs = it.roundToInt())) }
    )
    if (showTriggerDialog) {
        var triggerName by remember { mutableStateOf(keyName(macro.triggerKeyCode)) }
        var triggerAlt by remember { mutableStateOf(macro.requireAlt) }
        var triggerError by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showTriggerDialog = false },
            title = { Text("$title 触发键") },
            text = {
                Column {
                    OutlinedTextField(
                        value = triggerName,
                        onValueChange = {
                            triggerName = it
                            triggerError = false
                        },
                        label = { Text("按键名，如 9、F5、X（1–9 指主键盘左侧数字行）") },
                        singleLine = true,
                        isError = triggerError
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("需要按住 Alt", modifier = Modifier.weight(1f))
                        Switch(checked = triggerAlt, onCheckedChange = { triggerAlt = it })
                    }
                    if (triggerError) {
                        Text(
                            "无法识别这个键名",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val code = keyCodeForName(triggerName)
                    if (code == null) {
                        triggerError = true
                    } else {
                        onChange(macro.copy(triggerKeyCode = code, requireAlt = triggerAlt))
                        showTriggerDialog = false
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showTriggerDialog = false }) { Text("取消") }
            }
        )
    }
    if (showStepsDialog) {
        var seqText by remember { mutableStateOf(keySequenceToText(macro.steps)) }
        var seqError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showStepsDialog = false },
            title = { Text(title) },
            text = {
                Column {
                    Text(
                        "每行 D/U/T/W：D 1 50=按下1等50ms，U 1 50=放开1，T 1 50=点击1，W 50=纯等待；RMB 为鼠标右键，# 开头为注释。1–9 指主键盘左侧数字行，小键盘数字不推荐（NumLock 关闭时可能无效）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = seqText,
                        onValueChange = {
                            seqText = it
                            seqError = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    seqError?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    TextButton(onClick = {
                        seqText = keySequenceToText(defaultSteps())
                        seqError = null
                    }) { Text("载入默认序列") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val steps = parseKeySequence(seqText)
                    if (steps == null) {
                        seqError = "格式不对：每行 D/U/T/W，最多 128 步，延迟 0–5000"
                    } else {
                        onChange(macro.copy(steps = steps))
                        showStepsDialog = false
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showStepsDialog = false }) { Text("取消") }
            }
        )
    }
}

/** 快速近战：按住触发键循环「近战键 → 延迟 → 1 号键」。 */
@Composable
private fun QuickMeleeSection(
    macro: QuickMeleeMacro,
    onChange: (QuickMeleeMacro) -> Unit,
) {
    AssistSwitchRow(
        title = "快速近战（默认关闭，按住 Alt+0）",
        desc = "默认关闭，需要时在这里打开。按住触发键时循环执行：点一次「快速近战」映射位置 → 等设定延迟 → 点一次 1 号键；" +
            "松开立即停止。键盘长按的重复事件会被吞掉，不会重复启动，也不会额外注册监听。" +
            "需要先在布局里放好快速近战键和 1 号键的映射。",
        checked = macro.enabled,
        onToggle = { onChange(macro.copy(enabled = !macro.enabled)) }
    )
    if (!macro.enabled) return
    var editTrigger by remember { mutableStateOf(false) }
    var editMelee by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("触发键（按住循环）", style = MaterialTheme.typography.bodyMedium)
            Text(
                (if (macro.requireAlt) "Alt + " else "") + keyName(macro.triggerKeyCode),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = { editTrigger = true }) { Text("修改") }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("快速近战键", style = MaterialTheme.typography.bodyMedium)
            Text(
                keyName(macro.meleeKeyCode) + "（点它对应的映射位置）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = { editMelee = true }) { Text("修改") }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "近战键与 1 号键之间的延迟",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        Text("${macro.delayMs} ms", style = MaterialTheme.typography.bodyMedium)
    }
    Slider(
        value = macro.delayMs.toFloat().coerceIn(20f, 1000f),
        valueRange = 20f..1000f,
        steps = 48,
        onValueChange = { onChange(macro.copy(delayMs = it.roundToInt())) }
    )
    if (editTrigger) {
        KeyNameDialog(
            title = "快速近战触发键",
            initialKeyCode = macro.triggerKeyCode,
            onDismiss = { editTrigger = false },
            onConfirm = {
                onChange(macro.copy(triggerKeyCode = it))
                editTrigger = false
            }
        )
    }
    if (editMelee) {
        KeyNameDialog(
            title = "快速近战键",
            initialKeyCode = macro.meleeKeyCode,
            onDismiss = { editMelee = false },
            onConfirm = {
                onChange(macro.copy(meleeKeyCode = it))
                editMelee = false
            }
        )
    }
}

/** 输入一个键名并解析成 KeyCode；无法识别时就地报错。 */
@Composable
private fun KeyNameDialog(
    title: String,
    initialKeyCode: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var name by remember { mutableStateOf(keyName(initialKeyCode)) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = false
                    },
                    label = { Text("按键名，如 5、V、NUM0、F5（1–9 指主键盘数字行，NUM0 为右侧小键盘）") },
                    singleLine = true,
                    isError = error
                )
                if (error) {
                    Text(
                        "无法识别这个键名",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val code = keyCodeForName(name)
                if (code == null) error = true else onConfirm(code)
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}



