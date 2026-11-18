package com.example.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ContentPasteGo
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.AiFreedomConfig
import com.example.data.local.AquaPersona
import com.example.data.model.AgentStepTrace
import com.example.data.model.AquaCoreState
import com.example.data.model.ChatMessage
import com.example.device.DeviceTelemetry
import com.example.domain.AgeTier
import com.example.domain.UserGender
import com.example.ui.components.AquaAnimatedEntrance
import com.example.ui.components.AquaGlassCard
import com.example.ui.components.AquaProfileTierPill
import com.example.ui.components.AquaStatusPulseDot
import com.example.ui.components.ChatMessageBubble
import com.example.ui.components.aquaPressScale
import com.example.ui.theme.AquaTheme

@Composable
fun HomeTelemetryHeaderRow(
    telemetry: DeviceTelemetry,
    currentDateTimeLabel: String,
    onOpenCommandPalette: () -> Unit,
    onOpenTab: (String) -> Unit
) {
    val c = AquaTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Surface(
            color = c.cardSurface.copy(alpha = 0.92f),
            shape = CircleShape,
            modifier = Modifier
                .weight(1f, fill = false)
                .border(1.dp, c.borderGlow, CircleShape)
                .clip(CircleShape)
                .clickable { onOpenTab("settings") }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AquaStatusPulseDot(
                    color = if (telemetry.isOnline) c.success else c.error,
                    active = telemetry.isOnline,
                    size = 8.dp
                )
                Text(
                    text = if (telemetry.isOnline) telemetry.connectionType else "Offline",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (telemetry.isOnline) c.success else c.error,
                    maxLines = 1
                )
                Text("•", color = c.textMuted, style = MaterialTheme.typography.labelSmall)
                Icon(
                    imageVector = if (telemetry.isCharging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryStd,
                    contentDescription = "Battery ${telemetry.batteryPercent}%",
                    tint = if (telemetry.batteryPercent in 0..20) c.amber else c.accentTeal,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = if (telemetry.batteryPercent >= 0) "${telemetry.batteryPercent}%" else "--",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.textPrimary
                )
                Text("•", color = c.textMuted, style = MaterialTheme.typography.labelSmall)
                Text(
                    text = currentDateTimeLabel.substringAfter("•").trim(),
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textMuted,
                    maxLines = 1
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconButton(
                onClick = onOpenCommandPalette,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .background(c.cardSurface.copy(alpha = 0.92f))
                    .border(1.dp, c.borderGlow, CircleShape)
                    .testTag("home_command_palette_button")
            ) {
                Icon(Icons.Filled.Search, contentDescription = "Open Command Palette", tint = c.accentCyan)
            }

            IconButton(
                onClick = { onOpenTab("drive") },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .background(c.cardSurface.copy(alpha = 0.92f))
                    .border(1.dp, c.borderGlow, CircleShape)
                    .testTag("home_drive_shortcut")
            ) {
                Icon(Icons.Filled.CloudSync, contentDescription = "Open Google Drive Vault", tint = c.accentTeal)
            }

            IconButton(
                onClick = { onOpenTab("settings") },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .background(c.cardSurface.copy(alpha = 0.92f))
                    .border(1.dp, c.borderGlow, CircleShape)
                    .testTag("home_settings_shortcut")
            ) {
                Icon(Icons.Filled.Settings, contentDescription = "Open Settings", tint = c.accentCyan)
            }
        }
    }
}

@Composable
fun HomeProfileAndEngineStrip(
    ageTier: AgeTier,
    gender: UserGender,
    usingPersonalKey: Boolean,
    elevenLabsReady: Boolean,
    elevenVoiceName: String,
    useGeminiTts: Boolean,
    freedomConfig: AiFreedomConfig,
    onOpenSetupWizard: () -> Unit,
    onOpenProfileSettings: () -> Unit
) {
    val c = AquaTheme.colors
    val engineLabel = if (usingPersonalKey) "AI Studio Key" else "Firebase AI"
    val voiceLabel = when {
        elevenLabsReady -> elevenVoiceName.ifBlank { "ElevenLabs" }
        useGeminiTts -> "Gemini TTS"
        else -> "Android TTS"
    }
    val autonomyLabel = when (freedomConfig.autonomyLevel) {
        AiFreedomConfig.AUTONOMY_UNRESTRICTED -> "Unrestricted"
        AiFreedomConfig.AUTONOMY_ADVISORY -> "Advisory"
        else -> "Balanced"
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AquaProfileTierPill(
                ageTier = ageTier,
                gender = gender,
                onClick = onOpenProfileSettings,
                modifier = Modifier.testTag("home_profile_tier_pill")
            )
            if (ageTier == AgeTier.UNDER_13_BLOCKED) {
                Text(
                    text = "Update Age 13+ ->",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.error,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onOpenProfileSettings)
                )
            } else if (ageTier == AgeTier.UNSET) {
                Text(
                    text = "Confirm Age 13+ ->",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.accentCyan,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(onClick = onOpenProfileSettings)
                )
            }
        }

        Surface(
            color = c.cardSurface.copy(alpha = 0.92f),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, c.accentCyan.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onOpenSetupWizard)
                .testTag("home_ai_setup_status_chip")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = "Configure AI Engine and Voice",
                        tint = c.accentCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "$engineLabel • $voiceLabel • $autonomyLabel (${freedomConfig.maxAgentSteps} steps)",
                        style = MaterialTheme.typography.labelMedium,
                        color = c.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "Setup",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.accentCyan,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun HomeLiveAndForegroundSection(
    isLiveActive: Boolean,
    liveStatus: String,
    wakeWordRunning: Boolean,
    wakeWordEnabled: Boolean,
    wakeWordPaused: Boolean,
    wakeWordPhrase: String,
    automationKeepAliveActive: Boolean,
    backgroundAutomationEnabled: Boolean,
    activeAutomationTasks: List<String>,
    onToggleLiveSession: () -> Unit,
    onToggleBackgroundMicPaused: () -> Unit,
    onToggleBackgroundService: (Boolean) -> Unit
) {
    val c = AquaTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val liveInteraction = remember { MutableInteractionSource() }
        AquaGlassCard(
            containerColor = if (isLiveActive) c.accentAzure.copy(alpha = 0.24f) else c.cardSurface,
            borderColor = if (isLiveActive) c.accentCyan else c.borderGlow,
            modifier = Modifier
                .fillMaxWidth()
                .aquaPressScale(liveInteraction)
                .clip(RoundedCornerShape(22.dp))
                .clickable(
                    interactionSource = liveInteraction,
                    indication = LocalIndication.current,
                    onClick = onToggleLiveSession
                )
                .testTag("live_voice_card")
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = if (isLiveActive) c.accentCyan else c.accentAzure.copy(alpha = 0.22f),
                    shape = CircleShape,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.GraphicEq,
                            contentDescription = "Gemini Live Voice",
                            tint = if (isLiveActive) c.onAccent else c.accentCyan
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isLiveActive) "Gemini Live Session Active" else "Start Gemini Live Voice",
                        style = MaterialTheme.typography.titleMedium,
                        color = c.textPrimary
                    )
                    Text(
                        text = liveStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        val bgActive = wakeWordRunning || wakeWordEnabled || automationKeepAliveActive || backgroundAutomationEnabled
        val fgsRunning = wakeWordRunning || automationKeepAliveActive
        AquaGlassCard(
            containerColor = if (fgsRunning && !wakeWordPaused) {
                c.accentTeal.copy(alpha = 0.14f)
            } else {
                c.cardSurface
            },
            borderColor = if (fgsRunning && !wakeWordPaused) c.accentTeal else c.borderGlow,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("background_mic_card")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = if (fgsRunning && !wakeWordPaused) c.accentTeal else c.accentAzure.copy(alpha = 0.20f),
                    shape = CircleShape,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Mic,
                            contentDescription = "Voice and Automation Foreground Service",
                            tint = if (fgsRunning && !wakeWordPaused) c.onAccent else c.accentTeal
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            activeAutomationTasks.isNotEmpty() ->
                                "Priority Service • ${activeAutomationTasks.size} Task(s) Active"
                            fgsRunning && wakeWordPaused ->
                                "Priority Service (Mic Paused)"
                            fgsRunning ->
                                "Voice & Automation Service Active"
                            else ->
                                "Run Voice & Automation in Background"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = c.textPrimary
                    )
                    Text(
                        text = when {
                            activeAutomationTasks.isNotEmpty() ->
                                "Running: ${activeAutomationTasks.first()} (OS prioritized)"
                            fgsRunning && wakeWordPaused ->
                                "Persistent notification active • Tap play to resume '$wakeWordPhrase'"
                            fgsRunning ->
                                "Persistent notification active • Voice & routines prioritized"
                            else ->
                                "Keep Aqua AI voice conversation & tasks prioritized by Android OS"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (wakeWordRunning) {
                    IconButton(
                        onClick = onToggleBackgroundMicPaused,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("background_mic_pause_button")
                    ) {
                        Icon(
                            imageVector = if (wakeWordPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = if (wakeWordPaused) "Resume background mic" else "Pause background mic",
                            tint = c.accentCyan
                        )
                    }
                }
                Switch(
                    checked = bgActive,
                    onCheckedChange = onToggleBackgroundService,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = c.onAccent,
                        checkedTrackColor = c.accentTeal
                    ),
                    modifier = Modifier.testTag("background_mic_switch")
                )
            }
        }
    }
}

@Composable
fun HomeQuickModulesStrip(onOpenTab: (String) -> Unit) {
    val c = AquaTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "QUICK ACCESS & MODULES",
            style = MaterialTheme.typography.labelSmall,
            color = c.textMuted,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item { QuickShortcutTile(Icons.Filled.AutoMode, "Routines", c.accentCyan, "shortcut_routines") { onOpenTab("routines") } }
            item { QuickShortcutTile(Icons.Filled.CloudSync, "Drive Vault", c.success, "shortcut_drive") { onOpenTab("drive") } }
            item { QuickShortcutTile(Icons.Filled.VerifiedUser, "SDK & Sig", c.accentTeal, "shortcut_sdk") { onOpenTab("sdk") } }
            item { QuickShortcutTile(Icons.Filled.Extension, "Skills", c.accentTeal, "shortcut_skills") { onOpenTab("skills") } }
            item { QuickShortcutTile(Icons.Filled.Language, "Browser", c.accentAzure, "shortcut_browser") { onOpenTab("browser") } }
            item { QuickShortcutTile(Icons.Filled.AccountBalanceWallet, "Wallet", c.amber, "shortcut_wallet") { onOpenTab("wallet") } }
            item { QuickShortcutTile(Icons.Filled.Memory, "Memory", c.accentCyan, "shortcut_memory") { onOpenTab("memory") } }
            item { QuickShortcutTile(Icons.Filled.Settings, "Settings", c.accentCyan, "shortcut_settings") { onOpenTab("settings") } }
        }
    }
}

@Composable
fun HomeQuickDeviceControlsRow(
    telemetry: DeviceTelemetry,
    onToggleFlashlight: () -> Unit,
    onAskClipboard: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onNewChat: () -> Unit
) {
    val c = AquaTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        QuickDeviceButton(
            icon = Icons.Filled.FlashlightOn,
            label = if (telemetry.flashlightEnabled) "Torch ON" else "Torch OFF",
            modifier = Modifier.weight(1f),
            onClick = onToggleFlashlight
        )
        QuickDeviceButton(
            icon = Icons.Filled.ContentPasteGo,
            label = "Clipboard AI",
            modifier = Modifier.weight(1f),
            onClick = onAskClipboard
        )
        QuickDeviceButton(
            icon = if (telemetry.isOnline) Icons.Filled.Wifi else Icons.Filled.WifiOff,
            label = "Wi-Fi",
            modifier = Modifier.weight(1f),
            onClick = onOpenWifiSettings
        )
        QuickDeviceButton(
            icon = Icons.Filled.ChatBubbleOutline,
            label = "New Chat",
            modifier = Modifier.weight(1f),
            onClick = onNewChat
        )
    }
}

@Composable
fun HomeConversationPanel(
    coreState: AquaCoreState,
    preferredModel: String,
    activePersona: AquaPersona,
    messages: List<ChatMessage>,
    streamingReply: String,
    agentTraces: List<AgentStepTrace>,
    isSpeaking: Boolean,
    listState: LazyListState,
    onExportMarkdown: () -> Unit,
    onNewConversation: () -> Unit,
    onCollapseToOrb: () -> Unit,
    onSpeakMessage: (String) -> Unit,
    onStopSpeaking: () -> Unit,
    onCopyMessage: (String) -> Unit,
    onShareMessage: (String) -> Unit,
    onSaveMemory: (String) -> Unit
) {
    val c = AquaTheme.colors
    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = c.cardSurface.copy(alpha = 0.94f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, c.borderGlow, RoundedCornerShape(16.dp))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    AquaStatusPulseDot(
                        color = c.accentCyan,
                        active = coreState != AquaCoreState.IDLE,
                        size = 10.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when (coreState) {
                            AquaCoreState.THINKING -> "Aqua Thinking ($preferredModel)..."
                            AquaCoreState.EXECUTING_TOOL -> "Executing Tool..."
                            AquaCoreState.LISTENING -> "Listening..."
                            AquaCoreState.SPEAKING -> "Speaking..."
                            else -> "${activePersona.label} Context Active"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = c.accentCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (messages.isNotEmpty()) {
                        IconButton(
                            onClick = onExportMarkdown,
                            modifier = Modifier
                                .size(34.dp)
                                .testTag("export_chat_markdown_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FileDownload,
                                contentDescription = "Export conversation as Markdown",
                                tint = c.accentTeal,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Text(
                        text = "New",
                        style = MaterialTheme.typography.labelMedium,
                        color = c.accentTeal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onNewConversation)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    Text(
                        text = "Orb",
                        style = MaterialTheme.typography.labelMedium,
                        color = c.textMuted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onCollapseToOrb)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        AnimatedVisibility(visible = agentTraces.isNotEmpty() && coreState == AquaCoreState.EXECUTING_TOOL) {
            Surface(
                color = c.accentAzure.copy(alpha = 0.18f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                val latest = agentTraces.last()
                Text(
                    text = "Agent Step: ${latest.stepName} (${latest.toolName}) -> ${latest.detail}",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textPrimary,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (messages.isEmpty() && streamingReply.isBlank()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Type a command, tap the mic, or attach a file below to begin.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.textMuted
                        )
                    }
                }
            } else {
                items(messages, key = { it.id.ifBlank { "${it.role}_${it.content.hashCode()}" } }) { msg ->
                    ChatMessageBubble(
                        message = msg,
                        isSpeaking = isSpeaking,
                        onSpeakClick = onSpeakMessage,
                        onStopSpeakClick = onStopSpeaking,
                        onCopyClick = onCopyMessage,
                        onShareClick = onShareMessage,
                        onSaveMemoryClick = onSaveMemory
                    )
                }
                if (streamingReply.isNotBlank()) {
                    item {
                        Surface(
                            color = c.cardSurface,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier
                                .fillMaxWidth(0.92f)
                                .padding(vertical = 4.dp)
                                .border(1.dp, c.accentCyan.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                        ) {
                            Text(
                                text = streamingReply,
                                style = MaterialTheme.typography.bodyLarge,
                                color = c.textPrimary,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickShortcutTile(
    icon: ImageVector,
    label: String,
    accent: Color,
    tag: String,
    onClick: () -> Unit
) {
    val c = AquaTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        color = c.cardSurface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .width(88.dp)
            .aquaPressScale(interactionSource)
            .border(1.dp, c.borderGlow, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            )
            .testTag(tag)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 13.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = accent, modifier = Modifier.size(23.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun QuickDeviceButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val c = AquaTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        color = c.deepSurface,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .aquaPressScale(interactionSource)
            .border(1.dp, c.borderGlow, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            )
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = c.accentCyan, modifier = Modifier.size(20.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
