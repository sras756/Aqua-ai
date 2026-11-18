package com.example.security

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.example.R
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.auth.FirebaseAuth
import java.security.MessageDigest

data class SignatureCheckReport(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val signingSha1: String,
    val signingSha256: String,
    val expectedRegisteredSha1: String,
    val sha1MatchesRegisteredOAuth: Boolean,
    val webClientIdConfigured: Boolean,
    val playServicesAvailable: Boolean,
    val playServicesVersion: String,
    val playServicesStatusLabel: String,
    val googleAuthProviderLinked: Boolean,
    val signedInEmail: String,
    val overallStatusLabel: String,
    val certificateCount: Int,
    val hasMultipleSigners: Boolean
) {
    val isTrustedForGoogleSignIn: Boolean
        get() = signingSha1.isNotBlank() && webClientIdConfigured && playServicesAvailable
}

/**
 * Verifies the live Android APK signing certificate fingerprints (SHA-1 & SHA-256),
 * Google Play Services availability, Web OAuth Client ID presence (`default_web_client_id`),
 * and Firebase Auth Google provider linkage.
 */
object GoogleSignatureVerifier {

    const val REGISTERED_FIREBASE_SHA1 =
        "5B:FA:1D:7E:BF:7D:72:49:B5:AF:83:9C:1B:E6:98:04:65:45:B9:07"

    @Suppress("DEPRECATION")
    fun verifyAppSignature(context: Context): SignatureCheckReport {
        val appContext = context.applicationContext
        val pm = appContext.packageManager
        val pkgName = appContext.packageName

        var versionName = "1.0"
        var versionCode = 1L
        val rawSignatures = mutableListOf<Signature>()
        var multipleSigners = false

        try {
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(pkgName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                pm.getPackageInfo(pkgName, PackageManager.GET_SIGNATURES)
            }
            versionName = pkgInfo.versionName ?: "1.0"
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode
            } else {
                pkgInfo.versionCode.toLong()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = pkgInfo.signingInfo
                if (signingInfo != null) {
                    multipleSigners = signingInfo.hasMultipleSigners()
                    val certs = if (multipleSigners) {
                        signingInfo.apkContentsSigners
                    } else {
                        signingInfo.signingCertificateHistory
                    }
                    if (certs != null) {
                        rawSignatures.addAll(certs)
                    }
                }
            } else {
                pkgInfo.signatures?.let { rawSignatures.addAll(it) }
            }
        } catch (_: Exception) {
            // Package info unavailable on restricted environment; handled cleanly via empty hashes.
        }

        val primaryCertBytes = rawSignatures.firstOrNull()?.toByteArray()
        val sha1 = primaryCertBytes?.let { computeFingerprint(it, "SHA-1") }.orEmpty()
        val sha256 = primaryCertBytes?.let { computeFingerprint(it, "SHA-256") }.orEmpty()

        val webClientId = runCatching {
            appContext.getString(R.string.default_web_client_id).trim()
        }.getOrDefault("")
        val webClientConfigured = webClientId.endsWith(".apps.googleusercontent.com")

        val gmsAvailability = GoogleApiAvailability.getInstance()
        val gmsCode = runCatching { gmsAvailability.isGooglePlayServicesAvailable(appContext) }
            .getOrDefault(ConnectionResult.SERVICE_MISSING)
        val gmsAvailable = gmsCode == ConnectionResult.SUCCESS
        val gmsStatusLabel = when (gmsCode) {
            ConnectionResult.SUCCESS -> "Verified & Up to Date"
            ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED -> "Update Required"
            ConnectionResult.SERVICE_UPDATING -> "Updating"
            ConnectionResult.SERVICE_DISABLED -> "Disabled"
            ConnectionResult.SERVICE_INVALID -> "Signature Invalid"
            else -> "Unavailable ($gmsCode)"
        }
        val gmsPkgVersion = runCatching {
            val gmsInfo = pm.getPackageInfo("com.google.android.gms", 0)
            gmsInfo.versionName ?: "Installed"
        }.getOrDefault(if (gmsAvailable) "Active" else "Not installed")

        val currentUser = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()
        val googleProviderLinked = currentUser?.providerData
            ?.any { it.providerId == "google.com" } == true
        val signedInEmail = currentUser?.email.orEmpty()

        val sha1Match = sha1.equals(REGISTERED_FIREBASE_SHA1, ignoreCase = true)
        val overallStatus = when {
            sha1.isBlank() && !webClientConfigured -> "Signature Metadata Unavailable"
            sha1Match && webClientConfigured && gmsAvailable -> "Google Signature Verified (SHA-1 Matched)"
            sha1.isNotBlank() && webClientConfigured && gmsAvailable -> "Signed APK (Custom / Release Keystore)"
            !webClientConfigured -> "Missing OAuth Web Client ID"
            !gmsAvailable -> "Google Play Services Check Required"
            else -> "Certificate Detected"
        }

        return SignatureCheckReport(
            packageName = pkgName,
            versionName = versionName,
            versionCode = versionCode,
            signingSha1 = sha1.ifBlank { "Unavailable" },
            signingSha256 = sha256.ifBlank { "Unavailable" },
            expectedRegisteredSha1 = REGISTERED_FIREBASE_SHA1,
            sha1MatchesRegisteredOAuth = sha1Match,
            webClientIdConfigured = webClientConfigured,
            playServicesAvailable = gmsAvailable,
            playServicesVersion = gmsPkgVersion,
            playServicesStatusLabel = gmsStatusLabel,
            googleAuthProviderLinked = googleProviderLinked,
            signedInEmail = signedInEmail,
            overallStatusLabel = overallStatus,
            certificateCount = rawSignatures.size,
            hasMultipleSigners = multipleSigners
        )
    }

    fun computeFingerprint(bytes: ByteArray, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm).digest(bytes)
        return digest.joinToString(":") { b -> "%02X".format(b.toInt() and 0xFF) }
    }
}
