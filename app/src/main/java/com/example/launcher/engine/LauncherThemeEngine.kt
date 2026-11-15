package com.example.launcher.engine

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.launcher.model.LauncherFontOption
import com.example.launcher.model.LauncherIconShape
import com.example.launcher.model.LauncherThemeConfig
import com.example.launcher.model.LauncherThemePreset
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.SpaceGroteskFontFamily

data class ResolvedLauncherTheme(
    val primaryColor: Color,
    val secondaryColor: Color,
    val surfaceColor: Color,
    val glassCardColor: Color,
    val dockColor: Color,
    val borderColor: Color,
    val textPrimary: Color,
    val textMuted: Color,
    val iconShape: Shape,
    val cardShape: Shape,
    val fontFamily: FontFamily,
    val animationDurationMs: Int
)

object LauncherThemeEngine {

    fun parseColorOrDefault(hex: String, fallback: Color): Color {
        return runCatching {
            val cleaned = hex.trim().removePrefix("#")
            val parsed = when (cleaned.length) {
                6 -> ("FF$cleaned").toLong(16)
                8 -> cleaned.toLong(16)
                else -> return fallback
            }
            Color(parsed.toInt())
        }.getOrDefault(fallback)
    }

    fun applyPreset(current: LauncherThemeConfig, preset: LauncherThemePreset): LauncherThemeConfig {
        return current.copy(
            presetId = preset.id,
            primaryColorHex = preset.primaryHex,
            secondaryColorHex = preset.secondaryHex,
            backgroundAlpha = preset.bgAlpha,
            widgetAlpha = preset.widgetAlpha,
            cornerRadiusDp = preset.cornerRadiusDp
        )
    }

    fun resolve(config: LauncherThemeConfig): ResolvedLauncherTheme {
        val preset = LauncherThemePreset.fromId(config.presetId)
        val primary = parseColorOrDefault(config.primaryColorHex, Color(0xFF00E5FF))
        val secondary = parseColorOrDefault(config.secondaryColorHex, Color(0xFF2979FF))
        val baseSurface = parseColorOrDefault(preset.surfaceHex, Color(0xFF082036))

        val iconShape: Shape = when (LauncherIconShape.fromId(config.iconShape)) {
            LauncherIconShape.CIRCLE -> CircleShape
            LauncherIconShape.ROUNDED_SQUARE -> RoundedCornerShape(12.dp)
            LauncherIconShape.TEARDROP -> RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = 20.dp,
                bottomEnd = 6.dp
            )
            LauncherIconShape.SQUIRCLE -> RoundedCornerShape(16.dp)
        }

        val fontFamily: FontFamily = when (LauncherFontOption.fromId(config.fontOption)) {
            LauncherFontOption.SPACE_GROTESK -> SpaceGroteskFontFamily
            LauncherFontOption.INTER -> InterFontFamily
            LauncherFontOption.JETBRAINS_MONO -> JetBrainsMonoFontFamily
            LauncherFontOption.SYSTEM_DEFAULT -> FontFamily.SansSerif
        }

        val durationMs = (260f / config.animationSpeed.coerceIn(0.4f, 2.0f)).toInt().coerceIn(100, 650)

        return ResolvedLauncherTheme(
            primaryColor = primary,
            secondaryColor = secondary,
            surfaceColor = baseSurface.copy(alpha = config.backgroundAlpha.coerceIn(0.2f, 1.0f)),
            glassCardColor = baseSurface.copy(alpha = config.widgetAlpha.coerceIn(0.18f, 0.96f)),
            dockColor = baseSurface.copy(alpha = config.dockAlpha.coerceIn(0.2f, 0.98f)),
            borderColor = primary.copy(alpha = if (preset == LauncherThemePreset.MINIMAL) 0.22f else 0.38f),
            textPrimary = Color(0xFFEAF7FF),
            textMuted = Color(0xFF9AC0DC),
            iconShape = iconShape,
            cardShape = RoundedCornerShape(config.cornerRadiusDp.coerceIn(6, 32).dp),
            fontFamily = fontFamily,
            animationDurationMs = durationMs
        )
    }
}
