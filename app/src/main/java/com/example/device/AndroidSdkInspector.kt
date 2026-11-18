package com.example.device

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.example.security.GoogleSignatureVerifier
import com.example.security.SignatureCheckReport

data class HardwareFeatureStatus(
    val featureKey: String,
    val label: String,
    val supported: Boolean
)

data class AndroidSdkReport(
    val sdkInt: Int,
    val releaseVersion: String,
    val codename: String,
    val compileSdkLabel: String,
    val targetSdkVersion: Int,
    val minSdkVersion: Int,
    val securityPatch: String,
    val manufacturer: String,
    val model: String,
    val device: String,
    val hardware: String,
    val supportedAbis: List<String>,
    val cpuCores: Int,
    val maxHeapMb: Long,
    val allocatedHeapMb: Long,
    val features: List<HardwareFeatureStatus>,
    val signatureReport: SignatureCheckReport
) {
    val apiBadge: String
        get() = "API $sdkInt • Android $releaseVersion"
}

/**
 * Live Android SDK, ABI, hardware capability matrix, JVM runtime, and Google Signature inspector.
 * Uses real Android Build & PackageManager APIs with zero simulated telemetry.
 */
object AndroidSdkInspector {

    const val COMPILE_SDK_LABEL = "Android 16 (API 36.1)"
    const val BUILD_MIN_SDK = 24
    const val BUILD_TARGET_SDK = 36

    fun inspect(context: Context): AndroidSdkReport {
        val appContext = context.applicationContext
        val pm = appContext.packageManager
        val appInfo = runCatching { appContext.applicationInfo }.getOrNull()

        val targetSdk = appInfo?.targetSdkVersion ?: BUILD_TARGET_SDK
        val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && appInfo != null) {
            appInfo.minSdkVersion
        } else {
            BUILD_MIN_SDK
        }

        val securityPatch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Build.VERSION.SECURITY_PATCH.orEmpty().ifBlank { "Standard" }
        } else {
            "Pre-Marshmallow"
        }

        val featureMatrix = listOf(
            HardwareFeatureStatus(
                featureKey = PackageManager.FEATURE_CAMERA_ANY,
                label = "Camera (QR & Vision)",
                supported = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
            ),
            HardwareFeatureStatus(
                featureKey = PackageManager.FEATURE_MICROPHONE,
                label = "Microphone (Voice & Wake)",
                supported = pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
            ),
            HardwareFeatureStatus(
                featureKey = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PackageManager.FEATURE_FINGERPRINT
                } else {
                    "android.hardware.fingerprint"
                },
                label = "Biometric Auth",
                supported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) ||
                        pm.hasSystemFeature(PackageManager.FEATURE_FACE)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
                } else {
                    false
                }
            ),
            HardwareFeatureStatus(
                featureKey = "android.hardware.strongbox_keystore",
                label = "StrongBox Keystore",
                supported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pm.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
                } else {
                    false
                }
            ),
            HardwareFeatureStatus(
                featureKey = PackageManager.FEATURE_BLUETOOTH_LE,
                label = "Bluetooth LE",
                supported = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
            ),
            HardwareFeatureStatus(
                featureKey = PackageManager.FEATURE_NFC,
                label = "NFC Hardware",
                supported = pm.hasSystemFeature(PackageManager.FEATURE_NFC)
            ),
            HardwareFeatureStatus(
                featureKey = PackageManager.FEATURE_WIFI,
                label = "Wi-Fi Networking",
                supported = pm.hasSystemFeature(PackageManager.FEATURE_WIFI)
            )
        )

        val runtime = Runtime.getRuntime()
        val maxHeapMb = (runtime.maxMemory() / (1024L * 1024L)).coerceAtLeast(1L)
        val allocatedHeapMb = ((runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)).coerceAtLeast(0L)
        val abis = Build.SUPPORTED_ABIS?.toList()?.filter { it.isNotBlank() }.orEmpty()

        return AndroidSdkReport(
            sdkInt = Build.VERSION.SDK_INT,
            releaseVersion = Build.VERSION.RELEASE ?: "Unknown",
            codename = Build.VERSION.CODENAME ?: "REL",
            compileSdkLabel = COMPILE_SDK_LABEL,
            targetSdkVersion = targetSdk,
            minSdkVersion = minSdk,
            securityPatch = securityPatch,
            manufacturer = Build.MANUFACTURER ?: "Android",
            model = Build.MODEL ?: "Device",
            device = Build.DEVICE ?: "generic",
            hardware = Build.HARDWARE ?: "unknown",
            supportedAbis = abis.ifEmpty { listOf("armeabi-v7a") },
            cpuCores = runtime.availableProcessors().coerceAtLeast(1),
            maxHeapMb = maxHeapMb,
            allocatedHeapMb = allocatedHeapMb,
            features = featureMatrix,
            signatureReport = GoogleSignatureVerifier.verifyAppSignature(appContext)
        )
    }

    fun formatMarkdownReport(report: AndroidSdkReport): String = buildString {
        val sig = report.signatureReport
        appendLine("# Aqua AI — Android SDK & Google Signature Report")
        appendLine("- Package: `${sig.packageName}` (v${sig.versionName} / code ${sig.versionCode})")
        appendLine("- Android OS: Android ${report.releaseVersion} (API ${report.sdkInt}, ${report.codename})")
        appendLine("- Security Patch: ${report.securityPatch}")
        appendLine("- SDK Alignment: Compile ${report.compileSdkLabel} | Target API ${report.targetSdkVersion} | Min API ${report.minSdkVersion}")
        appendLine("- Hardware: ${report.manufacturer} ${report.model} (${report.device} / ${report.hardware})")
        appendLine("- ABIs: ${report.supportedAbis.joinToString(", ")}")
        appendLine("- Runtime: ${report.cpuCores} CPU cores | ${report.allocatedHeapMb} / ${report.maxHeapMb} MB JVM heap")
        appendLine()
        appendLine("## Google Signature Check")
        appendLine("- Status: ${sig.overallStatusLabel}")
        appendLine("- Live SHA-1: `${sig.signingSha1}`")
        appendLine("- Registered OAuth SHA-1: `${sig.expectedRegisteredSha1}` (Match: ${sig.sha1MatchesRegisteredOAuth})")
        appendLine("- Live SHA-256: `${sig.signingSha256}`")
        appendLine("- OAuth Web Client ID: ${if (sig.webClientIdConfigured) "Configured" else "Missing"}")
        appendLine("- Google Play Services: ${sig.playServicesStatusLabel} (${sig.playServicesVersion})")
        appendLine("- Google Account Linked: ${if (sig.googleAuthProviderLinked) sig.signedInEmail.ifBlank { "Linked" } else "Not linked"}")
        appendLine()
        appendLine("## Device Feature Matrix")
        report.features.forEach { feat ->
            appendLine("- ${feat.label} (`${feat.featureKey}`): ${if (feat.supported) "Supported" else "Unavailable"}")
        }
    }
}
