package com.example.wallet.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Modular coordinator delegate for Aqua Wallet's advanced multi-chain tools:
 * - Multi-chain Address Book
 * - Live Market Watchlist & Multi-Currency FX Rates
 * - Live ENS (.eth) resolution
 * - Live EVM Gas/Block/Nonce Telemetry & ERC-20 Allowance Inspector / Revoker
 * - Local EIP-191 & Solana Ed25519 Off-Chain Message Signing & Verification
 */
class WalletToolsCoordinator(
    private val engine: AquaWalletEngine,
    private val scope: CoroutineScope,
    private val activeWalletMeta: () -> WalletMetadata?,
    private val activeAccountInfo: () -> WalletAccountInfo?,
    private val activeNetworkConfig: () -> NetworkConfig,
    private val unlockVaultIfNeeded: (String?, Boolean) -> Boolean,
    private val setPreparedTxForSigning: (PreparedChainTransaction) -> Unit,
    private val onBanner: (String) -> Unit
) {
    private val _addressBook = MutableStateFlow<List<AddressBookEntry>>(emptyList())
    val addressBook: StateFlow<List<AddressBookEntry>> = _addressBook.asStateFlow()

    private val _displayCurrency = MutableStateFlow(
        DisplayFiatCurrency.fromCode(engine.vault.getSecuritySettings().displayCurrencyCode)
    )
    val displayCurrency: StateFlow<DisplayFiatCurrency> = _displayCurrency.asStateFlow()

    private val _fiatRateFromUsd = MutableStateFlow(1.0)
    val fiatRateFromUsd: StateFlow<Double> = _fiatRateFromUsd.asStateFlow()

    private val _marketChart = MutableStateFlow<MarketSparklineData?>(null)
    val marketChart: StateFlow<MarketSparklineData?> = _marketChart.asStateFlow()

    private val _marketBusy = MutableStateFlow(false)
    val marketBusy: StateFlow<Boolean> = _marketBusy.asStateFlow()

    private val _marketError = MutableStateFlow<String?>(null)
    val marketError: StateFlow<String?> = _marketError.asStateFlow()

    private val _evmTelemetry = MutableStateFlow<EvmChainTelemetry?>(null)
    val evmTelemetry: StateFlow<EvmChainTelemetry?> = _evmTelemetry.asStateFlow()

    private val _erc20Allowance = MutableStateFlow<Erc20AllowanceResult?>(null)
    val erc20Allowance: StateFlow<Erc20AllowanceResult?> = _erc20Allowance.asStateFlow()

    private val _signedMessageProof = MutableStateFlow<SignedMessageProof?>(null)
    val signedMessageProof: StateFlow<SignedMessageProof?> = _signedMessageProof.asStateFlow()

    private val _verificationResult = MutableStateFlow<Boolean?>(null)
    val verificationResult: StateFlow<Boolean?> = _verificationResult.asStateFlow()

    private val _toolsBusy = MutableStateFlow(false)
    val toolsBusy: StateFlow<Boolean> = _toolsBusy.asStateFlow()

    init {
        scope.launch {
            engine.utilities.observeAddressBook().collect { _addressBook.value = it }
        }
        val initialCurrency = _displayCurrency.value
        if (initialCurrency != DisplayFiatCurrency.USD) {
            setDisplayCurrency(initialCurrency)
        }
    }

    fun setDisplayCurrency(currency: DisplayFiatCurrency) {
        val current = engine.vault.getSecuritySettings()
        engine.vault.updateSecuritySettings(current.copy(displayCurrencyCode = currency.code))
        _displayCurrency.value = currency
        if (currency == DisplayFiatCurrency.USD) {
            _fiatRateFromUsd.value = 1.0
            return
        }
        scope.launch {
            engine.marketPriceService.fetchFiatRateFromUsd(currency)
                .onSuccess { rate -> _fiatRateFromUsd.value = rate }
                .onFailure { err ->
                    onBanner("FX quote unavailable (${err.localizedMessage}); showing USD rate.")
                    _displayCurrency.value = DisplayFiatCurrency.USD
                    _fiatRateFromUsd.value = 1.0
                }
        }
    }

    fun loadMarketChart(coingeckoId: String, symbol: String, days: Int) {
        scope.launch {
            _marketBusy.value = true
            _marketError.value = null
            engine.marketPriceService.fetchMarketChart(coingeckoId, symbol, days)
                .onSuccess { data -> _marketChart.value = data }
                .onFailure { err -> _marketError.value = err.localizedMessage ?: "Chart unavailable" }
            _marketBusy.value = false
        }
    }

    fun saveAddressBookContact(label: String, chainFamily: ChainFamily, address: String, note: String) {
        scope.launch {
            engine.utilities.saveContact(label, chainFamily, address, note)
                .onSuccess { onBanner("Saved '${it.label}' (${it.chainFamily.name}) to Address Book.") }
                .onFailure { onBanner(it.localizedMessage ?: "Invalid contact details.") }
        }
    }

    fun deleteAddressBookContact(entryId: String) {
        scope.launch {
            engine.utilities.deleteContact(entryId)
            onBanner("Removed contact from Address Book.")
        }
    }

    fun resolveEnsName(ensName: String, onResolved: (String) -> Unit) {
        scope.launch {
            _toolsBusy.value = true
            engine.utilities.resolveEnsName(ensName)
                .onSuccess { addr ->
                    onBanner("Resolved $ensName -> ${addr.take(10)}...${addr.takeLast(6)}")
                    onResolved(addr)
                }
                .onFailure { err -> onBanner("ENS error: ${err.localizedMessage}") }
            _toolsBusy.value = false
        }
    }

    fun refreshEvmTelemetry(networkId: String? = null) {
        val acc = activeAccountInfo() ?: return
        scope.launch {
            _toolsBusy.value = true
            val nets = engine.getAvailableNetworks(includeTestnets = true)
            val net = nets.firstOrNull { it.id == networkId }
                ?: activeNetworkConfig().takeIf { it.chainFamily == ChainFamily.EVM }
                ?: nets.first { it.chainFamily == ChainFamily.EVM }
            engine.utilities.fetchEvmTelemetry(acc.evmAddress, net)
                .onSuccess { _evmTelemetry.value = it }
                .onFailure { onBanner("Telemetry unavailable: ${it.localizedMessage}") }
            _toolsBusy.value = false
        }
    }

    fun inspectErc20Allowance(networkId: String, tokenId: String, spenderAddress: String) {
        val acc = activeAccountInfo() ?: return
        scope.launch {
            _toolsBusy.value = true
            val nets = engine.getAvailableNetworks(includeTestnets = true)
            val net = nets.firstOrNull { it.id == networkId }
                ?: return@launch
            val tok = engine.getTokensForNetwork(net.id).firstOrNull { it.id == tokenId }
            if (tok == null) {
                onBanner("Select an ERC-20 token contract first.")
                _toolsBusy.value = false
                return@launch
            }
            engine.utilities.fetchErc20Allowance(acc.evmAddress, tok, spenderAddress, net)
                .onSuccess { res ->
                    _erc20Allowance.value = res
                    onBanner("${res.tokenSymbol} allowance: ${res.formattedAllowance}")
                }
                .onFailure { err -> onBanner("Allowance check failed: ${err.localizedMessage}") }
            _toolsBusy.value = false
        }
    }

    fun prepareErc20Revoke(
        networkId: String,
        tokenId: String,
        spenderAddress: String,
        onPreparedReady: () -> Unit
    ) {
        val meta = activeWalletMeta() ?: return
        val acc = activeAccountInfo() ?: return
        scope.launch {
            _toolsBusy.value = true
            val net = engine.getAvailableNetworks(includeTestnets = true).firstOrNull { it.id == networkId }
            val tok = net?.let { engine.getTokensForNetwork(it.id).firstOrNull { t -> t.id == tokenId } }
            if (net == null || tok == null) {
                onBanner("Select a valid EVM network and ERC-20 token.")
                _toolsBusy.value = false
                return@launch
            }
            engine.utilities.prepareErc20RevokeApproval(meta, acc, net, tok, spenderAddress)
                .onSuccess { prep ->
                    setPreparedTxForSigning(prep)
                    onBanner("Prepared approve(0) revoke for ${tok.symbol}. Review & sign to broadcast.")
                    onPreparedReady()
                }
                .onFailure { err -> onBanner(err.localizedMessage ?: "Failed to build revoke transaction.") }
            _toolsBusy.value = false
        }
    }

    fun signMessageLocally(
        chainFamily: ChainFamily,
        message: String,
        pinAttempt: String? = null,
        biometricVerified: Boolean = false
    ) {
        val meta = activeWalletMeta() ?: return
        val acc = activeAccountInfo() ?: return
        if (!unlockVaultIfNeeded(pinAttempt, biometricVerified)) {
            onBanner("Authenticate with PIN or Biometrics before signing message.")
            return
        }
        engine.utilities.signMessageLocally(meta, acc.accountIndex, chainFamily, message)
            .onSuccess { proof ->
                _signedMessageProof.value = proof
                _verificationResult.value = null
                onBanner("Signed off-chain message (${proof.standardName}).")
            }
            .onFailure { err -> onBanner(err.localizedMessage ?: "Message signing failed.") }
    }

    fun verifyMessageSignature(
        chainFamily: ChainFamily,
        signerAddress: String,
        message: String,
        signature: String
    ) {
        engine.utilities.verifySignedMessage(chainFamily, signerAddress, message, signature)
            .onSuccess { valid ->
                _verificationResult.value = valid
                onBanner(if (valid) "Signature verified cleanly for $signerAddress." else "Signature does NOT match signer & message.")
            }
            .onFailure { err ->
                _verificationResult.value = false
                onBanner(err.localizedMessage ?: "Invalid signature format.")
            }
    }

    fun clearSignedProof() {
        _signedMessageProof.value = null
        _verificationResult.value = null
    }
}
