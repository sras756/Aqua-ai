package com.example.device

import android.content.Context
import com.example.core.security.PermissionDomain
import com.example.core.security.PermissionPolicyStore

/**
 * On-demand screen intelligence controller wrapping opt-in AquaAccessibilityService.
 * Never performs continuous background recording; enforces explicit disclosure and permission policy.
 */
class AquaScreenIntelligence(
    private val context: Context,
    private val permissionPolicyStore: PermissionPolicyStore
) {
    fun captureScreenSummaryOnDemand(): Result<String> {
        if (!permissionPolicyStore.isAllowed(PermissionDomain.READ_ACCESS) ||
            !permissionPolicyStore.isAllowed(PermissionDomain.DEVICE_CONTROL)
        ) {
            return Result.failure(
                SecurityException("Enable both Read Access and Device Control in Security Center to inspect the active screen.")
            )
        }
        val svc = AquaAccessibilityService.getActiveInstance()
        if (svc == null || !AquaAccessibilityService.hasAcceptedDisclosure(context)) {
            return Result.failure(
                IllegalStateException("Aqua Accessibility Screen Reader is opt-in and currently disabled. Enable it in Device Control after accepting the disclosure.")
            )
        }
        val snapshot = svc.captureVisibleScreen(maxNodes = 60)
            ?: return Result.failure(IllegalStateException("No active screen window content available right now."))
        val formatted = buildString {
            appendLine("Active Package: ${snapshot.packageName}")
            snapshot.elements.forEach { el ->
                val line = listOf(el.text, el.contentDescription).filter { it.isNotBlank() }.joinToString(" | ")
                if (line.isNotBlank()) appendLine("- $line")
            }
        }
        return Result.success(formatted.take(4000))
    }
}
