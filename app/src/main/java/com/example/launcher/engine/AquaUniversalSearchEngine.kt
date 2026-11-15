package com.example.launcher.engine

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.example.data.model.BrowsingTab
import com.example.data.model.Conversation
import com.example.data.model.MemoryItem
import com.example.launcher.model.DiscoveredApp
import com.example.launcher.model.SearchResultCategory
import com.example.launcher.model.UniversalSearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AquaUniversalSearchEngine(private val context: Context) {

    private val appContext = context.applicationContext

    fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun hasCalendarPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun searchAll(
        query: String,
        apps: List<DiscoveredApp>,
        conversations: List<Conversation> = emptyList(),
        memories: List<MemoryItem> = emptyList(),
        browserTabs: List<BrowsingTab> = emptyList()
    ): List<UniversalSearchHit> = withContext(Dispatchers.IO) {
        val q = query.trim()
        val lower = q.lowercase()
        val hits = mutableListOf<UniversalSearchHit>()

        // 1. Installed Applications
        apps.filter {
            q.isBlank() ||
                it.label.lowercase().contains(lower) ||
                it.packageName.lowercase().contains(lower) ||
                it.category.label.lowercase().contains(lower)
        }.take(10).forEach { app ->
            hits.add(
                UniversalSearchHit(
                    id = "app_${app.packageName}",
                    category = SearchResultCategory.APP,
                    title = app.label,
                    subtitle = "${app.category.label} • ${app.packageName}",
                    payload = app.packageName
                )
            )
        }

        // 2. Launcher Settings & Shortcuts
        val settingEntries = listOf(
            Triple("theme", "Launcher Themes & Colors", "Customize Aqua Blue, Midnight, Glassmorphism, Gaming & fonts"),
            Triple("wallpaper", "Wallpapers & Live Map Wallpaper", "Choose Live Map, Aurora, Photo, Video, or System Wallpaper"),
            Triple("widgets", "Widgets & System AppWidgetHost", "Add Aqua widgets or Android system widgets"),
            Triple("gestures", "Launcher Gestures", "Swipe up, swipe down, double-tap, long press & two-finger controls"),
            Triple("gaming", "Aqua Gaming Mode", "Open Gaming Launcher, recent games & thermal monitor"),
            Triple("default_home", "Default Launcher Settings", "Set Aqua Launcher as Home or switch back via Android settings"),
            Triple("downloads", "Downloaded Files (Android Downloads)", "Open your device's Downloads manager")
        )
        settingEntries.filter { (id, title, sub) ->
            q.isBlank() || title.lowercase().contains(lower) || sub.lowercase().contains(lower) || id.contains(lower)
        }.forEach { (id, title, sub) ->
            hits.add(
                UniversalSearchHit(
                    id = "setting_$id",
                    category = if (id == "downloads") SearchResultCategory.LOCAL_FILE else SearchResultCategory.LAUNCHER_SETTING,
                    title = title,
                    subtitle = sub,
                    payload = id
                )
            )
        }

        if (q.isNotBlank()) {
            // 3. Contacts (only with explicit runtime READ_CONTACTS permission)
            if (hasContactsPermission()) {
                queryContacts(q).forEachIndexed { idx, (name, phone) ->
                    hits.add(
                        UniversalSearchHit(
                            id = "contact_$idx",
                            category = SearchResultCategory.CONTACT,
                            title = name,
                            subtitle = phone.ifBlank { "Saved contact" },
                            payload = phone.ifBlank { name }
                        )
                    )
                }
            }

            // 4. Calendar events (only with explicit runtime READ_CALENDAR permission)
            if (hasCalendarPermission()) {
                queryCalendar(q).forEach { (id, title, subtitle) ->
                    hits.add(
                        UniversalSearchHit(
                            id = "cal_$id",
                            category = SearchResultCategory.CALENDAR,
                            title = title,
                            subtitle = subtitle,
                            payload = id.toString()
                        )
                    )
                }
            }

            // 5. Aqua AI Conversations & Saved Memories
            conversations.filter {
                it.title.lowercase().contains(lower) || it.summary.lowercase().contains(lower)
            }.take(4).forEach { conv ->
                hits.add(
                    UniversalSearchHit(
                        id = "conv_${conv.id}",
                        category = SearchResultCategory.CONVERSATION,
                        title = conv.title,
                        subtitle = conv.summary.ifBlank { "${conv.messageCount} messages • ${conv.modelUsed}" },
                        payload = conv.id
                    )
                )
            }
            memories.filter {
                it.title.lowercase().contains(lower) || it.content.lowercase().contains(lower)
            }.take(3).forEach { mem ->
                hits.add(
                    UniversalSearchHit(
                        id = "mem_${mem.id}",
                        category = SearchResultCategory.CONVERSATION,
                        title = "Memory: ${mem.title}",
                        subtitle = mem.content.take(90),
                        payload = mem.id
                    )
                )
            }

            // 6. Browser Tabs
            browserTabs.filter {
                it.title.lowercase().contains(lower) || it.url.lowercase().contains(lower)
            }.take(4).forEach { tab ->
                hits.add(
                    UniversalSearchHit(
                        id = "tab_${tab.id}",
                        category = SearchResultCategory.BROWSER_TAB,
                        title = tab.title.ifBlank { tab.url },
                        subtitle = tab.url,
                        payload = tab.url
                    )
                )
            }

            // 7. Web & Internet Fallback Action via existing Aqua Browser / Web Search
            hits.add(
                UniversalSearchHit(
                    id = "web_aqua_search",
                    category = SearchResultCategory.WEB,
                    title = "Search Web in Aqua Browser: \"$q\"",
                    subtitle = "Uses integrated Aqua Search & DuckDuckGo engine",
                    payload = q
                )
            )
            hits.add(
                UniversalSearchHit(
                    id = "ai_ask_aqua",
                    category = SearchResultCategory.CONVERSATION,
                    title = "Ask Aqua AI: \"$q\"",
                    subtitle = "Send prompt directly to Aqua AI Launcher Control",
                    payload = q
                )
            )
        }

        hits
    }

    fun openDownloadsApp(): Result<String> = runCatching {
        val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
        "Opened Android Downloads."
    }

    private fun queryContacts(query: String): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        runCatching {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            appContext.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                arrayOf("%$query%"),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { c ->
                while (c.moveToNext() && list.size < 5) {
                    val name = c.getString(0).orEmpty()
                    val num = c.getString(1).orEmpty()
                    if (name.isNotBlank()) list.add(name to num)
                }
            }
        }
        return list
    }

    private fun queryCalendar(query: String): List<Triple<Long, String, String>> {
        val list = mutableListOf<Triple<Long, String, String>>()
        runCatching {
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.EVENT_LOCATION
            )
            val selection = "${CalendarContract.Events.TITLE} LIKE ?"
            appContext.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                arrayOf("%$query%"),
                "${CalendarContract.Events.DTSTART} DESC"
            )?.use { c ->
                while (c.moveToNext() && list.size < 5) {
                    val id = c.getLong(0)
                    val title = c.getString(1).orEmpty()
                    val loc = c.getString(2).orEmpty().ifBlank { "Device Calendar" }
                    if (title.isNotBlank()) list.add(Triple(id, title, loc))
                }
            }
        }
        return list
    }
}
