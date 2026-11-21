package com.example.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Registers Aqua AI's categorized Android notification channels:
 * Task Completions, Pending Approvals, Drive Sync Alerts, and Scheduled Reminders.
 */
object AquaNotificationChannels {
    const val CHANNEL_TASK_COMPLETIONS = "aqua_task_completions"
    const val CHANNEL_PENDING_APPROVALS = "aqua_pending_approvals"
    const val CHANNEL_SYNC_ALERTS = "aqua_sync_alerts"
    const val CHANNEL_REMINDERS = "aqua_reminders"

    fun ensureAllChannels(context: Context): List<String> {
        val ids = listOf(
            CHANNEL_TASK_COMPLETIONS,
            CHANNEL_PENDING_APPROVALS,
            CHANNEL_SYNC_ALERTS,
            CHANNEL_REMINDERS
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return ids
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return ids

        val channels = listOf(
            NotificationChannel(
                CHANNEL_TASK_COMPLETIONS,
                "Task Completions",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Completion updates for autonomous agent plans and tool runs."
            },
            NotificationChannel(
                CHANNEL_PENDING_APPROVALS,
                "Pending Approvals",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Explicit Risk Tier 2/3 action confirmations requiring user authorization."
            },
            NotificationChannel(
                CHANNEL_SYNC_ALERTS,
                "Drive Sync Alerts",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Google Drive offline queue flushes and version conflict notifications."
            },
            NotificationChannel(
                CHANNEL_REMINDERS,
                "Scheduled Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Automation Studio scheduled reminders and triggers."
            }
        )
        channels.forEach { ch -> nm.createNotificationChannel(ch) }
        return ids
    }
}
