package com.example.launcher.wallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import com.example.launcher.model.MapWallpaperStyle
import com.example.launcher.model.WallpaperConfig
import com.example.launcher.model.WallpaperType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.tan

data class MapGeocodeResult(
    val displayName: String,
    val latitude: Double,
    val longitude: Double
)

data class MapTileSample(
    val tileX: Int,
    val tileY: Int,
    val zoom: Int,
    val bitmap: Bitmap
)

class AquaWallpaperEngine(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    private val appContext = context.applicationContext
    private val tileCacheDir: File by lazy {
        File(appContext.cacheDir, "aqua_map_tiles").apply { mkdirs() }
    }

    suspend fun searchLocation(query: String): Result<List<MapGeocodeResult>> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = query.trim()
            require(clean.isNotEmpty()) { "Location query cannot be empty." }
            val url = "https://nominatim.openstreetmap.org/search?q=${Uri.encode(clean)}&format=json&limit=5"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "AquaLauncherAndroid/1.0")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("Geocoding HTTP ${resp.code}")
                val arr = JSONArray(resp.body?.string().orEmpty().ifBlank { "[]" })
                val results = mutableListOf<MapGeocodeResult>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val name = obj.optString("display_name").ifBlank { clean }
                    val lat = obj.optString("lat").toDoubleOrNull() ?: continue
                    val lon = obj.optString("lon").toDoubleOrNull() ?: continue
                    results.add(MapGeocodeResult(name, lat, lon))
                }
                results
            }
        }
    }

    suspend fun fetchMapTileGrid(
        latitude: Double,
        longitude: Double,
        zoom: Int,
        style: MapWallpaperStyle
    ): List<MapTileSample> = withContext(Dispatchers.IO) {
        val template = style.tileSubdomainUrl ?: return@withContext emptyList()
        val z = zoom.coerceIn(3, 17)
        val (centerX, centerY) = latLonToTile(latitude, longitude, z)
        val maxTile = (1 shl z) - 1
        val samples = mutableListOf<MapTileSample>()
        for (dy in -1..1) {
            for (dx in -1..1) {
                val tx = ((centerX + dx) and maxTile)
                val ty = (centerY + dy).coerceIn(0, maxTile)
                val bmp = loadOrFetchSingleTile(template, style.id, z, tx, ty)
                if (bmp != null) {
                    samples.add(MapTileSample(tx, ty, z, bmp))
                }
            }
        }
        samples
    }

    private fun loadOrFetchSingleTile(
        template: String,
        styleId: String,
        z: Int,
        x: Int,
        y: Int
    ): Bitmap? {
        val cachedFile = File(tileCacheDir, "${styleId}_${z}_${x}_${y}.png")
        if (cachedFile.exists() && cachedFile.length() > 128L) {
            runCatching { BitmapFactory.decodeFile(cachedFile.absolutePath) }.getOrNull()?.let { return it }
        }
        val url = template
            .replace("{z}", z.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "AquaLauncherAndroid/1.0")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val bytes = resp.body?.bytes() ?: return@use null
                if (bytes.size < 128) return@use null
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@use null
                runCatching {
                    FileOutputStream(cachedFile).use { it.write(bytes) }
                }
                bmp
            }
        }.getOrNull()
    }

    suspend fun applySystemStaticWallpaper(
        config: WallpaperConfig,
        widthPx: Int = 1080,
        heightPx: Int = 2400
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val wm = WallpaperManager.getInstance(appContext)
            val customBmp = if ((config.typeId == WallpaperType.CUSTOM_PHOTO.id ||
                    config.typeId == WallpaperType.CUSTOM_VIDEO.id) &&
                config.customMediaUri.isNotBlank()
            ) {
                loadBitmapFromContentUri(Uri.parse(config.customMediaUri))
            } else null

            val tileSamples = if (config.typeId == WallpaperType.LIVE_MAP.id) {
                fetchMapTileGrid(
                    latitude = config.mapLatitude,
                    longitude = config.mapLongitude,
                    zoom = config.mapZoom,
                    style = MapWallpaperStyle.fromId(config.mapStyleId)
                )
            } else emptyList()

            val finalBitmap = customBmp ?: renderWallpaperToBitmap(
                config = config,
                widthPx = widthPx.coerceIn(720, 1440),
                heightPx = heightPx.coerceIn(1280, 2800),
                phase = 0.25f,
                mapTiles = tileSamples
            )

            val flags = when (config.targetScreen.uppercase()) {
                "HOME" -> WallpaperManager.FLAG_SYSTEM
                "LOCK" -> WallpaperManager.FLAG_LOCK
                else -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                wm.setBitmap(finalBitmap, null, true, flags)
            } else {
                @Suppress("DEPRECATION")
                wm.setBitmap(finalBitmap)
            }
            val targetLabel = when (config.targetScreen.uppercase()) {
                "HOME" -> "Home screen"
                "LOCK" -> "Lock screen"
                else -> "Home & Lock screens"
            }
            "Applied '${config.label}' to Android $targetLabel."
        }
    }

    fun launchSystemLiveWallpaperChooser(): Result<String> = runCatching {
        val comp = ComponentName(appContext, AquaLiveWallpaperService::class.java)
        val liveIntent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, comp)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val started = runCatching {
            appContext.startActivity(liveIntent)
            true
        }.getOrDefault(false)
        if (!started) {
            val fallback = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(fallback)
        }
        "Opened Android Live Wallpaper selector for Aqua Live Wallpaper."
    }

    private fun loadBitmapFromContentUri(uri: Uri): Bitmap? {
        return runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull()
    }

    fun renderWallpaperToBitmap(
        config: WallpaperConfig,
        widthPx: Int,
        heightPx: Int,
        phase: Float,
        mapTiles: List<MapTileSample> = emptyList()
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawWallpaperCanvas(canvas, widthPx, heightPx, config, phase, mapTiles)
        return bitmap
    }

    companion object {
        fun latLonToTile(lat: Double, lon: Double, zoom: Int): Pair<Int, Int> {
            val n = (1 shl zoom).toDouble()
            val latRad = Math.toRadians(lat.coerceIn(-85.0, 85.0))
            val x = floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, (1 shl zoom) - 1)
            val y = floor((1.0 - asinh(tan(latRad)) / PI) / 2.0 * n).toInt().coerceIn(0, (1 shl zoom) - 1)
            return x to y
        }

        fun parseHexInt(hex: String, fallback: Int): Int {
            return runCatching { Color.parseColor(hex) }.getOrDefault(fallback)
        }

        fun drawWallpaperCanvas(
            canvas: Canvas,
            w: Int,
            h: Int,
            config: WallpaperConfig,
            phase: Float,
            mapTiles: List<MapTileSample> = emptyList()
        ) {
            val startColor = parseHexInt(config.gradientStartHex, Color.rgb(3, 16, 30))
            val midColor = parseHexInt(config.gradientMidHex, Color.rgb(7, 38, 66))
            val endColor = parseHexInt(config.gradientEndHex, Color.rgb(0, 77, 102))
            val accentColor = parseHexInt(config.accentHex, Color.rgb(0, 229, 255))

            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, w.toFloat(), h.toFloat(),
                    intArrayOf(startColor, midColor, endColor),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

            when (WallpaperType.fromId(config.typeId)) {
                WallpaperType.LIVE_MAP -> {
                    drawMapCanvas(canvas, w, h, config, phase, mapTiles, accentColor)
                }
                WallpaperType.GAMING_CYBER -> {
                    drawCyberGridCanvas(canvas, w, h, phase, accentColor)
                }
                WallpaperType.AI_GENERATIVE -> {
                    drawGenerativeFlowCanvas(canvas, w, h, config.aiPromptSeed, phase, accentColor)
                }
                WallpaperType.ANIMATED_AURORA -> {
                    drawAuroraCanvas(canvas, w, h, phase, accentColor)
                }
                WallpaperType.DYNAMIC_GRADIENT,
                WallpaperType.STATIC_OCEAN,
                WallpaperType.CUSTOM_PHOTO,
                WallpaperType.CUSTOM_VIDEO -> {
                    drawSoftRadialGlow(canvas, w, h, phase, accentColor)
                }
            }
        }

        private fun drawAuroraCanvas(canvas: Canvas, w: Int, h: Int, phase: Float, accentColor: Int) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val radX = w * (0.5f + 0.18f * cos(phase * 2f * PI.toFloat()))
            val radY = h * (0.28f + 0.08f * sin(phase * 2f * PI.toFloat()))
            paint.shader = RadialGradient(
                radX, radY, w * 0.85f,
                Color.argb(115, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(radX, radY, w * 0.85f, paint)

            val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f
                color = Color.argb(95, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
            }
            for (band in 0 until 4) {
                val path = Path()
                val baseY = h * (0.38f + band * 0.12f)
                path.moveTo(0f, baseY)
                var x = 0f
                val step = (w / 24f).coerceAtLeast(16f)
                while (x <= w + step) {
                    val y = baseY + sin((x / w * 4f + phase * 2f + band * 0.7f) * PI.toFloat()) * (h * 0.035f)
                    path.lineTo(x, y)
                    x += step
                }
                canvas.drawPath(path, wavePaint)
            }
        }

        private fun drawCyberGridCanvas(canvas: Canvas, w: Int, h: Int, phase: Float, accentColor: Int) {
            val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(72, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
                strokeWidth = 1.6f
            }
            val spacing = (w / 10f).coerceAtLeast(48f)
            val drift = (phase * spacing) % spacing
            var x = drift
            while (x < w) {
                canvas.drawLine(x, 0f, x, h.toFloat(), gridPaint)
                x += spacing
            }
            var y = drift
            while (y < h) {
                canvas.drawLine(0f, y, w.toFloat(), y, gridPaint)
                y += spacing
            }
            drawSoftRadialGlow(canvas, w, h, phase, accentColor)
        }

        private fun drawGenerativeFlowCanvas(
            canvas: Canvas,
            w: Int,
            h: Int,
            seedText: String,
            phase: Float,
            accentColor: Int
        ) {
            val seed = abs(seedText.hashCode())
            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2.4f
            }
            val cx = w * (0.35f + (seed % 30) / 100f)
            val cy = h * (0.30f + ((seed / 7) % 30) / 100f)
            for (i in 1..14) {
                val alpha = (140 - i * 8).coerceIn(20, 140)
                ringPaint.color = Color.argb(alpha, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
                val r = i * (w * 0.055f) + sin((phase * 2f * PI.toFloat()) + i * 0.4f) * 14f
                canvas.drawCircle(cx, cy, r, ringPaint)
            }
        }

        private fun drawMapCanvas(
            canvas: Canvas,
            w: Int,
            h: Int,
            config: WallpaperConfig,
            phase: Float,
            mapTiles: List<MapTileSample>,
            accentColor: Int
        ) {
            if (mapTiles.isNotEmpty()) {
                val (cx, cy) = latLonToTile(config.mapLatitude, config.mapLongitude, config.mapZoom.coerceIn(3, 17))
                val tilePx = (maxOf(w, h) / 2.2f).coerceAtLeast(360f)
                val panX = if (config.mapAnimatePan) sin(phase * 2f * PI.toFloat()) * 18f else 0f
                val panY = if (config.mapAnimatePan) cos(phase * 2f * PI.toFloat()) * 14f else 0f
                val originX = w / 2f - tilePx / 2f + panX
                val originY = h / 2f - tilePx / 2f + panY
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                mapTiles.forEach { sample ->
                    val dx = sample.tileX - cx
                    val dy = sample.tileY - cy
                    val left = (originX + dx * tilePx).toInt()
                    val top = (originY + dy * tilePx).toInt()
                    val dst = Rect(left, top, (left + tilePx).toInt() + 1, (top + tilePx).toInt() + 1)
                    canvas.drawBitmap(sample.bitmap, null, dst, paint)
                }
                // Subtle ocean vignette for crisp icon contrast
                val tintPaint = Paint().apply {
                    color = Color.argb(90, 4, 17, 31)
                }
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), tintPaint)
            } else {
                // Offline topographic grid fallback when network is unavailable
                val topoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                    color = Color.argb(85, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
                }
                val latGrid = 12
                for (i in 0..latGrid) {
                    val y = i * (h.toFloat() / latGrid)
                    canvas.drawLine(0f, y, w.toFloat(), y, topoPaint)
                }
                for (j in 0..8) {
                    val x = j * (w.toFloat() / 8)
                    canvas.drawLine(x, 0f, x, h.toFloat(), topoPaint)
                }
                val contourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2.5f
                    color = Color.argb(120, 29, 233, 182)
                }
                for (ring in 1..6) {
                    val radius = ring * (w * 0.09f)
                    canvas.drawCircle(w * 0.5f, h * 0.45f, radius, contourPaint)
                }
            }
            // Pin reticle at selected coordinates
            val pulseR = 14f + 6f * sin(phase * 2f * PI.toFloat())
            val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f
                color = accentColor
            }
            canvas.drawCircle(w * 0.5f, h * 0.46f, pulseR, reticlePaint)
            val attrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(175, 220, 240, 255)
                textSize = (w * 0.024f).coerceIn(18f, 28f)
            }
            canvas.drawText(
                "Aqua Live Map • © OpenStreetMap contributors",
                24f,
                h - 32f,
                attrPaint
            )
        }

        private fun drawSoftRadialGlow(canvas: Canvas, w: Int, h: Int, phase: Float, accentColor: Int) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = RadialGradient(
                w * 0.72f,
                h * (0.24f + 0.04f * sin(phase * 2f * PI.toFloat())),
                w * 0.7f,
                Color.argb(75, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        }
    }
}
