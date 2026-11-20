package com.example.device

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ScreenUiElement(
    val text: String,
    val contentDescription: String,
    val viewId: String,
    val clickable: Boolean,
    val bounds: Rect
)

data class CapturedScreenSnapshot(
    val packageName: String,
    val elements: List<ScreenUiElement>,
    val capturedAtMs: Long = System.currentTimeMillis()
)

class AquaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()?.trim()
        if (!pkg.isNullOrBlank()) {
            _activePackageName.value = pkg
        }
    }

    override fun onInterrupt() {
        // No persistent speech output owned here
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        _isConnected.value = false
        super.onDestroy()
    }

    fun captureVisibleScreen(maxNodes: Int = 60): CapturedScreenSnapshot? {
        if (!hasAcceptedDisclosure(this)) return null
        val root = rootInActiveWindow ?: return null
        val collected = mutableListOf<ScreenUiElement>()
        collectNodes(root, collected, maxNodes)
        val snapshot = CapturedScreenSnapshot(
            packageName = root.packageName?.toString().orEmpty(),
            elements = collected
        )
        _latestSnapshot.value = snapshot
        return snapshot
    }

    fun executeNavigationAction(action: String): Boolean {
        if (!hasAcceptedDisclosure(this)) return false
        return when (action.lowercase()) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            else -> false
        }
    }

    fun clickVisibleText(targetText: String): Boolean {
        if (!hasAcceptedDisclosure(this) || targetText.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        val matches = root.findAccessibilityNodeInfosByText(targetText.trim()).orEmpty()
        for (node in matches) {
            if (node.isPassword) continue
            var cursor: AccessibilityNodeInfo? = node
            var depth = 0
            while (cursor != null && depth < 4) {
                if (cursor.isClickable && cursor.isEnabled) {
                    return cursor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                cursor = cursor.parent
                depth++
            }
        }
        return false
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo?,
        out: MutableList<ScreenUiElement>,
        maxNodes: Int
    ) {
        if (node == null || out.size >= maxNodes) return
        // Security: never extract password or hidden credential fields
        if (node.isPassword) return

        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if (text.isNotEmpty() || desc.isNotEmpty()) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            out.add(
                ScreenUiElement(
                    text = text.take(240),
                    contentDescription = desc.take(180),
                    viewId = node.viewIdResourceName.orEmpty(),
                    clickable = node.isClickable,
                    bounds = rect
                )
            )
        }
        for (i in 0 until node.childCount) {
            if (out.size >= maxNodes) break
            collectNodes(node.getChild(i), out, maxNodes)
        }
    }

    companion object {
        private const val PREFS_NAME = "aqua_accessibility_disclosure_prefs"
        private const val KEY_DISCLOSURE_ACCEPTED = "prominent_disclosure_accepted"

        @Volatile
        private var instance: AquaAccessibilityService? = null

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _activePackageName = MutableStateFlow("")
        val activePackageName: StateFlow<String> = _activePackageName.asStateFlow()

        private val _latestSnapshot = MutableStateFlow<CapturedScreenSnapshot?>(null)
        val latestSnapshot: StateFlow<CapturedScreenSnapshot?> = _latestSnapshot.asStateFlow()

        fun hasAcceptedDisclosure(context: Context): Boolean {
            return context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_DISCLOSURE_ACCEPTED, false)
        }

        fun setDisclosureAccepted(context: Context, accepted: Boolean) {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DISCLOSURE_ACCEPTED, accepted)
                .apply()
        }

        fun getActiveInstance(): AquaAccessibilityService? = instance
    }
}
