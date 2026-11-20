package com.example.core.security

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Six centralized, independently toggleable & auditable security permission domains
 * governing all Aqua AI and autonomous agent actions.
 */
enum class PermissionDomain(
    val id: String,
    val title: String,
    val description: String,
    val defaultEnabled: Boolean
) {
    READ_ACCESS(
        id = "read_access",
        title = "Read Access",
        description = "Authorized local files, Aqua Memory, notifications, and on-demand screen context.",
        defaultEnabled = true
    ),
    WRITE_ACCESS(
        id = "write_access",
        title = "Write Access",
        description = "Creating or editing AI Workspace documents, notes, bookmarks, and authorized files.",
        defaultEnabled = true
    ),
    DEVICE_CONTROL(
        id = "device_control",
        title = "Device Control",
        description = "App launches, media playback, settings shortcuts, alarms, and opt-in accessibility actions.",
        defaultEnabled = true
    ),
    CLOUD_STORAGE(
        id = "cloud_storage",
        title = "Cloud Storage & Connectors",
        description = "Google Drive Aqua AI/ synchronization and optional Google ecosystem connectors.",
        defaultEnabled = true
    ),
    BACKGROUND_EXECUTION(
        id = "background_execution",
        title = "Background Execution",
        description = "Scheduled WorkManager automations, background sync, and opt-in foreground voice listeners.",
        defaultEnabled = true
    ),
    FINANCIAL_AUTHORIZATION(
        id = "financial_authorization",
        title = "Financial Authorization",
        description = "Non-custodial wallet read telemetry and biometric-gated transaction draft preparation.",
        defaultEnabled = true
    );

    companion object {
        fun fromId(id: String): PermissionDomain? = entries.firstOrNull { it.id == id }
    }
}

class PermissionPolicyStore(
    context: Context,
    private val userId: String
) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _domainStates = MutableStateFlow(loadAll())
    val domainStates: StateFlow<Map<PermissionDomain, Boolean>> = _domainStates.asStateFlow()

    private fun keyFor(domain: PermissionDomain): String =
        "${userId.ifBlank { "default" }}_perm_${domain.id}"

    private fun loadAll(): Map<PermissionDomain, Boolean> {
        return PermissionDomain.entries.associateWith { domain ->
            prefs.getBoolean(keyFor(domain), domain.defaultEnabled)
        }
    }

    fun isAllowed(domain: PermissionDomain): Boolean {
        return _domainStates.value[domain] ?: domain.defaultEnabled
    }

    fun setAllowed(domain: PermissionDomain, enabled: Boolean) {
        prefs.edit().putBoolean(keyFor(domain), enabled).apply()
        _domainStates.value = _domainStates.value.toMutableMap().apply {
            put(domain, enabled)
        }
    }

    fun requireAllowed(domain: PermissionDomain, actionName: String) {
        if (!isAllowed(domain)) {
            throw SecurityException(
                "Permission domain '${domain.title}' is disabled in Security Center (blocked '$actionName')."
            )
        }
    }

    fun resetDefaults() {
        val editor = prefs.edit()
        PermissionDomain.entries.forEach { domain ->
            editor.putBoolean(keyFor(domain), domain.defaultEnabled)
        }
        editor.apply()
        _domainStates.value = PermissionDomain.entries.associateWith { it.defaultEnabled }
    }

    companion object {
        private const val PREFS_NAME = "aqua_security_policy_prefs"
    }
}
