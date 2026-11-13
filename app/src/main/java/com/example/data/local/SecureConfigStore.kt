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
 * user-supplied configuration (such as an optional personal Gemini API key).
 * Never stores wallet private keys or seed phrases. Never logs secrets.
 */
class SecureConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun saveGeminiApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) {
            clearGeminiApiKey()
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
            .putString(KEY_ENCRYPTED_GEMINI, Base64.encodeToString(combined, Base64.NO_WRAP))
            .apply()
    }

    fun getGeminiApiKey(): String? {
        val encoded = prefs.getString(KEY_ENCRYPTED_GEMINI, null) ?: return null
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

    fun hasGeminiApiKey(): Boolean = !getGeminiApiKey().isNullOrBlank()

    fun getMaskedGeminiApiKey(): String {
        val key = getGeminiApiKey() ?: return ""
        return if (key.length <= 8) "••••••••" else "${key.take(4)}••••${key.takeLast(4)}"
    }

    fun clearGeminiApiKey() {
        prefs.edit().remove(KEY_ENCRYPTED_GEMINI).apply()
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
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
