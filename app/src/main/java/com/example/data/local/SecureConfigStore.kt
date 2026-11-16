package com.example.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed Android Keystore AES-GCM encrypted storage for sensitive
 * user-supplied configuration (personal Google AI Studio API key, optional ElevenLabs API key & Voice ID).
 * Never stores wallet private keys or seed phrases. Never logs secrets.
 */
class SecureConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    // --- Google AI Studio / Gemini API Key ---

    fun saveGeminiApiKey(apiKey: String) = saveEncryptedString(KEY_ENCRYPTED_GEMINI, apiKey)

    fun getGeminiApiKey(): String? = getDecryptedString(KEY_ENCRYPTED_GEMINI)

    fun hasGeminiApiKey(): Boolean = !getGeminiApiKey().isNullOrBlank()

    fun getMaskedGeminiApiKey(): String = maskSecret(getGeminiApiKey())

    fun clearGeminiApiKey() {
        prefs.edit().remove(KEY_ENCRYPTED_GEMINI).apply()
    }

    // --- Optional ElevenLabs API Key & Voice ID ---

    fun saveElevenLabsApiKey(apiKey: String) = saveEncryptedString(KEY_ENCRYPTED_ELEVENLABS_KEY, apiKey)

    fun getElevenLabsApiKey(): String? = getDecryptedString(KEY_ENCRYPTED_ELEVENLABS_KEY)

    fun hasElevenLabsApiKey(): Boolean = !getElevenLabsApiKey().isNullOrBlank()

    fun getMaskedElevenLabsApiKey(): String = maskSecret(getElevenLabsApiKey())

    fun clearElevenLabsApiKey() {
        prefs.edit().remove(KEY_ENCRYPTED_ELEVENLABS_KEY).apply()
    }

    fun saveElevenLabsVoiceId(voiceId: String) = saveEncryptedString(KEY_ENCRYPTED_ELEVENLABS_VOICE, voiceId)

    fun getElevenLabsVoiceId(): String = getDecryptedString(KEY_ENCRYPTED_ELEVENLABS_VOICE).orEmpty()

    fun hasElevenLabsVoiceId(): Boolean = getElevenLabsVoiceId().isNotBlank()

    fun clearElevenLabsVoiceId() {
        prefs.edit().remove(KEY_ENCRYPTED_ELEVENLABS_VOICE).apply()
    }

    fun clearElevenLabsAll() {
        prefs.edit()
            .remove(KEY_ENCRYPTED_ELEVENLABS_KEY)
            .remove(KEY_ENCRYPTED_ELEVENLABS_VOICE)
            .apply()
    }

    private fun maskSecret(secret: String?): String {
        val value = secret ?: return ""
        return if (value.length <= 8) "••••••••" else "${value.take(4)}••••${value.takeLast(4)}"
    }

    private fun saveEncryptedString(prefKey: String, rawValue: String) {
        val trimmed = rawValue.trim()
        if (trimmed.isEmpty()) {
            prefs.edit().remove(prefKey).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(1 + iv.size + ciphertext.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(ciphertext, 0, combined, 1 + iv.size, ciphertext.size)
        prefs.edit()
            .putString(prefKey, Base64.encodeToString(combined, Base64.NO_WRAP))
            .apply()
    }

    private fun getDecryptedString(prefKey: String): String? {
        val encoded = prefs.getString(prefKey, null) ?: return null
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            val ivSize = combined[0].toInt() and 0xFF
            val iv = combined.copyOfRange(1, 1 + ivSize)
            val ciphertext = combined.copyOfRange(1 + ivSize, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "aqua_ai_secure_config_aes"
        const val PREFS_NAME = "aqua_secure_config_prefs"
        const val KEY_ENCRYPTED_GEMINI = "enc_gemini_api_key"
        const val KEY_ENCRYPTED_ELEVENLABS_KEY = "enc_elevenlabs_api_key"
        const val KEY_ENCRYPTED_ELEVENLABS_VOICE = "enc_elevenlabs_voice_id"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
