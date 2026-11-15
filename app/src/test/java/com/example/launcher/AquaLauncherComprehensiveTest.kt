package com.example.launcher

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.Conversation
import com.example.data.model.MemoryItem
import com.example.launcher.engine.AppDiscoveryEngine
import com.example.launcher.engine.AquaLauncherEngine
import com.example.launcher.engine.AquaUniversalSearchEngine
import com.example.launcher.engine.LauncherAiController
import com.example.launcher.engine.LauncherAiUiAction
import com.example.launcher.engine.LauncherPersistenceStore
import com.example.launcher.engine.LauncherThemeEngine
import com.example.launcher.model.AppCategory
import com.example.launcher.model.DiscoveredApp
import com.example.launcher.model.DrawerSortMode
import com.example.launcher.model.GestureActionType
import com.example.launcher.model.GestureTrigger
import com.example.launcher.model.HomeSlotItem
import com.example.launcher.model.LauncherState
import com.example.launcher.model.LauncherThemePreset
import com.example.launcher.model.LauncherWidgetType
import com.example.launcher.model.SearchResultCategory
import com.example.launcher.model.WallpaperConfig
import com.example.launcher.model.WallpaperType
import com.example.launcher.wallpaper.AquaWallpaperEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AquaLauncherComprehensiveTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun launcherPersistenceStore_roundTripsLayoutFoldersWidgetsAndThemesAcrossRestart() = runBlocking {
        val store = LauncherPersistenceStore(context)
        val initial = LauncherState(
            pageCount = 3,
            preferredHomePage = 1,
            dockPackages = listOf("com.example.browser", "com.example.dialer"),
            hiddenPackages = setOf("com.example.hidden"),
            favoritePackages = setOf("com.example.browser"),
            customCategories = mapOf("Work" to listOf("com.example.docs")),
            gamingModeActive = true,
            drawerSortMode = DrawerSortMode.FREQUENT,
            quickNoteText = "Check flight at 8am",
            weatherCityQuery = "Tokyo",
            theme = LauncherThemeEngine.applyPreset(
                LauncherState().theme.copy(gridColumns = 5, gridRows = 6, iconSizeDp = 58),
                LauncherThemePreset.GAMING
            ),
            wallpaper = WallpaperConfig(
                typeId = WallpaperType.LIVE_MAP.id,
                label = "Tokyo Bay",
                mapLatitude = 35.6762,
                mapLongitude = 139.6503,
                mapLocationName = "Tokyo",
                mapZoom = 13
            ),
            items = listOf(
                HomeSlotItem.WidgetItem("w1", 0, LauncherWidgetType.AQUA_AI.id, colSpan = 4, rowSpan = 1),
                HomeSlotItem.FolderItem("f1", 1, "Social", listOf("com.example.chat")),
                HomeSlotItem.AppItem("a1", 1, "com.example.cam", "Camera")
            ),
            hasCompletedInitialSeed = true
        )

        store.saveState(initial)
        val reloaded = store.loadState()

        assertEquals(3, reloaded.pageCount)
        assertEquals(1, reloaded.preferredHomePage)
        assertEquals(listOf("com.example.browser", "com.example.dialer"), reloaded.dockPackages)
        assertTrue("com.example.hidden" in reloaded.hiddenPackages)
        assertEquals("Tokyo", reloaded.wallpaper.mapLocationName)
        assertEquals(13, reloaded.wallpaper.mapZoom)
        assertEquals(LauncherThemePreset.GAMING.id, reloaded.theme.presetId)
        assertEquals(5, reloaded.theme.gridColumns)
        assertEquals(6, reloaded.theme.gridRows)
        assertEquals(DrawerSortMode.FREQUENT, reloaded.drawerSortMode)
        assertEquals("Check flight at 8am", reloaded.quickNoteText)
        assertEquals(3, reloaded.items.size)
        assertTrue(reloaded.items[1] is HomeSlotItem.FolderItem)
    }

    @Test
    fun cloudSyncJson_excludesPrivateInstalledAppsContactsAndGpsCoordinates() {
        val state = LauncherState(
            dockPackages = listOf("com.secret.bank", "com.private.chat"),
            hiddenPackages = setOf("com.secret.vault"),
            wallpaper = WallpaperConfig(
                typeId = WallpaperType.LIVE_MAP.id,
                mapLatitude = 37.7749,
                mapLongitude = -122.4194,
                mapLocationName = "San Francisco"
            )
        )
        val cloudJson = state.toCloudSyncJson()
        assertFalse(cloudJson.contains("com.secret.bank"))
        assertFalse(cloudJson.contains("com.secret.vault"))
        assertFalse(cloudJson.contains("37.7749"))
        assertFalse(cloudJson.contains("-122.4194"))

        val merged = LauncherState().applyCloudSyncJson("""{"themePreset":"midnight_dark","gridColumns":5,"swipeUpAction":"open_search"}""")
        assertEquals("midnight_dark", merged.theme.presetId)
        assertEquals(5, merged.theme.gridColumns)
        assertEquals(GestureActionType.OPEN_UNIVERSAL_SEARCH, merged.gestures.actionFor(GestureTrigger.SWIPE_UP))
    }

    @Test
    fun manifestDeclaresStandaloneHomeActivityAndLiveWallpaperService() {
        val pm = context.packageManager
        val homeIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
        val homeHandlers = pm.queryIntentActivities(homeIntent, 0)
        assertTrue(
            "AquaLauncherActivity must handle CATEGORY_HOME",
            homeHandlers.any { it.activityInfo.packageName == context.packageName }
        )

        val wallpaperIntent = Intent("android.service.wallpaper.WallpaperService")
        val wallpaperServices = pm.queryIntentServices(wallpaperIntent, 0)
        assertTrue(
            "AquaLiveWallpaperService must be registered",
            wallpaperServices.any { it.serviceInfo.packageName == context.packageName }
        )
    }

    @Test
    fun appDiscovery_launchesOrHandlesMissingAndTracksStats() = runBlocking {
        val discovery = AppDiscoveryEngine(context)
        val apps = discovery.discoverInstalledApps(includeIcons = false)
        assertNotNull(apps)

        val missingLaunch = discovery.launchApp("com.nonexistent.fake.pkg.xyz")
        assertTrue(missingLaunch.isFailure)

        val store = LauncherPersistenceStore(context)
        val updated = store.recordAppLaunch("com.example.sample")
        assertTrue((updated["com.example.sample"]?.first ?: 0) >= 1)
    }

    @Test
    fun launcherAiController_enforcesPermissionGateAndHonestCapabilities() = runBlocking {
        val engine = AquaLauncherEngine.getInstance(context)
        val ai = LauncherAiController(engine)

        val batteryRes = ai.executeCommand("Show my battery level")
        assertTrue(batteryRes.executed)
        assertTrue(batteryRes.message.contains("Battery:"))

        val darkRes = ai.executeCommand("Turn on dark theme")
        assertTrue(darkRes.executed)
        assertEquals(LauncherThemePreset.MIDNIGHT_DARK.id, engine.state.value.theme.presetId)

        val lockRes = ai.executeCommand("Lock screen now")
        assertFalse(lockRes.executed)
        assertNotNull(lockRes.pendingPermissionProposal)
        assertEquals("SECURITY_SETTINGS", lockRes.pendingPermissionProposal?.actionKind)

        val defaultRes = ai.executeCommand("Set default launcher")
        assertFalse(defaultRes.executed)
        assertEquals(LauncherAiUiAction.OPEN_DEFAULT_HOME_SETTINGS, defaultRes.uiAction)
        assertEquals("DEFAULT_HOME_SETTINGS", defaultRes.pendingPermissionProposal?.actionKind)

        val missingUninstall = ai.executeCommand("Uninstall totallynonexistentapp999")
        assertFalse(missingUninstall.executed)
        assertNull(missingUninstall.pendingPermissionProposal)
        assertTrue(missingUninstall.message.contains("Could not find"))
    }

    @Test
    fun universalSearch_searchesAcrossAppsSettingsConversationsMemoriesAndWeb() = runBlocking {
        val search = AquaUniversalSearchEngine(context)
        val fakeApps = listOf(
            DiscoveredApp("org.mozilla.firefox", "App", "Firefox Browser", AppCategory.BROWSER, false, false),
            DiscoveredApp("com.spotify.music", "Main", "Spotify", AppCategory.MEDIA, false, false)
        )
        val convs = listOf(Conversation(id = "c1", title = "Browser automation notes", summary = "Firefox tips"))
        val mems = listOf(MemoryItem(id = "m1", title = "Preferred Browser", content = "Firefox focus mode"))

        val hits = search.searchAll("firefox", fakeApps, convs, mems)
        assertTrue(hits.any { it.category == SearchResultCategory.APP && it.title == "Firefox Browser" })
        assertTrue(hits.any { it.category == SearchResultCategory.CONVERSATION })
        assertTrue(hits.any { it.category == SearchResultCategory.WEB })
    }

    @Test
    fun wallpaperEngine_tileMathAndOfflineCanvasRenderingSucceed() {
        val (x, y) = AquaWallpaperEngine.latLonToTile(37.7749, -122.4194, 12)
        assertTrue(x in 0 until (1 shl 12))
        assertTrue(y in 0 until (1 shl 12))

        val bmp = AquaWallpaperEngine(context).renderWallpaperToBitmap(
            config = WallpaperConfig(typeId = WallpaperType.LIVE_MAP.id),
            widthPx = 360,
            heightPx = 640,
            phase = 0.4f
        )
        assertEquals(360, bmp.width)
        assertEquals(640, bmp.height)
    }

    @Test
    fun themeEngine_resolvesAllPresetsAndFontFamilies() {
        LauncherThemePreset.entries.forEach { preset ->
            val cfg = LauncherThemeEngine.applyPreset(LauncherState().theme, preset)
            val resolved = LauncherThemeEngine.resolve(cfg)
            assertTrue(resolved.animationDurationMs in 100..650)
        }
    }
}
