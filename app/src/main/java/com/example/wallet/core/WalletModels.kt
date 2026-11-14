package com.example.wallet.core

import java.math.BigDecimal
import java.math.BigInteger

enum class ChainFamily {
    EVM,
    SOLANA,
    BITCOIN
}

data class NetworkConfig(
    val id: String,
    val name: String,
    val chainFamily: ChainFamily,
    val nativeSymbol: String,
    val nativeDecimals: Int,
    val chainId: Long,
    val slip44CoinType: Int,
    val defaultRpcUrl: String,
    val explorerBaseUrl: String,
    val coingeckoId: String,
    val isTestnet: Boolean = false,
    val isCustom: Boolean = false
)

data class TokenDefinition(
    val id: String,
    val networkId: String,
    val symbol: String,
    val name: String,
    val decimals: Int,
    val contractOrMintAddress: String,
    val coingeckoId: String,
    val isNative: Boolean = false,
    val enabled: Boolean = true
)

data class WalletMetadata(
    val walletId: String,
    val label: String,
    val isWatchOnly: Boolean,
    val wordCount: Int,
    val accountCount: Int,
    val backupVerified: Boolean,
    val createdAtMs: Long,
    val hasPassphrase: Boolean = false
)

data class WalletAccountInfo(
    val walletId: String,
    val accountIndex: Int,
    val label: String,
    val evmAddress: String,
    val solanaAddress: String,
    val bitcoinMainnetAddress: String,
    val bitcoinTestnetAddress: String
) {
    fun addressFor(network: NetworkConfig): String = when (network.chainFamily) {
        ChainFamily.EVM -> evmAddress
        ChainFamily.SOLANA -> solanaAddress
        ChainFamily.BITCOIN -> if (network.isTestnet) bitcoinTestnetAddress else bitcoinMainnetAddress
    }
}

data class AssetBalance(
    val token: TokenDefinition,
    val rawAmount: BigInteger,
    val formattedAmount: BigDecimal,
    val displayBalance: String,
    val priceUsd: Double?,
    val valueUsd: Double?,
    val change24hPct: Double?
)

data class PortfolioSummary(
    val totalValueUsd: Double?,
    val weighted24hChangePct: Double?,
    val assets: List<AssetBalance>,
    val chainTotalsUsd: Map<String, Double>,
    val lastUpdatedMs: Long,
    val warnings: List<String>
)

data class FeeEstimate(
    val networkId: String,
    val nativeSymbol: String,
    val slowLabel: String,
    val standardLabel: String,
    val fastLabel: String,
    val estimatedFeeDecimal: BigDecimal,
    val estimatedFeeDisplay: String,
    val estimatedFeeUsd: Double?,
    val gasLimit: Long = 0L,
    val maxPriorityFeeWei: BigInteger = BigInteger.ZERO,
    val maxFeePerGasWei: BigInteger = BigInteger.ZERO,
    val lamportsFee: Long = 0L,
    val satsPerVbyte: Long = 0L,
    val estimatedVbytes: Int = 0
)

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

data class AddressRiskReport(
    val address: String,
    val networkId: String,
    val isValidFormat: Boolean,
    val checksumValid: Boolean,
    val isSelfTransfer: Boolean,
    val isContractAddress: Boolean,
    val riskLevel: RiskLevel,
    val findings: List<String>
)

data class PreparedChainTransaction(
    val id: String,
    val idempotencyKey: String,
    val walletId: String,
    val accountIndex: Int,
    val network: NetworkConfig,
    val fromAddress: String,
    val toAddress: String,
    val token: TokenDefinition,
    val amountDecimal: BigDecimal,
    val feeEstimate: FeeEstimate,
    val totalCostDisplay: String,
    val nonceOrBlockhash: String,
    val utxosPayloadJson: String,
    val simulationSummary: String,
    val riskReport: AddressRiskReport,
    val createdAtMs: Long = System.currentTimeMillis()
)

enum class TxStatus {
    PENDING,
    CONFIRMED,
    FAILED
}

enum class TxDirection {
    OUTBOUND,
    INBOUND,
    SELF
}

data class WalletTxRecord(
    val txHash: String,
    val idempotencyKey: String,
    val walletId: String,
    val networkId: String,
    val networkName: String,
    val fromAddress: String,
    val toAddress: String,
    val assetSymbol: String,
    val amountDisplay: String,
    val feeDisplay: String,
    val status: TxStatus,
    val confirmations: Int,
    val timestampMs: Long,
    val explorerUrl: String,
    val direction: TxDirection,
    val errorMessage: String? = null
)

data class WalletSecuritySettings(
    val hasPin: Boolean = false,
    val biometricEnabled: Boolean = false,
    val emergencyLocked: Boolean = false,
    val dailyLimitUsd: Double = 1000.0,
    val perTxLimitUsd: Double = 500.0,
    val spentTodayUsd: Double = 0.0,
    val spendDayKey: String = "",
    val testnetModeEnabled: Boolean = false,
    val activeWalletId: String = "",
    val activeAccountIndex: Int = 0,
    val activeNetworkId: String = "eth_mainnet"
)

data class WalletExtensionPoint(
    val id: String,
    val title: String,
    val category: String,
    val statusLabel: String,
    val securityRequirement: String,
    val description: String
)

object DefaultWalletRegistry {

    val networks: List<NetworkConfig> = listOf(
        NetworkConfig(
            id = "eth_mainnet",
            name = "Ethereum",
            chainFamily = ChainFamily.EVM,
            nativeSymbol = "ETH",
            nativeDecimals = 18,
            chainId = 1L,
            slip44CoinType = 60,
            defaultRpcUrl = "https://ethereum-rpc.publicnode.com",
            explorerBaseUrl = "https://etherscan.io/tx/",
            coingeckoId = "ethereum"
        ),
        NetworkConfig(
            id = "bsc_mainnet",
            name = "BNB Smart Chain",
            chainFamily = ChainFamily.EVM,
            nativeSymbol = "BNB",
            nativeDecimals = 18,
            chainId = 56L,
            slip44CoinType = 60,
            defaultRpcUrl = "https://bsc-rpc.publicnode.com",
            explorerBaseUrl = "https://bscscan.com/tx/",
            coingeckoId = "binancecoin"
        ),
        NetworkConfig(
            id = "polygon_mainnet",
            name = "Polygon PoS",
            chainFamily = ChainFamily.EVM,
            nativeSymbol = "POL",
            nativeDecimals = 18,
            chainId = 137L,
            slip44CoinType = 60,
            defaultRpcUrl = "https://polygon-bor-rpc.publicnode.com",
            explorerBaseUrl = "https://polygonscan.com/tx/",
            coingeckoId = "polygon-ecosystem-token"
        ),
        NetworkConfig(
            id = "base_mainnet",
            name = "Base",
            chainFamily = ChainFamily.EVM,
            nativeSymbol = "ETH",
            nativeDecimals = 18,
            chainId = 8453L,
            slip44CoinType = 60,
            defaultRpcUrl = "https://base-rpc.publicnode.com",
            explorerBaseUrl = "https://basescan.org/tx/",
            coingeckoId = "ethereum"
        ),
        NetworkConfig(
            id = "arbitrum_mainnet",
            name = "Arbitrum One",
            chainFamily = ChainFamily.EVM,
            nativeSymbol = "ETH",
            nativeDecimals = 18,
            chainId = 42161L,
            slip44CoinType = 60,
            defaultRpcUrl = "https://arbitrum-one-rpc.publicnode.com",
            explorerBaseUrl = "https://arbiscan.io/tx/",
            coingeckoId = "ethereum"
        ),
        NetworkConfig(
            id = "solana_mainnet",
            name = "Solana",
            chainFamily = ChainFamily.SOLANA,
            nativeSymbol = "SOL",
            nativeDecimals = 9,
            chainId = 101L,
            slip44CoinType = 501,
            defaultRpcUrl = "https://api.mainnet-beta.solana.com",
            explorerBaseUrl = "https://solscan.io/tx/",
            coingeckoId = "solana"
        ),
        NetworkConfig(
            id = "btc_mainnet",
            name = "Bitcoin",
            chainFamily = ChainFamily.BITCOIN,
            nativeSymbol = "BTC",
            nativeDecimals = 8,
            chainId = 0L,
            slip44CoinType = 0,
            defaultRpcUrl = "https://mempool.space/api",
            explorerBaseUrl = "https://mempool.space/tx/",
            coingeckoId = "bitcoin"
        ),
        // Testnets for verification & safe testing
        NetworkConfig(
            id = "eth_sepolia",
            name = "Ethereum Sepolia",
            chainFamily = ChainFamily.EVM,
            nativeSymbol = "ETH",
            nativeDecimals = 18,
            chainId = 11155111L,
            slip44CoinType = 60,
            defaultRpcUrl = "https://ethereum-sepolia-rpc.publicnode.com",
            explorerBaseUrl = "https://sepolia.etherscan.io/tx/",
            coingeckoId = "ethereum",
            isTestnet = true
        ),
        NetworkConfig(
            id = "solana_devnet",
            name = "Solana Devnet",
            chainFamily = ChainFamily.SOLANA,
            nativeSymbol = "SOL",
            nativeDecimals = 9,
            chainId = 103L,
            slip44CoinType = 501,
            defaultRpcUrl = "https://api.devnet.solana.com",
            explorerBaseUrl = "https://solscan.io/tx/",
            coingeckoId = "solana",
            isTestnet = true
        ),
        NetworkConfig(
            id = "btc_testnet",
            name = "Bitcoin Testnet4",
            chainFamily = ChainFamily.BITCOIN,
            nativeSymbol = "tBTC",
            nativeDecimals = 8,
            chainId = 1L,
            slip44CoinType = 1,
            defaultRpcUrl = "https://mempool.space/testnet4/api",
            explorerBaseUrl = "https://mempool.space/testnet4/tx/",
            coingeckoId = "bitcoin",
            isTestnet = true
        )
    )

    val defaultTokens: List<TokenDefinition> = listOf(
        // Native tokens
        TokenDefinition("eth_native", "eth_mainnet", "ETH", "Ethereum", 18, "", "ethereum", isNative = true),
        TokenDefinition("eth_usdt", "eth_mainnet", "USDT", "Tether USD", 6, "0xdAC17F958D2ee523a2206206994597C13D831ec7", "tether"),
        TokenDefinition("eth_usdc", "eth_mainnet", "USDC", "USD Coin", 6, "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", "usd-coin"),

        TokenDefinition("bsc_native", "bsc_mainnet", "BNB", "BNB", 18, "", "binancecoin", isNative = true),
        TokenDefinition("bsc_usdt", "bsc_mainnet", "USDT", "Tether USD (BEP-20)", 18, "0x55d398326f99059fF775485246999027B3197955", "tether"),

        TokenDefinition("pol_native", "polygon_mainnet", "POL", "Polygon Ecosystem Token", 18, "", "polygon-ecosystem-token", isNative = true),
        TokenDefinition("pol_usdc", "polygon_mainnet", "USDC", "USD Coin (PoS)", 6, "0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359", "usd-coin"),

        TokenDefinition("base_native", "base_mainnet", "ETH", "Ethereum (Base)", 18, "", "ethereum", isNative = true),
        TokenDefinition("base_usdc", "base_mainnet", "USDC", "USD Coin (Base)", 6, "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913", "usd-coin"),

        TokenDefinition("arb_native", "arbitrum_mainnet", "ETH", "Ethereum (Arbitrum)", 18, "", "ethereum", isNative = true),
        TokenDefinition("arb_usdc", "arbitrum_mainnet", "USDC", "USD Coin (Arbitrum)", 6, "0xaf88d065e77c8cC2239327C5EDb3A432268e5831", "usd-coin"),

        TokenDefinition("sol_native", "solana_mainnet", "SOL", "Solana", 9, "", "solana", isNative = true),
        TokenDefinition("sol_usdc", "solana_mainnet", "USDC", "USD Coin (SPL)", 6, "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", "usd-coin"),

        TokenDefinition("btc_native", "btc_mainnet", "BTC", "Bitcoin", 8, "", "bitcoin", isNative = true),

        // Testnets
        TokenDefinition("sepolia_native", "eth_sepolia", "ETH", "Sepolia Ether", 18, "", "ethereum", isNative = true),
        TokenDefinition("sol_dev_native", "solana_devnet", "SOL", "Solana Devnet", 9, "", "solana", isNative = true),
        TokenDefinition("btc_test_native", "btc_testnet", "tBTC", "Bitcoin Testnet", 8, "", "bitcoin", isNative = true)
    )

    val extensionPoints: List<WalletExtensionPoint> = listOf(
        WalletExtensionPoint(
            id = "ext_dex_swap",
            title = "Decentralized Token Swaps",
            category = "DEX Router",
            statusLabel = "Guarded Extension Interface",
            securityRequirement = "Requires audited DEX aggregator router whitelist + simulation + slippage bounds.",
            description = "Prepared extension hook for on-chain token swaps without exposing keys."
        ),
        WalletExtensionPoint(
            id = "ext_cross_chain",
            title = "Cross-Chain Bridge",
            category = "Interoperability",
            statusLabel = "Locked Until Audit",
            securityRequirement = "Disabled by policy until canonical audited bridge contracts are verified.",
            description = "Interface boundary for future verified cross-chain liquidity routing."
        ),
        WalletExtensionPoint(
            id = "ext_staking",
            title = "Native Staking & Delegation",
            category = "Yield / Consensus",
            statusLabel = "Extension Point Ready",
            securityRequirement = "Supports Solana stake accounts & EVM validator contracts with explicit lockup preview.",
            description = "Architectural hook for native validator staking & reward tracking."
        ),
        WalletExtensionPoint(
            id = "ext_defi_analytics",
            title = "DeFi Position Analytics",
            category = "Portfolio",
            statusLabel = "Read-Only Ready",
            securityRequirement = "Read-only contract state queries only; never requests approvals.",
            description = "Extends portfolio engine to track LP shares, lending health, and yield positions."
        ),
        WalletExtensionPoint(
            id = "ext_approvals",
            title = "ERC-20 / SPL Approval Manager",
            category = "Security",
            statusLabel = "Extension Point Ready",
            securityRequirement = "Allows revoking unlimited token allowances after explicit signing confirmation.",
            description = "Inspects and revokes active spender allowances on EVM & Solana."
        ),
        WalletExtensionPoint(
            id = "ext_hardware_wallet",
            title = "Hardware Signer (USB / BLE / NFC)",
            category = "Cold Storage",
            statusLabel = "Transport Interface",
            securityRequirement = "Delegates transaction digest signing to external air-gapped or hardware device.",
            description = "Defines hardware APDU / QR PSBT signing transport boundary."
        )
    )
}
