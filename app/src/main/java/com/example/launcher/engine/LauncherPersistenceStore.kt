package com.example.launcher.engine

import android.content.Context
import android.content.SharedPreferences
import com.example.launcher.model.DrawerFolder
import com.example.launcher.model.DrawerSortMode
import com.example.launcher.model.HomeSlotItem
import com.example.launcher.model.LauncherGestureConfig
import com.example.launcher.model.LauncherState
import com.example.launcher.model.LauncherThemeConfig
import com.example.launcher.model.WallpaperConfig
import com.example.launcher.model.WeatherSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class LauncherPersistenceStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val ioMutex = Mutex()

    suspend fun loadState(): LauncherState = withContext(Dispatchers.IO) {
        val raw = prefs.getString(KEY_LAUNCHER_STATE_JSON, null)
        if (raw.isNullOrBlank()) {
            LauncherState()
        } else {
            decodeState(raw)
        }
    }

    suspend fun saveState(state: LauncherState) = withContext(Dispatchers.IO) {
        val encoded = encodeState(state)
        ioMutex.withLock {
            prefs.edit().putString(KEY_LAUNCHER_STATE_JSON, encoded).commit()
        }
    }

    suspend fun loadLaunchStats(): Map<String, Pair<Int, Long>> = withContext(Dispatchers.IO) {
        val raw = prefs.getString(KEY_LAUNCH_STATS_JSON, null)
        if (raw.isNullOrBlank()) return@withContext emptyMap()
        runCatching {
            val obj = JSONObject(raw)
            val result = mutableMapOf<String, Pair<Int, Long>>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val pkg = keys.next()
                val item = obj.optJSONObject(pkg) ?: continue
                result[pkg] = item.optInt("count", 0) to item.optLong("lastMs", 0L)
            }
            result
        }.getOrDefault(emptyMap())
    }

    suspend fun recordAppLaunch(packageName: String, timestampMs: Long = System.currentTimeMillis()): Map<String, Pair<Int, Long>> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val raw = prefs.getString(KEY_LAUNCH_STATS_JSON, null)
            val current = if (raw.isNullOrBlank()) {
                mutableMapOf()
            } else {
                runCatching {
                    val obj = JSONObject(raw)
                    val result = mutableMapOf<String, Pair<Int, Long>>()
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val pkg = keys.next()
                        val item = obj.optJSONObject(pkg) ?: continue
                        result[pkg] = item.optInt("count", 0) to item.optLong("lastMs", 0L)
                    }
                    result
                }.getOrDefault(mutableMapOf())
            }
            val prev = current[packageName] ?: (0 to 0L)
            current[packageName] = (prev.first + 1) to timestampMs
            val obj = JSONObject()
            current.forEach { (pkg, pair) ->
                obj.put(pkg, JSONObject().apply {
                    put("count", pair.first)
                    put("lastMs", pair.second)
                })
            }
            prefs.edit().putString(KEY_LAUNCH_STATS_JSON, obj.toString()).commit()
            current
        }
    }

    suspend fun loadCachedWeather(): WeatherSnapshot? = withContext(Dispatchers.IO) {
        val raw = prefs.getString(KEY_WEATHER_CACHE_JSON, null)
        if (raw.isNullOrBlank()) return@withContext null
        runCatching {
            val obj = JSONObject(raw)
            WeatherSnapshot(
                locationName = obj.optString("locationName", ""),
                temperatureC = obj.optDouble("temperatureC", 0.0),
                apparentTempC = obj.optDouble("apparentTempC", 0.0),
                windKmh = obj.optDouble("windKmh", 0.0),
                humidityPercent = obj.optInt("humidityPercent", 0),
                conditionLabel = obj.optString("conditionLabel", "Unknown"),
                weatherCode = obj.optInt("weatherCode", 0),
                isDay = obj.optBoolean("isDay", true),
                fetchedAtMs = obj.optLong("fetchedAtMs", 0L)
            )
        }.getOrNull()
    }

    suspend fun saveCachedWeather(snapshot: WeatherSnapshot) = withContext(Dispatchers.IO) {
        val obj = JSONObject().apply {
            put("locationName", snapshot.locationName)
            put("temperatureC", snapshot.temperatureC)
            put("apparentTempC", snapshot.apparentTempC)
            put("windKmh", snapshot.windKmh)
            put("humidityPercent", snapshot.humidityPercent)
            put("conditionLabel", snapshot.conditionLabel)
            put("weatherCode", snapshot.weatherCode)
            put("isDay", snapshot.isDay)
            put("fetchedAtMs", snapshot.fetchedAtMs)
        }
        ioMutex.withLock {
            prefs.edit().putString(KEY_WEATHER_CACHE_JSON, obj.toString()).commit()
        }
    }

    internal fun encodeState(state: LauncherState): String {
        val root = JSONObject()
        root.put("pageCount", state.pageCount)
        root.put("preferredHomePage", state.preferredHomePage)
        root.put("dockPackages", JSONArray(state.dockPackages))
        root.put("hiddenPackages", JSONArray(state.hiddenPackages.toList()))
        root.put("favoritePackages", JSONArray(state.favoritePackages.toList()))
        root.put("gamePackagesAddedByUser", JSONArray(state.gamePackagesAddedByUser.toList()))
        root.put("gamePackagesExcluded", JSONArray(state.gamePackagesExcluded.toList()))
        root.put("gamingModeActive", state.gamingModeActive)
        root.put("drawerSortMode", state.drawerSortMode.name)
        root.put("quickNoteText", state.quickNoteText)
        root.put("weatherCityQuery", state.weatherCityQuery)
        root.put("cloudSyncEnabled", state.cloudSyncEnabled)
        root.put("lastCloudSyncMs", state.lastCloudSyncMs)
        root.put("hasCompletedInitialSeed", state.hasCompletedInitialSeed)

        val customCatObj = JSONObject()
        state.customCategories.forEach { (cat, pkgs) ->
            customCatObj.put(cat, JSONArray(pkgs))
        }
        root.put("customCategories", customCatObj)

        root.put("drawerFolders", encodeFolders(state.drawerFolders))
        root.put("gameFolders", encodeFolders(state.gameFolders))

        val itemsArr = JSONArray()
        state.items.forEach { item ->
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("pageIndex", item.pageIndex)
            when (item) {
                is HomeSlotItem.AppItem -> {
                    obj.put("kind", "app")
                    obj.put("packageName", item.packageName)
                    obj.put("label", item.label)
                }
                is HomeSlotItem.FolderItem -> {
                    obj.put("kind", "folder")
                    obj.put("name", item.name)
                    obj.put("appPackages", JSONArray(item.appPackages))
                }
                is HomeSlotItem.WidgetItem -> {
                    obj.put("kind", "widget")
                    obj.put("widgetTypeId", item.widgetTypeId)
                    obj.put("colSpan", item.colSpan)
                    obj.put("rowSpan", item.rowSpan)
                    if (item.systemAppWidgetId != null) {
                        obj.put("systemAppWidgetId", item.systemAppWidgetId)
                    }
                    obj.put("customTitle", item.customTitle)
                }
            }
            itemsArr.put(obj)
        }
        root.put("items", itemsArr)

        root.put("theme", JSONObject().apply {
            put("presetId", state.theme.presetId)
            put("primaryColorHex", state.theme.primaryColorHex)
            put("secondaryColorHex", state.theme.secondaryColorHex)
            put("backgroundAlpha", state.theme.backgroundAlpha.toDouble())
            put("widgetAlpha", state.theme.widgetAlpha.toDouble())
            put("dockAlpha", state.theme.dockAlpha.toDouble())
            put("iconShape", state.theme.iconShape)
            put("iconPackStyle", state.theme.iconPackStyle)
            put("iconSizeDp", state.theme.iconSizeDp)
            put("cornerRadiusDp", state.theme.cornerRadiusDp)
            put("fontOption", state.theme.fontOption)
            put("textScale", state.theme.textScale.toDouble())
            put("animationSpeed", state.theme.animationSpeed.toDouble())
            put("gridColumns", state.theme.gridColumns)
            put("gridRows", state.theme.gridRows)
            put("showDockLabels", state.theme.showDockLabels)
            put("showSearchBar", state.theme.showSearchBar)
            put("showIconLabels", state.theme.showIconLabels)
            put("use24HourClock", state.theme.use24HourClock)
        })

        root.put("wallpaper", JSONObject().apply {
            put("typeId", state.wallpaper.typeId)
            put("label", state.wallpaper.label)
            put("gradientStartHex", state.wallpaper.gradientStartHex)
            put("gradientMidHex", state.wallpaper.gradientMidHex)
            put("gradientEndHex", state.wallpaper.gradientEndHex)
            put("accentHex", state.wallpaper.accentHex)
            put("customMediaUri", state.wallpaper.customMediaUri)
            put("mapLatitude", state.wallpaper.mapLatitude)
            put("mapLongitude", state.wallpaper.mapLongitude)
            put("mapLocationName", state.wallpaper.mapLocationName)
            put("mapZoom", state.wallpaper.mapZoom)
            put("mapStyleId", state.wallpaper.mapStyleId)
            put("mapAnimatePan", state.wallpaper.mapAnimatePan)
            put("mapUseDeviceLocation", state.wallpaper.mapUseDeviceLocation)
            put("aiPromptSeed", state.wallpaper.aiPromptSeed)
            put("targetScreen", state.wallpaper.targetScreen)
        })

        root.put("gestures", JSONObject().apply {
            put("swipeUpAction", state.gestures.swipeUpAction)
            put("swipeDownAction", state.gestures.swipeDownAction)
            put("doubleTapAction", state.gestures.doubleTapAction)
            put("longPressAction", state.gestures.longPressAction)
            put("twoFingerSwipeAction", state.gestures.twoFingerSwipeAction)
            put("customEdgeAction", state.gestures.customEdgeAction)
        })

        return root.toString()
    }

    internal fun decodeState(raw: String): LauncherState {
        return runCatching {
            val root = JSONObject(raw)
            val dock = jsonArrayToList(root.optJSONArray("dockPackages"))
            val hidden = jsonArrayToList(root.optJSONArray("hiddenPackages")).toSet()
            val favorites = jsonArrayToList(root.optJSONArray("favoritePackages")).toSet()
            val addedGames = jsonArrayToList(root.optJSONArray("gamePackagesAddedByUser")).toSet()
            val excludedGames = jsonArrayToList(root.optJSONArray("gamePackagesExcluded")).toSet()

            val customCats = mutableMapOf<String, List<String>>()
            root.optJSONObject("customCategories")?.let { obj ->
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    customCats[key] = jsonArrayToList(obj.optJSONArray(key))
                }
            }

            val itemsList = mutableListOf<HomeSlotItem>()
            val itemsArr = root.optJSONArray("items")
            if (itemsArr != null) {
                for (i in 0 until itemsArr.length()) {
                    val obj = itemsArr.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "slot_$i")
                    val pageIndex = obj.optInt("pageIndex", 0).coerceAtLeast(0)
                    when (obj.optString("kind")) {
                        "app" -> itemsList.add(
                            HomeSlotItem.AppItem(
                                id = id,
                                pageIndex = pageIndex,
                                packageName = obj.optString("packageName"),
                                label = obj.optString("label")
                            )
                        )
                        "folder" -> itemsList.add(
                            HomeSlotItem.FolderItem(
                                id = id,
                                pageIndex = pageIndex,
                                name = obj.optString("name", "Folder"),
                                appPackages = jsonArrayToList(obj.optJSONArray("appPackages"))
                            )
                        )
                        "widget" -> itemsList.add(
                            HomeSlotItem.WidgetItem(
                                id = id,
                                pageIndex = pageIndex,
                                widgetTypeId = obj.optString("widgetTypeId"),
                                colSpan = obj.optInt("colSpan", 4).coerceIn(1, 6),
                                rowSpan = obj.optInt("rowSpan", 1).coerceIn(1, 4),
                                systemAppWidgetId = if (obj.has("systemAppWidgetId")) obj.optInt("systemAppWidgetId") else null,
                                customTitle = obj.optString("customTitle", "")
                            )
                        )
                    }
                }
            }

            val themeObj = root.optJSONObject("theme")
            val theme = if (themeObj != null) {
                LauncherThemeConfig(
                    presetId = themeObj.optString("presetId", "aqua_blue"),
                    primaryColorHex = themeObj.optString("primaryColorHex", "#00E5FF"),
                    secondaryColorHex = themeObj.optString("secondaryColorHex", "#2979FF"),
                    backgroundAlpha = themeObj.optDouble("backgroundAlpha", 0.78).toFloat(),
                    widgetAlpha = themeObj.optDouble("widgetAlpha", 0.72).toFloat(),
                    dockAlpha = themeObj.optDouble("dockAlpha", 0.80).toFloat(),
                    iconShape = themeObj.optString("iconShape", "squircle"),
                    iconPackStyle = themeObj.optString("iconPackStyle", "aqua_glow"),
                    iconSizeDp = themeObj.optInt("iconSizeDp", 54).coerceIn(40, 72),
                    cornerRadiusDp = themeObj.optInt("cornerRadiusDp", 20).coerceIn(4, 32),
                    fontOption = themeObj.optString("fontOption", "space_grotesk"),
                    textScale = themeObj.optDouble("textScale", 1.0).toFloat(),
                    animationSpeed = themeObj.optDouble("animationSpeed", 1.0).toFloat(),
                    gridColumns = themeObj.optInt("gridColumns", 4).coerceIn(3, 6),
                    gridRows = themeObj.optInt("gridRows", 5).coerceIn(3, 7),
                    showDockLabels = themeObj.optBoolean("showDockLabels", false),
                    showSearchBar = themeObj.optBoolean("showSearchBar", true),
                    showIconLabels = themeObj.optBoolean("showIconLabels", true),
                    use24HourClock = themeObj.optBoolean("use24HourClock", false)
                )
            } else LauncherThemeConfig()

            val wallObj = root.optJSONObject("wallpaper")
            val wallpaper = if (wallObj != null) {
                WallpaperConfig(
                    typeId = wallObj.optString("typeId", "animated_aurora"),
                    label = wallObj.optString("label", "Deep Aqua Aurora"),
                    gradientStartHex = wallObj.optString("gradientStartHex", "#03101E"),
                    gradientMidHex = wallObj.optString("gradientMidHex", "#072642"),
                    gradientEndHex = wallObj.optString("gradientEndHex", "#004D66"),
                    accentHex = wallObj.optString("accentHex", "#00E5FF"),
                    customMediaUri = wallObj.optString("customMediaUri", ""),
                    mapLatitude = wallObj.optDouble("mapLatitude", 37.7749),
                    mapLongitude = wallObj.optDouble("mapLongitude", -122.4194),
                    mapLocationName = wallObj.optString("mapLocationName", "San Francisco Bay"),
                    mapZoom = wallObj.optInt("mapZoom", 12).coerceIn(3, 17),
                    mapStyleId = wallObj.optString("mapStyleId", "aqua_night"),
                    mapAnimatePan = wallObj.optBoolean("mapAnimatePan", true),
                    mapUseDeviceLocation = wallObj.optBoolean("mapUseDeviceLocation", false),
                    aiPromptSeed = wallObj.optString("aiPromptSeed", "Bioluminescent deep ocean neural reef"),
                    targetScreen = wallObj.optString("targetScreen", "BOTH")
                )
            } else WallpaperConfig()

            val gestObj = root.optJSONObject("gestures")
            val gestures = if (gestObj != null) {
                LauncherGestureConfig(
                    swipeUpAction = gestObj.optString("swipeUpAction", "open_drawer"),
                    swipeDownAction = gestObj.optString("swipeDownAction", "open_search"),
                    doubleTapAction = gestObj.optString("doubleTapAction", "lock_screen_info"),
                    longPressAction = gestObj.optString("longPressAction", "edit_mode"),
                    twoFingerSwipeAction = gestObj.optString("twoFingerSwipeAction", "open_settings"),
                    customEdgeAction = gestObj.optString("customEdgeAction", "open_aqua_ai")
                )
            } else LauncherGestureConfig()

            val sortMode = runCatching {
                DrawerSortMode.valueOf(root.optString("drawerSortMode", DrawerSortMode.ALPHABETICAL.name))
            }.getOrDefault(DrawerSortMode.ALPHABETICAL)

            val defaults = LauncherState()
            LauncherState(
                pageCount = root.optInt("pageCount", defaults.pageCount).coerceIn(1, 6),
                preferredHomePage = root.optInt("preferredHomePage", 0).coerceIn(0, 5),
                items = if (itemsList.isEmpty() && !root.has("items")) defaults.items else itemsList,
                dockPackages = dock,
                hiddenPackages = hidden,
                favoritePackages = favorites,
                customCategories = customCats,
                drawerFolders = decodeFolders(root.optJSONArray("drawerFolders")),
                gamePackagesAddedByUser = addedGames,
                gamePackagesExcluded = excludedGames,
                gameFolders = decodeFolders(root.optJSONArray("gameFolders")),
                gamingModeActive = root.optBoolean("gamingModeActive", false),
                drawerSortMode = sortMode,
                quickNoteText = root.optString("quickNoteText", ""),
                weatherCityQuery = root.optString("weatherCityQuery", "San Francisco"),
                theme = theme,
                wallpaper = wallpaper,
                gestures = gestures,
                cloudSyncEnabled = root.optBoolean("cloudSyncEnabled", true),
                lastCloudSyncMs = root.optLong("lastCloudSyncMs", 0L),
                hasCompletedInitialSeed = root.optBoolean("hasCompletedInitialSeed", false)
            )
        }.getOrDefault(LauncherState())
    }

    private fun encodeFolders(folders: List<DrawerFolder>): JSONArray {
        val arr = JSONArray()
        folders.forEach { folder ->
            arr.put(JSONObject().apply {
                put("id", folder.id)
                put("name", folder.name)
                put("appPackages", JSONArray(folder.appPackages))
            })
        }
        return arr
    }

    private fun decodeFolders(arr: JSONArray?): List<DrawerFolder> {
        if (arr == null) return emptyList()
        val list = mutableListOf<DrawerFolder>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(
                DrawerFolder(
                    id = obj.optString("id", "folder_$i"),
                    name = obj.optString("name", "Folder"),
                    appPackages = jsonArrayToList(obj.optJSONArray("appPackages"))
                )
            )
        }
        return list
    }

    private fun jsonArrayToList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val v = arr.optString(i)
            if (!v.isNullOrBlank()) list.add(v)
        }
        return list
    }

    private companion object {
        const val PREFS_NAME = "aqua_launcher_prefs"
        const val KEY_LAUNCHER_STATE_JSON = "launcher_state_json"
        const val KEY_LAUNCH_STATS_JSON = "launch_stats_json"
        const val KEY_WEATHER_CACHE_JSON = "weather_cache_json"
    }
}
