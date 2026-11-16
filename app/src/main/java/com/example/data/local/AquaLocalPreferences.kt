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
 * Local per-user preferences for features layered seamlessly atop Firestore settings
 * without altering Firestore security rule schemas (AI persona, custom directive, pinned routines).
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

    private fun key(suffix: String): String = "${userId.ifBlank { "default" }}_$suffix"

    private fun loadPersonaId(): String =
        prefs.getString(key(KEY_PERSONA), AquaPersona.AUTONOMOUS.id) ?: AquaPersona.AUTONOMOUS.id

    private fun loadCustomDirective(): String =
        prefs.getString(key(KEY_DIRECTIVE), "").orEmpty()

    private fun loadPinnedRoutines(): Set<String> =
        prefs.getStringSet(key(KEY_PINNED_ROUTINES), emptySet())?.toSet() ?: emptySet()

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

    private companion object {
        const val PREFS_NAME = "aqua_local_companion_prefs"
        const val KEY_PERSONA = "ai_persona_id"
        const val KEY_DIRECTIVE = "custom_persona_directive"
        const val KEY_PINNED_ROUTINES = "pinned_routine_ids"
    }
}
