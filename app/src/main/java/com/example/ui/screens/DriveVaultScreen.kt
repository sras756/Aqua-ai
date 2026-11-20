package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.repository.DriveBackupMetadata
import com.example.ui.AquaMainViewModel
import com.example.ui.components.AquaAnimatedEntrance
import com.example.ui.components.AquaBackdropBox
import com.example.ui.components.AquaGlassCard
import com.example.ui.components.AquaSectionHeader
import com.example.ui.components.AquaStatusPill
import com.example.ui.theme.AquaTheme

private tailrec fun Context.findDriveActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findDriveActivity()
    else -> null
}

/**
 * Dedicated Google Drive Vault screen with least-privilege Drive AppData + File OAuth,
 * approved GOOGLE_DRIVE_API_KEY configuration (BuildConfig + Keystore encrypted override),
 * live Drive quota telemetry, and snapshot backup / restore / delete actions.
 */
@Composable
fun DriveVaultScreen(viewModel: AquaMainViewModel) {
    val c = AquaTheme.colors
    val context = LocalContext.current
    val activity = remember(context) { context.findDriveActivity() }

    val driveBusy by viewModel.driveBusy.collectAsStateWithLifecycle()
    val driveConnected by viewModel.driveConnected.collectAsStateWithLifecycle()
    val driveBackups by viewModel.driveBackups.collectAsStateWithLifecycle()
    val driveAboutInfo by viewModel.driveAboutInfo.collectAsStateWithLifecycle()
    val driveStatusLabel by viewModel.driveStatusLabel.collectAsStateWithLifecycle()
    val driveApiKeyStatus by viewModel.driveApiKeyStatus.collectAsStateWithLifecycle()
    val driveApiKeyMasked by viewModel.driveApiKeyMasked.collectAsStateWithLifecycle()
    val hasCustomDriveApiKey by viewModel.hasCustomDriveApiKey.collectAsStateWithLifecycle()
    val driveAuthPendingIntent by viewModel.driveAuthPendingIntent.collectAsStateWithLifecycle()
    val preferredDriveEmail by viewModel.preferredDriveEmail.collectAsStateWithLifecycle()

    var apiKeyInput by remember { mutableStateOf("") }

    val driveConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.handleDriveConsentResult(result.data, result.resultCode)
    }

    LaunchedEffect(Unit) {
        viewModel.probeSilentDriveSession()
    }

    LaunchedEffect(driveAuthPendingIntent) {
        val pending = driveAuthPendingIntent ?: return@LaunchedEffect
        viewModel.consumeDriveAuthPendingIntent()
        driveConsentLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
    }

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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AquaSectionHeader(
                    eyebrow = "Cloud Vault & Backup",
                    title = "Google Drive Vault",
                    subtitle = "AppData encrypted snapshots, JSON export & API key control",
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { viewModel.refreshDriveBackups(activity) },
                    enabled = !driveBusy,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("drive_refresh_button")
                ) {
                    if (driveBusy) {
                        CircularProgressIndicator(
                            color = c.accentCyan,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh Google Drive", tint = c.accentCyan)
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Google Drive Connection & Quota Card
                item {
                    AquaAnimatedEntrance(index = 0) {
                        AquaGlassCard(
                            borderColor = if (driveConnected) c.success else c.borderGlow,
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
                                        Icon(
                                            imageVector = if (driveConnected) Icons.Filled.CloudDone else Icons.Filled.CloudSync,
                                            contentDescription = null,
                                            tint = if (driveConnected) c.success else c.accentCyan
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = if (driveConnected) "Google Drive Authorized" else "Connect Google Drive",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = c.textPrimary
                                        )
                                    }
                                    AquaStatusPill(
                                        label = if (driveConnected) "OAUTH ACTIVE" else "CONNECT",
                                        accent = if (driveConnected) c.success else c.accentCyan
                                    )
                                }

                                Text(
                                    text = driveStatusLabel
                                        ?: if (driveConnected) {
                                            "One-tap Drive Vault active for encrypted snapshots & JSON exports."
                                        } else {
                                            "Sign in in one tap — works immediately via Google OAuth 2.0 (no API key required)."
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = c.textMuted
                                )

                                if (!driveConnected) {
                                    Surface(
                                        color = c.deepSurface,
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Filled.AccountCircle,
                                                    contentDescription = null,
                                                    tint = c.accentCyan,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    text = if (preferredDriveEmail.isNotBlank()) {
                                                        "Google Account ready: $preferredDriveEmail"
                                                    } else {
                                                        "Select any Google account on this device"
                                                    },
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = c.textPrimary
                                                )
                                            }
                                            Text(
                                                text = "• Auth Mode: Google Credential Manager OAuth 2.0",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = c.accentTeal
                                            )
                                            Text(
                                                text = "• Drive Access: Private AppData & exported Aqua JSON files only",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = c.textMuted
                                            )
                                        }
                                    }

                                    Button(
                                        onClick = { viewModel.connectGoogleDrive(activity, forceAccountPicker = false) },
                                        enabled = !driveBusy,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = c.accentCyan,
                                            contentColor = c.onAccent
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 50.dp)
                                            .testTag("drive_connect_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Login,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = if (preferredDriveEmail.isNotBlank()) {
                                                "Continue with Google Drive"
                                            } else {
                                                "Sign in with Google Drive"
                                            },
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = { viewModel.backupHistoryToGoogleDrive(activity, exportVisibleCopy = false) },
                                            enabled = !driveBusy,
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 48.dp)
                                                .testTag("drive_vault_backup_appdata")
                                        ) {
                                            Icon(Icons.Filled.CloudUpload, contentDescription = null, tint = c.accentCyan, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Connect & Back Up", color = c.accentCyan, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        OutlinedButton(
                                            onClick = { viewModel.connectGoogleDrive(activity, forceAccountPicker = true) },
                                            enabled = !driveBusy,
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 48.dp)
                                                .testTag("drive_switch_account_button")
                                        ) {
                                            Icon(Icons.Filled.SwapHoriz, contentDescription = null, tint = c.accentTeal, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Use Another Account", color = c.accentTeal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                } else {
                                    driveAboutInfo?.let { about ->
                                        val usedMb = about.storageUsageBytes / (1024L * 1024L)
                                        val limitGb = about.storageLimitBytes / (1024L * 1024L * 1024L)
                                        Text(
                                            text = "${about.displayName}${if (about.emailAddress.isNotBlank()) " (${about.emailAddress})" else ""}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = c.accentCyan
                                        )
                                        if (about.storageLimitBytes > 0L) {
                                            LinearProgressIndicator(
                                                progress = { about.usageFraction },
                                                color = c.accentTeal,
                                                trackColor = c.deepSurface,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(6.dp)
                                            )
                                            Text(
                                                text = "Drive Storage: $usedMb MB used of $limitGb GB",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = c.textMuted
                                            )
                                        }
                                    } ?: run {
                                        if (preferredDriveEmail.isNotBlank()) {
                                            Text(
                                                text = "Signed in as $preferredDriveEmail",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = c.accentCyan
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Button(
                                            onClick = { viewModel.backupHistoryToGoogleDrive(activity, exportVisibleCopy = false) },
                                            enabled = !driveBusy,
                                            colors = ButtonDefaults.buttonColors(containerColor = c.accentCyan, contentColor = c.onAccent),
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 48.dp)
                                                .testTag("drive_vault_backup_appdata")
                                        ) {
                                            Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Sync AppData", fontWeight = FontWeight.Bold)
                                        }
                                        OutlinedButton(
                                            onClick = { viewModel.backupHistoryToGoogleDrive(activity, exportVisibleCopy = true) },
                                            enabled = !driveBusy,
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 48.dp)
                                                .testTag("drive_vault_export_json")
                                        ) {
                                            Icon(Icons.Filled.CloudDownload, contentDescription = null, tint = c.accentTeal, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Export JSON", color = c.accentTeal)
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TextButton(
                                            onClick = { viewModel.connectGoogleDrive(activity, forceAccountPicker = true) },
                                            enabled = !driveBusy,
                                            modifier = Modifier.testTag("drive_switch_account_button")
                                        ) {
                                            Icon(Icons.Filled.SwapHoriz, contentDescription = null, tint = c.accentCyan, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Switch Account", color = c.accentCyan)
                                        }
                                        TextButton(
                                            onClick = { viewModel.disconnectGoogleDrive() },
                                            enabled = !driveBusy,
                                            modifier = Modifier.testTag("drive_disconnect_button")
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Disconnect", color = c.textMuted)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Optional Approved Google Drive API Key Card
                item {
                    AquaAnimatedEntrance(index = 1) {
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
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Icon(Icons.Filled.Key, contentDescription = null, tint = c.amber)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "Drive API Key (Optional)",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = c.textPrimary
                                        )
                                    }
                                    AquaStatusPill(
                                        label = if (hasCustomDriveApiKey) "KEYSTORE ACTIVE" else "OPTIONAL",
                                        accent = if (hasCustomDriveApiKey) c.success else c.textMuted
                                    )
                                }

                                Text(
                                    text = driveApiKeyStatus,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (hasCustomDriveApiKey) c.accentTeal else c.textMuted
                                )
                                if (hasCustomDriveApiKey && driveApiKeyMasked.isNotBlank()) {
                                    Text(
                                        text = "Stored Encrypted Override: $driveApiKeyMasked",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = c.accentCyan
                                    )
                                }

                                OutlinedTextField(
                                    value = apiKeyInput,
                                    onValueChange = { apiKeyInput = it },
                                    label = { Text("Google Drive API Key (AIza...)") },
                                    placeholder = { Text("Paste Cloud Console / Secrets Drive API key") },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    colors = fieldColors,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("drive_api_key_input")
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            viewModel.saveGoogleDriveApiKey(apiKeyInput)
                                            apiKeyInput = ""
                                        },
                                        enabled = apiKeyInput.isNotBlank() && !driveBusy,
                                        colors = ButtonDefaults.buttonColors(containerColor = c.amber, contentColor = c.onAccent),
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 48.dp)
                                            .testTag("drive_api_key_save_button")
                                    ) {
                                        Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Save & Verify", fontWeight = FontWeight.Bold)
                                    }
                                    OutlinedButton(
                                        onClick = { viewModel.verifyGoogleDriveApiKey() },
                                        enabled = !driveBusy,
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("drive_api_key_test_button")
                                    ) {
                                        Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = c.accentCyan, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Probe", color = c.accentCyan)
                                    }
                                    if (hasCustomDriveApiKey) {
                                        OutlinedButton(
                                            onClick = { viewModel.clearGoogleDriveApiKey() },
                                            modifier = Modifier
                                                .heightIn(min = 48.dp)
                                                .testTag("drive_api_key_clear_button")
                                        ) {
                                            Text("Clear", color = c.error)
                                        }
                                    }
                                }

                                Text(
                                    text = "Configure GOOGLE_DRIVE_API_KEY via the AI Studio Secrets panel (.env) or save an Android Keystore AES-256-GCM override here. Keystore storage stays strictly on-device.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = c.textMuted
                                )
                            }
                        }
                    }
                }

                // 3. Stored Backups Header
                item {
                    Text(
                        text = "STORED DRIVE SNAPSHOTS (${driveBackups.size})",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.accentCyan
                    )
                }

                if (driveBackups.isEmpty()) {
                    item {
                        AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "No backups indexed yet",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = c.textPrimary
                                )
                                Text(
                                    text = "Tap Refresh or Sync AppData above to connect your Google Drive account and inspect snapshots.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = c.textMuted
                                )
                            }
                        }
                    }
                } else {
                    items(driveBackups, key = { it.fileId }) { backup ->
                        DriveVaultBackupCard(
                            backup = backup,
                            busy = driveBusy,
                            onRestore = { viewModel.restoreHistoryFromGoogleDrive(activity, backup.fileId) },
                            onDelete = { viewModel.deleteDriveBackup(activity, backup.fileId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DriveVaultBackupCard(
    backup: DriveBackupMetadata,
    busy: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    val c = AquaTheme.colors
    AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = backup.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AquaStatusPill(label = backup.space, accent = c.accentTeal)
                    Text(
                        text = "${(backup.sizeBytes / 1024L).coerceAtLeast(1L)} KB",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.textMuted
                    )
                }
                if (backup.modifiedTime.isNotBlank()) {
                    Text(
                        text = "Modified: ${backup.modifiedTime}",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onRestore,
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Restore", color = c.accentCyan)
                }
                IconButton(
                    onClick = onDelete,
                    enabled = !busy,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete Drive backup", tint = c.error)
                }
            }
        }
    }
}
