package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsApplications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.UserSettings
import com.example.ui.AquaMainViewModel
import com.example.ui.auth.signOutUser
import com.example.ui.theme.AquaAbyss
import com.example.ui.theme.AquaAmber
import com.example.ui.theme.AquaBorderGlow
import com.example.ui.theme.AquaCardSurface
import com.example.ui.theme.AquaCoralError
import com.example.ui.theme.AquaCyan
import com.example.ui.theme.AquaIceText
import com.example.ui.theme.AquaMintSuccess
import com.example.ui.theme.AquaMutedText
import com.example.ui.theme.AquaTeal
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: AquaMainViewModel) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val wakeRunning by viewModel.wakeWordRunning.collectAsStateWithLifecycle()

    var apiKeyInput by remember { mutableStateOf("") }
    var hasStoredKey by remember { mutableStateOf(viewModel.secureConfigStore.hasGeminiApiKey()) }
    var maskedKey by remember { mutableStateOf(viewModel.secureConfigStore.getMaskedGeminiApiKey()) }

    var modelExpanded by remember { mutableStateOf(false) }
    val models = listOf(
        UserSettings.MODEL_FLASH_38 to "Gemini 3.8 Flash (Default + Tools)",
        UserSettings.MODEL_FLASH_LITE to "Gemini 3.5 Flash-Lite (Ultra Fast)",
        UserSettings.MODEL_ARGON to "Gemini 4 Argon (Complex Reasoning)"
    )

    var langExpanded by remember { mutableStateOf(false) }
    val languages = listOf("en-US", "en-GB", "es-ES", "fr-FR", "de-DE", "ja-JP", "ko-KR", "zh-CN")

    var brightnessSlider by remember { mutableFloatStateOf(75f) }
    var activeLegalDialog by remember { mutableStateOf<String?>(null) }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AquaAbyss)
            .statusBarsPadding()
    ) {
        Text(
            text = "AQUA SETTINGS & AI CORE",
            style = MaterialTheme.typography.headlineSmall,
            color = AquaIceText,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Account & Sign Out
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AquaBorderGlow, RoundedCornerShape(18.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = currentUser?.displayName ?: "Authenticated Operator",
                                style = MaterialTheme.typography.titleMedium,
                                color = AquaIceText
                            )
                            Text(
                                text = currentUser?.email ?: (currentUser?.uid ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                                color = AquaMutedText
                            )
                        }
                        OutlinedButton(
                            onClick = { signOutUser(credentialManager, scope) },
                            modifier = Modifier.testTag("sign_out_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = AquaCoralError, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Sign Out", color = AquaCoralError)
                        }
                    }
                }
            }

            // Gemini Model & Grounding Configuration
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AquaBorderGlow, RoundedCornerShape(18.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Psychology, contentDescription = null, tint = AquaCyan)
                            Spacer(Modifier.width(8.dp))
                            Text("Gemini Intelligence & Tools", style = MaterialTheme.typography.titleMedium, color = AquaIceText)
                        }

                        ExposedDropdownMenuBox(
                            expanded = modelExpanded,
                            onExpandedChange = { modelExpanded = !modelExpanded }
                        ) {
                            OutlinedTextField(
                                value = models.firstOrNull { it.first == settings.preferredModel }?.second
                                    ?: settings.preferredModel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Active Gemini Model") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AquaCyan,
                                    unfocusedBorderColor = AquaBorderGlow,
                                    focusedTextColor = AquaIceText,
                                    unfocusedTextColor = AquaIceText
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                            )
                            ExposedDropdownMenu(
                                expanded = modelExpanded,
                                onDismissRequest = { modelExpanded = false }
                            ) {
                                models.forEach { (id, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            viewModel.updateSettings(settings.copy(preferredModel = id))
                                            modelExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        SettingSwitchRow(
                            title = "Deep Thinking (HIGH)",
                            subtitle = "Enable structured reasoning trace for complex agent tasks.",
                            checked = settings.thinkingEnabled,
                            onCheckedChange = { viewModel.updateSettings(settings.copy(thinkingEnabled = it)) }
                        )
                        SettingSwitchRow(
                            title = "Full Internet Access (Web, Reader & APIs)",
                            subtitle = "Allow Aqua AI to search the live web, fetch public URLs, and query HTTP APIs anytime.",
                            checked = settings.fullInternetAccessEnabled,
                            onCheckedChange = { viewModel.updateSettings(settings.copy(fullInternetAccessEnabled = it)) }
                        )
                        SettingSwitchRow(
                            title = "Google Search Grounding",
                            subtitle = "Cite live web information for factual queries.",
                            checked = settings.searchGroundingEnabled,
                            onCheckedChange = { viewModel.updateSettings(settings.copy(searchGroundingEnabled = it)) }
                        )
                        SettingSwitchRow(
                            title = "Google Maps Grounding",
                            subtitle = "Resolve places and directions using Maps grounding.",
                            checked = settings.mapsGroundingEnabled,
                            onCheckedChange = { viewModel.updateSettings(settings.copy(mapsGroundingEnabled = it)) }
                        )
                        SettingSwitchRow(
                            title = "Autonomous Reversible Actions",
                            subtitle = "Allow Aqua to run reversible tools automatically. Irreversible financial/security actions always ask.",
                            checked = settings.autonomousReversibleActions,
                            onCheckedChange = { viewModel.updateSettings(settings.copy(autonomousReversibleActions = it)) }
                        )
                        SettingSwitchRow(
                            title = "Crypto Wallet Access for Aqua AI",
                            subtitle = "Allow Aqua AI to read multi-chain balances, receive addresses, telemetry, ENS, allowances, and tx history (zero-key access).",
                            checked = settings.aiWalletAccessEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.updateSettings(
                                    settings.copy(
                                        aiWalletAccessEnabled = enabled,
                                        aiWalletTransferPrepareEnabled = if (enabled) settings.aiWalletTransferPrepareEnabled else false
                                    )
                                )
                            }
                        )
                        SettingSwitchRow(
                            title = "Allow AI to Prepare Transfers & Revokes",
                            subtitle = "Allow Aqua AI to draft unsigned transfers and ERC-20 revokes. Always requires your explicit confirmation & PIN/Biometrics.",
                            checked = settings.aiWalletAccessEnabled && settings.aiWalletTransferPrepareEnabled,
                            onCheckedChange = { enabled ->
                                if (settings.aiWalletAccessEnabled) {
                                    viewModel.updateSettings(settings.copy(aiWalletTransferPrepareEnabled = enabled))
                                }
                            }
                        )
                    }
                }
            }

            // Personal Gemini API Key (Android Keystore Encrypted)
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AquaBorderGlow, RoundedCornerShape(18.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Key, contentDescription = null, tint = AquaAmber)
                            Spacer(Modifier.width(8.dp))
                            Text("User-Configurable Gemini API Key", style = MaterialTheme.typography.titleMedium, color = AquaIceText)
                        }
                        Text(
                            text = if (hasStoredKey) "Stored securely in Android Keystore AES-GCM ($maskedKey)."
                            else "Using managed Firebase AI Logic by default. Optionally supply your own Gemini API key below.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AquaMutedText
                        )

                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it },
                            label = { Text("Personal Gemini API Key (Optional)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AquaCyan,
                                unfocusedBorderColor = AquaBorderGlow,
                                focusedTextColor = AquaIceText,
                                unfocusedTextColor = AquaIceText
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_api_key_input")
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    viewModel.secureConfigStore.saveGeminiApiKey(apiKeyInput)
                                    apiKeyInput = ""
                                    hasStoredKey = viewModel.secureConfigStore.hasGeminiApiKey()
                                    maskedKey = viewModel.secureConfigStore.getMaskedGeminiApiKey()
                                },
                                enabled = apiKeyInput.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = AquaCyan, contentColor = AquaAbyss),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_save_api_key_button")
                            ) {
                                Text("Encrypt & Save Key", fontWeight = FontWeight.Bold)
                            }
                            if (hasStoredKey) {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.secureConfigStore.clearGeminiApiKey()
                                        hasStoredKey = false
                                        maskedKey = ""
                                        viewModel.updateSettings(settings.copy(useCustomDirectApiKey = false))
                                    }
                                ) {
                                    Text("Clear Key", color = AquaCoralError)
                                }
                            }
                        }

                        SettingSwitchRow(
                            title = "Prefer Personal Gemini API Key",
                            subtitle = "Route Gemini requests through your stored personal key when present.",
                            checked = settings.useCustomDirectApiKey && hasStoredKey,
                            onCheckedChange = { enabled ->
                                if (enabled && !hasStoredKey) return@SettingSwitchRow
                                viewModel.updateSettings(settings.copy(useCustomDirectApiKey = enabled))
                            }
                        )
                    }
                }
            }

            // Voice & Wake Word Controls
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AquaBorderGlow, RoundedCornerShape(18.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Mic, contentDescription = null, tint = AquaTeal)
                            Spacer(Modifier.width(8.dp))
                            Text("Voice & 'Hey Aqua' Wake Word", style = MaterialTheme.typography.titleMedium, color = AquaIceText)
                        }

                        SettingSwitchRow(
                            title = "Hey Aqua Foreground Listener",
                            subtitle = if (wakeRunning) "Foreground microphone service active." else "Runs Play-compliant microphone foreground service.",
                            checked = settings.wakeWordEnabled,
                            onCheckedChange = { enable ->
                                if (!enable) {
                                    viewModel.setWakeWordServiceEnabled(false)
                                } else {
                                    val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        perms.add(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    val allGranted = perms.all {
                                        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                                    }
                                    if (allGranted) {
                                        viewModel.setWakeWordServiceEnabled(true)
                                    } else {
                                        micAndNotifLauncher.launch(perms.toTypedArray())
                                    }
                                }
                            }
                        )

                        SettingSwitchRow(
                            title = "Use Gemini Native TTS Audio",
                            subtitle = "Synthesize natural speech via Gemini TTS with Android TTS fallback.",
                            checked = settings.useGeminiTts,
                            onCheckedChange = { viewModel.updateSettings(settings.copy(useGeminiTts = it)) }
                        )

                        ExposedDropdownMenuBox(
                            expanded = langExpanded,
                            onExpandedChange = { langExpanded = !langExpanded }
                        ) {
                            OutlinedTextField(
                                value = settings.voiceLanguage,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Voice & Speech Language") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = langExpanded) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AquaCyan,
                                    unfocusedBorderColor = AquaBorderGlow,
                                    focusedTextColor = AquaIceText,
                                    unfocusedTextColor = AquaIceText
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                            )
                            ExposedDropdownMenu(
                                expanded = langExpanded,
                                onDismissRequest = { langExpanded = false }
                            ) {
                                languages.forEach { lang ->
                                    DropdownMenuItem(
                                        text = { Text(lang) },
                                        onClick = {
                                            viewModel.updateSettings(settings.copy(voiceLanguage = lang))
                                            langExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        Text("Speech Speed: ${String.format("%.2fx", settings.voiceSpeed)}", style = MaterialTheme.typography.labelMedium, color = AquaIceText)
                        Slider(
                            value = settings.voiceSpeed.toFloat(),
                            onValueChange = { viewModel.updateSettings(settings.copy(voiceSpeed = it.toDouble())) },
                            valueRange = 0.5f..2.0f,
                            colors = SliderDefaults.colors(thumbColor = AquaCyan, activeTrackColor = AquaCyan)
                        )

                        Text("Voice Pitch: ${String.format("%.2fx", settings.voicePitch)}", style = MaterialTheme.typography.labelMedium, color = AquaIceText)
                        Slider(
                            value = settings.voicePitch.toFloat(),
                            onValueChange = { viewModel.updateSettings(settings.copy(voicePitch = it.toDouble())) },
                            valueRange = 0.5f..1.8f,
                            colors = SliderDefaults.colors(thumbColor = AquaTeal, activeTrackColor = AquaTeal)
                        )
                    }
                }
            }

            // Display & Theme
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AquaBorderGlow, RoundedCornerShape(18.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Palette, contentDescription = null, tint = AquaCyan)
                            Spacer(Modifier.width(8.dp))
                            Text("Appearance & Window Brightness", style = MaterialTheme.typography.titleMedium, color = AquaIceText)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                "aqua_abyss" to "Aqua Dark",
                                "aqua_light" to "Aqua Light",
                                "system" to "System"
                            ).forEach { (mode, label) ->
                                FilterChip(
                                    selected = settings.darkThemeMode == mode,
                                    onClick = { viewModel.updateSettings(settings.copy(darkThemeMode = mode)) },
                                    label = { Text(label) }
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.BrightnessMedium, contentDescription = null, tint = AquaAmber, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("App Window Brightness: ${brightnessSlider.toInt()}%", style = MaterialTheme.typography.labelMedium, color = AquaIceText)
                        }
                        Slider(
                            value = brightnessSlider,
                            onValueChange = {
                                brightnessSlider = it
                                viewModel.setWindowBrightness(activity, it.toInt())
                            },
                            valueRange = 10f..100f,
                            colors = SliderDefaults.colors(thumbColor = AquaAmber, activeTrackColor = AquaAmber)
                        )
                    }
                }
            }

            // Permissions & System Access Dashboard
            item {
                val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                val locGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

                Card(
                    colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AquaBorderGlow, RoundedCornerShape(18.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Security, contentDescription = null, tint = AquaMintSuccess)
                            Spacer(Modifier.width(8.dp))
                            Text("Android Permissions Dashboard", style = MaterialTheme.typography.titleMedium, color = AquaIceText)
                        }
                        PermissionRow(
                            label = "Microphone (Voice & Wake Word)",
                            granted = micGranted,
                            onAction = { micAndNotifLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }
                        )
                        PermissionRow(
                            label = "Notification Access (Read Summaries)",
                            granted = telemetry.notificationAccessGranted,
                            onAction = { viewModel.deviceController.openAndroidSettings("notifications") }
                        )
                        PermissionRow(
                            label = "Coarse Location (Maps Context)",
                            granted = locGranted,
                            onAction = { coarseLocationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
                        )
                        OutlinedButton(
                            onClick = { viewModel.deviceController.openAndroidSettings("apps") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.SettingsApplications, contentDescription = null, tint = AquaCyan, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Open Android App Permissions", color = AquaCyan)
                        }
                    }
                }
            }

            // Legal, Privacy Policy & Terms and Conditions
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AquaBorderGlow, RoundedCornerShape(18.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Policy, contentDescription = null, tint = AquaCyan)
                            Spacer(Modifier.width(8.dp))
                            Text("Legal, Privacy Policy & Terms", style = MaterialTheme.typography.titleMedium, color = AquaIceText)
                        }
                        Text(
                            text = "Review how Aqua AI handles Google account data, Gemini AI requests, and non-custodial wallet keys, along with our Terms & Conditions.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AquaMutedText
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { activeLegalDialog = "privacy" },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_privacy_policy_button")
                            ) {
                                Icon(Icons.Filled.Policy, contentDescription = null, tint = AquaCyan, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Privacy Policy", color = AquaCyan)
                            }
                            OutlinedButton(
                                onClick = { activeLegalDialog = "terms" },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_terms_conditions_button")
                            ) {
                                Icon(Icons.Filled.Description, contentDescription = null, tint = AquaCyan, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Terms & Conditions", color = AquaCyan)
                            }
                        }
                    }
                }
            }
        }

        if (activeLegalDialog != null) {
            val isPrivacy = activeLegalDialog == "privacy"
            val externalUrl = if (isPrivacy) {
                "https://sras756.github.io/aqua-ai/privacy.html"
            } else {
                "https://sras756.github.io/aqua-ai/terms.html"
            }
            AlertDialog(
                onDismissRequest = { activeLegalDialog = null },
                containerColor = AquaCardSurface,
                title = {
                    Text(
                        text = if (isPrivacy) "Privacy Policy — Aqua AI" else "Terms & Conditions — Aqua AI",
                        color = AquaIceText
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (isPrivacy) {
                            Text("Effective Date: November 14, 2026\nApp Package: com.aistudio.aquaai.vqrzmn\nContact: srasmarkhor7289@gmail.com", style = MaterialTheme.typography.labelMedium, color = AquaCyan)
                            Text("1. Account & AI Processing: Sign-in uses Firebase Authentication to sync settings, memories, and conversations. Prompts and attachments are processed via Gemini / Firebase AI Logic when you interact with the assistant.", style = MaterialTheme.typography.bodySmall, color = AquaMutedText)
                            Text("2. Google Workspace & Drive Limited Use: Google Drive (drive.appdata, drive.file) and Google Workspace APIs (Gmail, Calendar, Contacts, Sheets, Forms, Chat) are accessed only when explicitly connected by you and strictly adhere to the Google API Services User Data Policy (Limited Use). Data is never sold or used for ads.", style = MaterialTheme.typography.bodySmall, color = AquaMutedText)
                            Text("3. Self-Custodial Wallet Keys: Mnemonics and private keys are created on-device and encrypted locally via Android Keystore. Seeds, keys, and PINs are never sent to AI models, Firebase, or third parties.", style = MaterialTheme.typography.bodySmall, color = AquaMutedText)
                            Text("4. Controls & Deletion: You can pause AI internet or wallet access anytime, clear conversations, revoke OAuth grants in your Google Account, or email srasmarkhor7289@gmail.com for data deletion.", style = MaterialTheme.typography.bodySmall, color = AquaMutedText)
                        } else {
                            Text("Effective Date: November 14, 2026\nApp Package: com.aistudio.aquaai.vqrzmn\nContact: srasmarkhor7289@gmail.com", style = MaterialTheme.typography.labelMedium, color = AquaCyan)
                            Text("1. Acceptance of Terms: By using Aqua AI, you agree to these Terms & Conditions and our Privacy Policy.", style = MaterialTheme.typography.bodySmall, color = AquaMutedText)
                            Text("2. AI & External Actions: Verify AI outputs before acting on critical decisions. High-impact actions (sending messages, mutations, or signing transactions) require explicit confirmation.", style = MaterialTheme.typography.bodySmall, color = AquaMutedText)
                            Text("3. Non-Custodial Wallet Responsibility: Aqua Wallet is self-custodial software. You alone are responsible for backing up your recovery phrase and securing your PIN. Blockchain transfers and approvals/revocations are irreversible once broadcast, and market data is not financial advice.", style = MaterialTheme.typography.bodySmall, color = AquaMutedText)
                            Text("4. Third-Party Services & Liability: Public RPC nodes, CoinGecko, and Google Workspace services remain subject to their respective availability and terms. Aqua AI is provided as-is without implied warranties.", style = MaterialTheme.typography.bodySmall, color = AquaMutedText)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { activeLegalDialog = null },
                        colors = ButtonDefaults.buttonColors(containerColor = AquaCyan, contentColor = AquaAbyss)
                    ) {
                        Text("Close")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(externalUrl)))
                            }
                        }
                    ) {
                        Icon(Icons.Filled.OpenInBrowser, contentDescription = null, tint = AquaCyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Open Web Page", color = AquaCyan)
                    }
                }
            )
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = AquaIceText)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = AquaMutedText)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = AquaAbyss, checkedTrackColor = AquaCyan)
        )
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = AquaIceText)
            Text(
                text = if (granted) "Granted" else "Not Granted",
                style = MaterialTheme.typography.labelSmall,
                color = if (granted) AquaMintSuccess else AquaAmber
            )
        }
        OutlinedButton(onClick = onAction) {
            Text(if (granted) "Manage" else "Configure", color = AquaCyan)
        }
    }
}
