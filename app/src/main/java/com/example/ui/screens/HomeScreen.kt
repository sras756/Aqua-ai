package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ContentPasteGo
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.AquaPersona
import com.example.data.model.AquaCoreState
import com.example.ui.AquaMainViewModel
import com.example.ui.components.AquaBackdropBox
import com.example.ui.components.AquaCoreOrb
import com.example.ui.components.AquaGlassCard
import com.example.ui.components.ChatMessageBubble
import com.example.ui.components.PersistentChatBar
import com.example.ui.components.TelemetrySparklineCard
import com.example.ui.theme.AquaTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val QUICK_PROMPT_STARTERS = listOf(
    "Device Health" to "Check my current battery, Wi-Fi connectivity, available RAM, and storage space.",
    "Unread Gmail" to "Check my recent unread Gmail messages and summarize anything urgent.",
    "Top Tech News" to "Search the live web for today's biggest Android and AI engineering headlines with links.",
    "Clipboard Insights" to "Read my Android clipboard and tell me the most helpful next action."
)

@Composable
fun HomeScreen(
    viewModel: AquaMainViewModel,
    onOpenTab: (String) -> Unit,
    onOpenCommandPalette: () -> Unit = {}
) {
    val c = AquaTheme.colors
    val context = LocalContext.current
    val coreState by viewModel.coreState.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val stats by viewModel.storageAndMemory.collectAsStateWithLifecycle()
    val telemetrySamples by viewModel.telemetryHistory.collectAsStateWithLifecycle()
    val waveformBars by viewModel.waveformBars.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val streamingReply by viewModel.streamingReply.collectAsStateWithLifecycle()
    val agentTraces by viewModel.agentTraces.collectAsStateWithLifecycle()
    val pendingAttachment by viewModel.pendingAttachment.collectAsStateWithLifecycle()
    val isListening by viewModel.isListening.collectAsStateWithLifecycle()
    val isSpeaking by viewModel.isSpeaking.collectAsStateWithLifecycle()
    val isLiveActive by viewModel.isLiveSessionActive.collectAsStateWithLifecycle()
    val liveStatus by viewModel.liveStatus.collectAsStateWithLifecycle()
    val partialTranscript by viewModel.partialTranscript.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val wakeWordRunning by viewModel.wakeWordRunning.collectAsStateWithLifecycle()
    val wakeWordPaused by viewModel.wakeWordPaused.collectAsStateWithLifecycle()
    val automationKeepAliveActive by viewModel.automationKeepAliveActive.collectAsStateWithLifecycle()
    val activeAutomationTasks by viewModel.activeAutomationTasks.collectAsStateWithLifecycle()
    val personaId by viewModel.personaId.collectAsStateWithLifecycle()
    val routines by viewModel.routines.collectAsStateWithLifecycle()
    val pinnedIds by viewModel.pinnedRoutineIds.collectAsStateWithLifecycle()

    val activePersona = AquaPersona.fromId(personaId)
    val pinnedRoutines = remember(routines, pinnedIds) {
        routines.filter { pinnedIds.contains(it.id) }
    }

    var chatInput by rememberSaveable { mutableStateOf("") }
    var conversationExpanded by rememberSaveable { mutableStateOf(false) }
    var currentDateTimeLabel by remember { mutableStateOf(formatNow()) }

    LaunchedEffect(Unit) {
        while (true) {
            currentDateTimeLabel = formatNow()
            viewModel.refreshTelemetry()
            delay(15_000L)
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, streamingReply) {
        if (messages.isNotEmpty() || streamingReply.isNotBlank()) {
            conversationExpanded = true
            val target = (messages.size + if (streamingReply.isNotBlank()) 1 else 0).coerceAtLeast(1) - 1
            listState.animateScrollToItem(target)
        }
    }

    var pendingVoiceAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pendingVoiceAction?.invoke()
        pendingVoiceAction = null
    }

    val bgMicPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val micGranted = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        viewModel.setVoiceAndAutomationServiceEnabled(
            voiceEnabled = micGranted,
            automationEnabled = true
        )
    }

    val exportMarkdownLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        if (uri != null) {
            runCatching {
                val md = viewModel.buildActiveConversationMarkdown()
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(md.toByteArray(Charsets.UTF_8))
                }
            }.onSuccess {
                viewModel.copyToClipboard("Markdown Export", "Exported conversation to Markdown.")
            }
        }
    }

    fun toggleBackgroundAppAndMic(enable: Boolean) {
        if (!enable) {
            viewModel.setVoiceAndAutomationServiceEnabled(voiceEnabled = false, automationEnabled = false)
            return
        }
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val allGranted = perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            viewModel.setVoiceAndAutomationServiceEnabled(voiceEnabled = true, automationEnabled = true)
        } else {
            bgMicPermissionLauncher.launch(perms.toTypedArray())
        }
    }

    fun withMicPermission(action: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            pendingVoiceAction = action
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importAttachmentUri(uri)
    }

    val photoVideoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.importAttachmentUri(uri)
    }

    AquaBackdropBox(
        modifier = Modifier.fillMaxSize(),
        accentColor = when (coreState) {
            AquaCoreState.LISTENING -> c.accentTeal
            AquaCoreState.EXECUTING_TOOL -> c.amber
            AquaCoreState.ERROR -> c.error
            else -> c.accentCyan
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Spacious Non-Truncating Telemetry Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    color = c.cardSurface.copy(alpha = 0.9f),
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
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (telemetry.isOnline) c.success else c.error)
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
                            .background(c.cardSurface.copy(alpha = 0.9f))
                            .border(1.dp, c.borderGlow, CircleShape)
                            .testTag("home_command_palette_button")
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = "Open Command Palette", tint = c.accentCyan)
                    }

                    IconButton(
                        onClick = { onOpenTab("launcher") },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(CircleShape)
                            .background(c.cardSurface.copy(alpha = 0.9f))
                            .border(1.dp, c.borderGlow, CircleShape)
                            .testTag("home_launcher_shortcut")
                    ) {
                        Icon(Icons.Filled.Home, contentDescription = "Open Aqua Launcher", tint = c.accentCyan)
                    }

                    IconButton(
                        onClick = { onOpenTab("workspace") },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(CircleShape)
                            .background(c.cardSurface.copy(alpha = 0.9f))
                            .border(1.dp, c.borderGlow, CircleShape)
                            .testTag("home_workspace_shortcut")
                    ) {
                        Icon(Icons.Filled.Apps, contentDescription = "Open Google Workspace", tint = c.accentTeal)
                    }

                    IconButton(
                        onClick = { onOpenTab("settings") },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(CircleShape)
                            .background(c.cardSurface.copy(alpha = 0.9f))
                            .border(1.dp, c.borderGlow, CircleShape)
                            .testTag("home_settings_shortcut")
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = "Open Settings", tint = c.accentCyan)
                    }
                }
            }

            // Main Core + Expandable Conversation Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (!conversationExpanded && messages.isEmpty() && streamingReply.isBlank()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            AquaCoreOrb(
                                coreState = if (isLiveActive) AquaCoreState.SPEAKING else coreState,
                                waveformBars = waveformBars,
                                personaBadge = activePersona.badge,
                                onTapOrb = {
                                    if (isSpeaking) {
                                        viewModel.stopSpeaking()
                                    } else if (isListening) {
                                        viewModel.stopVoiceCapture()
                                    } else {
                                        withMicPermission { viewModel.startVoiceCapture(autoSendAndSpeak = true) }
                                    }
                                }
                            )
                        }

                        // AI Persona Mode Switcher
                        item {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(AquaPersona.entries) { persona ->
                                    val selected = persona.id == personaId
                                    FilterChip(
                                        selected = selected,
                                        onClick = { viewModel.setAiPersona(persona.id) },
                                        label = { Text(persona.label) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = c.accentCyan.copy(alpha = 0.22f),
                                            selectedLabelColor = c.accentCyan,
                                            containerColor = c.cardSurface,
                                            labelColor = c.textMuted
                                        ),
                                        modifier = Modifier.testTag("persona_chip_${persona.id}")
                                    )
                                }
                            }
                        }

                        if (partialTranscript.isNotBlank()) {
                            item {
                                AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "\"$partialTranscript\"",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = c.accentTeal,
                                        modifier = Modifier.padding(14.dp)
                                    )
                                }
                            }
                        }

                        // Pinned Routines + Quick Starters Strip
                        item {
                            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = if (pinnedRoutines.isNotEmpty()) "PINNED ROUTINES & QUICK PROMPTS" else "QUICK PROMPT STARTERS",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = c.textMuted
                                )
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(pinnedRoutines, key = { "pinned_${it.id}" }) { routine ->
                                        AssistChip(
                                            onClick = { viewModel.executeRoutine(routine) },
                                            label = { Text(routine.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            leadingIcon = {
                                                Icon(Icons.Filled.PushPin, contentDescription = null, modifier = Modifier.size(16.dp))
                                            },
                                            colors = AssistChipDefaults.assistChipColors(
                                                containerColor = c.accentTeal.copy(alpha = 0.16f),
                                                labelColor = c.textPrimary,
                                                leadingIconContentColor = c.accentTeal
                                            ),
                                            modifier = Modifier.testTag("home_pinned_routine_${routine.id}")
                                        )
                                    }
                                    items(QUICK_PROMPT_STARTERS) { (label, prompt) ->
                                        AssistChip(
                                            onClick = {
                                                chatInput = prompt
                                                conversationExpanded = true
                                                viewModel.sendMessage(prompt, speakReply = false)
                                            },
                                            label = { Text(label) },
                                            leadingIcon = {
                                                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(15.dp))
                                            },
                                            colors = AssistChipDefaults.assistChipColors(
                                                containerColor = c.cardSurface,
                                                labelColor = c.textPrimary,
                                                leadingIconContentColor = c.accentCyan
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        // Gemini Live Voice Card
                        item {
                            AquaGlassCard(
                                containerColor = if (isLiveActive) c.accentAzure.copy(alpha = 0.24f) else c.cardSurface,
                                borderColor = if (isLiveActive) c.accentCyan else c.borderGlow,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(22.dp))
                                    .clickable {
                                        withMicPermission { viewModel.toggleLiveVoiceSession() }
                                    }
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
                        }

                        // Foreground Priority Service Card
                        item {
                            val bgActive = wakeWordRunning || settings.wakeWordEnabled || automationKeepAliveActive || settings.backgroundAutomationEnabled
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
                                                    "Persistent notification active • Tap play to resume '${settings.wakeWordPhrase}'"
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
                                            onClick = { viewModel.toggleBackgroundMicPaused() },
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
                                        onCheckedChange = { enable -> toggleBackgroundAppAndMic(enable) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = c.onAccent,
                                            checkedTrackColor = c.accentTeal
                                        ),
                                        modifier = Modifier.testTag("background_mic_switch")
                                    )
                                }
                            }
                        }

                        // Quick-Access Module Tiles
                        item {
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
                                item {
                                    QuickShortcutTile(Icons.Filled.Home, "Launcher", c.accentCyan, "shortcut_launcher") { onOpenTab("launcher") }
                                }
                                item {
                                    QuickShortcutTile(Icons.Filled.AutoMode, "Routines", c.accentCyan, "shortcut_routines") { onOpenTab("routines") }
                                }
                                item {
                                    QuickShortcutTile(Icons.Filled.Apps, "Workspace", c.success, "shortcut_workspace") { onOpenTab("workspace") }
                                }
                                item {
                                    QuickShortcutTile(Icons.Filled.History, "History", c.accentCyan, "shortcut_history") { onOpenTab("memory") }
                                }
                                item {
                                    QuickShortcutTile(Icons.Filled.Extension, "Skills", c.accentTeal, "shortcut_skills") { onOpenTab("skills") }
                                }
                                item {
                                    QuickShortcutTile(Icons.Filled.Language, "Browser", c.accentAzure, "shortcut_browser") { onOpenTab("browser") }
                                }
                                item {
                                    QuickShortcutTile(Icons.Filled.AccountBalanceWallet, "Wallet", c.amber, "shortcut_wallet") { onOpenTab("wallet") }
                                }
                                item {
                                    QuickShortcutTile(Icons.Filled.Memory, "Memory", c.accentCyan, "shortcut_memory") { onOpenTab("memory") }
                                }
                            }
                        }

                        // Quick Device Controls
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                QuickDeviceButton(
                                    icon = Icons.Filled.FlashlightOn,
                                    label = if (telemetry.flashlightEnabled) "Torch ON" else "Torch OFF",
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        viewModel.deviceController.toggleFlashlight()
                                        viewModel.refreshTelemetry()
                                    }
                                )
                                QuickDeviceButton(
                                    icon = Icons.Filled.ContentPasteGo,
                                    label = "Clipboard AI",
                                    modifier = Modifier.weight(1f),
                                    onClick = { viewModel.askAquaWithClipboard() }
                                )
                                QuickDeviceButton(
                                    icon = if (telemetry.isOnline) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                                    label = "Wi-Fi",
                                    modifier = Modifier.weight(1f),
                                    onClick = { viewModel.deviceController.openAndroidSettings("wifi") }
                                )
                                QuickDeviceButton(
                                    icon = Icons.Filled.ChatBubbleOutline,
                                    label = "New Chat",
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        viewModel.startNewConversation()
                                        conversationExpanded = true
                                    }
                                )
                            }
                        }

                        // Real Session Telemetry Sparkline Card
                        item {
                            TelemetrySparklineCard(
                                telemetry = telemetry,
                                stats = stats,
                                samples = telemetrySamples,
                                onOpenSettingsSection = { section -> viewModel.deviceController.openAndroidSettings(section) }
                            )
                        }

                        item {
                            Surface(
                                color = c.cardSurface.copy(alpha = 0.72f),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.Bookmark, contentDescription = null, tint = c.accentCyan, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "Model: ${settings.preferredModel} • Thinking: ${if (settings.thinkingEnabled) "HIGH" else "OFF"} • Persona: ${activePersona.label}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = c.textMuted,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Expanded Continuous Conversation View with Markdown Export & Header Bar
                    Column(modifier = Modifier.fillMaxSize()) {
                        Surface(
                            color = c.cardSurface.copy(alpha = 0.92f),
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
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(c.accentCyan)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = when (coreState) {
                                            AquaCoreState.THINKING -> "Aqua Thinking (${settings.preferredModel})..."
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
                                            onClick = {
                                                val safeName = viewModel.activeConversationTitle()
                                                    .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                                                    .take(36)
                                                    .ifBlank { "aqua_session" }
                                                exportMarkdownLauncher.launch("$safeName.md")
                                            },
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
                                            .clickable { viewModel.startNewConversation() }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                    Text(
                                        text = "Orb",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = c.textMuted,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { conversationExpanded = false }
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
                                        onSpeakClick = { viewModel.speakMessage(it) },
                                        onStopSpeakClick = { viewModel.stopSpeaking() },
                                        onCopyClick = { viewModel.copyToClipboard("Aqua AI Reply", it) },
                                        onShareClick = { viewModel.shareText("Aqua AI Response", it) },
                                        onSaveMemoryClick = { viewModel.saveAssistantReplyToMemory(it) }
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
            }

            PersistentChatBar(
                inputText = chatInput,
                onInputTextChange = { chatInput = it },
                isListening = isListening,
                isExpanded = conversationExpanded,
                pendingAttachment = pendingAttachment,
                onClearAttachment = { viewModel.clearPendingAttachment() },
                onToggleExpand = { conversationExpanded = !conversationExpanded },
                onMicClick = {
                    if (isListening) {
                        viewModel.stopVoiceCapture()
                    } else {
                        withMicPermission { viewModel.startVoiceCapture(autoSendAndSpeak = true) }
                    }
                },
                onPickDocumentClick = { documentPickerLauncher.launch(arrayOf("*/*")) },
                onPickMediaClick = {
                    photoVideoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    )
                },
                onSendClick = {
                    val toSend = chatInput
                    chatInput = ""
                    conversationExpanded = true
                    viewModel.sendMessage(toSend, speakReply = false)
                }
            )
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
    Surface(
        color = c.cardSurface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .width(88.dp)
            .border(1.dp, c.borderGlow, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
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
    Surface(
        color = c.deepSurface,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .border(1.dp, c.borderGlow, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
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

private fun formatNow(): String {
    val fmt = SimpleDateFormat("EEE, MMM d • HH:mm", Locale.getDefault())
    return fmt.format(Date())
}
