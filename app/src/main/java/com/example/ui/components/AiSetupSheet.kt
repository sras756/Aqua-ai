package com.example.ui.components

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.AiFreedomConfig
import com.example.data.local.ElevenLabsConfig
import com.example.domain.AgeTier
import com.example.domain.UserGender
import com.example.domain.UserProfilePolicy
import com.example.ui.AquaMainViewModel
import com.example.ui.theme.AquaTheme
import kotlin.math.roundToInt

@Composable
fun AiSetupSheet(
    viewModel: AquaMainViewModel,
    onOpenBrowserUrl: (String) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler { onDismiss() }

    val c = AquaTheme.colors
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val savedFreedom by viewModel.aiFreedomConfig.collectAsStateWithLifecycle()
    val savedElevenConfig by viewModel.elevenLabsConfig.collectAsStateWithLifecycle()
    val verifiedModels by viewModel.verifiedAiStudioModels.collectAsStateWithLifecycle()
    val keyVerificationStatus by viewModel.aiKeyVerificationStatus.collectAsStateWithLifecycle()
    val elevenVoices by viewModel.elevenLabsVoices.collectAsStateWithLifecycle()
    val elevenSub by viewModel.elevenLabsSubscription.collectAsStateWithLifecycle()
    val elevenStatus by viewModel.elevenLabsStatus.collectAsStateWithLifecycle()
    val elevenBusy by viewModel.elevenLabsBusy.collectAsStateWithLifecycle()

    val storedGeminiMask = remember(settings.useCustomDirectApiKey) {
        viewModel.secureConfigStore.getMaskedGeminiApiKey()
    }
    val hasStoredGemini = remember(settings.useCustomDirectApiKey) {
        viewModel.secureConfigStore.hasGeminiApiKey()
    }
    val storedElevenMask = remember { viewModel.secureConfigStore.getMaskedElevenLabsApiKey() }
    val hasStoredElevenKey = remember { viewModel.secureConfigStore.hasElevenLabsApiKey() }
    val storedVoiceId = remember { viewModel.secureConfigStore.getElevenLabsVoiceId() }

    var ageInput by remember(settings.ageYears) {
        mutableStateOf(if (settings.ageYears > 0) settings.ageYears.toString() else "")
    }
    var selectedGenderId by remember(settings.gender) {
        mutableStateOf(UserGender.fromId(settings.gender).id)
    }

    val parsedAge = ageInput.toIntOrNull()?.coerceIn(0, UserProfilePolicy.MAX_VALID_AGE) ?: 0
    val previewTier = UserProfilePolicy.resolveAgeTier(parsedAge, confirmed = parsedAge > 0)
    val previewGender = UserGender.fromId(selectedGenderId)
    val isTeenMode = previewTier == AgeTier.TEEN_13_17
    val isUnder13 = previewTier == AgeTier.UNDER_13_BLOCKED

    var geminiKeyInput by remember { mutableStateOf("") }
    var showGeminiKey by remember { mutableStateOf(false) }
    var usePersonalAiStudio by remember { mutableStateOf(settings.useCustomDirectApiKey) }
    var selectedModel by remember {
        mutableStateOf(
            savedFreedom.customModelOverride.ifBlank { settings.preferredModel }
        )
    }

    var elevenKeyInput by remember { mutableStateOf("") }
    var showElevenKey by remember { mutableStateOf(false) }
    var elevenVoiceIdInput by remember { mutableStateOf(storedVoiceId) }
    var elevenVoiceName by remember { mutableStateOf(savedElevenConfig.voiceName) }
    var elevenModelId by remember { mutableStateOf(savedElevenConfig.modelId) }
    var enableElevenLabs by remember { mutableStateOf(savedElevenConfig.enabled) }

    var freedomDraft by remember(savedFreedom) { mutableStateOf(savedFreedom) }
    val effectiveFreedomDraft = if (isTeenMode && freedomDraft.autonomyLevel == AiFreedomConfig.AUTONOMY_UNRESTRICTED) {
        freedomDraft.copy(autonomyLevel = AiFreedomConfig.AUTONOMY_BALANCED)
    } else {
        freedomDraft
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accentCyan,
        unfocusedBorderColor = c.borderGlow,
        focusedTextColor = c.textPrimary,
        unfocusedTextColor = c.textPrimary
    )

    Surface(
        color = c.canvas.copy(alpha = 0.98f),
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("ai_setup_sheet")
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp)
            ) {
                // Animated Gradient Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    c.accentCyan.copy(alpha = 0.14f),
                                    c.accentTeal.copy(alpha = 0.08f),
                                    c.canvas
                                )
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = c.accentCyan, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Configure Your Aqua AI Studio",
                                style = MaterialTheme.typography.titleLarge,
                                color = c.textPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "Confirm 13+ age safety, optional gender preference, Google AI Studio key, ElevenLabs voice & AI freedom.",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textMuted
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("setup_close_button")
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Close AI Setup", tint = c.accentCyan)
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Card 1: Age System (13+) & Optional Gender
                    item {
                        AquaAnimatedEntrance(index = 0) {
                            AquaGlassCard(
                                borderColor = when (previewTier) {
                                    AgeTier.UNDER_13_BLOCKED -> c.error.copy(alpha = 0.65f)
                                    AgeTier.TEEN_13_17 -> c.amber.copy(alpha = 0.6f)
                                    AgeTier.ADULT_18_PLUS -> c.success.copy(alpha = 0.5f)
                                    AgeTier.UNSET -> c.accentCyan.copy(alpha = 0.45f)
                                },
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
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                            Icon(Icons.Filled.Person, contentDescription = null, tint = c.accentCyan)
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = "Profile: Age (13+) & Gender",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = c.textPrimary
                                            )
                                        }
                                        AquaProfileTierPill(ageTier = previewTier, gender = previewGender)
                                    }

                                    Text(
                                        text = previewTier.summary,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = when (previewTier) {
                                            AgeTier.UNDER_13_BLOCKED -> c.error
                                            AgeTier.TEEN_13_17 -> c.amber
                                            AgeTier.ADULT_18_PLUS -> c.success
                                            AgeTier.UNSET -> c.textMuted
                                        }
                                    )

                                    OutlinedTextField(
                                        value = ageInput,
                                        onValueChange = { raw ->
                                            val digits = raw.filter { it.isDigit() }.take(3)
                                            ageInput = digits
                                        },
                                        label = { Text("Your Age in Years (13+ Required)") },
                                        placeholder = { Text("e.g. 16 or 24") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        colors = fieldColors,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("setup_age_input")
                                    )

                                    Text(
                                        text = "Gender Preference (Optional — personalizes respectful pronouns):",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = c.textMuted
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        UserGender.entries.forEach { g ->
                                            val selected = selectedGenderId == g.id
                                            FilterChip(
                                                selected = selected,
                                                onClick = { selectedGenderId = g.id },
                                                label = { Text(g.label) },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = c.accentCyan.copy(alpha = 0.22f),
                                                    selectedLabelColor = c.accentCyan,
                                                    labelColor = c.textMuted
                                                ),
                                                modifier = Modifier.testTag("setup_gender_${g.id}")
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Card 2: Google AI Studio API Key (Optional)
                    item {
                        AquaAnimatedEntrance(index = 1) {
                            AquaGlassCard(
                                borderColor = c.accentCyan.copy(alpha = 0.45f),
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
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.Key, contentDescription = null, tint = c.amber)
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = "Google AI Studio API Key",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = c.textPrimary
                                            )
                                        }
                                        Surface(
                                            color = c.accentCyan.copy(alpha = 0.16f),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                text = "OPTIONAL",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = c.accentCyan,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }

                                    Text(
                                        text = if (hasStoredGemini) {
                                            "Encrypted key on device ($storedGeminiMask). Supply a new key only to replace it."
                                        } else {
                                            "Uses managed Firebase AI Logic by default. Add your Google AI Studio key to unlock custom Gemini models & full REST multi-step tools."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = c.textMuted
                                    )

                                    OutlinedTextField(
                                        value = geminiKeyInput,
                                        onValueChange = { geminiKeyInput = it },
                                        label = { Text("Google AI Studio API Key") },
                                        placeholder = { Text(if (hasStoredGemini) storedGeminiMask else "AIza...") },
                                        singleLine = true,
                                        visualTransformation = if (showGeminiKey) VisualTransformation.None else PasswordVisualTransformation(),
                                        trailingIcon = {
                                            IconButton(onClick = { showGeminiKey = !showGeminiKey }) {
                                                Icon(
                                                    imageVector = if (showGeminiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                                    contentDescription = "Toggle key visibility",
                                                    tint = c.textMuted
                                                )
                                            }
                                        },
                                        colors = fieldColors,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("setup_gemini_key_input")
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                viewModel.verifyGoogleAiStudioKey(geminiKeyInput)
                                                if (geminiKeyInput.isNotBlank()) usePersonalAiStudio = true
                                            },
                                            enabled = geminiKeyInput.isNotBlank() || hasStoredGemini,
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 48.dp)
                                                .testTag("setup_verify_gemini_button")
                                        ) {
                                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = c.accentCyan, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Verify & Load Models", color = c.accentCyan)
                                        }
                                        OutlinedButton(
                                            onClick = { onOpenBrowserUrl("https://aistudio.google.com/apikey") },
                                            modifier = Modifier
                                                .heightIn(min = 48.dp)
                                                .testTag("setup_get_gemini_key_button")
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = c.accentTeal, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Get Key", color = c.accentTeal)
                                        }
                                    }

                                    if (!keyVerificationStatus.isNullOrBlank()) {
                                        Text(
                                            text = keyVerificationStatus.orEmpty(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (keyVerificationStatus?.startsWith("Verified") == true) c.success else c.amber
                                        )
                                    }

                                    if (verifiedModels.isNotEmpty()) {
                                        Text(
                                            text = "Live Verified Models (Tap to select):",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = c.textMuted
                                        )
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            verifiedModels.forEach { modelId ->
                                                val isPicked = selectedModel == modelId || freedomDraft.customModelOverride == modelId
                                                FilterChip(
                                                    selected = isPicked,
                                                    onClick = {
                                                        selectedModel = modelId
                                                        freedomDraft = freedomDraft.copy(customModelOverride = modelId)
                                                        usePersonalAiStudio = true
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

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                            Text("Route AI via Personal AI Studio Key", style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                                            Text("Requires a saved or entered Google AI Studio key.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                                        }
                                        Switch(
                                            checked = usePersonalAiStudio && (geminiKeyInput.isNotBlank() || hasStoredGemini),
                                            onCheckedChange = { usePersonalAiStudio = it },
                                            enabled = geminiKeyInput.isNotBlank() || hasStoredGemini,
                                            colors = SwitchDefaults.colors(checkedThumbColor = c.canvas, checkedTrackColor = c.accentCyan)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Card 3: ElevenLabs Voice Engine (Optional • Not Compulsory)
                    item {
                        AquaAnimatedEntrance(index = 2) {
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
                                            Text(
                                                text = "ElevenLabs Voice",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = c.textPrimary
                                            )
                                        }
                                        Surface(
                                            color = c.accentTeal.copy(alpha = 0.16f),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                text = "OPTIONAL • NOT COMPULSORY",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = c.accentTeal,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }

                                    Text(
                                        text = "Skip or leave blank anytime — Aqua AI speaks naturally with Gemini Native TTS or Android TTS out of the box.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = c.textMuted
                                    )

                                    OutlinedTextField(
                                        value = elevenKeyInput,
                                        onValueChange = { elevenKeyInput = it },
                                        label = { Text("ElevenLabs API Key (Optional)") },
                                        placeholder = { Text(if (hasStoredElevenKey) storedElevenMask else "xi-api-key...") },
                                        singleLine = true,
                                        visualTransformation = if (showElevenKey) VisualTransformation.None else PasswordVisualTransformation(),
                                        trailingIcon = {
                                            IconButton(onClick = { showElevenKey = !showElevenKey }) {
                                                Icon(
                                                    imageVector = if (showElevenKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                                    contentDescription = "Toggle ElevenLabs key visibility",
                                                    tint = c.textMuted
                                                )
                                            }
                                        },
                                        colors = fieldColors,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("setup_elevenlabs_key_input")
                                    )

                                    OutlinedTextField(
                                        value = elevenVoiceIdInput,
                                        onValueChange = { elevenVoiceIdInput = it },
                                        label = { Text("ElevenLabs Voice ID (Optional)") },
                                        placeholder = { Text("Paste any ElevenLabs Voice ID or pick below") },
                                        singleLine = true,
                                        colors = fieldColors,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("setup_elevenlabs_voice_input")
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = { viewModel.fetchElevenLabsVoicesAndQuota(elevenKeyInput) },
                                            enabled = !elevenBusy && (elevenKeyInput.isNotBlank() || hasStoredElevenKey),
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 48.dp)
                                                .testTag("setup_load_voices_button")
                                        ) {
                                            if (elevenBusy) {
                                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = c.accentTeal)
                                            } else {
                                                Text("Load My Voices", color = c.accentTeal)
                                            }
                                        }
                                        OutlinedButton(
                                            onClick = { viewModel.testElevenLabsVoice(elevenKeyInput, elevenVoiceIdInput) },
                                            enabled = !elevenBusy && (elevenKeyInput.isNotBlank() || hasStoredElevenKey) && elevenVoiceIdInput.isNotBlank(),
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 48.dp)
                                                .testTag("setup_test_voice_button")
                                        ) {
                                            Icon(Icons.Filled.VolumeUp, contentDescription = null, tint = c.accentCyan, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Test Voice", color = c.accentCyan)
                                        }
                                    }

                                    elevenSub?.let { sub ->
                                        Text(
                                            text = "Plan: ${sub.tier.uppercase()} • ${sub.remainingCharacters} chars remaining (${sub.characterCount}/${sub.characterLimit})",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = c.success
                                        )
                                    }

                                    if (!elevenStatus.isNullOrBlank()) {
                                        Text(
                                            text = elevenStatus.orEmpty(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = c.textMuted
                                        )
                                    }

                                    if (elevenVoices.isNotEmpty()) {
                                        Text(
                                            text = "Your Live ElevenLabs Voices (Tap to select):",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = c.textMuted
                                        )
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            elevenVoices.forEach { voice ->
                                                val selected = elevenVoiceIdInput.trim() == voice.voiceId
                                                FilterChip(
                                                    selected = selected,
                                                    onClick = {
                                                        elevenVoiceIdInput = voice.voiceId
                                                        elevenVoiceName = voice.name
                                                        enableElevenLabs = true
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

                                    Text(
                                        text = "ElevenLabs Synthesis Model:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = c.textMuted
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        ElevenLabsConfig.SUPPORTED_MODELS.forEach { (id, label) ->
                                            FilterChip(
                                                selected = elevenModelId == id,
                                                onClick = { elevenModelId = id },
                                                label = { Text(label) },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = c.accentTeal.copy(alpha = 0.22f),
                                                    selectedLabelColor = c.accentTeal,
                                                    labelColor = c.textMuted
                                                )
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                            Text("Enable ElevenLabs Voice Output", style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                                            Text("Falls back cleanly to Gemini / Android TTS if empty.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                                        }
                                        Switch(
                                            checked = enableElevenLabs,
                                            onCheckedChange = { enableElevenLabs = it },
                                            colors = SwitchDefaults.colors(checkedThumbColor = c.canvas, checkedTrackColor = c.accentTeal)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Card 4: AI Dependency & Freedom Presets
                    item {
                        AquaAnimatedEntrance(index = 3) {
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
                                            "Teen Guardrails (13–17) active: Unrestricted Tools mode is clamped to Balanced Autonomy."
                                        } else {
                                            "Tune how freely Aqua AI chains tools, selects autonomy gates, and samples responses. Full controls remain available anytime in Settings."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isTeenMode) c.amber else c.textMuted
                                    )

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        FilterChip(
                                            selected = effectiveFreedomDraft.autonomyLevel == AiFreedomConfig.AUTONOMY_BALANCED &&
                                                effectiveFreedomDraft.responseStyle == AiFreedomConfig.STYLE_ADAPTIVE,
                                            onClick = {
                                                freedomDraft = freedomDraft.copy(
                                                    autonomyLevel = AiFreedomConfig.AUTONOMY_BALANCED,
                                                    responseStyle = AiFreedomConfig.STYLE_ADAPTIVE,
                                                    temperature = 0.7f,
                                                    maxAgentSteps = 10,
                                                    enableAllToolsAlways = true
                                                )
                                            },
                                            label = { Text("Balanced") }
                                        )
                                        FilterChip(
                                            selected = effectiveFreedomDraft.autonomyLevel == AiFreedomConfig.AUTONOMY_UNRESTRICTED,
                                            enabled = !isTeenMode,
                                            onClick = {
                                                freedomDraft = freedomDraft.copy(
                                                    autonomyLevel = AiFreedomConfig.AUTONOMY_UNRESTRICTED,
                                                    enableAllToolsAlways = true,
                                                    maxAgentSteps = 15,
                                                    maxOutputTokens = 8192
                                                )
                                            },
                                            label = { Text(if (isTeenMode) "Unrestricted (18+)" else "Unrestricted Tools") }
                                        )
                                        FilterChip(
                                            selected = effectiveFreedomDraft.responseStyle == AiFreedomConfig.STYLE_TECHNICAL &&
                                                effectiveFreedomDraft.autonomyLevel != AiFreedomConfig.AUTONOMY_UNRESTRICTED,
                                            onClick = {
                                                freedomDraft = freedomDraft.copy(
                                                    autonomyLevel = AiFreedomConfig.AUTONOMY_BALANCED,
                                                    responseStyle = AiFreedomConfig.STYLE_TECHNICAL,
                                                    temperature = 0.35f,
                                                    maxAgentSteps = 12,
                                                    maxOutputTokens = 8192
                                                )
                                            },
                                            label = { Text("Deep Technical") }
                                        )
                                        FilterChip(
                                            selected = effectiveFreedomDraft.autonomyLevel == AiFreedomConfig.AUTONOMY_ADVISORY,
                                            onClick = {
                                                freedomDraft = freedomDraft.copy(
                                                    autonomyLevel = AiFreedomConfig.AUTONOMY_ADVISORY,
                                                    enableAllToolsAlways = false
                                                )
                                            },
                                            label = { Text("Advisory (Confirm All)") }
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                            Text("Unlock All 50+ Tools Always", style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                                            Text("Exposes Web, Drive Vault, Android SDK & Device tools simultaneously without keyword filtering.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                                        }
                                        Switch(
                                            checked = effectiveFreedomDraft.enableAllToolsAlways,
                                            onCheckedChange = { freedomDraft = freedomDraft.copy(enableAllToolsAlways = it) },
                                            colors = SwitchDefaults.colors(checkedThumbColor = c.canvas, checkedTrackColor = c.amber)
                                        )
                                    }

                                    val tempDisplay = ((effectiveFreedomDraft.temperature * 100f).roundToInt() / 100f)
                                    Text(
                                        text = "Temperature: $tempDisplay (${if (tempDisplay < 0.4f) "Deterministic" else if (tempDisplay > 1.0f) "Expansive" else "Balanced"})",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = c.textPrimary
                                    )
                                    Slider(
                                        value = effectiveFreedomDraft.temperature,
                                        onValueChange = { freedomDraft = freedomDraft.copy(temperature = it) },
                                        valueRange = 0f..2f,
                                        colors = SliderDefaults.colors(thumbColor = c.accentCyan, activeTrackColor = c.accentCyan)
                                    )

                                    Text(
                                        text = "Max Autonomous Tool Steps per Turn: ${effectiveFreedomDraft.maxAgentSteps}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = c.textPrimary
                                    )
                                    Slider(
                                        value = effectiveFreedomDraft.maxAgentSteps.toFloat(),
                                        onValueChange = { freedomDraft = freedomDraft.copy(maxAgentSteps = it.roundToInt().coerceIn(2, 15)) },
                                        valueRange = 2f..15f,
                                        steps = 12,
                                        colors = SliderDefaults.colors(thumbColor = c.amber, activeTrackColor = c.amber)
                                    )
                                }
                            }
                        }
                    }
                }

                // Bottom Action Bar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.deepSurface)
                        .border(1.dp, c.borderGlow, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isUnder13) {
                        Text(
                            text = "Users under 13 cannot activate AI chat or live voice under COPPA & Google Play policy.",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.error
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("setup_skip_button")
                        ) {
                            Text("Skip for Now", color = c.textMuted)
                        }
                        Button(
                            onClick = {
                                viewModel.completePostLoginAiSetup(
                                    googleAiStudioKey = geminiKeyInput,
                                    useGoogleAiStudioKey = usePersonalAiStudio,
                                    preferredModel = selectedModel,
                                    elevenLabsKey = elevenKeyInput,
                                    elevenLabsVoiceId = elevenVoiceIdInput,
                                    elevenLabsVoiceName = elevenVoiceName,
                                    elevenLabsModelId = elevenModelId,
                                    enableElevenLabs = enableElevenLabs,
                                    freedomConfig = effectiveFreedomDraft,
                                    ageYears = parsedAge,
                                    genderId = selectedGenderId
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isUnder13) c.error else c.accentCyan,
                                contentColor = c.onAccent
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("setup_save_launch_button")
                        ) {
                            Text("Save & Launch Aqua AI", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
