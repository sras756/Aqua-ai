package com.example.ai

import com.example.data.local.AquaPersona
import com.example.data.local.TelemetrySample
import com.example.data.model.ChatMessage
import com.example.device.DeviceTelemetry
import com.example.device.StorageAndMemoryStats
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class MessageSegment {
    data class Prose(val text: String) : MessageSegment()
    data class CodeBlock(val language: String, val code: String) : MessageSegment()
}

data class ConversationSessionStats(
    val totalMessages: Int,
    val userMessages: Int,
    val assistantMessages: Int,
    val codeBlockCount: Int,
    val toolCallCount: Int,
    val estimatedWords: Int
)

object ConversationMarkdownExporter {

    /**
     * Splits an AI/User message into plain prose and fenced ```code``` blocks
     * so Compose can render dedicated monospace code cards with copy actions.
     */
    fun parseMessageSegments(raw: String): List<MessageSegment> {
        if (!raw.contains("```")) {
            return if (raw.isBlank()) emptyList() else listOf(MessageSegment.Prose(raw))
        }
        val regex = Regex("```([a-zA-Z0-9_+.-]*)\\s*\\n?([\\s\\S]*?)```")
        val result = mutableListOf<MessageSegment>()
        var cursor = 0
        for (match in regex.findAll(raw)) {
            if (match.range.first > cursor) {
                val prose = raw.substring(cursor, match.range.first).trim()
                if (prose.isNotEmpty()) result.add(MessageSegment.Prose(prose))
            }
            val lang = match.groupValues[1].trim().ifBlank { "code" }
            val code = match.groupValues[2].trimEnd('\n', '\r')
            if (code.isNotEmpty()) {
                result.add(MessageSegment.CodeBlock(lang, code))
            }
            cursor = match.range.last + 1
        }
        if (cursor < raw.length) {
            val tail = raw.substring(cursor).trim()
            if (tail.isNotEmpty()) result.add(MessageSegment.Prose(tail))
        }
        return result.ifEmpty { listOf(MessageSegment.Prose(raw)) }
    }

    fun computeSessionStats(messages: List<ChatMessage>): ConversationSessionStats {
        var userCount = 0
        var aiCount = 0
        var codeBlocks = 0
        var tools = 0
        var words = 0

        messages.forEach { msg ->
            if (msg.role.equals("user", ignoreCase = true)) userCount++ else aiCount++
            val segments = parseMessageSegments(msg.content)
            codeBlocks += segments.count { it is MessageSegment.CodeBlock }
            words += msg.content.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
            if (msg.toolActionsJson.isNotBlank()) {
                runCatching {
                    tools += JSONArray(msg.toolActionsJson).length()
                }
            }
        }
        return ConversationSessionStats(
            totalMessages = messages.size,
            userMessages = userCount,
            assistantMessages = aiCount,
            codeBlockCount = codeBlocks,
            toolCallCount = tools,
            estimatedWords = words
        )
    }

    fun formatConversationAsMarkdown(
        title: String,
        messages: List<ChatMessage>,
        exportedAtMs: Long = System.currentTimeMillis()
    ): String = buildString {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val stats = computeSessionStats(messages)
        appendLine("# ${title.ifBlank { "Aqua AI Session" }}")
        appendLine()
        appendLine(
            "_Exported ${fmt.format(Date(exportedAtMs))} • ${stats.totalMessages} message(s) • " +
                "${stats.estimatedWords} words • ${stats.codeBlockCount} code block(s) • ${stats.toolCallCount} tool execution(s)_"
        )
        appendLine()
        appendLine("---")
        appendLine()

        messages.forEach { msg ->
            val roleHeading = when (msg.role.lowercase()) {
                "user" -> "### You"
                "agent" -> "### Aqua Agent"
                else -> "### Aqua AI (${msg.modelUsed.ifBlank { "gemini" }})"
            }
            appendLine(roleHeading)
            if (msg.attachmentName.isNotBlank()) {
                appendLine("*Attachment: ${msg.attachmentName} (${msg.attachmentMime})*")
            }
            appendLine()
            appendLine(msg.content.trim())
            appendLine()

            if (msg.toolActionsJson.isNotBlank()) {
                runCatching {
                    val arr = JSONArray(msg.toolActionsJson)
                    if (arr.length() > 0) {
                        appendLine("<details><summary>Tool Executions (${arr.length()})</summary>")
                        appendLine()
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            appendLine("- **${o.optString("tool")}** `[${o.optString("status")}]`: ${o.optString("detail")}")
                        }
                        appendLine()
                        appendLine("</details>")
                        appendLine()
                    }
                }
            }

            if (msg.groundingSourcesJson.isNotBlank()) {
                runCatching {
                    val arr = JSONArray(msg.groundingSourcesJson)
                    if (arr.length() > 0) {
                        val list = (0 until arr.length()).map { arr.getString(it) }
                        appendLine("**Sources:** ${list.joinToString(" | ")}")
                        appendLine()
                    }
                }
            }
        }
    }.trimEnd() + "\n"

    fun formatConversationAsJson(
        title: String,
        messages: List<ChatMessage>,
        exportedAtMs: Long = System.currentTimeMillis()
    ): String {
        val stats = computeSessionStats(messages)
        val root = JSONObject().apply {
            put("title", title.ifBlank { "Aqua AI Session" })
            put("exportedAtMs", exportedAtMs)
            put("stats", JSONObject().apply {
                put("totalMessages", stats.totalMessages)
                put("userMessages", stats.userMessages)
                put("assistantMessages", stats.assistantMessages)
                put("codeBlockCount", stats.codeBlockCount)
                put("toolCallCount", stats.toolCallCount)
                put("estimatedWords", stats.estimatedWords)
            })
            val msgArray = JSONArray()
            messages.forEach { m ->
                msgArray.put(JSONObject().apply {
                    put("id", m.id)
                    put("role", m.role)
                    put("modelUsed", m.modelUsed)
                    put("content", m.content)
                    if (m.attachmentName.isNotBlank()) {
                        put("attachmentName", m.attachmentName)
                        put("attachmentMime", m.attachmentMime)
                    }
                    if (m.toolActionsJson.isNotBlank()) {
                        runCatching { put("toolActions", JSONArray(m.toolActionsJson)) }
                    }
                    if (m.groundingSourcesJson.isNotBlank()) {
                        runCatching { put("sources", JSONArray(m.groundingSourcesJson)) }
                    }
                })
            }
            put("messages", msgArray)
        }
        return root.toString(2) + "\n"
    }
}

object DiagnosticReportExporter {

    fun buildSystemDiagnosticMarkdown(
        telemetry: DeviceTelemetry,
        storage: StorageAndMemoryStats,
        samples: List<TelemetrySample>,
        installedAppsCount: Int,
        activeModel: String,
        activePersona: AquaPersona,
        foregroundServiceActive: Boolean,
        generatedAtMs: Long = System.currentTimeMillis()
    ): String = buildString {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val ramUsed = (storage.ramTotalMb - storage.ramAvailableMb).coerceAtLeast(0L)
        val ramPct = if (storage.ramTotalMb > 0) ((ramUsed * 100) / storage.ramTotalMb).toInt() else 0
        val storageUsed = (storage.storageTotalMb - storage.storageFreeMb).coerceAtLeast(0L)
        val storagePct = if (storage.storageTotalMb > 0) ((storageUsed * 100) / storage.storageTotalMb).toInt() else 0

        appendLine("# Aqua AI — Android System & Session Diagnostics")
        appendLine()
        appendLine("_Generated ${fmt.format(Date(generatedAtMs))}_")
        appendLine()
        appendLine("## Live Device Telemetry")
        appendLine("- **Battery:** ${telemetry.batteryPercent}% (${if (telemetry.isCharging) "Charging" else "Discharging"})")
        appendLine("- **Network:** ${if (telemetry.isOnline) "Online (${telemetry.connectionType})" else "Offline"}")
        appendLine("- **RAM Usage:** $ramUsed MB / ${storage.ramTotalMb} MB ($ramPct%${if (storage.lowMemory) " • LOW MEMORY" else ""})")
        appendLine("- **Internal Storage:** $storageUsed MB / ${storage.storageTotalMb} MB ($storagePct% used • ${storage.storageFreeMb} MB free)")
        appendLine("- **Media Volume:** ${telemetry.mediaVolumePercent}% | **Flashlight:** ${if (telemetry.flashlightEnabled) "On" else if (telemetry.flashlightAvailable) "Off (Available)" else "Unavailable"}")
        appendLine("- **Notification Listener Permission:** ${if (telemetry.notificationAccessGranted) "Granted" else "Not Granted"}")
        appendLine()
        appendLine("## Aqua AI Runtime Configuration")
        appendLine("- **Active Model:** `$activeModel`")
        appendLine("- **Active Persona:** ${activePersona.label} (`${activePersona.id}`)")
        appendLine("- **Discovered Launchable Apps:** $installedAppsCount")
        appendLine("- **Foreground Priority Service:** ${if (foregroundServiceActive) "Active (Persistent Notification)" else "Idle"}")
        appendLine()
        appendLine("## Session Telemetry Ring Buffer (${samples.size} sample(s))")
        if (samples.isEmpty()) {
            appendLine("_No samples captured yet in this session._")
        } else {
            appendLine("| Time | Battery | RAM Used | Charging | Network |")
            appendLine("|---|---:|---:|---|---|")
            val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
            samples.forEach { s ->
                appendLine(
                    "| ${timeFmt.format(Date(s.timestampMs))} | ${s.batteryPercent}% | ${s.ramUsedPercent}% | " +
                        "${if (s.isCharging) "Yes" else "No"} | ${if (s.isOnline) "Online" else "Offline"} |"
                )
            }
        }
    }
}
