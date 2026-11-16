package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class AquaExtendedColors(
    val isDark: Boolean,
    val canvas: Color,
    val canvasMid: Color,
    val deepSurface: Color,
    val cardSurface: Color,
    val elevatedSurface: Color,
    val accentCyan: Color,
    val accentTeal: Color,
    val accentAzure: Color,
    val textPrimary: Color,
    val textMuted: Color,
    val borderGlow: Color,
    val amber: Color,
    val success: Color,
    val error: Color,
    val onAccent: Color,
    val glassScrim: Color
)

private val AbyssExtendedColors = AquaExtendedColors(
    isDark = true,
    canvas = AquaAbyss,
    canvasMid = AquaAbyssGradientMid,
    deepSurface = AquaDeepSurface,
    cardSurface = AquaCardSurface,
    elevatedSurface = AquaElevatedSurface,
    accentCyan = AquaCyan,
    accentTeal = AquaTeal,
    accentAzure = AquaAzure,
    textPrimary = AquaIceText,
    textMuted = AquaMutedText,
    borderGlow = AquaBorderGlow,
    amber = AquaAmber,
    success = AquaMintSuccess,
    error = AquaCoralError,
    onAccent = AquaAbyss,
    glassScrim = AquaCardSurface.copy(alpha = 0.78f)
)

private val MidnightOledExtendedColors = AquaExtendedColors(
    isDark = true,
    canvas = AquaOledCanvas,
    canvasMid = AquaOledDeepSurface,
    deepSurface = AquaOledDeepSurface,
    cardSurface = AquaOledCardSurface,
    elevatedSurface = AquaOledElevatedSurface,
    accentCyan = Color(0xFF38BDF8),
    accentTeal = Color(0xFF2DD4BF),
    accentAzure = Color(0xFF60A5FA),
    textPrimary = Color(0xFFF1F7FD),
    textMuted = Color(0xFF8AA2BE),
    borderGlow = Color(0xFF1F324D),
    amber = AquaAmber,
    success = AquaMintSuccess,
    error = AquaCoralError,
    onAccent = Color.Black,
    glassScrim = AquaOledCardSurface.copy(alpha = 0.85f)
)

private val LightExtendedColors = AquaExtendedColors(
    isDark = false,
    canvas = AquaLightBg,
    canvasMid = AquaLightGradientMid,
    deepSurface = Color(0xFFEAF4FB),
    cardSurface = AquaLightCardSurface,
    elevatedSurface = AquaLightElevatedSurface,
    accentCyan = AquaLightPrimary,
    accentTeal = AquaLightTeal,
    accentAzure = AquaLightSecondary,
    textPrimary = AquaLightText,
    textMuted = AquaLightMutedText,
    borderGlow = AquaLightBorder,
    amber = AquaLightAmber,
    success = AquaLightSuccess,
    error = AquaLightError,
    onAccent = Color.White,
    glassScrim = Color.White.copy(alpha = 0.90f)
)

val LocalAquaColors = staticCompositionLocalOf { AbyssExtendedColors }

object AquaTheme {
    val colors: AquaExtendedColors
        @Composable get() = LocalAquaColors.current
}

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

private val AquaOledColorScheme = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color.Black,
    primaryContainer = AquaOledElevatedSurface,
    onPrimaryContainer = Color(0xFFF1F7FD),
    secondary = Color(0xFF60A5FA),
    onSecondary = Color.Black,
    secondaryContainer = AquaOledCardSurface,
    onSecondaryContainer = Color(0xFFF1F7FD),
    tertiary = Color(0xFF2DD4BF),
    onTertiary = Color.Black,
    background = AquaOledCanvas,
    onBackground = Color(0xFFF1F7FD),
    surface = AquaOledDeepSurface,
    onSurface = Color(0xFFF1F7FD),
    surfaceVariant = AquaOledCardSurface,
    onSurfaceVariant = Color(0xFF8AA2BE),
    outline = Color(0xFF1F324D),
    error = AquaCoralError,
    onError = Color.White
)

private val AquaLightColorScheme = lightColorScheme(
    primary = AquaLightPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5EFF9),
    onPrimaryContainer = AquaLightText,
    secondary = AquaLightSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1EDFF),
    onSecondaryContainer = AquaLightText,
    tertiary = AquaLightTeal,
    onTertiary = Color.White,
    background = AquaLightBg,
    onBackground = AquaLightText,
    surface = AquaLightSurface,
    onSurface = AquaLightText,
    surfaceVariant = AquaLightElevatedSurface,
    onSurfaceVariant = AquaLightMutedText,
    outline = AquaLightBorder,
    error = AquaLightError,
    onError = Color.White
)

val AquaShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

@Composable
fun AquaAiTheme(
    darkThemeMode: String = "aqua_abyss",
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val resolvedMode = when (darkThemeMode) {
        "aqua_light" -> "aqua_light"
        "aqua_midnight" -> "aqua_midnight"
        "system" -> if (systemDark) "aqua_abyss" else "aqua_light"
        else -> "aqua_abyss"
    }
    val (scheme, extended) = when (resolvedMode) {
        "aqua_light" -> AquaLightColorScheme to LightExtendedColors
        "aqua_midnight" -> AquaOledColorScheme to MidnightOledExtendedColors
        else -> AquaDarkColorScheme to AbyssExtendedColors
    }
    CompositionLocalProvider(LocalAquaColors provides extended) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AquaTypography,
            shapes = AquaShapes,
            content = content
        )
    }
}
