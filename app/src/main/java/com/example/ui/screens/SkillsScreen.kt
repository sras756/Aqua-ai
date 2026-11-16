package com.example.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.model.AquaSkill
import com.example.skills.SkillParserAndValidator
import com.example.ui.AquaMainViewModel
import com.example.ui.components.AquaBackdropBox
import com.example.ui.components.AquaGlassCard
import com.example.ui.theme.AquaTheme

@Composable
fun SkillsScreen(viewModel: AquaMainViewModel) {
    val context = LocalContext.current
    val c = AquaTheme.colors
    val skills by viewModel.skills.collectAsStateWithLifecycle()

    var editingSkill by remember { mutableStateOf<AquaSkill?>(null) }

    val importSkillLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importSkillFromUri(uri, "Android File Manager")
        }
    }

    AquaBackdropBox(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Aqua Skills Engine",
                style = MaterialTheme.typography.headlineMedium,
                color = c.textPrimary
            )
            Text(
                text = "Import JSON, YAML, Markdown, or ZIP skill packages. Untrusted inputs are statically inspected and never executed as native code.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textMuted
            )
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { importSkillLauncher.launch(arrayOf("*/*")) },
                    colors = ButtonDefaults.buttonColors(containerColor = c.accentCyan, contentColor = c.canvas),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("import_skill_button")
                ) {
                    Icon(Icons.Filled.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import Skill", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { editingSkill = AquaSkill(format = "custom", sourceLabel = "user-created") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("create_skill_button")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = c.accentCyan, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Create Skill", color = c.textPrimary)
                }
            }

            Spacer(Modifier.height(14.dp))

            if (skills.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    AquaGlassCard(
                        borderColor = c.accentCyan.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.img_aqua_vault_emblem),
                                contentDescription = "Aqua Skills Engine Emblem",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .border(1.dp, c.accentCyan.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "No skills installed yet",
                                style = MaterialTheme.typography.titleMedium,
                                color = c.textPrimary
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Import a .json, .yaml, .yml, .md, or .zip skill package or create one from scratch.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = c.textMuted,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(skills, key = { it.id }) { skill ->
                        SkillItemCard(
                            skill = skill,
                            onToggle = { viewModel.toggleSkillEnabled(skill) },
                            onEdit = { editingSkill = skill },
                            onExport = {
                                val json = SkillParserAndValidator.exportToJson(skill)
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_SUBJECT, "${skill.name}.skill.json")
                                    putExtra(Intent.EXTRA_TEXT, json)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Export Skill"))
                            },
                            onDelete = { viewModel.deleteSkill(skill.id) }
                        )
                    }
                }
            }
        }
    }

    editingSkill?.let { draft ->
        SkillEditorDialog(
            initial = draft,
            onDismiss = { editingSkill = null },
            onSave = { updated ->
                viewModel.saveSkill(updated)
                editingSkill = null
            }
        )
    }
}

@Composable
private fun SkillItemCard(
    skill: AquaSkill,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val c = AquaTheme.colors
    val (statusIcon, statusColor) = when (skill.securityStatus) {
        "blocked" -> Icons.Filled.Block to c.error
        "warning" -> Icons.Filled.Warning to c.amber
        else -> Icons.Filled.CheckCircle to c.success
    }

    AquaGlassCard(
        borderColor = if (skill.enabled) c.accentCyan.copy(alpha = 0.45f) else c.borderGlow.copy(alpha = 0.8f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = skill.name, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                    Text(
                        text = "v${skill.version} • ${skill.format.uppercase()} • ${skill.sourceLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.textMuted
                    )
                }
                Switch(
                    checked = skill.enabled,
                    enabled = skill.securityStatus != "blocked",
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(checkedThumbColor = c.canvas, checkedTrackColor = c.accentCyan)
                )
            }

            if (skill.description.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(text = skill.description, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            }

            Spacer(Modifier.height(8.dp))
            Surface(
                color = statusColor.copy(alpha = 0.14f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = skill.securityReport.ifBlank { "Security status: ${skill.securityStatus}" },
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor
                    )
                }
            }

            if (skill.requiredPermissions.isNotEmpty() || skill.tools.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Tools: ${skill.tools.joinToString().ifBlank { "None" }} | Permissions: ${skill.requiredPermissions.joinToString().ifBlank { "None" }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.accentCyan
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit skill", tint = c.accentCyan)
                }
                IconButton(onClick = onExport) {
                    Icon(Icons.Filled.Share, contentDescription = "Export skill", tint = c.textMuted)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete skill", tint = c.error)
                }
            }
        }
    }
}

@Composable
private fun SkillEditorDialog(
    initial: AquaSkill,
    onDismiss: () -> Unit,
    onSave: (AquaSkill) -> Unit
) {
    val c = AquaTheme.colors
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accentCyan,
        unfocusedBorderColor = c.borderGlow,
        focusedTextColor = c.textPrimary,
        unfocusedTextColor = c.textPrimary
    )
    var name by remember { mutableStateOf(initial.name) }
    var description by remember { mutableStateOf(initial.description) }
    var version by remember { mutableStateOf(initial.version) }
    var instructions by remember { mutableStateOf(initial.instructions) }
    var permissionsText by remember { mutableStateOf(initial.requiredPermissions.joinToString(", ")) }
    var toolsText by remember { mutableStateOf(initial.tools.joinToString(", ")) }
    var configJson by remember { mutableStateOf(initial.configJson) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.deepSurface,
        title = {
            Text(
                text = if (initial.id.isBlank()) "Create Skill" else "Edit Skill",
                color = c.textPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Skill Name") }, singleLine = true, colors = fieldColors)
                OutlinedTextField(value = version, onValueChange = { version = it }, label = { Text("Version") }, singleLine = true, colors = fieldColors)
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") }, colors = fieldColors)
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = { Text("Declarative Instructions") },
                    minLines = 3,
                    colors = fieldColors
                )
                OutlinedTextField(
                    value = permissionsText,
                    onValueChange = { permissionsText = it },
                    label = { Text("Required Permissions (comma-separated)") },
                    colors = fieldColors
                )
                OutlinedTextField(
                    value = toolsText,
                    onValueChange = { toolsText = it },
                    label = { Text("Tools (comma-separated)") },
                    colors = fieldColors
                )
                OutlinedTextField(
                    value = configJson,
                    onValueChange = { configJson = it },
                    label = { Text("Config JSON") },
                    colors = fieldColors
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val perms = permissionsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val tools = toolsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    onSave(
                        initial.copy(
                            name = name.ifBlank { "Custom Skill" },
                            description = description,
                            version = version.ifBlank { "1.0.0" },
                            instructions = instructions.ifBlank { "Assist user according to configuration." },
                            requiredPermissions = perms,
                            tools = tools,
                            configJson = configJson.ifBlank { "{}" }
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = c.accentCyan, contentColor = c.canvas)
            ) {
                Text("Save & Inspect", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = c.textMuted) }
        }
    )
}
