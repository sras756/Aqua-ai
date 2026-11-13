package com.example.skills

import com.example.data.model.AquaSkill
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

object SkillParserAndValidator {

    private val blockedExecutionPatterns = listOf(
        Regex("""Runtime\.getRuntime\(\)\.exec""", RegexOption.IGNORE_CASE),
        Regex("""DexClassLoader|PathClassLoader|System\.loadLibrary""", RegexOption.IGNORE_CASE),
        Regex("""eval\s*\(|Function\s*\(|child_process|subprocess\.Popen""", RegexOption.IGNORE_CASE),
        Regex("""rm\s+-rf\s+/|su\s+-c|chmod\s+777""", RegexOption.IGNORE_CASE)
    )

    private val warningPatterns = listOf(
        Regex("""seed\s*phrase|mnemonic|private\s*key|keystore\s*password""", RegexOption.IGNORE_CASE),
        Regex("""http://(?!localhost|127\.0\.0\.1)""", RegexOption.IGNORE_CASE),
        Regex("""accessibility|overlay|bypass\s+captcha|skip\s+auth""", RegexOption.IGNORE_CASE)
    )

    fun parseAndValidateFromBytes(
        fileName: String,
        bytes: ByteArray,
        sourceLabel: String
    ): Result<AquaSkill> = runCatching {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val parsed = when (ext) {
            "json" -> parseJsonSkill(bytes.toString(Charsets.UTF_8), fileName, sourceLabel)
            "yaml", "yml" -> parseYamlSkill(bytes.toString(Charsets.UTF_8), fileName, sourceLabel)
            "md", "markdown" -> parseMarkdownSkill(bytes.toString(Charsets.UTF_8), fileName, sourceLabel)
            "zip" -> parseZipSkill(bytes, fileName, sourceLabel)
            else -> {
                val text = bytes.toString(Charsets.UTF_8).trim()
                when {
                    text.startsWith("{") -> parseJsonSkill(text, fileName, sourceLabel)
                    text.startsWith("---") || text.contains("instructions:", ignoreCase = true) ->
                        parseYamlSkill(text, fileName, sourceLabel)
                    else -> parseMarkdownSkill(text, fileName, sourceLabel)
                }
            }
        }
        validateSecurity(parsed)
    }

    fun validateSecurity(skill: AquaSkill): AquaSkill {
        if (skill.securityStatus == "blocked") {
            return skill.copy(enabled = false)
        }
        val combined = buildString {
            append(skill.name).append('\n')
            append(skill.description).append('\n')
            append(skill.instructions).append('\n')
            append(skill.configJson)
        }

        val blockedHits = blockedExecutionPatterns.mapNotNull { it.find(combined)?.value }
        val warningHits = warningPatterns.mapNotNull { it.find(combined)?.value }

        val highRiskPerms = skill.requiredPermissions.filter { perm ->
            perm.uppercase().let {
                it.contains("ADMIN") || it.contains("WRITE_SETTINGS") ||
                    it.contains("TRANSFER") || it.contains("WALLET_SIGN")
            }
        }

        return when {
            blockedHits.isNotEmpty() -> skill.copy(
                securityStatus = "blocked",
                enabled = false,
                securityReport = "Blocked: Detected unsafe dynamic execution or shell pattern (${blockedHits.joinToString()}). Imported skills are declarative only."
            )
            skill.instructions.isBlank() -> skill.copy(
                securityStatus = "blocked",
                enabled = false,
                securityReport = "Blocked: Skill instructions cannot be empty."
            )
            warningHits.isNotEmpty() || highRiskPerms.isNotEmpty() -> {
                val notes = mutableListOf<String>()
                if (warningHits.isNotEmpty()) notes.add("Sensitive keywords: ${warningHits.joinToString()}")
                if (highRiskPerms.isNotEmpty()) notes.add("Elevated permissions: ${highRiskPerms.joinToString()}")
                skill.copy(
                    securityStatus = "warning",
                    securityReport = "Review advised (treated as untrusted input): ${notes.joinToString("; ")}"
                )
            }
            else -> skill.copy(
                securityStatus = "verified",
                securityReport = "Passed static security inspection. Declarative instructions only; no executable binaries."
            )
        }
    }

    fun exportToJson(skill: AquaSkill): String {
        return JSONObject().apply {
            put("name", skill.name)
            put("description", skill.description)
            put("version", skill.version)
            put("format", skill.format)
            put("instructions", skill.instructions)
            put("requiredPermissions", JSONArray(skill.requiredPermissions))
            put("tools", JSONArray(skill.tools))
            put("config", runCatching { JSONObject(skill.configJson) }.getOrDefault(JSONObject()))
        }.toString(2)
    }

    private fun parseJsonSkill(raw: String, fileName: String, sourceLabel: String): AquaSkill {
        val obj = JSONObject(raw)
        val perms = obj.optJSONArray("requiredPermissions")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } ?: obj.optJSONArray("permissions")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } ?: emptyList()

        val tools = obj.optJSONArray("tools")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } ?: emptyList()

        val configStr = obj.optJSONObject("config")?.toString(2)
            ?: obj.optString("configJson", "{}").ifBlank { "{}" }

        return AquaSkill(
            name = obj.optString("name", fileName.substringBeforeLast('.')).take(100),
            description = obj.optString("description", "").take(1000),
            version = obj.optString("version", "1.0.0").take(32),
            format = "json",
            instructions = obj.optString("instructions", obj.optString("prompt", "")).take(16000),
            requiredPermissions = perms.take(20),
            tools = tools.take(20),
            configJson = configStr.take(8000),
            enabled = false, // Untrusted imports start disabled until reviewed
            sourceLabel = sourceLabel.take(255)
        )
    }

    private fun parseYamlSkill(raw: String, fileName: String, sourceLabel: String): AquaSkill {
        val lines = raw.lines()
        val map = mutableMapOf<String, String>()
        val lists = mutableMapOf<String, MutableList<String>>()
        var currentKey: String? = null
        val multiline = StringBuilder()
        var inMultiline = false

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed == "---" || trimmed.startsWith("#")) continue
            if (inMultiline) {
                if (!line.startsWith(" ") && !line.startsWith("\t") && trimmed.contains(":")) {
                    map[currentKey!!] = multiline.toString().trim()
                    multiline.clear()
                    inMultiline = false
                } else {
                    multiline.appendLine(line.trimStart())
                    continue
                }
            }
            if (trimmed.startsWith("- ") && currentKey != null) {
                lists.getOrPut(currentKey) { mutableListOf() }
                    .add(trimmed.removePrefix("- ").trim().trim('"', '\''))
                continue
            }
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx > 0) {
                val key = trimmed.substring(0, colonIdx).trim()
                val value = trimmed.substring(colonIdx + 1).trim()
                currentKey = key
                if (value == "|" || value == ">") {
                    inMultiline = true
                    multiline.clear()
                } else if (value.startsWith("[") && value.endsWith("]")) {
                    val items = value.removeSurrounding("[", "]")
                        .split(",")
                        .map { it.trim().trim('"', '\'') }
                        .filter { it.isNotBlank() }
                    lists[key] = items.toMutableList()
                } else if (value.isNotEmpty()) {
                    map[key] = value.trim('"', '\'')
                }
            }
        }
        if (inMultiline && currentKey != null) {
            map[currentKey] = multiline.toString().trim()
        }

        val perms = lists["requiredPermissions"] ?: lists["permissions"] ?: emptyList()
        val tools = lists["tools"] ?: emptyList()
        return AquaSkill(
            name = (map["name"] ?: fileName.substringBeforeLast('.')).take(100),
            description = (map["description"] ?: "").take(1000),
            version = (map["version"] ?: "1.0.0").take(32),
            format = "yaml",
            instructions = (map["instructions"] ?: map["prompt"] ?: raw).take(16000),
            requiredPermissions = perms.take(20),
            tools = tools.take(20),
            configJson = map["config"] ?: "{}",
            enabled = false,
            sourceLabel = sourceLabel.take(255)
        )
    }

    private fun parseMarkdownSkill(raw: String, fileName: String, sourceLabel: String): AquaSkill {
        var name = fileName.substringBeforeLast('.')
        var description = ""
        var version = "1.0.0"
        var instructions = raw.trim()
        var perms = emptyList<String>()
        var tools = emptyList<String>()

        if (raw.trimStart().startsWith("---")) {
            val parts = raw.trimStart().removePrefix("---").split("---", limit = 2)
            if (parts.size == 2) {
                val frontmatter = parseYamlSkill(parts[0], fileName, sourceLabel)
                name = frontmatter.name
                description = frontmatter.description
                version = frontmatter.version
                perms = frontmatter.requiredPermissions
                tools = frontmatter.tools
                instructions = parts[1].trim()
            }
        } else {
            val firstHeading = raw.lines().firstOrNull { it.trimStart().startsWith("#") }
            if (firstHeading != null) {
                name = firstHeading.trimStart('#', ' ').trim()
            }
        }

        return AquaSkill(
            name = name.take(100),
            description = description.take(1000),
            version = version.take(32),
            format = "markdown",
            instructions = instructions.ifBlank { raw }.take(16000),
            requiredPermissions = perms.take(20),
            tools = tools.take(20),
            configJson = "{}",
            enabled = false,
            sourceLabel = sourceLabel.take(255)
        )
    }

    private fun parseZipSkill(bytes: ByteArray, fileName: String, sourceLabel: String): AquaSkill {
        var manifestSkill: AquaSkill? = null
        val rejectedBinaries = mutableListOf<String>()

        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            var entriesChecked = 0
            while (entry != null && entriesChecked < 50) {
                entriesChecked++
                val entryName = entry.name
                if (entryName.contains("..")) {
                    throw SecurityException("Blocked ZIP package containing path traversal entry: $entryName")
                }
                val lower = entryName.lowercase()
                if (lower.endsWith(".dex") || lower.endsWith(".jar") || lower.endsWith(".so") || lower.endsWith(".apk")) {
                    rejectedBinaries.add(entryName)
                } else if (!entry.isDirectory && manifestSkill == null &&
                    (lower.endsWith("skill.json") || lower.endsWith("skill.yaml") ||
                        lower.endsWith("skill.yml") || lower.endsWith("skill.md") ||
                        lower.endsWith(".json") || lower.endsWith(".yaml") || lower.endsWith(".md"))
                ) {
                    val contentBytes = zis.readBytes()
                    if (contentBytes.size <= 256 * 1024) {
                        val subExt = entryName.substringAfterLast('.', "").lowercase()
                        val subSkill = when (subExt) {
                            "json" -> parseJsonSkill(contentBytes.toString(Charsets.UTF_8), fileName, sourceLabel)
                            "yaml", "yml" -> parseYamlSkill(contentBytes.toString(Charsets.UTF_8), fileName, sourceLabel)
                            else -> parseMarkdownSkill(contentBytes.toString(Charsets.UTF_8), fileName, sourceLabel)
                        }
                        manifestSkill = subSkill.copy(format = "zip")
                    }
                }
                entry = zis.nextEntry
            }
        }

        if (rejectedBinaries.isNotEmpty()) {
            return AquaSkill(
                name = fileName.substringBeforeLast('.').take(100),
                description = "ZIP package contained prohibited executable binaries.",
                version = "0.0.0",
                format = "zip",
                instructions = "Blocked executable artifacts: ${rejectedBinaries.joinToString()}",
                securityStatus = "blocked",
                securityReport = "Blocked: Dynamic executable binaries (.dex/.jar/.so/.apk) are strictly prohibited.",
                enabled = false,
                sourceLabel = sourceLabel
            )
        }

        return manifestSkill ?: throw IllegalArgumentException(
            "ZIP package did not contain a valid skill.json, skill.yaml, or skill.md descriptor."
        )
    }
}
