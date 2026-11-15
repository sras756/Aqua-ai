package com.example.launcher.wallpaper

import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.example.launcher.engine.LauncherPersistenceStore
import com.example.launcher.model.MapWallpaperStyle
import com.example.launcher.model.WallpaperConfig
import com.example.launcher.model.WallpaperType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AquaLiveWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = AquaWallpaperServiceEngine()

    inner class AquaWallpaperServiceEngine : Engine() {
        private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val handler = Handler(Looper.getMainLooper())
        private val store by lazy { LauncherPersistenceStore(applicationContext) }
        private val wallpaperEngine by lazy { AquaWallpaperEngine(applicationContext) }

        private var visible = false
        private var currentConfig = WallpaperConfig()
        private var cachedMapTiles: List<MapTileSample> = emptyList()
        private var phase = 0f

        private val drawRunnable = Runnable { drawFrame() }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            refreshConfigFromStore()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            handler.removeCallbacks(drawRunnable)
            if (visible) {
                refreshConfigFromStore()
                drawFrame()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            super.onSurfaceDestroyed(holder)
            visible = false
            handler.removeCallbacks(drawRunnable)
        }

        override fun onDestroy() {
            super.onDestroy()
            visible = false
            handler.removeCallbacks(drawRunnable)
            serviceScope.cancel()
        }

        private fun refreshConfigFromStore() {
            serviceScope.launch {
                val state = runCatching { store.loadState() }.getOrNull() ?: return@launch
                currentConfig = state.wallpaper
                if (currentConfig.typeId == WallpaperType.LIVE_MAP.id) {
                    cachedMapTiles = wallpaperEngine.fetchMapTileGrid(
                        latitude = currentConfig.mapLatitude,
                        longitude = currentConfig.mapLongitude,
                        zoom = currentConfig.mapZoom,
                        style = MapWallpaperStyle.fromId(currentConfig.mapStyleId)
                    )
                } else {
                    cachedMapTiles = emptyList()
                }
            }
        }

        private fun drawFrame() {
            val holder = surfaceHolder
            var canvas: android.graphics.Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    AquaWallpaperEngine.drawWallpaperCanvas(
                        canvas = canvas,
                        w = canvas.width,
                        h = canvas.height,
                        config = currentConfig,
                        phase = phase,
                        mapTiles = cachedMapTiles
                    )
                }
            } finally {
                if (canvas != null) {
                    runCatching { holder.unlockCanvasAndPost(canvas) }
                }
            }
            phase = (phase + 0.012f) % 1.0f
            handler.removeCallbacks(drawRunnable)
            if (visible) {
                // Battery-conscious ~20fps animation for live animated modes, static 2s refresh otherwise
                val delayMs = when (WallpaperType.fromId(currentConfig.typeId)) {
                    WallpaperType.ANIMATED_AURORA,
                    WallpaperType.GAMING_CYBER,
                    WallpaperType.AI_GENERATIVE,
                    WallpaperType.LIVE_MAP -> 55L
                    else -> 2000L
                }
                handler.postDelayed(drawRunnable, delayMs)
            }
        }
    }
}
