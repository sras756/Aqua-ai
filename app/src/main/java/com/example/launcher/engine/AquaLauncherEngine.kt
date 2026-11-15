package com.example.launcher.engine

import android.app.ActivityManager
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import com.example.browser.AquaBrowserController
import com.example.data.model.BrowsingTab
import com.example.data.model.Conversation
import com.example.data.model.MemoryItem
import com.example.device.AndroidDeviceController
import com.example.device.CapturedNotification
import com.example.launcher.model.AppCategory
import com.example.launcher.model.DeviceCalendarEventItem
import com.example.launcher.model.DiscoveredApp
import com.example.launcher.model.DrawerFolder
import com.example.launcher.model.DrawerSortMode
import com.example.launcher.model.GamingThermalAndPerfSnapshot
import com.example.launcher.model.GestureActionType
import com.example.launcher.model.GestureTrigger
import com.example.launcher.model.HomeSlotItem
import com.example.launcher.model.LauncherGestureConfig
import com.example.launcher.model.LauncherState
import com.example.launcher.model.LauncherThemeConfig
import com.example.launcher.model.LauncherThemePreset
import com.example.launcher.model.LauncherWidgetType
import com.example.launcher.model.MapWallpaperStyle
import com.example.launcher.model.UniversalSearchHit
import com.example.launcher.model.WallpaperConfig
import com.example.launcher.model.WallpaperType
import com.example.launcher.model.WeatherSnapshot
import com.example.launcher.wallpaper.AquaWallpaperEngine
import com.example.launcher.wallpaper.MapGeocodeResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class AquaLauncherEngine private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stateMutex = Mutex()

    val persistenceStore = LauncherPersistenceStore(appContext)
    val appDiscovery = AppDiscoveryEngine(appContext)
    val widgetManager = LauncherWidgetManager(appContext)
    val wallpaperEngine = AquaWallpaperEngine(appContext)
    val searchEngine = AquaUniversalSearchEngine(appContext)
    val deviceController = AndroidDeviceController(appContext)

    private val _state = MutableStateFlow(LauncherState())
    val state: StateFlow<LauncherState> = _state.asStateFlow()

    private val _installedApps = MutableStateFlow<List<DiscoveredApp>>(emptyList())
    val installedApps: StateFlow<List<DiscoveredApp>> = _installedApps.asStateFlow()

    private val _weather = MutableStateFlow<WeatherSnapshot?>(null)
    val weather: StateFlow<WeatherSnapshot?> = _weather.asStateFlow()

    private val _weatherError = MutableStateFlow<String?>(null)
    val weatherError: StateFlow<String?> = _weatherError.asStateFlow()

    private val _calendarEvents = MutableStateFlow<List<DeviceCalendarEventItem>>(emptyList())
    val calendarEvents: StateFlow<List<DeviceCalendarEventItem>> = _calendarEvents.asStateFlow()

    private val _isDefaultLauncher = MutableStateFlow(false)
    val isDefaultLauncher: StateFlow<Boolean> = _isDefaultLauncher.asStateFlow()

    private val _currentDefaultHomePackage = MutableStateFlow<String?>(null)
    val currentDefaultHomePackage: StateFlow<String?> = _currentDefaultHomePackage.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _thermalSnapshot = MutableStateFlow(readGamingThermalSnapshot())
    val thermalSnapshot: StateFlow<GamingThermalAndPerfSnapshot> = _thermalSnapshot.asStateFlow()

    private var packageReceiver: BroadcastReceiver? = null
    private var receiverRefCount = 0
    private var cloudPushCallback: (suspend (String) -> Unit)? = null

    init {
        engineScope.launch {
            val loaded = persistenceStore.loadState()
            _state.value = loaded
            _weather.value = persistenceStore.loadCachedWeather()
            refreshDefaultLauncherStatus()
            refreshInstalledApps(seedDockIfFirstRun = !loaded.hasCompletedInitialSeed)
            if (widgetManager.hasCalendarPermission()) {
                refreshCalendarEvents()
            }
        }
    }

    fun clearBanner() {
        _statusBanner.value = null
    }

    fun postBanner(message: String) {
        _statusBanner.value = message
    }

    fun attachCloudPushCallback(callback: (suspend (String) -> Unit)?) {
        cloudPushCallback = callback
    }

    fun startWatchingPackages() {
        receiverRefCount++
        if (packageReceiver == null) {
            packageReceiver = appDiscovery.registerPackageReceiver {
                engineScope.launch { refreshInstalledApps(seedDockIfFirstRun = false) }
            }
        }
        refreshDefaultLauncherStatus()
    }

    fun stopWatchingPackages() {
        receiverRefCount = (receiverRefCount - 1).coerceAtLeast(0)
        if (receiverRefCount == 0 && packageReceiver != null) {
            appDiscovery.unregisterReceiverSafely(packageReceiver)
            packageReceiver = null
        }
    }

    fun refreshDefaultLauncherStatus() {
        val pm = appContext.packageManager
        val homeIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
        val resolved = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.resolveActivity(homeIntent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            }
        }.getOrNull()
        val pkg = resolved?.activityInfo?.packageName
        _currentDefaultHomePackage.value = pkg
        val roleHeld = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val rm = appContext.getSystemService(Context.ROLE_SERVICE) as? RoleManager
                rm?.isRoleHeld(RoleManager.ROLE_HOME) == true
            }.getOrDefault(false)
        } else false
        _isDefaultLauncher.value = roleHeld || (pkg == appContext.packageName)
    }

    fun buildRequestDefaultHomeIntent(): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = appContext.getSystemService(Context.ROLE_SERVICE) as? RoleManager
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                return runCatching { rm.createRequestRoleIntent(RoleManager.ROLE_HOME) }.getOrNull()
            }
        }
        return Intent(Settings.ACTION_HOME_SETTINGS)
    }

    fun openHomeSettings(): Result<String> = runCatching {
        val intent = Intent(Settings.ACTION_HOME_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
        "Opened Android Default Home app settings."
    }

    suspend fun refreshInstalledApps(seedDockIfFirstRun: Boolean = false) {
        val stats = persistenceStore.loadLaunchStats()
        val apps = appDiscovery.discoverInstalledApps(launchStats = stats, includeIcons = true)
        _installedApps.value = apps
        if (seedDockIfFirstRun && apps.isNotEmpty()) {
            updateState { cur ->
                if (cur.hasCompletedInitialSeed || cur.dockPackages.isNotEmpty()) {
                    cur.copy(hasCompletedInitialSeed = true)
                } else {
                    val prioritized = listOf(
                        AppCategory.COMMUNICATION,
                        AppCategory.BROWSER,
                        AppCategory.MEDIA,
                        AppCategory.TOOLS
                    ).mapNotNull { cat ->
                        apps.firstOrNull { it.category == cat && it.packageName != appContext.packageName }?.packageName
                    }.distinct().toMutableList()
                    apps.filter { it.packageName != appContext.packageName }
                        .forEach { app ->
                            if (prioritized.size < 4 && app.packageName !in prioritized) {
                                prioritized.add(app.packageName)
                            }
                        }
                    val homeSampleApps = apps.filter {
                        it.packageName != appContext.packageName && it.packageName !in prioritized
                    }.take(4).map { app ->
                        HomeSlotItem.AppItem(
                            id = "app_${app.packageName}",
                            pageIndex = 0,
                            packageName = app.packageName,
                            label = app.label
                        )
                    }
                    cur.copy(
                        dockPackages = prioritized.take(5),
                        items = cur.items + homeSampleApps,
                        hasCompletedInitialSeed = true
                    )
                }
            }
        }
    }

    fun launchApp(app: DiscoveredApp): Result<String> {
        val res = appDiscovery.launchApp(app.packageName, app.activityName)
        if (res.isSuccess) {
            engineScope.launch {
                val updatedStats = persistenceStore.recordAppLaunch(app.packageName)
                _installedApps.value = _installedApps.value.map { item ->
                    if (item.packageName == app.packageName) {
                        val st = updatedStats[app.packageName]
                        item.copy(
                            launchCount = st?.first ?: (item.launchCount + 1),
                            lastLaunchedMs = st?.second ?: System.currentTimeMillis()
                        )
                    } else item
                }
            }
        } else {
            _statusBanner.value = res.exceptionOrNull()?.localizedMessage ?: "Failed to open ${app.label}"
        }
        return res
    }

    fun launchPackageOrQuery(packageName: String): Result<String> {
        val match = _installedApps.value.firstOrNull { it.packageName == packageName }
        if (match != null) return launchApp(match)
        val res = appDiscovery.launchApp(packageName)
        if (res.isSuccess) {
            engineScope.launch { persistenceStore.recordAppLaunch(packageName) }
        }
        return res
    }

    fun openDeviceCamera(): Result<String> = runCatching {
        val cameraApp = _installedApps.value.firstOrNull {
            it.packageName.contains("camera", ignoreCase = true) ||
                it.label.equals("Camera", ignoreCase = true)
        }
        if (cameraApp != null) {
            return launchApp(cameraApp)
        }
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
        "Opened device Camera."
    }

    suspend fun refreshWeather(cityOverride: String? = null, useLocationIfPermitted: Boolean = false) {
        val city = (cityOverride ?: _state.value.weatherCityQuery).ifBlank { "San Francisco" }
        _weatherError.value = null
        wallpaperEngine // keeps city state
        widgetManager.fetchLiveWeather(city, useLocationIfPermitted)
            .onSuccess { snap ->
                _weather.value = snap
                persistenceStore.saveCachedWeather(snap)
                if (!cityOverride.isNullOrBlank()) {
                    updateState { it.copy(weatherCityQuery = cityOverride.trim()) }
                }
            }
            .onFailure { err ->
                _weatherError.value = err.localizedMessage ?: "Weather unavailable offline"
            }
    }

    suspend fun refreshCalendarEvents() {
        _calendarEvents.value = widgetManager.queryUpcomingCalendarEvents()
    }

    fun refreshThermalAndPerf() {
        _thermalSnapshot.value = readGamingThermalSnapshot()
    }

    private fun readGamingThermalSnapshot(): GamingThermalAndPerfSnapshot {
        val stats = deviceController.getStorageAndMemoryStats()
        val batteryIntent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val st = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL

        val thermalLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                when (pm?.currentThermalStatus) {
                    PowerManager.THERMAL_STATUS_NONE -> "Cool (Nominal)"
                    PowerManager.THERMAL_STATUS_LIGHT -> "Warm (Light)"
                    PowerManager.THERMAL_STATUS_MODERATE -> "Moderate Load"
                    PowerManager.THERMAL_STATUS_SEVERE -> "Hot (Throttling Risk)"
                    PowerManager.THERMAL_STATUS_CRITICAL,
                    PowerManager.THERMAL_STATUS_EMERGENCY,
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> "Critical Thermal"
                    else -> "Normal"
                }
            }.getOrDefault("Normal")
        } else {
            "Normal (API < 29)"
        }
        return GamingThermalAndPerfSnapshot(
            ramAvailableMb = stats.ramAvailableMb,
            ramTotalMb = stats.ramTotalMb,
            batteryPercent = pct,
            isCharging = charging,
            thermalStatusLabel = thermalLabel,
            lowMemory = stats.lowMemory
        )
    }

    suspend fun updateState(transform: (LauncherState) -> LauncherState) {
        val next = stateMutex.withLock {
            val updated = transform(_state.value)
            _state.value = updated
            persistenceStore.saveState(updated)
            updated
        }
        if (next.cloudSyncEnabled) {
            runCatching { cloudPushCallback?.invoke(next.toCloudSyncJson()) }
        }
    }

    fun applyThemePreset(preset: LauncherThemePreset) {
        engineScope.launch {
            updateState { cur ->
                cur.copy(theme = LauncherThemeEngine.applyPreset(cur.theme, preset))
            }
            _statusBanner.value = "Applied ${preset.displayName} theme."
        }
    }

    fun updateThemeConfig(transform: (LauncherThemeConfig) -> LauncherThemeConfig) {
        engineScope.launch {
            updateState { cur -> cur.copy(theme = transform(cur.theme)) }
        }
    }

    fun updateWallpaperConfig(transform: (WallpaperConfig) -> WallpaperConfig) {
        engineScope.launch {
            updateState { cur -> cur.copy(wallpaper = transform(cur.wallpaper)) }
        }
    }

    suspend fun applySystemWallpaperFromCurrent(): Result<String> {
        return wallpaperEngine.applySystemStaticWallpaper(_state.value.wallpaper)
            .onSuccess { _statusBanner.value = it }
            .onFailure { _statusBanner.value = it.localizedMessage }
    }

    suspend fun searchMapWallpaperLocations(query: String): Result<List<MapGeocodeResult>> {
        return wallpaperEngine.searchLocation(query)
    }

    fun updateGesture(trigger: GestureTrigger, action: GestureActionType) {
        engineScope.launch {
            updateState { cur ->
                val g = cur.gestures
                val nextG = when (trigger) {
                    GestureTrigger.SWIPE_UP -> g.copy(swipeUpAction = action.id)
                    GestureTrigger.SWIPE_DOWN -> g.copy(swipeDownAction = action.id)
                    GestureTrigger.DOUBLE_TAP -> g.copy(doubleTapAction = action.id)
                    GestureTrigger.LONG_PRESS -> g.copy(longPressAction = action.id)
                    GestureTrigger.TWO_FINGER_SWIPE -> g.copy(twoFingerSwipeAction = action.id)
                    GestureTrigger.CUSTOM_EDGE -> g.copy(customEdgeAction = action.id)
                }
                cur.copy(gestures = nextG)
            }
        }
    }

    fun addAppToHome(app: DiscoveredApp, pageIndex: Int = 0) {
        engineScope.launch {
            updateState { cur ->
                val targetPage = pageIndex.coerceIn(0, (cur.pageCount - 1).coerceAtLeast(0))
                val existsOnPage = cur.items.any {
                    it is HomeSlotItem.AppItem && it.pageIndex == targetPage && it.packageName == app.packageName
                }
                if (existsOnPage) return@updateState cur
                val slot = HomeSlotItem.AppItem(
                    id = "app_${app.packageName}_${UUID.randomUUID().toString().take(6)}",
                    pageIndex = targetPage,
                    packageName = app.packageName,
                    label = app.label
                )
                cur.copy(items = cur.items + slot)
            }
            _statusBanner.value = "Added ${app.label} to Home page ${pageIndex + 1}."
        }
    }

    fun removeHomeSlot(slotId: String) {
        engineScope.launch {
            updateState { cur ->
                val removed = cur.items.firstOrNull { it.id == slotId }
                if (removed is HomeSlotItem.WidgetItem && removed.systemAppWidgetId != null) {
                    widgetManager.deleteWidgetId(removed.systemAppWidgetId)
                }
                cur.copy(items = cur.items.filterNot { it.id == slotId })
            }
        }
    }

    fun moveHomeSlot(slotId: String, delta: Int) {
        engineScope.launch {
            updateState { cur ->
                val list = cur.items.toMutableList()
                val idx = list.indexOfFirst { it.id == slotId }
                if (idx < 0) return@updateState cur
                val targetIdx = (idx + delta).coerceIn(0, list.lastIndex)
                if (idx == targetIdx) return@updateState cur
                val moved = list.removeAt(idx)
                list.add(targetIdx, moved)
                cur.copy(items = list)
            }
        }
    }

    fun moveHomeSlotToPage(slotId: String, targetPage: Int) {
        engineScope.launch {
            updateState { cur ->
                val clamped = targetPage.coerceIn(0, (cur.pageCount - 1).coerceAtLeast(0))
                val nextItems = cur.items.map { item ->
                    if (item.id == slotId) {
                        when (item) {
                            is HomeSlotItem.AppItem -> item.copy(pageIndex = clamped)
                            is HomeSlotItem.FolderItem -> item.copy(pageIndex = clamped)
                            is HomeSlotItem.WidgetItem -> item.copy(pageIndex = clamped)
                        }
                    } else item
                }
                cur.copy(items = nextItems)
            }
        }
    }

    fun createHomeFolder(name: String, appPackages: List<String>, pageIndex: Int = 0) {
        if (name.isBlank()) return
        engineScope.launch {
            updateState { cur ->
                val folder = HomeSlotItem.FolderItem(
                    id = "folder_${UUID.randomUUID().toString().take(8)}",
                    pageIndex = pageIndex.coerceIn(0, (cur.pageCount - 1).coerceAtLeast(0)),
                    name = name.trim().take(32),
                    appPackages = appPackages.distinct()
                )
                cur.copy(items = cur.items + folder)
            }
            _statusBanner.value = "Created folder '${name.trim()}'."
        }
    }

    fun renameHomeFolder(folderId: String, newName: String) {
        if (newName.isBlank()) return
        engineScope.launch {
            updateState { cur ->
                cur.copy(
                    items = cur.items.map { item ->
                        if (item is HomeSlotItem.FolderItem && item.id == folderId) {
                            item.copy(name = newName.trim().take(32))
                        } else item
                    }
                )
            }
        }
    }

    fun addAppToFolder(folderId: String, packageName: String) {
        engineScope.launch {
            updateState { cur ->
                cur.copy(
                    items = cur.items.map { item ->
                        if (item is HomeSlotItem.FolderItem && item.id == folderId) {
                            item.copy(appPackages = (item.appPackages + packageName).distinct())
                        } else item
                    }
                )
            }
        }
    }

    fun addWidgetToHome(widgetType: LauncherWidgetType, pageIndex: Int = 0, systemWidgetId: Int? = null, customTitle: String = "") {
        engineScope.launch {
            updateState { cur ->
                val widget = HomeSlotItem.WidgetItem(
                    id = "widget_${widgetType.id}_${UUID.randomUUID().toString().take(6)}",
                    pageIndex = pageIndex.coerceIn(0, (cur.pageCount - 1).coerceAtLeast(0)),
                    widgetTypeId = widgetType.id,
                    colSpan = widgetType.defaultColSpan,
                    rowSpan = widgetType.defaultRowSpan,
                    systemAppWidgetId = systemWidgetId,
                    customTitle = customTitle
                )
                cur.copy(items = cur.items + widget)
            }
            _statusBanner.value = "Added ${widgetType.title} widget."
        }
    }

    fun resizeWidget(widgetId: String, newColSpan: Int, newRowSpan: Int) {
        engineScope.launch {
            updateState { cur ->
                cur.copy(
                    items = cur.items.map { item ->
                        if (item is HomeSlotItem.WidgetItem && item.id == widgetId) {
                            item.copy(
                                colSpan = newColSpan.coerceIn(1, cur.theme.gridColumns),
                                rowSpan = newRowSpan.coerceIn(1, 4)
                            )
                        } else item
                    }
                )
            }
        }
    }

    fun toggleDockApp(packageName: String) {
        engineScope.launch {
            updateState { cur ->
                val next = if (packageName in cur.dockPackages) {
                    cur.dockPackages - packageName
                } else {
                    (cur.dockPackages + packageName).take(5)
                }
                cur.copy(dockPackages = next)
            }
        }
    }

    fun toggleFavoriteApp(packageName: String) {
        engineScope.launch {
            updateState { cur ->
                val next = if (packageName in cur.favoritePackages) {
                    cur.favoritePackages - packageName
                } else {
                    cur.favoritePackages + packageName
                }
                cur.copy(favoritePackages = next)
            }
        }
    }

    fun toggleHiddenApp(packageName: String) {
        engineScope.launch {
            updateState { cur ->
                val next = if (packageName in cur.hiddenPackages) {
                    cur.hiddenPackages - packageName
                } else {
                    cur.hiddenPackages + packageName
                }
                cur.copy(hiddenPackages = next)
            }
        }
    }

    fun addOrRemoveHomePage(add: Boolean) {
        engineScope.launch {
            updateState { cur ->
                if (add) {
                    cur.copy(pageCount = (cur.pageCount + 1).coerceAtMost(6))
                } else {
                    val newCount = (cur.pageCount - 1).coerceAtLeast(1)
                    val migrated = cur.items.map { item ->
                        if (item.pageIndex >= newCount) {
                            when (item) {
                                is HomeSlotItem.AppItem -> item.copy(pageIndex = newCount - 1)
                                is HomeSlotItem.FolderItem -> item.copy(pageIndex = newCount - 1)
                                is HomeSlotItem.WidgetItem -> item.copy(pageIndex = newCount - 1)
                            }
                        } else item
                    }
                    cur.copy(
                        pageCount = newCount,
                        preferredHomePage = cur.preferredHomePage.coerceIn(0, newCount - 1),
                        items = migrated
                    )
                }
            }
        }
    }

    fun setPreferredHomePage(index: Int) {
        engineScope.launch {
            updateState { cur ->
                cur.copy(preferredHomePage = index.coerceIn(0, (cur.pageCount - 1).coerceAtLeast(0)))
            }
        }
    }

    fun setDrawerSortMode(mode: DrawerSortMode) {
        engineScope.launch { updateState { it.copy(drawerSortMode = mode) } }
    }

    fun setQuickNote(noteText: String) {
        engineScope.launch { updateState { it.copy(quickNoteText = noteText.take(2000)) } }
    }

    fun toggleGamingMode(enabled: Boolean) {
        engineScope.launch {
            updateState { cur ->
                val nextTheme = if (enabled) {
                    LauncherThemeEngine.applyPreset(cur.theme, LauncherThemePreset.GAMING)
                } else {
                    cur.theme
                }
                val nextWall = if (enabled && cur.wallpaper.typeId != WallpaperType.GAMING_CYBER.id) {
                    cur.wallpaper.copy(
                        typeId = WallpaperType.GAMING_CYBER.id,
                        label = "Gaming Cyber Matrix",
                        accentHex = "#00FF9C"
                    )
                } else cur.wallpaper
                cur.copy(
                    gamingModeActive = enabled,
                    theme = nextTheme,
                    wallpaper = nextWall
                )
            }
            refreshThermalAndPerf()
        }
    }

    fun toggleAppGameTag(packageName: String) {
        engineScope.launch {
            updateState { cur ->
                val app = _installedApps.value.firstOrNull { it.packageName == packageName }
                val isCurrentlyGame = (app?.isGame == true && packageName !in cur.gamePackagesExcluded) ||
                    (packageName in cur.gamePackagesAddedByUser)
                if (isCurrentlyGame) {
                    cur.copy(
                        gamePackagesAddedByUser = cur.gamePackagesAddedByUser - packageName,
                        gamePackagesExcluded = cur.gamePackagesExcluded + packageName
                    )
                } else {
                    cur.copy(
                        gamePackagesAddedByUser = cur.gamePackagesAddedByUser + packageName,
                        gamePackagesExcluded = cur.gamePackagesExcluded - packageName
                    )
                }
            }
        }
    }

    fun createGameFolder(name: String, appPackages: List<String>) {
        if (name.isBlank()) return
        engineScope.launch {
            updateState { cur ->
                val folder = DrawerFolder(
                    id = "gfolder_${UUID.randomUUID().toString().take(8)}",
                    name = name.trim().take(28),
                    appPackages = appPackages.distinct()
                )
                cur.copy(gameFolders = cur.gameFolders + folder)
            }
        }
    }

    fun createCustomCategory(categoryName: String, appPackages: List<String>) {
        if (categoryName.isBlank()) return
        engineScope.launch {
            updateState { cur ->
                val map = cur.customCategories.toMutableMap()
                map[categoryName.trim().take(28)] = appPackages.distinct()
                cur.copy(customCategories = map)
            }
        }
    }

    fun getGameApps(state: LauncherState = _state.value, apps: List<DiscoveredApp> = _installedApps.value): List<DiscoveredApp> {
        return apps.filter { app ->
            (app.isGame && app.packageName !in state.gamePackagesExcluded) ||
                (app.packageName in state.gamePackagesAddedByUser)
        }
    }

    fun getRecentApps(limit: Int = 8): List<DiscoveredApp> =
        _installedApps.value
            .filter { it.lastLaunchedMs > 0L }
            .sortedByDescending { it.lastLaunchedMs }
            .take(limit)

    fun getFrequentApps(limit: Int = 8): List<DiscoveredApp> =
        _installedApps.value
            .filter { it.launchCount > 0 }
            .sortedByDescending { it.launchCount }
            .take(limit)

    fun readNotificationPreviews(): List<CapturedNotification> {
        return deviceController.readRecentNotifications()
    }

    suspend fun searchUniversal(
        query: String,
        conversations: List<Conversation> = emptyList(),
        memories: List<MemoryItem> = emptyList(),
        browserTabs: List<BrowsingTab> = emptyList()
    ): List<UniversalSearchHit> {
        return searchEngine.searchAll(
            query = query,
            apps = _installedApps.value,
            conversations = conversations,
            memories = memories,
            browserTabs = browserTabs
        )
    }

    suspend fun applySyncedCloudPreferences(cloudJson: String, syncedAtMs: Long) {
        stateMutex.withLock {
            val updated = _state.value.applyCloudSyncJson(cloudJson).copy(lastCloudSyncMs = syncedAtMs)
            _state.value = updated
            persistenceStore.saveState(updated)
        }
    }

    private fun shutdown() {
        appDiscovery.unregisterReceiverSafely(packageReceiver)
        packageReceiver = null
        engineScope.cancel()
    }

    companion object {
        @Volatile
        private var INSTANCE: AquaLauncherEngine? = null

        fun getInstance(context: Context): AquaLauncherEngine {
            val appCtx = context.applicationContext
            val existing = INSTANCE
            if (existing != null && existing.appContext === appCtx) {
                return existing
            }
            return synchronized(this) {
                val cur = INSTANCE
                if (cur != null && cur.appContext === appCtx) {
                    cur
                } else {
                    cur?.shutdown()
                    AquaLauncherEngine(appCtx).also { INSTANCE = it }
                }
            }
        }
    }
}
