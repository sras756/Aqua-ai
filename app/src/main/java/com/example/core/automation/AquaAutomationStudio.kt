package com.example.core.automation

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.core.agent.RiskTier
import com.example.core.database.AutomationWorkflowDao
import com.example.core.database.AutomationWorkflowEntity
import com.example.core.drive.AquaDriveFolders
import com.example.core.drive.AquaDriveSyncCoordinator
import com.example.core.security.PermissionDomain
import com.example.core.security.PermissionPolicyStore
import com.example.data.local.AquaCacheDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Aqua Automation Studio (`feature:automation`) managing scheduled and conditional workflows
 * backed by Room SSOT, WorkManager execution, Risk Tier checks, and `Aqua AI/Automation records/` sync.
 */
class AquaAutomationStudio(
    private val context: Context,
    private val workflowDao: AutomationWorkflowDao,
    private val permissionPolicyStore: PermissionPolicyStore,
    private val driveSyncCoordinator: AquaDriveSyncCoordinator? = null,
    private val userIdProvider: () -> String = { "default_user" }
) {
    fun observeWorkflows(userId: String = userIdProvider()): Flow<List<AutomationWorkflowEntity>> =
        workflowDao.observeWorkflows(userId.ifBlank { "default_user" })

    suspend fun saveWorkflow(
        id: String = "",
        name: String,
        description: String = "",
        triggerType: String = "MANUAL",
        intervalMinutes: Long = 0L,
        steps: List<String>,
        riskTier: RiskTier = RiskTier.TIER_1_WORKSPACE,
        enabled: Boolean = true,
        scheduleWork: Boolean = true,
        userId: String = userIdProvider()
    ): Result<AutomationWorkflowEntity> = withContext(Dispatchers.IO) {
        if (!permissionPolicyStore.isAllowed(PermissionDomain.BACKGROUND_EXECUTION) &&
            triggerType != "MANUAL"
        ) {
            return@withContext Result.failure(
                SecurityException("Background Execution permission domain is disabled in Security Center.")
            )
        }
        val uid = userId.ifBlank { "default_user" }
        val cleanId = id.ifBlank { "wf_${UUID.randomUUID().toString().replace("-", "").take(14)}" }
        val now = System.currentTimeMillis()
        val stepsArr = JSONArray().apply { steps.forEach { put(it.trim()) } }.toString()
        val entity = AutomationWorkflowEntity(
            id = cleanId,
            userId = uid,
            name = name.trim().ifBlank { "Untitled Workflow" },
            description = description.trim(),
            triggerType = triggerType.uppercase(),
            intervalMinutes = intervalMinutes.coerceAtLeast(0L),
            stepsJson = stepsArr,
            enabled = enabled,
            riskTier = riskTier.level,
            createdAtMs = now,
            updatedAtMs = now
        )
        workflowDao.upsertWorkflow(entity)

        driveSyncCoordinator?.enqueueMutation(
            userId = uid,
            entityType = "automation_workflow",
            entityId = cleanId,
            driveFolder = AquaDriveFolders.AUTOMATION_RECORDS,
            fileName = "workflow_$cleanId.json",
            operation = "UPSERT",
            serializedPayloadJson = JSONObject()
                .put("id", entity.id)
                .put("name", entity.name)
                .put("triggerType", entity.triggerType)
                .put("intervalMinutes", entity.intervalMinutes)
                .put("riskTier", entity.riskTier)
                .put("steps", stepsArr)
                .toString(),
            scheduleBackgroundWork = scheduleWork
        )

        if (scheduleWork && entity.enabled) {
            scheduleWorkflowInWorkManager(entity)
        }
        Result.success(entity)
    }

    suspend fun recordExecutionResult(
        workflowId: String,
        status: String
    ) = withContext(Dispatchers.IO) {
        val wf = workflowDao.getWorkflow(workflowId) ?: return@withContext
        workflowDao.upsertWorkflow(
            wf.copy(
                lastRunAtMs = System.currentTimeMillis(),
                lastRunStatus = status.take(240),
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteWorkflow(workflowId: String, userId: String = userIdProvider()) = withContext(Dispatchers.IO) {
        val uid = userId.ifBlank { "default_user" }
        workflowDao.deleteWorkflow(workflowId)
        runCatching { WorkManager.getInstance(context.applicationContext).cancelUniqueWork("aqua_wf_$workflowId") }
        driveSyncCoordinator?.enqueueMutation(
            userId = uid,
            entityType = "automation_workflow",
            entityId = workflowId,
            driveFolder = AquaDriveFolders.AUTOMATION_RECORDS,
            fileName = "workflow_$workflowId.json",
            operation = "DELETE",
            serializedPayloadJson = JSONObject().put("id", workflowId).put("deleted", true).toString()
        )
    }

    private fun scheduleWorkflowInWorkManager(workflow: AutomationWorkflowEntity) {
        val wm = runCatching { WorkManager.getInstance(context.applicationContext) }.getOrNull() ?: return
        val data = workDataOf(
            AquaAutomationWorker.KEY_WORKFLOW_ID to workflow.id,
            AquaAutomationWorker.KEY_USER_ID to workflow.userId
        )
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        if (workflow.triggerType == "SCHEDULED" && workflow.intervalMinutes >= 15L) {
            val req = PeriodicWorkRequestBuilder<AquaAutomationWorker>(
                workflow.intervalMinutes,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setInputData(data)
                .build()
            wm.enqueueUniquePeriodicWork(
                "aqua_wf_${workflow.id}",
                ExistingPeriodicWorkPolicy.UPDATE,
                req
            )
        } else if (workflow.triggerType == "ONE_SHOT") {
            val delayMin = workflow.intervalMinutes.coerceAtLeast(1L)
            val req = OneTimeWorkRequestBuilder<AquaAutomationWorker>()
                .setInitialDelay(delayMin, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setInputData(data)
                .build()
            wm.enqueueUniqueWork(
                "aqua_wf_${workflow.id}",
                ExistingWorkPolicy.REPLACE,
                req
            )
        }
    }
}

class AquaAutomationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val workflowId = inputData.getString(KEY_WORKFLOW_ID).orEmpty()
        val userId = inputData.getString(KEY_USER_ID).orEmpty().ifBlank { "default_user" }
        val policyStore = PermissionPolicyStore(applicationContext, userId)
        if (!policyStore.isAllowed(PermissionDomain.BACKGROUND_EXECUTION)) {
            return Result.success(workDataOf(KEY_RESULT to "skipped_background_policy_disabled"))
        }

        val db = AquaCacheDatabase.getInstance(applicationContext)
        val wf = db.automationWorkflowDao().getWorkflow(workflowId)
            ?: return Result.success(workDataOf(KEY_RESULT to "workflow_not_found"))

        if (!wf.enabled) {
            return Result.success(workDataOf(KEY_RESULT to "workflow_disabled"))
        }

        val status = if (wf.riskTier >= RiskTier.TIER_2_CONFIRM.level) {
            "AWAITING_USER_APPROVAL (Risk Tier ${wf.riskTier} requires confirmation)"
        } else {
            "READY_FOR_FOREGROUND_DISPATCH"
        }
        db.automationWorkflowDao().upsertWorkflow(
            wf.copy(
                lastRunAtMs = System.currentTimeMillis(),
                lastRunStatus = status,
                updatedAtMs = System.currentTimeMillis()
            )
        )
        return Result.success(workDataOf(KEY_RESULT to status))
    }

    companion object {
        const val KEY_WORKFLOW_ID = "workflow_id"
        const val KEY_USER_ID = "user_id"
        const val KEY_RESULT = "result"
    }
}
