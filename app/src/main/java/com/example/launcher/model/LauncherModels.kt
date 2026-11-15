package com.example.launcher.model

import androidx.compose.ui.graphics.ImageBitmap
import org.json.JSONArray
import org.json.JSONObject

enum class AppCategory(val label: String) {
    COMMUNICATION("Communication"),
    SOCIAL("Social"),
    MEDIA("Media & Video"),
    PRODUCTIVITY("Productivity"),
    GAMES("Games"),
    FINANCE("Finance & Crypto"),
    TOOLS("Tools & System"),
    BROWSER("Web & Info"),
    OTHER("Apps")
}

enum class DrawerSortMode(val label: String) {
    ALPHABETICAL("A–Z"),
    RECENT("Recent"),
    FREQUENT("Frequent"),
    CATEGORY("Categories"),
    FAVORITES("Favorites")
}

enum class LauncherThemePreset(
    val id: String,
    val displayName: String,
    val primaryHex: String,
    val secondaryHex: String,
    val surfaceHex: String,
    val bgAlpha: Float,
    val widgetAlpha: Float,
    val cornerRadiusDp: Int
) {
    AQUA_BLUE("aqua_blue", "Aqua Blue", "#00E5FF", "#2979FF", "#082036", 0.78f, 0.72f, 20),
    MIDNIGHT_DARK("midnight_dark", "Midnight Dark", "#38BDF8", "#0F172A", "#050A14", 0.88f, 0.82f, 18),
    GLASSMORPHISM("glassmorphism", "Glassmorphism", "#5EEAD4", "#38BDF8", "#0D2B45", 0.45f, 0.40f, 24),
    MINIMAL("minimal", "Minimal", "#E2F1FF", "#64748B", "#0C1824", 0.65f, 0.55f, 12),
    GAMING("gaming", "Gaming Neon", "#00FF9C", "#FF2E93", "#090B1A", 0.85f, 0.78f, 14),
    PREMIUM("premium", "Premium Sapphire", "#FBBF24", "#00E5FF", "#0A192F", 0.82f, 0.76f, 22),
    CUSTOM("custom", "Custom Theme", "#00E5FF", "#1DE9B6", "#0B243E", 0.76f, 0.70f, 20);

    companion object {
        fun fromId(id: String): LauncherThemePreset =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AQUA_BLUE
    }
}

enum class LauncherFontOption(val id: String, val label: String) {
    SPACE_GROTESK("space_grotesk", "Space Grotesk"),
    INTER("inter", "Inter Clean"),
    JETBRAINS_MONO("jetbrains_mono", "JetBrains Mono"),
    SYSTEM_DEFAULT("system", "System Sans");

    companion object {
        fun fromId(id: String): LauncherFontOption =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: SPACE_GROTESK
    }
}

enum class LauncherIconShape(val id: String, val label: String) {
    SQUIRCLE("squircle", "Squircle"),
    ROUNDED_SQUARE("rounded", "Rounded"),
    CIRCLE("circle", "Circle"),
    TEARDROP("teardrop", "Droplet");

    companion object {
        fun fromId(id: String): LauncherIconShape =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: SQUIRCLE
    }
}

enum class LauncherIconPackStyle(val id: String, val label: String) {
    SYSTEM_NATIVE("native", "System Adaptive"),
    AQUA_GLOW("aqua_glow", "Aqua Luminous Ring"),
    MONOCHROME_ICE("monochrome_ice", "Monochrome Cyan"),
    CYBER_FRAME("cyber_frame", "Cyber Frame");

    companion object {
        fun fromId(id: String): LauncherIconPackStyle =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AQUA_GLOW
    }
}

data class LauncherThemeConfig(
    val presetId: String = LauncherThemePreset.AQUA_BLUE.id,
    val primaryColorHex: String = LauncherThemePreset.AQUA_BLUE.primaryHex,
    val secondaryColorHex: String = LauncherThemePreset.AQUA_BLUE.secondaryHex,
    val backgroundAlpha: Float = 0.78f,
    val widgetAlpha: Float = 0.72f,
    val dockAlpha: Float = 0.80f,
    val iconShape: String = LauncherIconShape.SQUIRCLE.id,
    val iconPackStyle: String = LauncherIconPackStyle.AQUA_GLOW.id,
    val iconSizeDp: Int = 54,
    val cornerRadiusDp: Int = 20,
    val fontOption: String = LauncherFontOption.SPACE_GROTESK.id,
    val textScale: Float = 1.0f,
    val animationSpeed: Float = 1.0f,
    val gridColumns: Int = 4,
    val gridRows: Int = 5,
    val showDockLabels: Boolean = false,
    val showSearchBar: Boolean = true,
    val showIconLabels: Boolean = true,
    val use24HourClock: Boolean = false
)

enum class WallpaperType(val id: String, val label: String) {
    ANIMATED_AURORA("animated_aurora", "Animated Aqua Aurora"),
    DYNAMIC_GRADIENT("dynamic_gradient", "Dynamic Gradient"),
    LIVE_MAP("live_map", "Aqua Live Map Wallpaper"),
    AI_GENERATIVE("ai_generative", "AI Generative Art"),
    CUSTOM_PHOTO("custom_photo", "Custom Photo"),
    CUSTOM_VIDEO("custom_video", "Custom Video Frame"),
    GAMING_CYBER("gaming_cyber", "Gaming Cyber Matrix"),
    STATIC_OCEAN("static_ocean", "Deep Ocean Static");

    companion object {
        fun fromId(id: String): WallpaperType =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: ANIMATED_AURORA
    }
}

enum class MapWallpaperStyle(val id: String, val label: String, val tileSubdomainUrl: String?) {
    AQUA_NIGHT("aqua_night", "Aqua Night Carto", "https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png"),
    SATELLITE_HYBRID("satellite", "Satellite Earth", "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"),
    STANDARD_OSM("osm_voyager", "Voyager Atlas", "https://a.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png"),
    OFFLINE_VECTOR("offline_vector", "Offline Vector Grid", null);

    companion object {
        fun fromId(id: String): MapWallpaperStyle =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AQUA_NIGHT
    }
}

data class WallpaperConfig(
    val typeId: String = WallpaperType.ANIMATED_AURORA.id,
    val label: String = "Deep Aqua Aurora",
    val gradientStartHex: String = "#03101E",
    val gradientMidHex: String = "#072642",
    val gradientEndHex: String = "#004D66",
    val accentHex: String = "#00E5FF",
    val customMediaUri: String = "",
    val mapLatitude: Double = 37.7749,
    val mapLongitude: Double = -122.4194,
    val mapLocationName: String = "San Francisco Bay",
    val mapZoom: Int = 12,
    val mapStyleId: String = MapWallpaperStyle.AQUA_NIGHT.id,
    val mapAnimatePan: Boolean = true,
    val mapUseDeviceLocation: Boolean = false,
    val aiPromptSeed: String = "Bioluminescent deep ocean neural reef",
    val targetScreen: String = "BOTH"
)

enum class GestureTrigger(val id: String, val label: String) {
    SWIPE_UP("swipe_up", "Swipe Up"),
    SWIPE_DOWN("swipe_down", "Swipe Down"),
    DOUBLE_TAP("double_tap", "Double Tap Empty Area"),
    LONG_PRESS("long_press", "Long Press Home"),
    TWO_FINGER_SWIPE("two_finger_swipe", "Two-Finger Swipe"),
    CUSTOM_EDGE("custom_edge", "Quick Edge Gesture");

    companion object {
        fun fromId(id: String): GestureTrigger =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: SWIPE_UP
    }
}

enum class GestureActionType(val id: String, val label: String) {
    OPEN_APP_DRAWER("open_drawer", "Open App Drawer"),
    OPEN_UNIVERSAL_SEARCH("open_search", "Open Aqua Universal Search"),
    EXPAND_NOTIFICATIONS("expand_notifications", "Open Quick Notifications & Status"),
    ENTER_EDIT_MODE("edit_mode", "Enter Home Layout Editor"),
    OPEN_LAUNCHER_SETTINGS("open_settings", "Open Launcher Customization"),
    OPEN_AQUA_AI("open_aqua_ai", "Open Aqua AI Control"),
    OPEN_GAMING_HUB("open_gaming", "Open Gaming Launcher"),
    LOCK_SCREEN_INFO("lock_screen_info", "Lock Screen (Guided Android Support)"),
    TOGGLE_FLASHLIGHT("toggle_flashlight", "Toggle Camera Flashlight"),
    OPEN_CAMERA("open_camera", "Open Device Camera"),
    NONE("none", "Disabled");

    companion object {
        fun fromId(id: String): GestureActionType =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: OPEN_APP_DRAWER
    }
}

data class LauncherGestureConfig(
    val swipeUpAction: String = GestureActionType.OPEN_APP_DRAWER.id,
    val swipeDownAction: String = GestureActionType.OPEN_UNIVERSAL_SEARCH.id,
    val doubleTapAction: String = GestureActionType.LOCK_SCREEN_INFO.id,
    val longPressAction: String = GestureActionType.ENTER_EDIT_MODE.id,
    val twoFingerSwipeAction: String = GestureActionType.OPEN_LAUNCHER_SETTINGS.id,
    val customEdgeAction: String = GestureActionType.OPEN_AQUA_AI.id
) {
    fun actionFor(trigger: GestureTrigger): GestureActionType = when (trigger) {
        GestureTrigger.SWIPE_UP -> GestureActionType.fromId(swipeUpAction)
        GestureTrigger.SWIPE_DOWN -> GestureActionType.fromId(swipeDownAction)
        GestureTrigger.DOUBLE_TAP -> GestureActionType.fromId(doubleTapAction)
        GestureTrigger.LONG_PRESS -> GestureActionType.fromId(longPressAction)
        GestureTrigger.TWO_FINGER_SWIPE -> GestureActionType.fromId(twoFingerSwipeAction)
        GestureTrigger.CUSTOM_EDGE -> GestureActionType.fromId(customEdgeAction)
    }
}

enum class LauncherWidgetType(
    val id: String,
    val title: String,
    val description: String,
    val defaultColSpan: Int,
    val defaultRowSpan: Int
) {
    AQUA_AI("aqua_ai", "Aqua AI Assistant", "Quick voice, natural language commands & status", 4, 1),
    CLOCK_DATE("clock_date", "Clock & Date", "Minimal futuristic digital clock and calendar day", 4, 1),
    WEATHER("weather", "Live Weather", "Real Open-Meteo conditions & forecast for your city", 2, 1),
    CALENDAR("calendar", "Calendar Agenda", "Upcoming events when Calendar permission is granted", 2, 1),
    BATTERY_DEVICE("battery_device", "Battery & Device", "Live charge %, temperature & health telemetry", 2, 1),
    STORAGE_RAM("storage_ram", "Storage & RAM", "Real internal storage and memory availability", 2, 1),
    NETWORK_STATUS("network_status", "Network Status", "Live Wi-Fi, Cellular and connectivity status", 2, 1),
    MUSIC_CONTROLS("music_controls", "Music Controls", "Media playback keys and volume control", 4, 1),
    FAVORITE_APPS("favorite_apps", "Favorite Apps", "Quick launch strip for starred applications", 4, 1),
    UNIVERSAL_SEARCH("universal_search", "Aqua Search", "Search apps, contacts, files, conversations & web", 4, 1),
    NOTES("notes", "Quick Notes", "Pinned notes synced locally & ready for Aqua Memory", 2, 1),
    SYSTEM_APP_WIDGET("system_widget", "Android System Widget", "Hosted external Android AppWidgetHost view", 4, 2);

    companion object {
        fun fromId(id: String): LauncherWidgetType =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: CLOCK_DATE
    }
}

sealed class HomeSlotItem {
    abstract val id: String
    abstract val pageIndex: Int

    data class AppItem(
        override val id: String,
        override val pageIndex: Int,
        val packageName: String,
        val label: String
    ) : HomeSlotItem()

    data class FolderItem(
        override val id: String,
        override val pageIndex: Int,
        val name: String,
        val appPackages: List<String>
    ) : HomeSlotItem()

    data class WidgetItem(
        override val id: String,
        override val pageIndex: Int,
        val widgetTypeId: String,
        val colSpan: Int = 4,
        val rowSpan: Int = 1,
        val systemAppWidgetId: Int? = null,
        val customTitle: String = ""
    ) : HomeSlotItem()
}

data class DrawerFolder(
    val id: String,
    val name: String,
    val appPackages: List<String>
)

data class AppShortcutInfo(
    val id: String,
    val packageName: String,
    val shortLabel: String,
    val longLabel: String
)

data class DiscoveredApp(
    val packageName: String,
    val activityName: String,
    val label: String,
    val category: AppCategory,
    val isSystemApp: Boolean,
    val isGame: Boolean,
    val launchCount: Int = 0,
    val lastLaunchedMs: Long = 0L,
    val iconBitmap: ImageBitmap? = null,
    val shortcuts: List<AppShortcutInfo> = emptyList()
)

data class WeatherSnapshot(
    val locationName: String,
    val temperatureC: Double,
    val apparentTempC: Double,
    val windKmh: Double,
    val humidityPercent: Int,
    val conditionLabel: String,
    val weatherCode: Int,
    val isDay: Boolean,
    val fetchedAtMs: Long
)

data class DeviceCalendarEventItem(
    val id: Long,
    val title: String,
    val startMs: Long,
    val location: String
)

data class DeviceContactResult(
    val name: String,
    val phoneNumber: String
)

enum class SearchResultCategory(val label: String) {
    APP("Installed Apps"),
    LAUNCHER_SETTING("Launcher Controls"),
    CONTACT("Contacts"),
    CALENDAR("Calendar"),
    LOCAL_FILE("Files & Downloads"),
    CONVERSATION("Aqua AI History"),
    BROWSER_TAB("Browser Tabs & Bookmarks"),
    WEB("Web & Internet")
}

data class UniversalSearchHit(
    val id: String,
    val category: SearchResultCategory,
    val title: String,
    val subtitle: String,
    val payload: String
)

data class GamingThermalAndPerfSnapshot(
    val ramAvailableMb: Long,
    val ramTotalMb: Long,
    val batteryPercent: Int,
    val isCharging: Boolean,
    val thermalStatusLabel: String,
    val lowMemory: Boolean
)

data class LauncherState(
    val pageCount: Int = 2,
    val preferredHomePage: Int = 0,
    val items: List<HomeSlotItem> = defaultInitialItems(),
    val dockPackages: List<String> = emptyList(),
    val hiddenPackages: Set<String> = emptySet(),
    val favoritePackages: Set<String> = emptySet(),
    val customCategories: Map<String, List<String>> = emptyMap(),
    val drawerFolders: List<DrawerFolder> = emptyList(),
    val gamePackagesAddedByUser: Set<String> = emptySet(),
    val gamePackagesExcluded: Set<String> = emptySet(),
    val gameFolders: List<DrawerFolder> = emptyList(),
    val gamingModeActive: Boolean = false,
    val drawerSortMode: DrawerSortMode = DrawerSortMode.ALPHABETICAL,
    val quickNoteText: String = "",
    val weatherCityQuery: String = "San Francisco",
    val theme: LauncherThemeConfig = LauncherThemeConfig(),
    val wallpaper: WallpaperConfig = WallpaperConfig(),
    val gestures: LauncherGestureConfig = LauncherGestureConfig(),
    val cloudSyncEnabled: Boolean = true,
    val lastCloudSyncMs: Long = 0L,
    val hasCompletedInitialSeed: Boolean = false
) {
    companion object {
        fun defaultInitialItems(): List<HomeSlotItem> = listOf(
            HomeSlotItem.WidgetItem(
                id = "w_clock_0",
                pageIndex = 0,
                widgetTypeId = LauncherWidgetType.CLOCK_DATE.id,
                colSpan = 4,
                rowSpan = 1
            ),
            HomeSlotItem.WidgetItem(
                id = "w_ai_0",
                pageIndex = 0,
                widgetTypeId = LauncherWidgetType.AQUA_AI.id,
                colSpan = 4,
                rowSpan = 1
            ),
            HomeSlotItem.WidgetItem(
                id = "w_weather_0",
                pageIndex = 0,
                widgetTypeId = LauncherWidgetType.WEATHER.id,
                colSpan = 2,
                rowSpan = 1
            ),
            HomeSlotItem.WidgetItem(
                id = "w_battery_0",
                pageIndex = 0,
                widgetTypeId = LauncherWidgetType.BATTERY_DEVICE.id,
                colSpan = 2,
                rowSpan = 1
            ),
            HomeSlotItem.WidgetItem(
                id = "w_storage_1",
                pageIndex = 1,
                widgetTypeId = LauncherWidgetType.STORAGE_RAM.id,
                colSpan = 2,
                rowSpan = 1
            ),
            HomeSlotItem.WidgetItem(
                id = "w_network_1",
                pageIndex = 1,
                widgetTypeId = LauncherWidgetType.NETWORK_STATUS.id,
                colSpan = 2,
                rowSpan = 1
            ),
            HomeSlotItem.WidgetItem(
                id = "w_notes_1",
                pageIndex = 1,
                widgetTypeId = LauncherWidgetType.NOTES.id,
                colSpan = 4,
                rowSpan = 1
            )
        )
    }

    fun toCloudSyncJson(): String {
        val obj = JSONObject()
        obj.put("themePreset", theme.presetId)
        obj.put("primaryColorHex", theme.primaryColorHex)
        obj.put("secondaryColorHex", theme.secondaryColorHex)
        obj.put("backgroundAlpha", theme.backgroundAlpha.toDouble())
        obj.put("widgetAlpha", theme.widgetAlpha.toDouble())
        obj.put("dockAlpha", theme.dockAlpha.toDouble())
        obj.put("iconShape", theme.iconShape)
        obj.put("iconPackStyle", theme.iconPackStyle)
        obj.put("iconSizeDp", theme.iconSizeDp)
        obj.put("cornerRadiusDp", theme.cornerRadiusDp)
        obj.put("fontOption", theme.fontOption)
        obj.put("textScale", theme.textScale.toDouble())
        obj.put("animationSpeed", theme.animationSpeed.toDouble())
        obj.put("gridColumns", theme.gridColumns)
        obj.put("gridRows", theme.gridRows)
        obj.put("showDockLabels", theme.showDockLabels)
        obj.put("showSearchBar", theme.showSearchBar)
        obj.put("showIconLabels", theme.showIconLabels)
        obj.put("use24HourClock", theme.use24HourClock)

        obj.put("wallpaperType", wallpaper.typeId)
        obj.put("wallpaperLabel", wallpaper.label)
        obj.put("gradientStartHex", wallpaper.gradientStartHex)
        obj.put("gradientMidHex", wallpaper.gradientMidHex)
        obj.put("gradientEndHex", wallpaper.gradientEndHex)
        obj.put("accentHex", wallpaper.accentHex)
        obj.put("mapStyleId", wallpaper.mapStyleId)
        obj.put("mapZoom", wallpaper.mapZoom)
        obj.put("mapAnimatePan", wallpaper.mapAnimatePan)

        obj.put("swipeUpAction", gestures.swipeUpAction)
        obj.put("swipeDownAction", gestures.swipeDownAction)
        obj.put("doubleTapAction", gestures.doubleTapAction)
        obj.put("longPressAction", gestures.longPressAction)
        obj.put("twoFingerSwipeAction", gestures.twoFingerSwipeAction)
        obj.put("customEdgeAction", gestures.customEdgeAction)

        obj.put("pageCount", pageCount)
        obj.put("preferredHomePage", preferredHomePage)
        obj.put("drawerSortMode", drawerSortMode.name)
        obj.put("gamingModeActive", gamingModeActive)
        // Note: Intentionally excludes installed app package names, contacts, and GPS coordinates for user privacy.
        return obj.toString()
    }

    fun applyCloudSyncJson(json: String): LauncherState {
        if (json.isBlank()) return this
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return this
        val updatedTheme = theme.copy(
            presetId = obj.optString("themePreset", theme.presetId),
            primaryColorHex = obj.optString("primaryColorHex", theme.primaryColorHex),
            secondaryColorHex = obj.optString("secondaryColorHex", theme.secondaryColorHex),
            backgroundAlpha = obj.optDouble("backgroundAlpha", theme.backgroundAlpha.toDouble()).toFloat().coerceIn(0.15f, 1.0f),
            widgetAlpha = obj.optDouble("widgetAlpha", theme.widgetAlpha.toDouble()).toFloat().coerceIn(0.15f, 1.0f),
            dockAlpha = obj.optDouble("dockAlpha", theme.dockAlpha.toDouble()).toFloat().coerceIn(0.15f, 1.0f),
            iconShape = obj.optString("iconShape", theme.iconShape),
            iconPackStyle = obj.optString("iconPackStyle", theme.iconPackStyle),
            iconSizeDp = obj.optInt("iconSizeDp", theme.iconSizeDp).coerceIn(40, 72),
            cornerRadiusDp = obj.optInt("cornerRadiusDp", theme.cornerRadiusDp).coerceIn(4, 32),
            fontOption = obj.optString("fontOption", theme.fontOption),
            textScale = obj.optDouble("textScale", theme.textScale.toDouble()).toFloat().coerceIn(0.8f, 1.4f),
            animationSpeed = obj.optDouble("animationSpeed", theme.animationSpeed.toDouble()).toFloat().coerceIn(0.4f, 2.0f),
            gridColumns = obj.optInt("gridColumns", theme.gridColumns).coerceIn(3, 6),
            gridRows = obj.optInt("gridRows", theme.gridRows).coerceIn(3, 7),
            showDockLabels = obj.optBoolean("showDockLabels", theme.showDockLabels),
            showSearchBar = obj.optBoolean("showSearchBar", theme.showSearchBar),
            showIconLabels = obj.optBoolean("showIconLabels", theme.showIconLabels),
            use24HourClock = obj.optBoolean("use24HourClock", theme.use24HourClock)
        )
        val updatedWallpaper = wallpaper.copy(
            typeId = obj.optString("wallpaperType", wallpaper.typeId),
            label = obj.optString("wallpaperLabel", wallpaper.label),
            gradientStartHex = obj.optString("gradientStartHex", wallpaper.gradientStartHex),
            gradientMidHex = obj.optString("gradientMidHex", wallpaper.gradientMidHex),
            gradientEndHex = obj.optString("gradientEndHex", wallpaper.gradientEndHex),
            accentHex = obj.optString("accentHex", wallpaper.accentHex),
            mapStyleId = obj.optString("mapStyleId", wallpaper.mapStyleId),
            mapZoom = obj.optInt("mapZoom", wallpaper.mapZoom).coerceIn(3, 17),
            mapAnimatePan = obj.optBoolean("mapAnimatePan", wallpaper.mapAnimatePan)
        )
        val updatedGestures = gestures.copy(
            swipeUpAction = obj.optString("swipeUpAction", gestures.swipeUpAction),
            swipeDownAction = obj.optString("swipeDownAction", gestures.swipeDownAction),
            doubleTapAction = obj.optString("doubleTapAction", gestures.doubleTapAction),
            longPressAction = obj.optString("longPressAction", gestures.longPressAction),
            twoFingerSwipeAction = obj.optString("twoFingerSwipeAction", gestures.twoFingerSwipeAction),
            customEdgeAction = obj.optString("customEdgeAction", gestures.customEdgeAction)
        )
        val parsedSort = runCatching {
            DrawerSortMode.valueOf(obj.optString("drawerSortMode", drawerSortMode.name))
        }.getOrDefault(drawerSortMode)
        return copy(
            theme = updatedTheme,
            wallpaper = updatedWallpaper,
            gestures = updatedGestures,
            pageCount = obj.optInt("pageCount", pageCount).coerceIn(1, 6),
            preferredHomePage = obj.optInt("preferredHomePage", preferredHomePage).coerceIn(0, 5),
            drawerSortMode = parsedSort,
            gamingModeActive = obj.optBoolean("gamingModeActive", gamingModeActive)
        )
    }
}
