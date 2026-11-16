package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsApplications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import com.example.data.local.AquaPersona
import com.example.data.model.UserSettings
import com.example.ui.AquaMainViewModel
import com.example.ui.auth.signOutUser
import com.example.ui.components.AquaBackdropBox
import com.example.ui.components.AquaGlassCard
import com.example.ui.theme.AquaTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

@OptIn(ExperimentalMaterial3Api::class)
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

    var wakePhraseInput by remember(settings.wakeWordPhrase) { mutableStateOf(settings.wakeWordPhrase) }
    var directiveInput by remember(savedDirective) { mutableStateOf(savedDirective) }

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
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accentCyan,
        unfocusedBorderColor = c.borderGlow,
        focusedTextColor = c.textPrimary,
        unfocusedTextColor = c.textPrimary
    )

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
                    text = "Tune intelligence, personas, background voice, theme, and privacy",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Account & Sign Out
                item {
                    AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = currentUser?.displayName ?: "Authenticated Operator",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = c.textPrimary
                                )
                                Text(
                                    text = currentUser?.email ?: (currentUser?.uid ?: ""),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = c.textMuted
                                )
                            }
                            OutlinedButton(
                                onClick = { signOutUser(credentialManager, scope) },
                                modifier = Modifier.testTag("sign_out_button")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = c.error, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Sign Out", color = c.error)
                            }
                        }
                    }
                }

                // AI Persona & Custom System Directive
                item {
                    val activePersona = AquaPersona.fromId(activePersonaId)
                    AquaGlassCard(
                        borderColor = c.accentCyan.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = c.accentCyan)
                                Spacer(Modifier.width(8.dp))
                                Text("AI Persona & Custom Directive", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                            }
                            Text(
                                text = "${activePersona.label} (${activePersona.badge}): ${activePersona.promptGuidance}",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.accentTeal
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                AquaPersona.entries.forEach { persona ->
                                    val selected = persona.id == activePersonaId
                                    FilterChip(
                                        selected = selected,
                                        onClick = { viewModel.setAiPersona(persona.id) },
                                        label = { Text(persona.label) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = c.accentCyan.copy(alpha = 0.2f),
                                            selectedLabelColor = c.accentCyan,
                                            labelColor = c.textMuted
                                        ),
                                        modifier = Modifier.testTag("settings_persona_${persona.id}")
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = directiveInput,
                                onValueChange = { directiveInput = it },
                                label = { Text("Custom System Directive (Optional)") },
                                placeholder = { Text("e.g. Answer in bullet points; confirm chain before gas estimates.") },
                                minLines = 2,
                                maxLines = 4,
                                colors = fieldColors,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_custom_directive_input")
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { viewModel.updateCustomDirective(directiveInput) },
                                    colors = ButtonDefaults.buttonColors(containerColor = c.accentCyan, contentColor = c.canvas),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("settings_save_directive_button")
                                ) {
                                    Text("Save Directive", fontWeight = FontWeight.Bold)
                                }
                                if (savedDirective.isNotBlank()) {
                                    OutlinedButton(
                                        onClick = {
                                            directiveInput = ""
                                            viewModel.updateCustomDirective("")
                                        }
                                    ) {
                                        Text("Clear", color = c.error)
                                    }
                                }
                            }
                        }
                    }
                }

                // Gemini Model & Grounding Configuration
                item {
                    AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Psychology, contentDescription = null, tint = c.accentCyan)
                                Spacer(Modifier.width(8.dp))
                                Text("Gemini Intelligence & Tools", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
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
                                    colors = fieldColors,
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
                                title = "Crypto Wallet Read Permission (Aqua AI)",
                                subtitle = "Allow Aqua AI to read multi-chain balances, receive addresses, telemetry, ENS, allowances, payment URIs, and tx history (zero-key).",
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
                                title = "Crypto Wallet Write Permission (Aqua AI)",
                                subtitle = "Allow Aqua AI to create wallets/accounts, add tokens/networks, switch chains, edit contacts, sign messages, and prepare transfers/revokes (always gated by your approval).",
                                checked = settings.aiWalletAccessEnabled && settings.aiWalletTransferPrepareEnabled,
                                onCheckedChange = { enabled ->
                                    if (settings.aiWalletAccessEnabled) {
                                        viewModel.updateSettings(settings.copy(aiWalletTransferPrepareEnabled = enabled))
                                    } else if (enabled) {
                                        viewModel.updateSettings(settings.copy(aiWalletAccessEnabled = true, aiWalletTransferPrepareEnabled = true))
                                    }
                                }
                            )
                        }
                    }
                }

                // Personal Gemini API Key (Android Keystore Encrypted)
                item {
                    AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Key, contentDescription = null, tint = c.amber)
                                Spacer(Modifier.width(8.dp))
                                Text("User-Configurable Gemini API Key", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                            }
                            Text(
                                text = if (hasStoredKey) "Stored securely in Android Keystore AES-GCM ($maskedKey)."
                                else "Using managed Firebase AI Logic by default. Optionally supply your own Gemini API key below.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = c.textMuted
                            )

                            OutlinedTextField(
                                value = apiKeyInput,
                                onValueChange = { apiKeyInput = it },
                                label = { Text("Personal Gemini API Key (Optional)") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                colors = fieldColors,
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
                                    colors = ButtonDefaults.buttonColors(containerColor = c.accentCyan, contentColor = c.canvas),
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
                                        Text("Clear Key", color = c.error)
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
                    AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Mic, contentDescription = null, tint = c.accentTeal)
                                Spacer(Modifier.width(8.dp))
                                Text("Voice & 'Hey Aqua' Wake Word", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                            }

                            SettingSwitchRow(
                                title = "Prioritize Automation & Voice Tasks",
                                subtitle = if (automationKeepAlive || settings.backgroundAutomationEnabled) {
                                    "Persistent foreground notification active for routines & agent tasks."
                                } else {
                                    "Uses Android dataSync foreground service without requiring mic listener."
                                },
                                checked = settings.backgroundAutomationEnabled || automationKeepAlive,
                                onCheckedChange = { enable ->
                                    viewModel.setAutomationKeepAliveEnabled(enable)
                                }
                            )

                            SettingSwitchRow(
                                title = "Run App & Mic in Background",
                                subtitle = when {
                                    wakeRunning && wakePaused -> "Foreground service active (Mic paused)."
                                    wakeRunning -> "Foreground service & microphone active even when minimized."
                                    else -> "Runs Play-compliant microphone foreground service."
                                },
                                checked = settings.wakeWordEnabled || wakeRunning,
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

                            if (wakeRunning) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { viewModel.toggleBackgroundMicPaused() },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = if (wakePaused) "Resume Mic Listener" else "Pause Mic Listener",
                                            color = c.accentCyan
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = { viewModel.setWakeWordServiceEnabled(false) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Stop Service", color = c.error)
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = wakePhraseInput,
                                    onValueChange = { wakePhraseInput = it },
                                    label = { Text("Wake Word Phrase") },
                                    singleLine = true,
                                    colors = fieldColors,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = { viewModel.updateWakePhrase(wakePhraseInput) },
                                    enabled = wakePhraseInput.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(containerColor = c.accentCyan, contentColor = c.canvas)
                                ) {
                                    Text("Save", fontWeight = FontWeight.Bold)
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    runCatching {
                                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(intent)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Battery Optimization Settings (Keep App Alive)", color = c.accentTeal)
                            }

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
                                    colors = fieldColors,
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

                            Text("Speech Speed: ${String.format("%.2fx", settings.voiceSpeed)}", style = MaterialTheme.typography.labelMedium, color = c.textPrimary)
                            Slider(
                                value = settings.voiceSpeed.toFloat(),
                                onValueChange = { viewModel.updateSettings(settings.copy(voiceSpeed = it.toDouble())) },
                                valueRange = 0.5f..2.0f,
                                colors = SliderDefaults.colors(thumbColor = c.accentCyan, activeTrackColor = c.accentCyan)
                            )

                            Text("Voice Pitch: ${String.format("%.2fx", settings.voicePitch)}", style = MaterialTheme.typography.labelMedium, color = c.textPrimary)
                            Slider(
                                value = settings.voicePitch.toFloat(),
                                onValueChange = { viewModel.updateSettings(settings.copy(voicePitch = it.toDouble())) },
                                valueRange = 0.5f..1.8f,
                                colors = SliderDefaults.colors(thumbColor = c.accentTeal, activeTrackColor = c.accentTeal)
                            )
                        }
                    }
                }

                // Display & Theme
                item {
                    AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Palette, contentDescription = null, tint = c.accentCyan)
                                Spacer(Modifier.width(8.dp))
                                Text("Appearance & Window Brightness", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    "aqua_abyss" to "Aqua Abyss",
                                    "aqua_midnight" to "Midnight OLED",
                                    "aqua_light" to "Oceanic Light",
                                    "system" to "System"
                                ).forEach { (mode, label) ->
                                    FilterChip(
                                        selected = settings.darkThemeMode == mode,
                                        onClick = { viewModel.updateSettings(settings.copy(darkThemeMode = mode)) },
                                        label = { Text(label) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = c.accentCyan.copy(alpha = 0.2f),
                                            selectedLabelColor = c.accentCyan,
                                            labelColor = c.textMuted
                                        ),
                                        modifier = Modifier.testTag("settings_theme_$mode")
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.BrightnessMedium, contentDescription = null, tint = c.amber, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("App Window Brightness: ${brightnessSlider.toInt()}%", style = MaterialTheme.typography.labelMedium, color = c.textPrimary)
                            }
                            Slider(
                                value = brightnessSlider,
                                onValueChange = {
                                    brightnessSlider = it
                                    viewModel.setWindowBrightness(activity, it.toInt())
                                },
                                valueRange = 10f..100f,
                                colors = SliderDefaults.colors(thumbColor = c.amber, activeTrackColor = c.amber)
                            )
                        }
                    }
                }

                // Permissions & System Access Dashboard
                item {
                    val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    val locGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

                    AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Security, contentDescription = null, tint = c.success)
                                Spacer(Modifier.width(8.dp))
                                Text("Android Permissions Dashboard", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
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
                                Icon(Icons.Filled.SettingsApplications, contentDescription = null, tint = c.accentCyan, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Open Android App Permissions", color = c.accentCyan)
                            }
                        }
                    }
                }

                // Legal, Privacy Policy & Terms and Conditions
                item {
                    AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Policy, contentDescription = null, tint = c.accentCyan)
                                Spacer(Modifier.width(8.dp))
                                Text("Legal, Privacy Policy & Terms", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                            }
                            Text(
                                text = "Review how Aqua AI handles Google account data, Gemini AI requests, and non-custodial wallet keys, along with our Terms & Conditions.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = c.textMuted
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
                                    Icon(Icons.Filled.Policy, contentDescription = null, tint = c.accentCyan, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Privacy Policy", color = c.accentCyan)
                                }
                                OutlinedButton(
                                    onClick = { activeLegalDialog = "terms" },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("settings_terms_conditions_button")
                                ) {
                                    Icon(Icons.Filled.Description, contentDescription = null, tint = c.accentCyan, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Terms & Conditions", color = c.accentCyan)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (activeLegalDialog != null) {
            BackHandler { activeLegalDialog = null }
            val activeDoc = activeLegalDialog ?: "privacy"
            val externalUrl = when (activeDoc) {
                "index" -> "https://sras756.github.io/aqua-ai/"
                "terms" -> "https://sras756.github.io/aqua-ai/terms.html"
                else -> "https://sras756.github.io/aqua-ai/privacy.html"
            }
            var copiedFeedback by remember(activeDoc) { mutableStateOf(false) }

            val sections: List<Pair<String, String>> = remember(activeDoc) {
                when (activeDoc) {
                    "index" -> listOf(
                        "Aqua AI — Package: com.aistudio.aquaai.vqrzmn" to
                            "Autonomous Android AI Assistant, Workspace Hub & Self-Custodial Multi-Chain Crypto Wallet.",
                        "Overview" to
                            "Aqua AI is a native Android personal AI assistant combining Gemini intelligence, voice conversations, user-controlled live web research, device automation, optional Google Workspace and Google Drive backup tools, and a non-custodial multi-chain cryptocurrency wallet.",
                        "Core Capabilities" to
                            "• Gemini AI & Live Web Access: Multimodal reasoning, live web search, webpage reading, and public HTTP API inspection governed by your in-app toggles.\n" +
                            "• Google Workspace & Drive (Optional): Connects to Gmail, Calendar, Contacts, Sheets, Forms, Chat, and Google Drive strictly for user-requested actions and conversation backups.\n" +
                            "• Self-Custodial Crypto Wallet: Supports Ethereum, BNB Smart Chain, Polygon, Base, Arbitrum, Solana, and Bitcoin using local OS randomness and Android Keystore-backed encrypted key storage.",
                        "Support" to
                            "Developer support & privacy inquiries: srasmarkhor7289@gmail.com"
                    )
                    "terms" -> listOf(
                        "Effective Date: November 14, 2026" to
                            "App Package: com.aistudio.aquaai.vqrzmn | Developer Contact: srasmarkhor7289@gmail.com",
                        "1. Acceptance of Terms" to
                            "By downloading, accessing, or using Aqua AI, you agree to be bound by these Terms & Conditions and our Privacy Policy. If you do not agree, discontinue using the application.",
                        "2. AI Assistant, Web Tools & External Actions" to
                            "• Aqua AI uses generative AI and optional connected tools (web search, webpage inspection, device controls, and Google Workspace) to assist with your requests.\n" +
                            "• AI outputs, web summaries, and research notes may occasionally contain errors or outdated information; you remain responsible for reviewing critical information before acting on it.\n" +
                            "• High-impact actions (such as sending emails/messages, posting HTTP mutations, modifying wallet vaults/contacts, or signing blockchain transactions) present confirmation prompts that require your explicit review and approval.",
                        "3. Self-Custodial Wallet & Financial Disclaimers" to
                            "• Non-Custodial Software: Aqua Wallet is purely self-custodial software. You alone control your recovery phrases (mnemonics), private keys, PINs, and device security.\n" +
                            "• No Recovery Guarantee: The developer does not store or have access to your recovery phrases, private keys, or PINs and cannot recover lost funds or credentials.\n" +
                            "• Irreversible Blockchain Transactions: Digital asset transfers and smart-contract interactions (including ERC-20 approvals/revocations) are irreversible once broadcast. Always verify recipient addresses, network selection, gas fees, and risk warnings before authorizing signing.\n" +
                            "• Not Financial Advice: Portfolio analytics, token information, and CoinGecko market data displayed inside Aqua AI are informational only and do not constitute financial, investment, legal, or tax advice.",
                        "4. Third-Party Services" to
                            "Aqua AI integrates with external providers including Google (Firebase, Google Sign-In, Google Workspace/Drive), public blockchain RPC nodes, explorers, and CoinGecko. Availability and accuracy of third-party services are subject to those providers' uptime and terms.",
                        "5. Acceptable Use" to
                            "You agree not to misuse Aqua AI to violate applicable laws, infringe intellectual property rights, distribute malware, bypass rate limits, or attempt unauthorized access to external accounts, networks, or APIs.",
                        "6. Disclaimer of Warranties & Limitation of Liability" to
                            "Aqua AI is provided on an \"as is\" and \"as available\" basis without warranties of any kind, express or implied. To the fullest extent permitted by applicable law, the developer shall not be liable for any indirect, incidental, special, consequential, or financial damages arising from your use of the application.",
                        "7. Changes & Contact" to
                            "We reserve the right to update these Terms & Conditions from time to time. Continued use after updates become effective constitutes acceptance. Contact srasmarkhor7289@gmail.com with any questions."
                    )
                    else -> listOf(
                        "Effective Date: November 14, 2026" to
                            "App Package: com.aistudio.aquaai.vqrzmn | Developer Contact: srasmarkhor7289@gmail.com",
                        "1. Information We Access & Process" to
                            "• Account Information: When you sign in with Google via Firebase Authentication, Aqua AI receives your Firebase user ID, display name, email address, and profile photo URL to sync your preferences, memories, and chat sessions.\n" +
                            "• AI Prompts & Attachments: Messages, voice transcripts, images, and tool outputs you submit to the assistant are processed via Gemini / Firebase AI Logic to generate responses.\n" +
                            "• Live Web & Network Requests: When Full Internet Access is enabled, search queries and URLs you ask Aqua AI to inspect are fetched directly over HTTPS without advertising trackers.\n" +
                            "• Device Permissions (Opt-In): Camera (QR scanning and photo capture), Microphone (voice commands and wake-word service), Biometrics (local wallet unlock/signing), Coarse Location (optional Maps context), and Alarms (when requested).",
                        "2. Google Workspace & Google Drive Data (Limited Use Disclosure)" to
                            "• Google Drive (drive.appdata, drive.file): Used strictly to back up and restore your Aqua AI conversation history and memory snapshots inside your own Google Drive account.\n" +
                            "• Google Workspace (Gmail, Calendar, Contacts, Sheets, Forms, Chat): Accessed strictly on your explicit request to read, compose, schedule, inspect, or send items inside your connected Google account. Outbound sends require your confirmation.\n" +
                            "Aqua AI's use and transfer of information received from Google APIs adheres strictly to the Google API Services User Data Policy, including Limited Use requirements. Google user data is never sold, shared with brokers, or used for ads or generalized model training.",
                        "3. Self-Custodial Crypto Wallet & AI Wallet Controls" to
                            "• Local Key Custody: BIP-39 recovery phrases and private keys for Aqua Wallet are generated on-device using OS secure randomness and stored locally using Android Keystore-backed AES encryption. Private keys, seeds, and PINs are NEVER transmitted to Firebase, AI models, or third parties.\n" +
                            "• AI Wallet Boundary: You control Wallet Read Permission and Wallet Write Permission through switches in Settings and Wallet. Every transaction, revoke, vault/account creation, or contact edit requires your explicit approval and PIN/Biometric unlock when enabled.",
                        "4. Third-Party Blockchain & Market Services" to
                            "When checking balances, broadcasting signed transactions, resolving ENS names, or viewing prices, Aqua AI communicates directly with public blockchain RPC endpoints and CoinGecko market APIs using only public addresses or coin IDs.",
                        "5. Data Storage, Security, Retention & Deletion" to
                            "Cloud conversations and memories are stored in user-scoped Firebase Firestore records isolated to your account. Local wallet vaults remain encrypted in private device storage. You may sign out, clear conversations, delete local wallets, revoke Google OAuth access in your Google Account settings, or email srasmarkhor7289@gmail.com for complete data deletion.",
                        "6. Children's Privacy & Updates" to
                            "Aqua AI is not intended for children under 13 (or the applicable minimum age in your jurisdiction). Material policy updates will be reflected by an updated Effective Date."
                    )
                }
            }

            Surface(
                color = c.canvas,
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = when (activeDoc) {
                                "index" -> "Aqua AI Legal Overview"
                                "terms" -> "Terms & Conditions — Aqua AI"
                                else -> "Privacy Policy — Aqua AI"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = c.textPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { activeLegalDialog = null },
                            modifier = Modifier.testTag("legal_reader_close_button")
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Close", tint = c.accentCyan)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = activeDoc == "privacy",
                            onClick = { activeLegalDialog = "privacy" },
                            label = { Text("Privacy Policy") }
                        )
                        FilterChip(
                            selected = activeDoc == "terms",
                            onClick = { activeLegalDialog = "terms" },
                            label = { Text("Terms & Conditions") }
                        )
                        FilterChip(
                            selected = activeDoc == "index",
                            onClick = { activeLegalDialog = "index" },
                            label = { Text("Overview") }
                        )
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(sections) { (heading, body) ->
                            AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = heading,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = c.accentCyan,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = body,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = c.textMuted
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(c.cardSurface)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("Public Hosting URL (GitHub Pages)", style = MaterialTheme.typography.labelSmall, color = c.accentCyan)
                            Text(externalUrl, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                        }
                        OutlinedButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("Aqua AI Legal URL", externalUrl))
                                copiedFeedback = true
                            }
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = c.accentCyan, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (copiedFeedback) "Copied" else "Copy URL", color = c.accentCyan)
                        }
                    }
                }
            }
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
    val c = AquaTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = c.canvas, checkedTrackColor = c.accentCyan)
        )
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    onAction: () -> Unit
) {
    val c = AquaTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
            Text(
                text = if (granted) "Granted" else "Not Granted",
                style = MaterialTheme.typography.labelSmall,
                color = if (granted) c.success else c.amber
            )
        }
        OutlinedButton(onClick = onAction) {
            Text(if (granted) "Manage" else "Configure", color = c.accentCyan)
        }
    }
}
