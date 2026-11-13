package com.example.ui.navigation

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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
import com.example.ui.components.AuthorizationModal
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.BrowserScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.MemoryAndHistoryScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SkillsScreen
import com.example.ui.screens.WalletScreen
import com.example.ui.theme.AquaAbyss
import com.example.ui.theme.AquaAiTheme
import com.example.ui.theme.AquaCardSurface
import com.example.ui.theme.AquaCyan
import com.example.ui.theme.AquaDeepSurface
import com.example.ui.theme.AquaIceText
import com.example.ui.theme.AquaMutedText
import com.example.wallet.FlashWalletConnector
import com.google.firebase.Firebase
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
    AquaTab("skills", "Skills", Icons.Filled.Extension),
    AquaTab("browser", "Browser", Icons.Filled.Language),
    AquaTab("wallet", "Wallet", Icons.Filled.AccountBalanceWallet),
    AquaTab("memory", "Memory", Icons.Filled.Memory),
    AquaTab("settings", "Settings", Icons.Filled.Settings)
)

@Composable
fun AquaRootApp(
    sharedUris: List<Uri>,
    onConsumeSharedUris: () -> Unit,
    wakeCommand: String?,
    onConsumeWakeCommand: () -> Unit
) {
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
                onConsumeWakeCommand = onConsumeWakeCommand
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
    onConsumeWakeCommand: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val viewModel: AquaMainViewModel = viewModel(
        key = userId,
        factory = viewModelFactory {
            initializer {
                val dbId = context.getString(R.string.firestore_database_id)
                val firestore = FirebaseFirestore.getInstance(dbId)
                val firestoreRepo = AquaFirestoreRepository(firestore)
                AquaMainViewModel(
                    appContext = context,
                    currentUserId = userId,
                    firestoreRepo = firestoreRepo,
                    secureConfigStore = SecureConfigStore(context),
                    fileImportRepository = FileImportRepository(context),
                    deviceController = AndroidDeviceController(context),
                    browserController = AquaBrowserController(context),
                    walletConnector = FlashWalletConnector(context),
                    cacheDb = AquaCacheDatabase.getInstance(context),
                    driveHistoryRepo = GoogleDriveHistoryRepository(context)
                )
            }
        }
    )

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pendingAuth by viewModel.pendingAuthorization.collectAsStateWithLifecycle()
    val bannerMessage by viewModel.bannerMessage.collectAsStateWithLifecycle()
    val navEvent by viewModel.navigateToTabEvent.collectAsStateWithLifecycle()

    var currentTab by rememberSaveable { mutableStateOf("home") }
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

    if (currentTab != "home") {
        BackHandler { currentTab = "home" }
    }

    AquaAiTheme(darkThemeMode = settings.darkThemeMode) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 700.dp

            Scaffold(
                containerColor = AquaAbyss,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    if (!useRail) {
                        NavigationBar(containerColor = AquaDeepSurface) {
                            AQUA_TABS.forEach { tab ->
                                NavigationBarItem(
                                    selected = currentTab == tab.route,
                                    onClick = { currentTab = tab.route },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = { Text(tab.label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = AquaCyan,
                                        selectedTextColor = AquaCyan,
                                        indicatorColor = AquaCardSurface,
                                        unselectedIconColor = AquaMutedText,
                                        unselectedTextColor = AquaMutedText
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
                        NavigationRail(containerColor = AquaDeepSurface) {
                            AQUA_TABS.forEach { tab ->
                                NavigationRailItem(
                                    selected = currentTab == tab.route,
                                    onClick = { currentTab = tab.route },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = { Text(tab.label) },
                                    colors = NavigationRailItemDefaults.colors(
                                        selectedIconColor = AquaCyan,
                                        selectedTextColor = AquaCyan,
                                        indicatorColor = AquaCardSurface,
                                        unselectedIconColor = AquaMutedText,
                                        unselectedTextColor = AquaMutedText
                                    ),
                                    modifier = Modifier.testTag("rail_tab_${tab.route}")
                                )
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        when (currentTab) {
                            "home" -> HomeScreen(viewModel = viewModel, onOpenTab = { currentTab = it })
                            "skills" -> SkillsScreen(viewModel = viewModel)
                            "browser" -> BrowserScreen(viewModel = viewModel)
                            "wallet" -> WalletScreen(viewModel = viewModel)
                            "memory" -> MemoryAndHistoryScreen(viewModel = viewModel, onOpenHomeChat = { currentTab = "home" })
                            "settings" -> SettingsScreen(viewModel = viewModel)
                            else -> HomeScreen(viewModel = viewModel, onOpenTab = { currentTab = it })
                        }
                    }
                }
            }

            pendingAuth?.let { auth ->
                AuthorizationModal(
                    pending = auth,
                    onApprove = { viewModel.resolvePendingAuthorization(true) },
                    onDeny = { viewModel.resolvePendingAuthorization(false) }
                )
            }
        }
    }
}
