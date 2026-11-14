package com.example.ui.screens.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AquaAbyss
import com.example.ui.theme.AquaAmber
import com.example.ui.theme.AquaBorderGlow
import com.example.ui.theme.AquaCardSurface
import com.example.ui.theme.AquaCoralError
import com.example.ui.theme.AquaCyan
import com.example.ui.theme.AquaDeepSurface
import com.example.ui.theme.AquaIceText
import com.example.ui.theme.AquaMintSuccess
import com.example.ui.theme.AquaMutedText
import com.example.ui.theme.AquaTeal
import com.example.wallet.core.AquaWalletCoordinator
import com.example.wallet.core.NetworkConfig
import com.example.wallet.core.TokenDefinition
import com.example.wallet.core.TxStatus
import com.example.wallet.core.WalletTxRecord
import java.text.DateFormat
import java.util.Date

@Composable
fun WalletTransactionsSection(
    transactions: List<WalletTxRecord>,
    busy: Boolean,
    coordinator: AquaWalletCoordinator,
    onRetryTx: (WalletTxRecord) -> Unit,
    onOpenExplorer: (String) -> Unit
) {
    var statusFilter by remember { mutableStateOf("ALL") }
    val filtered = remember(transactions, statusFilter) {
        when (statusFilter) {
            "PENDING", "CONFIRMED", "FAILED" -> transactions.filter { it.status.name == statusFilter }
            else -> transactions
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AquaAbyss),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("TRANSACTION ACTIVITY", style = MaterialTheme.typography.titleMedium, color = AquaIceText)
                OutlinedButton(
                    onClick = { coordinator.refreshTransactions() },
                    enabled = !busy,
                    modifier = Modifier.height(48.dp).testTag("tx_sync_button")
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = AquaCyan, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Sync Chain", color = AquaCyan)
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("ALL", "PENDING", "CONFIRMED", "FAILED").forEach { f ->
                    FilterChip(
                        selected = statusFilter == f,
                        onClick = { statusFilter = f },
                        label = { Text(f) },
                        modifier = Modifier.testTag("tx_filter_$f")
                    )
                }
            }
        }

        item {
            Text(
                text = "Locally broadcast EVM, Solana, and Bitcoin transactions start as PENDING until confirmed on-chain. Live Solana and Bitcoin inbound/outbound history syncs directly from chain RPCs/Esplora.",
                style = MaterialTheme.typography.bodySmall,
                color = AquaMutedText
            )
        }

        if (filtered.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No transactions matching '$statusFilter' yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AquaMutedText,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(filtered, key = { it.txHash + it.idempotencyKey }) { tx ->
                val statusColor = when (tx.status) {
                    TxStatus.CONFIRMED -> AquaMintSuccess
                    TxStatus.PENDING -> AquaAmber
                    TxStatus.FAILED -> AquaCoralError
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, statusColor.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${tx.direction.name} • ${tx.amountDisplay}",
                                style = MaterialTheme.typography.titleSmall,
                                color = AquaIceText,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                color = statusColor.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "${tx.status.name} (${tx.confirmations} conf)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = statusColor,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                        Text(
                            text = "${tx.networkName} • Fee: ${tx.feeDisplay}",
                            style = MaterialTheme.typography.labelMedium,
                            color = AquaTeal
                        )
                        Text(
                            text = "Hash: ${tx.txHash}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AquaMutedText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = DateFormat.getDateTimeInstance().format(Date(tx.timestampMs)),
                            style = MaterialTheme.typography.labelSmall,
                            color = AquaMutedText
                        )
                        tx.errorMessage?.takeIf { it.isNotBlank() }?.let { err ->
                            Text("Failure reason: $err", color = AquaCoralError, style = MaterialTheme.typography.bodySmall)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (tx.status == TxStatus.PENDING) {
                                OutlinedButton(
                                    onClick = { coordinator.refreshSingleTxStatus(tx) },
                                    modifier = Modifier.weight(1f).height(48.dp)
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = AquaAmber, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Check Status", color = AquaAmber)
                                }
                            }
                            if (tx.status == TxStatus.FAILED) {
                                OutlinedButton(
                                    onClick = { onRetryTx(tx) },
                                    modifier = Modifier.weight(1f).height(48.dp)
                                ) {
                                    Icon(Icons.Filled.Replay, contentDescription = null, tint = AquaCyan, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Retry", color = AquaCyan)
                                }
                            }
                            if (tx.explorerUrl.isNotBlank()) {
                                OutlinedButton(
                                    onClick = { onOpenExplorer(tx.explorerUrl) },
                                    modifier = Modifier.weight(1f).height(48.dp)
                                ) {
                                    Icon(Icons.Filled.OpenInBrowser, contentDescription = null, tint = AquaCyan, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Explorer", color = AquaCyan)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WalletNetworksAndTokensSection(
    networks: List<NetworkConfig>,
    activeNetworkId: String,
    testnetModeEnabled: Boolean,
    tokens: List<TokenDefinition>,
    coordinator: AquaWalletCoordinator
) {
    var showAddNetForm by remember { mutableStateOf(false) }
    var netName by remember { mutableStateOf("") }
    var netSymbol by remember { mutableStateOf("") }
    var netChainId by remember { mutableStateOf("") }
    var netRpc by remember { mutableStateOf("") }
    var netExplorer by remember { mutableStateOf("") }
    var netIsTestnet by remember { mutableStateOf(false) }

    var showAddTokenForm by remember { mutableStateOf(false) }
    var tokSymbol by remember { mutableStateOf("") }
    var tokName by remember { mutableStateOf("") }
    var tokContract by remember { mutableStateOf("") }
    var tokDecimals by remember { mutableStateOf("18") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AquaAbyss),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, AquaBorderGlow, RoundedCornerShape(18.dp))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enable Testnets", style = MaterialTheme.typography.titleSmall, color = AquaIceText)
                        Text(
                            "Includes Ethereum Sepolia, Solana Devnet & Bitcoin Testnet4",
                            style = MaterialTheme.typography.bodySmall,
                            color = AquaMutedText
                        )
                    }
                    Switch(
                        checked = testnetModeEnabled,
                        onCheckedChange = { coordinator.toggleTestnetMode(it) },
                        modifier = Modifier.testTag("networks_testnet_switch")
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("BLOCKCHAIN NETWORKS", style = MaterialTheme.typography.labelLarge, color = AquaCyan)
                OutlinedButton(
                    onClick = { showAddNetForm = !showAddNetForm },
                    modifier = Modifier.height(48.dp).testTag("networks_add_custom_button")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = AquaCyan, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Custom EVM RPC", color = AquaCyan)
                }
            }
        }

        if (showAddNetForm) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AquaDeepSurface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, AquaCyan.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Add Custom EVM Network", style = MaterialTheme.typography.titleSmall, color = AquaIceText)
                        WalletField(netName, { netName = it }, "Network Name", "Optimism")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WalletField(netSymbol, { netSymbol = it }, "Native Symbol", "ETH", modifier = Modifier.weight(1f))
                            WalletField(netChainId, { netChainId = it }, "Chain ID", "10", modifier = Modifier.weight(1f))
                        }
                        WalletField(netRpc, { netRpc = it }, "HTTPS RPC URL", "https://...")
                        WalletField(netExplorer, { netExplorer = it }, "Explorer Tx URL Base", "https://optimistic.etherscan.io/tx/")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = netIsTestnet, onCheckedChange = { netIsTestnet = it })
                            Spacer(Modifier.width(8.dp))
                            Text("Mark as Testnet", color = AquaMutedText, style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            onClick = {
                                val cid = netChainId.toLongOrNull() ?: 0L
                                coordinator.addCustomEvmNetwork(netName, netSymbol, cid, netRpc, netExplorer, netIsTestnet)
                                showAddNetForm = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AquaCyan, contentColor = AquaAbyss),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("Save Custom Network", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        items(networks, key = { it.id }) { net ->
            val isActive = net.id == activeNetworkId
            Card(
                colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, if (isActive) AquaCyan else AquaBorderGlow, RoundedCornerShape(16.dp))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${net.name} (${net.nativeSymbol})",
                            style = MaterialTheme.typography.titleSmall,
                            color = AquaIceText
                        )
                        Text(
                            text = "${net.chainFamily.name} • Chain ID ${net.chainId}${if (net.isTestnet) " • TESTNET" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AquaTeal
                        )
                        Text(
                            text = net.defaultRpcUrl,
                            style = MaterialTheme.typography.labelSmall,
                            color = AquaMutedText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (isActive) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = "Active Network", tint = AquaMintSuccess)
                    } else {
                        OutlinedButton(
                            onClick = { coordinator.selectNetwork(net.id) },
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("Select", color = AquaCyan)
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("TOKENS ON ACTIVE NETWORK", style = MaterialTheme.typography.labelLarge, color = AquaCyan)
                OutlinedButton(
                    onClick = { showAddTokenForm = !showAddTokenForm },
                    modifier = Modifier.height(48.dp).testTag("tokens_add_custom_button")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = AquaCyan, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Token", color = AquaCyan)
                }
            }
        }

        if (showAddTokenForm) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AquaDeepSurface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, AquaCyan.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Add Custom ERC-20 / SPL Token", style = MaterialTheme.typography.titleSmall, color = AquaIceText)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WalletField(tokSymbol, { tokSymbol = it }, "Symbol", "LINK", modifier = Modifier.weight(1f))
                            OutlinedTextField(
                                value = tokDecimals,
                                onValueChange = { if (it.all(Char::isDigit)) tokDecimals = it },
                                label = { Text("Decimals") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AquaCyan,
                                    unfocusedBorderColor = AquaBorderGlow,
                                    focusedTextColor = AquaIceText,
                                    unfocusedTextColor = AquaIceText
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        WalletField(tokName, { tokName = it }, "Token Name", "Chainlink")
                        WalletField(tokContract, { tokContract = it }, "Contract / Mint Address", "0x... or SPL Mint")
                        Button(
                            onClick = {
                                val dec = tokDecimals.toIntOrNull() ?: 18
                                coordinator.addCustomToken(activeNetworkId, tokSymbol, tokName, tokContract, dec)
                                showAddTokenForm = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AquaCyan, contentColor = AquaAbyss),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("Save Token", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        items(tokens, key = { it.id }) { tok ->
            Surface(
                color = AquaDeepSurface,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("${tok.symbol} — ${tok.name} (${tok.decimals} decimals)", color = AquaIceText, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = if (tok.isNative) "Native Gas Asset" else "Contract/Mint: ${tok.contractOrMintAddress}",
                        color = AquaMutedText,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
