package com.example.ui.screens.wallet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import com.example.wallet.core.AssetBalance
import com.example.wallet.core.PortfolioSummary
import com.example.wallet.core.WalletAccountInfo
import com.example.wallet.core.WalletMetadata
import java.util.Locale

private val CHART_PALETTE = listOf(
    AquaCyan,
    AquaTeal,
    AquaAzure,
    AquaAmber,
    AquaMintSuccess,
    Color(0xFF9C7AFF),
    Color(0xFFFF7EB6)
)

@Composable
fun WalletDashboardAndAnalyticsSection(
    walletMeta: WalletMetadata,
    account: WalletAccountInfo,
    portfolio: PortfolioSummary?,
    busy: Boolean,
    onRefresh: () -> Unit,
    onNavigateToSend: (networkId: String?, tokenId: String?) -> Unit,
    onNavigateToReceive: () -> Unit,
    onOpenScanner: () -> Unit,
    onOpenBackupSecurity: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AquaAbyss),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (!walletMeta.backupVerified && !walletMeta.isWatchOnly) {
            item {
                Surface(
                    color = AquaAmber.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AquaAmber, RoundedCornerShape(14.dp))
                        .clickable { onOpenBackupSecurity() }
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = AquaAmber)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Backup Verification Pending",
                                style = MaterialTheme.typography.labelLarge,
                                color = AquaAmber
                            )
                            Text(
                                "Tap to verify your ${walletMeta.wordCount}-word recovery phrase in Security Center.",
                                style = MaterialTheme.typography.bodySmall,
                                color = AquaIceText
                            )
                        }
                    }
                }
            }
        }

        // Hero Portfolio Valuation Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, AquaCyan.copy(alpha = 0.45f), RoundedCornerShape(22.dp))
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "TOTAL PORTFOLIO",
                                style = MaterialTheme.typography.labelMedium,
                                color = AquaCyan
                            )
                            Text(
                                text = "${walletMeta.label} • ${account.label}",
                                style = MaterialTheme.typography.bodySmall,
                                color = AquaMutedText
                            )
                        }
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = AquaCyan)
                        }
                    }

                    val totalUsdText = when {
                        portfolio == null -> "Tap Refresh to query live RPCs"
                        portfolio.totalValueUsd != null -> "$${"%,.2f".format(Locale.US, portfolio.totalValueUsd)} USD"
                        else -> "Live balances loaded (USD quotes offline/testnet)"
                    }
                    Text(
                        text = totalUsdText,
                        style = MaterialTheme.typography.headlineMedium,
                        color = AquaIceText,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("wallet_total_portfolio_value")
                    )

                    portfolio?.weighted24hChangePct?.let { delta ->
                        val positive = delta >= 0.0
                        Surface(
                            color = (if (positive) AquaMintSuccess else AquaCoralError).copy(alpha = 0.16f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "24h Portfolio Delta: ${if (positive) "+" else ""}${"%.2f".format(Locale.US, delta)}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (positive) AquaMintSuccess else AquaCoralError,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onNavigateToSend(null, null) },
                            colors = ButtonDefaults.buttonColors(containerColor = AquaCyan, contentColor = AquaAbyss),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("quick_action_send")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Send", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = onNavigateToReceive,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("quick_action_receive")
                        ) {
                            Icon(Icons.Filled.QrCode2, contentDescription = null, tint = AquaCyan, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Receive", color = AquaCyan)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onOpenScanner,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("quick_action_scan_qr")
                        ) {
                            Icon(Icons.Filled.QrCodeScanner, contentDescription = null, tint = AquaTeal, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Scan QR", color = AquaTeal)
                        }
                        OutlinedButton(
                            onClick = onRefresh,
                            enabled = !busy,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("quick_action_refresh")
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, tint = AquaIceText, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Refresh", color = AquaIceText)
                        }
                    }
                }
            }
        }

        // Portfolio Analytics Card (Chain Breakdown Canvas Chart)
        val chainTotals = portfolio?.chainTotalsUsd?.entries?.filter { it.value > 0.0 }?.sortedByDescending { it.value }.orEmpty()
        if (chainTotals.isNotEmpty()) {
            item {
                val grandTotal = chainTotals.sumOf { it.value }.coerceAtLeast(1e-9)
                Card(
                    colors = CardDefaults.cardColors(containerColor = AquaDeepSurface),
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
                            Icon(Icons.Filled.PieChart, contentDescription = null, tint = AquaTeal)
                            Spacer(Modifier.width(8.dp))
                            Text("Chain Allocation Analytics", style = MaterialTheme.typography.titleMedium, color = AquaIceText)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Canvas(modifier = Modifier.size(110.dp)) {
                                val strokeWidth = 22.dp.toPx()
                                var startAngle = -90f
                                chainTotals.forEachIndexed { idx, entry ->
                                    val sweep = ((entry.value / grandTotal) * 360.0).toFloat()
                                    drawArc(
                                        color = CHART_PALETTE[idx % CHART_PALETTE.size],
                                        startAngle = startAngle,
                                        sweepAngle = (sweep - 2f).coerceAtLeast(1f),
                                        useCenter = false,
                                        style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                                    )
                                    startAngle += sweep
                                }
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                chainTotals.forEachIndexed { idx, (chain, usd) ->
                                    val pct = (usd / grandTotal) * 100.0
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .background(CHART_PALETTE[idx % CHART_PALETTE.size], CircleShape)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "$chain: ${"%.1f".format(Locale.US, pct)}% ($${"%,.2f".format(Locale.US, usd)})",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = AquaIceText
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Warnings for unreachable RPC nodes
        if (!portfolio?.warnings.isNullOrEmpty()) {
            item {
                Surface(
                    color = AquaCardSurface,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AquaAmber.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Live RPC Warnings", style = MaterialTheme.typography.labelLarge, color = AquaAmber)
                        portfolio!!.warnings.forEach { w ->
                            Text("• $w", style = MaterialTheme.typography.bodySmall, color = AquaMutedText)
                        }
                    }
                }
            }
        }

        // My Assets Section
        item {
            Text(
                text = "MY MULTI-CHAIN ASSETS",
                style = MaterialTheme.typography.labelMedium,
                color = AquaCyan
            )
        }

        val assets = portfolio?.assets.orEmpty()
        if (assets.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = if (busy) "Querying live blockchain RPC endpoints..." else "No balance snapshot fetched yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AquaIceText
                        )
                        Text(
                            text = "Tap Refresh above to fetch real-time balances across Ethereum, BNB Smart Chain, Polygon, Base, Arbitrum, Solana, and Bitcoin.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AquaMutedText
                        )
                    }
                }
            }
        } else {
            items(assets, key = { it.token.id }) { asset ->
                AssetRowCard(
                    asset = asset,
                    onClick = { onNavigateToSend(asset.token.networkId, asset.token.id) }
                )
            }
        }
    }
}

@Composable
private fun AssetRowCard(
    asset: AssetBalance,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, AquaBorderGlow, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .testTag("asset_row_${asset.token.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = asset.token.symbol,
                        style = MaterialTheme.typography.titleMedium,
                        color = AquaIceText,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = AquaDeepSurface,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = asset.token.networkId.replace("_", " ").uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = AquaTeal,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = asset.token.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = AquaMutedText
                )
                asset.priceUsd?.let { p ->
                    val chg = asset.change24hPct?.let {
                        val sign = if (it >= 0) "+" else ""
                        " ($sign${"%.2f".format(Locale.US, it)}%)"
                    } ?: ""
                    Text(
                        text = "$${"%,.4f".format(Locale.US, p)}$chg",
                        style = MaterialTheme.typography.labelSmall,
                        color = if ((asset.change24hPct ?: 0.0) >= 0.0) AquaMintSuccess else AquaCoralError
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = asset.displayBalance,
                    style = MaterialTheme.typography.titleSmall,
                    color = AquaIceText,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = asset.valueUsd?.let { "$${"%,.2f".format(Locale.US, it)}" } ?: "Tap to Send",
                    style = MaterialTheme.typography.labelMedium,
                    color = AquaCyan
                )
            }
        }
    }
}
