package com.example.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.MemoryItem
import com.example.data.repository.DriveBackupMetadata
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MemoryAndHistoryScreen(
    viewModel: AquaMainViewModel,
    onOpenHomeChat: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity

    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val logs by viewModel.deviceActionLogs.collectAsStateWithLifecycle()

    val driveBusy by viewModel.driveBusy.collectAsStateWithLifecycle()
    val driveConnected by viewModel.driveConnected.collectAsStateWithLifecycle()
    val driveBackups by viewModel.driveBackups.collectAsStateWithLifecycle()
    val driveStatusLabel by viewModel.driveStatusLabel.collectAsStateWithLifecycle()
    val driveAuthPendingIntent by viewModel.driveAuthPendingIntent.collectAsStateWithLifecycle()

    val driveConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.handleDriveConsentResult(result.data)
    }

    LaunchedEffect(driveAuthPendingIntent) {
        val pending = driveAuthPendingIntent ?: return@LaunchedEffect
        viewModel.consumeDriveAuthPendingIntent()
        driveConsentLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var editingMemory by remember { mutableStateOf<MemoryItem?>(null) }

    val installedApps = remember { viewModel.deviceController.getInstalledLaunchableApps() }

    Scaffold(
        containerColor = AquaAbyss,
        floatingActionButton = {
            if (selectedTab == 1) {
                FloatingActionButton(
                    onClick = { editingMemory = MemoryItem(category = "instruction") },
                    containerColor = AquaCyan,
                    contentColor = AquaAbyss,
                    modifier = Modifier.testTag("add_memory_fab")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add memory or instruction")
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
        ) {
            Text(
                text = "MEMORY, HISTORY & DEVICE",
                style = MaterialTheme.typography.headlineSmall,
                color = AquaIceText,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search conversations, memories, logs, or apps...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = AquaCyan) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AquaCyan,
                    unfocusedBorderColor = AquaBorderGlow,
                    focusedTextColor = AquaIceText,
                    unfocusedTextColor = AquaIceText
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("memory_search_input")
            )

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = AquaDeepSurface,
                contentColor = AquaCyan
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("History (${conversations.size})") },
                    icon = { Icon(Icons.Filled.History, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Memory (${memories.size})") },
                    icon = { Icon(Icons.Filled.Memory, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Device (${logs.size})") },
                    icon = { Icon(Icons.Filled.Terminal, contentDescription = null) }
                )
            }

            when (selectedTab) {
                0 -> {
                    val filtered = remember(conversations, searchQuery) {
                        val q = searchQuery.trim().lowercase()
                        if (q.isEmpty()) conversations
                        else conversations.filter {
                            it.title.lowercase().contains(q) || it.summary.lowercase().contains(q)
                        }
                    }
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            GoogleDriveHistoryCard(
                                connected = driveConnected,
                                busy = driveBusy,
                                statusLabel = driveStatusLabel,
                                backups = driveBackups,
                                onBackupAppData = { viewModel.backupHistoryToGoogleDrive(activity, exportVisibleCopy = false) },
                                onExportVisible = { viewModel.backupHistoryToGoogleDrive(activity, exportVisibleCopy = true) },
                                onRefresh = { viewModel.refreshDriveBackups(activity) },
                                onRestore = { fileId -> viewModel.restoreHistoryFromGoogleDrive(activity, fileId) }
                            )
                        }

                        if (filtered.isEmpty()) {
                            item {
                                Surface(
                                    color = AquaCardSurface.copy(alpha = 0.7f),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(Icons.Filled.History, contentDescription = null, tint = AquaCyan, modifier = Modifier.size(40.dp))
                                        Spacer(Modifier.height(8.dp))
                                        Text("No Saved Conversations", style = MaterialTheme.typography.titleMedium, color = AquaIceText)
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "Start a conversation from the Home screen chat bar or restore an existing backup from Google Drive above.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = AquaMutedText
                                        )
                                    }
                                }
                            }
                        } else {
                            items(filtered, key = { it.id }) { conv ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, AquaBorderGlow, RoundedCornerShape(16.dp))
                                        .clickable {
                                            viewModel.selectConversation(conv.id)
                                            onOpenHomeChat()
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = conv.title,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = AquaIceText,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (conv.summary.isNotBlank()) {
                                                Text(
                                                    text = conv.summary,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = AquaMutedText,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Text(
                                                text = "${conv.messageCount} msgs • ${conv.modelUsed}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = AquaCyan,
                                                modifier = Modifier.padding(top = 4.dp)
                                            )
                                        }
                                        IconButton(onClick = { viewModel.deleteConversation(conv.id) }) {
                                            Icon(
                                                Icons.Filled.DeleteOutline,
                                                contentDescription = "Delete conversation",
                                                tint = AquaCoralError
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> {
                    val filtered = remember(memories, searchQuery) {
                        val q = searchQuery.trim().lowercase()
                        if (q.isEmpty()) memories
                        else memories.filter {
                            it.title.lowercase().contains(q) ||
                                it.content.lowercase().contains(q) ||
                                it.category.lowercase().contains(q)
                        }
                    }
                    if (filtered.isEmpty()) {
                        EmptyVaultState(
                            icon = Icons.Filled.Memory,
                            title = "No Personal Memories",
                            subtitle = "Add instructions, preferences, or facts so Aqua remembers your personal workflow."
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(filtered, key = { it.id }) { mem ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, AquaBorderGlow, RoundedCornerShape(16.dp))
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Surface(
                                                color = AquaDeepSurface,
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text(
                                                    text = mem.category.uppercase(),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = AquaTeal,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                            Row {
                                                IconButton(onClick = { editingMemory = mem }) {
                                                    Icon(Icons.Filled.Edit, contentDescription = "Edit memory", tint = AquaCyan)
                                                }
                                                IconButton(onClick = { viewModel.deleteMemory(mem.id) }) {
                                                    Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete memory", tint = AquaCoralError)
                                                }
                                            }
                                        }
                                        Text(
                                            text = mem.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = AquaIceText
                                        )
                                        Text(
                                            text = mem.content,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = AquaMutedText,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                2 -> {
                    val q = searchQuery.trim().lowercase()
                    val filteredLogs = remember(logs, q) {
                        if (q.isEmpty()) logs else logs.filter {
                            it.toolName.lowercase().contains(q) || it.detail.lowercase().contains(q)
                        }
                    }
                    val filteredApps = remember(installedApps, q) {
                        if (q.isEmpty()) installedApps else installedApps.filter {
                            it.first.lowercase().contains(q) || it.second.lowercase().contains(q)
                        }
                    }
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Text(
                                text = "AUTONOMOUS DEVICE EXECUTION LOG",
                                style = MaterialTheme.typography.labelMedium,
                                color = AquaCyan
                            )
                        }
                        if (filteredLogs.isEmpty()) {
                            item {
                                Text(
                                    text = "No tool executions recorded on this device yet.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AquaMutedText
                                )
                            }
                        } else {
                            items(filteredLogs, key = { it.id }) { log ->
                                Surface(
                                    color = AquaCardSurface,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, AquaBorderGlow, RoundedCornerShape(12.dp))
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = log.toolName,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = AquaTeal
                                            )
                                            Text(
                                                text = SimpleDateFormat("MMM d HH:mm:ss", Locale.getDefault()).format(Date(log.timestampMs)),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = AquaMutedText
                                            )
                                        }
                                        Text(
                                            text = "[${log.status}] ${log.detail}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = AquaIceText,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Apps, contentDescription = null, tint = AquaCyan, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "LAUNCHABLE ANDROID APPS (${filteredApps.size})",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AquaCyan
                                )
                            }
                        }
                        items(filteredApps.take(30), key = { it.second }) { (label, pkg) ->
                            Surface(
                                color = AquaDeepSurface,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.deviceController.launchApp(pkg) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(label, style = MaterialTheme.typography.bodyLarge, color = AquaIceText)
                                        Text(pkg, style = MaterialTheme.typography.labelSmall, color = AquaMutedText)
                                    }
                                    Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = "Launch $label", tint = AquaMintSuccess, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editingMemory?.let { initial ->
        MemoryEditorDialog(
            initial = initial,
            onDismiss = { editingMemory = null },
            onSave = { updated ->
                viewModel.saveMemory(updated)
                editingMemory = null
            }
        )
    }
}

@Composable
private fun GoogleDriveHistoryCard(
    connected: Boolean,
    busy: Boolean,
    statusLabel: String?,
    backups: List<DriveBackupMetadata>,
    onBackupAppData: () -> Unit,
    onExportVisible: () -> Unit,
    onRefresh: () -> Unit,
    onRestore: (String) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, AquaCyan.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = if (connected) Icons.Filled.CloudDone else Icons.Filled.CloudUpload,
                        contentDescription = "Google Drive history sync",
                        tint = if (connected) AquaMintSuccess else AquaCyan,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Google Drive History Vault",
                            style = MaterialTheme.typography.titleMedium,
                            color = AquaIceText
                        )
                        Text(
                            text = statusLabel ?: if (connected) "Authorized via Google Drive OAuth" else "Connect Google Drive to back up & restore chats",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (connected) AquaTeal else AquaMutedText
                        )
                    }
                }
                IconButton(
                    onClick = onRefresh,
                    enabled = !busy,
                    modifier = Modifier.testTag("drive_refresh_button")
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AquaCyan)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh Google Drive backups", tint = AquaCyan)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onBackupAppData,
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = AquaCyan, contentColor = AquaAbyss),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("drive_backup_button")
                ) {
                    Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Back Up", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onExportVisible,
                    enabled = !busy,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("drive_export_button")
                ) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null, tint = AquaTeal, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export JSON", color = AquaTeal)
                }
            }

            if (backups.isNotEmpty()) {
                Text(
                    text = "DRIVE BACKUPS (${backups.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = AquaMutedText
                )
                backups.take(4).forEach { file ->
                    Surface(
                        color = AquaDeepSurface,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AquaIceText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${file.space} • ${file.sizeBytes} B",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AquaMutedText
                                )
                            }
                            OutlinedButton(
                                onClick = { onRestore(file.fileId) },
                                enabled = !busy,
                                modifier = Modifier.testTag("drive_restore_button")
                            ) {
                                Icon(Icons.Filled.CloudDownload, contentDescription = null, tint = AquaCyan, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Restore", color = AquaCyan)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyVaultState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = AquaCyan, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = AquaIceText)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = AquaMutedText)
        }
    }
}

@Composable
private fun MemoryEditorDialog(
    initial: MemoryItem,
    onDismiss: () -> Unit,
    onSave: (MemoryItem) -> Unit
) {
    var category by remember { mutableStateOf(initial.category.ifBlank { "instruction" }) }
    var title by remember { mutableStateOf(initial.title) }
    var content by remember { mutableStateOf(initial.content) }
    val categories = listOf("instruction", "preference", "fact", "reminder")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AquaDeepSurface,
        title = { Text(if (initial.id.isBlank()) "Add Memory" else "Edit Memory", color = AquaIceText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat) }
                        )
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Instruction / Fact Details") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = title.isNotBlank() && content.isNotBlank(),
                onClick = {
                    onSave(
                        initial.copy(
                            category = category,
                            title = title.trim(),
                            content = content.trim(),
                            approvedByUser = true
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = AquaCyan, contentColor = AquaAbyss)
            ) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel", color = AquaIceText)
            }
        }
    )
}
