package com.example.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.MainActivity
import com.example.device.DeviceTelemetry
import com.example.launcher.engine.AquaLauncherEngine
import com.example.launcher.engine.LauncherAiActionProposal
import com.example.launcher.engine.LauncherAiController
import com.example.launcher.engine.LauncherAiUiAction
import com.example.launcher.engine.LauncherThemeEngine
import com.example.launcher.model.GestureActionType
import com.example.launcher.model.GestureTrigger
import com.example.launcher.model.LauncherWidgetType
import com.example.ui.screens.launcher.LauncherAppDrawerContent
import com.example.ui.screens.launcher.LauncherDefaultHomeAndSyncTab
import com.example.ui.screens.launcher.LauncherHomeWorkspace
import com.example.ui.screens.launcher.LauncherThemeAndLayoutTab
import com.example.ui.screens.launcher.LauncherUniversalSearchPanel
import com.example.ui.screens.launcher.LauncherWallpaperAndMapTab
import com.example.ui.screens.launcher.LauncherWallpaperBackdrop
import com.example.ui.screens.launcher.LauncherWidgetsGesturesAndGamingTab
import com.example.ui.theme.AquaAiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class StandaloneOverlay {
    HOME,
    DRAWER,
    SEARCH,
    THEMES,
    WALLPAPERS,
    WIDGETS_GAMING,
    SETTINGS
}

class AquaLauncherActivity : FragmentActivity() {

    private lateinit var engine: AquaLauncherEngine
    private lateinit var aiController: LauncherAiController

    private var homePressSignal by mutableIntStateOf(0)
    private var activeOverlay by mutableStateOf(StandaloneOverlay.HOME)
    private var editMode by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        engine = AquaLauncherEngine.getInstance(applicationContext)
        aiController = LauncherAiController(engine)

        setContent {
            AquaAiTheme(darkThemeMode = "aqua_abyss") {
                StandaloneAquaLauncherRoot(
                    engine = engine,
                    aiController = aiController,
                    homePressSignal = homePressSignal,
                    activeOverlay = activeOverlay,
                    onOverlayChange = { activeOverlay = it },
                    editMode = editMode,
                    onEditModeChange = { editMode = it },
                    onOpenFullAquaAi = { initialPrompt ->
                        val intent = Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            if (!initialPrompt.isNullOrBlank()) {
                                putExtra("aqua_launcher_prompt", initialPrompt)
                            }
                        }
                        startActivity(intent)
                    },
                    onOpenAquaBrowserQuery = { query ->
                        val intent = Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            putExtra("aqua_browser_query", query)
                        }
                        startActivity(intent)
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        engine.widgetManager.startListening()
        engine.startWatchingPackages()
    }

    override fun onResume() {
        super.onResume()
        engine.refreshDefaultLauncherStatus()
        engine.refreshThermalAndPerf()
    }

    override fun onStop() {
        super.onStop()
        engine.stopWatchingPackages()
        engine.widgetManager.stopListening()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME) || intent.action == Intent.ACTION_MAIN) {
            activeOverlay = StandaloneOverlay.HOME
            editMode = false
            homePressSignal++
        }
    }
}

@Composable
private fun StandaloneAquaLauncherRoot(
    engine: AquaLauncherEngine,
    aiController: LauncherAiController,
    homePressSignal: Int,
    activeOverlay: StandaloneOverlay,
    onOverlayChange: (StandaloneOverlay) -> Unit,
    editMode: Boolean,
    onEditModeChange: (Boolean) -> Unit,
    onOpenFullAquaAi: (String?) -> Unit,
    onOpenAquaBrowserQuery: (String) -> Unit
) {
    val state by engine.state.collectAsStateWithLifecycle()
    val installedApps by engine.installedApps.collectAsStateWithLifecycle()
    val weather by engine.weather.collectAsStateWithLifecycle()
    val weatherError by engine.weatherError.collectAsStateWithLifecycle()
    val calendarEvents by engine.calendarEvents.collectAsStateWithLifecycle()
    val isDefaultHome by engine.isDefaultLauncher.collectAsStateWithLifecycle()
    val currentHomePkg by engine.currentDefaultHomePackage.collectAsStateWithLifecycle()
    val thermal by engine.thermalSnapshot.collectAsStateWithLifecycle()
    val banner by engine.statusBanner.collectAsStateWithLifecycle()

    val resolvedTheme = remember(state.theme) { LauncherThemeEngine.resolve(state.theme) }
    val scope = rememberCoroutineScope()

    var telemetry by remember { mutableStateOf(engine.deviceController.getTelemetry()) }
    var storageStats by remember { mutableStateOf(engine.deviceController.getStorageAndMemoryStats()) }
    var pageJump by remember { mutableStateOf<Int?>(null) }
    var drawerTargetPage by remember { mutableIntStateOf(state.preferredHomePage) }
    var searchPrefill by remember { mutableStateOf("") }
    var showAiCommandDialog by remember { mutableStateOf(false) }
    var aiInputDraft by remember { mutableStateOf("") }
    var aiOutcomeMsg by remember { mutableStateOf<String?>(null) }
    var pendingProposal by remember { mutableStateOf<LauncherAiActionProposal?>(null) }
    var pendingWidgetBindProvider by remember { mutableStateOf<AppWidgetProviderInfo?>(null) }

    val roleHomeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        engine.refreshDefaultLauncherStatus()
    }

    val widgetConfigureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val widgetId = res.data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        val info = pendingWidgetBindProvider
        pendingWidgetBindProvider = null
        if (widgetId != -1 && res.resultCode == Activity.RESULT_OK) {
            engine.addWidgetToHome(
                LauncherWidgetType.SYSTEM_APP_WIDGET,
                pageIndex = state.preferredHomePage,
                systemWidgetId = widgetId,
                customTitle = info?.provider?.className ?: "System Widget"
            )
            onOverlayChange(StandaloneOverlay.HOME)
        } else if (widgetId != -1) {
            engine.widgetManager.deleteWidgetId(widgetId)
        }
    }

    val widgetBindLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val widgetId = res.data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        val info = pendingWidgetBindProvider
        if (res.resultCode == Activity.RESULT_OK && widgetId != -1) {
            if (info?.configure != null) {
                val cfgIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                    component = info.configure
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                }
                widgetConfigureLauncher.launch(cfgIntent)
            } else {
                pendingWidgetBindProvider = null
                engine.addWidgetToHome(
                    LauncherWidgetType.SYSTEM_APP_WIDGET,
                    pageIndex = state.preferredHomePage,
                    systemWidgetId = widgetId,
                    customTitle = info?.provider?.className ?: "System Widget"
                )
                onOverlayChange(StandaloneOverlay.HOME)
            }
        } else {
            pendingWidgetBindProvider = null
            if (widgetId != -1) engine.widgetManager.deleteWidgetId(widgetId)
        }
    }

    LaunchedEffect(homePressSignal) {
        if (homePressSignal > 0) {
            pageJump = state.preferredHomePage
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            telemetry = engine.deviceController.getTelemetry()
            storageStats = engine.deviceController.getStorageAndMemoryStats()
            delay(20_000L)
        }
    }

    // Consume back press inside Home Screen cleanly
    BackHandler(enabled = true) {
        when {
            editMode -> onEditModeChange(false)
            activeOverlay != StandaloneOverlay.HOME -> onOverlayChange(StandaloneOverlay.HOME)
            else -> pageJump = state.preferredHomePage
        }
    }

    fun executeAiCommand(cmd: String) {
        scope.launch {
            val outcome = aiController.executeCommand(cmd)
            aiOutcomeMsg = outcome.message
            if (outcome.pendingPermissionProposal != null) {
                pendingProposal = outcome.pendingPermissionProposal
            }
            when (outcome.uiAction) {
                LauncherAiUiAction.OPEN_APP_DRAWER,
                LauncherAiUiAction.OPEN_RECENT_APPS -> {
                    showAiCommandDialog = false
                    onOverlayChange(StandaloneOverlay.DRAWER)
                }
                LauncherAiUiAction.OPEN_WALLPAPER_PICKER -> {
                    showAiCommandDialog = false
                    onOverlayChange(StandaloneOverlay.WALLPAPERS)
                }
                LauncherAiUiAction.OPEN_UNIVERSAL_SEARCH -> {
                    searchPrefill = outcome.queryPayload
                    showAiCommandDialog = false
                    onOverlayChange(StandaloneOverlay.SEARCH)
                }
                LauncherAiUiAction.OPEN_GAMING_HUB -> {
                    showAiCommandDialog = false
                    onOverlayChange(StandaloneOverlay.WIDGETS_GAMING)
                }
                LauncherAiUiAction.OPEN_DEFAULT_HOME_SETTINGS -> {
                    showAiCommandDialog = false
                    onOverlayChange(StandaloneOverlay.SETTINGS)
                }
                LauncherAiUiAction.OPEN_BROWSER_SEARCH -> {
                    showAiCommandDialog = false
                    onOpenAquaBrowserQuery(outcome.queryPayload.ifBlank { "https://duckduckgo.com" })
                }
                LauncherAiUiAction.NONE -> Unit
            }
        }
    }

    fun dispatchGesture(trigger: GestureTrigger) {
        when (state.gestures.actionFor(trigger)) {
            GestureActionType.OPEN_APP_DRAWER -> onOverlayChange(StandaloneOverlay.DRAWER)
            GestureActionType.OPEN_UNIVERSAL_SEARCH -> onOverlayChange(StandaloneOverlay.SEARCH)
            GestureActionType.EXPAND_NOTIFICATIONS -> onOverlayChange(StandaloneOverlay.SETTINGS)
            GestureActionType.ENTER_EDIT_MODE -> onEditModeChange(true)
            GestureActionType.OPEN_LAUNCHER_SETTINGS -> onOverlayChange(StandaloneOverlay.THEMES)
            GestureActionType.OPEN_AQUA_AI -> showAiCommandDialog = true
            GestureActionType.OPEN_GAMING_HUB -> onOverlayChange(StandaloneOverlay.WIDGETS_GAMING)
            GestureActionType.LOCK_SCREEN_INFO -> engine.postBanner("Press Power button or check Android Security Settings to lock.")
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
                .navigationBarsPadding()
        ) {
            // Standalone Top Status + Quick Navigation Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = resolvedTheme.glassCardColor,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .border(1.dp, resolvedTheme.borderColor, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onOpenFullAquaAi(null) }
                        .testTag("standalone_open_aqua_ai")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = "Open Aqua AI App", tint = resolvedTheme.primaryColor, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Aqua AI", color = resolvedTheme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { onOverlayChange(StandaloneOverlay.WALLPAPERS) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Wallpaper, contentDescription = "Wallpapers", tint = resolvedTheme.textPrimary, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = { onOverlayChange(StandaloneOverlay.THEMES) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Palette, contentDescription = "Themes", tint = resolvedTheme.textPrimary, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = { onOverlayChange(StandaloneOverlay.WIDGETS_GAMING) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.SportsEsports, contentDescription = "Gaming & Widgets", tint = resolvedTheme.textPrimary, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = { onOverlayChange(StandaloneOverlay.SETTINGS) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Tune, contentDescription = "Launcher Settings", tint = resolvedTheme.textPrimary, modifier = Modifier.size(18.dp))
                    }
                    if (activeOverlay != StandaloneOverlay.HOME) {
                        IconButton(onClick = { onOverlayChange(StandaloneOverlay.HOME) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Back to Home", tint = resolvedTheme.primaryColor, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            if (!banner.isNullOrBlank()) {
                Surface(
                    color = resolvedTheme.glassCardColor,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(banner!!, color = resolvedTheme.textPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { engine.clearBanner() }) { Text("OK", fontSize = 11.sp) }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (activeOverlay) {
                    StandaloneOverlay.HOME -> LauncherHomeWorkspace(
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
                        onToggleEditMode = onEditModeChange,
                        requestedPageJump = pageJump,
                        onConsumePageJump = { pageJump = null },
                        onOpenDrawer = { curPage ->
                            drawerTargetPage = curPage
                            onOverlayChange(StandaloneOverlay.DRAWER)
                        },
                        onOpenSearch = { onOverlayChange(StandaloneOverlay.SEARCH) },
                        onOpenAiBar = { showAiCommandDialog = true },
                        onLaunchApp = { engine.launchApp(it) },
                        onTriggerGesture = { dispatchGesture(it) }
                    )
                    StandaloneOverlay.DRAWER -> LauncherAppDrawerContent(
                        engine = engine,
                        state = state,
                        installedApps = installedApps,
                        resolvedTheme = resolvedTheme,
                        targetAddPage = drawerTargetPage,
                        onLaunchApp = { engine.launchApp(it) }
                    )
                    StandaloneOverlay.SEARCH -> LauncherUniversalSearchPanel(
                        engine = engine,
                        resolvedTheme = resolvedTheme,
                        initialQuery = searchPrefill,
                        onNavigateLauncherSection = { id ->
                            onOverlayChange(
                                when (id) {
                                    "theme" -> StandaloneOverlay.THEMES
                                    "wallpaper" -> StandaloneOverlay.WALLPAPERS
                                    "widgets", "gestures", "gaming" -> StandaloneOverlay.WIDGETS_GAMING
                                    else -> StandaloneOverlay.SETTINGS
                                }
                            )
                        },
                        onAskAquaAi = { prompt -> onOpenFullAquaAi(prompt) },
                        onSearchWebInBrowser = { q -> onOpenAquaBrowserQuery(q) }
                    )
                    StandaloneOverlay.THEMES -> LauncherThemeAndLayoutTab(
                        engine = engine,
                        state = state,
                        resolvedTheme = resolvedTheme
                    )
                    StandaloneOverlay.WALLPAPERS -> LauncherWallpaperAndMapTab(
                        engine = engine,
                        state = state,
                        resolvedTheme = resolvedTheme
                    )
                    StandaloneOverlay.WIDGETS_GAMING -> LauncherWidgetsGesturesAndGamingTab(
                        engine = engine,
                        state = state,
                        installedApps = installedApps,
                        thermal = thermal,
                        resolvedTheme = resolvedTheme,
                        onRequestBindSystemWidget = { info ->
                            val newId = engine.widgetManager.allocateWidgetId()
                            if (newId != null) {
                                if (engine.widgetManager.bindWidgetIfAllowed(newId, info)) {
                                    if (info.configure != null) {
                                        pendingWidgetBindProvider = info
                                        val cfgIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                                            component = info.configure
                                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, newId)
                                        }
                                        widgetConfigureLauncher.launch(cfgIntent)
                                    } else {
                                        engine.addWidgetToHome(
                                            LauncherWidgetType.SYSTEM_APP_WIDGET,
                                            pageIndex = state.preferredHomePage,
                                            systemWidgetId = newId,
                                            customTitle = info.provider.className
                                        )
                                        onOverlayChange(StandaloneOverlay.HOME)
                                    }
                                } else {
                                    pendingWidgetBindProvider = info
                                    widgetBindLauncher.launch(engine.widgetManager.buildBindRequestIntent(newId, info))
                                }
                            }
                        },
                        onLaunchApp = { engine.launchApp(it) }
                    )
                    StandaloneOverlay.SETTINGS -> LauncherDefaultHomeAndSyncTab(
                        engine = engine,
                        state = state,
                        isDefaultHome = isDefaultHome,
                        currentHomePackage = currentHomePkg,
                        onRequestSetDefaultHome = {
                            val intent = engine.buildRequestDefaultHomeIntent()
                            if (intent != null) roleHomeLauncher.launch(intent) else engine.openHomeSettings()
                        },
                        onOpenStandaloneLauncher = null,
                        onPushCloudSyncNow = {
                            engine.postBanner("Launcher cloud sync is managed automatically when signed into Aqua AI.")
                        },
                        resolvedTheme = resolvedTheme
                    )
                }
            }
        }
    }

    if (showAiCommandDialog) {
        AlertDialog(
            onDismissRequest = { showAiCommandDialog = false },
            title = { Text("Aqua AI Launcher Command") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = aiInputDraft,
                        onValueChange = { aiInputDraft = it },
                        placeholder = { Text("e.g. \"Open YouTube\", \"Battery\", \"Live Map\"") },
                        singleLine = true
                    )
                    if (!aiOutcomeMsg.isNullOrBlank()) {
                        Text(aiOutcomeMsg!!, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (aiInputDraft.isNotBlank()) executeAiCommand(aiInputDraft)
                }) { Text("Run") }
            },
            dismissButton = {
                TextButton(onClick = { showAiCommandDialog = false }) { Text("Close") }
            }
        )
    }

    pendingProposal?.let { prop ->
        AlertDialog(
            onDismissRequest = { pendingProposal = null },
            title = { Text(prop.title) },
            text = { Text(prop.description) },
            confirmButton = {
                TextButton(onClick = {
                    aiController.executeApprovedProposal(prop)
                    pendingProposal = null
                }) { Text("Authorize") }
            },
            dismissButton = {
                TextButton(onClick = { pendingProposal = null }) { Text("Deny") }
            }
        )
    }
}
