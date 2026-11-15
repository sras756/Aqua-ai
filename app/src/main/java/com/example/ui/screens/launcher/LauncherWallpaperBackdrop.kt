package com.example.ui.screens.launcher

import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.launcher.engine.ResolvedLauncherTheme
import com.example.launcher.model.DiscoveredApp
import com.example.launcher.model.LauncherIconPackStyle
import com.example.launcher.model.LauncherThemeConfig
import com.example.launcher.model.MapWallpaperStyle
import com.example.launcher.model.WallpaperConfig
import com.example.launcher.model.WallpaperType
import com.example.launcher.wallpaper.AquaWallpaperEngine
import com.example.launcher.wallpaper.MapTileSample

@Composable
fun LauncherWallpaperBackdrop(
    config: WallpaperConfig,
    wallpaperEngine: AquaWallpaperEngine,
    theme: ResolvedLauncherTheme,
    animationSpeed: Float = 1.0f,
    modifier: Modifier = Modifier
) {
    val type = WallpaperType.fromId(config.typeId)
    var mapTiles by remember { mutableStateOf<List<MapTileSample>>(emptyList()) }

    LaunchedEffect(
        config.typeId,
        config.mapLatitude,
        config.mapLongitude,
        config.mapZoom,
        config.mapStyleId
    ) {
        if (type == WallpaperType.LIVE_MAP) {
            mapTiles = wallpaperEngine.fetchMapTileGrid(
                latitude = config.mapLatitude,
                longitude = config.mapLongitude,
                zoom = config.mapZoom,
                style = MapWallpaperStyle.fromId(config.mapStyleId)
            )
        } else {
            mapTiles = emptyList()
        }
    }

    val shouldAnimate = when (type) {
        WallpaperType.ANIMATED_AURORA,
        WallpaperType.GAMING_CYBER,
        WallpaperType.AI_GENERATIVE -> true
        WallpaperType.LIVE_MAP -> config.mapAnimatePan
        else -> false
    }

    val durationMs = ((if (type == WallpaperType.LIVE_MAP) 14000f else 8500f) / animationSpeed.coerceIn(0.4f, 2.0f)).toInt()
    val infiniteTransition = rememberInfiniteTransition(label = "aqua_wallpaper_anim")
    val phase by if (shouldAnimate) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = durationMs, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "wallpaper_phase"
        )
    } else {
        remember { mutableStateOf(0.25f) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if ((type == WallpaperType.CUSTOM_PHOTO || type == WallpaperType.CUSTOM_VIDEO) &&
            config.customMediaUri.isNotBlank()
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(Uri.parse(config.customMediaUri))
                    .crossfade(true)
                    .build(),
                contentDescription = config.label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0x5504101E),
                                theme.surfaceColor.copy(alpha = 0.28f),
                                Color(0x88030D19)
                            )
                        )
                    )
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width.toInt()
                val h = size.height.toInt()
                if (w > 1 && h > 1) {
                    AquaWallpaperEngine.drawWallpaperCanvas(
                        canvas = drawContext.canvas.nativeCanvas,
                        w = w,
                        h = h,
                        config = config,
                        phase = phase,
                        mapTiles = mapTiles
                    )
                }
            }
        }
    }
}

@Composable
fun LauncherAppIconBadge(
    app: DiscoveredApp?,
    fallbackLabel: String,
    themeConfig: LauncherThemeConfig,
    resolvedTheme: ResolvedLauncherTheme,
    sizeDp: Int = themeConfig.iconSizeDp,
    modifier: Modifier = Modifier
) {
    val packStyle = LauncherIconPackStyle.fromId(themeConfig.iconPackStyle)
    val clampedSize = sizeDp.coerceIn(36, 76).dp

    val borderColor = when (packStyle) {
        LauncherIconPackStyle.AQUA_GLOW -> resolvedTheme.primaryColor.copy(alpha = 0.72f)
        LauncherIconPackStyle.CYBER_FRAME -> resolvedTheme.secondaryColor.copy(alpha = 0.85f)
        LauncherIconPackStyle.MONOCHROME_ICE -> resolvedTheme.primaryColor.copy(alpha = 0.48f)
        LauncherIconPackStyle.SYSTEM_NATIVE -> resolvedTheme.borderColor.copy(alpha = 0.35f)
    }

    val colorFilter = remember(packStyle) {
        when (packStyle) {
            LauncherIconPackStyle.MONOCHROME_ICE -> {
                val matrix = ColorMatrix().apply { setToSaturation(0.18f) }
                ColorFilter.colorMatrix(matrix)
            }
            else -> null
        }
    }

    val bgBrush = when (packStyle) {
        LauncherIconPackStyle.CYBER_FRAME -> Brush.linearGradient(
            colors = listOf(
                resolvedTheme.surfaceColor.copy(alpha = 0.92f),
                resolvedTheme.secondaryColor.copy(alpha = 0.35f)
            )
        )
        LauncherIconPackStyle.AQUA_GLOW -> Brush.radialGradient(
            colors = listOf(
                resolvedTheme.primaryColor.copy(alpha = 0.22f),
                resolvedTheme.surfaceColor.copy(alpha = 0.85f)
            )
        )
        else -> Brush.linearGradient(
            colors = listOf(
                resolvedTheme.glassCardColor,
                resolvedTheme.surfaceColor
            )
        )
    }

    Box(
        modifier = modifier
            .size(clampedSize)
            .clip(resolvedTheme.iconShape)
            .background(bgBrush)
            .border(
                width = if (packStyle == LauncherIconPackStyle.SYSTEM_NATIVE) 1.dp else 1.5.dp,
                color = borderColor,
                shape = resolvedTheme.iconShape
            ),
        contentAlignment = Alignment.Center
    ) {
        val bmp = app?.iconBitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = app.label,
                contentScale = ContentScale.Crop,
                colorFilter = colorFilter,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(resolvedTheme.iconShape)
            )
        } else {
            val initial = (app?.label ?: fallbackLabel).trim().take(2).uppercase().ifBlank { "AQ" }
            Text(
                text = initial,
                color = resolvedTheme.primaryColor,
                fontFamily = resolvedTheme.fontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = (sizeDp * 0.34f).coerceIn(13f, 24f).sp
            )
        }
    }
}
