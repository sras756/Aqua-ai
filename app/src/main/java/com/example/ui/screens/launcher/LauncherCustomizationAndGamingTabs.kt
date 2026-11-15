package com.example.ui.screens.launcher

import android.Manifest
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.location.LocationManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.launcher.engine.AquaLauncherEngine
import com.example.launcher.engine.LauncherThemeEngine
import com.example.launcher.engine.ResolvedLauncherTheme
import com.example.launcher.model.DiscoveredApp
import com.example.launcher.model.GamingThermalAndPerfSnapshot
import com.example.launcher.model.GestureActionType
import com.example.launcher.model.GestureTrigger
import com.example.launcher.model.LauncherFontOption
import com.example.launcher.model.LauncherIconPackStyle
import com.example.launcher.model.LauncherIconShape
import com.example.launcher.model.LauncherState
import com.example.launcher.model.LauncherThemePreset
import com.example.launcher.model.LauncherWidgetType
import com.example.launcher.model.MapWallpaperStyle
import com.example.launcher.model.WallpaperType
import com.example.launcher.wallpaper.MapGeocodeResult
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LauncherThemeAndLayoutTab(
    engine: AquaLauncherEngine,
    state: LauncherState,
    resolvedTheme: ResolvedLauncherTheme,
    modifier: Modifier = Modifier
) {
    val theme = state.theme
    val colorSwatches = remember {
        listOf("#00E5FF", "#2979FF", "#00FF9C", "#5EEAD4", "#FBBF24", "#FF2E93", "#E2F1FF")
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionCard(resolvedTheme, "Theme Presets") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(LauncherThemePreset.entries) { preset ->
                        FilterChip(
                            selected = theme.presetId == preset.id,
                            onClick = { engine.applyThemePreset(preset) },
                            label = { Text(preset.displayName) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Primary Accent Color", color = resolvedTheme.textMuted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    colorSwatches.forEach { hex ->
                        val c = LauncherThemeEngine.parseColorOrDefault(hex, resolvedTheme.primaryColor)
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(
                                    width = if (theme.primaryColorHex.equals(hex, true)) 2.5.dp else 1.dp,
                                    color = if (theme.primaryColorHex.equals(hex, true)) Color.White else Color.Transparent,
                                    shape = CircleShape
                                )
                                .clickable {
                                    engine.updateThemeConfig {
                                        it.copy(presetId = LauncherThemePreset.CUSTOM.id, primaryColorHex = hex)
                                    }
                                }
                        )
                    }
                }
            }
        }

        item {
            SectionCard(resolvedTheme, "Transparency & Geometry") {
                LabeledSlider(
                    label = "Background Opacity (${(theme.backgroundAlpha * 100).toInt()}%)",
                    value = theme.backgroundAlpha,
                    range = 0.2f..1.0f,
                    onValueChange = { v -> engine.updateThemeConfig { it.copy(backgroundAlpha = v) } },
                    resolvedTheme = resolvedTheme
                )
                LabeledSlider(
                    label = "Widget Glass Opacity (${(theme.widgetAlpha * 100).toInt()}%)",
                    value = theme.widgetAlpha,
                    range = 0.18f..0.95f,
                    onValueChange = { v -> engine.updateThemeConfig { it.copy(widgetAlpha = v) } },
                    resolvedTheme = resolvedTheme
                )
                LabeledSlider(
                    label = "Icon Size (${theme.iconSizeDp} dp)",
                    value = theme.iconSizeDp.toFloat(),
                    range = 42f..68f,
                    onValueChange = { v -> engine.updateThemeConfig { it.copy(iconSizeDp = v.toInt()) } },
                    resolvedTheme = resolvedTheme
                )
                LabeledSlider(
                    label = "Card Corner Radius (${theme.cornerRadiusDp} dp)",
                    value = theme.cornerRadiusDp.toFloat(),
                    range = 8f..30f,
                    onValueChange = { v -> engine.updateThemeConfig { it.copy(cornerRadiusDp = v.toInt()) } },
                    resolvedTheme = resolvedTheme
                )
            }
        }

        item {
            SectionCard(resolvedTheme, "Icons, Fonts & Animations") {
                Text("Icon Shape", color = resolvedTheme.textMuted, fontSize = 12.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(LauncherIconShape.entries) { shape ->
                        FilterChip(
                            selected = theme.iconShape == shape.id,
                            onClick = { engine.updateThemeConfig { it.copy(iconShape = shape.id) } },
                            label = { Text(shape.label) }
                        )
                    }
                }
                Text("Icon Frame / Pack Style", color = resolvedTheme.textMuted, fontSize = 12.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(LauncherIconPackStyle.entries) { pack ->
                        FilterChip(
                            selected = theme.iconPackStyle == pack.id,
                            onClick = { engine.updateThemeConfig { it.copy(iconPackStyle = pack.id) } },
                            label = { Text(pack.label) }
                        )
                    }
                }
                Text("Typography", color = resolvedTheme.textMuted, fontSize = 12.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(LauncherFontOption.entries) { f ->
                        FilterChip(
                            selected = theme.fontOption == f.id,
                            onClick = { engine.updateThemeConfig { it.copy(fontOption = f.id) } },
                            label = { Text(f.label) }
                        )
                    }
                }
                LabeledSlider(
                    label = "Animation Speed (${String.format(Locale.US, "%.1fx", theme.animationSpeed)})",
                    value = theme.animationSpeed,
                    range = 0.5f..2.0f,
                    onValueChange = { v -> engine.updateThemeConfig { it.copy(animationSpeed = v) } },
                    resolvedTheme = resolvedTheme
                )
                LabeledSlider(
                    label = "Text Scale (${String.format(Locale.US, "%.2fx", theme.textScale)})",
                    value = theme.textScale,
                    range = 0.85f..1.35f,
                    onValueChange = { v -> engine.updateThemeConfig { it.copy(textScale = v) } },
                    resolvedTheme = resolvedTheme
                )
            }
        }

        item {
            SectionCard(resolvedTheme, "Grid & Home Elements") {
                LabeledSlider(
                    label = "Grid Columns: ${theme.gridColumns}",
                    value = theme.gridColumns.toFloat(),
                    range = 3f..6f,
                    steps = 2,
                    onValueChange = { v -> engine.updateThemeConfig { it.copy(gridColumns = v.toInt().coerceIn(3, 6)) } },
                    resolvedTheme = resolvedTheme
                )
                LabeledSlider(
                    label = "Grid Rows: ${theme.gridRows}",
                    value = theme.gridRows.toFloat(),
                    range = 3f..7f,
                    steps = 3,
                    onValueChange = { v -> engine.updateThemeConfig { it.copy(gridRows = v.toInt().coerceIn(3, 7)) } },
                    resolvedTheme = resolvedTheme
                )
                ToggleRow("Show Search Pill on Home", theme.showSearchBar, resolvedTheme) { on ->
                    engine.updateThemeConfig { it.copy(showSearchBar = on) }
                }
                ToggleRow("Show App Icon Labels", theme.showIconLabels, resolvedTheme) { on ->
                    engine.updateThemeConfig { it.copy(showIconLabels = on) }
                }
                ToggleRow("Show Dock App Labels", theme.showDockLabels, resolvedTheme) { on ->
                    engine.updateThemeConfig { it.copy(showDockLabels = on) }
                }
                ToggleRow("24-Hour Clock", theme.use24HourClock, resolvedTheme) { on ->
                    engine.updateThemeConfig { it.copy(use24HourClock = on) }
                }
            }
        }
    }
}

@Composable
fun LauncherWallpaperAndMapTab(
    engine: AquaLauncherEngine,
    state: LauncherState,
    resolvedTheme: ResolvedLauncherTheme,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wall = state.wallpaper
    var locationQuery by remember { mutableStateOf("") }
    var geocodeResults by remember { mutableStateOf<List<MapGeocodeResult>>(emptyList()) }
    var aiSeedDraft by remember(wall.aiPromptSeed) { mutableStateOf(wall.aiPromptSeed) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            engine.updateWallpaperConfig {
                it.copy(
                    typeId = WallpaperType.CUSTOM_PHOTO.id,
                    label = "Custom Photo Wallpaper",
                    customMediaUri = uri.toString()
                )
            }
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            engine.updateWallpaperConfig {
                it.copy(
                    typeId = WallpaperType.CUSTOM_VIDEO.id,
                    label = "Custom Video Frame",
                    customMediaUri = uri.toString()
                )
            }
        }
    }

    val coarseLocPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            runCatching {
                val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as? LocationManager
                @Suppress("MissingPermission")
                val loc = lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?: lm?.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                if (loc != null) {
                    engine.updateWallpaperConfig {
                        it.copy(
                            typeId = WallpaperType.LIVE_MAP.id,
                            label = "Live Map • Current Area",
                            mapLatitude = loc.latitude,
                            mapLongitude = loc.longitude,
                            mapLocationName = "Current Area (%.2f, %.2f)".format(loc.latitude, loc.longitude),
                            mapUseDeviceLocation = true
                        )
                    }
                } else {
                    engine.postBanner("No last-known coarse location cached yet; choose a city manually.")
                }
            }
        }
    }

    val presetCities = remember {
        listOf(
            Triple("San Francisco", 37.7749, -122.4194),
            Triple("Tokyo Bay", 35.6762, 139.6503),
            Triple("London", 51.5072, -0.1276),
            Triple("New York", 40.7128, -74.0060),
            Triple("Reykjavik", 64.1466, -21.9426)
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionCard(resolvedTheme, "Aqua Wallpaper Engine") {
                Text("Select Wallpaper Mode", color = resolvedTheme.textMuted, fontSize = 12.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(WallpaperType.entries) { wt ->
                        FilterChip(
                            selected = wall.typeId == wt.id,
                            onClick = {
                                engine.updateWallpaperConfig {
                                    it.copy(typeId = wt.id, label = wt.label)
                                }
                            },
                            label = { Text(wt.label) }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pick Photo")
                    }
                    OutlinedButton(
                        onClick = { videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Wallpaper, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pick Video")
                    }
                }

                if (wall.typeId == WallpaperType.AI_GENERATIVE.id) {
                    OutlinedTextField(
                        value = aiSeedDraft,
                        onValueChange = { aiSeedDraft = it },
                        label = { Text("Generative Pattern Prompt Seed") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = {
                        engine.updateWallpaperConfig { it.copy(aiPromptSeed = aiSeedDraft.ifBlank { "Deep Aqua Reef" }) }
                    }) {
                        Text("Regenerate Generative Canvas")
                    }
                }

                Text("Target Screen", color = resolvedTheme.textMuted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("HOME" to "Home", "LOCK" to "Lock Screen", "BOTH" to "Home & Lock").forEach { (key, label) ->
                        FilterChip(
                            selected = wall.targetScreen == key,
                            onClick = { engine.updateWallpaperConfig { it.copy(targetScreen = key) } },
                            label = { Text(label) }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { scope.launch { engine.applySystemWallpaperFromCurrent() } },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Apply to System")
                    }
                    OutlinedButton(
                        onClick = { engine.wallpaperEngine.launchSystemLiveWallpaperChooser() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Set Live Service")
                    }
                }
            }
        }

        // Aqua Live Map Wallpaper dedicated section
        item {
            SectionCard(resolvedTheme, "Aqua Live Map Wallpaper") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Map, contentDescription = null, tint = resolvedTheme.primaryColor)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${wall.mapLocationName} (${String.format(Locale.US, "%.3f, %.3f", wall.mapLatitude, wall.mapLongitude)})",
                        color = resolvedTheme.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = locationQuery,
                        onValueChange = { locationQuery = it },
                        placeholder = { Text("Search city or landmark...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = {
                        if (locationQuery.isNotBlank()) {
                            scope.launch {
                                engine.searchMapWallpaperLocations(locationQuery)
                                    .onSuccess { geocodeResults = it }
                                    .onFailure { engine.postBanner(it.localizedMessage ?: "Location search unavailable") }
                            }
                        }
                    }) {
                        Text("Find")
                    }
                }

                if (geocodeResults.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        geocodeResults.forEach { hit ->
                            Surface(
                                color = resolvedTheme.surfaceColor.copy(alpha = 0.8f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        engine.updateWallpaperConfig {
                                            it.copy(
                                                typeId = WallpaperType.LIVE_MAP.id,
                                                label = "Live Map • ${hit.displayName.substringBefore(",")}",
                                                mapLatitude = hit.latitude,
                                                mapLongitude = hit.longitude,
                                                mapLocationName = hit.displayName.substringBefore(","),
                                                mapUseDeviceLocation = false
                                            )
                                        }
                                        geocodeResults = emptyList()
                                    }
                            ) {
                                Text(hit.displayName, color = resolvedTheme.textPrimary, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                            }
                        }
                    }
                }

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(presetCities) { (city, lat, lon) ->
                        FilterChip(
                            selected = wall.mapLocationName.contains(city, true),
                            onClick = {
                                engine.updateWallpaperConfig {
                                    it.copy(
                                        typeId = WallpaperType.LIVE_MAP.id,
                                        label = "Live Map • $city",
                                        mapLatitude = lat,
                                        mapLongitude = lon,
                                        mapLocationName = city,
                                        mapUseDeviceLocation = false
                                    )
                                }
                            },
                            label = { Text(city) }
                        )
                    }
                    item {
                        OutlinedButton(onClick = {
                            coarseLocPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                        }) {
                            Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("My Area")
                        }
                    }
                }

                Text("Map Style", color = resolvedTheme.textMuted, fontSize = 12.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(MapWallpaperStyle.entries) { st ->
                        FilterChip(
                            selected = wall.mapStyleId == st.id,
                            onClick = {
                                engine.updateWallpaperConfig {
                                    it.copy(typeId = WallpaperType.LIVE_MAP.id, mapStyleId = st.id)
                                }
                            },
                            label = { Text(st.label) }
                        )
                    }
                }

                LabeledSlider(
                    label = "Map Zoom Level: ${wall.mapZoom}",
                    value = wall.mapZoom.toFloat(),
                    range = 3f..17f,
                    steps = 13,
                    onValueChange = { z ->
                        engine.updateWallpaperConfig { it.copy(typeId = WallpaperType.LIVE_MAP.id, mapZoom = z.toInt()) }
                    },
                    resolvedTheme = resolvedTheme
                )
                ToggleRow("Gentle Drift Animation", wall.mapAnimatePan, resolvedTheme) { anim ->
                    engine.updateWallpaperConfig { it.copy(mapAnimatePan = anim) }
                }
                Text(
                    text = "Privacy note: Coordinates are stored locally and only used when Live Map is active. Attribution: © OpenStreetMap contributors.",
                    color = resolvedTheme.textMuted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
fun LauncherWidgetsGesturesAndGamingTab(
    engine: AquaLauncherEngine,
    state: LauncherState,
    installedApps: List<DiscoveredApp>,
    thermal: GamingThermalAndPerfSnapshot,
    resolvedTheme: ResolvedLauncherTheme,
    onRequestBindSystemWidget: ((AppWidgetProviderInfo) -> Unit)? = null,
    onLaunchApp: (DiscoveredApp) -> Unit,
    modifier: Modifier = Modifier
) {
    val systemWidgets = remember { engine.widgetManager.getInstalledSystemWidgets() }
    val gameApps = remember(installedApps, state.gamePackagesAddedByUser, state.gamePackagesExcluded) {
        engine.getGameApps(state, installedApps)
    }
    var showSystemWidgetPicker by remember { mutableStateOf(false) }
    var showTagGamesDialog by remember { mutableStateOf(false) }
    var gameFolderDraft by remember { mutableStateOf("") }
    var showCreateGameFolder by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Gaming Hub Section
        item {
            SectionCard(resolvedTheme, "Aqua Gaming Hub") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.SportsEsports, contentDescription = null, tint = resolvedTheme.primaryColor)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Gaming Mode Theme & Focus", color = resolvedTheme.textPrimary, fontWeight = FontWeight.SemiBold)
                            Text("Applies Cyber Matrix backdrop + neon contrast", color = resolvedTheme.textMuted, fontSize = 11.sp)
                        }
                    }
                    Switch(
                        checked = state.gamingModeActive,
                        onCheckedChange = { engine.toggleGamingMode(it) }
                    )
                }

                // Real Android Hardware Telemetry
                Surface(
                    color = resolvedTheme.surfaceColor.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Thermal: ${thermal.thermalStatusLabel} • Battery: ${if (thermal.batteryPercent >= 0) "${thermal.batteryPercent}%" else "--"} ${if (thermal.isCharging) "(Charging)" else ""}",
                            color = resolvedTheme.textPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Free RAM: ${thermal.ramAvailableMb} / ${thermal.ramTotalMb} MB • Memory pressure: ${if (thermal.lowMemory) "High" else "Nominal"}",
                            color = resolvedTheme.textMuted,
                            fontSize = 11.5.sp
                        )
                        Text(
                            text = "Truthful telemetry from Android ActivityManager & PowerManager; third-party launchers cannot overclock clocks or kill background OS processes.",
                            color = resolvedTheme.textMuted,
                            fontSize = 10.5.sp
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showTagGamesDialog = true }, modifier = Modifier.weight(1f)) {
                        Text("Tag Games (${gameApps.size})")
                    }
                    OutlinedButton(onClick = { showCreateGameFolder = true }, modifier = Modifier.weight(1f)) {
                        Text("+ Game Folder")
                    }
                }

                if (gameApps.isEmpty()) {
                    Text(
                        text = "No games flagged on this device yet. Tap 'Tag Games' to include installed apps in your Gaming Hub.",
                        color = resolvedTheme.textMuted,
                        fontSize = 12.sp
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(gameApps, key = { it.packageName }) { g ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .width(72.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onLaunchApp(g) }
                                    .padding(4.dp)
                            ) {
                                LauncherAppIconBadge(g, g.label, state.theme, resolvedTheme, sizeDp = 48)
                                Spacer(Modifier.height(4.dp))
                                Text(g.label, color = resolvedTheme.textPrimary, fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        // Widget Gallery Section
        item {
            SectionCard(resolvedTheme, "Widgets & Android AppWidgetHost") {
                Button(
                    onClick = { showSystemWidgetPicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Widgets, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Browse Android System Widgets (${systemWidgets.size})")
                }
                Spacer(Modifier.height(4.dp))
                LauncherWidgetType.entries.filter { it != LauncherWidgetType.SYSTEM_APP_WIDGET }.forEach { wt ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { engine.addWidgetToHome(wt, pageIndex = state.preferredHomePage) }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(wt.title, color = resolvedTheme.textPrimary, fontWeight = FontWeight.Medium, fontSize = 13.5.sp)
                            Text(wt.description, color = resolvedTheme.textMuted, fontSize = 11.sp)
                        }
                        Icon(Icons.Filled.Add, contentDescription = "Add ${wt.title}", tint = resolvedTheme.primaryColor)
                    }
                }
            }
        }

        // Gestures Configuration
        item {
            SectionCard(resolvedTheme, "Launcher Gestures") {
                GestureTrigger.entries.forEach { trigger ->
                    val currentAction = state.gestures.actionFor(trigger)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "${trigger.label} → ${currentAction.label}",
                            color = resolvedTheme.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(GestureActionType.entries) { act ->
                                FilterChip(
                                    selected = currentAction == act,
                                    onClick = { engine.updateGesture(trigger, act) },
                                    label = { Text(act.label, fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSystemWidgetPicker) {
        AlertDialog(
            onDismissRequest = { showSystemWidgetPicker = false },
            title = { Text("Android System Widgets (${systemWidgets.size})") },
            text = {
                if (systemWidgets.isEmpty()) {
                    Text("No third-party Android AppWidgets detected on this device.")
                } else {
                    val pkgMgr = LocalContext.current.packageManager
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(systemWidgets.take(40)) { info ->
                            val label = remember(info) { info.loadLabel(pkgMgr) }
                            Surface(
                                color = resolvedTheme.surfaceColor.copy(alpha = 0.7f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showSystemWidgetPicker = false
                                        if (onRequestBindSystemWidget != null) {
                                            onRequestBindSystemWidget(info)
                                        } else {
                                            val id = engine.widgetManager.allocateWidgetId()
                                            if (id != null && engine.widgetManager.bindWidgetIfAllowed(id, info)) {
                                                engine.addWidgetToHome(
                                                    LauncherWidgetType.SYSTEM_APP_WIDGET,
                                                    pageIndex = state.preferredHomePage,
                                                    systemWidgetId = id,
                                                    customTitle = label
                                                )
                                            } else {
                                                engine.postBanner("Open Standalone Launcher to grant Android widget bind permission.")
                                            }
                                        }
                                    }
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text(info.provider.packageName, color = resolvedTheme.textMuted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSystemWidgetPicker = false }) { Text("Close") }
            }
        )
    }

    if (showTagGamesDialog) {
        AlertDialog(
            onDismissRequest = { showTagGamesDialog = false },
            title = { Text("Manage Game Tags") },
            text = {
                LazyColumn {
                    items(installedApps, key = { it.packageName }) { app ->
                        val tagged = (app.isGame && app.packageName !in state.gamePackagesExcluded) ||
                            (app.packageName in state.gamePackagesAddedByUser)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { engine.toggleAppGameTag(app.packageName) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = tagged, onCheckedChange = { engine.toggleAppGameTag(app.packageName) })
                            Spacer(Modifier.width(8.dp))
                            Text(app.label, maxLines = 1)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTagGamesDialog = false }) { Text("Done") }
            }
        )
    }

    if (showCreateGameFolder) {
        AlertDialog(
            onDismissRequest = { showCreateGameFolder = false },
            title = { Text("Create Game Collection") },
            text = {
                OutlinedTextField(
                    value = gameFolderDraft,
                    onValueChange = { gameFolderDraft = it },
                    label = { Text("Folder Name (e.g. FPS, Indie)") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (gameFolderDraft.isNotBlank()) {
                        engine.createGameFolder(gameFolderDraft, gameApps.map { it.packageName })
                        gameFolderDraft = ""
                    }
                    showCreateGameFolder = false
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateGameFolder = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun LauncherDefaultHomeAndSyncTab(
    engine: AquaLauncherEngine,
    state: LauncherState,
    isDefaultHome: Boolean,
    currentHomePackage: String?,
    onRequestSetDefaultHome: () -> Unit,
    onOpenStandaloneLauncher: (() -> Unit)?,
    onPushCloudSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
    resolvedTheme: ResolvedLauncherTheme
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notificationsGranted = remember(isDefaultHome) { engine.deviceController.isNotificationAccessEnabled() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionCard(resolvedTheme, "System Default Home Status") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Home, contentDescription = null, tint = resolvedTheme.primaryColor)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = if (isDefaultHome) "Aqua Launcher is System Default Home" else "Aqua Launcher is NOT System Default Home",
                            color = resolvedTheme.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Current system Home package: ${currentHomePackage ?: "Android System Chooser"}",
                            color = resolvedTheme.textMuted,
                            fontSize = 11.5.sp
                        )
                    }
                }
                Text(
                    text = "Important: Viewing Aqua Launcher inside Aqua AI is an in-app dashboard. Pressing your Android Home button opens Aqua Launcher only when selected as your device's default Home app.",
                    color = resolvedTheme.textMuted,
                    fontSize = 11.5.sp
                )

                Button(
                    onClick = onRequestSetDefaultHome,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("set_default_launcher_button")
                ) {
                    Text(if (isDefaultHome) "Review Default Home Settings" else "Set Aqua Launcher as Default Home")
                }

                if (onOpenStandaloneLauncher != null) {
                    OutlinedButton(
                        onClick = onOpenStandaloneLauncher,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("open_standalone_launcher_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open Standalone Aqua Launcher")
                    }
                }

                OutlinedButton(
                    onClick = { engine.openHomeSettings() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Explore, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Switch Back / Manage System Home Settings")
                }
            }
        }

        item {
            SectionCard(resolvedTheme, "Notifications & Device Signals") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.NotificationsActive, contentDescription = null, tint = resolvedTheme.primaryColor)
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (notificationsGranted) "Notification Previews Enabled" else "Notification Access Not Granted",
                            color = resolvedTheme.textPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text("Required only if you want live notification summaries inside Aqua Launcher.", color = resolvedTheme.textMuted, fontSize = 11.sp)
                    }
                }
                OutlinedButton(
                    onClick = { engine.deviceController.openAndroidSettings("notifications") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Android Notification Access Settings")
                }
            }
        }

        item {
            SectionCard(resolvedTheme, "Firebase Cloud Preference Sync") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CloudSync, contentDescription = null, tint = resolvedTheme.primaryColor)
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Sync Themes, Wallpapers & Gestures", color = resolvedTheme.textPrimary, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (state.lastCloudSyncMs > 0L) {
                                "Last synced: ${SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date(state.lastCloudSyncMs))}"
                            } else "Not yet synced with Firebase",
                            color = resolvedTheme.textMuted,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = state.cloudSyncEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                engine.updateState { it.copy(cloudSyncEnabled = enabled) }
                            }
                        }
                    )
                }
                Text(
                    text = "Privacy guarantee: Installed app package lists, contacts, calendar events, and GPS coordinates are kept strictly on-device and never uploaded.",
                    color = resolvedTheme.textMuted,
                    fontSize = 11.sp
                )
                Button(
                    onClick = {
                        onPushCloudSyncNow()
                        Toast.makeText(context, "Syncing launcher preferences...", Toast.LENGTH_SHORT).show()
                    },
                    enabled = state.cloudSyncEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sync Preferences Now")
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    resolvedTheme: ResolvedLauncherTheme,
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Surface(
        color = resolvedTheme.glassCardColor,
        shape = resolvedTheme.cardShape,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, resolvedTheme.borderColor, resolvedTheme.cardShape)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = {
                Text(
                    text = title,
                    color = resolvedTheme.primaryColor,
                    fontFamily = resolvedTheme.fontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                content()
            }
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
    resolvedTheme: ResolvedLauncherTheme
) {
    Column {
        Text(label, color = resolvedTheme.textPrimary, fontSize = 12.sp)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    resolvedTheme: ResolvedLauncherTheme,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = resolvedTheme.textPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
