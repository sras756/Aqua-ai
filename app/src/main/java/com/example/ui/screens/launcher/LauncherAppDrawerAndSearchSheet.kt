package com.example.ui.screens.launcher

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BrowsingTab
import com.example.data.model.Conversation
import com.example.data.model.MemoryItem
import com.example.launcher.engine.AquaLauncherEngine
import com.example.launcher.engine.ResolvedLauncherTheme
import com.example.launcher.model.AppCategory
import com.example.launcher.model.DiscoveredApp
import com.example.launcher.model.DrawerSortMode
import com.example.launcher.model.HomeSlotItem
import com.example.launcher.model.LauncherState
import com.example.launcher.model.SearchResultCategory
import com.example.launcher.model.UniversalSearchHit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LauncherAppDrawerContent(
    engine: AquaLauncherEngine,
    state: LauncherState,
    installedApps: List<DiscoveredApp>,
    resolvedTheme: ResolvedLauncherTheme,
    targetAddPage: Int = 0,
    onLaunchApp: (DiscoveredApp) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var selectedFilterTab by remember(state.drawerSortMode) { mutableStateOf(state.drawerSortMode.name) }
    var selectedCategoryFilter by remember { mutableStateOf<AppCategory?>(null) }
    var selectedCustomCategory by remember { mutableStateOf<String?>(null) }
    var actionTargetApp by remember { mutableStateOf<DiscoveredApp?>(null) }
    var showCreateCustomCategory by remember { mutableStateOf(false) }
    var customCategoryDraft by remember { mutableStateOf("") }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var folderNameDraft by remember { mutableStateOf("") }

    val filteredApps = remember(
        installedApps,
        state.hiddenPackages,
        state.favoritePackages,
        state.customCategories,
        query,
        selectedFilterTab,
        selectedCategoryFilter,
        selectedCustomCategory
    ) {
        val base = when (selectedFilterTab) {
            "HIDDEN" -> installedApps.filter { it.packageName in state.hiddenPackages }
            else -> installedApps.filter { it.packageName !in state.hiddenPackages }
        }
        val searched = if (query.isBlank()) base else {
            val q = query.trim().lowercase()
            base.filter {
                it.label.lowercase().contains(q) ||
                    it.packageName.lowercase().contains(q) ||
                    it.category.label.lowercase().contains(q)
            }
        }
        val catFiltered = when {
            selectedCustomCategory != null -> {
                val allowed = state.customCategories[selectedCustomCategory].orEmpty().toSet()
                searched.filter { it.packageName in allowed }
            }
            selectedCategoryFilter != null -> searched.filter { it.category == selectedCategoryFilter }
            else -> searched
        }
        when (selectedFilterTab) {
            DrawerSortMode.RECENT.name -> catFiltered.sortedByDescending { it.lastLaunchedMs }
            DrawerSortMode.FREQUENT.name -> catFiltered.sortedByDescending { it.launchCount }
            DrawerSortMode.FAVORITES.name -> catFiltered.filter { it.packageName in state.favoritePackages }
            DrawerSortMode.CATEGORY.name -> catFiltered.sortedWith(compareBy({ it.category.label }, { it.label.lowercase() }))
            else -> catFiltered.sortedBy { it.label.lowercase() }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Filter ${installedApps.size} installed apps...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear filter")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("drawer_search_input")
            )
            IconButton(
                onClick = { scope.launch { engine.refreshInstalledApps() } },
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(resolvedTheme.glassCardColor)
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh Installed Apps", tint = resolvedTheme.primaryColor)
            }
        }

        // Sort & Collection filter strip
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(DrawerSortMode.entries) { mode ->
                FilterChip(
                    selected = selectedFilterTab == mode.name && selectedCustomCategory == null,
                    onClick = {
                        selectedFilterTab = mode.name
                        selectedCustomCategory = null
                        engine.setDrawerSortMode(mode)
                    },
                    label = { Text(mode.label) }
                )
            }
            item {
                FilterChip(
                    selected = selectedFilterTab == "HIDDEN",
                    onClick = {
                        selectedFilterTab = "HIDDEN"
                        selectedCustomCategory = null
                    },
                    label = { Text("Hidden (${state.hiddenPackages.size})") }
                )
            }
            items(state.customCategories.keys.toList()) { customCat ->
                FilterChip(
                    selected = selectedCustomCategory == customCat,
                    onClick = {
                        selectedCustomCategory = if (selectedCustomCategory == customCat) null else customCat
                    },
                    label = { Text(customCat) }
                )
            }
            item {
                AssistChip(
                    onClick = { showCreateCustomCategory = true },
                    label = { Text("+ Category") }
                )
            }
        }

        if (selectedFilterTab == DrawerSortMode.CATEGORY.name && selectedCustomCategory == null) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    FilterChip(
                        selected = selectedCategoryFilter == null,
                        onClick = { selectedCategoryFilter = null },
                        label = { Text("All") }
                    )
                }
                items(AppCategory.entries) { cat ->
                    FilterChip(
                        selected = selectedCategoryFilter == cat,
                        onClick = { selectedCategoryFilter = cat },
                        label = { Text(cat.label) }
                    )
                }
            }
        }

        if (filteredApps.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (selectedFilterTab == "HIDDEN") {
                        "No hidden applications."
                    } else if (selectedFilterTab == DrawerSortMode.FAVORITES.name) {
                        "No favorite apps yet. Long-press any app to star it."
                    } else {
                        "No installed applications match '$query'."
                    },
                    color = resolvedTheme.textMuted,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val cols = if (maxWidth >= 600.dp) (state.theme.gridColumns + 2).coerceAtMost(8) else state.theme.gridColumns.coerceIn(3, 6)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(cols),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .combinedClickable(
                                    onClick = { onLaunchApp(app) },
                                    onLongClick = { actionTargetApp = app }
                                )
                                .padding(vertical = 4.dp, horizontal = 2.dp)
                                .testTag("drawer_app_${app.packageName}")
                        ) {
                            LauncherAppIconBadge(
                                app = app,
                                fallbackLabel = app.label,
                                themeConfig = state.theme,
                                resolvedTheme = resolvedTheme
                            )
                            Spacer(Modifier.height(5.dp))
                            if (state.theme.showIconLabels) {
                                Text(
                                    text = app.label,
                                    color = resolvedTheme.textPrimary,
                                    fontFamily = resolvedTheme.fontFamily,
                                    fontSize = (11.5f * state.theme.textScale).sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Long-press action menu dialog
    actionTargetApp?.let { app ->
        val isFav = app.packageName in state.favoritePackages
        val isDock = app.packageName in state.dockPackages
        val isHidden = app.packageName in state.hiddenPackages
        val homeFolders = state.items.filterIsInstance<HomeSlotItem.FolderItem>()

        AlertDialog(
            onDismissRequest = { actionTargetApp = null },
            title = {
                Column {
                    Text(app.label, fontWeight = FontWeight.Bold)
                    Text("${app.category.label} • ${app.packageName}", fontSize = 11.sp, color = resolvedTheme.textMuted)
                }
            },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item {
                        DrawerActionRow(Icons.AutoMirrored.Filled.OpenInNew, "Open ${app.label}") {
                            actionTargetApp = null
                            onLaunchApp(app)
                        }
                    }
                    if (app.shortcuts.isNotEmpty()) {
                        items(app.shortcuts) { sc ->
                            DrawerActionRow(Icons.Filled.Apps, "Shortcut: ${sc.shortLabel}") {
                                actionTargetApp = null
                                engine.appDiscovery.launchAppShortcut(sc)
                                    .onFailure { engine.postBanner(it.localizedMessage ?: "Shortcut failed") }
                            }
                        }
                    } else if (!engine.appDiscovery.hasShortcutPermission()) {
                        item {
                            Text(
                                text = "Dynamic app shortcuts require Aqua Launcher as default Home app.",
                                fontSize = 11.sp,
                                color = resolvedTheme.textMuted,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    item {
                        DrawerActionRow(Icons.Filled.Add, "Add to Home Page ${targetAddPage + 1}") {
                            engine.addAppToHome(app, pageIndex = targetAddPage)
                            actionTargetApp = null
                        }
                    }
                    item {
                        DrawerActionRow(Icons.Filled.PushPin, if (isDock) "Remove from Dock" else "Pin to Dock") {
                            engine.toggleDockApp(app.packageName)
                            actionTargetApp = null
                        }
                    }
                    item {
                        DrawerActionRow(Icons.Filled.Star, if (isFav) "Remove from Favorites" else "Add to Favorites") {
                            engine.toggleFavoriteApp(app.packageName)
                            actionTargetApp = null
                        }
                    }
                    item {
                        DrawerActionRow(Icons.Filled.VisibilityOff, if (isHidden) "Unhide from Drawer" else "Hide from Drawer") {
                            engine.toggleHiddenApp(app.packageName)
                            actionTargetApp = null
                        }
                    }
                    if (homeFolders.isNotEmpty()) {
                        items(homeFolders) { folder ->
                            DrawerActionRow(Icons.Filled.CreateNewFolder, "Add to Folder '${folder.name}'") {
                                engine.addAppToFolder(folder.id, app.packageName)
                                engine.postBanner("Added ${app.label} to '${folder.name}'.")
                                actionTargetApp = null
                            }
                        }
                    }
                    item {
                        DrawerActionRow(Icons.Filled.CreateNewFolder, "New Home Folder with ${app.label}...") {
                            folderNameDraft = app.category.label
                            showCreateFolderDialog = true
                        }
                    }
                    item {
                        DrawerActionRow(Icons.Filled.Info, "Android App Info") {
                            actionTargetApp = null
                            engine.appDiscovery.openAppInfo(app.packageName)
                        }
                    }
                    item {
                        DrawerActionRow(Icons.Filled.DeleteOutline, "Uninstall App (System Confirm)") {
                            actionTargetApp = null
                            engine.appDiscovery.requestUninstall(app.packageName)
                                .onFailure { engine.postBanner(it.localizedMessage ?: "Uninstall unavailable") }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionTargetApp = null }) { Text("Close") }
            }
        )
    }

    if (showCreateFolderDialog) {
        val seedApp = actionTargetApp
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Create Home Folder") },
            text = {
                OutlinedTextField(
                    value = folderNameDraft,
                    onValueChange = { folderNameDraft = it },
                    label = { Text("Folder Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val pkgs = listOfNotNull(seedApp?.packageName)
                    engine.createHomeFolder(folderNameDraft, pkgs, pageIndex = targetAddPage)
                    showCreateFolderDialog = false
                    actionTargetApp = null
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showCreateCustomCategory) {
        AlertDialog(
            onDismissRequest = { showCreateCustomCategory = false },
            title = { Text("Create Custom Category") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Adds a custom app drawer category tab populated with matching top apps.", fontSize = 12.sp)
                    OutlinedTextField(
                        value = customCategoryDraft,
                        onValueChange = { customCategoryDraft = it },
                        label = { Text("Category Name (e.g., Work, Crypto)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = customCategoryDraft.trim()
                    if (name.isNotBlank()) {
                        val matching = installedApps.filter {
                            it.label.lowercase().contains(name.lowercase()) ||
                                it.category.label.lowercase().contains(name.lowercase()) ||
                                it.packageName.lowercase().contains(name.lowercase())
                        }.ifEmpty { installedApps.take(6) }.map { it.packageName }
                        engine.createCustomCategory(name, matching)
                        selectedCustomCategory = name
                        customCategoryDraft = ""
                    }
                    showCreateCustomCategory = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateCustomCategory = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun DrawerActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 14.sp)
    }
}

@Composable
fun LauncherUniversalSearchPanel(
    engine: AquaLauncherEngine,
    resolvedTheme: ResolvedLauncherTheme,
    initialQuery: String = "",
    conversations: List<Conversation> = emptyList(),
    memories: List<MemoryItem> = emptyList(),
    browserTabs: List<BrowsingTab> = emptyList(),
    onNavigateLauncherSection: (String) -> Unit,
    onAskAquaAi: (String) -> Unit,
    onSearchWebInBrowser: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    var hits by remember { mutableStateOf<List<UniversalSearchHit>>(emptyList()) }
    var hasContacts by remember { mutableStateOf(engine.searchEngine.hasContactsPermission()) }
    var hasCalendar by remember { mutableStateOf(engine.searchEngine.hasCalendarPermission()) }

    val contactsPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasContacts = granted
    }
    val calendarPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCalendar = granted
    }

    LaunchedEffect(query, conversations.size, memories.size, browserTabs.size, hasContacts, hasCalendar) {
        delay(120L)
        hits = engine.searchUniversal(query, conversations, memories, browserTabs)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search apps, launcher tools, contacts, files, chats & web...") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("universal_search_input")
        )

        if (!hasContacts || !hasCalendar) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!hasContacts) {
                    item {
                        AssistChip(
                            onClick = { contactsPermLauncher.launch(Manifest.permission.READ_CONTACTS) },
                            label = { Text("Enable Contacts Search") }
                        )
                    }
                }
                if (!hasCalendar) {
                    item {
                        AssistChip(
                            onClick = { calendarPermLauncher.launch(Manifest.permission.READ_CALENDAR) },
                            label = { Text("Enable Calendar Search") }
                        )
                    }
                }
            }
        }

        val grouped = hits.groupBy { it.category }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            grouped.forEach { (category, categoryHits) ->
                item(key = "header_${category.name}") {
                    Text(
                        text = category.label.uppercase(),
                        color = resolvedTheme.primaryColor,
                        fontFamily = resolvedTheme.fontFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                items(categoryHits, key = { it.id }) { hit ->
                    Surface(
                        color = resolvedTheme.glassCardColor,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, resolvedTheme.borderColor.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                when (hit.category) {
                                    SearchResultCategory.APP -> engine.launchPackageOrQuery(hit.payload)
                                    SearchResultCategory.LAUNCHER_SETTING -> onNavigateLauncherSection(hit.payload)
                                    SearchResultCategory.LOCAL_FILE -> {
                                        engine.searchEngine.openDownloadsApp()
                                            .onFailure { engine.postBanner(it.localizedMessage ?: "Downloads unavailable") }
                                    }
                                    SearchResultCategory.CONTACT -> {
                                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(hit.payload)}")).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        runCatching { context.startActivity(intent) }
                                    }
                                    SearchResultCategory.CALENDAR -> {
                                        engine.deviceController.openAndroidSettings("general")
                                    }
                                    SearchResultCategory.BROWSER_TAB, SearchResultCategory.WEB -> {
                                        onSearchWebInBrowser(hit.payload)
                                    }
                                    SearchResultCategory.CONVERSATION -> {
                                        onAskAquaAi(hit.payload)
                                    }
                                }
                            }
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(hit.title, color = resolvedTheme.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(hit.subtitle, color = resolvedTheme.textMuted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
