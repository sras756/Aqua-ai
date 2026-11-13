package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.WalletConfig
import com.example.ui.AquaMainViewModel
import com.example.ui.theme.AquaAbyss
import com.example.ui.theme.AquaAmber
import com.example.ui.theme.AquaAzure
import com.example.ui.theme.AquaBorderGlow
import com.example.ui.theme.AquaCardSurface
import com.example.ui.theme.AquaCoralError
import com.example.ui.theme.AquaCyan
import com.example.ui.theme.AquaDeepSurface
import com.example.ui.theme.AquaIceText
import com.example.ui.theme.AquaMintSuccess
import com.example.ui.theme.AquaMutedText
import com.example.ui.theme.AquaTeal
import com.example.wallet.QrCodeGenerator
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(viewModel: AquaMainViewModel) {
    val wallets by viewModel.wallets.collectAsStateWithLifecycle()
    val discovery by viewModel.walletDiscovery.collectAsStateWithLifecycle()
    val balanceSnapshot by viewModel.walletBalance.collectAsStateWithLifecycle()
    val walletBusy by viewModel.walletBusy.collectAsStateWithLifecycle()
    val preparedTransfer by viewModel.preparedTransfer.collectAsStateWithLifecycle()

    val savedWallet = wallets.firstOrNull()
    val scope = rememberCoroutineScope()

    var productName by remember(savedWallet?.id) { mutableStateOf(savedWallet?.walletProductName ?: "") }
    var developerName by remember(savedWallet?.id) { mutableStateOf(savedWallet?.developerName ?: "") }
    var packageName by remember(savedWallet?.id) { mutableStateOf(savedWallet?.packageName ?: "") }
    var docsUrl by remember(savedWallet?.id) { mutableStateOf(savedWallet?.docsUrl ?: "") }
    var networkName by remember(savedWallet?.id) {
        mutableStateOf(savedWallet?.networkName?.ifBlank { "Ethereum Mainnet" } ?: "Ethereum Mainnet")
    }
    var rpcEndpoint by remember(savedWallet?.id) { mutableStateOf(savedWallet?.rpcEndpoint ?: "") }
    var publicAddress by remember(savedWallet?.id) { mutableStateOf(savedWallet?.publicAddress ?: "") }

    var networkExpanded by remember { mutableStateOf(false) }
    val networkOptions = listOf(
        "Ethereum Mainnet",
        "Polygon",
        "BNB Smart Chain",
        "Arbitrum One",
        "Base",
        "Bitcoin Mainnet",
        "Ethereum Sepolia"
    )

    var showQrCode by remember { mutableStateOf(false) }

    // CoinGecko live price state
    var priceQuery by remember { mutableStateOf("ethereum") }
    var priceResult by remember { mutableStateOf<String?>(null) }
    var priceBusy by remember { mutableStateOf(false) }

    // Transfer preparation inputs
    var transferDest by remember { mutableStateOf("") }
    var transferAsset by remember { mutableStateOf("ETH") }
    var transferAmount by remember { mutableStateOf("") }

    LaunchedEffect(networkName) {
        transferAsset = when {
            networkName.contains("Bitcoin", ignoreCase = true) -> "BTC"
            networkName.contains("BNB", ignoreCase = true) -> "BNB"
            networkName.contains("Polygon", ignoreCase = true) -> "POL"
            else -> "ETH"
        }
    }

    fun buildCurrentConfig(): WalletConfig {
        return (savedWallet ?: WalletConfig()).copy(
            walletProductName = productName.trim(),
            developerName = developerName.trim(),
            packageName = packageName.trim(),
            docsUrl = docsUrl.trim(),
            networkName = networkName,
            rpcEndpoint = rpcEndpoint.trim(),
            publicAddress = publicAddress.trim(),
            integrationMethod = when {
                publicAddress.isNotBlank() -> "read_only_rpc"
                packageName.isNotBlank() -> "package_intent"
                else -> "unverified"
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AquaAbyss)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AQUA WALLET CONNECTOR",
                    style = MaterialTheme.typography.headlineSmall,
                    color = AquaIceText
                )
                Text(
                    text = "Flash Crypto Wallet • Non-Custodial & Read-Only",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AquaMutedText
                )
            }
            if (savedWallet != null) {
                OutlinedButton(
                    onClick = {
                        viewModel.disconnectWallet(savedWallet)
                        productName = ""
                        developerName = ""
                        packageName = ""
                        docsUrl = ""
                        rpcEndpoint = ""
                        publicAddress = ""
                    },
                    modifier = Modifier.testTag("wallet_disconnect_button")
                ) {
                    Icon(Icons.Filled.LinkOff, contentDescription = null, tint = AquaCoralError, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Disconnect", color = AquaCoralError)
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Security Guarantee Notice
            item {
                Surface(
                    color = AquaCardSurface,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AquaCyan.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Security,
                            contentDescription = "Non-custodial guarantee",
                            tint = AquaCyan,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Aqua never guesses undocumented Flash Wallet SDKs, never generates fake balances, and never stores private keys or seed phrases. Signing is delegated strictly to your official external wallet after explicit approval.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AquaMutedText
                        )
                    }
                }
            }

            // Flash Wallet Identity & Network Configuration Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AquaBorderGlow, RoundedCornerShape(20.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.AccountBalanceWallet, contentDescription = null, tint = AquaAmber)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "1. Flash Wallet Identity & Endpoint",
                                style = MaterialTheme.typography.titleMedium,
                                color = AquaIceText
                            )
                        }

                        WalletTextField(
                            value = productName,
                            onValueChange = { productName = it },
                            label = "Official Flash Wallet Product Name",
                            placeholder = "Exact app/product name"
                        )
                        WalletTextField(
                            value = developerName,
                            onValueChange = { developerName = it },
                            label = "Official Developer / Publisher",
                            placeholder = "Publisher name"
                        )
                        WalletTextField(
                            value = packageName,
                            onValueChange = { packageName = it },
                            label = "Android Package Name (e.g. com.example.flashwallet)",
                            placeholder = "Required to inspect installed app & wc: intent"
                        )
                        WalletTextField(
                            value = docsUrl,
                            onValueChange = { docsUrl = it },
                            label = "Official Integration / SDK Docs URL",
                            placeholder = "https://..."
                        )

                        ExposedDropdownMenuBox(
                            expanded = networkExpanded,
                            onExpandedChange = { networkExpanded = !networkExpanded }
                        ) {
                            OutlinedTextField(
                                value = networkName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Blockchain Network") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = networkExpanded) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AquaCyan,
                                    unfocusedBorderColor = AquaBorderGlow,
                                    focusedTextColor = AquaIceText,
                                    unfocusedTextColor = AquaIceText
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor()
                            )
                            ExposedDropdownMenu(
                                expanded = networkExpanded,
                                onDismissRequest = { networkExpanded = false }
                            ) {
                                networkOptions.forEach { opt ->
                                    DropdownMenuItem(
                                        text = { Text(opt) },
                                        onClick = {
                                            networkName = opt
                                            networkExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        WalletTextField(
                            value = rpcEndpoint,
                            onValueChange = { rpcEndpoint = it },
                            label = "HTTPS JSON-RPC URL (Optional for default public EVM RPCs)",
                            placeholder = "https://ethereum-rpc.publicnode.com"
                        )
                        WalletTextField(
                            value = publicAddress,
                            onValueChange = { publicAddress = it },
                            label = "Public Wallet Address (Read-Only)",
                            placeholder = "0x... or bc1...",
                            modifier = Modifier.testTag("wallet_public_address_input")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { viewModel.saveAndDiscoverWallet(buildCurrentConfig()) },
                                colors = ButtonDefaults.buttonColors(containerColor = AquaCyan, contentColor = AquaAbyss),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("wallet_discover_button")
                            ) {
                                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Save & Discover", fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { viewModel.refreshWalletBalances(buildCurrentConfig()) },
                                enabled = !walletBusy && publicAddress.isNotBlank(),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("wallet_refresh_balance_button")
                            ) {
                                if (walletBusy) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AquaCyan)
                                } else {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = AquaTeal, modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(6.dp))
                                Text("Live Balance", color = AquaTeal)
                            }
                        }
                    }
                }
            }

            // Integration Discovery Diagnostic Result
            discovery?.let { disc ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = AquaDeepSurface),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                if (disc.packageInstalled) AquaMintSuccess else AquaAmber,
                                RoundedCornerShape(18.dp)
                            )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (disc.packageInstalled) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = if (disc.packageInstalled) AquaMintSuccess else AquaAmber
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Integration Discovery Report",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = AquaIceText
                                )
                            }
                            Text(
                                text = "App Installed: ${if (disc.packageInstalled) "Yes (${disc.resolvedAppLabel})" else "No / Not Verified"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AquaIceText
                            )
                            Text(
                                text = "WalletConnect (wc:) Handler: ${if (disc.supportsWalletConnectScheme) "Detected" else "Not Detected"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AquaMutedText
                            )
                            if (disc.limitationNotice.isNotBlank()) {
                                Text(
                                    text = disc.limitationNotice,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AquaAmber
                                )
                            }
                            disc.recommendedAlternatives.forEach { alt ->
                                Text(
                                    text = "• $alt",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AquaMutedText
                                )
                            }
                        }
                    }
                }
            }

            // Live On-Chain Balance & Transactions
            balanceSnapshot?.let { snap ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, AquaMintSuccess.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "LIVE ON-CHAIN PORTFOLIO (${snap.networkName})",
                                style = MaterialTheme.typography.labelMedium,
                                color = AquaTeal
                            )
                            Text(
                                text = snap.nativeBalance,
                                style = MaterialTheme.typography.headlineMedium,
                                color = AquaIceText,
                                fontWeight = FontWeight.Bold
                            )
                            snap.usdtBalance?.let { usdt ->
                                Text(
                                    text = "ERC-20 Tether: $usdt",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = AquaCyan
                                )
                            }
                            Text(
                                text = snap.sourceInfo,
                                style = MaterialTheme.typography.labelSmall,
                                color = AquaMutedText
                            )
                            if (snap.recentTransactions.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "RECENT TRANSACTIONS",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AquaCyan
                                )
                                snap.recentTransactions.forEach { tx ->
                                    Surface(
                                        color = AquaDeepSurface,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text(
                                                text = tx.hash,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = AquaIceText,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${tx.status} • ${tx.summary} • ${tx.timestampLabel}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = AquaMutedText
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Receive QR Code Renderer (Real QR Matrix via QrCodeGenerator)
            if (publicAddress.isNotBlank()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, AquaBorderGlow, RoundedCornerShape(18.dp))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.QrCode2, contentDescription = null, tint = AquaCyan)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Receive Address QR", style = MaterialTheme.typography.titleMedium, color = AquaIceText)
                                }
                                OutlinedButton(
                                    onClick = { showQrCode = !showQrCode },
                                    modifier = Modifier.testTag("wallet_toggle_qr_button")
                                ) {
                                    Text(if (showQrCode) "Hide QR" else "Show QR", color = AquaCyan)
                                }
                            }

                            if (showQrCode) {
                                val matrix = remember(publicAddress) { QrCodeGenerator.encode(publicAddress.trim()) }
                                if (matrix != null) {
                                    Surface(
                                        color = Color.White,
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.padding(8.dp)
                                    ) {
                                        Canvas(
                                            modifier = Modifier
                                                .size(200.dp)
                                                .padding(12.dp)
                                        ) {
                                            val n = matrix.size
                                            val cell = size.minDimension / n
                                            for (r in 0 until n) {
                                                for (c in 0 until n) {
                                                    if (matrix[r][c]) {
                                                        drawRect(
                                                            color = Color.Black,
                                                            topLeft = Offset(c * cell, r * cell),
                                                            size = Size(cell + 0.5f, cell + 0.5f)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    Text(
                                        text = publicAddress.trim(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AquaMutedText
                                    )
                                } else {
                                    Text(
                                        text = "Address length exceeds QR Version 6 capacity for local rendering.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = AquaAmber
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Live Crypto Market Spot Price (CoinGecko Public API)
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AquaBorderGlow, RoundedCornerShape(18.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.TrendingUp, contentDescription = null, tint = AquaTeal)
                            Spacer(Modifier.width(8.dp))
                            Text("Live Market Price Lookup", style = MaterialTheme.typography.titleMedium, color = AquaIceText)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = priceQuery,
                                onValueChange = { priceQuery = it },
                                label = { Text("Asset (e.g. btc, eth, usdt, bnb)") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AquaCyan,
                                    unfocusedBorderColor = AquaBorderGlow,
                                    focusedTextColor = AquaIceText,
                                    unfocusedTextColor = AquaIceText
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    scope.launch {
                                        priceBusy = true
                                        viewModel.walletConnector.fetchMarketPriceUsd(priceQuery)
                                            .onSuccess { (id, usd) -> priceResult = "$id: $${String.format("%.4f", usd)} USD" }
                                            .onFailure { priceResult = "Error: ${it.localizedMessage}" }
                                        priceBusy = false
                                    }
                                },
                                enabled = !priceBusy && priceQuery.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = AquaAzure, contentColor = Color.White),
                                modifier = Modifier.testTag("wallet_price_lookup_button")
                            ) {
                                Text(if (priceBusy) "..." else "Fetch")
                            }
                        }
                        priceResult?.let { res ->
                            Text(text = res, style = MaterialTheme.typography.titleMedium, color = AquaCyan)
                        }
                    }
                }
            }

            // Transfer Preparation & External Signing Handoff
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AquaAmber.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "2. Prepare Transfer (Requires Explicit Authorization)",
                            style = MaterialTheme.typography.titleMedium,
                            color = AquaIceText
                        )
                        WalletTextField(
                            value = transferDest,
                            onValueChange = { transferDest = it },
                            label = "Destination Address",
                            placeholder = "0x... or bc1..."
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WalletTextField(
                                value = transferAsset,
                                onValueChange = { transferAsset = it },
                                label = "Asset",
                                placeholder = "ETH",
                                modifier = Modifier.weight(1f)
                            )
                            WalletTextField(
                                value = transferAmount,
                                onValueChange = { transferAmount = it },
                                label = "Amount",
                                placeholder = "0.01",
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Button(
                            onClick = {
                                viewModel.prepareWalletTransfer(
                                    config = buildCurrentConfig(),
                                    destination = transferDest,
                                    asset = transferAsset,
                                    amount = transferAmount
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AquaAmber, contentColor = AquaAbyss),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("wallet_prepare_tx_button")
                        ) {
                            Text("Prepare Signing Request", fontWeight = FontWeight.Bold)
                        }

                        preparedTransfer?.let { prep ->
                            Surface(
                                color = AquaDeepSurface,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, AquaAmber, RoundedCornerShape(14.dp))
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text("REVIEW BEFORE SIGNING", style = MaterialTheme.typography.labelMedium, color = AquaAmber)
                                    Text("To: ${prep.destinationAddress}", style = MaterialTheme.typography.bodyMedium, color = AquaIceText)
                                    Text("Amount: ${prep.amount} ${prep.assetSymbol} on ${prep.networkName}", style = MaterialTheme.typography.bodyMedium, color = AquaIceText)
                                    Text("URI: ${prep.eip681Uri}", style = MaterialTheme.typography.labelSmall, color = AquaMutedText)
                                    Text(
                                        text = if (prep.canLaunchWalletApp) "External signing handler detected on device."
                                        else "No matching wallet handler detected for ${prep.eip681Uri}.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (prep.canLaunchWalletApp) AquaMintSuccess else AquaAmber
                                    )
                                    Button(
                                        onClick = { viewModel.authorizeAndLaunchPreparedTransfer(buildCurrentConfig()) },
                                        colors = ButtonDefaults.buttonColors(containerColor = AquaCyan, contentColor = AquaAbyss),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("wallet_authorize_sign_button")
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Authorize & Open External Wallet", fontWeight = FontWeight.Bold)
                                    }
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
private fun WalletTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AquaCyan,
            unfocusedBorderColor = AquaBorderGlow,
            focusedTextColor = AquaIceText,
            unfocusedTextColor = AquaIceText
        ),
        modifier = modifier.fillMaxWidth()
    )
}
