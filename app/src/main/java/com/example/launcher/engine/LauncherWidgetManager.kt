package com.example.launcher.engine

import android.Manifest
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.CalendarContract
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.example.launcher.model.DeviceCalendarEventItem
import com.example.launcher.model.WeatherSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LauncherWidgetManager(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    private val appContext = context.applicationContext
    private val appWidgetManager: AppWidgetManager? by lazy {
        runCatching { AppWidgetManager.getInstance(appContext) }.getOrNull()
    }
    private val appWidgetHost: AppWidgetHost by lazy {
        AppWidgetHost(appContext, AQUA_APP_WIDGET_HOST_ID)
    }

    fun startListening() {
        runCatching { appWidgetHost.startListening() }
    }

    fun stopListening() {
        runCatching { appWidgetHost.stopListening() }
    }

    fun getInstalledSystemWidgets(): List<AppWidgetProviderInfo> {
        return runCatching {
            appWidgetManager?.getInstalledProviders().orEmpty()
                .sortedBy { it.loadLabel(appContext.packageManager).lowercase() }
        }.getOrDefault(emptyList())
    }

    fun allocateWidgetId(): Int? {
        return runCatching { appWidgetHost.allocateAppWidgetId() }.getOrNull()
    }

    fun deleteWidgetId(appWidgetId: Int) {
        runCatching { appWidgetHost.deleteAppWidgetId(appWidgetId) }
    }

    fun bindWidgetIfAllowed(appWidgetId: Int, info: AppWidgetProviderInfo, options: Bundle? = null): Boolean {
        val mgr = appWidgetManager ?: return false
        return runCatching {
            if (options != null) {
                mgr.bindAppWidgetIdIfAllowed(appWidgetId, info.provider, options)
            } else {
                mgr.bindAppWidgetIdIfAllowed(appWidgetId, info.provider)
            }
        }.getOrDefault(false)
    }

    fun buildBindRequestIntent(appWidgetId: Int, info: AppWidgetProviderInfo): Intent {
        return Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
        }
    }

    fun createHostView(hostContext: Context, appWidgetId: Int): AppWidgetHostView? {
        val mgr = appWidgetManager ?: return null
        val info = runCatching { mgr.getAppWidgetInfo(appWidgetId) }.getOrNull() ?: return null
        return runCatching {
            appWidgetHost.createView(hostContext, appWidgetId, info).apply {
                setAppWidget(appWidgetId, info)
            }
        }.getOrNull()
    }

    fun getWidgetInfo(appWidgetId: Int): AppWidgetProviderInfo? {
        return runCatching { appWidgetManager?.getAppWidgetInfo(appWidgetId) }.getOrNull()
    }

    suspend fun fetchLiveWeather(cityQuery: String, useDeviceLocationIfPermitted: Boolean): Result<WeatherSnapshot> =
        withContext(Dispatchers.IO) {
            runCatching {
                var lat = 37.7749
                var lon = -122.4194
                var resolvedName = cityQuery.trim().ifBlank { "San Francisco" }

                val locUsed = if (useDeviceLocationIfPermitted && hasCoarseLocationPermission()) {
                    readLastKnownLocation()?.let { loc ->
                        lat = loc.latitude
                        lon = loc.longitude
                        resolvedName = "Current Area (%.2f, %.2f)".format(lat, lon)
                        true
                    } ?: false
                } else false

                if (!locUsed) {
                    val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=${Uri.encode(resolvedName)}&count=1&language=en&format=json"
                    val geoReq = Request.Builder().url(geoUrl).header("User-Agent", "AquaLauncherAndroid/1.0").build()
                    httpClient.newCall(geoReq).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val obj = JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" })
                            val first = obj.optJSONArray("results")?.optJSONObject(0)
                            if (first != null) {
                                lat = first.optDouble("latitude", lat)
                                lon = first.optDouble("longitude", lon)
                                val name = first.optString("name", resolvedName)
                                val country = first.optString("country_code", "")
                                resolvedName = if (country.isNotBlank()) "$name, $country" else name
                            }
                        }
                    }
                }

                val weatherUrl =
                    "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                        "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,weather_code,wind_speed_10m"
                val weatherReq = Request.Builder().url(weatherUrl).header("User-Agent", "AquaLauncherAndroid/1.0").build()
                httpClient.newCall(weatherReq).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("Weather service unavailable (HTTP ${resp.code})")
                    }
                    val root = JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" })
                    val current = root.optJSONObject("current")
                        ?: throw IllegalStateException("Weather payload missing current conditions.")
                    val code = current.optInt("weather_code", 0)
                    WeatherSnapshot(
                        locationName = resolvedName,
                        temperatureC = current.optDouble("temperature_2m", 0.0),
                        apparentTempC = current.optDouble("apparent_temperature", 0.0),
                        windKmh = current.optDouble("wind_speed_10m", 0.0),
                        humidityPercent = current.optInt("relative_humidity_2m", 0),
                        conditionLabel = decodeWmoWeatherCode(code),
                        weatherCode = code,
                        isDay = current.optInt("is_day", 1) == 1,
                        fetchedAtMs = System.currentTimeMillis()
                    )
                }
            }
        }

    fun hasCalendarPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun hasCoarseLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun queryUpcomingCalendarEvents(limit: Int = 5): List<DeviceCalendarEventItem> =
        withContext(Dispatchers.IO) {
            if (!hasCalendarPermission()) return@withContext emptyList()
            val now = System.currentTimeMillis()
            val horizon = now + 7L * 24L * 60L * 60L * 1000L
            val results = mutableListOf<DeviceCalendarEventItem>()
            runCatching {
                val projection = arrayOf(
                    CalendarContract.Events._ID,
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.EVENT_LOCATION
                )
                val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
                val args = arrayOf(now.toString(), horizon.toString())
                appContext.contentResolver.query(
                    CalendarContract.Events.CONTENT_URI,
                    projection,
                    selection,
                    args,
                    "${CalendarContract.Events.DTSTART} ASC"
                )?.use { cursor ->
                    while (cursor.moveToNext() && results.size < limit) {
                        val id = cursor.getLong(0)
                        val title = cursor.getString(1).orEmpty().ifBlank { "Untitled Event" }
                        val start = cursor.getLong(2)
                        val loc = cursor.getString(3).orEmpty()
                        results.add(DeviceCalendarEventItem(id, title, start, loc))
                    }
                }
            }
            results
        }

    fun dispatchMediaKey(keyCode: Int): Boolean {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return runCatching {
            val now = SystemClock.uptimeMillis()
            audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
            true
        }.getOrDefault(false)
    }

    @Suppress("MissingPermission")
    private fun readLastKnownLocation(): Location? {
        if (!hasCoarseLocationPermission()) return null
        return runCatching {
            val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
            lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        }.getOrNull()
    }

    private fun decodeWmoWeatherCode(code: Int): String = when (code) {
        0 -> "Clear Sky"
        1, 2 -> "Partly Cloudy"
        3 -> "Overcast"
        45, 48 -> "Foggy"
        51, 53, 55 -> "Drizzle"
        61, 63, 65 -> "Rain"
        71, 73, 75 -> "Snow"
        80, 81, 82 -> "Rain Showers"
        95, 96, 99 -> "Thunderstorm"
        else -> "Fair"
    }

    companion object {
        const val AQUA_APP_WIDGET_HOST_ID = 0x41515541
    }
}
