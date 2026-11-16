package com.example.ai

import com.example.data.model.ChatMessage
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class MessageSegment {
    data class Prose(val text: String) : MessageSegment()
    data class CodeBlock(val language: String, val code: String) : MessageSegment()
}

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

    fun formatConversationAsMarkdown(
        title: String,
        messages: List<ChatMessage>,
        exportedAtMs: Long = System.currentTimeMillis()
    ): String = buildString {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        appendLine("# ${title.ifBlank { "Aqua AI Session" }}")
        appendLine()
        appendLine("_Exported ${fmt.format(Date(exportedAtMs))} • ${messages.size} message(s)_")
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
}
