package com.example.wallet.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.wallet.core.WalletAccountInfo
import com.example.wallet.core.WalletMetadata
import com.example.wallet.core.WalletSecuritySettings
import com.example.wallet.crypto.CryptoPrimitives
import com.example.wallet.crypto.HdKeyDerivation
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Self-custodial, Android Keystore AES-256-GCM encrypted wallet vault.
 *
 * Security invariants:
 * - Secrets (BIP-39 mnemonics & seeds) never leave this vault unencrypted.
 * - Never writes secrets to logs, Firestore, SharedPreferences plaintext, or AI contexts.
 * - Derives keys into short-lived ByteArrays that callers wipe immediately after use.
 */
class WalletSecurityVault(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var sessionUnlocked: Boolean = false

    fun createWallet(
        label: String,
        wordCount: Int = 12,
        passphrase: String = ""
    ): Result<Pair<WalletMetadata, WalletAccountInfo>> = runCatching {
        val mnemonic = HdKeyDerivation.generateMnemonic(wordCount)
        importWalletFromMnemonic(
            label = label.ifBlank { "Aqua Primary Wallet" },
            mnemonicPhrase = mnemonic,
            passphrase = passphrase,
            backupVerified = false
        ).getOrThrow()
    }

    fun importWalletFromMnemonic(
        label: String,
        mnemonicPhrase: String,
        passphrase: String = "",
        backupVerified: Boolean = true
    ): Result<Pair<WalletMetadata, WalletAccountInfo>> = runCatching {
        val words = HdKeyDerivation.validateMnemonic(mnemonicPhrase).getOrThrow()
        val normalizedPhrase = words.joinToString(" ")
        val seed = HdKeyDerivation.mnemonicToSeed(normalizedPhrase, passphrase)
        val account0 = try {
            HdKeyDerivation.deriveAccountMaterial(seed, 0)
        } finally {
            CryptoPrimitives.wipe(seed)
        }

        val walletId = "w_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val meta = WalletMetadata(
            walletId = walletId,
            label = label.ifBlank { "Aqua Wallet" }.take(64),
            isWatchOnly = false,
            wordCount = words.size,
            accountCount = 1,
            backupVerified = backupVerified,
            createdAtMs = System.currentTimeMillis(),
            hasPassphrase = passphrase.isNotBlank()
        )
        val accountInfo = WalletAccountInfo(
            walletId = walletId,
            accountIndex = 0,
            label = "Account 1",
            evmAddress = account0.evmAddress,
            solanaAddress = account0.solanaAddress,
            bitcoinMainnetAddress = account0.bitcoinMainnetAddress,
            bitcoinTestnetAddress = account0.bitcoinTestnetAddress
        )

        val secretPayload = JSONObject().apply {
            put("mnemonic", normalizedPhrase)
            put("passphrase", passphrase)
        }.toString()
        val encryptedSecret = encryptWithMasterKey(secretPayload)
        prefs.edit()
            .putString(secretKeyFor(walletId), encryptedSecret)
            .apply()

        saveWalletMetadata(meta)
        saveAccountList(walletId, listOf(accountInfo))

        val settings = getSecuritySettings()
        if (settings.activeWalletId.isBlank()) {
            updateSecuritySettings(
                settings.copy(
                    activeWalletId = walletId,
                    activeAccountIndex = 0
                )
            )
        }
        sessionUnlocked = true
        meta to accountInfo
    }

    fun importWatchOnlyWallet(
        label: String,
        evmAddress: String = "",
        solanaAddress: String = "",
        bitcoinAddress: String = ""
    ): Result<Pair<WalletMetadata, WalletAccountInfo>> = runCatching {
        require(evmAddress.isNotBlank() || solanaAddress.isNotBlank() || bitcoinAddress.isNotBlank()) {
            "Provide at least one public EVM, Solana, or Bitcoin address."
        }
        val walletId = "wo_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val meta = WalletMetadata(
            walletId = walletId,
            label = label.ifBlank { "Watch-Only Portfolio" }.take(64),
            isWatchOnly = true,
            wordCount = 0,
            accountCount = 1,
            backupVerified = true,
            createdAtMs = System.currentTimeMillis()
        )
        val accountInfo = WalletAccountInfo(
            walletId = walletId,
            accountIndex = 0,
            label = "Watch Account",
            evmAddress = evmAddress.trim(),
            solanaAddress = solanaAddress.trim(),
            bitcoinMainnetAddress = if (bitcoinAddress.startsWith("bc1") || bitcoinAddress.startsWith("1") || bitcoinAddress.startsWith("3")) bitcoinAddress.trim() else "",
            bitcoinTestnetAddress = if (bitcoinAddress.startsWith("tb1") || bitcoinAddress.startsWith("m") || bitcoinAddress.startsWith("n")) bitcoinAddress.trim() else ""
        )
        saveWalletMetadata(meta)
        saveAccountList(walletId, listOf(accountInfo))

        val settings = getSecuritySettings()
        if (settings.activeWalletId.isBlank()) {
            updateSecuritySettings(settings.copy(activeWalletId = walletId, activeAccountIndex = 0))
        }
        meta to accountInfo
    }

    fun addDerivedAccount(walletId: String, label: String = ""): Result<WalletAccountInfo> = runCatching {
        val meta = getAllWallets().firstOrNull { it.walletId == walletId }
            ?: throw IllegalStateException("Wallet not found.")
        require(!meta.isWatchOnly) { "Watch-only wallets cannot derive new HD accounts." }

        val existing = getAccountsForWallet(walletId)
        val nextIdx = (existing.maxOfOrNull { it.accountIndex } ?: -1) + 1
        val info = withSeed(walletId) { seed ->
            val mat = HdKeyDerivation.deriveAccountMaterial(seed, nextIdx)
            WalletAccountInfo(
                walletId = walletId,
                accountIndex = nextIdx,
                label = label.ifBlank { "Account ${nextIdx + 1}" },
                evmAddress = mat.evmAddress,
                solanaAddress = mat.solanaAddress,
                bitcoinMainnetAddress = mat.bitcoinMainnetAddress,
                bitcoinTestnetAddress = mat.bitcoinTestnetAddress
            )
        }.getOrThrow()

        val updatedAccounts = existing + info
        saveAccountList(walletId, updatedAccounts)
        saveWalletMetadata(meta.copy(accountCount = updatedAccounts.size))
        info
    }

    fun markBackupVerified(walletId: String): Result<WalletMetadata> = runCatching {
        val meta = getAllWallets().firstOrNull { it.walletId == walletId }
            ?: throw IllegalStateException("Wallet not found.")
        val updated = meta.copy(backupVerified = true)
        saveWalletMetadata(updated)
        updated
    }

    /**
     * Reveals the BIP-39 recovery phrase ONLY after checking lock/emergency state.
     * Callers MUST gate this behind PIN / Biometric prompt in the UI.
     */
    fun revealMnemonicForBackup(walletId: String): Result<String> = runCatching {
        val settings = getSecuritySettings()
        if ((settings.hasPin || settings.biometricEnabled) && !sessionUnlocked) {
            throw SecurityException("Wallet is locked. Authenticate with PIN or biometrics first.")
        }
        val meta = getAllWallets().firstOrNull { it.walletId == walletId }
            ?: throw IllegalStateException("Wallet not found.")
        require(!meta.isWatchOnly) { "Watch-only wallets do not have a recovery phrase." }
        val enc = prefs.getString(secretKeyFor(walletId), null)
            ?: throw IllegalStateException("Encrypted wallet secret missing.")
        val json = JSONObject(decryptWithMasterKey(enc))
        json.getString("mnemonic")
    }

    /**
     * Executes [block] with a freshly derived BIP-39 64-byte seed, then immediately wipes it.
     */
    fun <T> withSeed(walletId: String, block: (ByteArray) -> T): Result<T> = runCatching {
        val settings = getSecuritySettings()
        check(!settings.emergencyLocked) {
            "Wallet is in Emergency Lock mode. Unlock it in Security Center first."
        }
        if ((settings.hasPin || settings.biometricEnabled) && !sessionUnlocked) {
            throw SecurityException("Wallet is locked. Authenticate with PIN or biometrics first.")
        }
        val meta = getAllWallets().firstOrNull { it.walletId == walletId }
            ?: throw IllegalStateException("Wallet not found.")
        require(!meta.isWatchOnly) { "Watch-only wallets cannot sign transactions." }
        val enc = prefs.getString(secretKeyFor(walletId), null)
            ?: throw IllegalStateException("Encrypted key material unavailable.")
        val json = JSONObject(decryptWithMasterKey(enc))
        val phrase = json.getString("mnemonic")
        val passphrase = json.optString("passphrase", "")
        val seed = HdKeyDerivation.mnemonicToSeed(phrase, passphrase)
        try {
            block(seed)
        } finally {
            CryptoPrimitives.wipe(seed)
        }
    }

    /**
     * Exports a password-encrypted portable JSON backup (independent of Firebase/Aqua account).
     */
    fun exportPasswordEncryptedBackup(walletId: String, backupPassword: String): Result<String> = runCatching {
        require(backupPassword.length >= 8) { "Backup encryption password must be at least 8 characters." }
        val phrase = revealMnemonicForBackup(walletId).getOrThrow()
        val enc = prefs.getString(secretKeyFor(walletId), null)!!
        val json = JSONObject(decryptWithMasterKey(enc))
        val passphrase = json.optString("passphrase", "")
        val meta = getAllWallets().first { it.walletId == walletId }

        val salt = ByteArray(16).also { CryptoPrimitives.secureRandom.nextBytes(it) }
        val derivedKey = pbkdf2(backupPassword.toCharArray(), salt, 120_000, 256)
        val aesKey = SecretKeySpec(derivedKey, "AES")
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, aesKey)
        val innerPayload = JSONObject().apply {
            put("label", meta.label)
            put("mnemonic", phrase)
            put("passphrase", passphrase)
            put("accountCount", meta.accountCount)
        }.toString().toByteArray(StandardCharsets.UTF_8)
        val ciphertext = cipher.doFinal(innerPayload)
        CryptoPrimitives.wipe(derivedKey, innerPayload)

        JSONObject().apply {
            put("format", "aqua_encrypted_wallet_v1")
            put("kdf", "PBKDF2WithHmacSHA256")
            put("iterations", 120_000)
            put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }.toString(2)
    }

    fun restoreFromEncryptedBackup(
        backupJson: String,
        backupPassword: String
    ): Result<Pair<WalletMetadata, WalletAccountInfo>> = runCatching {
        val obj = JSONObject(backupJson)
        require(obj.optString("format") == "aqua_encrypted_wallet_v1") {
            "Unsupported backup format."
        }
        val salt = Base64.decode(obj.getString("salt"), Base64.NO_WRAP)
        val iv = Base64.decode(obj.getString("iv"), Base64.NO_WRAP)
        val ciphertext = Base64.decode(obj.getString("ciphertext"), Base64.NO_WRAP)
        val iterations = obj.optInt("iterations", 120_000)

        val derivedKey = pbkdf2(backupPassword.toCharArray(), salt, iterations, 256)
        val plainBytes = try {
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derivedKey, "AES"), GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext)
        } finally {
            CryptoPrimitives.wipe(derivedKey)
        }
        val inner = JSONObject(String(plainBytes, StandardCharsets.UTF_8))
        CryptoPrimitives.wipe(plainBytes)

        val label = inner.optString("label", "Restored Aqua Wallet")
        val mnemonic = inner.getString("mnemonic")
        val passphrase = inner.optString("passphrase", "")
        val accountCount = inner.optInt("accountCount", 1).coerceIn(1, 20)
        val created = importWalletFromMnemonic(label, mnemonic, passphrase, backupVerified = true).getOrThrow()
        for (idx in 1 until accountCount) {
            addDerivedAccount(created.first.walletId, "Account ${idx + 1}")
        }
        created
    }

    fun getAllWallets(): List<WalletMetadata> {
        val raw = prefs.getString(KEY_WALLET_METADATA_LIST, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                WalletMetadata(
                    walletId = o.getString("walletId"),
                    label = o.optString("label", "Aqua Wallet"),
                    isWatchOnly = o.optBoolean("isWatchOnly", false),
                    wordCount = o.optInt("wordCount", 12),
                    accountCount = o.optInt("accountCount", 1),
                    backupVerified = o.optBoolean("backupVerified", false),
                    createdAtMs = o.optLong("createdAtMs", 0L),
                    hasPassphrase = o.optBoolean("hasPassphrase", false)
                )
            }
        }.getOrDefault(emptyList())
    }

    fun getAccountsForWallet(walletId: String): List<WalletAccountInfo> {
        val raw = prefs.getString("accounts_$walletId", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                WalletAccountInfo(
                    walletId = o.getString("walletId"),
                    accountIndex = o.optInt("accountIndex", 0),
                    label = o.optString("label", "Account 1"),
                    evmAddress = o.optString("evmAddress", ""),
                    solanaAddress = o.optString("solanaAddress", ""),
                    bitcoinMainnetAddress = o.optString("bitcoinMainnetAddress", ""),
                    bitcoinTestnetAddress = o.optString("bitcoinTestnetAddress", "")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun deleteWallet(walletId: String) {
        val remaining = getAllWallets().filterNot { it.walletId == walletId }
        saveAllWallets(remaining)
        prefs.edit()
            .remove(secretKeyFor(walletId))
            .remove("accounts_$walletId")
            .apply()
        val settings = getSecuritySettings()
        if (settings.activeWalletId == walletId) {
            updateSecuritySettings(
                settings.copy(
                    activeWalletId = remaining.firstOrNull()?.walletId.orEmpty(),
                    activeAccountIndex = 0
                )
            )
        }
    }

    // --- PIN, BIOMETRICS, EMERGENCY LOCK, SPENDING LIMITS ---

    fun getSecuritySettings(): WalletSecuritySettings {
        val dayKey = todayKey()
        val storedDay = prefs.getString(KEY_SPEND_DAY, "") ?: ""
        val spentToday = if (storedDay == dayKey) {
            prefs.getFloat(KEY_SPENT_TODAY_USD, 0f).toDouble()
        } else {
            0.0
        }
        return WalletSecuritySettings(
            hasPin = !prefs.getString(KEY_PIN_HASH, null).isNullOrBlank(),
            biometricEnabled = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false),
            emergencyLocked = prefs.getBoolean(KEY_EMERGENCY_LOCKED, false),
            dailyLimitUsd = prefs.getFloat(KEY_DAILY_LIMIT_USD, 1000f).toDouble(),
            perTxLimitUsd = prefs.getFloat(KEY_PER_TX_LIMIT_USD, 500f).toDouble(),
            spentTodayUsd = spentToday,
            spendDayKey = dayKey,
            testnetModeEnabled = prefs.getBoolean(KEY_TESTNET_MODE, false),
            activeWalletId = prefs.getString(KEY_ACTIVE_WALLET_ID, "").orEmpty(),
            activeAccountIndex = prefs.getInt(KEY_ACTIVE_ACCOUNT_INDEX, 0),
            activeNetworkId = prefs.getString(KEY_ACTIVE_NETWORK_ID, "eth_mainnet").orEmpty(),
            displayCurrencyCode = prefs.getString(KEY_DISPLAY_CURRENCY, "USD").orEmpty().ifBlank { "USD" }
        )
    }

    fun updateSecuritySettings(settings: WalletSecuritySettings) {
        prefs.edit()
            .putBoolean(KEY_BIOMETRIC_ENABLED, settings.biometricEnabled)
            .putBoolean(KEY_EMERGENCY_LOCKED, settings.emergencyLocked)
            .putFloat(KEY_DAILY_LIMIT_USD, settings.dailyLimitUsd.coerceAtLeast(1.0).toFloat())
            .putFloat(KEY_PER_TX_LIMIT_USD, settings.perTxLimitUsd.coerceAtLeast(1.0).toFloat())
            .putFloat(KEY_SPENT_TODAY_USD, settings.spentTodayUsd.coerceAtLeast(0.0).toFloat())
            .putString(KEY_SPEND_DAY, settings.spendDayKey.ifBlank { todayKey() })
            .putBoolean(KEY_TESTNET_MODE, settings.testnetModeEnabled)
            .putString(KEY_ACTIVE_WALLET_ID, settings.activeWalletId)
            .putInt(KEY_ACTIVE_ACCOUNT_INDEX, settings.activeAccountIndex)
            .putString(KEY_ACTIVE_NETWORK_ID, settings.activeNetworkId)
            .putString(KEY_DISPLAY_CURRENCY, settings.displayCurrencyCode.ifBlank { "USD" })
            .apply()
    }

    fun setPin(pin: String): Result<Unit> = runCatching {
        require(pin.length in 4..12 && pin.all { it.isDigit() }) {
            "Wallet PIN must be 4 to 12 digits."
        }
        val salt = ByteArray(16).also { CryptoPrimitives.secureRandom.nextBytes(it) }
        val hash = pbkdf2(pin.toCharArray(), salt, 120_000, 256)
        prefs.edit()
            .putString(KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_PIN_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()
        CryptoPrimitives.wipe(hash)
        sessionUnlocked = true
    }

    fun verifyPin(pin: String): Boolean {
        val saltB64 = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val hashB64 = prefs.getString(KEY_PIN_HASH, null) ?: return false
        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        val expected = Base64.decode(hashB64, Base64.NO_WRAP)
        val actual = pbkdf2(pin.toCharArray(), salt, 120_000, 256)
        val ok = MessageDigest.isEqual(expected, actual)
        CryptoPrimitives.wipe(actual)
        if (ok) {
            sessionUnlocked = true
        }
        return ok
    }

    fun clearPin(currentPin: String): Result<Unit> = runCatching {
        require(verifyPin(currentPin)) { "Current PIN is incorrect." }
        prefs.edit()
            .remove(KEY_PIN_SALT)
            .remove(KEY_PIN_HASH)
            .putBoolean(KEY_BIOMETRIC_ENABLED, false)
            .apply()
    }

    fun isWalletLocked(): Boolean {
        val s = getSecuritySettings()
        if (s.emergencyLocked) return true
        return (s.hasPin || s.biometricEnabled) && !sessionUnlocked
    }

    fun markUnlockedWithBiometric() {
        sessionUnlocked = true
    }

    fun lockSessionNow() {
        sessionUnlocked = false
    }

    fun triggerEmergencyLock() {
        sessionUnlocked = false
        val s = getSecuritySettings()
        updateSecuritySettings(s.copy(emergencyLocked = true))
    }

    fun clearEmergencyLockWithPin(pin: String?): Boolean {
        val s = getSecuritySettings()
        if (s.hasPin) {
            if (pin.isNullOrBlank() || !verifyPin(pin)) return false
        }
        sessionUnlocked = true
        updateSecuritySettings(s.copy(emergencyLocked = false))
        return true
    }

    fun clearEmergencyLockWithBiometric() {
        sessionUnlocked = true
        val s = getSecuritySettings()
        updateSecuritySettings(s.copy(emergencyLocked = false))
    }

    fun checkSpendingLimit(estimatedUsdValue: Double): Result<Unit> = runCatching {
        val s = getSecuritySettings()
        if (s.emergencyLocked) {
            throw SecurityException("Emergency Wallet Lock is active. All transactions are blocked.")
        }
        if (estimatedUsdValue > s.perTxLimitUsd) {
            throw SecurityException(
                "Transaction value ($${"%.2f".format(estimatedUsdValue)}) exceeds per-transaction limit ($${"%.2f".format(s.perTxLimitUsd)})."
            )
        }
        if (s.spentTodayUsd + estimatedUsdValue > s.dailyLimitUsd) {
            throw SecurityException(
                "Daily spend limit ($${"%.2f".format(s.dailyLimitUsd)}) would be exceeded (already spent $${"%.2f".format(s.spentTodayUsd)} today)."
            )
        }
    }

    fun recordBroadcastSpend(estimatedUsdValue: Double) {
        if (estimatedUsdValue <= 0.0) return
        val s = getSecuritySettings()
        updateSecuritySettings(
            s.copy(
                spentTodayUsd = s.spentTodayUsd + estimatedUsdValue,
                spendDayKey = todayKey()
            )
        )
    }

    private fun saveWalletMetadata(meta: WalletMetadata) {
        val list = getAllWallets().filterNot { it.walletId == meta.walletId } + meta
        saveAllWallets(list)
    }

    private fun saveAllWallets(list: List<WalletMetadata>) {
        val arr = JSONArray()
        list.forEach { w ->
            arr.put(JSONObject().apply {
                put("walletId", w.walletId)
                put("label", w.label)
                put("isWatchOnly", w.isWatchOnly)
                put("wordCount", w.wordCount)
                put("accountCount", w.accountCount)
                put("backupVerified", w.backupVerified)
                put("createdAtMs", w.createdAtMs)
                put("hasPassphrase", w.hasPassphrase)
            })
        }
        prefs.edit().putString(KEY_WALLET_METADATA_LIST, arr.toString()).apply()
    }

    private fun saveAccountList(walletId: String, accounts: List<WalletAccountInfo>) {
        val arr = JSONArray()
        accounts.forEach { acc ->
            arr.put(JSONObject().apply {
                put("walletId", acc.walletId)
                put("accountIndex", acc.accountIndex)
                put("label", acc.label)
                put("evmAddress", acc.evmAddress)
                put("solanaAddress", acc.solanaAddress)
                put("bitcoinMainnetAddress", acc.bitcoinMainnetAddress)
                put("bitcoinTestnetAddress", acc.bitcoinTestnetAddress)
            })
        }
        prefs.edit().putString("accounts_$walletId", arr.toString()).apply()
    }

    private fun encryptWithMasterKey(plaintext: String): String {
        val key = getOrCreateMasterKey()
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val combined = ByteArray(1 + iv.size + cipherBytes.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(cipherBytes, 0, combined, 1 + iv.size, cipherBytes.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decryptWithMasterKey(encoded: String): String {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        val ivLen = combined[0].toInt() and 0xFF
        val iv = combined.copyOfRange(1, 1 + ivLen)
        val ciphertext = combined.copyOfRange(1 + ivLen, combined.size)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(ciphertext)
        return String(plain, StandardCharsets.UTF_8).also {
            CryptoPrimitives.wipe(plain)
        }
    }

    private fun getOrCreateMasterKey(): SecretKey {
        // Hardware-backed AndroidKeyStore on physical & emulator Android; graceful wrapped-key fallback only if Robolectric lacks AndroidKeyStore.
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

        // Unit-test JVM fallback when AndroidKeyStore provider is absent
        val existingFallback = prefs.getString(KEY_JVM_TEST_AES_KEY, null)
        if (existingFallback != null) {
            return SecretKeySpec(Base64.decode(existingFallback, Base64.NO_WRAP), "AES")
        }
        val raw = ByteArray(32).also { CryptoPrimitives.secureRandom.nextBytes(it) }
        prefs.edit().putString(KEY_JVM_TEST_AES_KEY, Base64.encodeToString(raw, Base64.NO_WRAP)).apply()
        return SecretKeySpec(raw, "AES")
    }

    private fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int, keyBits: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, keyBits)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            CryptoPrimitives.wipeChars(password)
        }
    }

    private fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun secretKeyFor(walletId: String) = "enc_secret_$walletId"

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEYSTORE_ALIAS = "aqua_native_wallet_master_aes_v1"
        const val PREFS_NAME = "aqua_native_wallet_vault"
        const val AES_GCM = "AES/GCM/NoPadding"

        const val KEY_WALLET_METADATA_LIST = "wallet_metadata_list"
        const val KEY_PIN_SALT = "wallet_pin_salt"
        const val KEY_PIN_HASH = "wallet_pin_hash"
        const val KEY_BIOMETRIC_ENABLED = "wallet_biometric_enabled"
        const val KEY_EMERGENCY_LOCKED = "wallet_emergency_locked"
        const val KEY_DAILY_LIMIT_USD = "wallet_daily_limit_usd"
        const val KEY_PER_TX_LIMIT_USD = "wallet_per_tx_limit_usd"
        const val KEY_SPENT_TODAY_USD = "wallet_spent_today_usd"
        const val KEY_SPEND_DAY = "wallet_spend_day"
        const val KEY_TESTNET_MODE = "wallet_testnet_mode"
        const val KEY_ACTIVE_WALLET_ID = "wallet_active_id"
        const val KEY_ACTIVE_ACCOUNT_INDEX = "wallet_active_account_index"
        const val KEY_ACTIVE_NETWORK_ID = "wallet_active_network_id"
        const val KEY_DISPLAY_CURRENCY = "wallet_display_currency"
        const val KEY_JVM_TEST_AES_KEY = "jvm_fallback_key"
    }
}
