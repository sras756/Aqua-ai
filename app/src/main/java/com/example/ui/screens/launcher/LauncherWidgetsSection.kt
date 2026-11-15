package com.example.ui.screens.launcher

import android.Manifest
import android.appwidget.AppWidgetHostView
import android.content.pm.PackageManager
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.device.DeviceTelemetry
import com.example.device.StorageAndMemoryStats
import com.example.launcher.engine.AquaLauncherEngine
import com.example.launcher.engine.ResolvedLauncherTheme
import com.example.launcher.model.DeviceCalendarEventItem
import com.example.launcher.model.DiscoveredApp
import com.example.launcher.model.HomeSlotItem
import com.example.launcher.model.LauncherState
import com.example.launcher.model.LauncherWidgetType
import com.example.launcher.model.WeatherSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LauncherWidgetCard(
    item: HomeSlotItem.WidgetItem,
    engine: AquaLauncherEngine,
    state: LauncherState,
    resolvedTheme: ResolvedLauncherTheme,
    telemetry: DeviceTelemetry,
    storageStats: StorageAndMemoryStats,
    weather: WeatherSnapshot?,
    weatherError: String?,
    calendarEvents: List<DeviceCalendarEventItem>,
    installedApps: List<DiscoveredApp>,
    editMode: Boolean,
    onOpenAiBar: () -> Unit,
    onOpenUniversalSearch: () -> Unit,
    onLaunchApp: (DiscoveredApp) -> Unit,
    modifier: Modifier = Modifier
) {
    val widgetType = LauncherWidgetType.fromId(item.widgetTypeId)
    val scale = state.theme.textScale.coerceIn(0.85f, 1.35f)
    val minHeightDp = (92 * item.rowSpan.coerceIn(1, 3)).dp

    Surface(
        color = resolvedTheme.glassCardColor,
        shape = resolvedTheme.cardShape,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, resolvedTheme.borderColor, resolvedTheme.cardShape)
            .clip(resolvedTheme.cardShape)
            .testTag("launcher_widget_${widgetType.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (editMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${widgetType.title} (${item.colSpan}x${item.rowSpan})",
                        color = resolvedTheme.primaryColor,
                        fontFamily = resolvedTheme.fontFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { engine.resizeWidget(item.id, item.colSpan - 1, item.rowSpan) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Narrower", tint = resolvedTheme.textPrimary, modifier = Modifier.size(16.dp))
                        }
                        IconButton(
                            onClick = { engine.resizeWidget(item.id, item.colSpan + 1, item.rowSpan) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Wider", tint = resolvedTheme.textPrimary, modifier = Modifier.size(16.dp))
                        }
                        IconButton(
                            onClick = { engine.resizeWidget(item.id, item.colSpan, if (item.rowSpan >= 2) 1 else 2) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Toggle Height", tint = resolvedTheme.textPrimary, modifier = Modifier.size(16.dp))
                        }
                        IconButton(
                            onClick = { engine.removeHomeSlot(item.id) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove Widget", tint = resolvedTheme.secondaryColor, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            when (widgetType) {
                LauncherWidgetType.CLOCK_DATE -> ClockWidgetBody(state, resolvedTheme, scale)
                LauncherWidgetType.AQUA_AI -> AquaAiWidgetBody(resolvedTheme, scale, onOpenAiBar)
                LauncherWidgetType.WEATHER -> WeatherWidgetBody(
                    engine = engine,
                    state = state,
                    resolvedTheme = resolvedTheme,
                    weather = weather,
                    weatherError = weatherError,
                    scale = scale
                )
                LauncherWidgetType.CALENDAR -> CalendarWidgetBody(
                    engine = engine,
                    resolvedTheme = resolvedTheme,
                    events = calendarEvents,
                    scale = scale
                )
                LauncherWidgetType.BATTERY_DEVICE -> BatteryWidgetBody(telemetry, resolvedTheme, scale)
                LauncherWidgetType.STORAGE_RAM -> StorageRamWidgetBody(storageStats, resolvedTheme, scale)
                LauncherWidgetType.NETWORK_STATUS -> NetworkStatusWidgetBody(telemetry, resolvedTheme, scale)
                LauncherWidgetType.MUSIC_CONTROLS -> MusicControlsWidgetBody(engine, resolvedTheme, scale)
                LauncherWidgetType.FAVORITE_APPS -> FavoriteAppsWidgetBody(
                    state = state,
                    installedApps = installedApps,
                    resolvedTheme = resolvedTheme,
                    onLaunchApp = onLaunchApp
                )
                LauncherWidgetType.UNIVERSAL_SEARCH -> UniversalSearchWidgetBody(resolvedTheme, scale, onOpenUniversalSearch)
                LauncherWidgetType.NOTES -> NotesWidgetBody(engine, state, resolvedTheme, scale)
                LauncherWidgetType.SYSTEM_APP_WIDGET -> SystemAppWidgetBody(
                    item = item,
                    engine = engine,
                    resolvedTheme = resolvedTheme,
                    minHeightDp = minHeightDp
                )
            }
        }
    }
}

@Composable
private fun ClockWidgetBody(
    state: LauncherState,
    resolvedTheme: ResolvedLauncherTheme,
    scale: Float
) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(15_000L)
        }
    }
    val timePattern = if (state.theme.use24HourClock) "HH:mm" else "hh:mm a"
    val timeStr = SimpleDateFormat(timePattern, Locale.getDefault()).format(Date(now))
    val dateStr = SimpleDateFormat("EEEE, MMM d, yyyy", Locale.getDefault()).format(Date(now))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = timeStr,
                color = resolvedTheme.textPrimary,
                fontFamily = resolvedTheme.fontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = (28f * scale).sp
            )
            Text(
                text = dateStr,
                color = resolvedTheme.textMuted,
                fontFamily = resolvedTheme.fontFamily,
                fontSize = (13f * scale).sp
            )
        }
        Surface(
            color = resolvedTheme.primaryColor.copy(alpha = 0.16f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = "AQUA OS",
                color = resolvedTheme.primaryColor,
                fontFamily = resolvedTheme.fontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
private fun AquaAiWidgetBody(
    resolvedTheme: ResolvedLauncherTheme,
    scale: Float,
    onOpenAiBar: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenAiBar)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(resolvedTheme.primaryColor.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = "Aqua AI Assistant", tint = resolvedTheme.primaryColor)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Ask Aqua Launcher AI",
                color = resolvedTheme.textPrimary,
                fontFamily = resolvedTheme.fontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = (14f * scale).sp
            )
            Text(
                text = "Tap to run commands: \"Open browser\", \"Battery\", \"Live Map wallpaper\"",
                color = resolvedTheme.textMuted,
                fontFamily = resolvedTheme.fontFamily,
                fontSize = (11.5f * scale).sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.Filled.Mic, contentDescription = "Command", tint = resolvedTheme.primaryColor)
    }
}

@Composable
private fun WeatherWidgetBody(
    engine: AquaLauncherEngine,
    state: LauncherState,
    resolvedTheme: ResolvedLauncherTheme,
    weather: WeatherSnapshot?,
    weatherError: String?,
    scale: Float
) {
    val scope = rememberCoroutineScope()
    var showCityDialog by remember { mutableStateOf(false) }
    var cityInput by remember(state.weatherCityQuery) { mutableStateOf(state.weatherCityQuery) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.WbSunny, contentDescription = "Weather", tint = resolvedTheme.primaryColor, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = weather?.locationName ?: state.weatherCityQuery,
                    color = resolvedTheme.textPrimary,
                    fontFamily = resolvedTheme.fontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (13f * scale).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row {
                IconButton(onClick = { showCityDialog = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Edit, contentDescription = "Set Weather City", tint = resolvedTheme.textMuted, modifier = Modifier.size(15.dp))
                }
                IconButton(
                    onClick = { scope.launch { engine.refreshWeather() } },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh Weather", tint = resolvedTheme.primaryColor, modifier = Modifier.size(16.dp))
                }
            }
        }
        if (weather != null) {
            Text(
                text = String.format(Locale.getDefault(), "%.1f°C • %s", weather.temperatureC, weather.conditionLabel),
                color = resolvedTheme.textPrimary,
                fontFamily = resolvedTheme.fontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = (16f * scale).sp
            )
            Text(
                text = "Humidity ${weather.humidityPercent}% • Wind ${String.format(Locale.getDefault(), "%.0f", weather.windKmh)} km/h",
                color = resolvedTheme.textMuted,
                fontFamily = resolvedTheme.fontFamily,
                fontSize = (11f * scale).sp
            )
        } else {
            Text(
                text = weatherError ?: "Tap refresh to fetch live Open-Meteo weather",
                color = resolvedTheme.textMuted,
                fontFamily = resolvedTheme.fontFamily,
                fontSize = (11.5f * scale).sp,
                maxLines = 2
            )
        }
    }

    if (showCityDialog) {
        AlertDialog(
            onDismissRequest = { showCityDialog = false },
            title = { Text("Weather Location") },
            text = {
                OutlinedTextField(
                    value = cityInput,
                    onValueChange = { cityInput = it },
                    label = { Text("City Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val query = cityInput.trim()
                    showCityDialog = false
                    if (query.isNotBlank()) {
                        scope.launch { engine.refreshWeather(cityOverride = query) }
                    }
                }) { Text("Save & Refresh") }
            },
            dismissButton = {
                TextButton(onClick = { showCityDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun CalendarWidgetBody(
    engine: AquaLauncherEngine,
    resolvedTheme: ResolvedLauncherTheme,
    events: List<DeviceCalendarEventItem>,
    scale: Float
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) scope.launch { engine.refreshCalendarEvents() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CalendarToday, contentDescription = "Calendar", tint = resolvedTheme.primaryColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Calendar Agenda", color = resolvedTheme.textPrimary, fontFamily = resolvedTheme.fontFamily, fontWeight = FontWeight.SemiBold, fontSize = (13f * scale).sp)
            }
            if (!hasPermission) {
                Text(
                    text = "Allow",
                    color = resolvedTheme.primaryColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { permLauncher.launch(Manifest.permission.READ_CALENDAR) }
                )
            }
        }
        if (!hasPermission) {
            Text(
                text = "Grant Calendar permission to show your upcoming events.",
                color = resolvedTheme.textMuted,
                fontSize = (11f * scale).sp
            )
        } else if (events.isEmpty()) {
            Text(
                text = "No upcoming events in the next 7 days.",
                color = resolvedTheme.textMuted,
                fontSize = (11f * scale).sp
            )
        } else {
            val fmt = remember { SimpleDateFormat("EEE HH:mm", Locale.getDefault()) }
            events.take(2).forEach { ev ->
                Text(
                    text = "${fmt.format(Date(ev.startMs))} • ${ev.title}",
                    color = resolvedTheme.textPrimary,
                    fontSize = (11.5f * scale).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun BatteryWidgetBody(
    telemetry: DeviceTelemetry,
    resolvedTheme: ResolvedLauncherTheme,
    scale: Float
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (telemetry.isCharging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryStd,
                contentDescription = "Battery",
                tint = resolvedTheme.primaryColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (telemetry.batteryPercent >= 0) "${telemetry.batteryPercent}%" else "--%",
                color = resolvedTheme.textPrimary,
                fontFamily = resolvedTheme.fontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = (17f * scale).sp
            )
        }
        if (telemetry.batteryPercent in 0..100) {
            LinearProgressIndicator(
                progress = { telemetry.batteryPercent / 100f },
                color = resolvedTheme.primaryColor,
                trackColor = resolvedTheme.surfaceColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
        }
        Text(
            text = if (telemetry.isCharging) "Charging active" else "On battery power",
            color = resolvedTheme.textMuted,
            fontSize = (11f * scale).sp
        )
    }
}

@Composable
private fun StorageRamWidgetBody(
    stats: StorageAndMemoryStats,
    resolvedTheme: ResolvedLauncherTheme,
    scale: Float
) {
    val ramFrac = if (stats.ramTotalMb > 0) 1f - (stats.ramAvailableMb.toFloat() / stats.ramTotalMb.toFloat()) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Memory, contentDescription = "Memory & Storage", tint = resolvedTheme.primaryColor, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text("Device Memory", color = resolvedTheme.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = (13f * scale).sp)
        }
        Text(
            text = "RAM free: ${stats.ramAvailableMb} / ${stats.ramTotalMb} MB",
            color = resolvedTheme.textPrimary,
            fontSize = (11.5f * scale).sp
        )
        LinearProgressIndicator(
            progress = { ramFrac.coerceIn(0f, 1f) },
            color = resolvedTheme.secondaryColor,
            trackColor = resolvedTheme.surfaceColor,
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(4.dp))
        )
        Text(
            text = "Disk free: ${stats.storageFreeMb / 1024} GB / ${stats.storageTotalMb / 1024} GB",
            color = resolvedTheme.textMuted,
            fontSize = (11f * scale).sp
        )
    }
}

@Composable
private fun NetworkStatusWidgetBody(
    telemetry: DeviceTelemetry,
    resolvedTheme: ResolvedLauncherTheme,
    scale: Float
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (telemetry.isOnline) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                contentDescription = "Network Status",
                tint = resolvedTheme.primaryColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = telemetry.connectionType,
                color = resolvedTheme.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = (14f * scale).sp
            )
        }
        Text(
            text = if (telemetry.isOnline) "Connected & ready" else "Offline — cached mode active",
            color = resolvedTheme.textMuted,
            fontSize = (11f * scale).sp
        )
    }
}

@Composable
private fun MusicControlsWidgetBody(
    engine: AquaLauncherEngine,
    resolvedTheme: ResolvedLauncherTheme,
    scale: Float
) {
    var playingState by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.MusicNote, contentDescription = "Media Controls", tint = resolvedTheme.primaryColor)
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Media Transport", color = resolvedTheme.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = (13f * scale).sp)
                Text("Controls active Android audio player", color = resolvedTheme.textMuted, fontSize = (11f * scale).sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { engine.widgetManager.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous Track", tint = resolvedTheme.textPrimary)
            }
            IconButton(onClick = {
                engine.widgetManager.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                playingState = !playingState
            }) {
                Icon(
                    imageVector = if (playingState) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "Play/Pause Media",
                    tint = resolvedTheme.primaryColor
                )
            }
            IconButton(onClick = { engine.widgetManager.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next Track", tint = resolvedTheme.textPrimary)
            }
        }
    }
}

@Composable
private fun FavoriteAppsWidgetBody(
    state: LauncherState,
    installedApps: List<DiscoveredApp>,
    resolvedTheme: ResolvedLauncherTheme,
    onLaunchApp: (DiscoveredApp) -> Unit
) {
    val favs = installedApps.filter { it.packageName in state.favoritePackages }.take(4)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Star, contentDescription = "Favorites", tint = resolvedTheme.primaryColor, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Favorite Apps", color = resolvedTheme.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
        if (favs.isEmpty()) {
            Text(
                text = "Long-press any app in the App Drawer to star it as a favorite.",
                color = resolvedTheme.textMuted,
                fontSize = 11.5.sp
            )
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                favs.forEach { app ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onLaunchApp(app) }
                    ) {
                        LauncherAppIconBadge(app, app.label, state.theme, resolvedTheme, sizeDp = 44)
                        Spacer(Modifier.height(4.dp))
                        Text(app.label, color = resolvedTheme.textPrimary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun UniversalSearchWidgetBody(
    resolvedTheme: ResolvedLauncherTheme,
    scale: Float,
    onOpenSearch: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(resolvedTheme.surfaceColor.copy(alpha = 0.72f))
            .clickable(onClick = onOpenSearch)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Search, contentDescription = "Aqua Search", tint = resolvedTheme.primaryColor, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Search apps, contacts, files, chats & web...",
            color = resolvedTheme.textMuted,
            fontSize = (12.5f * scale).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun NotesWidgetBody(
    engine: AquaLauncherEngine,
    state: LauncherState,
    resolvedTheme: ResolvedLauncherTheme,
    scale: Float
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(state.quickNoteText) { mutableStateOf(state.quickNoteText) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Quick Notes", color = resolvedTheme.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = (13f * scale).sp)
            IconButton(onClick = { editing = true }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit Quick Note", tint = resolvedTheme.primaryColor, modifier = Modifier.size(16.dp))
            }
        }
        Text(
            text = state.quickNoteText.ifBlank { "Tap edit to jot a quick note (saved locally across restarts)." },
            color = if (state.quickNoteText.isBlank()) resolvedTheme.textMuted else resolvedTheme.textPrimary,
            fontSize = (12f * scale).sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }

    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Quick Note") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Note") },
                    minLines = 3,
                    maxLines = 6
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    engine.setQuickNote(draft)
                    editing = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SystemAppWidgetBody(
    item: HomeSlotItem.WidgetItem,
    engine: AquaLauncherEngine,
    resolvedTheme: ResolvedLauncherTheme,
    minHeightDp: androidx.compose.ui.unit.Dp
) {
    val appWidgetId = item.systemAppWidgetId
    if (appWidgetId == null) {
        Text(
            text = "No Android system widget bound.",
            color = resolvedTheme.textMuted,
            fontSize = 12.sp
        )
        return
    }
    val context = LocalContext.current
    val hostView: AppWidgetHostView? = remember(appWidgetId) {
        engine.widgetManager.createHostView(context, appWidgetId)
    }
    if (hostView == null) {
        Text(
            text = item.customTitle.ifBlank { "System Widget #$appWidgetId (re-bind required)" },
            color = resolvedTheme.textMuted,
            fontSize = 12.sp
        )
    } else {
        AndroidView(
            factory = { hostView },
            modifier = Modifier
                .fillMaxWidth()
                .height(minHeightDp.coerceAtLeast(130.dp))
        )
    }
}
