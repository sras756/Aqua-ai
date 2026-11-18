package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.AquaPersona
import com.example.data.model.AquaRoutine
import com.example.ui.theme.AquaTheme

private data class CommandModuleEntry(
    val route: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector
)

private val MODULE_ENTRIES = listOf(
    CommandModuleEntry("home", "Aqua Core", "AI orb, Gemini Live voice & conversation", Icons.Filled.GraphicEq),
    CommandModuleEntry("routines", "Routines & Pulse", "Saved multi-step workflows & telemetry", Icons.Filled.AutoMode),
    CommandModuleEntry("image_plugin", "Image Studio Plugin", "Generate & transform images via Firebase AI Logic", Icons.Filled.AutoAwesome),
    CommandModuleEntry("drive", "Google Drive Vault", "AppData sync, JSON exports, quota & approved API key", Icons.Filled.CloudSync),
    CommandModuleEntry("sdk", "Android SDK & Signature", "Live APK SHA-1/SHA-256 verification & SDK capabilities", Icons.Filled.VerifiedUser),
    CommandModuleEntry("skills", "Skills Engine", "Import & manage verified declarative skills", Icons.Filled.Extension),
    CommandModuleEntry("browser", "Aqua Browser", "Live web viewer & AI page summarizer", Icons.Filled.Language),
    CommandModuleEntry("wallet", "Crypto Wallet", "Multi-chain vaults, swaps, telemetry & AI tools", Icons.Filled.AccountBalanceWallet),
    CommandModuleEntry("memory", "Memory & History", "Saved context, Drive backup & device logs", Icons.Filled.Memory),
    CommandModuleEntry("settings", "Settings & Core", "Profile, age & gender, Gemini, voice & privacy", Icons.Filled.Settings)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandPaletteSheet(
    activePersonaId: String,
    routines: List<AquaRoutine>,
    installedApps: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onSelectPersona: (String) -> Unit,
    onOpenModule: (String) -> Unit,
    onRunRoutine: (AquaRoutine) -> Unit,
    onLaunchApp: (String) -> Unit
) {
    val c = AquaTheme.colors
    var query by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val q = query.trim().lowercase()
    val filteredModules = remember(q) {
        if (q.isEmpty()) MODULE_ENTRIES
        else MODULE_ENTRIES.filter {
            it.title.lowercase().contains(q) || it.subtitle.lowercase().contains(q)
        }
    }
    val filteredRoutines = remember(routines, q) {
        if (q.isEmpty()) routines.take(6)
        else routines.filter {
            it.title.lowercase().contains(q) || it.prompt.lowercase().contains(q)
        }.take(6)
    }
    val filteredApps = remember(installedApps, q) {
        if (q.isEmpty()) installedApps.take(8)
        else installedApps.filter {
            it.first.lowercase().contains(q) || it.second.lowercase().contains(q)
        }.take(12)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.deepSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Psychology, contentDescription = null, tint = c.accentCyan)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Aqua Command Palette",
                    style = MaterialTheme.typography.titleLarge,
                    color = c.textPrimary
                )
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Jump to modules, routines, or installed apps...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = c.accentCyan) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = c.accentCyan,
                    unfocusedBorderColor = c.borderGlow,
                    focusedTextColor = c.textPrimary,
                    unfocusedTextColor = c.textPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("command_palette_search")
            )

            Text(
                text = "ACTIVE AI PERSONA",
                style = MaterialTheme.typography.labelSmall,
                color = c.textMuted
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AquaPersona.entries) { persona ->
                    val selected = activePersonaId == persona.id
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectPersona(persona.id) },
                        label = { Text(persona.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = c.accentCyan.copy(alpha = 0.22f),
                            selectedLabelColor = c.accentCyan,
                            containerColor = c.cardSurface,
                            labelColor = c.textPrimary
                        )
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (filteredModules.isNotEmpty()) {
                    item {
                        Text(
                            text = "MODULES (${filteredModules.size})",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.accentCyan
                        )
                    }
                    items(filteredModules, key = { "mod_${it.route}" }) { mod ->
                        Surface(
                            color = c.cardSurface,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onDismiss()
                                    onOpenModule(mod.route)
                                }
                                .testTag("palette_module_${mod.route}")
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(mod.icon, contentDescription = null, tint = c.accentCyan, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(mod.title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                                    Text(
                                        mod.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = c.textMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                if (filteredRoutines.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "SAVED ROUTINES (${filteredRoutines.size})",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.accentTeal
                        )
                    }
                    items(filteredRoutines, key = { "rtn_${it.id}" }) { routine ->
                        Surface(
                            color = c.cardSurface,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onDismiss()
                                    onRunRoutine(routine)
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = c.accentTeal, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(routine.title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                                    Text(
                                        routine.prompt,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = c.textMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                if (filteredApps.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "INSTALLED ANDROID APPS (${filteredApps.size})",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.textMuted
                        )
                    }
                    items(filteredApps, key = { "app_${it.second}" }) { (label, pkg) ->
                        Surface(
                            color = c.cardSurface,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onDismiss()
                                    onLaunchApp(pkg)
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(label, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, fontWeight = FontWeight.Medium)
                                    Text(pkg, style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                                }
                                Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = "Launch $label", tint = c.accentCyan, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
