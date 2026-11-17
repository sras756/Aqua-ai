package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ContentPasteGo
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.AquaPersona
import com.example.data.model.AquaCoreState
import com.example.domain.UserGender
import com.example.domain.UserProfilePolicy
import com.example.ui.AquaMainViewModel
import com.example.ui.components.AquaAnimatedEntrance
import com.example.ui.components.AquaBackdropBox
import com.example.ui.components.AquaCoreOrb
import com.example.ui.components.AquaGlassCard
import com.example.ui.components.PersistentChatBar
import com.example.ui.components.PromptStudioSheet
import com.example.ui.components.TelemetrySparklineCard
import com.example.ui.screens.home.HomeConversationPanel
import com.example.ui.screens.home.HomeLiveAndForegroundSection
import com.example.ui.screens.home.HomeProfileAndEngineStrip
import com.example.ui.screens.home.HomeQuickDeviceControlsRow
import com.example.ui.screens.home.HomeQuickModulesStrip
import com.example.ui.screens.home.HomeTelemetryHeaderRow
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
    val freedomConfig by viewModel.aiFreedomConfig.collectAsStateWithLifecycle()
    val elevenConfig by viewModel.elevenLabsConfig.collectAsStateWithLifecycle()

    val usingPersonalKey = viewModel.isUsingPersonalAiStudioKey()
    val elevenLabsReady = viewModel.isElevenLabsActive()
    val ageTier = UserProfilePolicy.resolveTier(settings)
    val userGender = UserGender.fromId(settings.gender)

    val activePersona = AquaPersona.fromId(personaId)
    val pinnedRoutines = remember(routines, pinnedIds) {
        routines.filter { pinnedIds.contains(it.id) }
    }

    var chatInput by rememberSaveable { mutableStateOf("") }
    var conversationExpanded by rememberSaveable { mutableStateOf(false) }
    var showPromptStudio by rememberSaveable { mutableStateOf(false) }
    var clipboardPreview by remember { mutableStateOf<String?>(null) }
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
            HomeTelemetryHeaderRow(
                telemetry = telemetry,
                currentDateTimeLabel = currentDateTimeLabel,
                onOpenCommandPalette = onOpenCommandPalette,
                onOpenTab = onOpenTab
            )

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
                            AquaAnimatedEntrance(index = 0) {
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
                        }

                        item {
                            AquaAnimatedEntrance(index = 1) {
                                HomeProfileAndEngineStrip(
                                    ageTier = ageTier,
                                    gender = userGender,
                                    usingPersonalKey = usingPersonalKey,
                                    elevenLabsReady = elevenLabsReady,
                                    elevenVoiceName = elevenConfig.voiceName,
                                    useGeminiTts = settings.useGeminiTts,
                                    freedomConfig = freedomConfig,
                                    onOpenSetupWizard = { viewModel.openAiSetupWizard() },
                                    onOpenProfileSettings = { onOpenTab("settings") }
                                )
                            }
                        }

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

                        item {
                            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = if (pinnedRoutines.isNotEmpty()) "PINNED ROUTINES, STUDIO & QUICK PROMPTS" else "PROMPT STUDIO & QUICK STARTERS",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = c.textMuted
                                )
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    item(key = "open_prompt_studio") {
                                        AssistChip(
                                            onClick = { showPromptStudio = true },
                                            label = { Text("Prompt Studio") },
                                            leadingIcon = {
                                                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                            },
                                            colors = AssistChipDefaults.assistChipColors(
                                                containerColor = c.accentCyan.copy(alpha = 0.20f),
                                                labelColor = c.accentCyan,
                                                leadingIconContentColor = c.accentCyan
                                            ),
                                            modifier = Modifier.testTag("home_prompt_studio_button")
                                        )
                                    }
                                    item(key = "inspect_clipboard") {
                                        AssistChip(
                                            onClick = {
                                                val clip = viewModel.readClipboardPreview().trim()
                                                clipboardPreview = clip.ifBlank { null }
                                                if (clip.isBlank()) {
                                                    viewModel.copyToClipboard("Aqua", "Clipboard is currently empty.")
                                                }
                                            },
                                            label = { Text("Inspect Clipboard") },
                                            leadingIcon = {
                                                Icon(Icons.Filled.ContentPasteGo, contentDescription = null, modifier = Modifier.size(16.dp))
                                            },
                                            colors = AssistChipDefaults.assistChipColors(
                                                containerColor = c.cardSurface,
                                                labelColor = c.textPrimary,
                                                leadingIconContentColor = c.accentTeal
                                            ),
                                            modifier = Modifier.testTag("home_clipboard_inspect_button")
                                        )
                                    }
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

                                AnimatedVisibility(visible = !clipboardPreview.isNullOrBlank()) {
                                    val clip = clipboardPreview.orEmpty()
                                    AquaGlassCard(
                                        borderColor = c.accentTeal.copy(alpha = 0.5f),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("home_clipboard_preview_card")
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "CLIPBOARD PREVIEW (${clip.length} chars)",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = c.accentTeal
                                                )
                                                Text(
                                                    text = "Dismiss",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = c.textMuted,
                                                    modifier = Modifier.clickable { clipboardPreview = null }
                                                )
                                            }
                                            Text(
                                                text = clip.take(220) + if (clip.length > 220) "..." else "",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = c.textPrimary,
                                                maxLines = 3,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                item {
                                                    AssistChip(
                                                        onClick = {
                                                            clipboardPreview = null
                                                            conversationExpanded = true
                                                            viewModel.sendMessage("Summarize and highlight key action items from this clipboard text:\n\n$clip")
                                                        },
                                                        label = { Text("Summarize") }
                                                    )
                                                }
                                                item {
                                                    AssistChip(
                                                        onClick = {
                                                            clipboardPreview = null
                                                            conversationExpanded = true
                                                            viewModel.sendMessage("Explain and verify any technical, URL, or crypto details in this clipboard text:\n\n$clip")
                                                        },
                                                        label = { Text("Analyze") }
                                                    )
                                                }
                                                item {
                                                    AssistChip(
                                                        onClick = {
                                                            clipboardPreview = null
                                                            conversationExpanded = true
                                                            viewModel.sendMessage("Translate this text into natural English:\n\n$clip")
                                                        },
                                                        label = { Text("Translate") }
                                                    )
                                                }
                                                item {
                                                    AssistChip(
                                                        onClick = {
                                                            chatInput = clip
                                                            clipboardPreview = null
                                                        },
                                                        label = { Text("Paste to Input") }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            AquaAnimatedEntrance(index = 2) {
                                HomeLiveAndForegroundSection(
                                    isLiveActive = isLiveActive,
                                    liveStatus = liveStatus,
                                    wakeWordRunning = wakeWordRunning,
                                    wakeWordEnabled = settings.wakeWordEnabled,
                                    wakeWordPaused = wakeWordPaused,
                                    wakeWordPhrase = settings.wakeWordPhrase,
                                    automationKeepAliveActive = automationKeepAliveActive,
                                    backgroundAutomationEnabled = settings.backgroundAutomationEnabled,
                                    activeAutomationTasks = activeAutomationTasks,
                                    onToggleLiveSession = { withMicPermission { viewModel.toggleLiveVoiceSession() } },
                                    onToggleBackgroundMicPaused = { viewModel.toggleBackgroundMicPaused() },
                                    onToggleBackgroundService = { enable -> toggleBackgroundAppAndMic(enable) }
                                )
                            }
                        }

                        item {
                            AquaAnimatedEntrance(index = 3) {
                                HomeQuickModulesStrip(onOpenTab = onOpenTab)
                            }
                        }

                        item {
                            HomeQuickDeviceControlsRow(
                                telemetry = telemetry,
                                onToggleFlashlight = {
                                    viewModel.deviceController.toggleFlashlight()
                                    viewModel.refreshTelemetry()
                                },
                                onAskClipboard = { viewModel.askAquaWithClipboard() },
                                onOpenWifiSettings = { viewModel.deviceController.openAndroidSettings("wifi") },
                                onNewChat = {
                                    viewModel.startNewConversation()
                                    conversationExpanded = true
                                }
                            )
                        }

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
                    HomeConversationPanel(
                        coreState = coreState,
                        preferredModel = settings.preferredModel,
                        activePersona = activePersona,
                        messages = messages,
                        streamingReply = streamingReply,
                        agentTraces = agentTraces,
                        isSpeaking = isSpeaking,
                        listState = listState,
                        onExportMarkdown = {
                            val safeName = viewModel.activeConversationTitle()
                                .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                                .take(36)
                                .ifBlank { "aqua_session" }
                            exportMarkdownLauncher.launch("$safeName.md")
                        },
                        onNewConversation = { viewModel.startNewConversation() },
                        onCollapseToOrb = { conversationExpanded = false },
                        onSpeakMessage = { viewModel.speakMessage(it) },
                        onStopSpeaking = { viewModel.stopSpeaking() },
                        onCopyMessage = { viewModel.copyToClipboard("Aqua AI Reply", it) },
                        onShareMessage = { viewModel.shareText("Aqua AI Response", it) },
                        onSaveMemory = { viewModel.saveAssistantReplyToMemory(it) }
                    )
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

        if (showPromptStudio) {
            PromptStudioSheet(
                onDismiss = { showPromptStudio = false },
                onSubmitPrompt = { promptText ->
                    conversationExpanded = true
                    viewModel.sendMessage(promptText, speakReply = false)
                }
            )
        }
    }
}

private fun formatNow(): String {
    val fmt = SimpleDateFormat("EEE, MMM d • HH:mm", Locale.getDefault())
    return fmt.format(Date())
}
