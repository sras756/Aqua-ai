package com.example.wallet.core

import com.example.data.model.WalletConfig
import com.example.data.repository.AquaFirestoreRepository
import com.example.wallet.ai.AquaAiWalletBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Coordinates native multi-chain wallet state, RPC/blockchain refreshes,
 * security lock/limits, backup/recovery flows, and non-secret cloud metadata sync.
 */
class AquaWalletCoordinator(
    val engine: AquaWalletEngine,
    private val firestoreRepo: AquaFirestoreRepository,
    private val scope: CoroutineScope,
    private val onBanner: (String) -> Unit
) {
    val aiBridge = AquaAiWalletBridge(engine)
    val tools = WalletToolsCoordinator(
        engine = engine,
        scope = scope,
        activeWalletMeta = ::activeWalletMeta,
        activeAccountInfo = ::activeAccountInfo,
        activeNetworkConfig = ::activeNetworkConfig,
        unlockVaultIfNeeded = ::unlockVaultIfNeeded,
        setPreparedTxForSigning = { _preparedTx.value = it },
        onBanner = onBanner
    )

    private val _wallets = MutableStateFlow<List<WalletMetadata>>(emptyList())
    val wallets: StateFlow<List<WalletMetadata>> = _wallets.asStateFlow()

    private val _accounts = MutableStateFlow<List<WalletAccountInfo>>(emptyList())
    val accounts: StateFlow<List<WalletAccountInfo>> = _accounts.asStateFlow()

    private val _securitySettings = MutableStateFlow(engine.vault.getSecuritySettings())
    val securitySettings: StateFlow<WalletSecuritySettings> = _securitySettings.asStateFlow()

    private val _isLocked = MutableStateFlow(engine.vault.isWalletLocked())
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    private val _availableNetworks = MutableStateFlow<List<NetworkConfig>>(emptyList())
    val availableNetworks: StateFlow<List<NetworkConfig>> = _availableNetworks.asStateFlow()

    private val _tokensForActiveNetwork = MutableStateFlow<List<TokenDefinition>>(emptyList())
    val tokensForActiveNetwork: StateFlow<List<TokenDefinition>> = _tokensForActiveNetwork.asStateFlow()

    private val _portfolioSummary = MutableStateFlow<PortfolioSummary?>(null)
    val portfolioSummary: StateFlow<PortfolioSummary?> = _portfolioSummary.asStateFlow()

    private val _transactions = MutableStateFlow<List<WalletTxRecord>>(emptyList())
    val transactions: StateFlow<List<WalletTxRecord>> = _transactions.asStateFlow()

    private val _preparedTx = MutableStateFlow<PreparedChainTransaction?>(null)
    val preparedTx: StateFlow<PreparedChainTransaction?> = _preparedTx.asStateFlow()

    private val _feeEstimate = MutableStateFlow<FeeEstimate?>(null)
    val feeEstimate: StateFlow<FeeEstimate?> = _feeEstimate.asStateFlow()

    private val _riskReport = MutableStateFlow<AddressRiskReport?>(null)
    val riskReport: StateFlow<AddressRiskReport?> = _riskReport.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _revealedMnemonic = MutableStateFlow<String?>(null)
    val revealedMnemonic: StateFlow<String?> = _revealedMnemonic.asStateFlow()

    private val _exportedBackupJson = MutableStateFlow<String?>(null)
    val exportedBackupJson: StateFlow<String?> = _exportedBackupJson.asStateFlow()

    private var txObserveJob: Job? = null

    init {
        scope.launch { reloadAll() }
    }

    fun activeWalletMeta(): WalletMetadata? {
        val id = _securitySettings.value.activeWalletId
        val list = _wallets.value
        return list.firstOrNull { it.walletId == id } ?: list.firstOrNull()
    }

    fun activeAccountInfo(): WalletAccountInfo? {
        val idx = _securitySettings.value.activeAccountIndex
        val list = _accounts.value
        return list.firstOrNull { it.accountIndex == idx } ?: list.firstOrNull()
    }

    fun activeNetworkConfig(): NetworkConfig {
        val id = _securitySettings.value.activeNetworkId
        val list = _availableNetworks.value
        return list.firstOrNull { it.id == id }
            ?: list.firstOrNull()
            ?: DefaultWalletRegistry.networks.first()
    }

    suspend fun reloadAll() {
        val sec = engine.vault.getSecuritySettings()
        val allWallets = engine.vault.getAllWallets()
        val resolvedWallet = allWallets.firstOrNull { it.walletId == sec.activeWalletId } ?: allWallets.firstOrNull()
        val accs = resolvedWallet?.let { engine.vault.getAccountsForWallet(it.walletId) }.orEmpty()
        val nets = engine.getAvailableNetworks(sec.testnetModeEnabled)
        val activeNet = nets.firstOrNull { it.id == sec.activeNetworkId } ?: nets.firstOrNull()
        val tokens = activeNet?.let { engine.getTokensForNetwork(it.id) }.orEmpty()

        _securitySettings.value = sec
        _isLocked.value = engine.vault.isWalletLocked()
        _wallets.value = allWallets
        _accounts.value = accs
        _availableNetworks.value = nets
        _tokensForActiveNetwork.value = tokens

        observeTransactionsFor(resolvedWallet?.walletId)
    }

    private fun observeTransactionsFor(walletId: String?) {
        txObserveJob?.cancel()
        if (walletId.isNullOrBlank()) {
            _transactions.value = emptyList()
            return
        }
        txObserveJob = scope.launch {
            engine.observeCachedTransactions(walletId).collect { list ->
                _transactions.value = list
            }
        }
    }

    fun createWallet(label: String, wordCount: Int, passphrase: String) {
        scope.launch {
            _busy.value = true
            engine.vault.createWallet(label, wordCount, passphrase)
                .onSuccess { (meta, acc) ->
                    selectWalletInternal(meta.walletId, acc.accountIndex)
                    reloadAll()
                    syncPublicMetadataOnly(meta, acc)
                    onBanner("Created ${meta.wordCount}-word Aqua Wallet '${meta.label}'. Verify your recovery backup.")
                    refreshPortfolio()
                }
                .onFailure { onBanner(it.localizedMessage ?: "Failed to create wallet.") }
            _busy.value = false
        }
    }

    fun importMnemonicWallet(label: String, phrase: String, passphrase: String) {
        scope.launch {
            _busy.value = true
            engine.vault.importWalletFromMnemonic(label, phrase, passphrase, backupVerified = true)
                .onSuccess { (meta, acc) ->
                    selectWalletInternal(meta.walletId, acc.accountIndex)
                    reloadAll()
                    syncPublicMetadataOnly(meta, acc)
                    onBanner("Imported '${meta.label}' across EVM, Solana & Bitcoin.")
                    refreshPortfolio()
                    refreshTransactions()
                }
                .onFailure { onBanner(it.localizedMessage ?: "Invalid recovery phrase.") }
            _busy.value = false
        }
    }

    fun importWatchOnlyWallet(label: String, evmAddr: String, solAddr: String, btcAddr: String) {
        scope.launch {
            _busy.value = true
            engine.vault.importWatchOnlyWallet(label, evmAddr, solAddr, btcAddr)
                .onSuccess { (meta, acc) ->
                    selectWalletInternal(meta.walletId, acc.accountIndex)
                    reloadAll()
                    syncPublicMetadataOnly(meta, acc)
                    onBanner("Added Watch-Only wallet '${meta.label}'.")
                    refreshPortfolio()
                    refreshTransactions()
                }
                .onFailure { onBanner(it.localizedMessage ?: "Failed to add watch-only wallet.") }
            _busy.value = false
        }
    }

    fun deriveNewAccount(label: String) {
        val meta = activeWalletMeta() ?: return
        scope.launch {
            _busy.value = true
            engine.vault.addDerivedAccount(meta.walletId, label)
                .onSuccess { newAcc ->
                    selectWalletInternal(meta.walletId, newAcc.accountIndex)
                    reloadAll()
                    syncPublicMetadataOnly(meta, newAcc)
                    onBanner("Derived ${newAcc.label} (index #${newAcc.accountIndex}).")
                    refreshPortfolio()
                }
                .onFailure { onBanner(it.localizedMessage ?: "Unlock wallet first to derive a new account.") }
            _busy.value = false
        }
    }

    fun selectWallet(walletId: String) {
        scope.launch {
            selectWalletInternal(walletId, 0)
            _preparedTx.value = null
            _portfolioSummary.value = null
            reloadAll()
            refreshPortfolio()
            refreshTransactions()
        }
    }

    fun selectAccount(accountIndex: Int) {
        val meta = activeWalletMeta() ?: return
        scope.launch {
            selectWalletInternal(meta.walletId, accountIndex)
            _preparedTx.value = null
            reloadAll()
            activeAccountInfo()?.let { syncPublicMetadataOnly(meta, it) }
            refreshPortfolio()
        }
    }

    fun selectNetwork(networkId: String) {
        scope.launch {
            val s = engine.vault.getSecuritySettings()
            engine.vault.updateSecuritySettings(s.copy(activeNetworkId = networkId))
            _preparedTx.value = null
            _feeEstimate.value = null
            _riskReport.value = null
            reloadAll()
        }
    }

    fun toggleTestnetMode(enabled: Boolean) {
        scope.launch {
            val s = engine.vault.getSecuritySettings()
            val fallbackNet = if (enabled) s.activeNetworkId else {
                val mainNets = DefaultWalletRegistry.networks.filter { !it.isTestnet }
                if (mainNets.any { it.id == s.activeNetworkId }) s.activeNetworkId else "eth_mainnet"
            }
            engine.vault.updateSecuritySettings(
                s.copy(testnetModeEnabled = enabled, activeNetworkId = fallbackNet)
            )
            reloadAll()
            refreshPortfolio()
        }
    }

    fun deleteWallet(walletId: String) {
        scope.launch {
            engine.vault.deleteWallet(walletId)
            firestoreRepo.deleteWalletConfig(walletId)
            _portfolioSummary.value = null
            _preparedTx.value = null
            _revealedMnemonic.value = null
            reloadAll()
            onBanner("Wallet removed from this device vault.")
        }
    }

    fun addCustomToken(networkId: String, symbol: String, name: String, contractOrMint: String, decimals: Int) {
        scope.launch {
            engine.addCustomToken(networkId, symbol, name, contractOrMint, decimals)
                .onSuccess {
                    reloadAll()
                    onBanner("Added token ${it.symbol} on ${activeNetworkConfig().name}.")
                    refreshPortfolio()
                }
                .onFailure { onBanner(it.localizedMessage ?: "Failed to add custom token.") }
        }
    }

    fun addCustomEvmNetwork(
        name: String,
        nativeSymbol: String,
        chainId: Long,
        rpcUrl: String,
        explorerUrl: String,
        isTestnet: Boolean
    ) {
        scope.launch {
            engine.addCustomEvmNetwork(name, nativeSymbol, chainId, rpcUrl, explorerUrl, isTestnet)
                .onSuccess { net ->
                    if (isTestnet) {
                        val s = engine.vault.getSecuritySettings()
                        engine.vault.updateSecuritySettings(s.copy(testnetModeEnabled = true, activeNetworkId = net.id))
                    } else {
                        val s = engine.vault.getSecuritySettings()
                        engine.vault.updateSecuritySettings(s.copy(activeNetworkId = net.id))
                    }
                    reloadAll()
                    onBanner("Added custom EVM network '${net.name}' (chainId=${net.chainId}).")
                }
                .onFailure { onBanner(it.localizedMessage ?: "Invalid custom network config.") }
        }
    }

    fun refreshPortfolio() {
        val acc = activeAccountInfo() ?: return
        scope.launch {
            _busy.value = true
            runCatching {
                val summary = engine.fetchPortfolio(acc, _securitySettings.value.testnetModeEnabled)
                _portfolioSummary.value = summary
            }.onFailure { err ->
                onBanner("Portfolio refresh error: ${err.localizedMessage}")
            }
            _busy.value = false
        }
    }

    fun refreshTransactions() {
        val meta = activeWalletMeta() ?: return
        val acc = activeAccountInfo() ?: return
        scope.launch {
            _busy.value = true
            runCatching {
                engine.syncTransactionHistory(meta.walletId, acc, _securitySettings.value.testnetModeEnabled)
            }.onFailure { err ->
                onBanner("History sync error: ${err.localizedMessage}")
            }
            _busy.value = false
        }
    }

    fun refreshSingleTxStatus(tx: WalletTxRecord) {
        scope.launch {
            _busy.value = true
            engine.refreshSingleTxStatus(tx)
                .onSuccess { updated ->
                    onBanner("Tx ${updated.txHash.take(10)}... status: ${updated.status} (${updated.confirmations} conf)")
                }
                .onFailure { err ->
                    onBanner("Status check failed: ${err.localizedMessage}")
                }
            _busy.value = false
        }
    }

    fun estimateFee(networkId: String, tokenId: String, toAddress: String, amountText: String) {
        val acc = activeAccountInfo() ?: return
        scope.launch {
            val net = _availableNetworks.value.firstOrNull { it.id == networkId } ?: return@launch
            val tokens = engine.getTokensForNetwork(net.id)
            val tok = tokens.firstOrNull { it.id == tokenId } ?: tokens.firstOrNull() ?: return@launch
            val fromAddr = acc.addressFor(net)
            val dest = toAddress.trim().ifBlank { fromAddr }
            val amt = amountText.trim().toBigDecimalOrNull() ?: java.math.BigDecimal("0.001")
            engine.adapterFor(net.chainFamily)
                .estimateFee(fromAddr, dest, tok, amt, net)
                .onSuccess { est ->
                    val quotes = if (net.isTestnet) emptyMap() else engine.marketPriceService.fetchPricesUsd(listOf(net.coingeckoId))
                    val usd = quotes[net.coingeckoId]?.priceUsd
                    _feeEstimate.value = est.copy(estimatedFeeUsd = usd?.let { est.estimatedFeeDecimal.toDouble() * it })
                }
                .onFailure { err -> onBanner("Fee estimate error: ${err.localizedMessage}") }
        }
    }

    fun analyzeAddressRisk(networkId: String, toAddress: String) {
        val acc = activeAccountInfo() ?: return
        scope.launch {
            val net = _availableNetworks.value.firstOrNull { it.id == networkId } ?: return@launch
            val fromAddr = acc.addressFor(net)
            runCatching {
                engine.adapterFor(net.chainFamily).analyzeAddressRisk(fromAddr, toAddress.trim(), net)
            }.onSuccess { _riskReport.value = it }
                .onFailure { onBanner(it.localizedMessage ?: "Address risk inspection failed.") }
        }
    }

    fun prepareTransaction(networkId: String, tokenId: String, toAddress: String, amountText: String) {
        val meta = activeWalletMeta() ?: return
        val acc = activeAccountInfo() ?: return
        scope.launch {
            _busy.value = true
            _preparedTx.value = null
            val net = _availableNetworks.value.firstOrNull { it.id == networkId }
            val tokens = net?.let { engine.getTokensForNetwork(it.id) }.orEmpty()
            val tok = tokens.firstOrNull { it.id == tokenId } ?: tokens.firstOrNull()
            if (net == null || tok == null) {
                onBanner("Select a valid network and token.")
                _busy.value = false
                return@launch
            }
            engine.prepareTransaction(meta, acc, net, tok, toAddress, amountText)
                .onSuccess { prep ->
                    _preparedTx.value = prep
                    _feeEstimate.value = prep.feeEstimate
                    _riskReport.value = prep.riskReport
                }
                .onFailure { err ->
                    onBanner(err.localizedMessage ?: "Transaction preparation failed.")
                }
            _busy.value = false
        }
    }

    fun clearPreparedTx() {
        _preparedTx.value = null
    }

    fun signAndBroadcastPrepared(
        pinAttempt: String? = null,
        biometricVerified: Boolean = false,
        onComplete: (Result<WalletTxRecord>) -> Unit = {}
    ) {
        val prep = _preparedTx.value ?: return
        scope.launch {
            if (!unlockVaultIfNeeded(pinAttempt, biometricVerified)) {
                onBanner("Authentication required (valid PIN or Biometric) before signing.")
                return@launch
            }
            _busy.value = true
            val res = engine.signAndBroadcastPrepared(prep)
            res.onSuccess { record ->
                _preparedTx.value = null
                reloadAll()
                onBanner("Broadcast submitted (PENDING confirmation). Hash: ${record.txHash.take(14)}...")
                refreshPortfolio()
            }.onFailure { err ->
                onBanner("Signing/broadcast failed: ${err.localizedMessage}")
            }
            onComplete(res)
            _busy.value = false
        }
    }

    // --- SECURITY, LOCKING, RECOVERY & PORTABLE ENCRYPTED BACKUP ---

    fun unlockWithPin(pin: String): Boolean {
        val ok = engine.vault.verifyPin(pin)
        if (ok) {
            _isLocked.value = engine.vault.isWalletLocked()
        } else {
            onBanner("Incorrect Wallet PIN.")
        }
        return ok
    }

    fun unlockWithBiometric() {
        engine.vault.markUnlockedWithBiometric()
        _isLocked.value = engine.vault.isWalletLocked()
        onBanner("Wallet unlocked.")
    }

    fun lockNow() {
        engine.vault.lockSessionNow()
        _revealedMnemonic.value = null
        _exportedBackupJson.value = null
        _isLocked.value = engine.vault.isWalletLocked()
        onBanner("Wallet session locked.")
    }

    fun setPin(pin: String) {
        engine.vault.setPin(pin)
            .onSuccess {
                _securitySettings.value = engine.vault.getSecuritySettings()
                _isLocked.value = engine.vault.isWalletLocked()
                onBanner("Wallet PIN enabled (PBKDF2-SHA256 protected).")
            }
            .onFailure { onBanner(it.localizedMessage ?: "Invalid PIN.") }
    }

    fun clearPin(currentPin: String) {
        engine.vault.clearPin(currentPin)
            .onSuccess {
                _securitySettings.value = engine.vault.getSecuritySettings()
                _isLocked.value = engine.vault.isWalletLocked()
                onBanner("Wallet PIN removed.")
            }
            .onFailure { onBanner(it.localizedMessage ?: "Incorrect PIN.") }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        val s = engine.vault.getSecuritySettings()
        engine.vault.updateSecuritySettings(s.copy(biometricEnabled = enabled))
        _securitySettings.value = engine.vault.getSecuritySettings()
        _isLocked.value = engine.vault.isWalletLocked()
        onBanner(if (enabled) "Biometric approval enabled." else "Biometric approval disabled.")
    }

    fun triggerEmergencyLock() {
        engine.vault.triggerEmergencyLock()
        _revealedMnemonic.value = null
        _exportedBackupJson.value = null
        _preparedTx.value = null
        _securitySettings.value = engine.vault.getSecuritySettings()
        _isLocked.value = true
        onBanner("EMERGENCY LOCK ACTIVATED: All signing & transfers blocked.")
    }

    fun clearEmergencyLockWithPin(pin: String): Boolean {
        val ok = engine.vault.clearEmergencyLockWithPin(pin)
        if (ok) {
            _securitySettings.value = engine.vault.getSecuritySettings()
            _isLocked.value = engine.vault.isWalletLocked()
            onBanner("Emergency Lock deactivated.")
        } else {
            onBanner("Invalid PIN — Emergency Lock remains active.")
        }
        return ok
    }

    fun clearEmergencyLockWithBiometric() {
        engine.vault.clearEmergencyLockWithBiometric()
        _securitySettings.value = engine.vault.getSecuritySettings()
        _isLocked.value = engine.vault.isWalletLocked()
        onBanner("Emergency Lock deactivated via Biometrics.")
    }

    fun updateSpendingLimits(dailyUsd: Double, perTxUsd: Double) {
        if (dailyUsd <= 0.0 || perTxUsd <= 0.0 || perTxUsd > dailyUsd) {
            onBanner("Per-Tx limit must be positive and not exceed Daily limit.")
            return
        }
        val s = engine.vault.getSecuritySettings()
        engine.vault.updateSecuritySettings(s.copy(dailyLimitUsd = dailyUsd, perTxLimitUsd = perTxUsd))
        _securitySettings.value = engine.vault.getSecuritySettings()
        onBanner("Updated spending limits: Per-Tx $${"%.0f".format(perTxUsd)}, Daily $${"%.0f".format(dailyUsd)}.")
    }

    fun revealMnemonic(pinAttempt: String? = null, biometricVerified: Boolean = false) {
        val meta = activeWalletMeta() ?: return
        if (!unlockVaultIfNeeded(pinAttempt, biometricVerified)) {
            onBanner("Authenticate to view your recovery phrase.")
            return
        }
        engine.vault.revealMnemonicForBackup(meta.walletId)
            .onSuccess { _revealedMnemonic.value = it }
            .onFailure { onBanner(it.localizedMessage ?: "Cannot reveal mnemonic.") }
    }

    fun clearRevealedMnemonic() {
        _revealedMnemonic.value = null
    }

    fun verifyBackupWords(wordIndicesToEntered: Map<Int, String>) {
        val meta = activeWalletMeta() ?: return
        val phrase = engine.vault.revealMnemonicForBackup(meta.walletId).getOrNull()
        if (phrase == null) {
            onBanner("Unlock wallet first to verify backup.")
            return
        }
        val words = phrase.split(" ")
        val allMatch = wordIndicesToEntered.isNotEmpty() && wordIndicesToEntered.all { (idx, guess) ->
            idx in words.indices && words[idx].equals(guess.trim(), ignoreCase = true)
        }
        if (allMatch) {
            engine.vault.markBackupVerified(meta.walletId)
            scope.launch { reloadAll() }
            onBanner("Recovery phrase backup verified successfully.")
        } else {
            onBanner("Entered recovery words do not match your phrase.")
        }
    }

    fun exportEncryptedBackup(password: String, pinAttempt: String? = null, biometricVerified: Boolean = false) {
        val meta = activeWalletMeta() ?: return
        if (!unlockVaultIfNeeded(pinAttempt, biometricVerified)) {
            onBanner("Unlock wallet first to export encrypted backup.")
            return
        }
        engine.vault.exportPasswordEncryptedBackup(meta.walletId, password)
            .onSuccess { json ->
                _exportedBackupJson.value = json
                onBanner("Generated PBKDF2 + AES-256-GCM encrypted backup.")
            }
            .onFailure { onBanner(it.localizedMessage ?: "Backup export failed.") }
    }

    fun clearExportedBackup() {
        _exportedBackupJson.value = null
    }

    fun restoreEncryptedBackup(backupJson: String, password: String) {
        scope.launch {
            _busy.value = true
            engine.vault.restoreFromEncryptedBackup(backupJson, password)
                .onSuccess { (meta, acc) ->
                    selectWalletInternal(meta.walletId, acc.accountIndex)
                    reloadAll()
                    syncPublicMetadataOnly(meta, acc)
                    onBanner("Restored '${meta.label}' from encrypted backup.")
                    refreshPortfolio()
                    refreshTransactions()
                }
                .onFailure { onBanner(it.localizedMessage ?: "Backup decryption failed.") }
            _busy.value = false
        }
    }

    private fun unlockVaultIfNeeded(pinAttempt: String?, biometricVerified: Boolean): Boolean {
        val s = engine.vault.getSecuritySettings()
        if (s.emergencyLocked) return false
        if (!engine.vault.isWalletLocked()) return true
        if (biometricVerified && s.biometricEnabled) {
            engine.vault.markUnlockedWithBiometric()
            _isLocked.value = engine.vault.isWalletLocked()
            return true
        }
        if (s.hasPin && !pinAttempt.isNullOrBlank()) {
            val ok = engine.vault.verifyPin(pinAttempt)
            _isLocked.value = engine.vault.isWalletLocked()
            return ok
        }
        return false
    }

    private fun selectWalletInternal(walletId: String, accountIndex: Int) {
        val s = engine.vault.getSecuritySettings()
        engine.vault.updateSecuritySettings(s.copy(activeWalletId = walletId, activeAccountIndex = accountIndex))
    }

    /**
     * Syncs strictly PUBLIC wallet metadata (label, network, public address) to Firestore.
     * NEVER transmits mnemonics, seeds, passphrases, or private keys.
     */
    private fun syncPublicMetadataOnly(meta: WalletMetadata, account: WalletAccountInfo) {
        scope.launch(Dispatchers.IO) {
            val net = activeNetworkConfig()
            val cfg = WalletConfig(
                id = meta.walletId,
                walletProductName = meta.label,
                developerName = "Aqua Native Self-Custody",
                packageName = "",
                integrationMethod = "read_only_rpc",
                networkName = net.name,
                rpcEndpoint = net.defaultRpcUrl,
                publicAddress = account.addressFor(net),
                connectionStatus = if (meta.isWatchOnly) "read_only" else "connected"
            )
            runCatching { firestoreRepo.saveWalletConfig(cfg) }
        }
    }
}
