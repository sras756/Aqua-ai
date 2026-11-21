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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.example.core.agent.RiskTier
import com.example.core.connectors.GoogleServiceId
import com.example.core.security.AquaSecurityGuardian
import com.example.core.security.PermissionDomain
import com.example.core.workspace.WorkspaceMode
import com.example.ui.AquaMainViewModel
import com.example.ui.components.AquaAdaptiveContainer
import com.example.ui.components.AquaBackdropBox
import com.example.ui.components.AquaGlassCard
import com.example.ui.components.AquaStatusPill
import com.example.ui.theme.AquaTheme

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

private val ECOSYSTEM_SECTIONS = listOf("Security", "Brain & Docs", "Automation", "Connectors", "Dev Mode")

@Composable
fun AquaEcosystemHubScreen(viewModel: AquaMainViewModel) {
    val c = AquaTheme.colors
    val context = LocalContext.current
    val activity = remember(context) { context.findHostActivity() }

    val policies by viewModel.permissionPolicies.collectAsStateWithLifecycle()
    val activeTaskId by viewModel.activeAgentTaskId.collectAsStateWithLifecycle()
    val pausedTasks by viewModel.pausedAgentTaskIds.collectAsStateWithLifecycle()
    val agentTasks by viewModel.agentTasks.collectAsStateWithLifecycle()
    val auditLogs by viewModel.auditLogs.collectAsStateWithLifecycle()
    val totalTokens by viewModel.totalAiTokensUsed.collectAsStateWithLifecycle()
    val workspaceMode by viewModel.workspaceMode.collectAsStateWithLifecycle()
    val workspaceDocs by viewModel.workspaceDocuments.collectAsStateWithLifecycle()
    val citations by viewModel.knowledgeCitations.collectAsStateWithLifecycle()
    val workflows by viewModel.automationWorkflows.collectAsStateWithLifecycle()
    val connectorStatuses by viewModel.connectorStatuses.collectAsStateWithLifecycle()
    val pendingConnectorAuth by viewModel.connectorAuthPending.collectAsStateWithLifecycle()
    val githubRepos by viewModel.githubRepos.collectAsStateWithLifecycle()
    val githubConnected by viewModel.githubConnectedState.collectAsStateWithLifecycle()

    var sectionIndex by rememberSaveable { mutableIntStateOf(0) }
    var activeAuthService by remember { mutableStateOf<GoogleServiceId?>(null) }

    val connectorConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val svc = activeAuthService
        activeAuthService = null
        if (svc != null && result.resultCode == Activity.RESULT_OK) {
            viewModel.handleConnectorConsentResult(svc, result.data)
        }
    }

    LaunchedEffect(pendingConnectorAuth) {
        val pair = pendingConnectorAuth ?: return@LaunchedEffect
        activeAuthService = pair.first
        viewModel.consumeConnectorAuthPending()
        connectorConsentLauncher.launch(IntentSenderRequest.Builder(pair.second.intentSender).build())
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accentCyan,
        unfocusedBorderColor = c.borderGlow,
        focusedTextColor = c.textPrimary,
        unfocusedTextColor = c.textPrimary
    )

    AquaBackdropBox(modifier = Modifier.fillMaxSize()) {
        AquaAdaptiveContainer(modifier = Modifier.fillMaxSize().statusBarsPadding()) { spec ->
            val maxW = if (spec.maxContentWidth != androidx.compose.ui.unit.Dp.Unspecified) {
                Modifier.fillMaxWidth().widthIn(max = spec.maxContentWidth)
            } else {
                Modifier.fillMaxWidth()
            }

            Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(modifier = maxW.padding(horizontal = spec.horizontalPadding, vertical = 10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("CONTROL, SECURITY & BRAIN", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
                            Text("6 Permission Domains • $totalTokens AI tokens metered", style = MaterialTheme.typography.labelSmall, color = c.accentCyan)
                        }
                        AquaStatusPill(label = if (activeTaskId != null) "AGENT ACTIVE" else "READY", accent = if (activeTaskId != null) c.accentTeal else c.success)
                    }

                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(ECOSYSTEM_SECTIONS.indices.toList()) { idx ->
                            FilterChip(
                                selected = sectionIndex == idx,
                                onClick = { sectionIndex = idx },
                                label = { Text(ECOSYSTEM_SECTIONS[idx]) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = c.accentCyan.copy(alpha = 0.2f),
                                    selectedLabelColor = c.accentCyan
                                ),
                                modifier = Modifier.testTag("ecosystem_tab_$idx")
                            )
                        }
                    }
                }

                LazyColumn(
                    modifier = maxW.weight(1f),
                    contentPadding = PaddingValues(horizontal = spec.horizontalPadding, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (sectionIndex) {
                        0 -> {
                            if (activeTaskId != null) {
                                val taskId = activeTaskId!!
                                val isPaused = pausedTasks.contains(taskId)
                                item {
                                    AquaGlassCard(borderColor = c.accentCyan, modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            modifier = Modifier.padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("Active Agent Task", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                                                Text(if (isPaused) "Paused by user" else "Executing task $taskId", style = MaterialTheme.typography.bodySmall, color = c.accentTeal)
                                            }
                                            OutlinedButton(
                                                onClick = { if (isPaused) viewModel.resumeActiveAgentTask(taskId) else viewModel.pauseActiveAgentTask(taskId) },
                                                modifier = Modifier.testTag("agent_pause_resume_btn")
                                            ) {
                                                Icon(if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text(if (isPaused) "Resume" else "Pause")
                                            }
                                            OutlinedButton(
                                                onClick = { viewModel.cancelActiveAgentTask(taskId) },
                                                modifier = Modifier.testTag("agent_cancel_btn")
                                            ) {
                                                Icon(Icons.Filled.Cancel, contentDescription = null, tint = c.error, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("Cancel", color = c.error)
                                            }
                                        }
                                    }
                                }
                            }

                            item {
                                AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.Security, contentDescription = null, tint = c.accentCyan)
                                            Spacer(Modifier.width(8.dp))
                                            Text("Centralized Permission Matrix", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                                        }
                                        PermissionDomain.entries.forEach { domain ->
                                            val allowed = policies[domain] ?: true
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(domain.title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                                                    Text(domain.description, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                                                }
                                                Switch(
                                                    checked = allowed,
                                                    onCheckedChange = { viewModel.setPermissionDomainAllowed(domain, it) },
                                                    modifier = Modifier.testTag("policy_switch_${domain.name}")
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            item {
                                var urlCheckInput by remember { mutableStateOf("") }
                                AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Security Guardian & Screen Assist", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                                        OutlinedTextField(
                                            value = urlCheckInput,
                                            onValueChange = { urlCheckInput = it },
                                            label = { Text("Inspect URL or Wallet Address") },
                                            singleLine = true,
                                            colors = fieldColors,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        if (urlCheckInput.isNotBlank()) {
                                            val alert = if (urlCheckInput.startsWith("0x")) {
                                                AquaSecurityGuardian.inspectWalletTransfer("", urlCheckInput)
                                            } else {
                                                AquaSecurityGuardian.inspectUrl(urlCheckInput)
                                            }
                                            Text(
                                                text = "${alert.title}: ${alert.detail}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (alert.isHighRisk) c.amber else c.success
                                            )
                                        }
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(onClick = { viewModel.captureOnDemandScreenSummary() }, modifier = Modifier.weight(1f)) {
                                                Text("Analyze Active Screen")
                                            }
                                            OutlinedButton(onClick = { viewModel.deviceController.openAccessibilitySettings() }, modifier = Modifier.weight(1f)) {
                                                Text("Accessibility Settings")
                                            }
                                        }
                                    }
                                }
                            }

                            item {
                                Text("RECENT TASK STATE MACHINE & AUDIT LOGS (${auditLogs.size})", style = MaterialTheme.typography.labelMedium, color = c.accentCyan)
                            }
                            items(agentTasks.take(5), key = { it.taskId }) { t ->
                                AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(t.title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            AquaStatusPill(label = t.state, accent = c.accentCyan)
                                        }
                                        Text("Tier ${t.riskTier} • Steps ${t.currentStepIndex}/${t.totalSteps}", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                                    }
                                }
                            }
                            items(auditLogs.take(10), key = { it.id }) { log ->
                                AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text("${log.action} • ${log.outcome} (${log.domain})", style = MaterialTheme.typography.labelMedium, color = c.accentTeal)
                                        Text(log.redactedDetail, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }

                        1 -> {
                            item {
                                var brainQuery by remember { mutableStateOf("") }
                                AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.Psychology, contentDescription = null, tint = c.accentCyan)
                                            Spacer(Modifier.width(8.dp))
                                            Text("Aqua Knowledge Brain (RAG Citations)", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedTextField(
                                                value = brainQuery,
                                                onValueChange = { brainQuery = it },
                                                placeholder = { Text("Search memories, workspace & Drive...") },
                                                singleLine = true,
                                                colors = fieldColors,
                                                modifier = Modifier.weight(1f).testTag("knowledge_search_input")
                                            )
                                            Button(onClick = { viewModel.runKnowledgeSearch(brainQuery) }) {
                                                Icon(Icons.Filled.Search, contentDescription = "Search Knowledge")
                                            }
                                        }
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            FilterChip(
                                                selected = workspaceMode == WorkspaceMode.USER_VIEW,
                                                onClick = { viewModel.setWorkspaceMode(WorkspaceMode.USER_VIEW) },
                                                label = { Text("USER VIEW (Read-Only)") }
                                            )
                                            FilterChip(
                                                selected = workspaceMode == WorkspaceMode.AI_WORKSPACE,
                                                onClick = { viewModel.setWorkspaceMode(WorkspaceMode.AI_WORKSPACE) },
                                                label = { Text("AI WORKSPACE (R/W)") }
                                            )
                                        }
                                    }
                                }
                            }

                            items(citations, key = { "${it.sourceType}_${it.id}" }) { cit ->
                                AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("${cit.title} [${cit.originLabel}]", style = MaterialTheme.typography.titleSmall, color = c.accentCyan)
                                        Text(cit.snippet, style = MaterialTheme.typography.bodySmall, color = c.textPrimary)
                                    }
                                }
                            }

                            item {
                                Text("AI WORKSPACE ARTIFACTS (${workspaceDocs.size})", style = MaterialTheme.typography.labelMedium, color = c.accentTeal)
                            }
                            items(workspaceDocs, key = { it.id }) { doc ->
                                AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("${doc.title} (${doc.folderType})", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                                        Text(doc.content.take(240), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                                    }
                                }
                            }
                        }

                        2 -> {
                            item {
                                var wfName by remember { mutableStateOf("") }
                                var wfStep by remember { mutableStateOf("") }
                                var wfInterval by remember { mutableStateOf("60") }
                                AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Automation Studio (WorkManager)", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                                        OutlinedTextField(value = wfName, onValueChange = { wfName = it }, label = { Text("Workflow Name") }, singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth())
                                        OutlinedTextField(value = wfStep, onValueChange = { wfStep = it }, label = { Text("Action Instruction / Step") }, colors = fieldColors, modifier = Modifier.fillMaxWidth())
                                        OutlinedTextField(value = wfInterval, onValueChange = { wfInterval = it }, label = { Text("Interval (minutes, >=15 for periodic)") }, singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth())
                                        Button(
                                            onClick = {
                                                val mins = wfInterval.toLongOrNull() ?: 60L
                                                viewModel.createAutomationWorkflow(
                                                    name = wfName,
                                                    description = "User workflow",
                                                    triggerType = if (mins >= 15L) "SCHEDULED" else "MANUAL",
                                                    intervalMinutes = mins,
                                                    steps = listOf(wfStep),
                                                    riskTier = RiskTier.TIER_1_WORKSPACE
                                                )
                                                wfName = ""
                                                wfStep = ""
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = c.accentCyan, contentColor = c.canvas),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("Save & Schedule Workflow", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                            items(workflows, key = { it.id }) { wf ->
                                AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(wf.name, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                                            Text("${wf.triggerType} (${wf.intervalMinutes}m) • ${wf.lastRunStatus.ifBlank { "Scheduled" }}", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                                        }
                                        TextButton(onClick = { viewModel.deleteAutomationWorkflow(wf.id) }) {
                                            Text("Delete", color = c.error)
                                        }
                                    }
                                }
                            }
                        }

                        3 -> {
                            items(GoogleServiceId.entries, key = { it.name }) { svc ->
                                val st = connectorStatuses[svc]
                                AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                                Icon(Icons.Filled.Cloud, contentDescription = null, tint = c.accentCyan)
                                                Spacer(Modifier.width(8.dp))
                                                Column {
                                                    Text(svc.title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                                                    Text(st?.lastSyncSummary ?: "Disabled", style = MaterialTheme.typography.labelSmall, color = c.accentTeal)
                                                }
                                            }
                                            Switch(
                                                checked = st?.enabledInPrefs == true,
                                                onCheckedChange = { viewModel.setGoogleConnectorEnabled(svc, it) }
                                            )
                                        }
                                        Text(svc.description, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(
                                                onClick = { viewModel.authorizeGoogleConnector(svc) },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text(if (st?.scopeGranted == true) "Refresh" else "Authorize OAuth")
                                            }
                                            OutlinedButton(
                                                onClick = { viewModel.revokeGoogleConnector(svc) },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("Revoke Scope", color = c.error)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        else -> {
                            item {
                                var patInput by remember { mutableStateOf("") }
                                AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.Build, contentDescription = null, tint = c.accentCyan)
                                            Spacer(Modifier.width(8.dp))
                                            Text("Developer Mode (GitHub API)", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                                        }
                                        Text(
                                            if (githubConnected) "GitHub PAT stored in Android Keystore." else "Enter a GitHub PAT to inspect real repositories (zero mock repos).",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (githubConnected) c.success else c.textMuted
                                        )
                                        OutlinedTextField(
                                            value = patInput,
                                            onValueChange = { patInput = it },
                                            label = { Text("GitHub Personal Access Token") },
                                            visualTransformation = PasswordVisualTransformation(),
                                            singleLine = true,
                                            colors = fieldColors,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(onClick = { viewModel.saveGithubPat(patInput); patInput = "" }, modifier = Modifier.weight(1f)) {
                                                Text("Save Key")
                                            }
                                            OutlinedButton(onClick = { viewModel.loadGithubRepositories() }, modifier = Modifier.weight(1f)) {
                                                Text("Fetch Repos")
                                            }
                                            if (githubConnected) {
                                                OutlinedButton(onClick = { viewModel.clearGithubPat() }) {
                                                    Text("Clear", color = c.error)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            items(githubRepos, key = { it.fullName }) { repo ->
                                AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(repo.fullName, style = MaterialTheme.typography.titleSmall, color = c.accentCyan)
                                        if (repo.description.isNotBlank()) {
                                            Text(repo.description, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                                        }
                                        Text("Branch: ${repo.defaultBranch} • ${if (repo.isPrivate) "Private" else "Public"}", style = MaterialTheme.typography.labelSmall, color = c.accentTeal)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
