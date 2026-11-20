package com.example.core.agent

import com.example.core.database.AgentTaskDao
import com.example.core.database.AgentTaskEntity
import com.example.core.database.AuditLogDao
import com.example.core.database.AuditLogEntity
import com.example.core.security.PermissionDomain
import com.example.core.security.PermissionPolicyStore
import com.example.core.security.SecurityAuditRedactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class TaskState {
    CREATED,
    PLANNING,
    AWAITING_APPROVAL,
    EXECUTING,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED,
    ROLLED_BACK
}

enum class RiskTier(val level: Int, val label: String) {
    TIER_0_AUTO(0, "Tier 0 • Autonomous Read"),
    TIER_1_WORKSPACE(1, "Tier 1 • Reversible Local/Workspace"),
    TIER_2_CONFIRM(2, "Tier 2 • Explicit Confirmation"),
    TIER_3_BIOMETRIC_CONFIRM(3, "Tier 3 • Biometric / Critical Confirmation")
}

data class ToolRiskClassification(
    val toolName: String,
    val riskTier: RiskTier,
    val permissionDomain: PermissionDomain,
    val actionTitle: String
)

object ToolRiskClassifier {
    fun classify(toolName: String): ToolRiskClassification {
        return when (toolName) {
            // Tier 0: Read-only telemetry & searches
            "check_device_status",
            "get_device_storage_and_memory",
            "read_notifications",
            "read_clipboard",
            "get_ai_freedom_config" -> ToolRiskClassification(
                toolName = toolName,
                riskTier = RiskTier.TIER_0_AUTO,
                permissionDomain = PermissionDomain.READ_ACCESS,
                actionTitle = toolName.replace('_', ' ')
            )

            "web_search",
            "web_fetch_page",
            "browser_read_current_page",
            "browser_extract_links",
            "fetch_crypto_price" -> ToolRiskClassification(
                toolName = toolName,
                riskTier = RiskTier.TIER_0_AUTO,
                permissionDomain = PermissionDomain.READ_ACCESS,
                actionTitle = toolName.replace('_', ' ')
            )

            "drive_list_backups",
            "drive_get_storage_quota" -> ToolRiskClassification(
                toolName = toolName,
                riskTier = RiskTier.TIER_0_AUTO,
                permissionDomain = PermissionDomain.CLOUD_STORAGE,
                actionTitle = toolName.replace('_', ' ')
            )

            "wallet_check_balance",
            "wallet_get_portfolio",
            "wallet_get_receive_addresses",
            "wallet_get_transactions",
            "wallet_check_tx_status",
            "wallet_analyze_portfolio",
            "wallet_research_network",
            "wallet_get_evm_telemetry",
            "wallet_resolve_ens",
            "wallet_check_allowance",
            "wallet_get_market_chart",
            "wallet_get_token_info",
            "wallet_estimate_fees",
            "wallet_analyze_address_risk",
            "wallet_lookup_contact",
            "wallet_verify_message",
            "wallet_parse_payment_uri" -> ToolRiskClassification(
                toolName = toolName,
                riskTier = RiskTier.TIER_0_AUTO,
                permissionDomain = PermissionDomain.FINANCIAL_AUTHORIZATION,
                actionTitle = toolName.replace('_', ' ')
            )

            // Tier 1: Reversible local / workspace mutations
            "toggle_flashlight",
            "set_media_volume",
            "set_alarm",
            "create_reminder",
            "open_android_settings",
            "media_playback_control" -> ToolRiskClassification(
                toolName = toolName,
                riskTier = RiskTier.TIER_1_WORKSPACE,
                permissionDomain = PermissionDomain.DEVICE_CONTROL,
                actionTitle = toolName.replace('_', ' ')
            )

            "copy_to_clipboard",
            "save_memory_note",
            "browser_open_url",
            "speak_with_elevenlabs" -> ToolRiskClassification(
                toolName = toolName,
                riskTier = RiskTier.TIER_1_WORKSPACE,
                permissionDomain = PermissionDomain.WRITE_ACCESS,
                actionTitle = toolName.replace('_', ' ')
            )

            // Tier 2: Mutating external/system/cloud/wallet setup actions
            "launch_app",
            "initiate_phone_call",
            "prepare_sms_message",
            "share_text_externally",
            "accessibility_perform_action" -> ToolRiskClassification(
                toolName = toolName,
                riskTier = RiskTier.TIER_2_CONFIRM,
                permissionDomain = PermissionDomain.DEVICE_CONTROL,
                actionTitle = toolName.replace('_', ' ')
            )

            "web_http_request",
            "browser_fill_form" -> ToolRiskClassification(
                toolName = toolName,
                riskTier = RiskTier.TIER_2_CONFIRM,
                permissionDomain = PermissionDomain.WRITE_ACCESS,
                actionTitle = toolName.replace('_', ' ')
            )

            "backup_history_to_drive",
            "drive_sync_now" -> ToolRiskClassification(
                toolName = toolName,
                riskTier = RiskTier.TIER_2_CONFIRM,
                permissionDomain = PermissionDomain.CLOUD_STORAGE,
                actionTitle = toolName.replace('_', ' ')
            )

            "wallet_switch_network",
            "wallet_switch_account",
            "wallet_switch_wallet",
            "wallet_save_contact",
            "wallet_delete_contact",
            "wallet_create_wallet",
            "wallet_add_account",
            "wallet_add_watch_wallet",
            "wallet_add_custom_token",
            "wallet_add_custom_network",
            "wallet_update_preferences" -> ToolRiskClassification(
                toolName = toolName,
                riskTier = RiskTier.TIER_2_CONFIRM,
                permissionDomain = PermissionDomain.FINANCIAL_AUTHORIZATION,
                actionTitle = toolName.replace('_', ' ')
            )

            // Tier 3: Critical cryptographic signing & irreversible asset authorizations
            "wallet_prepare_revoke_allowance",
            "wallet_sign_message",
            "wallet_prepare_transfer" -> ToolRiskClassification(
                toolName = toolName,
                riskTier = RiskTier.TIER_3_BIOMETRIC_CONFIRM,
                permissionDomain = PermissionDomain.FINANCIAL_AUTHORIZATION,
                actionTitle = toolName.replace('_', ' ')
            )

            // Safe conservative default for unknown/extension tools
            else -> ToolRiskClassification(
                toolName = toolName,
                riskTier = RiskTier.TIER_2_CONFIRM,
                permissionDomain = PermissionDomain.WRITE_ACCESS,
                actionTitle = toolName.replace('_', ' ')
            )
        }
    }
}

class AgentOrchestrator(
    private val agentTaskDao: AgentTaskDao,
    private val auditLogDao: AuditLogDao,
    private val permissionPolicyStore: PermissionPolicyStore,
    private val userIdProvider: () -> String
) {
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val pausedTaskIds = ConcurrentHashMap.newKeySet<String>()

    private val _activeTaskId = MutableStateFlow<String?>(null)
    val activeTaskId: StateFlow<String?> = _activeTaskId.asStateFlow()

    private val _pausedTasks = MutableStateFlow<Set<String>>(emptySet())
    val pausedTasks: StateFlow<Set<String>> = _pausedTasks.asStateFlow()

    suspend fun createTask(
        title: String,
        source: String = "chat",
        riskTier: RiskTier = RiskTier.TIER_0_AUTO,
        totalSteps: Int = 1
    ): String {
        val taskId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val uid = userIdProvider().ifBlank { "default_user" }
        val cleanTitle = SecurityAuditRedactor.redact(title).take(120)
        agentTaskDao.upsertTask(
            AgentTaskEntity(
                taskId = taskId,
                userId = uid,
                title = cleanTitle,
                source = source,
                state = TaskState.CREATED.name,
                riskTier = riskTier.level,
                totalSteps = totalSteps,
                createdAtMs = now,
                updatedAtMs = now
            )
        )
        _activeTaskId.value = taskId
        appendAudit(
            taskId = taskId,
            domain = PermissionDomain.READ_ACCESS,
            action = "task_created",
            riskTier = riskTier,
            outcome = "CREATED",
            detail = "Source=$source, Title=$cleanTitle"
        )
        return taskId
    }

    fun registerJob(taskId: String, job: Job) {
        activeJobs[taskId] = job
        job.invokeOnCompletion {
            activeJobs.remove(taskId)
            pausedTaskIds.remove(taskId)
            syncPausedFlow()
            if (_activeTaskId.value == taskId) {
                _activeTaskId.value = null
            }
        }
    }

    suspend fun transitionTask(
        taskId: String,
        nextState: TaskState,
        riskTier: RiskTier? = null,
        stepIndex: Int? = null,
        totalSteps: Int? = null,
        planJson: String? = null,
        summary: String? = null,
        error: String? = null
    ) {
        val existing = agentTaskDao.getTask(taskId) ?: return
        val effectiveTier = maxOf(existing.riskTier, riskTier?.level ?: 0)
        val updated = existing.copy(
            state = nextState.name,
            riskTier = effectiveTier,
            currentStepIndex = stepIndex ?: existing.currentStepIndex,
            totalSteps = totalSteps ?: existing.totalSteps,
            planJson = planJson ?: existing.planJson,
            resultSummary = summary?.let { SecurityAuditRedactor.redact(it).take(500) } ?: existing.resultSummary,
            errorMessage = error?.let { SecurityAuditRedactor.redact(it).take(300) } ?: existing.errorMessage,
            updatedAtMs = System.currentTimeMillis()
        )
        agentTaskDao.upsertTask(updated)
        if (nextState in setOf(TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED, TaskState.ROLLED_BACK)) {
            if (_activeTaskId.value == taskId) {
                _activeTaskId.value = null
            }
            pausedTaskIds.remove(taskId)
            syncPausedFlow()
            appendAudit(
                taskId = taskId,
                domain = PermissionDomain.BACKGROUND_EXECUTION,
                action = "task_${nextState.name.lowercase()}",
                riskTier = RiskTier.entries.firstOrNull { it.level == effectiveTier } ?: RiskTier.TIER_0_AUTO,
                outcome = nextState.name,
                detail = error ?: summary ?: existing.title
            )
        }
    }

    fun pauseTask(taskId: String) {
        pausedTaskIds.add(taskId)
        syncPausedFlow()
    }

    fun resumeTask(taskId: String) {
        pausedTaskIds.remove(taskId)
        syncPausedFlow()
    }

    suspend fun awaitIfPaused(taskId: String?) {
        if (taskId.isNullOrBlank()) return
        while (pausedTaskIds.contains(taskId)) {
            delay(150L)
        }
    }

    suspend fun cancelTask(taskId: String, reason: String = "Cancelled by user") {
        pausedTaskIds.remove(taskId)
        syncPausedFlow()
        activeJobs.remove(taskId)?.cancel(CancellationException(reason))
        transitionTask(
            taskId = taskId,
            nextState = TaskState.CANCELLED,
            summary = reason,
            error = reason
        )
    }

    /**
     * Checks centralized 6-domain permission policy prior to any tool execution and logs denials.
     */
    suspend fun guardToolExecution(
        taskId: String?,
        toolName: String,
        detailSummary: String = ""
    ): Result<ToolRiskClassification> {
        awaitIfPaused(taskId)
        val classification = ToolRiskClassifier.classify(toolName)
        if (!permissionPolicyStore.isAllowed(classification.permissionDomain)) {
            val denialReason = "Permission domain '${classification.permissionDomain.title}' is disabled in Security Center (blocked '$toolName')."
            appendAudit(
                taskId = taskId.orEmpty(),
                domain = classification.permissionDomain,
                action = toolName,
                riskTier = classification.riskTier,
                outcome = "DENIED_POLICY",
                detail = if (detailSummary.isBlank()) denialReason else "$denialReason | $detailSummary"
            )
            return Result.failure(SecurityException(denialReason))
        }
        if (!taskId.isNullOrBlank()) {
            val nextState = if (classification.riskTier.level >= 2) {
                TaskState.AWAITING_APPROVAL
            } else {
                TaskState.EXECUTING
            }
            transitionTask(
                taskId = taskId,
                nextState = nextState,
                riskTier = classification.riskTier
            )
        }
        return Result.success(classification)
    }

    suspend fun recordToolOutcome(
        taskId: String?,
        toolName: String,
        outcome: String,
        detail: String
    ) {
        val classification = ToolRiskClassifier.classify(toolName)
        appendAudit(
            taskId = taskId.orEmpty(),
            domain = classification.permissionDomain,
            action = toolName,
            riskTier = classification.riskTier,
            outcome = outcome,
            detail = detail
        )
    }

    suspend fun recordSecurityEvent(
        domain: PermissionDomain,
        action: String,
        riskTier: RiskTier,
        outcome: String,
        detail: String,
        taskId: String = ""
    ) {
        appendAudit(
            taskId = taskId,
            domain = domain,
            action = action,
            riskTier = riskTier,
            outcome = outcome,
            detail = detail
        )
    }

    private suspend fun appendAudit(
        taskId: String,
        domain: PermissionDomain,
        action: String,
        riskTier: RiskTier,
        outcome: String,
        detail: String
    ) {
        val uid = userIdProvider().ifBlank { "default_user" }
        val cleanDetail = SecurityAuditRedactor.redact(detail).take(800)
        runCatching {
            auditLogDao.appendLog(
                AuditLogEntity(
                    userId = uid,
                    taskId = taskId,
                    domain = domain.id,
                    action = action,
                    riskTier = riskTier.level,
                    outcome = outcome,
                    redactedDetail = cleanDetail
                )
            )
        }
    }

    private fun syncPausedFlow() {
        _pausedTasks.value = pausedTaskIds.toSet()
    }
}
