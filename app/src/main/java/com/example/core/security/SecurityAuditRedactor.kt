package com.example.core.security

/**
 * Redacts sensitive secrets before strings ever enter local Audit Logs, diagnostic exports, or UI traces.
 * Also provides the non-negotiable SecretExclusionFilter blocking secrets from entering Google Drive sync.
 */
object SecurityAuditRedactor {

    private val bearerTokenRegex = Regex("ya29\\.[A-Za-z0-9\\-._~+/]+=*")
    private val jwtRegex = Regex("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}")
    private val googleApiKeyRegex = Regex("AIza[0-9A-Za-z\\-_]{20,}")
    private val githubPatRegex = Regex("gh[pousr]_[A-Za-z0-9_]{20,}")
    private val elevenLabsKeyRegex = Regex("sk_[A-Za-z0-9]{20,}")
    private val hexPrivateKeyRegex = Regex("\\b(0x)?[0-9a-fA-F]{64}\\b")
    private val labeledSecretRegex = Regex(
        "(?i)(mnemonic|seed_phrase|seedPhrase|private_key|privateKey|keystore|passphrase|access_token|refresh_token|bearer|api_key|apiKey|password)\\s*[:=]\\s*[\"']?[^\\s\"',}]+[\"']?"
    )
    private val twelveOr24WordMnemonicRegex = Regex(
        "\\b([a-z]{3,8}\\s+){11,23}[a-z]{3,8}\\b"
    )

    fun redact(input: String): String {
        if (input.isBlank()) return input
        var out = input
        out = bearerTokenRegex.replace(out, "[REDACTED_OAUTH_TOKEN]")
        out = jwtRegex.replace(out, "[REDACTED_JWT]")
        out = googleApiKeyRegex.replace(out, "[REDACTED_GOOGLE_API_KEY]")
        out = githubPatRegex.replace(out, "[REDACTED_GITHUB_TOKEN]")
        out = elevenLabsKeyRegex.replace(out, "[REDACTED_ELEVENLABS_KEY]")
        out = labeledSecretRegex.replace(out) { mr -> "${mr.groupValues[1]}=[REDACTED]" }
        out = hexPrivateKeyRegex.replace(out) { mr ->
            val raw = mr.value
            if (raw.startsWith("0x") && raw.length == 66) "[REDACTED_64HEX_KEY]"
            else if (!raw.startsWith("0x") && raw.length == 64 && containsSecretContext(out)) "[REDACTED_64HEX_KEY]"
            else raw
        }
        return out
    }

    private fun containsSecretContext(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("private") || lower.contains("secret") || lower.contains("seed") || lower.contains("key")
    }
}

data class SecretFilterVerdict(
    val allowed: Boolean,
    val reason: String = ""
)

/**
 * Enforces the non-negotiable pre-upload sanitization filter before any payload or filename
 * enters the Google Drive sync queue or Drive REST transport.
 */
object SecretExclusionFilter {

    private val forbiddenFileNames = setOf(
        "aqua_native_wallet_vault",
        "aqua_secure_config_prefs",
        "aqua_db_key_prefs",
        "aqua_ssot_encrypted.db",
        "aqua_local_cache.db",
        "wallet_secrets",
        "keystore",
        "private_key",
        "seed_phrase"
    )

    private val forbiddenPayloadMarkers = listOf(
        "\"mnemonic\"",
        "\"privatekey\"",
        "\"private_key\"",
        "\"seedphrase\"",
        "\"seed_phrase\"",
        "\"wallet_pin_hash\"",
        "\"enc_secret_",
        "\"enc_gemini_api_key\"",
        "\"enc_elevenlabs_api_key\"",
        "\"aqua_encrypted_wallet_v1\"",
        "-----begin private key-----",
        "-----begin ec private key-----",
        "ya29.",
        "ghp_",
        "gho_",
        "aiza"
    )

    fun inspectForDriveUpload(
        fileName: String,
        payloadContent: String
    ): SecretFilterVerdict {
        val cleanName = fileName.lowercase()
        if (forbiddenFileNames.any { cleanName.contains(it) }) {
            return SecretFilterVerdict(
                allowed = false,
                reason = "Blocked Drive upload: filename '$fileName' matches restricted local secret/vault artifact."
            )
        }
        val lowerPayload = payloadContent.lowercase()
        val matchedMarker = forbiddenPayloadMarkers.firstOrNull { lowerPayload.contains(it) }
        if (matchedMarker != null) {
            return SecretFilterVerdict(
                allowed = false,
                reason = "Blocked Drive upload: payload contains restricted secret/credential marker ($matchedMarker)."
            )
        }
        if (Regex("\\b(0x)?[0-9a-fA-F]{64}\\b").containsMatchIn(payloadContent) &&
            (lowerPayload.contains("private") || lowerPayload.contains("secret") || lowerPayload.contains("signing"))
        ) {
            return SecretFilterVerdict(
                allowed = false,
                reason = "Blocked Drive upload: payload appears to contain a raw 256-bit cryptographic key."
            )
        }
        return SecretFilterVerdict(allowed = true)
    }
}
