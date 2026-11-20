package com.example.core.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Generates and manages the 32-byte SQLCipher database encryption passphrase,
 * wrapped using an Android Keystore AES-256-GCM Master Key.
 */
object AquaDatabaseKeyManager {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEYSTORE_ALIAS = "aqua_ssot_db_master_v1"
    private const val PREFS_NAME = "aqua_db_key_prefs"
    private const val KEY_WRAPPED_DEK = "wrapped_db_dek_v1"
    private const val KEY_JVM_TEST_AES_KEY = "jvm_unit_test_aes_key"
    private const val AES_GCM = "AES/GCM/NoPadding"

    private val secureRandom = SecureRandom()

    fun isRobolectricEnvironment(): Boolean = Build.FINGERPRINT.equals("robolectric", ignoreCase = true)

    fun isSqlCipherNativeAvailable(): Boolean {
        if (isRobolectricEnvironment()) return false
        return try {
            System.loadLibrary("sqlcipher")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    /**
     * Returns a newly copied 32-byte database encryption key.
     * Callers MUST zeroize this ByteArray cleanly when no longer needed.
     */
    fun getOrCreateDatabasePassphrase(context: Context): ByteArray {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val wrapped = prefs.getString(KEY_WRAPPED_DEK, null)
        if (!wrapped.isNullOrBlank()) {
            val decrypted = runCatching { decryptWithMasterKey(prefs, wrapped) }.getOrNull()
            if (decrypted != null && decrypted.size == 32) {
                return decrypted
            }
        }
        val dek = ByteArray(32).also { secureRandom.nextBytes(it) }
        val encodedWrapped = encryptWithMasterKey(prefs, dek)
        prefs.edit().putString(KEY_WRAPPED_DEK, encodedWrapped).commit()
        return dek
    }

    fun wipeKey(bytes: ByteArray) {
        bytes.fill(0)
    }

    private fun encryptWithMasterKey(
        prefs: android.content.SharedPreferences,
        plainBytes: ByteArray
    ): String {
        val key = getOrCreateMasterKey(prefs)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plainBytes)
        val combined = ByteArray(1 + iv.size + ciphertext.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(ciphertext, 0, combined, 1 + iv.size, ciphertext.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decryptWithMasterKey(
        prefs: android.content.SharedPreferences,
        encoded: String
    ): ByteArray {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        val ivLen = combined[0].toInt() and 0xFF
        val iv = combined.copyOfRange(1, 1 + ivLen)
        val ciphertext = combined.copyOfRange(1 + ivLen, combined.size)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(prefs), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateMasterKey(prefs: android.content.SharedPreferences): SecretKey {
        val ksResult = runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey
            if (existing != null) return@runCatching existing
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            gen.init(spec)
            gen.generateKey()
        }
        if (ksResult.isSuccess) return ksResult.getOrThrow()

        if (!isRobolectricEnvironment()) {
            throw SecurityException("AndroidKeyStore unavailable for database encryption key.", ksResult.exceptionOrNull())
        }
        val existingFallback = prefs.getString(KEY_JVM_TEST_AES_KEY, null)
        if (existingFallback != null) {
            return SecretKeySpec(Base64.decode(existingFallback, Base64.NO_WRAP), "AES")
        }
        val raw = ByteArray(32).also { secureRandom.nextBytes(it) }
        prefs.edit().putString(KEY_JVM_TEST_AES_KEY, Base64.encodeToString(raw, Base64.NO_WRAP)).commit()
        return SecretKeySpec(raw, "AES")
    }
}
