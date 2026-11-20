package com.example.wallet.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Enforces `WindowManager.LayoutParams.FLAG_SECURE` while sensitive wallet, seed phrase,
 * or key-export screens are visible, blocking OS screenshots and unencrypted recents thumbnails.
 */
object WalletScreenSecurityHelper {

    tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    fun applyFlagSecure(activity: Activity?, enabled: Boolean): Boolean {
        val window = activity?.window ?: return false
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        return true
    }
}

@Composable
fun SecureWalletWindowEffect(enabled: Boolean = true) {
    val context = LocalContext.current
    DisposableEffect(context, enabled) {
        val activity = with(WalletScreenSecurityHelper) { context.findActivity() }
        WalletScreenSecurityHelper.applyFlagSecure(activity, enabled)
        onDispose {
            WalletScreenSecurityHelper.applyFlagSecure(activity, false)
        }
    }
}
