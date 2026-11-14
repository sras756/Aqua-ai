package com.example.ui.screens.wallet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AquaAbyss
import com.example.ui.theme.AquaBorderGlow
import com.example.ui.theme.AquaCoralError
import com.example.ui.theme.AquaCyan
import com.example.ui.theme.AquaDeepSurface
import com.example.ui.theme.AquaIceText
import com.example.ui.theme.AquaMintSuccess
import com.example.ui.theme.AquaMutedText
import com.example.ui.theme.AquaTeal
import com.example.wallet.core.DisplayFiatCurrency
import com.example.wallet.core.MarketSparklineData
import java.util.Locale

private val WATCHLIST_COINS = listOf(
    "bitcoin" to "BTC",
    "ethereum" to "ETH",
    "solana" to "SOL",
    "binancecoin" to "BNB",
    "polygon-ecosystem-token" to "POL"
)

private val TIMEFRAMES = listOf(
    1 to "24H",
    7 to "7D",
    30 to "30D"
)

@Composable
fun WalletMarketSparklineCard(
    chartData: MarketSparklineData?,
    busy: Boolean,
    errorText: String?,
    displayCurrency: DisplayFiatCurrency,
    fiatRateFromUsd: Double,
    onLoadChart: (coingeckoId: String, symbol: String, days: Int) -> Unit
) {
    var selectedCoinIdx by remember { mutableIntStateOf(1) } // Default ETH
    var selectedDays by remember { mutableIntStateOf(7) }

    val (coinId, coinSymbol) = WATCHLIST_COINS[selectedCoinIdx]

    LaunchedEffect(coinId, selectedDays) {
        onLoadChart(coinId, coinSymbol, selectedDays)
    }

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.ShowChart, contentDescription = null, tint = AquaCyan)
                    Spacer(Modifier.width(8.dp))
                    Text("Live Market & Sparklines", style = MaterialTheme.typography.titleMedium, color = AquaIceText)
                }
                IconButton(
                    onClick = { onLoadChart(coinId, coinSymbol, selectedDays) },
                    enabled = !busy,
                    modifier = Modifier.size(48.dp).testTag("market_chart_refresh_button")
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AquaCyan)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh market chart", tint = AquaCyan)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                WATCHLIST_COINS.forEachIndexed { idx, (_, sym) ->
                    FilterChip(
                        selected = selectedCoinIdx == idx,
                        onClick = { selectedCoinIdx = idx },
                        label = { Text(sym) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AquaCyan.copy(alpha = 0.22f),
                            selectedLabelColor = AquaCyan,
                            labelColor = AquaMutedText
                        ),
                        modifier = Modifier.testTag("market_coin_chip_$sym")
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TIMEFRAMES.forEach { (days, label) ->
                    FilterChip(
                        selected = selectedDays == days,
                        onClick = { selectedDays = days },
                        label = { Text(label) },
                        modifier = Modifier.testTag("market_days_chip_$label")
                    )
                }
            }

            when {
                chartData != null && chartData.coingeckoId == coinId && chartData.points.size >= 2 -> {
                    val positive = chartData.periodChangePct >= 0.0
                    val lineColor = if (positive) AquaMintSuccess else AquaCoralError
                    val priceConv = chartData.currentPriceUsd * fiatRateFromUsd
                    val highConv = chartData.highUsd * fiatRateFromUsd
                    val lowConv = chartData.lowUsd * fiatRateFromUsd

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "${chartData.symbol} (${displayCurrency.code})",
                                style = MaterialTheme.typography.labelMedium,
                                color = AquaMutedText
                            )
                            Text(
                                text = "${displayCurrency.symbol}${"%,.2f".format(Locale.US, priceConv)}",
                                style = MaterialTheme.typography.titleLarge,
                                color = AquaIceText,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Surface(
                            color = lineColor.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "${if (positive) "+" else ""}${"%.2f".format(Locale.US, chartData.periodChangePct)}% (${selectedDays}d)",
                                style = MaterialTheme.typography.labelMedium,
                                color = lineColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                            .background(AquaAbyss.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val pts = chartData.points
                            val minP = pts.minOf { it.priceUsd }
                            val maxP = pts.maxOf { it.priceUsd }
                            val span = (maxP - minP).takeIf { it > 1e-12 } ?: 1.0
                            val w = size.width
                            val h = size.height

                            val linePath = Path()
                            val fillPath = Path()
                            pts.forEachIndexed { i, pt ->
                                val x = (i.toFloat() / (pts.size - 1).coerceAtLeast(1)) * w
                                val normY = ((pt.priceUsd - minP) / span).toFloat()
                                val y = h - (normY * (h * 0.88f) + h * 0.06f)
                                if (i == 0) {
                                    linePath.moveTo(x, y)
                                    fillPath.moveTo(x, h)
                                    fillPath.lineTo(x, y)
                                } else {
                                    linePath.lineTo(x, y)
                                    fillPath.lineTo(x, y)
                                }
                            }
                            fillPath.lineTo(w, h)
                            fillPath.close()

                            drawPath(
                                path = fillPath,
                                brush = Brush.verticalGradient(
                                    colors = listOf(lineColor.copy(alpha = 0.32f), lineColor.copy(alpha = 0.01f))
                                )
                            )
                            drawPath(
                                path = linePath,
                                color = lineColor,
                                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                            val lastY = h - ((((pts.last().priceUsd - minP) / span).toFloat()) * (h * 0.88f) + h * 0.06f)
                            drawCircle(color = lineColor, radius = 4.dp.toPx(), center = Offset(w, lastY))
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Low: ${displayCurrency.symbol}${"%,.2f".format(Locale.US, lowConv)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AquaMutedText
                        )
                        Text(
                            text = "High: ${displayCurrency.symbol}${"%,.2f".format(Locale.US, highConv)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AquaTeal
                        )
                    }
                }

                !errorText.isNullOrBlank() -> {
                    Text(
                        text = "Live market series unavailable: $errorText",
                        style = MaterialTheme.typography.bodySmall,
                        color = AquaMutedText
                    )
                }

                else -> {
                    Text(
                        text = if (busy) "Loading live $coinSymbol market chart from CoinGecko..." else "Tap refresh to load live CoinGecko price series.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AquaMutedText
                    )
                }
            }
        }
    }
}
