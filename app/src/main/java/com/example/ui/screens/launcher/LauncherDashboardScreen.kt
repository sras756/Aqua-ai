package com.example.ui.screens.launcher

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.launcher.engine.LauncherAiActionProposal
import com.example.launcher.engine.LauncherAiUiAction
import com.example.launcher.engine.LauncherThemeEngine
import com.example.launcher.engine.ResolvedLauncherTheme
import com.example.launcher.model.GestureActionType
import com.example.launcher.model.GestureTrigger
import com.example.ui.AquaMainViewModel
import kotlinx.coroutines.launch

private enum class IntegratedLauncherSection(val id: String, val label: String) {
    HOME_PREVIEW("home_preview", "Home"),
    DRAWER("drawer", "Apps"),
    SEARCH("search", "Search"),
    AI_CONTROL("ai", "AI Control"),
    WALLPAPERS("wallpaper", "Wallpapers"),
    THEMES("theme", "Themes"),
    GAMING_WIDGETS("widgets", "Gaming & Widgets"),
    SETTINGS("default_home", "Default & Sync")
}

@Composable
fun LauncherDashboardScreen(
    viewModel: AquaMainViewModel,
    onOpenTab: (String) -> Unit
) {
    val engine = viewModel.launcherEngine
    val state by engine.state.collectAsStateWithLifecycle()
    val installedApps by engine.installedApps.collectAsStateWithLifecycle()
    val weather by engine.weather.collectAsStateWithLifecycle()
    val weatherError by engine.weatherError.collectAsStateWithLifecycle()
    val calendarEvents by engine.calendarEvents.collectAsStateWithLifecycle()
    val isDefaultHome by engine.isDefaultLauncher.collectAsStateWithLifecycle()
    val currentHomePkg by engine.currentDefaultHomePackage.collectAsStateWithLifecycle()
    val thermal by engine.thermalSnapshot.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val storageStats by viewModel.storageAndMemory.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val browserTabs by viewModel.browserTabs.collectAsStateWithLifecycle()
    val launcherBanner by engine.statusBanner.collectAsStateWithLifecycle()

    val resolvedTheme = remember(state.theme) { LauncherThemeEngine.resolve(state.theme) }
    val scope = rememberCoroutineScope()

    var section by rememberSaveable { mutableStateOf(IntegratedLauncherSection.HOME_PREVIEW.id) }
    var editMode by remember { mutableStateOf(false) }
    var pageJump by remember { mutableStateOf<Int?>(null) }
    var activeDrawerTargetPage by remember { mutableIntStateOf(0) }
    var searchPrefill by remember { mutableStateOf("") }
    var aiPromptDraft by remember { mutableStateOf("") }
    var aiResponseText by remember { mutableStateOf<String?>(null) }
    var pendingProposal by remember { mutableStateOf<LauncherAiActionProposal?>(null) }

    val roleHomeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        engine.refreshDefaultLauncherStatus()
    }

    DisposableEffect(Unit) {
        engine.startWatchingPackages()
        engine.refreshDefaultLauncherStatus()
        engine.refreshThermalAndPerf()
        onDispose {
            engine.stopWatchingPackages()
        }
    }

    LaunchedEffect(launcherBanner) {
        if (!launcherBanner.isNullOrBlank()) {
            // Surface inside local banner strip too
        }
    }

    if (section != IntegratedLauncherSection.HOME_PREVIEW.id) {
        BackHandler {
            section = IntegratedLauncherSection.HOME_PREVIEW.id
        }
    }

    fun handleAiCommand(command: String) {
        scope.launch {
            val outcome = viewModel.launcherAiController.executeCommand(command)
            aiResponseText = outcome.message
            if (outcome.pendingPermissionProposal != null) {
                pendingProposal = outcome.pendingPermissionProposal
            }
            when (outcome.uiAction) {
                LauncherAiUiAction.OPEN_APP_DRAWER,
                LauncherAiUiAction.OPEN_RECENT_APPS -> section = IntegratedLauncherSection.DRAWER.id
                LauncherAiUiAction.OPEN_WALLPAPER_PICKER -> section = IntegratedLauncherSection.WALLPAPERS.id
                LauncherAiUiAction.OPEN_UNIVERSAL_SEARCH -> {
                    searchPrefill = outcome.queryPayload
                    section = IntegratedLauncherSection.SEARCH.id
                }
                LauncherAiUiAction.OPEN_GAMING_HUB -> section = IntegratedLauncherSection.GAMING_WIDGETS.id
                LauncherAiUiAction.OPEN_DEFAULT_HOME_SETTINGS -> section = IntegratedLauncherSection.SETTINGS.id
                LauncherAiUiAction.OPEN_BROWSER_SEARCH -> onOpenTab("browser")
                LauncherAiUiAction.NONE -> Unit
            }
        }
    }

    fun handleGestureTrigger(trigger: GestureTrigger) {
        when (state.gestures.actionFor(trigger)) {
            GestureActionType.OPEN_APP_DRAWER -> section = IntegratedLauncherSection.DRAWER.id
            GestureActionType.OPEN_UNIVERSAL_SEARCH -> section = IntegratedLauncherSection.SEARCH.id
            GestureActionType.EXPAND_NOTIFICATIONS -> section = IntegratedLauncherSection.SETTINGS.id
            GestureActionType.ENTER_EDIT_MODE -> editMode = true
            GestureActionType.OPEN_LAUNCHER_SETTINGS -> section = IntegratedLauncherSection.THEMES.id
            GestureActionType.OPEN_AQUA_AI -> section = IntegratedLauncherSection.AI_CONTROL.id
            GestureActionType.OPEN_GAMING_HUB -> section = IntegratedLauncherSection.GAMING_WIDGETS.id
            GestureActionType.LOCK_SCREEN_INFO -> engine.postBanner("Use device Power button or Android Security Settings to lock screen.")
            GestureActionType.TOGGLE_FLASHLIGHT -> {
                engine.deviceController.toggleFlashlight()
                    .onSuccess { engine.postBanner(it) }
                    .onFailure { engine.postBanner(it.localizedMessage ?: "Flashlight unavailable") }
            }
            GestureActionType.OPEN_CAMERA -> {
                engine.openDeviceCamera()
                    .onFailure { engine.postBanner(it.localizedMessage ?: "Camera unavailable") }
            }
            GestureActionType.NONE -> Unit
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LauncherWallpaperBackdrop(
            config = state.wallpaper,
            wallpaperEngine = engine.wallpaperEngine,
            theme = resolvedTheme,
            animationSpeed = state.theme.animationSpeed
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Top Integrated Launcher Bar
            Surface(
                color = resolvedTheme.surfaceColor.copy(alpha = 0.85f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Aqua AI • Aqua Launcher",
                                color = resolvedTheme.textPrimary,
                                fontFamily = resolvedTheme.fontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = if (isDefaultHome) "Default System Home Active • Synced Engine" else "In-App Dashboard Preview • Tap Default to set system Home",
                                color = if (isDefaultHome) Color(0xFF4ADE80) else resolvedTheme.primaryColor,
                                fontSize = 11.sp
                            )
                        }
                        OutlinedButton(
                            onClick = { viewModel.openStandaloneLauncher() },
                            modifier = Modifier.testTag("integrated_open_standalone_btn")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Standalone", fontSize = 12.sp)
                        }
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(IntegratedLauncherSection.entries) { item ->
                            FilterChip(
                                selected = section == item.id,
                                onClick = { section = item.id },
                                label = { Text(item.label, fontSize = 12.sp) },
                                leadingIcon = {
                                    val ic = when (item) {
                                        IntegratedLauncherSection.HOME_PREVIEW -> Icons.Filled.Home
                                        IntegratedLauncherSection.DRAWER -> Icons.Filled.Apps
                                        IntegratedLauncherSection.SEARCH -> Icons.Filled.Search
                                        IntegratedLauncherSection.AI_CONTROL -> Icons.Filled.AutoAwesome
                                        IntegratedLauncherSection.WALLPAPERS -> Icons.Filled.Wallpaper
                                        IntegratedLauncherSection.THEMES -> Icons.Filled.Palette
                                        IntegratedLauncherSection.GAMING_WIDGETS -> Icons.Filled.SportsEsports
                                        IntegratedLauncherSection.SETTINGS -> Icons.Filled.Tune
                                    }
                                    Icon(ic, contentDescription = null, modifier = Modifier.size(15.dp))
                                }
                            )
                        }
                    }

                    if (!launcherBanner.isNullOrBlank()) {
                        Surface(
                            color = resolvedTheme.primaryColor.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(launcherBanner!!, color = resolvedTheme.textPrimary, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { engine.clearBanner() }) { Text("OK", fontSize = 11.sp) }
                            }
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (section) {
                    IntegratedLauncherSection.HOME_PREVIEW.id -> {
                        LauncherHomeWorkspace(
                            engine = engine,
                            state = state,
                            installedApps = installedApps,
                            resolvedTheme = resolvedTheme,
                            telemetry = telemetry,
                            storageStats = storageStats,
                            weather = weather,
                            weatherError = weatherError,
                            calendarEvents = calendarEvents,
                            editMode = editMode,
                            onToggleEditMode = { editMode = it },
                            requestedPageJump = pageJump,
                            onConsumePageJump = { pageJump = null },
                            onOpenDrawer = { curPage ->
                                activeDrawerTargetPage = curPage
                                section = IntegratedLauncherSection.DRAWER.id
                            },
                            onOpenSearch = { section = IntegratedLauncherSection.SEARCH.id },
                            onOpenAiBar = { section = IntegratedLauncherSection.AI_CONTROL.id },
                            onLaunchApp = { engine.launchApp(it) },
                            onTriggerGesture = { handleGestureTrigger(it) }
                        )
                    }
                    IntegratedLauncherSection.DRAWER.id -> {
                        LauncherAppDrawerContent(
                            engine = engine,
                            state = state,
                            installedApps = installedApps,
                            resolvedTheme = resolvedTheme,
                            targetAddPage = activeDrawerTargetPage,
                            onLaunchApp = { engine.launchApp(it) }
                        )
                    }
                    IntegratedLauncherSection.SEARCH.id -> {
                        LauncherUniversalSearchPanel(
                            engine = engine,
                            resolvedTheme = resolvedTheme,
                            initialQuery = searchPrefill,
                            conversations = conversations,
                            memories = memories,
                            browserTabs = browserTabs,
                            onNavigateLauncherSection = { targetId ->
                                section = when (targetId) {
                                    "theme" -> IntegratedLauncherSection.THEMES.id
                                    "wallpaper" -> IntegratedLauncherSection.WALLPAPERS.id
                                    "widgets", "gestures", "gaming" -> IntegratedLauncherSection.GAMING_WIDGETS.id
                                    else -> IntegratedLauncherSection.SETTINGS.id
                                }
                            },
                            onAskAquaAi = { prompt ->
                                onOpenTab("home")
                                viewModel.sendMessage(prompt)
                            },
                            onSearchWebInBrowser = { q ->
                                viewModel.navigateBrowser(q)
                                onOpenTab("browser")
                            }
                        )
                    }
                    IntegratedLauncherSection.AI_CONTROL.id -> {
                        IntegratedAiControlPane(
                            promptDraft = aiPromptDraft,
                            onPromptChange = { aiPromptDraft = it },
                            responseText = aiResponseText,
                            resolvedTheme = resolvedTheme,
                            onExecute = { cmd -> handleAiCommand(cmd) },
                            onEscalateToFullAquaAi = { prompt ->
                                onOpenTab("home")
                                if (prompt.isNotBlank()) viewModel.sendMessage(prompt)
                            }
                        )
                    }
                    IntegratedLauncherSection.WALLPAPERS.id -> {
                        LauncherWallpaperAndMapTab(
                            engine = engine,
                            state = state,
                            resolvedTheme = resolvedTheme
                        )
                    }
                    IntegratedLauncherSection.THEMES.id -> {
                        LauncherThemeAndLayoutTab(
                            engine = engine,
                            state = state,
                            resolvedTheme = resolvedTheme
                        )
                    }
                    IntegratedLauncherSection.GAMING_WIDGETS.id -> {
                        LauncherWidgetsGesturesAndGamingTab(
                            engine = engine,
                            state = state,
                            installedApps = installedApps,
                            thermal = thermal,
                            resolvedTheme = resolvedTheme,
                            onLaunchApp = { engine.launchApp(it) }
                        )
                    }
                    IntegratedLauncherSection.SETTINGS.id -> {
                        LauncherDefaultHomeAndSyncTab(
                            engine = engine,
                            state = state,
                            isDefaultHome = isDefaultHome,
                            currentHomePackage = currentHomePkg,
                            onRequestSetDefaultHome = {
                                val intent = engine.buildRequestDefaultHomeIntent()
                                if (intent != null) roleHomeLauncher.launch(intent) else engine.openHomeSettings()
                            },
                            onOpenStandaloneLauncher = { viewModel.openStandaloneLauncher() },
                            onPushCloudSyncNow = { viewModel.pushLauncherCloudSyncNow() },
                            resolvedTheme = resolvedTheme
                        )
                    }
                }
            }
        }
    }

    pendingProposal?.let { prop ->
        AlertDialog(
            onDismissRequest = { pendingProposal = null },
            title = { Text(prop.title) },
            text = { Text(prop.description) },
            confirmButton = {
                TextButton(onClick = {
                    val res = viewModel.launcherAiController.executeApprovedProposal(prop)
                    res.onSuccess { aiResponseText = it }
                        .onFailure { aiResponseText = it.localizedMessage ?: "Action unavailable" }
                    pendingProposal = null
                }) { Text("Authorize") }
            },
            dismissButton = {
                TextButton(onClick = {
                    aiResponseText = "Cancelled '${prop.title}'."
                    pendingProposal = null
                }) { Text("Deny") }
            }
        )
    }
}

@Composable
private fun IntegratedAiControlPane(
    promptDraft: String,
    onPromptChange: (String) -> Unit,
    responseText: String?,
    resolvedTheme: ResolvedLauncherTheme,
    onExecute: (String) -> Unit,
    onEscalateToFullAquaAi: (String) -> Unit
) {
    val examples = remember {
        listOf(
            "Open my browser",
            "Show my battery level",
            "Show recently used applications",
            "Turn on dark theme",
            "Change my wallpaper to live map",
            "Find an installed game",
            "Find my downloaded files",
            "Open the camera"
        )
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            color = resolvedTheme.glassCardColor,
            shape = resolvedTheme.cardShape,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, resolvedTheme.borderColor, resolvedTheme.cardShape)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Aqua AI Intelligent Launcher Control",
                    color = resolvedTheme.primaryColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Text(
                    text = "Natural-language commands run through an explicit Action-Permission gate over real Android APIs.",
                    color = resolvedTheme.textMuted,
                    fontSize = 12.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = promptDraft,
                        onValueChange = onPromptChange,
                        placeholder = { Text("Try \"Open my browser\" or \"Battery\"...") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("launcher_ai_input")
                    )
                    IconButton(
                        onClick = {
                            if (promptDraft.isNotBlank()) onExecute(promptDraft)
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(resolvedTheme.primaryColor)
                            .testTag("launcher_ai_send")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Execute Command", tint = Color(0xFF03101E))
                    }
                }
                if (!responseText.isNullOrBlank()) {
                    Surface(
                        color = resolvedTheme.surfaceColor.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(responseText, color = resolvedTheme.textPrimary, fontSize = 13.sp, modifier = Modifier.padding(10.dp))
                    }
                }
                OutlinedButton(
                    onClick = { onEscalateToFullAquaAi(promptDraft) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Send to Full Gemini Aqua AI Core")
                }
            }
        }

        Text("Instant Commands", color = resolvedTheme.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(examples) { cmd ->
                FilterChip(
                    selected = false,
                    onClick = {
                        onPromptChange(cmd)
                        onExecute(cmd)
                    },
                    label = { Text(cmd) }
                )
            }
        }
    }
}
