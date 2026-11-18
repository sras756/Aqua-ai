package com.example.ui.components

import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AquaTheme

private data class PromptStudioTemplate(
    val id: String,
    val title: String,
    val category: String,
    val description: String,
    val icon: ImageVector,
    val fieldLabel: String,
    val fieldPlaceholder: String,
    val secondaryLabel: String? = null,
    val secondaryPlaceholder: String? = null,
    val builder: (primary: String, secondary: String) -> String
)

private val STUDIO_TEMPLATES = listOf(
    PromptStudioTemplate(
        id = "deep_brief",
        title = "Live Web Research Brief",
        category = "RESEARCH",
        description = "Search live sources and produce a cited executive summary.",
        icon = Icons.Filled.Search,
        fieldLabel = "Research Topic or Question",
        fieldPlaceholder = "e.g. Latest Android 16 privacy sandbox updates",
        secondaryLabel = "Focus Angle (Optional)",
        secondaryPlaceholder = "e.g. Developer migration checklist",
        builder = { topic, focus ->
            buildString {
                append("Search the live web and write a verified, cited research brief on: $topic.")
                if (focus.isNotBlank()) append(" Emphasize: $focus.")
                append(" Include key takeaways, open risks, and source links.")
            }
        }
    ),
    PromptStudioTemplate(
        id = "code_audit",
        title = "Code & Architecture Review",
        category = "ENGINEERING",
        description = "Analyze a snippet or design pattern for bugs, edge cases, and performance.",
        icon = Icons.Filled.Code,
        fieldLabel = "Language / Stack",
        fieldPlaceholder = "e.g. Kotlin + Jetpack Compose",
        secondaryLabel = "Code or Problem Statement",
        secondaryPlaceholder = "Paste snippet or describe the module...",
        builder = { stack, code ->
            "Act as a senior $stack engineer. Audit the following for correctness, edge cases, concurrency safety, and clarity, then provide refactored code:\n\n$code"
        }
    ),
    PromptStudioTemplate(
        id = "translate_polish",
        title = "Translate & Tone Rewrite",
        category = "LANGUAGE",
        description = "Translate cleanly or adapt text to executive, concise, or technical tone.",
        icon = Icons.Filled.Translate,
        fieldLabel = "Target Language or Tone",
        fieldPlaceholder = "e.g. Spanish (professional) or Crisp English bullets",
        secondaryLabel = "Text to Translate / Rewrite",
        secondaryPlaceholder = "Enter or paste text here...",
        builder = { target, body ->
            "Translate or rewrite the following text into $target while preserving exact meaning and technical terms:\n\n$body"
        }
    ),
    PromptStudioTemplate(
        id = "onchain_audit",
        title = "On-Chain Address / Risk Audit",
        category = "WEB3",
        description = "Inspect balance telemetry, ENS identity, or ERC-20 approval safety.",
        icon = Icons.Filled.AccountBalanceWallet,
        fieldLabel = "Address, ENS Name, or Chain Focus",
        fieldPlaceholder = "e.g. Check my Ethereum & Base ERC-20 allowances",
        builder = { target, _ ->
            "Using read-only wallet tools where permitted, inspect and summarize on-chain status, balance risk, and allowance hygiene for: $target. Never attempt any state-changing action without explicit review."
        }
    ),
    PromptStudioTemplate(
        id = "drive_vault_audit",
        title = "Google Drive Vault Sync & Quota",
        category = "CLOUD VAULT",
        description = "Inspect Drive storage quota, API key readiness, and saved AppData snapshots.",
        icon = Icons.Filled.CloudSync,
        fieldLabel = "Drive Action Focus",
        fieldPlaceholder = "e.g. Check quota and list my latest history backups",
        builder = { scopeText, _ ->
            "Check my Google Drive Vault ($scopeText) using drive_get_storage_quota and drive_list_backups, and advise if I should sync a fresh AppData snapshot."
        }
    ),
    PromptStudioTemplate(
        id = "sdk_signature_audit",
        title = "Android SDK & Signature Verification",
        category = "SECURITY",
        description = "Audit live APK SHA-1/SHA-256 fingerprint, Play Services & Android 16 (API 36) capabilities.",
        icon = Icons.Filled.VerifiedUser,
        fieldLabel = "Verification Focus (Optional)",
        fieldPlaceholder = "e.g. Confirm Google Sign-In SHA-1 & StrongBox support",
        builder = { scopeText, _ ->
            "Run inspect_android_sdk_and_signature ($scopeText) and summarize APK SHA-1/SHA-256 trust, OAuth readiness, API level, ABIs, and hardware capabilities."
        }
    ),
    PromptStudioTemplate(
        id = "device_audit",
        title = "Android Device & Memory Check",
        category = "SYSTEM",
        description = "Read real live battery, RAM, network, storage, and background status.",
        icon = Icons.Filled.Memory,
        fieldLabel = "Diagnostic Question (Optional)",
        fieldPlaceholder = "e.g. Check memory headroom and battery health",
        builder = { focus, _ ->
            val extra = if (focus.isNotBlank()) " Focus on: $focus." else ""
            "Inspect live device telemetry (battery, Wi-Fi/cellular, RAM, internal storage) and Aqua Foreground Service status, then recommend concrete optimizations.$extra"
        }
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptStudioSheet(
    onDismiss: () -> Unit,
    onSubmitPrompt: (String) -> Unit
) {
    val c = AquaTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selected by remember { mutableStateOf<PromptStudioTemplate?>(null) }
    var primaryInput by remember { mutableStateOf("") }
    var secondaryInput by remember { mutableStateOf("") }

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
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = c.accentCyan)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (selected == null) "Prompt Studio" else selected!!.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = c.textPrimary
                )
            }

            if (selected == null) {
                Text(
                    text = "Structured multi-tool workflow builders powered by live Gemini & Android tools.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(STUDIO_TEMPLATES, key = { it.id }) { tpl ->
                        Surface(
                            color = c.cardSurface,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, c.borderGlow, RoundedCornerShape(16.dp))
                                .clickable {
                                    selected = tpl
                                    primaryInput = ""
                                    secondaryInput = ""
                                }
                                .testTag("prompt_studio_tpl_${tpl.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = c.accentCyan.copy(alpha = 0.16f),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = tpl.icon,
                                        contentDescription = null,
                                        tint = c.accentCyan,
                                        modifier = Modifier.padding(9.dp)
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(tpl.category, style = MaterialTheme.typography.labelSmall, color = c.accentTeal)
                                    Text(tpl.title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                                    Text(tpl.description, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                                }
                            }
                        }
                    }
                }
            } else {
                val tpl = selected!!
                val fieldColors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = c.accentCyan,
                    unfocusedBorderColor = c.borderGlow,
                    focusedTextColor = c.textPrimary,
                    unfocusedTextColor = c.textPrimary
                )
                Text(tpl.description, style = MaterialTheme.typography.bodySmall, color = c.textMuted)

                OutlinedTextField(
                    value = primaryInput,
                    onValueChange = { primaryInput = it },
                    label = { Text(tpl.fieldLabel) },
                    placeholder = { Text(tpl.fieldPlaceholder) },
                    colors = fieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("prompt_studio_primary_input")
                )

                if (tpl.secondaryLabel != null) {
                    OutlinedTextField(
                        value = secondaryInput,
                        onValueChange = { secondaryInput = it },
                        label = { Text(tpl.secondaryLabel) },
                        placeholder = { Text(tpl.secondaryPlaceholder.orEmpty()) },
                        minLines = 2,
                        maxLines = 5,
                        colors = fieldColors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("prompt_studio_secondary_input")
                    )
                }

                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { selected = null },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Back", color = c.textMuted)
                    }
                    val canRun = tpl.id in setOf("device_audit", "drive_vault_audit", "sdk_signature_audit") ||
                        primaryInput.isNotBlank() || secondaryInput.isNotBlank()
                    Button(
                        onClick = {
                            val built = tpl.builder(
                                primaryInput.ifBlank { tpl.fieldPlaceholder.removePrefix("e.g. ") },
                                secondaryInput
                            )
                            onDismiss()
                            onSubmitPrompt(built)
                        },
                        enabled = canRun,
                        colors = ButtonDefaults.buttonColors(containerColor = c.accentCyan, contentColor = c.onAccent),
                        modifier = Modifier
                            .weight(1.4f)
                            .testTag("prompt_studio_launch_button")
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Run in Core", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
