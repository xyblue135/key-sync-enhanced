package com.devoid.keysync.ui

import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import com.devoid.keysync.R
import com.devoid.keysync.model.AppConfig
import com.devoid.keysync.model.DraggableItem
import com.devoid.keysync.model.Profile
import com.devoid.keysync.model.SwapPair
import com.devoid.keysync.model.ThemePreference
import com.devoid.keysync.model.TouchMode
import com.devoid.keysync.util.capitalizeFirst
import com.devoid.keysync.util.keyCodeToString
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
    onSetProfileSwapPairs: (profileId: String, pairs: List<SwapPair>) -> Unit = { _, _ -> },
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

    val isDirty = newConfig != appConfig

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

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(text = stringResource(R.string.settings_title))
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isDirty) showDiscardConfirm = true else onNavigateBack()
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
                        onClick = { onSave(newConfig) }
                    ) {
                        Text(stringResource(R.string.settings_save))
                    }
                })
        }
    ) { innerPadding ->
        val scrollState = rememberScrollState()
        Box(
            Modifier
                .padding(innerPadding)
                .padding(8.dp)
                .verticalScroll(state = scrollState)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileManagerCard(
                    profiles = profiles,
                    activeProfileId = activeProfileId,
                    onCreate = onCreateProfile,
                    onExportAllProfiles = onExportAllProfiles,
                    onSwitch = onSwitchProfile,
                    onRename = onRenameProfile,
                    onDelete = onDeleteProfile,
                    onDuplicate = onDuplicateProfile,
                    onExport = onExportProfile,
                    onImport = onImportProfile,
                    onSetSwapPairs = onSetProfileSwapPairs,
                )
                Card {
                    Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.settings_button_size),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
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
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            Text(
                                stringResource(R.string.settings_delete_data),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                modifier = Modifier.padding(top = 8.dp),
                                text = stringResource(R.string.settings_delete_keymap),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = newConfig.deleteDataOnRemove, onCheckedChange = {
                            newConfig = newConfig.copy(deleteDataOnRemove = it)
                        })
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    var cancellableDropDownExpanded by remember { mutableStateOf(false) }
                    var scopeDropDownExpanded by remember { mutableStateOf(false) }

                    ConstraintLayout(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        val (title,
                            cancelTitle,
                            cancelBody,
                            cancelSelector,
                            divider1,
                            scopeTitle,
                            scopeBody,
                            scopeSelector) = createRefs()
                        Text(
                            stringResource(R.string.settings_touch_mode),
                            modifier = Modifier.constrainAs(title) {
                                top.linkTo(parent.top)
                                start.linkTo(parent.start)
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            stringResource(R.string.settings_cancelable_button),
                            modifier = Modifier
                                .constrainAs(cancelTitle) {
                                    top.linkTo(title.bottom)
                                }
                                .padding(top = 8.dp),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            modifier = Modifier
                                .constrainAs(cancelBody) {
                                    top.linkTo(cancelTitle.bottom)
                                }
                                .padding(top = 8.dp),
                            text = stringResource(R.string.settings_touch_cancel),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedCard(modifier = Modifier.constrainAs(cancelSelector) {
                            bottom.linkTo(cancelBody.bottom)
                            top.linkTo(cancelTitle.top)
                            end.linkTo(parent.end)
                        }, onClick = { cancellableDropDownExpanded = true }) {
                            Row(
                                modifier = Modifier
                                    .padding(8.dp)
                                    .widthIn(min = 60.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    newConfig.cancellableTouchMode.name.capitalizeFirst(),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = cancellableDropDownExpanded,
                                onDismissRequest = { cancellableDropDownExpanded = false }) {
                                TouchMode.entries.filter { it != TouchMode.WHEEL }.forEach {
                                    DropdownMenuItem(
                                        text = { Text(it.name.capitalizeFirst()) },
                                        onClick = {
                                            newConfig = newConfig.copy(cancellableTouchMode = it)
                                            cancellableDropDownExpanded = false
                                        })
                                }
                            }
                        }
                        HorizontalDivider(
                            Modifier
                                .constrainAs(divider1) {
                                    top.linkTo(cancelBody.bottom)
                                }
                                .padding(top = 8.dp))
                        Text(
                            stringResource(R.string.settings_scope),
                            modifier = Modifier
                                .constrainAs(scopeTitle) {
                                    top.linkTo(divider1.bottom)
                                }
                                .padding(top = 8.dp),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            modifier = Modifier
                                .constrainAs(scopeBody) {
                                    top.linkTo(scopeTitle.bottom)
                                }
                                .padding(top = 8.dp),
                            text = stringResource(R.string.settings_touch_scope),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedCard(modifier = Modifier.constrainAs(scopeSelector) {
                            bottom.linkTo(scopeBody.bottom)
                            top.linkTo(scopeTitle.top)
                            end.linkTo(parent.end)
                        }, onClick = { scopeDropDownExpanded = true }) {
                            Row(
                                modifier = Modifier
                                    .padding(8.dp)
                                    .widthIn(min = 60.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    newConfig.normalBtnTouchMode.name.capitalizeFirst(),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = scopeDropDownExpanded,
                                onDismissRequest = { scopeDropDownExpanded = false }) {
                                TouchMode.entries.filter { it != TouchMode.WHEEL }.forEach {
                                    DropdownMenuItem(
                                        text = { Text(it.name.capitalizeFirst()) },
                                        onClick = {
                                            newConfig = newConfig.copy(normalBtnTouchMode = it)
                                            scopeDropDownExpanded = false
                                        })
                                }
                            }
                        }
                    }


                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    var dropDownExpanded by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            Text(
                                stringResource(R.string.settings_theme),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                            Text(
                                modifier = Modifier.padding(top = 8.dp),
                                text = stringResource(R.string.settings_theme_pref),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedCard(onClick = { dropDownExpanded = true }) {
                            Row(
                                modifier = Modifier
                                    .padding(8.dp)
                                    .widthIn(min = 60.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    newConfig.themePreference.name.capitalizeFirst(),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = dropDownExpanded,
                                onDismissRequest = { dropDownExpanded = false }) {
                                ThemePreference.entries.forEach {
                                    DropdownMenuItem(
                                        text = { Text(it.name.capitalizeFirst()) },
                                        onClick = {
                                            newConfig = newConfig.copy(themePreference = it)
                                            dropDownExpanded = false
                                        })
                                }
                            }
                        }

                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            Text(
                                stringResource(R.string.settings_screen_mirror_compat),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                            Text(
                                modifier = Modifier.padding(top = 8.dp),
                                text = stringResource(R.string.settings_screen_mirror_compat_desc),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = newConfig.screenMirrorCompatMode,
                            onCheckedChange = {
                                newConfig = newConfig.copy(screenMirrorCompatMode = it)
                            }
                        )
                    }
                }
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth(),
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
    onSetSwapPairs: (String, List<SwapPair>) -> Unit,
) {
    var showNewDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<Profile?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Profile?>(null) }
    var menuProfile by remember { mutableStateOf<Profile?>(null) }
    var exportText by remember { mutableStateOf<String?>(null) }
    var swapTarget by remember { mutableStateOf<Profile?>(null) }

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
                TextButton(onClick = { showImportDialog = true }) {
                    Text(stringResource(R.string.profile_import))
                }
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
            Row {
                TextButton(onClick = { exportText = onExportAllProfiles() }) { Text("复制全部预设") }
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
                        onSetSwap = { swapTarget = profile },
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


    swapTarget?.let { target ->
        SwapPairsDialog(
            profile = target,
            onDismiss = { swapTarget = null },
            onSave = { pairs -> onSetSwapPairs(target.id, pairs) }
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
    onSetSwap: () -> Unit,
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
                    text = "${profile.layoutScreenWidth} x ${profile.layoutScreenHeight}",
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
                    text = { Text("交换对（换位键）") },
                    onClick = {
                        onMenuDismiss()
                        onSetSwap()
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
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(json))
                onDismiss()
            }) { Text(stringResource(R.string.profile_copy_to_clipboard)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.key_rebind_cancel)) }
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
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
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


/** 双键交换对：为该预设维护 SwapPair 列表（A/B 两按钮 + 换位键 + 还原键）。 */
@Composable
private fun SwapPairsDialog(
    profile: Profile,
    onDismiss: () -> Unit,
    onSave: (List<SwapPair>) -> Unit,
) {
    var pairs by remember(profile) { mutableStateOf(profile.swapPairs) }
    var aItemId by remember { mutableStateOf<Int?>(null) }
    var bItemId by remember { mutableStateOf<Int?>(null) }
    var swapOn by remember { mutableStateOf<Int?>(null) }
    var swapOff by remember { mutableStateOf<Int?>(null) }

    fun itemLabel(id: Int): String {
        val item = profile.items.firstOrNull { it.id == id } ?: return "按钮#$id(不存在)"
        val code = when (item) {
            is DraggableItem.FixedKey -> item.keyCode
            is DraggableItem.VariableKey -> item.keyCode
            is DraggableItem.CancelableKey -> item.keyCode
            is DraggableItem.WASDGroup -> null
        }
        return code?.keyCodeToString()?.removePrefix("KEYCODE_") ?: "按钮#$id"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("双键交换对") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "按下「换位键」时，A / B 两按钮的布局位置互换；按「还原键」各自回到默认位置。只改位置，不动键位绑定。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (pairs.isEmpty()) {
                    Text("（本预设暂无交换对）", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    pairs.forEach { pair ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${itemLabel(pair.aItemId)} ↔ ${itemLabel(pair.bItemId)}　换${pair.swapOnKeyCode.keyCodeToString()} / 回${pair.swapOffKeyCode.keyCodeToString()}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = { pairs = pairs - pair }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "删除")
                            }
                        }
                    }
                }
                HorizontalDivider()
                Text("添加交换对", style = MaterialTheme.typography.titleSmall)
                ItemIdPicker(label = "A 按钮", items = profile.items, selected = aItemId,
                    onPick = { aItemId = it })
                ItemIdPicker(label = "B 按钮", items = profile.items, selected = bItemId,
                    onPick = { bItemId = it })
                KeyConfigTextField(
                    title = "换位键（按此键交换）",
                    value = swapOn?.keyCodeToString().orEmpty(),
                    onKeyEvent = { event ->
                        if (event.nativeKeyEvent.action == NativeKeyEvent.ACTION_DOWN) {
                            val code = event.nativeKeyEvent.keyCode
                            if (code > NativeKeyEvent.KEYCODE_UNKNOWN) swapOn = code
                        }
                        true
                    }
                )
                KeyConfigTextField(
                    title = "还原键（按此键还原）",
                    value = swapOff?.keyCodeToString().orEmpty(),
                    onKeyEvent = { event ->
                        if (event.nativeKeyEvent.action == NativeKeyEvent.ACTION_DOWN) {
                            val code = event.nativeKeyEvent.keyCode
                            if (code > NativeKeyEvent.KEYCODE_UNKNOWN) swapOff = code
                        }
                        true
                    }
                )
                TextButton(
                    enabled = aItemId != null && bItemId != null && swapOn != null && swapOff != null,
                    onClick = {
                        pairs = pairs + SwapPair(aItemId = aItemId!!, bItemId = bItemId!!,
                            swapOnKeyCode = swapOn!!, swapOffKeyCode = swapOff!!)
                        aItemId = null; bItemId = null; swapOn = null; swapOff = null
                    }
                ) { Text("添加") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(pairs); onDismiss() }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 从预设的按钮列表里选一个成员（A 或 B）。 */
@Composable
private fun ItemIdPicker(
    label: String,
    items: List<DraggableItem>,
    selected: Int?,
    onPick: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box {
            Button(onClick = { expanded = true }) {
                Text(SwapItemLabel.of(items, selected))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                items.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(SwapItemLabel.of(items, item.id)) },
                        onClick = { expanded = false; onPick(item.id) }
                    )
                }
            }
        }
    }
}

private object SwapItemLabel {
    fun of(items: List<DraggableItem>, id: Int?): String {
        val item = id?.let { i -> items.firstOrNull { it.id == i } } ?: return "选择按钮"
        val code = when (item) {
            is DraggableItem.FixedKey -> item.keyCode
            is DraggableItem.VariableKey -> item.keyCode
            is DraggableItem.CancelableKey -> item.keyCode
            is DraggableItem.WASDGroup -> null
        }
        return code?.keyCodeToString()?.removePrefix("KEYCODE_") ?: "按钮#${item.id}"
    }
}
