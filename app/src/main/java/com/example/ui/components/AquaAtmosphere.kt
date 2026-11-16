package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AquaTheme

/**
 * Ambient bioluminescent canvas atmosphere that adapts automatically across
 * Aqua Abyss, Midnight OLED, and Crisp Oceanic Light themes.
 */
@Composable
fun AquaBackdropBox(
    modifier: Modifier = Modifier,
    accentColor: Color = AquaTheme.colors.accentCyan,
    content: @Composable BoxScope.() -> Unit
) {
    val c = AquaTheme.colors
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(c.canvas, c.canvasMid, c.canvas)
                )
            )
            // Top ambient luminescence orb
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accentColor.copy(alpha = if (c.isDark) 0.14f else 0.10f),
                        c.accentAzure.copy(alpha = if (c.isDark) 0.06f else 0.04f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.5f, size.height * 0.14f),
                    radius = size.minDimension * 0.65f
                ),
                center = Offset(size.width * 0.5f, size.height * 0.14f),
                radius = size.minDimension * 0.65f
            )
            // Lower seafoam caustic accent
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        c.accentTeal.copy(alpha = if (c.isDark) 0.08f else 0.06f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.85f, size.height * 0.78f),
                    radius = size.minDimension * 0.55f
                ),
                center = Offset(size.width * 0.85f, size.height * 0.78f),
                radius = size.minDimension * 0.55f
            )
            // Subtle precision matrix dots
            val step = 34.dp.toPx()
            val dotAlpha = if (c.isDark) 0.07f else 0.09f
            var y = step
            while (y < size.height * 0.45f) {
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
    containerColor: Color = AquaTheme.colors.cardSurface.copy(alpha = if (AquaTheme.colors.isDark) 0.88f else 0.96f),
    borderColor: Color = AquaTheme.colors.borderGlow.copy(alpha = if (AquaTheme.colors.isDark) 0.8f else 0.9f),
    elevation: Dp = if (AquaTheme.colors.isDark) 0.dp else 2.dp,
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
