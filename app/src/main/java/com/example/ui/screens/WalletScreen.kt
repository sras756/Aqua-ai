package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.AquaMainViewModel
import com.example.ui.screens.wallet.QrScannerSheet
import com.example.ui.screens.wallet.WalletAiAssistantSection
import com.example.ui.screens.wallet.WalletDashboardAndAnalyticsSection
import com.example.ui.screens.wallet.WalletLockGateScreen
import com.example.ui.screens.wallet.WalletNetworksAndTokensSection
import com.example.ui.screens.wallet.WalletOnboardingView
import com.example.ui.screens.wallet.WalletReceiveSection
import com.example.ui.screens.wallet.WalletSecurityRecoveryAndExtensionsSection
import com.example.ui.screens.wallet.WalletSendSection
import com.example.ui.screens.wallet.WalletTransactionsSection
import com.example.ui.theme.AquaAbyss
import com.example.ui.theme.AquaCoralError
import com.example.ui.theme.AquaCyan
import com.example.ui.theme.AquaDeepSurface
import com.example.ui.theme.AquaIceText
import com.example.ui.theme.AquaMutedText
import com.example.ui.theme.AquaTeal

private val WALLET_SECTIONS = listOf(
    "Overview",
    "Send",
    "Receive",
    "Activity",
    "Networks",
    "Aqua AI",
    "Security"
)

@Composable
fun WalletScreen(viewModel: AquaMainViewModel) {
    val coordinator = viewModel.walletCoordinator
    val wallets by coordinator.wallets.collectAsStateWithLifecycle()
    val accounts by coordinator.accounts.collectAsStateWithLifecycle()
    val securitySettings by coordinator.securitySettings.collectAsStateWithLifecycle()
    val isLocked by coordinator.isLocked.collectAsStateWithLifecycle()
    val networks by coordinator.availableNetworks.collectAsStateWithLifecycle()
    val tokens by coordinator.tokensForActiveNetwork.collectAsStateWithLifecycle()
    val portfolio by coordinator.portfolioSummary.collectAsStateWithLifecycle()
    val transactions by coordinator.transactions.collectAsStateWithLifecycle()
    val preparedTx by coordinator.preparedTx.collectAsStateWithLifecycle()
    val feeEstimate by coordinator.feeEstimate.collectAsStateWithLifecycle()
    val riskReport by coordinator.riskReport.collectAsStateWithLifecycle()
    val busy by coordinator.busy.collectAsStateWithLifecycle()
    val revealedMnemonic by coordinator.revealedMnemonic.collectAsStateWithLifecycle()
    val exportedBackupJson by coordinator.exportedBackupJson.collectAsStateWithLifecycle()

    val activeWallet = wallets.firstOrNull { it.walletId == securitySettings.activeWalletId } ?: wallets.firstOrNull()
    val activeAccount = accounts.firstOrNull { it.accountIndex == securitySettings.activeAccountIndex } ?: accounts.firstOrNull()

    var activeSection by rememberSaveable { mutableIntStateOf(0) } // 0..6
    var showAddWalletModal by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var walletMenuExpanded by remember { mutableStateOf(false) }
    var accountMenuExpanded by remember { mutableStateOf(false) }

    // Lifted Send draft state so QR scan & asset row click populate seamlessly
    var sendNetworkId by rememberSaveable { mutableStateOf(securitySettings.activeNetworkId) }
    var sendTokenId by rememberSaveable { mutableStateOf("") }
    var sendToAddress by rememberSaveable { mutableStateOf("") }
    var sendAmount by rememberSaveable { mutableStateOf("") }
    var sendTokensList by remember { mutableStateOf(tokens) }

    LaunchedEffect(securitySettings.activeNetworkId, networks) {
        if (networks.none { it.id == sendNetworkId }) {
            sendNetworkId = networks.firstOrNull { it.id == securitySettings.activeNetworkId }?.id
                ?: networks.firstOrNull()?.id.orEmpty()
        }
    }

    LaunchedEffect(sendNetworkId) {
        if (sendNetworkId.isNotBlank()) {
            val loaded = coordinator.engine.getTokensForNetwork(sendNetworkId)
            sendTokensList = loaded
            if (loaded.none { it.id == sendTokenId }) {
                sendTokenId = loaded.firstOrNull()?.id.orEmpty()
            }
        }
    }

    if (showAddWalletModal) {
        BackHandler { showAddWalletModal = false }
    } else if (activeSection != 0) {
        BackHandler { activeSection = 0 }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AquaAbyss)
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Native Wallet Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.AccountBalanceWallet, contentDescription = null, tint = AquaCyan)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "AQUA CRYPTO WALLET",
                            style = MaterialTheme.typography.titleMedium,
                            color = AquaIceText,
                            fontWeight = FontWeight.Bold
                        )
                        if (activeWallet != null && activeAccount != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.clickable { walletMenuExpanded = true }
                                    ) {
                                        Text(
                                            text = activeWallet.label,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = AquaCyan
                                        )
                                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "Switch wallet", tint = AquaCyan, modifier = Modifier.size(18.dp))
                                    }
                                    DropdownMenu(
                                        expanded = walletMenuExpanded,
                                        onDismissRequest = { walletMenuExpanded = false }
                                    ) {
                                        wallets.forEach { w ->
                                            DropdownMenuItem(
                                                text = { Text("${w.label}${if (w.isWatchOnly) " (Watch)" else ""}") },
                                                onClick = {
                                                    coordinator.selectWallet(w.walletId)
                                                    walletMenuExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                                Text(" • ", color = AquaMutedText, style = MaterialTheme.typography.labelMedium)
                                Box {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.clickable { accountMenuExpanded = true }
                                    ) {
                                        Text(
                                            text = activeAccount.label,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = AquaTeal
                                        )
                                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "Switch account", tint = AquaTeal, modifier = Modifier.size(18.dp))
                                    }
                                    DropdownMenu(
                                        expanded = accountMenuExpanded,
                                        onDismissRequest = { accountMenuExpanded = false }
                                    ) {
                                        accounts.forEach { acc ->
                                            DropdownMenuItem(
                                                text = { Text("${acc.label} (#${acc.accountIndex})") },
                                                onClick = {
                                                    coordinator.selectAccount(acc.accountIndex)
                                                    accountMenuExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Text("Native Self-Custodial Multi-Chain Engine", style = MaterialTheme.typography.labelSmall, color = AquaMutedText)
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (securitySettings.emergencyLocked) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = "Emergency Lock Active",
                            tint = AquaCoralError,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                    if (activeWallet != null) {
                        IconButton(
                            onClick = { showAddWalletModal = true },
                            modifier = Modifier.size(48.dp).testTag("wallet_add_new_button")
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Add or import wallet", tint = AquaCyan)
                        }
                        if (securitySettings.hasPin || securitySettings.biometricEnabled) {
                            IconButton(
                                onClick = { coordinator.lockNow() },
                                modifier = Modifier.size(48.dp).testTag("wallet_lock_button")
                            ) {
                                Icon(Icons.Filled.Lock, contentDescription = "Lock wallet session", tint = AquaIceText)
                            }
                        }
                    }
                }
            }

            when {
                activeWallet == null || activeAccount == null -> {
                    WalletOnboardingView(coordinator = coordinator, busy = busy)
                }

                isLocked -> {
                    WalletLockGateScreen(settings = securitySettings, coordinator = coordinator)
                }

                else -> {
                    // Horizontal Sub-Tab Bar across all Wallet modules
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AquaDeepSurface)
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WALLET_SECTIONS.forEachIndexed { idx, label ->
                            FilterChip(
                                selected = activeSection == idx,
                                onClick = { activeSection = idx },
                                label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AquaCyan.copy(alpha = 0.22f),
                                    selectedLabelColor = AquaCyan,
                                    labelColor = AquaMutedText
                                ),
                                modifier = Modifier.testTag("wallet_subtab_$idx")
                            )
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        when (activeSection) {
                            0 -> WalletDashboardAndAnalyticsSection(
                                walletMeta = activeWallet,
                                account = activeAccount,
                                portfolio = portfolio,
                                busy = busy,
                                onRefresh = { coordinator.refreshPortfolio() },
                                onNavigateToSend = { netId, tokId ->
                                    if (!netId.isNullOrBlank()) sendNetworkId = netId
                                    if (!tokId.isNullOrBlank()) sendTokenId = tokId
                                    activeSection = 1
                                },
                                onNavigateToReceive = { activeSection = 2 },
                                onOpenScanner = { showQrScanner = true },
                                onOpenBackupSecurity = { activeSection = 6 }
                            )

                            1 -> WalletSendSection(
                                walletMeta = activeWallet,
                                networks = networks,
                                selectedNetworkId = sendNetworkId,
                                tokens = sendTokensList,
                                selectedTokenId = sendTokenId,
                                toAddress = sendToAddress,
                                amountText = sendAmount,
                                onSelectNetwork = { sendNetworkId = it },
                                onSelectToken = { sendTokenId = it },
                                onToAddressChange = { sendToAddress = it },
                                onAmountChange = { sendAmount = it },
                                onOpenScanner = { showQrScanner = true },
                                feeEstimate = feeEstimate,
                                riskReport = riskReport,
                                preparedTx = preparedTx,
                                securitySettings = securitySettings,
                                isLocked = isLocked,
                                busy = busy,
                                coordinator = coordinator,
                                onBroadcastSuccess = {
                                    sendToAddress = ""
                                    sendAmount = ""
                                    activeSection = 3
                                }
                            )

                            2 -> WalletReceiveSection(
                                account = activeAccount,
                                includeTestnets = securitySettings.testnetModeEnabled,
                                onCopyAddress = { label, addr -> viewModel.copyToClipboard(label, addr) },
                                onShareAddress = { label, addr -> viewModel.shareText(label, addr) }
                            )

                            3 -> WalletTransactionsSection(
                                transactions = transactions,
                                busy = busy,
                                coordinator = coordinator,
                                onRetryTx = { tx ->
                                    sendNetworkId = tx.networkId
                                    sendToAddress = tx.toAddress
                                    sendAmount = tx.amountDisplay.substringBefore(" ").trim()
                                    activeSection = 1
                                },
                                onOpenExplorer = { url ->
                                    viewModel.navigateBrowser(url)
                                    viewModel.openTab("browser")
                                }
                            )

                            4 -> WalletNetworksAndTokensSection(
                                networks = networks,
                                activeNetworkId = securitySettings.activeNetworkId,
                                testnetModeEnabled = securitySettings.testnetModeEnabled,
                                tokens = tokens,
                                coordinator = coordinator
                            )

                            5 -> WalletAiAssistantSection(
                                onSendAiWalletPrompt = { prompt ->
                                    viewModel.startNewConversation()
                                    viewModel.sendMessage(prompt)
                                    viewModel.openTab("home")
                                }
                            )

                            6 -> WalletSecurityRecoveryAndExtensionsSection(
                                walletMeta = activeWallet,
                                accounts = accounts,
                                activeAccountIndex = securitySettings.activeAccountIndex,
                                securitySettings = securitySettings,
                                revealedMnemonic = revealedMnemonic,
                                exportedBackupJson = exportedBackupJson,
                                coordinator = coordinator,
                                onCopyText = { l, t -> viewModel.copyToClipboard(l, t) },
                                onShareText = { l, t -> viewModel.shareText(l, t) }
                            )
                        }
                    }
                }
            }
        }

        if (showAddWalletModal) {
            Surface(
                color = AquaAbyss,
                shape = RoundedCornerShape(0.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                WalletOnboardingView(
                    coordinator = coordinator,
                    busy = busy,
                    showCancel = true,
                    onDismiss = { showAddWalletModal = false }
                )
            }
        }

        if (showQrScanner) {
            QrScannerSheet(
                onQrScanned = { raw ->
                    val parsed = coordinator.engine.parseScannedPaymentUri(raw)
                    sendToAddress = parsed.address
                    if (!parsed.suggestedNetworkId.isNullOrBlank()) {
                        sendNetworkId = parsed.suggestedNetworkId
                    }
                    if (!parsed.suggestedAmount.isNullOrBlank()) {
                        sendAmount = parsed.suggestedAmount
                    }
                    showQrScanner = false
                    activeSection = 1
                },
                onDismiss = { showQrScanner = false }
            )
        }
    }
}
