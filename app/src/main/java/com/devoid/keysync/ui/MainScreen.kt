package com.devoid.keysync.ui

import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.devoid.keysync.AppInfo
import com.devoid.keysync.MainActivityViewModel
import com.devoid.keysync.R
import kotlinx.coroutines.launch

@Composable
fun DualTopBar(
    modifier: Modifier = Modifier,
    isPrimaryVisible: Boolean = true,
    isPrimaryExpanded: Boolean = true,
    primaryContent: @Composable () -> Unit,
    primaryExpandedContent: @Composable () -> Unit,
    secondaryContent: @Composable () -> Unit,
    onClick: () -> Unit
) {
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp),
        colors = CardDefaults.cardColors(contentColor = MaterialTheme.colorScheme.primary),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = CircleShape.copy(CornerSize(30.dp))
    )
    {
        AnimatedContent(
            modifier = Modifier
                .clickable(onClick = onClick)
                .heightIn(min = 55.dp)
                .wrapContentHeight(align = Alignment.CenterVertically)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            targetState = isPrimaryVisible
        ) {
            if (it) {
                Column {
                    primaryContent()
                    AnimatedVisibility(isPrimaryExpanded) {
                        primaryExpandedContent()
                    }
                }
            } else {
                secondaryContent()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SelectableAppIcon(
    modifier: Modifier = Modifier,
    icon: Drawable?,
    selected: Boolean = false,
    borderColor: Color = MaterialTheme.colorScheme.primary,
    onLongClick: () -> Unit,
    onClick: () -> Unit
) {
    Box(
        modifier
            .clip(CircleShape.copy(CornerSize(20)))
            .wrapContentSize()
            .border(
                width = if (selected) 3.dp else (-1).dp,
                color = borderColor,
                CircleShape.copy(CornerSize(20))
            )
            .combinedClickable(
                interactionSource = null,
                indication = null,
                onLongClick = onLongClick,
                onClick = onClick
            )
    ) {
        AsyncImage(
            icon,
            contentDescription = null,
            colorFilter = if (selected) ColorFilter.tint(
                borderColor.copy(alpha = 0.2f),
                blendMode = BlendMode.Overlay
            ) else null,
        )
    }
}

/**
 * Grid of app icons.
 *
 * Takes [AppInfo] (package + pre-resolved label) instead of bare package names
 * so the label is not looked up from PackageManager on every recomposition —
 * the add-game sheet re-renders on each keystroke of its search field.
 */
@Composable
fun PackagesLayout(
    modifier: Modifier = Modifier,
    viewModel: MainActivityViewModel,
    apps: List<AppInfo>,
    selected: Set<String> = emptySet(),
    onClick: (AppInfo) -> Unit,
    onLongClick: (AppInfo) -> Unit = {}
) {
    LazyVerticalGrid(
        modifier = modifier,
        columns = GridCells.Adaptive(minSize = 76.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = apps,
            key = { it.packageName }
        ) { app ->
            val icon by produceState<Drawable?>(initialValue = null, key1 = app.packageName) {
                value = viewModel.getPackageIcon(app.packageName)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SelectableAppIcon(
                    modifier = Modifier.size(60.dp),
                    icon = icon,
                    selected = app.packageName in selected,
                    onLongClick = { onLongClick(app) },
                    onClick = { onClick(app) }
                )
                Text(
                    app.label,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    appName: String,
    viewModel: MainActivityViewModel,
    snackbarHostState: SnackbarHostState,
    onNavigateToSettings: () -> Unit,
    onPackageIconClick: (String) -> Unit,
    onApplyPreset: (String, String) -> Unit = { _, _ -> }
) {
    val packages by viewModel.addedPackages.collectAsState()
    val appConfig by viewModel.appConfig.collectAsState()
    val connectedDevices by viewModel.connectedDevices.collectAsState()
    val presets by viewModel.presets.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Selection is keyed by package name. It used to be keyed by list index,
    // which pointed at the wrong app as soon as the list changed.
    var selectedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isAddSheetVisible by remember { mutableStateOf(false) }
    var devicesExpanded by remember { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<Set<String>?>(null) }
    var presetTarget by remember { mutableStateOf<String?>(null) }

    val inSelectionMode = selectedPackages.isNotEmpty()

    // Back used to do nothing while items were selected, leaving the user
    // stuck in selection mode with no visible way out.
    BackHandler(enabled = inSelectionMode) {
        selectedPackages = emptySet()
    }

    val apps = remember(packages) {
        packages.map { AppInfo(it, viewModel.getPackageLabel(it)) }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        topBar = {
            val animatedRotation by animateFloatAsState(
                targetValue = if (devicesExpanded) -90f else 0f,
                label = "rotation"
            )
            DualTopBar(
                modifier = Modifier
                    .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                    .padding(16.dp),
                isPrimaryVisible = !inSelectionMode,
                isPrimaryExpanded = devicesExpanded,
                primaryContent = {
                    Row {
                        Text(
                            stringResource(R.string.main_connected_devices),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                        Text(
                            modifier = Modifier.padding(end = 16.dp),
                            text = stringResource(
                                R.string.main_devices_count,
                                connectedDevices.size
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
                        )
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            modifier = Modifier.rotate(animatedRotation),
                            contentDescription = null
                        )
                    }
                },
                primaryExpandedContent = {
                    if (connectedDevices.isEmpty()) {
                        Text(
                            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                            text = stringResource(R.string.main_no_devices),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(Modifier.padding(top = 8.dp)) {
                            connectedDevices.forEach { (key, value) ->
                                item(key = key) {
                                    HorizontalDivider()
                                    Row(Modifier.padding(8.dp)) {
                                        Text("•", fontSize = 20.sp, color = Color(0xFF4CAF50))
                                        Text(
                                            value,
                                            Modifier.padding(start = 16.dp),
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 2
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                secondaryContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { selectedPackages = emptySet() }) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.main_clear_selection)
                            )
                        }
                        Text(
                            stringResource(R.string.main_selected_count, selectedPackages.size),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )

                        if (selectedPackages.size == 1) {
                            IconButton(onClick = { presetTarget = selectedPackages.first() }) {
                                Icon(
                                    Icons.Rounded.Settings,
                                    contentDescription = stringResource(R.string.cd_apply_preset),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        IconButton(onClick = { pendingRemoval = selectedPackages }) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.cd_delete_selected),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }) {
                if (inSelectionMode) {
                    selectedPackages = emptySet()
                } else {
                    devicesExpanded = !devicesExpanded
                }
            }
        },
        bottomBar = {
            BottomAppBar(
                actions = {
                    Text(
                        modifier = Modifier.padding(start = 16.dp),
                        text = appName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            Icons.Rounded.Settings,
                            contentDescription = stringResource(R.string.cd_advanced_setting)
                        )
                    }

                    Spacer(Modifier.width(32.dp))
                }, floatingActionButton = {
                    FloatingActionButton(onClick = {
                        isAddSheetVisible = true
                    }) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.cd_add_app)
                        )
                    }
                })
        }
    ) { contentPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(contentPadding)
                .consumeWindowInsets(contentPadding)
        ) {
            if (apps.isEmpty()) {
                EmptyGamesHint(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp)
                )
            } else {
                PackagesLayout(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    viewModel = viewModel,
                    apps = apps,
                    selected = selectedPackages,
                    onLongClick = { selectedPackages = selectedPackages + it.packageName },
                    onClick = { app ->
                        if (inSelectionMode) {
                            selectedPackages = if (app.packageName in selectedPackages) {
                                selectedPackages - app.packageName
                            } else {
                                selectedPackages + app.packageName
                            }
                        } else {
                            onPackageIconClick(app.packageName)
                        }
                    })
            }
        }
    }

    if (isAddSheetVisible) {
        AddGameSheet(
            viewModel = viewModel,
            alreadyAdded = packages.toSet(),
            onDismiss = { isAddSheetVisible = false },
            onPick = { app ->
                isAddSheetVisible = false
                val added = viewModel.addPackage(app.packageName)
                val message = if (added) {
                    context.getString(R.string.main_added, app.label)
                } else {
                    context.getString(R.string.main_already_added, app.label)
                }
                scope.launch { snackbarHostState.showSnackbar(message) }
            }
        )
    }

    pendingRemoval?.let { targets ->
        RemoveGamesDialog(
            count = targets.size,
            deletesKeymap = appConfig.deleteDataOnRemove,
            onDismiss = { pendingRemoval = null },
            onConfirm = {
                viewModel.removePackages(targets)
                selectedPackages = emptySet()
                pendingRemoval = null
            }
        )
    }

    presetTarget?.let { packageName ->
        PresetDialog(
            title = stringResource(
                R.string.main_preset_for,
                viewModel.getPackageLabel(packageName)
            ),
            presets = presets,
            onDismiss = { presetTarget = null },
            onApply = { preset ->
                presetTarget = null
                selectedPackages = emptySet()
                onApplyPreset(packageName, preset.id)
            }
        )
    }
}

@Composable
private fun EmptyGamesHint(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.main_empty_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            modifier = Modifier.padding(top = 8.dp),
            text = stringResource(R.string.main_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun RemoveGamesDialog(
    count: Int,
    deletesKeymap: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.main_remove_title)) },
        text = {
            Text(
                if (deletesKeymap) {
                    stringResource(R.string.main_remove_message_with_data, count)
                } else {
                    stringResource(R.string.main_remove_message, count)
                }
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/**
 * Full-height sheet listing launchable apps that are not in the launcher yet.
 *
 * The scan runs once in [LaunchedEffect] on a background dispatcher. The old
 * version polled the bottom sheet's settle state and then slept 500 ms before
 * showing anything, which made the sheet feel broken on slower devices.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddGameSheet(
    viewModel: MainActivityViewModel,
    alreadyAdded: Set<String>,
    onDismiss: () -> Unit,
    onPick: (AppInfo) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var allApps by remember { mutableStateOf<List<AppInfo>?>(null) }

    LaunchedEffect(Unit) {
        allApps = runCatching { viewModel.loadInstalledApps(alreadyAdded) }
            .getOrDefault(emptyList())
    }

    val filtered = remember(allApps, query) {
        val list = allApps ?: return@remember null
        val needle = query.trim()
        if (needle.isEmpty()) list
        else list.filter {
            it.label.contains(needle, ignoreCase = true) ||
                it.packageName.contains(needle, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        modifier = Modifier.fillMaxHeight(0.92f),
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.main_add_games),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                placeholder = { Text(stringResource(R.string.main_search_hint)) },
                singleLine = true
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 8.dp)
            ) {
                when {
                    filtered == null -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Text(
                                modifier = Modifier.padding(top = 12.dp),
                                text = stringResource(R.string.overlay_loading_apps),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    filtered!!.isEmpty() -> {
                        Text(
                            modifier = Modifier.align(Alignment.Center),
                            text = stringResource(R.string.main_search_no_match),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    else -> {
                        PackagesLayout(
                            viewModel = viewModel,
                            apps = filtered!!,
                            onLongClick = {},
                            onClick = onPick
                        )
                    }
                }
            }
        }
    }
}
