package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.TelemetrySample
import com.example.device.DeviceTelemetry
import com.example.device.StorageAndMemoryStats
import com.example.ui.theme.AquaTheme

/**
 * Displays live Android telemetry sampled from BatteryManager, ActivityManager, StatFs,
 * and ConnectivityManager over the active session (strictly real OS data, never fabricated).
 */
@Composable
fun TelemetrySparklineCard(
    telemetry: DeviceTelemetry,
    stats: StorageAndMemoryStats,
    samples: List<TelemetrySample>,
    onOpenSettingsSection: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = AquaTheme.colors
    val ramUsedPct = if (stats.ramTotalMb > 0L) {
        (((stats.ramTotalMb - stats.ramAvailableMb).coerceAtLeast(0L) * 100L) / stats.ramTotalMb)
            .toInt().coerceIn(0, 100)
    } else 0
    val storageUsedPct = if (stats.storageTotalMb > 0L) {
        (((stats.storageTotalMb - stats.storageFreeMb).coerceAtLeast(0L) * 100L) / stats.storageTotalMb)
            .toInt().coerceIn(0, 100)
    } else 0

    AquaGlassCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("telemetry_sparkline_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.SettingsInputComponent,
                        contentDescription = null,
                        tint = c.accentCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "LIVE DEVICE TELEMETRY",
                        style = MaterialTheme.typography.labelMedium,
                        color = c.accentCyan
                    )
                }
                Surface(
                    color = c.elevatedSurface,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "${samples.size} real sample${if (samples.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.textMuted,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            // Metrics summary row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricTile(
                    icon = if (telemetry.isCharging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryStd,
                    label = "Battery",
                    value = if (telemetry.batteryPercent >= 0) "${telemetry.batteryPercent}%" else "--",
                    tint = if (telemetry.batteryPercent in 0..20) c.amber else c.success,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    icon = Icons.Filled.Memory,
                    label = "RAM Used",
                    value = "$ramUsedPct%",
                    tint = if (stats.lowMemory) c.error else c.accentTeal,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    icon = Icons.Filled.Storage,
                    label = "Disk Free",
                    value = if (stats.storageFreeMb >= 1024) "${stats.storageFreeMb / 1024}G" else "${stats.storageFreeMb}M",
                    tint = c.accentCyan,
                    modifier = Modifier.weight(1f)
                )
            }

            // Real session sparkline canvas for Battery (emerald/cyan) & RAM (azure)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                val h = size.height
                val w = size.width
                // Horizontal 50% baseline guide
                drawLine(
                    color = c.borderGlow.copy(alpha = 0.4f),
                    start = Offset(0f, h * 0.5f),
                    end = Offset(w, h * 0.5f),
                    strokeWidth = 1.5f
                )
                if (samples.isNotEmpty()) {
                    val effective = if (samples.size == 1) listOf(samples.first(), samples.first()) else samples
                    val count = effective.size
                    val stepX = w / (count - 1).coerceAtLeast(1)

                    fun drawMetricLine(
                        valueSelector: (TelemetrySample) -> Int,
                        lineColor: androidx.compose.ui.graphics.Color,
                        fillGradient: Boolean
                    ) {
                        val path = Path()
                        val fillPath = Path()
                        effective.forEachIndexed { idx, s ->
                            val x = idx * stepX
                            val norm = (valueSelector(s).coerceIn(0, 100) / 100f)
                            val y = h - (norm * (h - 10f)) - 5f
                            if (idx == 0) {
                                path.moveTo(x, y)
                                fillPath.moveTo(x, h)
                                fillPath.lineTo(x, y)
                            } else {
                                path.lineTo(x, y)
                                fillPath.lineTo(x, y)
                            }
                            if (idx == effective.lastIndex) {
                                fillPath.lineTo(x, h)
                                fillPath.close()
                                drawCircle(color = lineColor, radius = 4.5f, center = Offset(x, y))
                            }
                        }
                        if (fillGradient) {
                            drawPath(
                                path = fillPath,
                                brush = Brush.verticalGradient(
                                    colors = listOf(lineColor.copy(alpha = 0.22f), androidx.compose.ui.graphics.Color.Transparent)
                                )
                            )
                        }
                        drawPath(
                            path = path,
                            color = lineColor,
                            style = Stroke(width = 3.2f, cap = StrokeCap.Round)
                        )
                    }

                    drawMetricLine({ it.ramUsedPercent }, c.accentAzure, fillGradient = true)
                    drawMetricLine({ it.batteryPercent }, c.accentTeal, fillGradient = false)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Teal: Battery % • Blue: RAM Used % ($storageUsedPct% disk)",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textMuted
                )
            }

            // Quick OS Deep Links
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = { onOpenSettingsSection("wifi") },
                    label = { Text(telemetry.connectionType, maxLines = 1) },
                    leadingIcon = { Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = c.deepSurface,
                        labelColor = c.textPrimary,
                        leadingIconContentColor = c.accentCyan
                    ),
                    modifier = Modifier.weight(1f)
                )
                AssistChip(
                    onClick = { onOpenSettingsSection("bluetooth") },
                    label = { Text("Bluetooth", maxLines = 1) },
                    leadingIcon = { Icon(Icons.Filled.Bluetooth, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = c.deepSurface,
                        labelColor = c.textPrimary,
                        leadingIconContentColor = c.accentTeal
                    ),
                    modifier = Modifier.weight(1f)
                )
                AssistChip(
                    onClick = { onOpenSettingsSection("battery") },
                    label = { Text("Battery", maxLines = 1) },
                    leadingIcon = { Icon(Icons.Filled.BatteryStd, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = c.deepSurface,
                        labelColor = c.textPrimary,
                        leadingIconContentColor = c.amber
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MetricTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    val c = AquaTheme.colors
    Surface(
        color = c.deepSurface,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(18.dp))
            Column {
                Text(value, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, fontWeight = FontWeight.Bold)
                Text(label, style = MaterialTheme.typography.labelSmall, color = c.textMuted)
            }
        }
    }
}
