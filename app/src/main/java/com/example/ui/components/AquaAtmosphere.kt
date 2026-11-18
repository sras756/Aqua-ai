package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AquaTheme

/**
 * Animated ambient bioluminescent canvas atmosphere that drifts subtly across
 * Aqua Abyss, Midnight OLED, and Crisp Oceanic Light themes.
 */
@Composable
fun AquaBackdropBox(
    modifier: Modifier = Modifier,
    accentColor: Color = AquaTheme.colors.accentCyan,
    content: @Composable BoxScope.() -> Unit
) {
    val c = AquaTheme.colors
    val ambientTransition = rememberInfiniteTransition(label = "aqua_backdrop_drift")
    val topDrift by ambientTransition.animateFloat(
        initialValue = -0.06f,
        targetValue = 0.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "top_drift"
    )
    val bottomDrift by ambientTransition.animateFloat(
        initialValue = 0.05f,
        targetValue = -0.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bottom_drift"
    )

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(c.canvas, c.canvasMid, c.canvas)
                )
            )
            val topCenter = Offset(
                x = size.width * (0.50f + topDrift),
                y = size.height * (0.14f + topDrift * 0.35f)
            )
            val topRadius = size.minDimension * 0.68f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accentColor.copy(alpha = if (c.isDark) 0.16f else 0.11f),
                        c.accentAzure.copy(alpha = if (c.isDark) 0.07f else 0.05f),
                        Color.Transparent
                    ),
                    center = topCenter,
                    radius = topRadius
                ),
                center = topCenter,
                radius = topRadius
            )

            val bottomCenter = Offset(
                x = size.width * (0.82f + bottomDrift),
                y = size.height * (0.78f - bottomDrift * 0.4f)
            )
            val bottomRadius = size.minDimension * 0.56f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        c.accentTeal.copy(alpha = if (c.isDark) 0.09f else 0.07f),
                        Color.Transparent
                    ),
                    center = bottomCenter,
                    radius = bottomRadius
                ),
                center = bottomCenter,
                radius = bottomRadius
            )

            // Subtle precision matrix dots
            val step = 34.dp.toPx()
            val dotAlpha = if (c.isDark) 0.065f else 0.085f
            var y = step
            while (y < size.height * 0.46f) {
                var x = step * 0.7f
                while (x < size.width) {
                    drawCircle(
                        color = c.accentCyan.copy(alpha = dotAlpha),
                        radius = 1.2f,
                        center = Offset(x, y)
                    )
                    x += step
                }
                y += step
            }
        }
        content()
    }
}

@Composable
fun AquaGlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    containerColor: Color = AquaTheme.colors.cardSurface.copy(alpha = if (AquaTheme.colors.isDark) 0.92f else 0.97f),
    borderColor: Color = AquaTheme.colors.borderGlow.copy(alpha = if (AquaTheme.colors.isDark) 0.88f else 0.92f),
    elevation: Dp = if (AquaTheme.colors.isDark) 3.dp else 4.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        modifier = modifier,
        content = content
    )
}

@Composable
fun AquaSectionHeader(
    eyebrow: String,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    val c = AquaTheme.colors
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = eyebrow.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = c.accentCyan
        )
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = c.textPrimary
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = c.textMuted
            )
        }
    }
}

@Composable
fun AquaStatusPill(
    label: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = accent.copy(alpha = 0.14f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
