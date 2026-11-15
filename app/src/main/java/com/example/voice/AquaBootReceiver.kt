package com.example.voice

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

/**
 * Play-Policy & Android 14+ compliant boot/update receiver.
 * Android 14+ blocks direct background starts of `foregroundServiceType="microphone"` from BOOT_COMPLETED,
 * so when the user previously enabled Background App & Mic, this receiver posts a 1-tap notification
 * whose PendingIntent starts `AquaWakeWordService` with an official user-initiated FGS exemption.
 */
class AquaBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        if (!AquaWakeWordService.isSavedBackgroundEnabled(ctx)) return
        if (!AquaWakeWordService.hasMicPermission(ctx)) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BOOT_CHANNEL_ID,
                "Aqua Background Resume",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Allows 1-tap resume of Aqua AI background mic after device restart."
            }
            manager.createNotificationChannel(channel)
        }

        val wakePhrase = AquaWakeWordService.savedWakePhrase(ctx)
        val resumeServiceIntent = Intent(ctx, AquaWakeWordService::class.java).apply {
            this.action = AquaWakeWordService.ACTION_START
            putExtra(AquaWakeWordService.EXTRA_WAKE_PHRASE, wakePhrase)
        }
        val resumePendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                ctx,
                101,
                resumeServiceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                ctx,
                101,
                resumeServiceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val openAppIntent = PendingIntent.getActivity(
            ctx,
            102,
            Intent(ctx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(ctx, BOOT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Resume Aqua Background App & Mic")
            .setContentText("Tap to resume listening for \"$wakePhrase\" after restart.")
            .setAutoCancel(true)
            .setContentIntent(resumePendingIntent)
            .addAction(android.R.drawable.ic_media_play, "Resume Mic", resumePendingIntent)
            .addAction(android.R.drawable.ic_menu_view, "Open App", openAppIntent)
            .build()

        runCatching { manager.notify(BOOT_NOTIFICATION_ID, notification) }
    }

    private companion object {
        const val BOOT_CHANNEL_ID = "aqua_boot_resume_channel"
        const val BOOT_NOTIFICATION_ID = 4043
    }
}
