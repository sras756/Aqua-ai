package com.example.launcher.engine

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.launcher.model.AppCategory
import com.example.launcher.model.AppShortcutInfo
import com.example.launcher.model.DiscoveredApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppDiscoveryEngine(private val context: Context) {

    private val appContext = context.applicationContext
    private val iconCache = object : LruCache<String, ImageBitmap>(120) {}

    suspend fun discoverInstalledApps(
        launchStats: Map<String, Pair<Int, Long>> = emptyMap(),
        includeIcons: Boolean = true
    ): List<DiscoveredApp> = withContext(Dispatchers.IO) {
        val pm = appContext.packageManager
        val launcherApps = appContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        val canQueryShortcuts = launcherApps?.hasShortcutHostPermission() == true
        val currentUser: UserHandle = Process.myUserHandle()

        val discovered = linkedMapOf<String, DiscoveredApp>()

        if (launcherApps != null) {
            runCatching {
                val activities = launcherApps.getActivityList(null, currentUser)
                activities.forEach { info ->
                    val pkg = info.componentName.packageName
                    if (discovered.containsKey(pkg)) return@forEach
                    val appInfo = info.applicationInfo
                    val label = info.label?.toString().orEmpty().ifBlank { pkg }
                    val category = resolveCategory(appInfo, pkg)
                    val isGame = isGameApp(appInfo, pkg)
                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val stats = launchStats[pkg]
                    val icon = if (includeIcons) {
                        getOrLoadIcon(pkg) {
                            runCatching { info.getBadgedIcon(0) ?: info.getIcon(0) }.getOrNull()
                                ?: runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
                        }
                    } else null
                    val shortcuts = if (canQueryShortcuts) {
                        loadShortcutsSafely(launcherApps, pkg, currentUser)
                    } else emptyList()

                    discovered[pkg] = DiscoveredApp(
                        packageName = pkg,
                        activityName = info.componentName.className,
                        label = label,
                        category = category,
                        isSystemApp = isSystem,
                        isGame = isGame,
                        launchCount = stats?.first ?: 0,
                        lastLaunchedMs = stats?.second ?: 0L,
                        iconBitmap = icon,
                        shortcuts = shortcuts
                    )
                }
            }
        }

        // Supplement or fallback via PackageManager queryIntentActivities (works cleanly in Robolectric and all APIs)
        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PackageManager.MATCH_ALL else 0
        val resolved = runCatching { pm.queryIntentActivities(mainIntent, flags) }.getOrDefault(emptyList())
        resolved.forEach { ri ->
            val actInfo = ri.activityInfo ?: return@forEach
            val pkg = actInfo.packageName ?: return@forEach
            if (discovered.containsKey(pkg)) return@forEach
            val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg).ifBlank { pkg }
            val appInfo = actInfo.applicationInfo
            val category = resolveCategory(appInfo, pkg)
            val isGame = isGameApp(appInfo, pkg)
            val isSystem = appInfo != null && (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val stats = launchStats[pkg]
            val icon = if (includeIcons) {
                getOrLoadIcon(pkg) {
                    runCatching { ri.loadIcon(pm) }.getOrNull()
                }
            } else null

            discovered[pkg] = DiscoveredApp(
                packageName = pkg,
                activityName = actInfo.name.orEmpty(),
                label = label,
                category = category,
                isSystemApp = isSystem,
                isGame = isGame,
                launchCount = stats?.first ?: 0,
                lastLaunchedMs = stats?.second ?: 0L,
                iconBitmap = icon,
                shortcuts = emptyList()
            )
        }

        discovered.values.sortedBy { it.label.lowercase() }
    }

    fun hasShortcutPermission(): Boolean {
        val launcherApps = appContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        return runCatching { launcherApps?.hasShortcutHostPermission() == true }.getOrDefault(false)
    }

    fun launchApp(packageName: String, activityName: String = ""): Result<String> = runCatching {
        val pm = appContext.packageManager
        val launcherApps = appContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        if (launcherApps != null && activityName.isNotBlank()) {
            val comp = ComponentName(packageName, activityName)
            val started = runCatching {
                launcherApps.startMainActivity(comp, Process.myUserHandle(), null, null)
                true
            }.getOrDefault(false)
            if (started) return@runCatching "Launched $packageName"
        }
        val intent = pm.getLaunchIntentForPackage(packageName)
            ?: throw IllegalStateException("No launcher entry point exposed by $packageName.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
        "Launched $packageName"
    }

    fun launchAppShortcut(shortcut: AppShortcutInfo): Result<String> = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            throw UnsupportedOperationException("Android App Shortcuts require Android 7.1 (API 25) or higher.")
        }
        val launcherApps = appContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            ?: throw IllegalStateException("LauncherApps service unavailable.")
        if (!launcherApps.hasShortcutHostPermission()) {
            throw SecurityException("Shortcut permission requires Aqua Launcher to be set as the default Home app.")
        }
        launcherApps.startShortcut(shortcut.packageName, shortcut.id, null, null, Process.myUserHandle())
        "Launched shortcut '${shortcut.shortLabel}'"
    }

    fun openAppInfo(packageName: String): Result<String> = runCatching {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
        "Opened Android App Info for $packageName"
    }

    fun requestUninstall(packageName: String): Result<String> = runCatching {
        require(packageName != appContext.packageName) {
            "Aqua AI cannot uninstall itself while running."
        }
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
        "Opened Android uninstall confirmation for $packageName."
    }

    fun registerPackageReceiver(onPackageChanged: () -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val pkg = intent?.data?.schemeSpecificPart
                if (!pkg.isNullOrBlank()) {
                    iconCache.remove(pkg)
                }
                onPackageChanged()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
        return receiver
    }

    fun unregisterReceiverSafely(receiver: BroadcastReceiver?) {
        if (receiver == null) return
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    private fun loadShortcutsSafely(
        launcherApps: LauncherApps,
        packageName: String,
        userHandle: UserHandle
    ): List<AppShortcutInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return emptyList()
        return runCatching {
            val query = LauncherApps.ShortcutQuery().apply {
                setPackage(packageName)
                setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
                )
            }
            launcherApps.getShortcuts(query, userHandle).orEmpty().take(5).map { info ->
                AppShortcutInfo(
                    id = info.id,
                    packageName = packageName,
                    shortLabel = info.shortLabel?.toString().orEmpty().ifBlank { info.id },
                    longLabel = info.longLabel?.toString().orEmpty()
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun getOrLoadIcon(packageName: String, loader: () -> Drawable?): ImageBitmap? {
        iconCache.get(packageName)?.let { return it }
        val drawable = loader() ?: return null
        val bmp = drawableToBitmap(drawable, 128) ?: return null
        val img = bmp.asImageBitmap()
        iconCache.put(packageName, img)
        return img
    }

    private fun drawableToBitmap(drawable: Drawable, targetPx: Int): Bitmap? {
        return runCatching {
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                return drawable.bitmap
            }
            val width = drawable.intrinsicWidth.takeIf { it > 0 }?.coerceAtMost(targetPx) ?: targetPx
            val height = drawable.intrinsicHeight.takeIf { it > 0 }?.coerceAtMost(targetPx) ?: targetPx
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        }.getOrNull()
    }

    private fun resolveCategory(appInfo: ApplicationInfo?, pkg: String): AppCategory {
        if (appInfo != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            when (appInfo.category) {
                ApplicationInfo.CATEGORY_GAME -> return AppCategory.GAMES
                ApplicationInfo.CATEGORY_AUDIO,
                ApplicationInfo.CATEGORY_VIDEO,
                ApplicationInfo.CATEGORY_IMAGE -> return AppCategory.MEDIA
                ApplicationInfo.CATEGORY_SOCIAL -> return AppCategory.SOCIAL
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> return AppCategory.PRODUCTIVITY
                ApplicationInfo.CATEGORY_NEWS -> return AppCategory.BROWSER
                ApplicationInfo.CATEGORY_MAPS -> return AppCategory.TOOLS
            }
        }
        val lower = pkg.lowercase()
        return when {
            isGameApp(appInfo, pkg) -> AppCategory.GAMES
            lower.contains("browser") || lower.contains("chrome") || lower.contains("firefox") -> AppCategory.BROWSER
            lower.contains("messaging") || lower.contains("dialer") || lower.contains("whatsapp") ||
                lower.contains("telegram") || lower.contains("contacts") || lower.contains("phone") ||
                lower.contains("mail") || lower.contains("gmail") -> AppCategory.COMMUNICATION
            lower.contains("wallet") || lower.contains("bank") || lower.contains("pay") ||
                lower.contains("crypto") || lower.contains("finance") -> AppCategory.FINANCE
            lower.contains("music") || lower.contains("spotify") || lower.contains("youtube") ||
                lower.contains("camera") || lower.contains("gallery") || lower.contains("photos") -> AppCategory.MEDIA
            lower.contains("calendar") || lower.contains("notes") || lower.contains("docs") ||
                lower.contains("sheets") || lower.contains("drive") -> AppCategory.PRODUCTIVITY
            lower.contains("settings") || lower.contains("clock") || lower.contains("calc") ||
                lower.contains("files") -> AppCategory.TOOLS
            else -> AppCategory.OTHER
        }
    }

    @Suppress("DEPRECATION")
    private fun isGameApp(appInfo: ApplicationInfo?, pkg: String): Boolean {
        if (appInfo != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                appInfo.category == ApplicationInfo.CATEGORY_GAME
            ) {
                return true
            }
            if ((appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0) {
                return true
            }
        }
        return false
    }
}
