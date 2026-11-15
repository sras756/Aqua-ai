package com.example.launcher.engine

import com.example.launcher.model.DiscoveredApp
import com.example.launcher.model.LauncherThemePreset
import com.example.launcher.model.WallpaperType

enum class LauncherAiUiAction {
    NONE,
    OPEN_APP_DRAWER,
    OPEN_RECENT_APPS,
    OPEN_WALLPAPER_PICKER,
    OPEN_UNIVERSAL_SEARCH,
    OPEN_GAMING_HUB,
    OPEN_DEFAULT_HOME_SETTINGS,
    OPEN_BROWSER_SEARCH
}

data class LauncherAiActionProposal(
    val title: String,
    val description: String,
    val packageName: String? = null,
    val actionKind: String // "UNINSTALL_APP", "DEFAULT_HOME_SETTINGS", "SECURITY_SETTINGS"
)

data class LauncherAiCommandOutcome(
    val executed: Boolean,
    val message: String,
    val uiAction: LauncherAiUiAction = LauncherAiUiAction.NONE,
    val queryPayload: String = "",
    val matchedApps: List<DiscoveredApp> = emptyList(),
    val pendingPermissionProposal: LauncherAiActionProposal? = null
)

class LauncherAiController(private val engine: AquaLauncherEngine) {

    /**
     * Parses and routes natural-language launcher requests through an explicit Action-Permission layer.
     * Supports local instant execution for safe device/launcher actions, user confirmation prompts
     * for sensitive system changes (uninstall, security/home settings), and honest error reporting
     * when a requested capability or app is unavailable.
     */
    suspend fun executeCommand(rawPrompt: String): LauncherAiCommandOutcome {
        val prompt = rawPrompt.trim()
        if (prompt.isEmpty()) {
            return LauncherAiCommandOutcome(
                executed = false,
                message = "Enter a launcher command such as \"Open my browser\", \"Show my battery level\", or \"Turn on dark theme\"."
            )
        }
        val lower = prompt.lowercase()
        val apps = engine.installedApps.value
        val state = engine.state.value

        // 1. Sensitive Action Gate: App Uninstall
        if (lower.startsWith("uninstall ") || lower.contains("delete app ") || lower.contains("remove app ")) {
            val targetName = lower
                .removePrefix("uninstall ")
                .replace("delete app ", "")
                .replace("remove app ", "")
                .trim()
            val match = findBestAppMatch(targetName, apps)
            return if (match != null) {
                LauncherAiCommandOutcome(
                    executed = false,
                    message = "Uninstalling '${match.label}' modifies device software and requires your explicit approval.",
                    pendingPermissionProposal = LauncherAiActionProposal(
                        title = "Confirm Uninstall: ${match.label}",
                        description = "Aqua AI will open Android's official uninstall confirmation dialog for ${match.packageName}. No app can be uninstalled silently.",
                        packageName = match.packageName,
                        actionKind = "UNINSTALL_APP"
                    )
                )
            } else {
                LauncherAiCommandOutcome(
                    executed = false,
                    message = "Could not find an installed application matching '$targetName' to uninstall."
                )
            }
        }

        // 2. Sensitive Action Gate: Security / System Settings / Default Home change
        if (lower.contains("security settings") || lower.contains("lock screen") || lower.contains("default launcher") || lower.contains("default home")) {
            return if (lower.contains("lock screen")) {
                LauncherAiCommandOutcome(
                    executed = false,
                    message = "Standard Android launchers cannot lock the screen silently without a Device Admin or Accessibility grant. Android's power/lock button or system Security settings remain in your control.",
                    pendingPermissionProposal = LauncherAiActionProposal(
                        title = "Open Android Security Settings?",
                        description = "Open Android Security settings so you can review screen lock and biometric preferences manually.",
                        actionKind = "SECURITY_SETTINGS"
                    )
                )
            } else {
                LauncherAiCommandOutcome(
                    executed = false,
                    message = "Changing the system default Home app is controlled by Android RoleManager / System Settings.",
                    uiAction = LauncherAiUiAction.OPEN_DEFAULT_HOME_SETTINGS,
                    pendingPermissionProposal = LauncherAiActionProposal(
                        title = "Open Android Default Home Selector?",
                        description = "Choose or switch the system default launcher via Android's official chooser.",
                        actionKind = "DEFAULT_HOME_SETTINGS"
                    )
                )
            }
        }

        // 3. Battery level & device telemetry
        if (lower.contains("battery") || lower.contains("device status") || lower.contains("storage") || lower.contains("ram")) {
            val telemetry = engine.deviceController.getTelemetry()
            val storage = engine.deviceController.getStorageAndMemoryStats()
            val chargeLabel = if (telemetry.isCharging) "Charging" else "Discharging"
            val pctText = if (telemetry.batteryPercent >= 0) "${telemetry.batteryPercent}%" else "Unavailable"
            return LauncherAiCommandOutcome(
                executed = true,
                message = "Battery: $pctText ($chargeLabel) • Network: ${telemetry.connectionType} • Storage free: ${storage.storageFreeMb} MB / ${storage.storageTotalMb} MB • Free RAM: ${storage.ramAvailableMb} MB."
            )
        }

        // 4. Recent applications
        if (lower.contains("recently used") || lower.contains("recent app")) {
            val recent = engine.getRecentApps(8)
            return if (recent.isNotEmpty()) {
                LauncherAiCommandOutcome(
                    executed = true,
                    message = "Showing ${recent.size} recently launched app(s) from Aqua Launcher: ${recent.joinToString { it.label }}.",
                    uiAction = LauncherAiUiAction.OPEN_RECENT_APPS,
                    matchedApps = recent
                )
            } else {
                LauncherAiCommandOutcome(
                    executed = true,
                    message = "No launcher app launches have been recorded yet. Showing all ${apps.size} installed apps so you can launch one.",
                    uiAction = LauncherAiUiAction.OPEN_APP_DRAWER,
                    matchedApps = apps.take(8)
                )
            }
        }

        // 5. Downloaded files
        if (lower.contains("downloaded files") || lower.contains("downloads")) {
            val res = engine.searchEngine.openDownloadsApp()
            return if (res.isSuccess) {
                LauncherAiCommandOutcome(
                    executed = true,
                    message = "Opened the Android Downloads manager to view your downloaded files."
                )
            } else {
                LauncherAiCommandOutcome(
                    executed = false,
                    message = "Could not open the Downloads app (${res.exceptionOrNull()?.localizedMessage ?: "no Downloads handler found"}). Try searching in Files."
                )
            }
        }

        // 6. Wallpaper / Live Map Wallpaper
        if (lower.contains("wallpaper")) {
            if (lower.contains("map")) {
                engine.updateWallpaperConfig {
                    it.copy(
                        typeId = WallpaperType.LIVE_MAP.id,
                        label = "Aqua Live Map Wallpaper"
                    )
                }
            }
            return LauncherAiCommandOutcome(
                executed = true,
                message = "Opened Aqua Wallpaper Engine where you can choose Live Map, Aurora, AI Generative, Custom Photos, or apply directly to your Android Home/Lock screen.",
                uiAction = LauncherAiUiAction.OPEN_WALLPAPER_PICKER
            )
        }

        // 7. Theme switching (dark theme, midnight, aqua blue, glassmorphism, gaming)
        if (lower.contains("dark theme") || lower.contains("midnight") || lower.contains("aqua blue") ||
            lower.contains("glassmorphism") || lower.contains("minimal theme") || lower.contains("gaming mode") ||
            lower.contains("gaming theme")
        ) {
            val preset = when {
                lower.contains("midnight") || lower.contains("dark theme") -> LauncherThemePreset.MIDNIGHT_DARK
                lower.contains("glass") -> LauncherThemePreset.GLASSMORPHISM
                lower.contains("minimal") -> LauncherThemePreset.MINIMAL
                lower.contains("gaming") -> LauncherThemePreset.GAMING
                lower.contains("premium") -> LauncherThemePreset.PREMIUM
                else -> LauncherThemePreset.AQUA_BLUE
            }
            if (preset == LauncherThemePreset.GAMING) {
                engine.toggleGamingMode(true)
            } else {
                engine.applyThemePreset(preset)
            }
            return LauncherAiCommandOutcome(
                executed = true,
                message = "Switched Aqua Launcher theme to ${preset.displayName} (synced across both Standalone & Integrated Launcher).",
                uiAction = if (preset == LauncherThemePreset.GAMING) LauncherAiUiAction.OPEN_GAMING_HUB else LauncherAiUiAction.NONE
            )
        }

        // 8. Find installed games
        if (lower.contains("game") && (lower.contains("find") || lower.contains("show") || lower.contains("open"))) {
            val games = engine.getGameApps(state, apps)
            return if (games.isNotEmpty()) {
                LauncherAiCommandOutcome(
                    executed = true,
                    message = "Found ${games.size} installed game(s): ${games.joinToString { it.label }}.",
                    uiAction = LauncherAiUiAction.OPEN_GAMING_HUB,
                    matchedApps = games
                )
            } else {
                LauncherAiCommandOutcome(
                    executed = false,
                    message = "No apps tagged as Games were detected on this device. Opening Aqua Gaming Hub so you can manually pin or inspect apps.",
                    uiAction = LauncherAiUiAction.OPEN_GAMING_HUB
                )
            }
        }

        // 9. Open camera
        if (lower == "open camera" || lower == "open the camera" || lower.contains("launch camera")) {
            val res = engine.openDeviceCamera()
            return if (res.isSuccess) {
                LauncherAiCommandOutcome(executed = true, message = "Opened the Android Camera app.")
            } else {
                LauncherAiCommandOutcome(
                    executed = false,
                    message = "Camera could not be launched: ${res.exceptionOrNull()?.localizedMessage ?: "No camera app available"}."
                )
            }
        }

        // 10. Open browser
        if (lower.contains("my browser") || lower == "open browser" || lower == "launch browser") {
            val browserApp = apps.firstOrNull {
                it.category == com.example.launcher.model.AppCategory.BROWSER ||
                    it.packageName.contains("chrome") ||
                    it.packageName.contains("browser")
            }
            return if (browserApp != null) {
                val res = engine.launchApp(browserApp)
                LauncherAiCommandOutcome(
                    executed = res.isSuccess,
                    message = if (res.isSuccess) {
                        "Opened your browser (${browserApp.label})."
                    } else {
                        "Failed to open ${browserApp.label}: ${res.exceptionOrNull()?.localizedMessage}"
                    }
                )
            } else {
                LauncherAiCommandOutcome(
                    executed = true,
                    message = "No external browser package found; opening integrated Aqua Browser.",
                    uiAction = LauncherAiUiAction.OPEN_BROWSER_SEARCH
                )
            }
        }

        // 11. Flashlight
        if (lower.contains("flashlight") || lower.contains("torch")) {
            val enable = if (lower.contains("off")) false else if (lower.contains("on")) true else null
            val res = engine.deviceController.toggleFlashlight(enable)
            return LauncherAiCommandOutcome(
                executed = res.isSuccess,
                message = res.getOrElse { "Flashlight unavailable: ${it.localizedMessage}" }
            )
        }

        // 12. Search apps / Universal search
        if (lower.startsWith("search for ") || lower.startsWith("search ") || lower.startsWith("find ")) {
            val query = prompt
                .replace(Regex("^(search for|search|find)\\s+", RegexOption.IGNORE_CASE), "")
                .replace(Regex("^(an application|application|app)\\s*", RegexOption.IGNORE_CASE), "")
                .trim()
            val matches = apps.filter {
                query.isBlank() || it.label.lowercase().contains(query.lowercase()) ||
                    it.packageName.lowercase().contains(query.lowercase())
            }
            return LauncherAiCommandOutcome(
                executed = true,
                message = if (matches.isNotEmpty()) {
                    "Found ${matches.size} installed app(s) matching '${query.ifBlank { "all apps" }}': ${matches.take(6).joinToString { it.label }}."
                } else {
                    "No installed app matched '$query'. Showing Aqua Universal Search results."
                },
                uiAction = LauncherAiUiAction.OPEN_UNIVERSAL_SEARCH,
                queryPayload = query,
                matchedApps = matches
            )
        }

        // 13. Open / Launch <appName>
        if (lower.startsWith("open ") || lower.startsWith("launch ") || lower.startsWith("start ")) {
            val appQuery = prompt
                .replace(Regex("^(open|launch|start)\\s+", RegexOption.IGNORE_CASE), "")
                .replace(Regex("^the\\s+", RegexOption.IGNORE_CASE), "")
                .trim()
            if (appQuery.equals("app drawer", ignoreCase = true) || appQuery.equals("drawer", ignoreCase = true)) {
                return LauncherAiCommandOutcome(
                    executed = true,
                    message = "Opened Smart App Drawer.",
                    uiAction = LauncherAiUiAction.OPEN_APP_DRAWER
                )
            }
            val match = findBestAppMatch(appQuery, apps)
            return if (match != null) {
                val res = engine.launchApp(match)
                if (res.isSuccess) {
                    LauncherAiCommandOutcome(
                        executed = true,
                        message = "Launched ${match.label} (${match.packageName}).",
                        matchedApps = listOf(match)
                    )
                } else {
                    LauncherAiCommandOutcome(
                        executed = false,
                        message = "Unable to launch ${match.label}: ${res.exceptionOrNull()?.localizedMessage}"
                    )
                }
            } else {
                LauncherAiCommandOutcome(
                    executed = false,
                    message = "No installed app matching '$appQuery' was found on this device. Opening Universal Search instead.",
                    uiAction = LauncherAiUiAction.OPEN_UNIVERSAL_SEARCH,
                    queryPayload = appQuery
                )
            }
        }

        // 14. Unsupported or general search fallback
        val directMatches = apps.filter { it.label.lowercase().contains(lower) }
        return if (directMatches.isNotEmpty()) {
            LauncherAiCommandOutcome(
                executed = true,
                message = "Found ${directMatches.size} matching app(s) for '$prompt'.",
                uiAction = LauncherAiUiAction.OPEN_UNIVERSAL_SEARCH,
                queryPayload = prompt,
                matchedApps = directMatches
            )
        } else {
            LauncherAiCommandOutcome(
                executed = false,
                message = "Command '$prompt' is not a built-in direct launcher shortcut; opening Aqua Universal Search or use Aqua AI Core chat for full Gemini reasoning.",
                uiAction = LauncherAiUiAction.OPEN_UNIVERSAL_SEARCH,
                queryPayload = prompt
            )
        }
    }

    fun executeApprovedProposal(proposal: LauncherAiActionProposal): Result<String> {
        return when (proposal.actionKind) {
            "UNINSTALL_APP" -> {
                val pkg = proposal.packageName
                    ?: return Result.failure(IllegalArgumentException("Package missing"))
                engine.appDiscovery.requestUninstall(pkg)
            }
            "DEFAULT_HOME_SETTINGS" -> engine.openHomeSettings()
            "SECURITY_SETTINGS" -> engine.deviceController.openAndroidSettings("general")
            else -> Result.failure(UnsupportedOperationException("Unknown proposal action"))
        }
    }

    private fun findBestAppMatch(query: String, apps: List<DiscoveredApp>): DiscoveredApp? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        return apps.firstOrNull { it.label.lowercase() == q }
            ?: apps.firstOrNull { it.label.lowercase().startsWith(q) }
            ?: apps.firstOrNull { it.label.lowercase().contains(q) }
            ?: apps.firstOrNull { it.packageName.lowercase().contains(q) }
    }
}
