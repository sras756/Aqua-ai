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
    val postTime: Long,
    val key: String = "",
    val isClearable: Boolean = true
)

class AquaNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        _isConnected.value = true
        refreshNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) instance = null
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
                    postTime = sbn.postTime,
                    key = sbn.key.orEmpty(),
                    isClearable = sbn.isClearable
                )
            }
            _notifications.value = list
        } catch (_: Exception) {
        }
    }

    companion object {
        @Volatile
        private var instance: AquaNotificationListenerService? = null

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _notifications = MutableStateFlow<List<CapturedNotification>>(emptyList())
        val notifications: StateFlow<List<CapturedNotification>> = _notifications.asStateFlow()

        fun dismissNotification(key: String): Boolean {
            val service = instance ?: return false
            if (key.isBlank()) return false
            return runCatching {
                service.cancelNotification(key)
                true
            }.getOrDefault(false)
        }
    }
}
