package com.example.domain

import com.example.data.local.AiFreedomConfig
import com.example.data.model.UserSettings

enum class AgeTier(
    val id: String,
    val label: String,
    val shortBadge: String,
    val summary: String
) {
    UNSET(
        id = "unset",
        label = "Age Not Set",
        shortBadge = "AGE UNSET",
        summary = "Confirm your age (13+) in Profile & Safety or Setup to personalize guardrails."
    ),
    UNDER_13_BLOCKED(
        id = "under_13_blocked",
        label = "Under 13 (Restricted)",
        shortBadge = "UNDER 13",
        summary = "Aqua AI requires users to be at least 13 years old under Google Play and COPPA policy."
    ),
    TEEN_13_17(
        id = "teen_13_17",
        label = "Teen Mode (13–17)",
        shortBadge = "TEEN 13–17",
        summary = "Teen Guardrails active: balanced autonomy is enforced and risky/unmoderated actions require approval."
    ),
    ADULT_18_PLUS(
        id = "adult_18_plus",
        label = "Adult Mode (18+)",
        shortBadge = "18+ FULL",
        summary = "Full Aqua AI capabilities enabled."
    );

    companion object {
        fun fromId(id: String): AgeTier = entries.firstOrNull { it.id == id } ?: UNSET
    }
}

enum class UserGender(
    val id: String,
    val label: String,
    val pronounHint: String
) {
    UNSPECIFIED(
        id = "unspecified",
        label = "Not specified",
        pronounHint = ""
    ),
    FEMALE(
        id = "female",
        label = "Female",
        pronounHint = "User identifies as Female (she/her when referring to the user)."
    ),
    MALE(
        id = "male",
        label = "Male",
        pronounHint = "User identifies as Male (he/him when referring to the user)."
    ),
    NON_BINARY(
        id = "non_binary",
        label = "Non-binary",
        pronounHint = "User identifies as Non-binary (they/them when referring to the user)."
    ),
    PREFER_NOT_TO_SAY(
        id = "prefer_not_to_say",
        label = "Prefer not to say",
        pronounHint = "Keep address gender-neutral."
    );

    companion object {
        fun fromId(id: String): UserGender = entries.firstOrNull { it.id == id } ?: UNSPECIFIED
    }
}

/**
 * Centralized Age 13+ eligibility, Teen Guardrails (13-17), and optional Gender personalization rules.
 */
object UserProfilePolicy {
    const val MIN_ALLOWED_AGE = 13
    const val MAX_VALID_AGE = 120

    fun resolveAgeTier(ageYears: Int, confirmed: Boolean = ageYears > 0): AgeTier {
        if (!confirmed && ageYears <= 0) return AgeTier.UNSET
        return when {
            ageYears in 1 until MIN_ALLOWED_AGE -> AgeTier.UNDER_13_BLOCKED
            ageYears in MIN_ALLOWED_AGE..17 -> AgeTier.TEEN_13_17
            ageYears >= 18 -> AgeTier.ADULT_18_PLUS
            else -> AgeTier.UNSET
        }
    }

    fun resolveTier(settings: UserSettings): AgeTier {
        val computed = resolveAgeTier(settings.ageYears, settings.ageGateConfirmed)
        return if (computed != AgeTier.UNSET) computed else AgeTier.fromId(settings.ageGroup)
    }

    fun isUnderAgeBlocked(settings: UserSettings): Boolean =
        resolveTier(settings) == AgeTier.UNDER_13_BLOCKED

    fun isTeenMode(settings: UserSettings): Boolean =
        resolveTier(settings) == AgeTier.TEEN_13_17

    fun normalizeSettingsProfile(
        settings: UserSettings,
        ageYears: Int,
        genderId: String
    ): UserSettings {
        val clampedAge = ageYears.coerceIn(0, MAX_VALID_AGE)
        val confirmed = clampedAge > 0
        val tier = resolveAgeTier(clampedAge, confirmed)
        val normalizedGender = UserGender.fromId(genderId).id
        return settings.copy(
            ageYears = clampedAge,
            ageGroup = tier.id,
            gender = normalizedGender,
            ageGateConfirmed = confirmed
        )
    }

    fun clampFreedomForAge(
        freedom: AiFreedomConfig,
        settings: UserSettings
    ): AiFreedomConfig {
        return if (isTeenMode(settings) && freedom.autonomyLevel == AiFreedomConfig.AUTONOMY_UNRESTRICTED) {
            freedom.copy(autonomyLevel = AiFreedomConfig.AUTONOMY_BALANCED)
        } else {
            freedom
        }
    }

    fun buildProfileContextBlock(settings: UserSettings): String {
        val tier = resolveTier(settings)
        val gender = UserGender.fromId(settings.gender)
        return buildString {
            appendLine("[USER PROFILE & SAFETY POLICY]")
            when (tier) {
                AgeTier.UNDER_13_BLOCKED -> {
                    appendLine("- Age Status: Under 13 (Restricted). Refuse assistance and direct the user to adult supervision.")
                }
                AgeTier.TEEN_13_17 -> {
                    appendLine("- Age Tier: Teen Mode (${settings.ageYears} years, 13–17). TEEN GUARDRAILS ACTIVE:")
                    appendLine("  * Use age-appropriate, constructive explanations; avoid mature/explicit themes, gambling/speculative financial encouragement, or unsafe device actions.")
                    appendLine("  * Balanced autonomy applies: always confirm sensitive actions or external writes.")
                }
                AgeTier.ADULT_18_PLUS -> {
                    appendLine("- Age Tier: Adult (${settings.ageYears} years, 18+). Standard capabilities unlocked.")
                }
                AgeTier.UNSET -> {
                    appendLine("- Age Tier: Unset (treat cautiously until user sets age 13+).")
                }
            }
            if (gender != UserGender.UNSPECIFIED && gender.pronounHint.isNotBlank()) {
                appendLine("- Gender Preference: ${gender.label} — ${gender.pronounHint}")
            }
        }
    }
}
