package com.example.wallet

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ai.ContextManager
import com.example.ai.ToolCallingEngine
import com.example.browser.AquaBrowserController
import com.example.data.local.AquaCacheDatabase
import com.example.data.local.CachedWalletTxEntity
import com.example.data.model.UserSettings
import com.example.data.repository.AquaFirestoreRepository
import com.example.device.AndroidDeviceController
import com.example.device.DeviceTelemetry
import com.example.wallet.ai.AquaAiWalletBridge
import com.example.wallet.chains.BitcoinChainAdapter
import com.example.wallet.chains.BitcoinUtxo
import com.example.wallet.chains.EvmChainAdapter
import com.example.wallet.chains.EvmToolsClient
import com.example.wallet.chains.SolanaChainAdapter
import com.example.wallet.core.AddressRiskReport
import com.example.wallet.core.AquaWalletEngine
import com.example.wallet.core.ChainFamily
import com.example.wallet.core.DefaultWalletRegistry
import com.example.wallet.core.DisplayFiatCurrency
import com.example.wallet.core.FeeEstimate
import com.example.wallet.core.PreparedChainTransaction
import com.example.wallet.core.RiskLevel
import com.example.wallet.core.TxStatus
import com.example.wallet.crypto.Base58
import com.example.wallet.crypto.CryptoPrimitives
import com.example.wallet.crypto.HdKeyDerivation
import com.example.wallet.crypto.WalletMessageCrypto
import com.example.wallet.security.WalletSecurityVault
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.math.BigInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AquaWalletComprehensiveTest {

    private lateinit var context: Context
    private lateinit var db: AquaCacheDatabase
    private lateinit var vault: WalletSecurityVault
    private lateinit var engine: AquaWalletEngine
    private val httpClient = OkHttpClient()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("aqua_native_wallet_vault", Context.MODE_PRIVATE)
            .edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AquaCacheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        vault = WalletSecurityVault(context)
        engine = AquaWalletEngine(vault, db.walletCacheDao(), httpClient)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `bip39 generates valid 12 and 24 word mnemonics and rejects invalid checksum`() {
        val m12 = HdKeyDerivation.generateMnemonic(12)
        val m24 = HdKeyDerivation.generateMnemonic(24)
        assertEquals(12, m12.split(" ").size)
        assertEquals(24, m24.split(" ").size)
        assertTrue(HdKeyDerivation.validateMnemonic(m12).isSuccess)
        assertTrue(HdKeyDerivation.validateMnemonic(m24).isSuccess)

        val badChecksum = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon"
        assertTrue(HdKeyDerivation.validateMnemonic(badChecksum).isFailure)
        assertTrue(HdKeyDerivation.validateMnemonic("not a real bip39 phrase").isFailure)
    }

    @Test
    fun `deterministic standard BIP44 BIP84 SLIP10 address derivation and recovery equivalence`() {
        // Official BIP-39 test mnemonic
        val testPhrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val (meta1, acc0) = vault.importWalletFromMnemonic("Primary", testPhrase).getOrThrow()

        // Known Ethereum m/44'/60'/0'/0/0 address for "abandon...about"
        assertEquals("0x9858EfFD232B4033E47d90003D41EC34EcaEda94", acc0.evmAddress)
        assertTrue(acc0.bitcoinMainnetAddress.startsWith("bc1q"))
        assertTrue(acc0.bitcoinTestnetAddress.startsWith("tb1q"))
        assertEquals(32, Base58.decode(acc0.solanaAddress).size)

        // Multi-account derivation yields distinct addresses
        val acc1 = vault.addDerivedAccount(meta1.walletId, "Trading").getOrThrow()
        assertEquals(1, acc1.accountIndex)
        assertNotEquals(acc0.evmAddress, acc1.evmAddress)
        assertNotEquals(acc0.solanaAddress, acc1.solanaAddress)
        assertNotEquals(acc0.bitcoinMainnetAddress, acc1.bitcoinMainnetAddress)

        // Delete & re-import reproduces identical addresses across every chain
        vault.deleteWallet(meta1.walletId)
        assertTrue(vault.getAllWallets().isEmpty())

        val (_, recovered0) = vault.importWalletFromMnemonic("Recovered", testPhrase).getOrThrow()
        assertEquals(acc0.evmAddress, recovered0.evmAddress)
        assertEquals(acc0.solanaAddress, recovered0.solanaAddress)
        assertEquals(acc0.bitcoinMainnetAddress, recovered0.bitcoinMainnetAddress)
        assertEquals(acc0.bitcoinTestnetAddress, recovered0.bitcoinTestnetAddress)
    }

    @Test
    fun `portable password encrypted backup round trips independently of firebase`() {
        val (meta, acc0) = vault.createWallet("Exportable", wordCount = 12).getOrThrow()
        val phrase = vault.revealMnemonicForBackup(meta.walletId).getOrThrow()
        vault.addDerivedAccount(meta.walletId, "Vault 2").getOrThrow()

        val encryptedJson = vault.exportPasswordEncryptedBackup(meta.walletId, "StrongBackup#2026").getOrThrow()
        assertFalse(encryptedJson.contains(phrase))
        assertTrue(encryptedJson.contains("aqua_encrypted_wallet_v1"))

        vault.deleteWallet(meta.walletId)

        // Wrong password fails cleanly
        assertTrue(vault.restoreFromEncryptedBackup(encryptedJson, "WrongPassword").isFailure)

        // Correct password restores wallet, mnemonic, and multi-account count
        val (restoredMeta, restoredAcc0) = vault.restoreFromEncryptedBackup(encryptedJson, "StrongBackup#2026").getOrThrow()
        assertEquals(acc0.evmAddress, restoredAcc0.evmAddress)
        assertEquals(2, vault.getAccountsForWallet(restoredMeta.walletId).size)
        assertEquals(phrase, vault.revealMnemonicForBackup(restoredMeta.walletId).getOrThrow())
    }

    @Test
    fun `pin lock biometric lock emergency lock and spending limits strictly enforce security`() {
        val (meta, _) = vault.createWallet("Secured", wordCount = 12).getOrThrow()
        vault.setPin("258090").getOrThrow()
        assertTrue(vault.verifyPin("258090"))
        assertFalse(vault.verifyPin("000000"))

        // Locking session blocks seed access & mnemonic reveal
        vault.lockSessionNow()
        assertTrue(vault.isWalletLocked())
        assertTrue(vault.withSeed(meta.walletId) { it.size }.isFailure)
        assertTrue(vault.revealMnemonicForBackup(meta.walletId).isFailure)

        // Unlocking restores access
        assertTrue(vault.verifyPin("258090"))
        assertFalse(vault.isWalletLocked())
        assertTrue(vault.withSeed(meta.walletId) { it.size }.isSuccess)

        // Emergency Lock blocks signing even after prior unlock
        vault.triggerEmergencyLock()
        assertTrue(vault.isWalletLocked())
        assertTrue(vault.withSeed(meta.walletId) { it.size }.isFailure)
        assertTrue(vault.checkSpendingLimit(1.0).isFailure)

        assertFalse(vault.clearEmergencyLockWithPin("111111"))
        assertTrue(vault.clearEmergencyLockWithPin("258090"))
        assertFalse(vault.isWalletLocked())

        // Spending limits enforce per-tx and daily caps
        val s = vault.getSecuritySettings().copy(perTxLimitUsd = 200.0, dailyLimitUsd = 350.0)
        vault.updateSecuritySettings(s)
        assertTrue(vault.checkSpendingLimit(150.0).isSuccess)
        assertTrue(vault.checkSpendingLimit(250.0).isFailure) // > perTxLimit
        vault.recordBroadcastSpend(200.0)
        assertTrue(vault.checkSpendingLimit(180.0).isFailure) // 200 + 180 > dailyLimit 350
    }

    @Test
    fun `evm EIP1559 signing produces valid Type2 transaction and detects checksum or burn address risk`() = runBlocking {
        val adapter = EvmChainAdapter(httpClient)
        val ethNet = DefaultWalletRegistry.networks.first { it.id == "eth_mainnet" }
        val phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val seed = HdKeyDerivation.mnemonicToSeed(phrase)
        val key = HdKeyDerivation.deriveBip32Secp256k1(seed, "m/44'/60'/0'/0/0")
        try {
            val rawHex = adapter.buildAndSignEip1559RawHex(
                chainId = 1L,
                nonce = BigInteger.valueOf(3),
                maxPriorityFeeWei = BigInteger.valueOf(1_500_000_000L),
                maxFeePerGasWei = BigInteger.valueOf(25_000_000_000L),
                gasLimit = 21_000L,
                toAddress = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94",
                valueWei = BigInteger.valueOf(100_000_000_000_000_000L),
                dataBytes = ByteArray(0),
                privateKey32 = key.keyBytes
            )
            assertTrue(rawHex.startsWith("0x02"))
            assertTrue(rawHex.length > 160)
        } finally {
            key.wipe()
            CryptoPrimitives.wipe(seed)
        }

        // Bad mixed-case checksum flagged HIGH
        val badChecksumAddr = "0x9858effd232b4033e47d90003d41ec34ecaeDA94"
        val checksumRisk = adapter.analyzeAddressRisk(
            fromAddress = "0x1111111111111111111111111111111111111111",
            toAddress = badChecksumAddr,
            network = ethNet
        )
        assertFalse(checksumRisk.checksumValid)
        assertEquals(RiskLevel.HIGH, checksumRisk.riskLevel)

        // Zero burn address flagged CRITICAL
        val burnRisk = adapter.analyzeAddressRisk(
            fromAddress = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94",
            toAddress = "0x0000000000000000000000000000000000000000",
            network = ethNet
        )
        assertEquals(RiskLevel.CRITICAL, burnRisk.riskLevel)
    }

    @Test
    fun `bitcoin BIP143 segwit transaction construction UTXO selection and address validation`() {
        val adapter = BitcoinChainAdapter(httpClient)
        val btcNet = DefaultWalletRegistry.networks.first { it.id == "btc_mainnet" }
        val btcTest = DefaultWalletRegistry.networks.first { it.id == "btc_testnet" }

        val seed = HdKeyDerivation.mnemonicToSeed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
        val derivedMain = HdKeyDerivation.deriveBip32Secp256k1(seed, "m/84'/0'/0'/0/0")
        val mat = HdKeyDerivation.deriveAccountMaterial(seed, 0)
        CryptoPrimitives.wipe(seed)

        try {
            assertTrue(adapter.validateAddress(mat.bitcoinMainnetAddress, btcNet))
            assertFalse(adapter.validateAddress(mat.bitcoinMainnetAddress, btcTest))
            assertTrue(adapter.validateAddress(mat.bitcoinTestnetAddress, btcTest))
            assertFalse(adapter.validateAddress("bc1qinvalidchecksum000000000000", btcNet))

            val utxos = listOf(
                BitcoinUtxo(txid = "a".repeat(64), vout = 0, valueSats = 120_000L, confirmed = true)
            )
            val (chosen, feeSats) = adapter.selectUtxos(utxos, 50_000L, satsPerVbyte = 5L)
            assertEquals(1, chosen.size)
            assertTrue(feeSats > 0L)

            val rawSegwitHex = adapter.buildAndSignSegwitTxHex(
                utxos = chosen,
                fromAddress = mat.bitcoinMainnetAddress,
                toAddress = mat.bitcoinMainnetAddress,
                sendSats = 50_000L,
                feeSats = feeSats,
                privateKey32 = derivedMain.keyBytes,
                isTestnet = false
            )
            // Version 2 (02000000) + SegWit marker/flag (0001)
            assertTrue(rawSegwitHex.startsWith("020000000001"))

            // Insufficient UTXOs throws cleanly
            val insufficient = runCatching {
                adapter.selectUtxos(utxos, 200_000L, satsPerVbyte = 5L)
            }
            assertTrue(insufficient.isFailure)
        } finally {
            derivedMain.wipe()
        }
    }

    @Test
    fun `solana ed25519 legacy message signing and address validation work accurately`() {
        val adapter = SolanaChainAdapter(httpClient)
        val solNet = DefaultWalletRegistry.networks.first { it.id == "solana_mainnet" }
        val seed = HdKeyDerivation.mnemonicToSeed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
        val mat = HdKeyDerivation.deriveAccountMaterial(seed, 0)
        val solKey = HdKeyDerivation.deriveSlip10Ed25519(seed, "m/44'/501'/0'/0'")
        CryptoPrimitives.wipe(seed)

        try {
            assertTrue(adapter.validateAddress(mat.solanaAddress, solNet))
            assertFalse(adapter.validateAddress("0x9858EfFD232B4033E47d90003D41EC34EcaEda94", solNet))

            val blockhash = Base58.encode(ByteArray(32) { 7 })
            val message = adapter.buildNativeTransferMessage(
                fromAddress = mat.solanaAddress,
                toAddress = mat.solanaAddress,
                lamports = 1_000_000L,
                recentBlockhash = blockhash
            )
            val sig = CryptoPrimitives.signEd25519(message, solKey.keyBytes)
            assertEquals(64, sig.size)
            assertTrue(message.size >= 64)
        } finally {
            solKey.wipe()
        }
    }

    @Test
    fun `duplicate submission protection and watch-only signing guard prevent unsafe execution`() = runBlocking {
        val (watchMeta, watchAcc) = vault.importWatchOnlyWallet(
            label = "Cold Watch",
            evmAddress = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
        ).getOrThrow()
        val ethNet = DefaultWalletRegistry.networks.first { it.id == "eth_mainnet" }
        val ethTok = DefaultWalletRegistry.defaultTokens.first { it.id == "eth_native" }

        // Watch-only cannot prepare signed transfer
        val watchPrep = engine.prepareTransaction(
            walletMeta = watchMeta,
            account = watchAcc,
            network = ethNet,
            token = ethTok,
            toAddress = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94",
            amountText = "0.01"
        )
        assertTrue(watchPrep.isFailure)

        // Duplicate idempotencyKey blocks second broadcast when first is PENDING or CONFIRMED
        val dupKey = "dup_key_tx_001"
        db.walletCacheDao().upsertTransaction(
            CachedWalletTxEntity(
                txHash = "0xabc123",
                idempotencyKey = dupKey,
                walletId = "w_test",
                networkId = "eth_mainnet",
                networkName = "Ethereum",
                fromAddress = "0x1111111111111111111111111111111111111111",
                toAddress = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94",
                assetSymbol = "ETH",
                amountDisplay = "0.01 ETH",
                feeDisplay = "0.0004 ETH",
                status = TxStatus.PENDING.name,
                confirmations = 0,
                timestampMs = System.currentTimeMillis(),
                explorerUrl = "https://etherscan.io/tx/0xabc123",
                direction = "OUTBOUND",
                errorMessage = null
            )
        )
        val prepDup = PreparedChainTransaction(
            id = "prep_1",
            idempotencyKey = dupKey,
            walletId = "w_test",
            accountIndex = 0,
            network = ethNet,
            fromAddress = "0x1111111111111111111111111111111111111111",
            toAddress = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94",
            token = ethTok,
            amountDecimal = BigDecimal("0.01"),
            feeEstimate = FeeEstimate("eth_mainnet", "ETH", "0.0003", "0.0004", "0.0006", BigDecimal("0.0004"), "0.0004 ETH", null),
            totalCostDisplay = "0.0104 ETH",
            nonceOrBlockhash = "",
            utxosPayloadJson = "",
            simulationSummary = "ok",
            riskReport = AddressRiskReport("0x9858EfFD232B4033E47d90003D41EC34EcaEda94", "eth_mainnet", true, true, false, false, RiskLevel.LOW, listOf("ok"))
        )
        val dupAttempt = engine.signAndBroadcastPrepared(prepDup)
        assertTrue(dupAttempt.isFailure)
        assertTrue(dupAttempt.exceptionOrNull()!!.message!!.contains("Duplicate submission prevented"))
    }

    @Test
    fun `ai wallet bridge and tool calling engine never expose seeds and enforce authorization boundaries`() = runBlocking {
        val (meta, acc) = vault.createWallet("AI Protected", wordCount = 12).getOrThrow()
        val secretPhrase = vault.revealMnemonicForBackup(meta.walletId).getOrThrow()
        val bridge = AquaAiWalletBridge(engine)

        // Overview and AI system prompt expose ONLY public metadata, NEVER mnemonic
        val overview = bridge.getPublicOverviewText()
        assertTrue(overview.contains(acc.evmAddress))
        assertTrue(overview.contains(acc.solanaAddress))
        assertTrue(overview.contains(acc.bitcoinMainnetAddress))
        assertFalse(overview.contains(secretPhrase))

        val sysPrompt = ContextManager.buildSystemInstruction(
            settings = UserSettings(),
            memories = emptyList(),
            enabledSkills = emptyList(),
            activeWallet = null,
            telemetry = DeviceTelemetry(90, false, true, "Wi-Fi", 50, false, false, false),
            browserUrl = "https://example.com",
            browserTitle = "Example",
            nativeWalletOverview = overview
        )
        assertTrue(sysPrompt.contains("STRICT CRYPTO WALLET BOUNDARY"))
        assertFalse(sysPrompt.contains(secretPhrase))

        val app = if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("com.aistudio.aquaai.vqrzmn")
                    .setProjectId("test-project")
                    .setApiKey("test-api-key")
                    .build()
            )
        } else {
            FirebaseApp.getInstance()
        }
        val testFirestore = FirebaseFirestore.getInstance(app)
        val firestoreRepo = AquaFirestoreRepository(testFirestore)
        val toolEngine = ToolCallingEngine(
            deviceController = AndroidDeviceController(context),
            browserController = AquaBrowserController(context),
            walletConnector = FlashWalletConnector(context),
            firestoreRepo = firestoreRepo,
            aiWalletBridge = bridge
        )

        // Safe read-only risk inspection works cleanly without secrets
        val riskCall = FunctionCallPart(
            name = "wallet_analyze_address_risk",
            args = mapOf(
                "address" to JsonPrimitive("0x0000000000000000000000000000000000000000"),
                "network" to JsonPrimitive("eth_mainnet")
            )
        )
        val riskResp = toolEngine.executeFunctionCall(
            call = riskCall,
            settings = UserSettings(),
            activeWallet = null,
            onNavigateToBrowser = {},
            requestAuthorization = { false },
            onTrace = {}
        )
        assertTrue(riskResp["success"].toString().contains("true"))
        assertTrue(riskResp["message"].toString().contains("CRITICAL"))
    }

    @Test
    fun `payment uri scanner parser handles ethereum bitcoin testnet and solana uris`() {
        val ethParsed = engine.parseScannedPaymentUri("ethereum:0x9858EfFD232B4033E47d90003D41EC34EcaEda94@1?value=250000000000000000")
        assertEquals("0x9858EfFD232B4033E47d90003D41EC34EcaEda94", ethParsed.address)
        assertEquals("eth_mainnet", ethParsed.suggestedNetworkId)
        assertEquals("0.25", ethParsed.suggestedAmount)

        val btcParsed = engine.parseScannedPaymentUri("bitcoin:tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx?amount=0.005")
        assertEquals("btc_testnet", btcParsed.suggestedNetworkId)
        assertEquals("0.005", btcParsed.suggestedAmount)

        val solParsed = engine.parseScannedPaymentUri("solana:11111111111111111111111111111111?amount=1.25")
        assertEquals("solana_mainnet", solParsed.suggestedNetworkId)
        assertEquals("1.25", solParsed.suggestedAmount)
    }

    @Test
    fun `off-chain EIP191 personal_sign and Solana Ed25519 signing and verification enforce lock guards`() {
        val (meta, acc) = vault.createWallet("Signer Wallet", wordCount = 12).getOrThrow()
        val message = "Aqua Crypto Wallet ownership proof #2026"

        val evmProof = engine.utilities.signMessageLocally(meta, acc.accountIndex, ChainFamily.EVM, message).getOrThrow()
        assertEquals(acc.evmAddress, evmProof.signerAddress)
        assertTrue(evmProof.signature.startsWith("0x"))
        assertTrue(engine.utilities.verifySignedMessage(ChainFamily.EVM, acc.evmAddress, message, evmProof.signature).getOrThrow())
        assertFalse(engine.utilities.verifySignedMessage(ChainFamily.EVM, acc.evmAddress, "$message!", evmProof.signature).getOrThrow())

        val solProof = engine.utilities.signMessageLocally(meta, acc.accountIndex, ChainFamily.SOLANA, message).getOrThrow()
        assertEquals(acc.solanaAddress, solProof.signerAddress)
        assertTrue(engine.utilities.verifySignedMessage(ChainFamily.SOLANA, acc.solanaAddress, message, solProof.signature).getOrThrow())
        assertFalse(engine.utilities.verifySignedMessage(ChainFamily.SOLANA, acc.solanaAddress, "tampered", solProof.signature).getOrThrow())

        // Locked vault blocks message signing
        vault.setPin("135790").getOrThrow()
        vault.lockSessionNow()
        assertTrue(engine.utilities.signMessageLocally(meta, acc.accountIndex, ChainFamily.EVM, message).isFailure)
    }

    @Test
    fun `ens namehash matches EIP137 vectors and erc20 approve zero calldata encodes cleanly`() {
        assertEquals(
            "0x0000000000000000000000000000000000000000000000000000000000000000",
            CryptoPrimitives.toHex(WalletMessageCrypto.ensNamehash(""), prefix = true)
        )
        assertEquals(
            "0x93cdeb708b7545dc668eb9280176169d1c33cfd8ed6f04690a0bcc88a93fc4ae",
            CryptoPrimitives.toHex(WalletMessageCrypto.ensNamehash("eth"), prefix = true)
        )
        assertEquals(
            "0xee6c4522aab0003e8d14cd40a6af439055fd2577951148c14b6cea9a53475835",
            CryptoPrimitives.toHex(WalletMessageCrypto.ensNamehash("vitalik.eth"), prefix = true)
        )

        val toolsClient = EvmToolsClient(httpClient)
        val approveZero = toolsClient.encodeErc20Approve("0x9858EfFD232B4033E47d90003D41EC34EcaEda94", BigInteger.ZERO)
        assertEquals(
            "0x095ea7b30000000000000000000000009858effd232b4033e47d90003d41ec34ecaeda940000000000000000000000000000000000000000000000000000000000000000",
            approveZero
        )
    }

    @Test
    fun `multi-chain address book validates saves queries via AI bridge and currency preference persists`() = runBlocking {
        // Invalid address rejected
        assertTrue(engine.utilities.saveContact("Bad EVM", ChainFamily.EVM, "0x123").isFailure)

        // Valid contacts save and normalize
        val alice = engine.utilities.saveContact(
            label = "Alice Cold",
            chainFamily = ChainFamily.EVM,
            address = "0x9858effd232b4033e47d90003d41ec34ecaeda94",
            note = "Hardware vault"
        ).getOrThrow()
        assertEquals("0x9858EfFD232B4033E47d90003D41EC34EcaEda94", alice.address)

        val bob = engine.utilities.saveContact(
            label = "Bob Solana",
            chainFamily = ChainFamily.SOLANA,
            address = "11111111111111111111111111111111"
        ).getOrThrow()

        val bridge = AquaAiWalletBridge(engine)
        val lookupText = bridge.lookupAddressBookText("Alice").getOrThrow()
        assertTrue(lookupText.contains("Alice Cold"))
        assertTrue(lookupText.contains("0x9858EfFD232B4033E47d90003D41EC34EcaEda94"))

        engine.utilities.deleteContact(bob.id)
        assertEquals(1, engine.utilities.getAddressBook().size)

        // Display fiat currency persists cleanly
        val s = vault.getSecuritySettings().copy(displayCurrencyCode = DisplayFiatCurrency.EUR.code)
        vault.updateSecuritySettings(s)
        assertEquals("EUR", vault.getSecuritySettings().displayCurrencyCode)
    }

    @Test
    fun `ai wallet access switches new tools vault lock detection and gated contact saving work safely`() = runBlocking {
        val (meta, acc0) = vault.createWallet("Full AI Wallet", wordCount = 12).getOrThrow()
        val acc1 = vault.addDerivedAccount(meta.walletId, "Spending").getOrThrow()
        val secretPhrase = vault.revealMnemonicForBackup(meta.walletId).getOrThrow()
        val bridge = AquaAiWalletBridge(engine)

        val overview = bridge.getPublicOverviewText()
        // ContextManager includes wallet metadata when enabled, omits when paused
        val enabledPrompt = ContextManager.buildSystemInstruction(
            settings = UserSettings(aiWalletAccessEnabled = true, aiWalletTransferPrepareEnabled = true),
            memories = emptyList(),
            enabledSkills = emptyList(),
            activeWallet = null,
            telemetry = DeviceTelemetry(88, false, true, "Wi-Fi", 50, false, false, false),
            browserUrl = "",
            browserTitle = "",
            nativeWalletOverview = overview
        )
        assertTrue(enabledPrompt.contains("Aqua Native Self-Custodial Wallet"))
        assertTrue(enabledPrompt.contains(acc0.evmAddress))
        assertFalse(enabledPrompt.contains(secretPhrase))

        val pausedPrompt = ContextManager.buildSystemInstruction(
            settings = UserSettings(aiWalletAccessEnabled = false),
            memories = emptyList(),
            enabledSkills = emptyList(),
            activeWallet = null,
            telemetry = DeviceTelemetry(88, false, true, "Wi-Fi", 50, false, false, false),
            browserUrl = "",
            browserTitle = "",
            nativeWalletOverview = overview
        )
        assertFalse(pausedPrompt.contains("Aqua Native Self-Custodial Wallet"))
        assertTrue(pausedPrompt.contains("STRICT CRYPTO WALLET BOUNDARY (PAUSED)"))
        assertFalse(pausedPrompt.contains(acc0.evmAddress))

        val app = if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("com.aistudio.aquaai.vqrzmn")
                    .setProjectId("test-project")
                    .setApiKey("test-api-key")
                    .build()
            )
        } else {
            FirebaseApp.getInstance()
        }
        val firestoreRepo = AquaFirestoreRepository(FirebaseFirestore.getInstance(app))
        val toolEngine = ToolCallingEngine(
            deviceController = AndroidDeviceController(context),
            browserController = AquaBrowserController(context),
            walletConnector = FlashWalletConnector(context),
            firestoreRepo = firestoreRepo,
            aiWalletBridge = bridge
        )

        // When aiWalletAccessEnabled = false, all wallet tools refuse cleanly
        val pausedSettings = UserSettings(aiWalletAccessEnabled = false)
        val blockedReceive = toolEngine.executeFunctionCall(
            call = FunctionCallPart("wallet_get_receive_addresses", emptyMap()),
            settings = pausedSettings,
            activeWallet = null,
            onNavigateToBrowser = {},
            requestAuthorization = { true },
            onTrace = {}
        )
        assertTrue(blockedReceive["success"].toString().contains("false"))

        val blockedPrep = toolEngine.executeFunctionCall(
            call = FunctionCallPart(
                "wallet_prepare_transaction",
                mapOf(
                    "destinationAddress" to JsonPrimitive(acc0.evmAddress),
                    "assetSymbol" to JsonPrimitive("ETH"),
                    "amount" to JsonPrimitive("0.01")
                )
            ),
            settings = pausedSettings,
            activeWallet = null,
            onNavigateToBrowser = {},
            requestAuthorization = { true },
            onTrace = {}
        )
        assertTrue(blockedPrep["success"].toString().contains("false"))

        // Read-only mode allows reads & URI parsing while blocking ALL write mutations
        val readOnlySettings = UserSettings(aiWalletAccessEnabled = true, aiWalletTransferPrepareEnabled = false)
        val receiveResp = toolEngine.executeFunctionCall(
            call = FunctionCallPart("wallet_get_receive_addresses", emptyMap()),
            settings = readOnlySettings,
            activeWallet = null,
            onNavigateToBrowser = {},
            requestAuthorization = { false },
            onTrace = {}
        )
        assertTrue(receiveResp["success"].toString().contains("true"))
        assertTrue(receiveResp["message"].toString().contains(acc0.evmAddress))
        assertTrue(receiveResp["message"].toString().contains(acc0.solanaAddress))
        assertFalse(receiveResp["message"].toString().contains(secretPhrase))

        val parseUriResp = toolEngine.executeFunctionCall(
            call = FunctionCallPart(
                "wallet_parse_payment_uri",
                mapOf("uri" to JsonPrimitive("ethereum:${acc0.evmAddress}@1?value=500000000000000000"))
            ),
            settings = readOnlySettings,
            activeWallet = null,
            onNavigateToBrowser = {},
            requestAuthorization = { false },
            onTrace = {}
        )
        assertTrue(parseUriResp["success"].toString().contains("true"))
        assertTrue(parseUriResp["message"].toString().contains("0.5"))

        val blockedWriteSwitch = toolEngine.executeFunctionCall(
            call = FunctionCallPart("wallet_switch_network", mapOf("network" to JsonPrimitive("polygon"))),
            settings = readOnlySettings,
            activeWallet = null,
            onNavigateToBrowser = {},
            requestAuthorization = { true },
            onTrace = {}
        )
        assertTrue(blockedWriteSwitch["success"].toString().contains("false"))

        val blockedRevoke = toolEngine.executeFunctionCall(
            call = FunctionCallPart(
                "wallet_prepare_revoke_allowance",
                mapOf(
                    "token" to JsonPrimitive("USDC"),
                    "spenderAddress" to JsonPrimitive("0x1111111111111111111111111111111111111111")
                )
            ),
            settings = readOnlySettings,
            activeWallet = null,
            onNavigateToBrowser = {},
            requestAuthorization = { true },
            onTrace = {}
        )
        assertTrue(blockedRevoke["success"].toString().contains("false"))

        // Read + Write mode enables network/account/wallet switch, vault creation, tokens, networks, signing, contacts
        val fullSettings = UserSettings(aiWalletAccessEnabled = true, aiWalletTransferPrepareEnabled = true)

        val switchNetResp = toolEngine.executeFunctionCall(
            call = FunctionCallPart("wallet_switch_network", mapOf("network" to JsonPrimitive("polygon"))),
            settings = fullSettings,
            activeWallet = null,
            onNavigateToBrowser = {},
            requestAuthorization = { false },
            onTrace = {}
        )
        assertTrue(switchNetResp["success"].toString().contains("true"))
        assertEquals("polygon_mainnet", vault.getSecuritySettings().activeNetworkId)

        val switchAccResp = toolEngine.executeFunctionCall(
            call = FunctionCallPart("wallet_switch_account", mapOf("account" to JsonPrimitive("Spending"))),
            settings = fullSettings,
            activeWallet = null,
            onNavigateToBrowser = {},
            requestAuthorization = { false },
            onTrace = {}
        )
        assertTrue(switchAccResp["success"].toString().contains("true"))
        assertEquals(acc1.accountIndex, vault.getSecuritySettings().activeAccountIndex)

        // Contact save & delete obey explicit authorization gate
        val deniedContact = toolEngine.executeFunctionCall(
            call = FunctionCallPart(
                "wallet_save_contact",
                mapOf(
                    "label" to JsonPrimitive("Treasury"),
                    "chainFamily" to JsonPrimitive("EVM"),
                    "address" to JsonPrimitive(acc0.evmAddress)
                )
            ),
            settings = fullSettings,
            activeWallet = null,
            onNavigateToBrowser = {},
            requestAuthorization = { false },
            onTrace = {}
        )
        assertTrue(deniedContact["success"].toString().contains("false"))
        assertEquals(0, engine.utilities.getAddressBook().size)

        val approvedContact = toolEngine.executeFunctionCall(
            call = FunctionCallPart(
                "wallet_save_contact",
                mapOf(
                    "label" to JsonPrimitive("Treasury"),
                    "chainFamily" to JsonPrimitive("EVM"),
                    "address" to JsonPrimitive(acc0.evmAddress),
                    "note" to JsonPrimitive("Verified self")
                )
            ),
            settings = fullSettings,
            activeWallet = null,
            onNavigateToBrowser = {},
            requestAuthorization = { auth -> auth.isFinancialOrSecurity },
            onTrace = {}
        )
        assertTrue(approvedContact["success"].toString().contains("true"))
        assertEquals(1, engine.utilities.getAddressBook().size)

        val deleteContactResp = toolEngine.executeFunctionCall(
            call = FunctionCallPart("wallet_delete_contact", mapOf("queryOrId" to JsonPrimitive("Treasury"))),
            settings = fullSettings,
            activeWallet = null,
            onNavigateToBrowser = {},
            requestAuthorization = { true },
            onTrace = {}
        )
        assertTrue(deleteContactResp["success"].toString().contains("true"))
        assertEquals(0, engine.utilities.getAddressBook().size)

        // Sign and verify off-chain ownership proof via Read + Write tools
        val signMsgResp = toolEngine.executeFunctionCall(
            call = FunctionCallPart(
                "wallet_sign_message",
                mapOf("chainFamily" to JsonPrimitive("EVM"), "message" to JsonPrimitive("Aqua Write Proof"))
            ),
            settings = fullSettings,
            activeWallet = null,
            onNavigateToBrowser = {},
            requestAuthorization = { true },
            onTrace = {}
        )
        assertTrue(signMsgResp["success"].toString().contains("true"))
        val signedText = signMsgResp["message"].toString().trim('"')
        val sigHex = signedText.substringAfter("Signature: ").trim()
        assertTrue(sigHex.startsWith("0x"))

        val verifyMsgResp = toolEngine.executeFunctionCall(
            call = FunctionCallPart(
                "wallet_verify_message",
                mapOf(
                    "chainFamily" to JsonPrimitive("EVM"),
                    "signerAddress" to JsonPrimitive(acc1.evmAddress),
                    "message" to JsonPrimitive("Aqua Write Proof"),
                    "signature" to JsonPrimitive(sigHex)
                )
            ),
            settings = readOnlySettings,
            activeWallet = null,
            onNavigateToBrowser = {},
            requestAuthorization = { false },
            onTrace = {}
        )
        assertTrue(verifyMsgResp["success"].toString().contains("true"))
        assertTrue(verifyMsgResp["message"].toString().contains("VALID"))

        // Add custom token, custom network, account derivation, watch wallet & new HD wallet (zero-key safe)
        val addTokResp = toolEngine.executeFunctionCall(
            call = FunctionCallPart(
                "wallet_add_custom_token",
                mapOf(
                    "network" to JsonPrimitive("eth_mainnet"),
                    "symbol" to JsonPrimitive("UNI"),
                    "name" to JsonPrimitive("Uniswap"),
                    "contractOrMint" to JsonPrimitive("0x1f9840a85d5af5bf1d1762f925bdaddc4201f984"),
                    "decimals" to JsonPrimitive(18)
                )
            ),
            settings = fullSettings,
            activeWallet = null,
            onNavigateToBrowser = {},
            requestAuthorization = { true },
            onTrace = {}
        )
        assertTrue(addTokResp["success"].toString().contains("true"))
        assertTrue(engine.getTokensForNetwork("eth_mainnet").any { it.symbol == "UNI" })

        val addNetResp = toolEngine.executeFunctionCall(
            call = FunctionCallPart(
                "wallet_add_custom_network",
                mapOf(
                    "name" to JsonPrimitive("Optimism"),
                    "nativeSymbol" to JsonPrimitive("ETH"),
                    "chainId" to JsonPrimitive(10),
                    "rpcUrl" to JsonPrimitive("https://mainnet.optimism.io")
                )
            ),
            settings = fullSettings,
            activeWallet = null,
            onNavigateToBrowser = {},
            requestAuthorization = { true },
            onTrace = {}
        )
        assertTrue(addNetResp["success"].toString().contains("true"))
        assertTrue(engine.getAvailableNetworks(true).any { it.chainId == 10L })

        val createWalletResp = toolEngine.executeFunctionCall(
            call = FunctionCallPart(
                "wallet_create_wallet",
                mapOf("label" to JsonPrimitive("Savings Vault"), "wordCount" to JsonPrimitive(12))
            ),
            settings = fullSettings,
            activeWallet = null,
            onNavigateToBrowser = {},
            requestAuthorization = { true },
            onTrace = {}
        )
        assertTrue(createWalletResp["success"].toString().contains("true"))
        val newWalletMeta = vault.getAllWallets().first { it.label == "Savings Vault" }
        val newWalletPhrase = vault.revealMnemonicForBackup(newWalletMeta.walletId).getOrThrow()
        assertFalse(createWalletResp["message"].toString().contains(newWalletPhrase))

        val prefsResp = toolEngine.executeFunctionCall(
            call = FunctionCallPart(
                "wallet_update_preferences",
                mapOf("displayCurrencyCode" to JsonPrimitive("GBP"), "testnetModeEnabled" to JsonPrimitive(true))
            ),
            settings = fullSettings,
            activeWallet = null,
            onNavigateToBrowser = {},
            requestAuthorization = { false },
            onTrace = {}
        )
        assertTrue(prefsResp["success"].toString().contains("true"))
        assertEquals("GBP", vault.getSecuritySettings().displayCurrencyCode)
        assertTrue(vault.getSecuritySettings().testnetModeEnabled)

        // Bridge reflects vault PIN lock & emergency lock accurately
        assertFalse(bridge.isVaultLockedForSigning())
        vault.setPin("654321").getOrThrow()
        vault.lockSessionNow()
        assertTrue(bridge.isVaultLockedForSigning())
        assertFalse(bridge.isEmergencyLocked())

        vault.triggerEmergencyLock()
        assertTrue(bridge.isVaultLockedForSigning())
        assertTrue(bridge.isEmergencyLocked())
    }
}


