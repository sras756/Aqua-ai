package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ContentPasteGo
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.AquaCoreState
import com.example.ui.AquaMainViewModel
import com.example.ui.components.AquaCoreOrb
import com.example.ui.components.ChatMessageBubble
import com.example.ui.components.PersistentChatBar
import com.example.ui.theme.AquaAbyss
import com.example.ui.theme.AquaAmber
import com.example.ui.theme.AquaAzure
import com.example.ui.theme.AquaBorderGlow
import com.example.ui.theme.AquaCardSurface
import com.example.ui.theme.AquaCoralError
import com.example.ui.theme.AquaCyan
import com.example.ui.theme.AquaIceText
import com.example.ui.theme.AquaMintSuccess
import com.example.ui.theme.AquaMutedText
import com.example.ui.theme.AquaTeal
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    viewModel: AquaMainViewModel,
    onOpenTab: (String) -> Unit
) {
    val context = LocalContext.current
    val coreState by viewModel.coreState.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
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
        if (granted) {
            pendingVoiceAction?.invoke()
        }
        pendingVoiceAction = null
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
        if (uri != null) {
            viewModel.importAttachmentUri(uri)
        }
    }

    val photoVideoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.importAttachmentUri(uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(AquaAbyss, Color(0xFF061A30), AquaAbyss)
                )
            )
            .statusBarsPadding()
    ) {
        // Futuristic Telemetry Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onOpenTab("settings") }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(if (telemetry.isOnline) AquaMintSuccess else AquaCoralError)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = when {
                            !telemetry.isOnline -> "OFFLINE"
                            else -> {
                                val webLabel = if (settings.fullInternetAccessEnabled) "FULL WEB" else "WEB PAUSED"
                                val walletLabel = when {
                                    !settings.aiWalletAccessEnabled -> "WALLET PAUSED"
                                    settings.aiWalletTransferPrepareEnabled -> "WALLET R/W"
                                    else -> "WALLET READ"
                                }
                                "ONLINE • ${telemetry.connectionType} • $webLabel • $walletLabel"
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (telemetry.isOnline) AquaMintSuccess else AquaCoralError,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = currentDateTimeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = AquaMutedText
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    color = AquaCardSurface,
                    shape = CircleShape,
                    modifier = Modifier.border(1.dp, AquaBorderGlow, CircleShape)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (telemetry.isCharging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryStd,
                            contentDescription = "Battery ${telemetry.batteryPercent}%",
                            tint = if (telemetry.batteryPercent in 0..20) AquaAmber else AquaTeal,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (telemetry.batteryPercent >= 0) "${telemetry.batteryPercent}%" else "--",
                            style = MaterialTheme.typography.labelMedium,
                            color = AquaIceText
                        )
                    }
                }

                IconButton(
                    onClick = { onOpenTab("workspace") },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(CircleShape)
                        .background(AquaCardSurface)
                        .testTag("home_workspace_shortcut")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Apps,
                        contentDescription = "Open Google Workspace",
                        tint = AquaTeal
                    )
                }

                IconButton(
                    onClick = { onOpenTab("settings") },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(CircleShape)
                        .background(AquaCardSurface)
                        .testTag("home_settings_shortcut")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Open Settings",
                        tint = AquaCyan
                    )
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
                // Full Futuristic Core Dashboard
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        AquaCoreOrb(
                            coreState = if (isLiveActive) AquaCoreState.SPEAKING else coreState,
                            waveformBars = waveformBars,
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

                    if (partialTranscript.isNotBlank()) {
                        item {
                            Surface(
                                color = AquaCardSurface,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "\"$partialTranscript\"",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = AquaTeal,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }

                    // Gemini Live Voice Card
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isLiveActive) AquaAzure.copy(alpha = 0.28f) else AquaCardSurface
                            ),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    1.dp,
                                    if (isLiveActive) AquaCyan else AquaBorderGlow,
                                    RoundedCornerShape(20.dp)
                                )
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
                                    color = if (isLiveActive) AquaCyan else AquaAzure.copy(alpha = 0.25f),
                                    shape = CircleShape,
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Filled.GraphicEq,
                                            contentDescription = "Gemini Live Voice",
                                            tint = if (isLiveActive) AquaAbyss else AquaCyan
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (isLiveActive) "Gemini Live Session Active" else "Start Gemini Live Voice",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = AquaIceText
                                    )
                                    Text(
                                        text = liveStatus,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = AquaMutedText,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    // Quick-Access Shortcuts Row
                    item {
                        Text(
                            text = "QUICK ACCESS & MODULES",
                            style = MaterialTheme.typography.labelMedium,
                            color = AquaMutedText,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            item {
                                QuickShortcutTile(
                                    icon = Icons.Filled.AutoMode,
                                    label = "Routines",
                                    accent = AquaCyan,
                                    tag = "shortcut_routines",
                                    onClick = { onOpenTab("routines") }
                                )
                            }
                            item {
                                QuickShortcutTile(
                                    icon = Icons.Filled.Apps,
                                    label = "Workspace",
                                    accent = AquaMintSuccess,
                                    tag = "shortcut_workspace",
                                    onClick = { onOpenTab("workspace") }
                                )
                            }
                            item {
                                QuickShortcutTile(
                                    icon = Icons.Filled.History,
                                    label = "History",
                                    accent = AquaCyan,
                                    tag = "shortcut_history",
                                    onClick = { onOpenTab("memory") }
                                )
                            }
                            item {
                                QuickShortcutTile(
                                    icon = Icons.Filled.Extension,
                                    label = "Skills",
                                    accent = AquaTeal,
                                    tag = "shortcut_skills",
                                    onClick = { onOpenTab("skills") }
                                )
                            }
                            item {
                                QuickShortcutTile(
                                    icon = Icons.Filled.Language,
                                    label = "Browser",
                                    accent = AquaAzure,
                                    tag = "shortcut_browser",
                                    onClick = { onOpenTab("browser") }
                                )
                            }
                            item {
                                QuickShortcutTile(
                                    icon = Icons.Filled.AccountBalanceWallet,
                                    label = "Wallet",
                                    accent = AquaAmber,
                                    tag = "shortcut_wallet",
                                    onClick = { onOpenTab("wallet") }
                                )
                            }
                            item {
                                QuickShortcutTile(
                                    icon = Icons.Filled.Memory,
                                    label = "Memory",
                                    accent = AquaCyan,
                                    tag = "shortcut_memory",
                                    onClick = { onOpenTab("memory") }
                                )
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

                    item {
                        Surface(
                            color = AquaCardSurface.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Bookmark, contentDescription = null, tint = AquaCyan, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Model: ${settings.preferredModel} • Thinking: ${if (settings.thinkingEnabled) "HIGH" else "OFF"} • Wake: ${if (settings.wakeWordEnabled) settings.wakeWordPhrase else "Manual"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AquaMutedText
                                )
                            }
                        }
                    }
                }
            } else {
                // Expanded Continuous Conversation View with Compact Core Bar
                Column(modifier = Modifier.fillMaxSize()) {
                    Surface(
                        color = AquaCardSurface.copy(alpha = 0.85f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, AquaBorderGlow, RoundedCornerShape(16.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(AquaCyan)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = when (coreState) {
                                        AquaCoreState.THINKING -> "Aqua Thinking (${settings.preferredModel})..."
                                        AquaCoreState.EXECUTING_TOOL -> "Executing Tool..."
                                        AquaCoreState.LISTENING -> "Listening..."
                                        AquaCoreState.SPEAKING -> "Speaking..."
                                        else -> "Continuous Context Active"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AquaCyan
                                )
                            }
                            Row {
                                Text(
                                    text = "New",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AquaTeal,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { viewModel.startNewConversation() }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                                Text(
                                    text = "Orb View",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AquaMutedText,
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
                            color = AquaAzure.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            val latest = agentTraces.last()
                            Text(
                                text = "Agent Step: ${latest.stepName} (${latest.toolName}) -> ${latest.detail}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AquaIceText,
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
                                        color = AquaMutedText
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
                                        color = AquaCardSurface,
                                        shape = RoundedCornerShape(18.dp),
                                        modifier = Modifier
                                            .fillMaxWidth(0.9f)
                                            .padding(vertical = 4.dp)
                                            .border(1.dp, AquaCyan.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                                    ) {
                                        Text(
                                            text = streamingReply,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = AquaIceText,
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

        // Permanent Home Screen Chat Bar
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
            onPickDocumentClick = {
                documentPickerLauncher.launch(arrayOf("*/*"))
            },
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

@Composable
private fun QuickShortcutTile(
    icon: ImageVector,
    label: String,
    accent: Color,
    tag: String,
    onClick: () -> Unit
) {
    Surface(
        color = AquaCardSurface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .width(86.dp)
            .border(1.dp, AquaBorderGlow, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag(tag)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = accent, modifier = Modifier.size(24.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = AquaIceText,
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
    Surface(
        color = AquaCardSurface.copy(alpha = 0.8f),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .border(1.dp, AquaBorderGlow, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = AquaCyan, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = AquaIceText,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatNow(): String {
    return SimpleDateFormat("EEE, MMM d • HH:mm", Locale.getDefault()).format(Date())
}
