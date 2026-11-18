package com.example.ui.screens.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.data.local.AiFreedomConfig
import com.example.data.local.AquaPersona
import com.example.data.local.ElevenLabsConfig
import com.example.data.model.UserSettings
import com.example.domain.UserProfilePolicy
import com.example.ui.AquaMainViewModel
import com.example.ui.components.AquaGlassCard
import com.example.ui.theme.AquaTheme
import com.example.voice.ElevenLabsSubscription
import com.example.voice.ElevenLabsVoice
import kotlin.math.roundToInt

@Composable
fun AiPersonaAndDirectiveCard(
    activePersonaId: String,
    savedDirective: String,
    onSelectPersona: (String) -> Unit,
    onSaveDirective: (String) -> Unit
) {
    val c = AquaTheme.colors
    var directiveInput by remember(savedDirective) { mutableStateOf(savedDirective) }
    val activePersona = AquaPersona.fromId(activePersonaId)
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accentCyan,
        unfocusedBorderColor = c.borderGlow,
        focusedTextColor = c.textPrimary,
        unfocusedTextColor = c.textPrimary
    )

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
                        onClick = { onSelectPersona(persona.id) },
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
                    onClick = { onSaveDirective(directiveInput) },
                    colors = ButtonDefaults.buttonColors(containerColor = c.accentCyan, contentColor = c.onAccent),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("settings_save_directive_button")
                ) {
                    Text("Save Directive", fontWeight = FontWeight.Bold)
                }
                if (savedDirective.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            directiveInput = ""
                            onSaveDirective("")
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("Clear", color = c.error)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeminiIntelligenceCard(
    settings: UserSettings,
    onUpdateSettings: (UserSettings) -> Unit
) {
    val c = AquaTheme.colors
    var modelExpanded by remember { mutableStateOf(false) }
    val models = listOf(
        UserSettings.MODEL_FLASH_38 to "Gemini 3.8 Flash (Default + Tools)",
        UserSettings.MODEL_FLASH_LITE to "Gemini 3.5 Flash-Lite (Ultra Fast)",
        UserSettings.MODEL_ARGON to "Gemini 4 Argon (Complex Reasoning)"
    )
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accentCyan,
        unfocusedBorderColor = c.borderGlow,
        focusedTextColor = c.textPrimary,
        unfocusedTextColor = c.textPrimary
    )

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
                                onUpdateSettings(settings.copy(preferredModel = id))
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
                onCheckedChange = { onUpdateSettings(settings.copy(thinkingEnabled = it)) }
            )
            SettingSwitchRow(
                title = "Full Internet Access (Web, Reader & APIs)",
                subtitle = "Allow Aqua AI to search the live web, fetch public URLs, and query HTTP APIs anytime.",
                checked = settings.fullInternetAccessEnabled,
                onCheckedChange = { onUpdateSettings(settings.copy(fullInternetAccessEnabled = it)) }
            )
            SettingSwitchRow(
                title = "Google Search Grounding",
                subtitle = "Cite live web information for factual queries.",
                checked = settings.searchGroundingEnabled,
                onCheckedChange = { onUpdateSettings(settings.copy(searchGroundingEnabled = it)) }
            )
            SettingSwitchRow(
                title = "Google Maps Grounding",
                subtitle = "Resolve places and directions using Maps grounding.",
                checked = settings.mapsGroundingEnabled,
                onCheckedChange = { onUpdateSettings(settings.copy(mapsGroundingEnabled = it)) }
            )
            SettingSwitchRow(
                title = "Autonomous Reversible Actions",
                subtitle = "Allow Aqua to run reversible tools automatically. Irreversible financial/security actions always ask.",
                checked = settings.autonomousReversibleActions,
                onCheckedChange = { onUpdateSettings(settings.copy(autonomousReversibleActions = it)) }
            )
            SettingSwitchRow(
                title = "Crypto Wallet Read Permission (Aqua AI)",
                subtitle = "Allow Aqua AI to read multi-chain balances, receive addresses, telemetry, ENS, allowances, payment URIs, and tx history (zero-key).",
                checked = settings.aiWalletAccessEnabled,
                onCheckedChange = { enabled ->
                    onUpdateSettings(
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
                        onUpdateSettings(settings.copy(aiWalletTransferPrepareEnabled = enabled))
                    } else if (enabled) {
                        onUpdateSettings(settings.copy(aiWalletAccessEnabled = true, aiWalletTransferPrepareEnabled = true))
                    }
                }
            )
        }
    }
}

@Composable
fun GoogleAiStudioKeyCard(
    viewModel: AquaMainViewModel,
    settings: UserSettings,
    freedomConfig: AiFreedomConfig,
    verifiedAiModels: List<String>,
    aiKeyStatus: String?
) {
    val c = AquaTheme.colors
    var apiKeyInput by remember { mutableStateOf("") }
    var hasStoredKey by remember { mutableStateOf(viewModel.secureConfigStore.hasGeminiApiKey()) }
    var maskedKey by remember { mutableStateOf(viewModel.secureConfigStore.getMaskedGeminiApiKey()) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accentCyan,
        unfocusedBorderColor = c.borderGlow,
        focusedTextColor = c.textPrimary,
        unfocusedTextColor = c.textPrimary
    )

    AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Key, contentDescription = null, tint = c.amber)
                Spacer(Modifier.width(8.dp))
                Text("Google AI Studio API Key", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            }
            Text(
                text = if (hasStoredKey) "Stored securely in Android Keystore AES-GCM ($maskedKey)."
                else "Using managed Firebase AI Logic by default. Optionally supply your personal Google AI Studio key below.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textMuted
            )

            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { apiKeyInput = it },
                label = { Text("Google AI Studio API Key (Optional)") },
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
                        viewModel.saveGoogleAiStudioKey(apiKeyInput, enableDirectRouting = true)
                        apiKeyInput = ""
                        hasStoredKey = viewModel.secureConfigStore.hasGeminiApiKey()
                        maskedKey = viewModel.secureConfigStore.getMaskedGeminiApiKey()
                    },
                    enabled = apiKeyInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = c.accentCyan, contentColor = c.onAccent),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("settings_save_api_key_button")
                ) {
                    Text("Encrypt & Save", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { viewModel.verifyGoogleAiStudioKey(apiKeyInput) },
                    enabled = apiKeyInput.isNotBlank() || hasStoredKey,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("settings_verify_api_key_button")
                ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = c.accentCyan, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Verify", color = c.accentCyan)
                }
                if (hasStoredKey) {
                    OutlinedButton(
                        onClick = {
                            viewModel.clearGoogleAiStudioKey()
                            hasStoredKey = false
                            maskedKey = ""
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("Clear", color = c.error)
                    }
                }
            }

            if (!aiKeyStatus.isNullOrBlank()) {
                Text(
                    text = aiKeyStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (aiKeyStatus.startsWith("Verified")) c.success else c.amber
                )
            }

            if (verifiedAiModels.isNotEmpty()) {
                Text(
                    text = "Verified Google AI Studio Models (Tap to apply):",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textMuted
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    verifiedAiModels.forEach { modelId ->
                        val selected = freedomConfig.customModelOverride == modelId || settings.preferredModel == modelId
                        FilterChip(
                            selected = selected,
                            onClick = {
                                viewModel.updateAiFreedomConfig(freedomConfig.copy(customModelOverride = modelId))
                                viewModel.updateSettings(settings.copy(preferredModel = modelId))
                            },
                            label = { Text(modelId) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = c.accentCyan.copy(alpha = 0.22f),
                                selectedLabelColor = c.accentCyan,
                                labelColor = c.textMuted
                            )
                        )
                    }
                }
            }

            SettingSwitchRow(
                title = "Prefer Personal Google AI Studio Key",
                subtitle = "Route Gemini requests through your stored personal key when present.",
                checked = settings.useCustomDirectApiKey && hasStoredKey,
                onCheckedChange = { enabled ->
                    if (enabled && !hasStoredKey) return@SettingSwitchRow
                    viewModel.updateSettings(settings.copy(useCustomDirectApiKey = enabled))
                }
            )

            OutlinedButton(
                onClick = { viewModel.openAiSetupWizard() },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("settings_open_setup_wizard_button")
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = c.accentCyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open Post-Login AI Setup Studio", color = c.accentCyan)
            }
        }
    }
}

@Composable
fun ElevenLabsVoiceStudioCard(
    viewModel: AquaMainViewModel,
    elevenConfig: ElevenLabsConfig,
    elevenVoices: List<ElevenLabsVoice>,
    elevenSub: ElevenLabsSubscription?,
    elevenStatus: String?,
    elevenBusy: Boolean
) {
    val c = AquaTheme.colors
    var elevenKeyInput by remember { mutableStateOf("") }
    var elevenVoiceIdInput by remember { mutableStateOf(viewModel.secureConfigStore.getElevenLabsVoiceId()) }
    var hasStoredElevenKey by remember { mutableStateOf(viewModel.secureConfigStore.hasElevenLabsApiKey()) }
    var maskedElevenKey by remember { mutableStateOf(viewModel.secureConfigStore.getMaskedElevenLabsApiKey()) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accentCyan,
        unfocusedBorderColor = c.borderGlow,
        focusedTextColor = c.textPrimary,
        unfocusedTextColor = c.textPrimary
    )

    AquaGlassCard(
        borderColor = c.accentTeal.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = c.accentTeal)
                    Spacer(Modifier.width(8.dp))
                    Text("ElevenLabs Voice Studio", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                }
                Surface(
                    color = c.accentTeal.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "OPTIONAL",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.accentTeal,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            Text(
                text = if (hasStoredElevenKey && elevenVoiceIdInput.isNotBlank()) {
                    "Encrypted key ($maskedElevenKey) • Voice: ${elevenConfig.voiceName.ifBlank { elevenVoiceIdInput.take(12) }}"
                } else {
                    "Completely optional — configure only if you want your custom ElevenLabs Voice ID. Otherwise Gemini/Android TTS handles voice automatically."
                },
                style = MaterialTheme.typography.bodySmall,
                color = c.textMuted
            )

            OutlinedTextField(
                value = elevenKeyInput,
                onValueChange = { elevenKeyInput = it },
                label = { Text("ElevenLabs API Key (Optional)") },
                placeholder = { Text(if (hasStoredElevenKey) maskedElevenKey else "xi-api-key...") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_elevenlabs_key_input")
            )

            OutlinedTextField(
                value = elevenVoiceIdInput,
                onValueChange = { elevenVoiceIdInput = it },
                label = { Text("ElevenLabs Voice ID (Optional)") },
                placeholder = { Text("Enter Voice ID or load from your account") },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_elevenlabs_voice_input")
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        viewModel.saveElevenLabsCredentials(
                            apiKey = elevenKeyInput,
                            voiceId = elevenVoiceIdInput,
                            voiceName = elevenConfig.voiceName
                        )
                        elevenKeyInput = ""
                        hasStoredElevenKey = viewModel.secureConfigStore.hasElevenLabsApiKey()
                        maskedElevenKey = viewModel.secureConfigStore.getMaskedElevenLabsApiKey()
                    },
                    enabled = elevenKeyInput.isNotBlank() || elevenVoiceIdInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = c.accentTeal, contentColor = c.onAccent),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("settings_save_elevenlabs_button")
                ) {
                    Text("Save Voice", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { viewModel.fetchElevenLabsVoicesAndQuota(elevenKeyInput) },
                    enabled = !elevenBusy && (elevenKeyInput.isNotBlank() || hasStoredElevenKey),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("settings_load_elevenlabs_voices_button")
                ) {
                    if (elevenBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = c.accentTeal)
                    } else {
                        Text("Load Voices", color = c.accentTeal)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.testElevenLabsVoice(elevenKeyInput, elevenVoiceIdInput) },
                    enabled = !elevenBusy && (elevenKeyInput.isNotBlank() || hasStoredElevenKey) && elevenVoiceIdInput.isNotBlank(),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("settings_test_elevenlabs_button")
                ) {
                    Icon(Icons.Filled.VolumeUp, contentDescription = null, tint = c.accentCyan, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Test Voice", color = c.accentCyan)
                }
                if (hasStoredElevenKey || elevenVoiceIdInput.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            viewModel.clearElevenLabsCredentials()
                            elevenKeyInput = ""
                            elevenVoiceIdInput = ""
                            hasStoredElevenKey = false
                            maskedElevenKey = ""
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("Clear", color = c.error)
                    }
                }
            }

            elevenSub?.let { sub ->
                Text(
                    text = "Quota: ${sub.remainingCharacters} chars left (${sub.characterCount}/${sub.characterLimit}) • Tier: ${sub.tier.uppercase()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.success
                )
            }

            if (!elevenStatus.isNullOrBlank()) {
                Text(
                    text = elevenStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted
                )
            }

            if (elevenVoices.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    elevenVoices.forEach { voice ->
                        FilterChip(
                            selected = elevenVoiceIdInput.trim() == voice.voiceId,
                            onClick = {
                                elevenVoiceIdInput = voice.voiceId
                                viewModel.saveElevenLabsCredentials(
                                    apiKey = elevenKeyInput,
                                    voiceId = voice.voiceId,
                                    voiceName = voice.name
                                )
                                viewModel.updateElevenLabsConfig(elevenConfig.copy(enabled = true, voiceName = voice.name))
                            },
                            label = { Text("${voice.name} (${voice.category})") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = c.accentTeal.copy(alpha = 0.22f),
                                selectedLabelColor = c.accentTeal,
                                labelColor = c.textMuted
                            )
                        )
                    }
                }
            }

            Text("ElevenLabs Synthesis Model:", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ElevenLabsConfig.SUPPORTED_MODELS.forEach { (id, label) ->
                    FilterChip(
                        selected = elevenConfig.modelId == id,
                        onClick = { viewModel.updateElevenLabsConfig(elevenConfig.copy(modelId = id)) },
                        label = { Text(label) }
                    )
                }
            }

            SettingSwitchRow(
                title = "Enable ElevenLabs Voice",
                subtitle = "Use your configured ElevenLabs Voice ID for spoken responses when present.",
                checked = elevenConfig.enabled,
                onCheckedChange = { viewModel.updateElevenLabsConfig(elevenConfig.copy(enabled = it)) }
            )

            Text("Voice Stability: ${String.format("%.2f", elevenConfig.stability)}", style = MaterialTheme.typography.labelMedium, color = c.textPrimary)
            Slider(
                value = elevenConfig.stability,
                onValueChange = { viewModel.updateElevenLabsConfig(elevenConfig.copy(stability = it)) },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(thumbColor = c.accentTeal, activeTrackColor = c.accentTeal)
            )

            Text("Similarity Boost: ${String.format("%.2f", elevenConfig.similarityBoost)}", style = MaterialTheme.typography.labelMedium, color = c.textPrimary)
            Slider(
                value = elevenConfig.similarityBoost,
                onValueChange = { viewModel.updateElevenLabsConfig(elevenConfig.copy(similarityBoost = it)) },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(thumbColor = c.accentCyan, activeTrackColor = c.accentCyan)
            )
        }
    }
}

@Composable
fun AiFreedomLabCard(
    settings: UserSettings,
    freedomConfig: AiFreedomConfig,
    onUpdateFreedom: (AiFreedomConfig) -> Unit
) {
    val c = AquaTheme.colors
    val isTeenMode = UserProfilePolicy.isTeenMode(settings)
    var customModelInput by remember(freedomConfig.customModelOverride) { mutableStateOf(freedomConfig.customModelOverride) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accentCyan,
        unfocusedBorderColor = c.borderGlow,
        focusedTextColor = c.textPrimary,
        unfocusedTextColor = c.textPrimary
    )

    AquaGlassCard(
        borderColor = c.amber.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Tune, contentDescription = null, tint = c.amber)
                Spacer(Modifier.width(8.dp))
                Text("AI Dependency & Freedom Lab", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            }
            Text(
                text = if (isTeenMode) {
                    "Teen Guardrails (13–17) active: Unrestricted Tools mode is clamped to Balanced Autonomy to keep external actions supervised."
                } else {
                    "Grant Aqua AI fuller tool dependency, deeper autonomous chaining, custom model selection, and live sampling parameters."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (isTeenMode) c.amber else c.textMuted
            )

            Text("Autonomy Policy:", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    AiFreedomConfig.AUTONOMY_ADVISORY to "Advisory (Confirm All)",
                    AiFreedomConfig.AUTONOMY_BALANCED to "Balanced",
                    AiFreedomConfig.AUTONOMY_UNRESTRICTED to if (isTeenMode) "Unrestricted (18+)" else "Unrestricted Tools"
                ).forEach { (level, label) ->
                    val enabled = !(isTeenMode && level == AiFreedomConfig.AUTONOMY_UNRESTRICTED)
                    FilterChip(
                        selected = freedomConfig.autonomyLevel == level,
                        enabled = enabled,
                        onClick = { onUpdateFreedom(freedomConfig.copy(autonomyLevel = level)) },
                        label = { Text(label) },
                        modifier = Modifier.testTag("settings_autonomy_${level.lowercase()}")
                    )
                }
            }

            Text("Response Style:", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    AiFreedomConfig.STYLE_ADAPTIVE to "Adaptive",
                    AiFreedomConfig.STYLE_CONCISE to "Concise",
                    AiFreedomConfig.STYLE_TECHNICAL to "Technical",
                    AiFreedomConfig.STYLE_CREATIVE to "Creative"
                ).forEach { (styleId, label) ->
                    FilterChip(
                        selected = freedomConfig.responseStyle == styleId,
                        onClick = { onUpdateFreedom(freedomConfig.copy(responseStyle = styleId)) },
                        label = { Text(label) }
                    )
                }
            }

            SettingSwitchRow(
                title = "Unlock All 50+ Tools Always",
                subtitle = "Give the AI access to Device, Web, Browser, Drive Vault, SDK & Wallet tools simultaneously without keyword gating.",
                checked = freedomConfig.enableAllToolsAlways,
                onCheckedChange = { onUpdateFreedom(freedomConfig.copy(enableAllToolsAlways = it)) }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = customModelInput,
                    onValueChange = { customModelInput = it },
                    label = { Text("Custom Gemini Model Override") },
                    placeholder = { Text("e.g. gemini-2.5-flash") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { onUpdateFreedom(freedomConfig.copy(customModelOverride = customModelInput.trim())) },
                    colors = ButtonDefaults.buttonColors(containerColor = c.amber, contentColor = c.onAccent),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Apply", fontWeight = FontWeight.Bold)
                }
            }

            Text(
                text = "Temperature: ${String.format("%.2f", freedomConfig.temperature)}",
                style = MaterialTheme.typography.labelMedium,
                color = c.textPrimary
            )
            Slider(
                value = freedomConfig.temperature,
                onValueChange = { onUpdateFreedom(freedomConfig.copy(temperature = it)) },
                valueRange = 0f..2f,
                colors = SliderDefaults.colors(thumbColor = c.accentCyan, activeTrackColor = c.accentCyan)
            )

            Text(
                text = "Top-P Sampling: ${String.format("%.2f", freedomConfig.topP)}",
                style = MaterialTheme.typography.labelMedium,
                color = c.textPrimary
            )
            Slider(
                value = freedomConfig.topP,
                onValueChange = { onUpdateFreedom(freedomConfig.copy(topP = it)) },
                valueRange = 0.1f..1f,
                colors = SliderDefaults.colors(thumbColor = c.accentTeal, activeTrackColor = c.accentTeal)
            )

            Text(
                text = "Max Output Tokens: ${freedomConfig.maxOutputTokens}",
                style = MaterialTheme.typography.labelMedium,
                color = c.textPrimary
            )
            Slider(
                value = freedomConfig.maxOutputTokens.toFloat(),
                onValueChange = { onUpdateFreedom(freedomConfig.copy(maxOutputTokens = it.roundToInt().coerceIn(512, 8192))) },
                valueRange = 512f..8192f,
                colors = SliderDefaults.colors(thumbColor = c.accentCyan, activeTrackColor = c.accentCyan)
            )

            Text(
                text = "Max Autonomous Agent Steps: ${freedomConfig.maxAgentSteps}",
                style = MaterialTheme.typography.labelMedium,
                color = c.textPrimary
            )
            Slider(
                value = freedomConfig.maxAgentSteps.toFloat(),
                onValueChange = { onUpdateFreedom(freedomConfig.copy(maxAgentSteps = it.roundToInt().coerceIn(2, 15))) },
                valueRange = 2f..15f,
                steps = 12,
                colors = SliderDefaults.colors(thumbColor = c.amber, activeTrackColor = c.amber)
            )

            OutlinedButton(
                onClick = {
                    customModelInput = ""
                    onUpdateFreedom(AiFreedomConfig())
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("settings_reset_freedom_button")
            ) {
                Text("Reset AI Freedom Defaults", color = c.textMuted)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceAndWakeWordCard(
    context: Context,
    settings: UserSettings,
    wakeRunning: Boolean,
    wakePaused: Boolean,
    automationKeepAlive: Boolean,
    onRequestMicAndNotif: (Array<String>) -> Unit,
    viewModel: AquaMainViewModel
) {
    val c = AquaTheme.colors
    var wakePhraseInput by remember(settings.wakeWordPhrase) { mutableStateOf(settings.wakeWordPhrase) }
    var langExpanded by remember { mutableStateOf(false) }
    val languages = listOf("en-US", "en-GB", "es-ES", "fr-FR", "de-DE", "ja-JP", "ko-KR", "zh-CN")
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accentCyan,
        unfocusedBorderColor = c.borderGlow,
        focusedTextColor = c.textPrimary,
        unfocusedTextColor = c.textPrimary
    )

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
                onCheckedChange = { viewModel.setAutomationKeepAliveEnabled(it) }
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
                            onRequestMicAndNotif(perms.toTypedArray())
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
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) {
                        Text(
                            text = if (wakePaused) "Resume Mic Listener" else "Pause Mic Listener",
                            color = c.accentCyan
                        )
                    }
                    OutlinedButton(
                        onClick = { viewModel.setWakeWordServiceEnabled(false) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
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
                    colors = ButtonDefaults.buttonColors(containerColor = c.accentCyan, contentColor = c.onAccent),
                    modifier = Modifier.heightIn(min = 48.dp)
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
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
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

@Composable
fun SettingSwitchRow(
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
