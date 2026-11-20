package com.example.core.ai

import com.example.core.database.AiUsageMeterDao
import com.example.core.database.AiUsageMeterEntity
import com.example.data.model.ChatMessage
import com.google.firebase.ai.type.Schema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.min

data class AquaPlanStep(
    val stepNumber: Int,
    val goal: String,
    val suggestedTool: String = "",
    val riskLevel: String = "LOW"
)

data class AquaTaskPlan(
    val objective: String,
    val language: String = "en",
    val steps: List<AquaPlanStep> = emptyList()
)

data class CompactedHistoryResult(
    val retainedMessages: List<ChatMessage>,
    val summaryBlock: String,
    val compactedCount: Int,
    val estimatedTokensSaved: Int
)

object ContextCompactor {
    const val DEFAULT_MAX_HISTORY_CHARS = 16_000
    const val RECENT_VERBATIM_COUNT = 8

    fun compact(
        history: List<ChatMessage>,
        maxHistoryChars: Int = DEFAULT_MAX_HISTORY_CHARS,
        keepRecentCount: Int = RECENT_VERBATIM_COUNT
    ): CompactedHistoryResult {
        val valid = history.filter { it.role == "user" || it.role == "model" }
        if (valid.isEmpty()) {
            return CompactedHistoryResult(emptyList(), "", 0, 0)
        }

        val totalChars = valid.sumOf { it.content.length }
        if (totalChars <= maxHistoryChars && valid.size <= 16) {
            return CompactedHistoryResult(
                retainedMessages = valid.takeLast(12),
                summaryBlock = "",
                compactedCount = 0,
                estimatedTokensSaved = 0
            )
        }

        val verbatimCount = keepRecentCount.coerceIn(2, valid.size)
        val splitIndex = (valid.size - verbatimCount).coerceAtLeast(0)
        val older = valid.subList(0, splitIndex)
        val recent = valid.subList(splitIndex, valid.size)

        if (older.isEmpty()) {
            return CompactedHistoryResult(recent, "", 0, 0)
        }

        val summaryLines = mutableListOf<String>()
        summaryLines += "[Compacted Prior Context — ${older.size} earlier turn(s)]"
        var summaryBudget = 2_400
        for (msg in older.takeLast(18)) {
            if (summaryBudget <= 0) break
            val roleLabel = if (msg.role == "user") "User" else "Aqua"
            val singleLine = msg.content.replace(Regex("\\s+"), " ").trim()
            val snippet = if (singleLine.length > 140) singleLine.take(137) + "..." else singleLine
            val toolsHint = if (msg.toolActionsJson.isNotBlank() && msg.toolActionsJson != "[]") {
                " [Used tools]"
            } else {
                ""
            }
            val line = "- $roleLabel: $snippet$toolsHint"
            summaryLines += line
            summaryBudget -= line.length
        }

        val summaryText = summaryLines.joinToString("\n")
        val olderChars = older.sumOf { it.content.length }
        val savedChars = (olderChars - summaryText.length).coerceAtLeast(0)
        return CompactedHistoryResult(
            retainedMessages = recent,
            summaryBlock = summaryText,
            compactedCount = older.size,
            estimatedTokensSaved = ceil(savedChars / 4.0).toInt()
        )
    }
}

object TaskPlanner {
    fun buildTaskPlanSchema(): Schema {
        return Schema.obj(
            mapOf(
                "objective" to Schema.string("Clear 1-sentence objective summary"),
                "language" to Schema.string("Detected ISO language code: en or ur"),
                "steps" to Schema.array(
                    Schema.obj(
                        mapOf(
                            "stepNumber" to Schema.integer("1-based sequence number"),
                            "goal" to Schema.string("Concrete actionable step"),
                            "suggestedTool" to Schema.string("Aqua tool name or empty string"),
                            "riskLevel" to Schema.string("LOW, MEDIUM, or HIGH")
                        )
                    )
                )
            )
        )
    }

    fun parsePlanJson(rawJson: String): AquaTaskPlan? {
        val trimmed = rawJson.trim()
        if (trimmed.isBlank()) return null
        val normalized = trimmed
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        return runCatching {
            val obj = JSONObject(normalized)
            val objective = obj.optString("objective", "").trim()
            if (objective.isBlank()) return null
            val language = obj.optString("language", "en").ifBlank { "en" }
            val arr = obj.optJSONArray("steps") ?: JSONArray()
            val steps = buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val goal = item.optString("goal", "").trim()
                    if (goal.isNotEmpty()) {
                        add(
                            AquaPlanStep(
                                stepNumber = item.optInt("stepNumber", i + 1),
                                goal = goal,
                                suggestedTool = item.optString("suggestedTool", "").trim(),
                                riskLevel = item.optString("riskLevel", "LOW").uppercase()
                            )
                        )
                    }
                }
            }
            AquaTaskPlan(objective = objective, language = language, steps = steps)
        }.getOrNull()
    }
}

class AiUsageMeterTracker(
    private val aiUsageMeterDao: AiUsageMeterDao?,
    private val userIdProvider: () -> String = { "" }
) {
    suspend fun record(
        sessionId: String,
        modelId: String,
        promptTokens: Int,
        outputTokens: Int,
        totalTokens: Int,
        latencyMs: Long
    ) {
        val dao = aiUsageMeterDao ?: return
        val uid = userIdProvider().ifBlank { "default_user" }
        val normalizedPrompt = promptTokens.coerceAtLeast(0)
        val normalizedOutput = outputTokens.coerceAtLeast(0)
        val computedTotal = if (totalTokens > 0) totalTokens else (normalizedPrompt + normalizedOutput)
        runCatching {
            dao.recordUsage(
                AiUsageMeterEntity(
                    userId = uid,
                    sessionId = sessionId,
                    modelId = modelId,
                    promptTokens = normalizedPrompt,
                    outputTokens = normalizedOutput,
                    totalTokens = computedTotal,
                    latencyMs = latencyMs.coerceAtLeast(0L)
                )
            )
        }
    }
}

object ExponentialBackoffExecutor {
    suspend fun <T> executeWithRetry(
        maxAttempts: Int = 3,
        initialDelayMs: Long = 500L,
        maxDelayMs: Long = 4500L,
        multiplier: Double = 2.0,
        shouldRetry: (Throwable) -> Boolean,
        block: suspend (attempt: Int) -> T
    ): T {
        val boundedAttempts = maxAttempts.coerceIn(1, 5)
        var currentDelay = initialDelayMs.coerceAtLeast(0L)
        var lastError: Throwable? = null
        for (attempt in 1..boundedAttempts) {
            try {
                return block(attempt)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastError = t
                if (attempt >= boundedAttempts || !shouldRetry(t)) {
                    throw t
                }
                if (currentDelay > 0L) {
                    delay(currentDelay)
                    currentDelay = min((currentDelay * multiplier).toLong(), maxDelayMs)
                }
            }
        }
        throw lastError ?: IllegalStateException("Retry executor exhausted without error.")
    }
}
