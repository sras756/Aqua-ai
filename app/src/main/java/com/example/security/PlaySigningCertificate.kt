package com.example.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.example.BuildConfig
import java.security.MessageDigest

private const val TAG = "PlaySigningCertificate"
private const val EXPECTED_CERTIFICATE_SHA256 =
    "d466b305c7b897ff3b0a5cbae0deaca774fd70c43e4fb3a98d6b97e275cd65a2"

fun hasOfficialPlaySigningCertificate(context: Context): Boolean {
    if (BuildConfig.DEBUG) return true

    val packageInfo = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES
            )
        }
    } catch (e: PackageManager.NameNotFoundException) {
        Log.e(TAG, "Unable to inspect this app's signing certificate.", e)
        return false
    }

    val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val signingInfo = packageInfo.signingInfo
        if (signingInfo == null || signingInfo.hasMultipleSigners()) {
            Log.e(TAG, "App does not have the expected single Play signing certificate.")
            return false
        }
        signingInfo.apkContentsSigners
    } else {
        @Suppress("DEPRECATION")
        packageInfo.signatures
    }

    val signer = signatures?.singleOrNull()
    if (signer == null) {
        Log.e(TAG, "App does not have exactly one signing certificate.")
        return false
    }

    val actualDigest = MessageDigest.getInstance("SHA-256")
        .digest(signer.toByteArray())
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    if (!MessageDigest.isEqual(
            actualDigest.toByteArray(),
            EXPECTED_CERTIFICATE_SHA256.toByteArray()
        )
    ) {
        Log.e(TAG, "App signing certificate does not match the Google Play App Signing certificate.")
        return false
    }
    return true
}
