package com.example.data.local

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AquaPersona(
    val id: String,
    val label: String,
    val badge: String,
    val promptGuidance: String
) {
    AUTONOMOUS(
        id = "autonomous",
        label = "Autonomous",
        badge = "AUTO",
        promptGuidance = "Operate as an autonomous proactive Android agent: execute reversible tools immediately, surface clear action summaries, and ask approval only for impactful actions."
    ),
    CONCISE_VOICE(
        id = "concise_voice",
        label = "Voice Concise",
        badge = "VOICE",
        promptGuidance = "Respond in crisp 1-3 sentence natural spoken phrasing free of excessive formatting or markdown clutter so responses sound great when read aloud."
    ),
    DEEP_ANALYST(
        id = "deep_analyst",
        label = "Deep Analyst",
        badge = "RESEARCH",
        promptGuidance = "Provide thorough, structured, multi-angle analysis with clear headings, verified sources, comparisons, and code/technical precision when applicable."
    ),
    ANDROID_POWER(
        id = "android_power",
        label = "Device Power",
        badge = "ANDROID",
        promptGuidance = "Prioritize Android device optimization, installed app workflows, launcher customization, routines, notifications, storage/RAM diagnostics, and Workspace productivity."
    ),
    WEB3_STRATEGIST(
        id = "web3_strategist",
        label = "On-Chain Pro",
        badge = "WEB3",
        promptGuidance = "Focus on multi-chain EVM/Solana/Bitcoin telemetry, gas optimization, ERC-20 allowance hygiene, ENS verification, and strict non-custodial security checks."
    );

    companion object {
        fun fromId(id: String): AquaPersona = entries.firstOrNull { it.id == id } ?: AUTONOMOUS
    }
}

data class TelemetrySample(
    val timestampMs: Long,
    val batteryPercent: Int,
    val ramUsedPercent: Int,
    val isCharging: Boolean,
    val isOnline: Boolean
)

/**
 * Granular AI dependency & autonomy configuration giving users full control over
 * model sampling parameters, multi-step chaining depth, unrestricted tool access,
 * custom model overrides from Google AI Studio, and response style.
 */
data class AiFreedomConfig(
    val autonomyLevel: String = AUTONOMY_BALANCED,
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val maxOutputTokens: Int = 4096,
    val maxAgentSteps: Int = 10,
    val enableAllToolsAlways: Boolean = true,
    val customModelOverride: String = "",
    val responseStyle: String = STYLE_ADAPTIVE
) {
    companion object {
        const val AUTONOMY_ADVISORY = "ADVISORY"
        const val AUTONOMY_BALANCED = "BALANCED"
        const val AUTONOMY_UNRESTRICTED = "UNRESTRICTED"

        const val STYLE_ADAPTIVE = "ADAPTIVE"
        const val STYLE_CONCISE = "CONCISE"
        const val STYLE_TECHNICAL = "TECHNICAL"
        const val STYLE_CREATIVE = "CREATIVE"
    }
}

/**
 * Optional ElevenLabs voice synthesis preferences (non-compulsory).
 */
data class ElevenLabsConfig(
    val enabled: Boolean = true,
    val voiceName: String = "",
    val modelId: String = MODEL_MULTILINGUAL_V2,
    val stability: Float = 0.50f,
    val similarityBoost: Float = 0.75f
) {
    companion object {
        const val MODEL_MULTILINGUAL_V2 = "eleven_multilingual_v2"
        const val MODEL_FLASH_V2_5 = "eleven_flash_v2_5"
        const val MODEL_TURBO_V2_5 = "eleven_turbo_v2_5"
        const val MODEL_MONOLINGUAL_V1 = "eleven_monolingual_v1"

        val SUPPORTED_MODELS = listOf(
            MODEL_MULTILINGUAL_V2 to "Multilingual v2 (Expressive)",
            MODEL_FLASH_V2_5 to "Flash v2.5 (Ultra Low-Latency)",
            MODEL_TURBO_V2_5 to "Turbo v2.5 (Balanced Real-Time)",
            MODEL_MONOLINGUAL_V1 to "English Monolingual v1"
        )
    }
}

/**
 * Local per-user preferences for features layered seamlessly atop Firestore settings
 * without altering Firestore security rule schemas (AI persona, custom directive, pinned routines,
 * AI Freedom parameters, ElevenLabs voice tuning, and post-login setup completion).
 */
class AquaLocalPreferences(
    context: Context,
    private val userId: String
) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _personaId = MutableStateFlow(loadPersonaId())
    val personaId: StateFlow<String> = _personaId.asStateFlow()

    private val _customDirective = MutableStateFlow(loadCustomDirective())
    val customDirective: StateFlow<String> = _customDirective.asStateFlow()

    private val _pinnedRoutineIds = MutableStateFlow(loadPinnedRoutines())
    val pinnedRoutineIds: StateFlow<Set<String>> = _pinnedRoutineIds.asStateFlow()

    private val _pinnedConversationIds = MutableStateFlow(loadPinnedConversations())
    val pinnedConversationIds: StateFlow<Set<String>> = _pinnedConversationIds.asStateFlow()

    private val _conversationAliases = MutableStateFlow(loadConversationAliases())
    val conversationAliases: StateFlow<Map<String, String>> = _conversationAliases.asStateFlow()

    private val _aiFreedomConfig = MutableStateFlow(loadAiFreedomConfig())
    val aiFreedomConfig: StateFlow<AiFreedomConfig> = _aiFreedomConfig.asStateFlow()

    private val _elevenLabsConfig = MutableStateFlow(loadElevenLabsConfig())
    val elevenLabsConfig: StateFlow<ElevenLabsConfig> = _elevenLabsConfig.asStateFlow()

    private val _hasCompletedAiSetup = MutableStateFlow(loadHasCompletedAiSetup())
    val hasCompletedAiSetup: StateFlow<Boolean> = _hasCompletedAiSetup.asStateFlow()

    private fun key(suffix: String): String = "${userId.ifBlank { "default" }}_$suffix"

    private fun loadPersonaId(): String =
        prefs.getString(key(KEY_PERSONA), AquaPersona.AUTONOMOUS.id) ?: AquaPersona.AUTONOMOUS.id

    private fun loadCustomDirective(): String =
        prefs.getString(key(KEY_DIRECTIVE), "").orEmpty()

    private fun loadPinnedRoutines(): Set<String> =
        prefs.getStringSet(key(KEY_PINNED_ROUTINES), emptySet())?.toSet() ?: emptySet()

    private fun loadPinnedConversations(): Set<String> =
        prefs.getStringSet(key(KEY_PINNED_CONVERSATIONS), emptySet())?.toSet() ?: emptySet()

    private fun loadConversationAliases(): Map<String, String> {
        val raw = prefs.getString(key(KEY_CONV_ALIASES), "").orEmpty()
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val obj = org.json.JSONObject(raw)
            buildMap {
                obj.keys().forEach { k ->
                    val v = obj.optString(k, "").trim()
                    if (v.isNotEmpty()) put(k, v)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun loadAiFreedomConfig(): AiFreedomConfig {
        val raw = prefs.getString(key(KEY_AI_FREEDOM), "").orEmpty()
        if (raw.isBlank()) return AiFreedomConfig()
        return runCatching {
            val obj = org.json.JSONObject(raw)
            AiFreedomConfig(
                autonomyLevel = obj.optString("autonomyLevel", AiFreedomConfig.AUTONOMY_BALANCED)
                    .takeIf { it in setOf(AiFreedomConfig.AUTONOMY_ADVISORY, AiFreedomConfig.AUTONOMY_BALANCED, AiFreedomConfig.AUTONOMY_UNRESTRICTED) }
                    ?: AiFreedomConfig.AUTONOMY_BALANCED,
                temperature = obj.optDouble("temperature", 0.7).toFloat().coerceIn(0f, 2f),
                topP = obj.optDouble("topP", 0.95).toFloat().coerceIn(0.1f, 1f),
                maxOutputTokens = obj.optInt("maxOutputTokens", 4096).coerceIn(512, 8192),
                maxAgentSteps = obj.optInt("maxAgentSteps", 10).coerceIn(2, 15),
                enableAllToolsAlways = obj.optBoolean("enableAllToolsAlways", true),
                customModelOverride = obj.optString("customModelOverride", "").trim().take(120),
                responseStyle = obj.optString("responseStyle", AiFreedomConfig.STYLE_ADAPTIVE)
                    .takeIf { it in setOf(AiFreedomConfig.STYLE_ADAPTIVE, AiFreedomConfig.STYLE_CONCISE, AiFreedomConfig.STYLE_TECHNICAL, AiFreedomConfig.STYLE_CREATIVE) }
                    ?: AiFreedomConfig.STYLE_ADAPTIVE
            )
        }.getOrDefault(AiFreedomConfig())
    }

    private fun loadElevenLabsConfig(): ElevenLabsConfig {
        val raw = prefs.getString(key(KEY_ELEVENLABS_CONFIG), "").orEmpty()
        if (raw.isBlank()) return ElevenLabsConfig()
        return runCatching {
            val obj = org.json.JSONObject(raw)
            ElevenLabsConfig(
                enabled = obj.optBoolean("enabled", true),
                voiceName = obj.optString("voiceName", "").trim().take(80),
                modelId = obj.optString("modelId", ElevenLabsConfig.MODEL_MULTILINGUAL_V2)
                    .ifBlank { ElevenLabsConfig.MODEL_MULTILINGUAL_V2 },
                stability = obj.optDouble("stability", 0.50).toFloat().coerceIn(0f, 1f),
                similarityBoost = obj.optDouble("similarityBoost", 0.75).toFloat().coerceIn(0f, 1f)
            )
        }.getOrDefault(ElevenLabsConfig())
    }

    private fun loadHasCompletedAiSetup(): Boolean =
        prefs.getBoolean(key(KEY_AI_SETUP_DONE), false)

    fun setPersonaId(id: String) {
        val resolved = AquaPersona.fromId(id).id
        prefs.edit().putString(key(KEY_PERSONA), resolved).apply()
        _personaId.value = resolved
    }

    fun setCustomDirective(directive: String) {
        val clean = directive.trim().take(1200)
        prefs.edit().putString(key(KEY_DIRECTIVE), clean).apply()
        _customDirective.value = clean
    }

    fun setAiFreedomConfig(config: AiFreedomConfig) {
        val normalized = config.copy(
            temperature = config.temperature.coerceIn(0f, 2f),
            topP = config.topP.coerceIn(0.1f, 1f),
            maxOutputTokens = config.maxOutputTokens.coerceIn(512, 8192),
            maxAgentSteps = config.maxAgentSteps.coerceIn(2, 15),
            customModelOverride = config.customModelOverride.trim().take(120)
        )
        val obj = org.json.JSONObject().apply {
            put("autonomyLevel", normalized.autonomyLevel)
            put("temperature", normalized.temperature.toDouble())
            put("topP", normalized.topP.toDouble())
            put("maxOutputTokens", normalized.maxOutputTokens)
            put("maxAgentSteps", normalized.maxAgentSteps)
            put("enableAllToolsAlways", normalized.enableAllToolsAlways)
            put("customModelOverride", normalized.customModelOverride)
            put("responseStyle", normalized.responseStyle)
        }
        prefs.edit().putString(key(KEY_AI_FREEDOM), obj.toString()).apply()
        _aiFreedomConfig.value = normalized
    }

    fun setElevenLabsConfig(config: ElevenLabsConfig) {
        val normalized = config.copy(
            voiceName = config.voiceName.trim().take(80),
            modelId = config.modelId.trim().ifBlank { ElevenLabsConfig.MODEL_MULTILINGUAL_V2 },
            stability = config.stability.coerceIn(0f, 1f),
            similarityBoost = config.similarityBoost.coerceIn(0f, 1f)
        )
        val obj = org.json.JSONObject().apply {
            put("enabled", normalized.enabled)
            put("voiceName", normalized.voiceName)
            put("modelId", normalized.modelId)
            put("stability", normalized.stability.toDouble())
            put("similarityBoost", normalized.similarityBoost.toDouble())
        }
        prefs.edit().putString(key(KEY_ELEVENLABS_CONFIG), obj.toString()).apply()
        _elevenLabsConfig.value = normalized
    }

    fun setAiSetupCompleted(completed: Boolean) {
        prefs.edit().putBoolean(key(KEY_AI_SETUP_DONE), completed).apply()
        _hasCompletedAiSetup.value = completed
    }

    fun togglePinnedRoutine(routineId: String): Boolean {
        if (routineId.isBlank()) return false
        val next = _pinnedRoutineIds.value.toMutableSet()
        val nowPinned = if (next.contains(routineId)) {
            next.remove(routineId)
            false
        } else {
            next.add(routineId)
            true
        }
        prefs.edit().putStringSet(key(KEY_PINNED_ROUTINES), next).apply()
        _pinnedRoutineIds.value = next
        return nowPinned
    }

    fun togglePinnedConversation(conversationId: String): Boolean {
        if (conversationId.isBlank()) return false
        val next = _pinnedConversationIds.value.toMutableSet()
        val nowPinned = if (next.contains(conversationId)) {
            next.remove(conversationId)
            false
        } else {
            next.add(conversationId)
            true
        }
        prefs.edit().putStringSet(key(KEY_PINNED_CONVERSATIONS), next).apply()
        _pinnedConversationIds.value = next
        return nowPinned
    }

    fun setConversationAlias(conversationId: String, alias: String) {
        if (conversationId.isBlank()) return
        val clean = alias.trim().take(80)
        val next = _conversationAliases.value.toMutableMap()
        if (clean.isBlank()) {
            next.remove(conversationId)
        } else {
            next[conversationId] = clean
        }
        val json = org.json.JSONObject()
        next.forEach { (k, v) -> json.put(k, v) }
        prefs.edit().putString(key(KEY_CONV_ALIASES), json.toString()).apply()
        _conversationAliases.value = next
    }

    private companion object {
        const val PREFS_NAME = "aqua_local_companion_prefs"
        const val KEY_PERSONA = "ai_persona_id"
        const val KEY_DIRECTIVE = "custom_persona_directive"
        const val KEY_PINNED_ROUTINES = "pinned_routine_ids"
        const val KEY_PINNED_CONVERSATIONS = "pinned_conversation_ids"
        const val KEY_CONV_ALIASES = "conversation_aliases_json"
        const val KEY_AI_FREEDOM = "ai_freedom_config_json"
        const val KEY_ELEVENLABS_CONFIG = "elevenlabs_config_json"
        const val KEY_AI_SETUP_DONE = "ai_setup_completed"
    }
}
