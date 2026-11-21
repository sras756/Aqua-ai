package com.example.ui.navigation

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.R
import com.example.browser.AquaBrowserController
import com.example.data.local.AquaCacheDatabase
import com.example.data.local.SecureConfigStore
import com.example.data.repository.AquaFirestoreRepository
import com.example.data.repository.FileImportRepository
import com.example.data.repository.GoogleDriveHistoryRepository
import com.example.device.AndroidDeviceController
import com.example.ui.AquaMainViewModel
import com.example.ui.components.AiSetupSheet
import com.example.ui.components.AquaAdaptiveContainer
import com.example.ui.components.AuthorizationModal
import com.example.ui.components.CommandPaletteSheet
import com.example.ui.screens.AquaEcosystemHubScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.BrowserScreen
import com.example.ui.screens.DriveVaultScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.HubScreen
import com.example.ui.screens.MemoryAndHistoryScreen
import com.example.ui.screens.RoutinesScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SkillsScreen
import com.example.ui.screens.WalletScreen
import com.example.ui.theme.AquaAiTheme
import com.example.ui.theme.AquaTheme
import com.example.wallet.FlashWalletConnector
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

private data class AquaTab(
    val route: String,
    val label: String,
    val icon: ImageVector
)

private val AQUA_TABS = listOf(
    AquaTab("home", "Core", Icons.Filled.GraphicEq),
    AquaTab("routines", "Routines", Icons.Filled.AutoMode),
    AquaTab("browser", "Browser", Icons.Filled.Language),
    AquaTab("wallet", "Wallet", Icons.Filled.AccountBalanceWallet),
    AquaTab("hub", "Hub", Icons.Filled.Dashboard)
)

private val AQUA_RAIL_TABS = listOf(
    AquaTab("home", "Core", Icons.Filled.GraphicEq),
    AquaTab("hub", "Hub", Icons.Filled.Dashboard),
    AquaTab("routines", "Routines", Icons.Filled.AutoMode),
    AquaTab("drive", "Drive", Icons.Filled.CloudSync),
    AquaTab("skills", "Skills", Icons.Filled.Extension),
    AquaTab("browser", "Browser", Icons.Filled.Language),
    AquaTab("wallet", "Wallet", Icons.Filled.AccountBalanceWallet),
    AquaTab("memory", "Memory", Icons.Filled.Memory),
    AquaTab("ecosystem", "Control", Icons.Filled.Security),
    AquaTab("settings", "Settings", Icons.Filled.Settings)
)

@Composable
fun AquaRootApp(
    sharedUris: List<Uri>,
    onConsumeSharedUris: () -> Unit,
    wakeCommand: String?,
    onConsumeWakeCommand: () -> Unit,
    browserQuery: String? = null,
    onConsumeBrowserQuery: () -> Unit = {}
) {
    val appContext = LocalContext.current.applicationContext
    if (FirebaseApp.getApps(appContext).isEmpty()) {
        AquaAiTheme(darkThemeMode = "aqua_abyss") {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Firebase is not configured",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        text = "This build needs the Firebase Android configuration file (google-services.json). Add the file for this app and rebuild.",
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        return
    }

    val currentUser by produceState<FirebaseUser?>(initialValue = Firebase.auth.currentUser) {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            value = auth.currentUser
        }
        Firebase.auth.addAuthStateListener(listener)
        awaitDispose { Firebase.auth.removeAuthStateListener(listener) }
    }

    val user = currentUser
    if (user == null) {
        AquaAiTheme(darkThemeMode = "aqua_abyss") {
            AuthScreen(onAuthSuccess = { /* authState listener updates automatically */ })
        }
    } else {
        key(user.uid) {
            AuthenticatedAquaShell(
                userId = user.uid,
                sharedUris = sharedUris,
                onConsumeSharedUris = onConsumeSharedUris,
                wakeCommand = wakeCommand,
                onConsumeWakeCommand = onConsumeWakeCommand,
                browserQuery = browserQuery,
                onConsumeBrowserQuery = onConsumeBrowserQuery
            )
        }
    }
}

@Composable
private fun AuthenticatedAquaShell(
    userId: String,
    sharedUris: List<Uri>,
    onConsumeSharedUris: () -> Unit,
    wakeCommand: String?,
    onConsumeWakeCommand: () -> Unit,
    browserQuery: String? = null,
    onConsumeBrowserQuery: () -> Unit = {}
) {
    val context = LocalContext.current.applicationContext
    val viewModel: AquaMainViewModel = viewModel(
        key = userId,
        factory = viewModelFactory {
            initializer {
                val dbId = context.getString(R.string.firestore_database_id)
                val firestore = FirebaseFirestore.getInstance(dbId)
                val firestoreRepo = AquaFirestoreRepository(firestore)
                val secureConfig = SecureConfigStore(context)
                AquaMainViewModel(
                    appContext = context,
                    currentUserId = userId,
                    firestoreRepo = firestoreRepo,
                    secureConfigStore = secureConfig,
                    fileImportRepository = FileImportRepository(context),
                    deviceController = AndroidDeviceController(context),
                    browserController = AquaBrowserController(context),
                    walletConnector = FlashWalletConnector(context),
                    cacheDb = AquaCacheDatabase.getInstance(context),
                    driveHistoryRepo = GoogleDriveHistoryRepository(context, secureConfig)
                )
            }
        }
    )

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pendingAuth by viewModel.pendingAuthorization.collectAsStateWithLifecycle()
    val walletSecuritySettings by viewModel.walletCoordinator.securitySettings.collectAsStateWithLifecycle()
    val bannerMessage by viewModel.bannerMessage.collectAsStateWithLifecycle()
    val navEvent by viewModel.navigateToTabEvent.collectAsStateWithLifecycle()
    val routines by viewModel.routines.collectAsStateWithLifecycle()
    val personaId by viewModel.personaId.collectAsStateWithLifecycle()
    val aiSetupVisible by viewModel.aiSetupSheetVisible.collectAsStateWithLifecycle()

    var currentTab by rememberSaveable { mutableStateOf("home") }
    var showCommandPalette by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(navEvent) {
        if (!navEvent.isNullOrBlank()) {
            currentTab = navEvent!!
            viewModel.consumeNavigationEvent()
        }
    }

    LaunchedEffect(bannerMessage) {
        bannerMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBanner()
        }
    }

    LaunchedEffect(sharedUris) {
        if (sharedUris.isNotEmpty()) {
            val first = sharedUris.first()
            val lastSeg = (first.lastPathSegment ?: "").lowercase()
            val mime = context.contentResolver.getType(first)?.lowercase().orEmpty()
            val isSkill = lastSeg.endsWith(".yaml") || lastSeg.endsWith(".yml") ||
                (lastSeg.contains("skill") && (lastSeg.endsWith(".json") || lastSeg.endsWith(".md") || lastSeg.endsWith(".zip"))) ||
                mime.contains("yaml")
            if (isSkill) {
                viewModel.importSkillFromUri(first, sourceLabel = "Shared via Android")
                currentTab = "skills"
            } else {
                viewModel.importAttachmentUri(first)
                currentTab = "home"
            }
            onConsumeSharedUris()
        }
    }

    LaunchedEffect(wakeCommand) {
        if (wakeCommand != null) {
            currentTab = "home"
            if (wakeCommand.isNotBlank()) {
                viewModel.sendMessage(wakeCommand, speakReply = true)
            } else {
                viewModel.startVoiceCapture(autoSendAndSpeak = true)
            }
            onConsumeWakeCommand()
        }
    }

    LaunchedEffect(browserQuery) {
        if (!browserQuery.isNullOrBlank()) {
            currentTab = "browser"
            viewModel.navigateBrowser(browserQuery)
            onConsumeBrowserQuery()
        }
    }

    val hubSubRoutes = setOf("drive", "skills", "memory", "ecosystem", "settings")
    if (currentTab != "home") {
        BackHandler {
            currentTab = if (currentTab in hubSubRoutes) {
                "hub"
            } else {
                "home"
            }
        }
    }

    AquaAiTheme(darkThemeMode = settings.darkThemeMode) {
        val c = AquaTheme.colors

        AquaAdaptiveContainer(modifier = Modifier.fillMaxSize()) { spec ->
            val useRail = spec.useNavigationRail

            Scaffold(
                containerColor = c.canvas,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    if (!useRail) {
                        NavigationBar(
                            containerColor = c.deepSurface,
                            tonalElevation = 4.dp
                        ) {
                            AQUA_TABS.forEach { tab ->
                                val selected = currentTab == tab.route ||
                                    (tab.route == "hub" && currentTab in hubSubRoutes)
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { currentTab = tab.route },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = {
                                        Text(
                                            text = tab.label,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis,
                                            fontSize = 11.5.sp
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = c.accentCyan,
                                        selectedTextColor = c.accentCyan,
                                        indicatorColor = c.accentCyan.copy(alpha = 0.16f),
                                        unselectedIconColor = c.textMuted,
                                        unselectedTextColor = c.textMuted
                                    ),
                                    modifier = Modifier.testTag("nav_tab_${tab.route}")
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = innerPadding.calculateBottomPadding())
                ) {
                    if (useRail) {
                        NavigationRail(
                            containerColor = c.deepSurface,
                            windowInsets = WindowInsets(0, 0, 0, 0),
                            header = {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier
                                        .statusBarsPadding()
                                        .padding(top = 8.dp, bottom = 6.dp)
                                ) {
                                    Surface(
                                        color = c.cardSurface,
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .size(44.dp)
                                            .border(1.dp, c.accentCyan.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                                    ) {
                                        Image(
                                            painter = painterResource(id = R.drawable.img_aqua_vault_emblem),
                                            contentDescription = "Aqua AI Tablet Navigation",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(14.dp))
                                        )
                                    }
                                    Text(
                                        text = "AQUA",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = c.accentCyan,
                                        fontWeight = FontWeight.Bold
                                    )
                                    IconButton(
                                        onClick = { showCommandPalette = true },
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .clip(CircleShape)
                                            .background(c.accentCyan.copy(alpha = 0.16f))
                                            .border(1.dp, c.accentCyan.copy(alpha = 0.45f), CircleShape)
                                            .testTag("rail_command_palette_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Search,
                                            contentDescription = "Open Command Palette",
                                            tint = c.accentCyan
                                        )
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxHeight()
                                .displayCutoutPadding()
                                .navigationBarsPadding()
                                .width(84.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                                    .padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                AQUA_RAIL_TABS.forEach { tab ->
                                    NavigationRailItem(
                                        selected = currentTab == tab.route,
                                        onClick = { currentTab = tab.route },
                                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                                        label = {
                                            Text(
                                                text = tab.label,
                                                maxLines = 1,
                                                softWrap = false,
                                                overflow = TextOverflow.Ellipsis,
                                                fontSize = 11.sp
                                            )
                                        },
                                        colors = NavigationRailItemDefaults.colors(
                                            selectedIconColor = c.accentCyan,
                                            selectedTextColor = c.accentCyan,
                                            indicatorColor = c.accentCyan.copy(alpha = 0.16f),
                                            unselectedIconColor = c.textMuted,
                                            unselectedTextColor = c.textMuted
                                        ),
                                        modifier = Modifier.testTag("rail_tab_${tab.route}")
                                    )
                                }
                            }
                        }
                        VerticalDivider(
                            thickness = 1.dp,
                            color = c.borderGlow.copy(alpha = 0.45f)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .let { if (useRail) it.navigationBarsPadding() else it }
                    ) {
                        AnimatedContent(
                            targetState = currentTab,
                            transitionSpec = {
                                (fadeIn(tween(210)) + slideInVertically(tween(210)) { it / 28 })
                                    .togetherWith(fadeOut(tween(150)))
                            },
                            label = "aqua_route_transition"
                        ) { route ->
                            when (route) {
                                "home" -> HomeScreen(
                                    viewModel = viewModel,
                                    onOpenTab = { currentTab = it },
                                    onOpenCommandPalette = { showCommandPalette = true }
                                )
                                "hub" -> HubScreen(
                                    viewModel = viewModel,
                                    onOpenTab = { currentTab = it },
                                    onOpenCommandPalette = { showCommandPalette = true }
                                )
                                "routines" -> RoutinesScreen(viewModel = viewModel)
                                "drive" -> DriveVaultScreen(viewModel = viewModel)
                                "skills" -> SkillsScreen(viewModel = viewModel)
                                "browser" -> BrowserScreen(viewModel = viewModel)
                                "wallet" -> WalletScreen(viewModel = viewModel)
                                "memory" -> MemoryAndHistoryScreen(viewModel = viewModel, onOpenHomeChat = { currentTab = "home" })
                                "ecosystem" -> AquaEcosystemHubScreen(viewModel = viewModel)
                                "settings" -> SettingsScreen(viewModel = viewModel)
                                else -> HomeScreen(
                                    viewModel = viewModel,
                                    onOpenTab = { currentTab = it },
                                    onOpenCommandPalette = { showCommandPalette = true }
                                )
                            }
                        }
                    }
                }
            }

            if (showCommandPalette) {
                val installedApps = remember { viewModel.deviceController.getInstalledLaunchableApps() }
                CommandPaletteSheet(
                    activePersonaId = personaId,
                    routines = routines,
                    installedApps = installedApps,
                    onDismiss = { showCommandPalette = false },
                    onSelectPersona = { viewModel.setAiPersona(it) },
                    onOpenModule = { currentTab = it },
                    onRunRoutine = { viewModel.executeRoutine(it) },
                    onLaunchApp = { pkg -> viewModel.deviceController.launchApp(pkg) }
                )
            }

            pendingAuth?.let { auth ->
                AuthorizationModal(
                    pending = auth,
                    walletSecuritySettings = walletSecuritySettings,
                    onUnlockWithPin = { pin -> viewModel.walletCoordinator.unlockWithPin(pin) },
                    onUnlockWithBiometric = { viewModel.walletCoordinator.unlockWithBiometric() },
                    onApprove = { viewModel.resolvePendingAuthorization(true) },
                    onDeny = { viewModel.resolvePendingAuthorization(false) }
                )
            }

            if (aiSetupVisible) {
                AiSetupSheet(
                    viewModel = viewModel,
                    onOpenBrowserUrl = { url ->
                        viewModel.dismissAiSetupWizard(markCompleted = false)
                        viewModel.navigateBrowser(url)
                        currentTab = "browser"
                    },
                    onDismiss = { viewModel.dismissAiSetupWizard(markCompleted = true) }
                )
            }
        }
    }
}
