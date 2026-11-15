package com.example.ui.screens.launcher

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.device.DeviceTelemetry
import com.example.device.StorageAndMemoryStats
import com.example.launcher.engine.AquaLauncherEngine
import com.example.launcher.engine.ResolvedLauncherTheme
import com.example.launcher.model.DeviceCalendarEventItem
import com.example.launcher.model.DiscoveredApp
import com.example.launcher.model.GestureTrigger
import com.example.launcher.model.HomeSlotItem
import com.example.launcher.model.LauncherState
import com.example.launcher.model.LauncherWidgetType
import com.example.launcher.model.WeatherSnapshot
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LauncherHomeWorkspace(
    engine: AquaLauncherEngine,
    state: LauncherState,
    installedApps: List<DiscoveredApp>,
    resolvedTheme: ResolvedLauncherTheme,
    telemetry: DeviceTelemetry,
    storageStats: StorageAndMemoryStats,
    weather: WeatherSnapshot?,
    weatherError: String?,
    calendarEvents: List<DeviceCalendarEventItem>,
    editMode: Boolean,
    onToggleEditMode: (Boolean) -> Unit,
    requestedPageJump: Int?,
    onConsumePageJump: () -> Unit,
    onOpenDrawer: (Int) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenAiBar: () -> Unit,
    onLaunchApp: (DiscoveredApp) -> Unit,
    onTriggerGesture: (GestureTrigger) -> Unit,
    modifier: Modifier = Modifier
) {
    val pageCount = state.pageCount.coerceIn(1, 6)
    val pagerState = rememberPagerState(
        initialPage = state.preferredHomePage.coerceIn(0, pageCount - 1),
        pageCount = { pageCount }
    )
    val scope = rememberCoroutineScope()

    LaunchedEffect(requestedPageJump) {
        if (requestedPageJump != null) {
            val target = requestedPageJump.coerceIn(0, pageCount - 1)
            pagerState.animateScrollToPage(target)
            onConsumePageJump()
        }
    }

    var openedFolder by remember { mutableStateOf<HomeSlotItem.FolderItem?>(null) }
    var folderRenameDraft by remember { mutableStateOf("") }
    var showAddWidgetDialog by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("Essentials") }

    val appsByPkg = remember(installedApps) { installedApps.associateBy { it.packageName } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Two-finger vertical swipe detection
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var startAvgY: Float? = null
                    var fired = false
                    while (!fired) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            val avgY = pressed.map { it.position.y }.average().toFloat()
                            if (startAvgY == null) startAvgY = avgY
                            else if (abs(avgY - startAvgY) > 90f) {
                                onTriggerGesture(GestureTrigger.TWO_FINGER_SWIPE)
                                fired = true
                            }
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                var cumulativeDrag = 0f
                detectVerticalDragGestures(
                    onDragStart = { cumulativeDrag = 0f },
                    onVerticalDrag = { _, dragAmount -> cumulativeDrag += dragAmount },
                    onDragEnd = {
                        if (cumulativeDrag < -110f) {
                            onTriggerGesture(GestureTrigger.SWIPE_UP)
                        } else if (cumulativeDrag > 110f) {
                            onTriggerGesture(GestureTrigger.SWIPE_DOWN)
                        }
                    }
                )
            }
            .combinedClickable(
                onClick = {},
                onDoubleClick = { onTriggerGesture(GestureTrigger.DOUBLE_TAP) },
                onLongClick = { onTriggerGesture(GestureTrigger.LONG_PRESS) }
            )
    ) {
        // Optional Persistent Universal Search Pill
        if (state.theme.showSearchBar) {
            Surface(
                color = resolvedTheme.glassCardColor,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .border(1.dp, resolvedTheme.borderColor, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp))
                    .clickable(onClick = onOpenSearch)
                    .testTag("home_search_pill")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Search, contentDescription = "Search", tint = resolvedTheme.primaryColor, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Aqua Universal Search...",
                            color = resolvedTheme.textMuted,
                            fontFamily = resolvedTheme.fontFamily,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onOpenAiBar, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = "Aqua AI Command", tint = resolvedTheme.primaryColor, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onToggleEditMode(!editMode) }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = if (editMode) Icons.Filled.Close else Icons.Filled.Edit,
                                contentDescription = if (editMode) "Done Editing" else "Customize Home",
                                tint = resolvedTheme.textPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // Edit Mode Toolbar
        if (editMode) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    AssistChip(
                        onClick = { showAddWidgetDialog = true },
                        leadingIcon = { Icon(Icons.Filled.Widgets, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("+ Widget") }
                    )
                }
                item {
                    AssistChip(
                        onClick = { showCreateFolderDialog = true },
                        leadingIcon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("+ Folder") }
                    )
                }
                item {
                    AssistChip(
                        onClick = { engine.addOrRemoveHomePage(add = true) },
                        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("+ Page") }
                    )
                }
                if (state.pageCount > 1) {
                    item {
                        AssistChip(
                            onClick = { engine.addOrRemoveHomePage(add = false) },
                            leadingIcon = { Icon(Icons.Filled.Remove, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text("- Page") }
                        )
                    }
                }
                item {
                    AssistChip(
                        onClick = {
                            engine.setPreferredHomePage(pagerState.currentPage)
                            engine.postBanner("Page ${pagerState.currentPage + 1} set as preferred Home page.")
                        },
                        leadingIcon = { Icon(Icons.Filled.Home, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = {
                            Text(
                                if (state.preferredHomePage == pagerState.currentPage) "Preferred Home" else "Set Preferred Page"
                            )
                        }
                    )
                }
                item {
                    AssistChip(
                        onClick = { onToggleEditMode(false) },
                        label = { Text("Done") }
                    )
                }
            }
        }

        // Multi-page Workspace Pager
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { pageIdx ->
            val pageItems = remember(state.items, pageIdx) {
                state.items.filter { it.pageIndex == pageIdx }
            }
            val widgets = pageItems.filterIsInstance<HomeSlotItem.WidgetItem>()
            val smallSlots = pageItems.filter { it !is HomeSlotItem.WidgetItem }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Lay out widgets in responsive rows honoring colSpan vs theme.gridColumns
                val maxCols = state.theme.gridColumns.coerceIn(3, 6)
                val widgetRows = groupWidgetsIntoRows(widgets, maxCols)
                items(widgetRows.size) { rowIdx ->
                    val rowItems = widgetRows[rowIdx]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowItems.forEach { wItem ->
                            val weight = (wItem.colSpan.toFloat() / maxCols.toFloat()).coerceIn(0.25f, 1f)
                            Box(modifier = Modifier.weight(weight)) {
                                LauncherWidgetCard(
                                    item = wItem,
                                    engine = engine,
                                    state = state,
                                    resolvedTheme = resolvedTheme,
                                    telemetry = telemetry,
                                    storageStats = storageStats,
                                    weather = weather,
                                    weatherError = weatherError,
                                    calendarEvents = calendarEvents,
                                    installedApps = installedApps,
                                    editMode = editMode,
                                    onOpenAiBar = onOpenAiBar,
                                    onOpenUniversalSearch = onOpenSearch,
                                    onLaunchApp = onLaunchApp
                                )
                            }
                        }
                    }
                }

                // Lay out Folders & Pinned Home Apps into gridColumns rows
                if (smallSlots.isNotEmpty()) {
                    val slotRows = smallSlots.chunked(maxCols)
                    items(slotRows.size) { rowIndex ->
                        val row = slotRows[rowIndex]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (col in 0 until maxCols) {
                                val slot = row.getOrNull(col)
                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    when (slot) {
                                        is HomeSlotItem.AppItem -> {
                                            val discovered = appsByPkg[slot.packageName]
                                            HomeAppSlotTile(
                                                slot = slot,
                                                app = discovered,
                                                state = state,
                                                resolvedTheme = resolvedTheme,
                                                editMode = editMode,
                                                onLaunch = {
                                                    if (discovered != null) onLaunchApp(discovered)
                                                    else engine.launchPackageOrQuery(slot.packageName)
                                                },
                                                onMoveLeft = { engine.moveHomeSlot(slot.id, -1) },
                                                onMoveRight = { engine.moveHomeSlot(slot.id, 1) },
                                                onRemove = { engine.removeHomeSlot(slot.id) }
                                            )
                                        }
                                        is HomeSlotItem.FolderItem -> {
                                            HomeFolderSlotTile(
                                                folder = slot,
                                                resolvedTheme = resolvedTheme,
                                                state = state,
                                                editMode = editMode,
                                                onOpenFolder = {
                                                    folderRenameDraft = slot.name
                                                    openedFolder = slot
                                                },
                                                onRemove = { engine.removeHomeSlot(slot.id) }
                                            )
                                        }
                                        else -> Spacer(Modifier.height(1.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                if (pageItems.isEmpty()) {
                    item {
                        Surface(
                            color = resolvedTheme.glassCardColor,
                            shape = resolvedTheme.cardShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 24.dp)
                                .border(1.dp, resolvedTheme.borderColor, resolvedTheme.cardShape)
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Page ${pageIdx + 1} is empty", color = resolvedTheme.textPrimary, fontWeight = FontWeight.SemiBold)
                                Text("Open the App Drawer or Edit Mode to add apps, folders, or widgets here.", color = resolvedTheme.textMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }

        // Page indicator dots
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 0 until pageCount) {
                val active = pagerState.currentPage == i
                val isHome = state.preferredHomePage == i
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (active) 9.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                active -> resolvedTheme.primaryColor
                                isHome -> resolvedTheme.secondaryColor
                                else -> resolvedTheme.textMuted.copy(alpha = 0.4f)
                            }
                        )
                        .clickable { scope.launch { pagerState.animateScrollToPage(i) } }
                )
            }
        }

        // Favorite Application Dock
        Surface(
            color = resolvedTheme.dockColor,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .border(1.dp, resolvedTheme.borderColor, RoundedCornerShape(24.dp))
                .testTag("launcher_dock")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val dockApps = state.dockPackages.mapNotNull { appsByPkg[it] }.take(5)
                dockApps.forEach { app ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .combinedClickable(
                                onClick = { onLaunchApp(app) },
                                onLongClick = { onToggleEditMode(true) }
                            )
                            .padding(4.dp)
                    ) {
                        LauncherAppIconBadge(
                            app = app,
                            fallbackLabel = app.label,
                            themeConfig = state.theme,
                            resolvedTheme = resolvedTheme,
                            sizeDp = (state.theme.iconSizeDp - 4).coerceIn(40, 62)
                        )
                        if (state.theme.showDockLabels) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = app.label,
                                color = resolvedTheme.textPrimary,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(52.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // Always include App Drawer button on the dock
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onOpenDrawer(pagerState.currentPage) }
                        .padding(4.dp)
                        .testTag("dock_open_drawer_button")
                ) {
                    Box(
                        modifier = Modifier
                            .size((state.theme.iconSizeDp - 4).coerceIn(40, 62).dp)
                            .clip(resolvedTheme.iconShape)
                            .background(resolvedTheme.primaryColor.copy(alpha = 0.22f))
                            .border(1.5.dp, resolvedTheme.primaryColor, resolvedTheme.iconShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Apps,
                            contentDescription = "Open App Drawer",
                            tint = resolvedTheme.primaryColor
                        )
                    }
                    if (state.theme.showDockLabels) {
                        Spacer(Modifier.height(2.dp))
                        Text("Drawer", color = resolvedTheme.primaryColor, fontSize = 10.sp)
                    }
                }
            }
        }
    }

    // Folder Modal Dialog
    openedFolder?.let { folder ->
        val latestFolder = state.items.filterIsInstance<HomeSlotItem.FolderItem>().firstOrNull { it.id == folder.id } ?: folder
        AlertDialog(
            onDismissRequest = { openedFolder = null },
            title = {
                OutlinedTextField(
                    value = folderRenameDraft,
                    onValueChange = { folderRenameDraft = it },
                    label = { Text("Folder Name") },
                    singleLine = true
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (latestFolder.appPackages.isEmpty()) {
                        Text("No apps in this folder yet. Long-press an app in the Drawer to add it.", fontSize = 12.sp)
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(latestFolder.appPackages) { pkg ->
                                val app = appsByPkg[pkg]
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            openedFolder = null
                                            if (app != null) onLaunchApp(app) else engine.launchPackageOrQuery(pkg)
                                        }
                                        .padding(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    LauncherAppIconBadge(app, pkg, state.theme, resolvedTheme, sizeDp = 36)
                                    Spacer(Modifier.width(10.dp))
                                    Text(app?.label ?: pkg, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (folderRenameDraft.isNotBlank() && folderRenameDraft.trim() != latestFolder.name) {
                        engine.renameHomeFolder(latestFolder.id, folderRenameDraft.trim())
                    }
                    openedFolder = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { openedFolder = null }) { Text("Close") }
            }
        )
    }

    if (showAddWidgetDialog) {
        AlertDialog(
            onDismissRequest = { showAddWidgetDialog = false },
            title = { Text("Add Widget to Page ${pagerState.currentPage + 1}") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(LauncherWidgetType.entries.filter { it != LauncherWidgetType.SYSTEM_APP_WIDGET }) { wt ->
                        Surface(
                            color = resolvedTheme.surfaceColor.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    engine.addWidgetToHome(wt, pageIndex = pagerState.currentPage)
                                    showAddWidgetDialog = false
                                }
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(wt.title, color = resolvedTheme.textPrimary, fontWeight = FontWeight.SemiBold)
                                Text(wt.description, color = resolvedTheme.textMuted, fontSize = 11.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddWidgetDialog = false }) { Text("Close") }
            }
        )
    }

    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("New Folder on Page ${pagerState.currentPage + 1}") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Folder Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val samplePkgs = installedApps.take(3).map { it.packageName }
                    engine.createHomeFolder(newFolderName, samplePkgs, pageIndex = pagerState.currentPage)
                    showCreateFolderDialog = false
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun HomeAppSlotTile(
    slot: HomeSlotItem.AppItem,
    app: DiscoveredApp?,
    state: LauncherState,
    resolvedTheme: ResolvedLauncherTheme,
    editMode: Boolean,
    onLaunch: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onRemove: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onLaunch)
            .padding(4.dp)
    ) {
        LauncherAppIconBadge(
            app = app,
            fallbackLabel = slot.label,
            themeConfig = state.theme,
            resolvedTheme = resolvedTheme
        )
        if (state.theme.showIconLabels) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = app?.label ?: slot.label,
                color = resolvedTheme.textPrimary,
                fontFamily = resolvedTheme.fontFamily,
                fontSize = (11.5f * state.theme.textScale).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
        if (editMode) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(onClick = onMoveLeft, modifier = Modifier.size(22.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Move Left", tint = resolvedTheme.textMuted, modifier = Modifier.size(13.dp))
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(22.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove", tint = resolvedTheme.secondaryColor, modifier = Modifier.size(13.dp))
                }
                IconButton(onClick = onMoveRight, modifier = Modifier.size(22.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Move Right", tint = resolvedTheme.textMuted, modifier = Modifier.size(13.dp))
                }
            }
        }
    }
}

@Composable
private fun HomeFolderSlotTile(
    folder: HomeSlotItem.FolderItem,
    resolvedTheme: ResolvedLauncherTheme,
    state: LauncherState,
    editMode: Boolean,
    onOpenFolder: () -> Unit,
    onRemove: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpenFolder)
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(state.theme.iconSizeDp.coerceIn(36, 76).dp)
                .clip(resolvedTheme.iconShape)
                .background(resolvedTheme.glassCardColor)
                .border(1.5.dp, resolvedTheme.primaryColor.copy(alpha = 0.7f), resolvedTheme.iconShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Folder, contentDescription = folder.name, tint = resolvedTheme.primaryColor, modifier = Modifier.size(28.dp))
        }
        if (state.theme.showIconLabels) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = folder.name,
                color = resolvedTheme.textPrimary,
                fontFamily = resolvedTheme.fontFamily,
                fontSize = (11.5f * state.theme.textScale).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (editMode) {
            IconButton(onClick = onRemove, modifier = Modifier.size(22.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Delete Folder", tint = resolvedTheme.secondaryColor, modifier = Modifier.size(14.dp))
            }
        }
    }
}

private fun groupWidgetsIntoRows(
    widgets: List<HomeSlotItem.WidgetItem>,
    maxCols: Int
): List<List<HomeSlotItem.WidgetItem>> {
    val rows = mutableListOf<List<HomeSlotItem.WidgetItem>>()
    var currentRow = mutableListOf<HomeSlotItem.WidgetItem>()
    var usedCols = 0
    for (w in widgets) {
        val span = w.colSpan.coerceIn(1, maxCols)
        if (usedCols > 0 && usedCols + span > maxCols) {
            rows.add(currentRow)
            currentRow = mutableListOf()
            usedCols = 0
        }
        currentRow.add(w)
        usedCols += span
        if (usedCols >= maxCols) {
            rows.add(currentRow)
            currentRow = mutableListOf()
            usedCols = 0
        }
    }
    if (currentRow.isNotEmpty()) rows.add(currentRow)
    return rows
}
