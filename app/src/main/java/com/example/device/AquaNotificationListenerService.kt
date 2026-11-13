package com.example.device

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CapturedNotification(
    val packageName: String,
    val title: String,
    val text: String,
    val postTime: Long
)

class AquaNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        _isConnected.value = true
        refreshNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        _isConnected.value = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refreshNotifications()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshNotifications()
    }

    private fun refreshNotifications() {
        try {
            val active = activeNotifications ?: return
            val list = active.take(25).map { sbn ->
                val extras = sbn.notification.extras
                val title = extras.getCharSequence("android.title")?.toString().orEmpty()
                val text = extras.getCharSequence("android.text")?.toString().orEmpty()
                CapturedNotification(
                    packageName = sbn.packageName,
                    title = title,
                    text = text,
                    postTime = sbn.postTime
                )
            }
            _notifications.value = list
        } catch (_: Exception) {
        }
    }

    companion object {
        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _notifications = MutableStateFlow<List<CapturedNotification>>(emptyList())
        val notifications: StateFlow<List<CapturedNotification>> = _notifications.asStateFlow()
    }
}
