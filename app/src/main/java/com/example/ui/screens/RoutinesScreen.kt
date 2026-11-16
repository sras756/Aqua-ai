package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.ContentPasteGo
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.AquaRoutine
import com.example.ui.AquaMainViewModel
import com.example.ui.components.AquaBackdropBox
import com.example.ui.components.AquaGlassCard
import com.example.ui.components.TelemetrySparklineCard
import com.example.ui.theme.AquaTheme

private data class RoutineComposerStarter(
    val chipLabel: String,
    val title: String,
    val category: String,
    val prompt: String
)

private val ROUTINE_COMPOSER_STARTERS = listOf(
    RoutineComposerStarter(
        chipLabel = "Device Pulse",
        title = "Device Diagnostics Pulse",
        category = "device",
        prompt = "Check my battery, network, storage, and available RAM, and report any resource warnings."
    ),
    RoutineComposerStarter(
        chipLabel = "Daily Briefing",
        title = "Workspace Morning Briefing",
        category = "workspace",
        prompt = "Check my upcoming Google Calendar events and recent unread Gmail messages, then give me a prioritized action summary."
    ),
    RoutineComposerStarter(
        chipLabel = "Market Check",
        title = "Portfolio & Market Check",
        category = "crypto",
        prompt = "Check live spot prices for Bitcoin and Ethereum and summarize my configured Flash Wallet balance if connected."
    ),
    RoutineComposerStarter(
        chipLabel = "Page Digest",
        title = "Active Tab Briefing",
        category = "browser",
        prompt = "Extract the current Aqua Browser webpage text and links, then summarize key takeaways."
    )
)

@Composable
fun RoutinesScreen(viewModel: AquaMainViewModel) {
    val c = AquaTheme.colors
    val context = LocalContext.current
    val routines by viewModel.routines.collectAsStateWithLifecycle()
    val pinnedIds by viewModel.pinnedRoutineIds.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val sysStats by viewModel.storageAndMemory.collectAsStateWithLifecycle()
    val telemetrySamples by viewModel.telemetryHistory.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val fgsRunning by viewModel.wakeWordRunning.collectAsStateWithLifecycle()
    val automationKeepAlive by viewModel.automationKeepAliveActive.collectAsStateWithLifecycle()
    val activeTasks by viewModel.activeAutomationTasks.collectAsStateWithLifecycle()
    val fgSummary by viewModel.foregroundStatusSummary.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.setAutomationKeepAliveEnabled(true)
    }

    fun toggleAutomationForegroundService(enable: Boolean) {
        if (!enable) {
            viewModel.setAutomationKeepAliveEnabled(false)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.setAutomationKeepAliveEnabled(true)
        }
    }

    val ramUsedMb = (sysStats.ramTotalMb - sysStats.ramAvailableMb).coerceAtLeast(0L)
    val ramProgress = if (sysStats.ramTotalMb > 0L) {
        (ramUsedMb.toFloat() / sysStats.ramTotalMb.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val storageUsedMb = (sysStats.storageTotalMb - sysStats.storageFreeMb).coerceAtLeast(0L)
    val storageProgress = if (sysStats.storageTotalMb > 0L) {
        (storageUsedMb.toFloat() / sysStats.storageTotalMb.toFloat()).coerceIn(0f, 1f)
    } else 0f

    AquaBackdropBox(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Autonomous Routines",
                        style = MaterialTheme.typography.headlineMedium,
                        color = c.textPrimary
                    )
                    Text(
                        text = "Pin workflows to Home & inspect real-time device health.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textMuted
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = { viewModel.refreshTelemetry() },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("routines_refresh_telemetry")
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh telemetry", tint = c.accentCyan)
                    }
                    Button(
                        onClick = { showCreateDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = c.accentCyan, contentColor = c.onAccent),
                        modifier = Modifier.testTag("create_routine_button")
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("New", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    val isPrioritized = fgsRunning || automationKeepAlive || settings.backgroundAutomationEnabled
                    AquaGlassCard(
                        containerColor = if (isPrioritized) c.accentTeal.copy(alpha = 0.14f) else c.cardSurface,
                        borderColor = if (isPrioritized) c.accentTeal else c.borderGlow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("routines_foreground_service_card")
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Notifications,
                                contentDescription = "Persistent Foreground Service",
                                tint = if (isPrioritized) c.accentTeal else c.accentCyan
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = when {
                                        activeTasks.isNotEmpty() -> "OS Priority Active (${activeTasks.size} Running)"
                                        isPrioritized -> "Foreground Priority Notification Active"
                                        else -> "Keep Automation & Voice Prioritized"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = c.textPrimary
                                )
                                Text(
                                    text = when {
                                        fgSummary.isNotBlank() -> fgSummary
                                        isPrioritized -> "Android keeps background routines & voice active."
                                        else -> "Runs a persistent notification so OS won't pause routines."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = c.textMuted,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Switch(
                                checked = isPrioritized,
                                onCheckedChange = { toggleAutomationForegroundService(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = c.onAccent,
                                    checkedTrackColor = c.accentTeal
                                ),
                                modifier = Modifier.testTag("routines_foreground_switch")
                            )
                        }
                    }
                }

                item {
                    TelemetrySparklineCard(
                        telemetry = telemetry,
                        stats = sysStats,
                        samples = telemetrySamples,
                        onOpenSettingsSection = { section -> viewModel.deviceController.openAndroidSettings(section) }
                    )
                }

                item {
                    AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "MEMORY & STORAGE DETAILS",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = c.accentCyan
                                )
                                Text(
                                    text = if (telemetry.isOnline) "${telemetry.connectionType} • ${telemetry.batteryPercent}%" else "Offline • ${telemetry.batteryPercent}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (telemetry.isOnline) c.success else c.error
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Memory, contentDescription = null, tint = c.accentTeal, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("System RAM", style = MaterialTheme.typography.labelMedium, color = c.textPrimary)
                                    }
                                    Text(
                                        text = "${sysStats.ramAvailableMb} MB free / ${sysStats.ramTotalMb} MB",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = c.textMuted
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { ramProgress },
                                    color = if (sysStats.lowMemory) c.error else c.accentTeal,
                                    trackColor = c.deepSurface,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Storage, contentDescription = null, tint = c.accentCyan, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Internal Storage", style = MaterialTheme.typography.labelMedium, color = c.textPrimary)
                                    }
                                    Text(
                                        text = "${formatMbToGb(sysStats.storageFreeMb)} free / ${formatMbToGb(sysStats.storageTotalMb)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = c.textMuted
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { storageProgress },
                                    color = c.accentCyan,
                                    trackColor = c.deepSurface,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            OutlinedButton(
                                onClick = { viewModel.askAquaWithClipboard() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("clipboard_analyze_button")
                            ) {
                                Icon(Icons.Filled.ContentPasteGo, contentDescription = null, tint = c.accentCyan, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Analyze Current Clipboard with Aqua", color = c.accentCyan)
                            }
                        }
                    }
                }

                if (routines.isEmpty()) {
                    item {
                        AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AutoMode,
                                    contentDescription = null,
                                    tint = c.accentCyan,
                                    modifier = Modifier.size(40.dp)
                                )
                                Text(
                                    text = "No Saved Routines Yet",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = c.textPrimary
                                )
                                Text(
                                    text = "Author reusable multi-step commands for Aqua to run across your Android device, Google Workspace, Browser, or Wallet with one tap.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = c.textMuted
                                )
                                Button(
                                    onClick = { showCreateDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = c.accentCyan, contentColor = c.onAccent)
                                ) {
                                    Text("Create Your First Routine", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                } else {
                    items(routines, key = { it.id }) { routine ->
                        RoutineCard(
                            routine = routine,
                            isPinned = pinnedIds.contains(routine.id),
                            onTogglePin = { viewModel.togglePinRoutine(routine.id) },
                            onRun = { viewModel.executeRoutine(routine) },
                            onDelete = { viewModel.deleteRoutine(routine.id) }
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateRoutineDialog(
            onDismiss = { showCreateDialog = false },
            onSave = { newRoutine ->
                viewModel.saveRoutine(newRoutine)
                showCreateDialog = false
            }
        )
    }
}

@Composable
private fun RoutineCard(
    routine: AquaRoutine,
    isPinned: Boolean,
    onTogglePin: () -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit
) {
    val c = AquaTheme.colors
    AquaGlassCard(
        borderColor = if (isPinned) c.accentTeal else c.borderGlow,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("routine_card_${routine.id}")
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
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = routine.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = c.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (routine.speakResult) {
                            Icon(
                                imageVector = Icons.Filled.VolumeUp,
                                contentDescription = "Voice readout enabled",
                                tint = c.accentTeal,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Text(
                        text = "${routine.category.uppercase()} • Executed ${routine.runCount}x",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.accentCyan
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onTogglePin,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("pin_routine_${routine.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PushPin,
                            contentDescription = if (isPinned) "Unpin from Home" else "Pin to Home",
                            tint = if (isPinned) c.accentTeal else c.textMuted
                        )
                    }
                    Button(
                        onClick = onRun,
                        colors = ButtonDefaults.buttonColors(containerColor = c.accentCyan, contentColor = c.onAccent),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("run_routine_${routine.id}")
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Run", fontWeight = FontWeight.Bold)
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete routine", tint = c.textMuted)
                    }
                }
            }

            Text(
                text = routine.prompt,
                style = MaterialTheme.typography.bodyMedium,
                color = c.textMuted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            if (routine.lastRunSummary.isNotBlank()) {
                Surface(
                    color = c.deepSurface,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Last Result: ${routine.lastRunSummary}",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CreateRoutineDialog(
    onDismiss: () -> Unit,
    onSave: (AquaRoutine) -> Unit
) {
    val c = AquaTheme.colors
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("productivity") }
    var speakResult by remember { mutableStateOf(false) }

    val categories = listOf("productivity", "workspace", "device", "browser", "crypto", "custom")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.deepSurface,
        title = { Text("Author Autonomous Routine", color = c.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Quick starter builders (optional — edit before saving):",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textMuted
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(ROUTINE_COMPOSER_STARTERS) { starter ->
                        AssistChip(
                            onClick = {
                                title = starter.title
                                prompt = starter.prompt
                                category = starter.category
                            },
                            label = { Text(starter.chipLabel) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = c.cardSurface,
                                labelColor = c.accentCyan
                            )
                        )
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Routine Title") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = c.accentCyan,
                        unfocusedBorderColor = c.borderGlow,
                        focusedTextColor = c.textPrimary,
                        unfocusedTextColor = c.textPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("routine_title_input")
                )

                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(categories) { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = c.accentCyan.copy(alpha = 0.25f),
                                selectedLabelColor = c.accentCyan
                            )
                        )
                    }
                }

                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Multi-step AI Instructions") },
                    minLines = 3,
                    maxLines = 5,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = c.accentCyan,
                        unfocusedBorderColor = c.borderGlow,
                        focusedTextColor = c.textPrimary,
                        unfocusedTextColor = c.textPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("routine_prompt_input")
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { speakResult = !speakResult },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Speak response aloud when finished",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textPrimary
                    )
                    Switch(
                        checked = speakResult,
                        onCheckedChange = { speakResult = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = c.accentCyan)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = title.isNotBlank() && prompt.isNotBlank(),
                onClick = {
                    onSave(
                        AquaRoutine(
                            title = title.trim(),
                            prompt = prompt.trim(),
                            category = category,
                            speakResult = speakResult
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = c.accentCyan, contentColor = c.onAccent),
                modifier = Modifier.testTag("save_routine_button")
            ) {
                Text("Save Routine", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel", color = c.textPrimary)
            }
        }
    )
}

private fun formatMbToGb(mb: Long): String {
    return if (mb >= 1024L) {
        String.format("%.1f GB", mb / 1024.0)
    } else {
        "$mb MB"
    }
}
