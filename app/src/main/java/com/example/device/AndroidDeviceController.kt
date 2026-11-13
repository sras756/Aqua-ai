package com.example.device

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.content.ComponentName

data class DeviceTelemetry(
    val batteryPercent: Int,
    val isCharging: Boolean,
    val isOnline: Boolean,
    val connectionType: String,
    val mediaVolumePercent: Int,
    val flashlightAvailable: Boolean,
    val flashlightEnabled: Boolean,
    val notificationAccessGranted: Boolean
)

class AndroidDeviceController(private val context: Context) {

    private var torchEnabled = false

    fun getTelemetry(): DeviceTelemetry {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(activeNetwork)
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val connType = when {
            caps == null -> "Offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Connected"
        }

        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val currentVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val volPct = (currentVol * 100) / maxVol

        val cameraId = findFlashCameraId()

        return DeviceTelemetry(
            batteryPercent = pct,
            isCharging = charging,
            isOnline = online,
            connectionType = connType,
            mediaVolumePercent = volPct,
            flashlightAvailable = cameraId != null,
            flashlightEnabled = torchEnabled,
            notificationAccessGranted = isNotificationAccessEnabled()
        )
    }

    fun isNotificationAccessEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val expected = ComponentName(context, AquaNotificationListenerService::class.java).flattenToString()
        return flat.split(":").any { it.equals(expected, ignoreCase = true) }
    }

    fun getInstalledLaunchableApps(): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return pm.queryIntentActivities(intent, 0)
            .map { resolveInfo ->
                val label = resolveInfo.loadLabel(pm).toString()
                val pkg = resolveInfo.activityInfo.packageName
                label to pkg
            }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
    }

    fun launchApp(appNameOrPackage: String): Result<String> = runCatching {
        val pm = context.packageManager
        val direct = pm.getLaunchIntentForPackage(appNameOrPackage)
        if (direct != null) {
            direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(direct)
            return@runCatching "Launched package $appNameOrPackage."
        }
        val query = appNameOrPackage.trim().lowercase()
        val match = getInstalledLaunchableApps().firstOrNull { (label, pkg) ->
            label.lowercase() == query || label.lowercase().contains(query) || pkg.lowercase().contains(query)
        } ?: throw IllegalArgumentException("No launchable app matching '$appNameOrPackage' is installed.")

        val intent = pm.getLaunchIntentForPackage(match.second)
            ?: throw IllegalStateException("App ${match.first} does not expose a launcher activity.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        "Launched ${match.first} (${match.second})."
    }

    fun setMediaVolumePercent(percent: Int): Result<String> = runCatching {
        val clamped = percent.coerceIn(0, 100)
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = ((clamped / 100.0) * maxVol).toInt().coerceIn(0, maxVol)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        "Set media volume to $clamped% ($target/$maxVol)."
    }

    fun setAppWindowBrightness(activity: Activity?, percent: Int): Result<String> = runCatching {
        val clamped = percent.coerceIn(5, 100)
        val targetActivity = activity ?: throw IllegalStateException(
            "In-app window brightness requires an active foreground Activity. Opening Display Settings instead."
        )
        targetActivity.runOnUiThread {
            val lp = targetActivity.window.attributes
            lp.screenBrightness = clamped / 100f
            targetActivity.window.attributes = lp
        }
        "Adjusted Aqua AI window brightness to $clamped%. (System-wide brightness requires manual Display Settings confirmation.)"
    }

    fun toggleFlashlight(enable: Boolean? = null): Result<String> = runCatching {
        val camMgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = findFlashCameraId()
            ?: throw IllegalStateException("No camera flash unit available on this device.")
        val next = enable ?: !torchEnabled
        camMgr.setTorchMode(cameraId, next)
        torchEnabled = next
        if (next) "Flashlight turned ON." else "Flashlight turned OFF."
    }

    fun setAlarm(hour: Int, minute: Int, label: String): Result<String> = runCatching {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
            putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
            putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "Aqua AI Alarm" })
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        "Opened Alarm app to confirm alarm for %02d:%02d (%s).".format(hour, minute, label)
    }

    fun createCalendarReminder(title: String, description: String): Result<String> = runCatching {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title.ifBlank { "Aqua AI Reminder" })
            putExtra(CalendarContract.Events.DESCRIPTION, description)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        "Opened Calendar event composer for '$title'."
    }

    fun openDialer(phoneNumber: String): Result<String> = runCatching {
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:${Uri.encode(phoneNumber.trim())}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        "Opened phone dialer with $phoneNumber."
    }

    fun prepareSmsMessage(recipient: String, body: String): Result<String> = runCatching {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:${Uri.encode(recipient.trim())}")
            putExtra("sms_body", body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        "Prepared SMS composer for $recipient."
    }

    fun openAndroidSettings(section: String): Result<String> = runCatching {
        val action = when (section.lowercase()) {
            "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS
            "notifications", "notification_listener" -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            "apps", "permissions" -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        val intent = Intent(action).apply {
            if (action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                data = Uri.fromParts("package", context.packageName, null)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        "Opened Android Settings ($section)."
    }

    fun readRecentNotifications(): List<CapturedNotification> {
        return AquaNotificationListenerService.notifications.value
    }

    private fun findFlashCameraId(): String? {
        return try {
            val camMgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            camMgr.cameraIdList.firstOrNull { id ->
                camMgr.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) {
            null
        }
    }
}
