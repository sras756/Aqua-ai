package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.AquaRoutine
import com.example.ui.AquaMainViewModel
import com.example.ui.theme.AquaAbyss
import com.example.ui.theme.AquaBorderGlow
import com.example.ui.theme.AquaCardSurface
import com.example.ui.theme.AquaCoralError
import com.example.ui.theme.AquaCyan
import com.example.ui.theme.AquaDeepSurface
import com.example.ui.theme.AquaIceText
import com.example.ui.theme.AquaMintSuccess
import com.example.ui.theme.AquaMutedText
import com.example.ui.theme.AquaTeal

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
    val routines by viewModel.routines.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val sysStats by viewModel.storageAndMemory.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }

    val ramUsedMb = (sysStats.ramTotalMb - sysStats.ramAvailableMb).coerceAtLeast(0L)
    val ramProgress = if (sysStats.ramTotalMb > 0L) {
        (ramUsedMb.toFloat() / sysStats.ramTotalMb.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val storageUsedMb = (sysStats.storageTotalMb - sysStats.storageFreeMb).coerceAtLeast(0L)
    val storageProgress = if (sysStats.storageTotalMb > 0L) {
        (storageUsedMb.toFloat() / sysStats.storageTotalMb.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AquaAbyss)
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
                    color = AquaIceText
                )
                Text(
                    text = "Save multi-step AI commands & inspect live system health.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AquaMutedText
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick = { viewModel.refreshTelemetry() },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("routines_refresh_telemetry")
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh telemetry", tint = AquaCyan)
                }
                Button(
                    onClick = { showCreateDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = AquaCyan, contentColor = AquaAbyss),
                    modifier = Modifier.testTag("create_routine_button")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("New")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Live Device Hardware & System Pulse Card (Real Android StatFs + ActivityManager)
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "LIVE ANDROID HARDWARE PULSE",
                                style = MaterialTheme.typography.labelMedium,
                                color = AquaCyan
                            )
                            Text(
                                text = if (telemetry.isOnline) "${telemetry.connectionType} • ${telemetry.batteryPercent}%" else "Offline • ${telemetry.batteryPercent}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (telemetry.isOnline) AquaMintSuccess else AquaCoralError
                            )
                        }

                        // RAM Usage
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Memory, contentDescription = null, tint = AquaTeal, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("System RAM", style = MaterialTheme.typography.labelMedium, color = AquaIceText)
                                }
                                Text(
                                    text = "${sysStats.ramAvailableMb} MB free / ${sysStats.ramTotalMb} MB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AquaMutedText
                                )
                            }
                            LinearProgressIndicator(
                                progress = { ramProgress },
                                color = if (sysStats.lowMemory) AquaCoralError else AquaTeal,
                                trackColor = AquaDeepSurface,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Internal Storage Usage
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Storage, contentDescription = null, tint = AquaCyan, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Internal Storage", style = MaterialTheme.typography.labelMedium, color = AquaIceText)
                                }
                                Text(
                                    text = "${formatMbToGb(sysStats.storageFreeMb)} free / ${formatMbToGb(sysStats.storageTotalMb)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AquaMutedText
                                )
                            }
                            LinearProgressIndicator(
                                progress = { storageProgress },
                                color = AquaCyan,
                                trackColor = AquaDeepSurface,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        OutlinedButton(
                            onClick = { viewModel.askAquaWithClipboard() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("clipboard_analyze_button")
                        ) {
                            Icon(Icons.Filled.ContentPasteGo, contentDescription = null, tint = AquaCyan, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Analyze Current Clipboard with Aqua", color = AquaCyan)
                        }
                    }
                }
            }

            if (routines.isEmpty()) {
                item {
                    Surface(
                        color = AquaCardSurface.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, AquaBorderGlow, RoundedCornerShape(18.dp))
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AutoMode,
                                contentDescription = null,
                                tint = AquaCyan,
                                modifier = Modifier.size(40.dp)
                            )
                            Text(
                                text = "No Saved Routines Yet",
                                style = MaterialTheme.typography.titleMedium,
                                color = AquaIceText
                            )
                            Text(
                                text = "Author reusable multi-step commands for Aqua to run across your Android device, Google Workspace, Browser, or Wallet with one tap.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AquaMutedText
                            )
                            Button(
                                onClick = { showCreateDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = AquaCyan, contentColor = AquaAbyss)
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
                        onRun = { viewModel.executeRoutine(routine) },
                        onDelete = { viewModel.deleteRoutine(routine.id) }
                    )
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
    onRun: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, AquaBorderGlow, RoundedCornerShape(16.dp))
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
                            color = AquaIceText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (routine.speakResult) {
                            Icon(
                                imageVector = Icons.Filled.VolumeUp,
                                contentDescription = "Voice readout enabled",
                                tint = AquaTeal,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Text(
                        text = "${routine.category.uppercase()} • Executed ${routine.runCount}x",
                        style = MaterialTheme.typography.labelSmall,
                        color = AquaCyan
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onRun,
                        colors = ButtonDefaults.buttonColors(containerColor = AquaCyan, contentColor = AquaAbyss),
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
                        Icon(Icons.Filled.Delete, contentDescription = "Delete routine", tint = AquaMutedText)
                    }
                }
            }

            Text(
                text = routine.prompt,
                style = MaterialTheme.typography.bodyMedium,
                color = AquaMutedText,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            if (routine.lastRunSummary.isNotBlank()) {
                Surface(
                    color = AquaDeepSurface,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Last Result: ${routine.lastRunSummary}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AquaIceText,
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
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("productivity") }
    var speakResult by remember { mutableStateOf(false) }

    val categories = listOf("productivity", "workspace", "device", "browser", "crypto", "custom")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AquaDeepSurface,
        title = { Text("Author Autonomous Routine", color = AquaIceText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Quick starter builders (optional — edit before saving):",
                    style = MaterialTheme.typography.labelSmall,
                    color = AquaMutedText
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
                                containerColor = AquaCardSurface,
                                labelColor = AquaCyan
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
                        focusedBorderColor = AquaCyan,
                        unfocusedBorderColor = AquaBorderGlow,
                        focusedTextColor = AquaIceText,
                        unfocusedTextColor = AquaIceText
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
                                selectedContainerColor = AquaCyan.copy(alpha = 0.25f),
                                selectedLabelColor = AquaCyan
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
                        focusedBorderColor = AquaCyan,
                        unfocusedBorderColor = AquaBorderGlow,
                        focusedTextColor = AquaIceText,
                        unfocusedTextColor = AquaIceText
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
                        color = AquaIceText
                    )
                    Switch(
                        checked = speakResult,
                        onCheckedChange = { speakResult = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = AquaCyan)
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
                colors = ButtonDefaults.buttonColors(containerColor = AquaCyan, contentColor = AquaAbyss),
                modifier = Modifier.testTag("save_routine_button")
            ) {
                Text("Save Routine", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel", color = AquaIceText)
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
