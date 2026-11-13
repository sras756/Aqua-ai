package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val AquaDarkColorScheme = darkColorScheme(
    primary = AquaCyan,
    onPrimary = AquaAbyss,
    primaryContainer = AquaElevatedSurface,
    onPrimaryContainer = AquaIceText,
    secondary = AquaAzure,
    onSecondary = Color.White,
    secondaryContainer = AquaCardSurface,
    onSecondaryContainer = AquaIceText,
    tertiary = AquaTeal,
    onTertiary = AquaAbyss,
    background = AquaAbyss,
    onBackground = AquaIceText,
    surface = AquaDeepSurface,
    onSurface = AquaIceText,
    surfaceVariant = AquaCardSurface,
    onSurfaceVariant = AquaMutedText,
    outline = AquaBorderGlow,
    error = AquaCoralError,
    onError = Color.White
)

private val AquaLightColorScheme = lightColorScheme(
    primary = AquaLightPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6F3FF),
    onPrimaryContainer = AquaLightText,
    secondary = AquaLightSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1EDFF),
    onSecondaryContainer = AquaLightText,
    tertiary = Color(0xFF00796B),
    onTertiary = Color.White,
    background = AquaLightBg,
    onBackground = AquaLightText,
    surface = AquaLightSurface,
    onSurface = AquaLightText,
    surfaceVariant = Color(0xFFE3F1FA),
    onSurfaceVariant = Color(0xFF3E5E7A),
    outline = Color(0xFF9AC2DC),
    error = Color(0xFFD32F2F),
    onError = Color.White
)

val AquaShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun AquaAiTheme(
    darkThemeMode: String = "aqua_abyss",
    content: @Composable () -> Unit
) {
    val useDark = when (darkThemeMode) {
        "aqua_light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true
    }
    val scheme = if (useDark) AquaDarkColorScheme else AquaLightColorScheme
    MaterialTheme(
        colorScheme = scheme,
        typography = AquaTypography,
        shapes = AquaShapes,
        content = content
    )
}
