package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.AquaMainViewModel
import com.example.ui.auth.signOutUser
import com.example.ui.components.AquaAnimatedEntrance
import com.example.ui.components.AquaBackdropBox
import com.example.ui.screens.settings.AccountSignOutCard
import com.example.ui.screens.settings.AiFreedomLabCard
import com.example.ui.screens.settings.AiPersonaAndDirectiveCard
import com.example.ui.screens.settings.AppearanceAndBrightnessCard
import com.example.ui.screens.settings.ElevenLabsVoiceStudioCard
import com.example.ui.screens.settings.GeminiIntelligenceCard
import com.example.ui.screens.settings.GoogleAiStudioKeyCard
import com.example.ui.screens.settings.LegalReaderFullScreenModal
import com.example.ui.screens.settings.LegalTrustLinksCard
import com.example.ui.screens.settings.PermissionsAndDiagnosticsCard
import com.example.ui.screens.settings.ProfileAgeAndGenderCard
import com.example.ui.screens.settings.VoiceAndWakeWordCard
import com.example.ui.theme.AquaTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

@Composable
fun SettingsScreen(viewModel: AquaMainViewModel) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }

    val c = AquaTheme.colors
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val wakeRunning by viewModel.wakeWordRunning.collectAsStateWithLifecycle()
    val wakePaused by viewModel.wakeWordPaused.collectAsStateWithLifecycle()
    val automationKeepAlive by viewModel.automationKeepAliveActive.collectAsStateWithLifecycle()
    val activePersonaId by viewModel.personaId.collectAsStateWithLifecycle()
    val savedDirective by viewModel.customDirective.collectAsStateWithLifecycle()
    val freedomConfig by viewModel.aiFreedomConfig.collectAsStateWithLifecycle()
    val elevenConfig by viewModel.elevenLabsConfig.collectAsStateWithLifecycle()
    val verifiedAiModels by viewModel.verifiedAiStudioModels.collectAsStateWithLifecycle()
    val aiKeyStatus by viewModel.aiKeyVerificationStatus.collectAsStateWithLifecycle()
    val elevenVoices by viewModel.elevenLabsVoices.collectAsStateWithLifecycle()
    val elevenSub by viewModel.elevenLabsSubscription.collectAsStateWithLifecycle()
    val elevenStatus by viewModel.elevenLabsStatus.collectAsStateWithLifecycle()
    val elevenBusy by viewModel.elevenLabsBusy.collectAsStateWithLifecycle()

    var activeLegalDialog by remember { mutableStateOf<String?>(null) }

    val exportDiagnosticsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        if (uri != null) {
            runCatching {
                val report = viewModel.buildSystemDiagnosticsReport()
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(report.toByteArray(Charsets.UTF_8))
                }
            }.onSuccess {
                viewModel.copyToClipboard("Aqua Diagnostics", "Saved diagnostic report to Markdown.")
            }
        }
    }

    val micAndNotifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val micOk = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micOk) {
            viewModel.setWakeWordServiceEnabled(true)
        }
    }

    val coarseLocationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    val currentUser = Firebase.auth.currentUser

    AquaBackdropBox(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    text = "AQUA SETTINGS & AI CORE",
                    style = MaterialTheme.typography.headlineSmall,
                    color = c.textPrimary
                )
                Text(
                    text = "Profile & age safety, personas, keys, voice, theme, and privacy",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    AquaAnimatedEntrance(index = 0) {
                        AccountSignOutCard(
                            currentUser = currentUser,
                            onSignOut = { signOutUser(credentialManager, scope) }
                        )
                    }
                }

                item {
                    AquaAnimatedEntrance(index = 1) {
                        ProfileAgeAndGenderCard(
                            settings = settings,
                            onSaveProfile = { age, genderId ->
                                viewModel.updateUserProfile(age, genderId)
                            }
                        )
                    }
                }

                item {
                    AquaAnimatedEntrance(index = 2) {
                        AiPersonaAndDirectiveCard(
                            activePersonaId = activePersonaId,
                            savedDirective = savedDirective,
                            onSelectPersona = { viewModel.setAiPersona(it) },
                            onSaveDirective = { viewModel.updateCustomDirective(it) }
                        )
                    }
                }

                item {
                    AquaAnimatedEntrance(index = 3) {
                        GeminiIntelligenceCard(
                            settings = settings,
                            onUpdateSettings = { viewModel.updateSettings(it) }
                        )
                    }
                }

                item {
                    AquaAnimatedEntrance(index = 4) {
                        GoogleAiStudioKeyCard(
                            viewModel = viewModel,
                            settings = settings,
                            freedomConfig = freedomConfig,
                            verifiedAiModels = verifiedAiModels,
                            aiKeyStatus = aiKeyStatus
                        )
                    }
                }

                item {
                    AquaAnimatedEntrance(index = 5) {
                        ElevenLabsVoiceStudioCard(
                            viewModel = viewModel,
                            elevenConfig = elevenConfig,
                            elevenVoices = elevenVoices,
                            elevenSub = elevenSub,
                            elevenStatus = elevenStatus,
                            elevenBusy = elevenBusy
                        )
                    }
                }

                item {
                    AquaAnimatedEntrance(index = 6) {
                        AiFreedomLabCard(
                            settings = settings,
                            freedomConfig = freedomConfig,
                            onUpdateFreedom = { viewModel.updateAiFreedomConfig(it) }
                        )
                    }
                }

                item {
                    VoiceAndWakeWordCard(
                        context = context,
                        settings = settings,
                        wakeRunning = wakeRunning,
                        wakePaused = wakePaused,
                        automationKeepAlive = automationKeepAlive,
                        onRequestMicAndNotif = { perms -> micAndNotifLauncher.launch(perms) },
                        viewModel = viewModel
                    )
                }

                item {
                    AppearanceAndBrightnessCard(
                        settings = settings,
                        onUpdateSettings = { viewModel.updateSettings(it) },
                        onBrightnessChange = { pct -> viewModel.setWindowBrightness(activity, pct) }
                    )
                }

                item {
                    PermissionsAndDiagnosticsCard(
                        context = context,
                        telemetry = telemetry,
                        onRequestMic = { micAndNotifLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) },
                        onOpenNotificationSettings = { viewModel.deviceController.openAndroidSettings("notifications") },
                        onRequestLocation = { coarseLocationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                        onOpenAppSettings = { viewModel.deviceController.openAndroidSettings("apps") },
                        onExportDiagnostics = { exportDiagnosticsLauncher.launch("aqua-diagnostics.md") }
                    )
                }

                item {
                    LegalTrustLinksCard(
                        onOpenLegalDoc = { doc -> activeLegalDialog = doc }
                    )
                }
            }
        }

        if (activeLegalDialog != null) {
            LegalReaderFullScreenModal(
                context = context,
                activeDoc = activeLegalDialog ?: "privacy",
                onChangeDoc = { activeLegalDialog = it },
                onOpenInAppBrowser = { url ->
                    activeLegalDialog = null
                    viewModel.navigateBrowser(url)
                    viewModel.openTab("browser")
                },
                onClose = { activeLegalDialog = null }
            )
        }
    }
}
