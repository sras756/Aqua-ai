package com.example.wallet.chains

import com.example.wallet.core.AddressRiskReport
import com.example.wallet.core.AssetBalance
import com.example.wallet.core.FeeEstimate
import com.example.wallet.core.NetworkConfig
import com.example.wallet.core.PreparedChainTransaction
import com.example.wallet.core.TokenDefinition
import com.example.wallet.core.WalletTxRecord
import java.math.BigDecimal

interface ChainAdapter {
    fun validateAddress(address: String, network: NetworkConfig): Boolean

    suspend fun analyzeAddressRisk(
        fromAddress: String,
        toAddress: String,
        network: NetworkConfig
    ): AddressRiskReport

    suspend fun fetchBalances(
        address: String,
        tokens: List<TokenDefinition>,
        network: NetworkConfig
    ): Result<List<AssetBalance>>

    suspend fun fetchTransactionHistory(
        address: String,
        network: NetworkConfig
    ): Result<List<WalletTxRecord>>

    suspend fun estimateFee(
        fromAddress: String,
        toAddress: String,
        token: TokenDefinition,
        amount: BigDecimal,
        network: NetworkConfig
    ): Result<FeeEstimate>

    suspend fun simulateTransaction(
        prepared: PreparedChainTransaction
    ): Result<String>

    suspend fun signAndBroadcast(
        prepared: PreparedChainTransaction,
        seed: ByteArray
    ): Result<WalletTxRecord>

    suspend fun fetchTransactionStatus(
        txHash: String,
        existingRecord: WalletTxRecord?,
        network: NetworkConfig
    ): Result<WalletTxRecord>
}
