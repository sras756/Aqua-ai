package com.example.security

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import com.example.R
import com.example.data.model.UserSettings
import com.example.domain.AgeTier
import com.example.domain.UserProfilePolicy
import com.example.voice.AquaWakeWordService
import org.json.JSONArray
import org.json.JSONObject

enum class ComplianceState(val label: String) {
    COMPLIANT("Compliant"),
    ACTION_REQUIRED("Action Required")
}

data class ComplianceCheckItem(
    val id: String,
    val title: String,
    val category: String,
    val status: ComplianceState,
    val currentValue: String,
    val detail: String,
    val remediation: String = ""
)

data class ComplianceEnvironmentSnapshot(
    val packageName: String,
    val appName: String,
    val targetSdkVersion: Int,
    val minSdkVersion: Int,
    val usesCleartextTraffic: Boolean,
    val declaredPermissions: Set<String>,
    val foregroundServiceType: Int,
    val ageTier: AgeTier,
    val ageYears: Int,
    val signatureReport: SignatureCheckReport
)

data class ComplianceHistoryPoint(
    val timestampMs: Long,
    val passedCount: Int,
    val totalCount: Int,
    val status: ComplianceState,
    val ageTierId: String,
    val targetSdkVersion: Int
)

data class PlayComplianceReport(
    val overallStatus: ComplianceState,
    val passedCount: Int,
    val totalCount: Int,
    val checkedAtMs: Long,
    val snapshot: ComplianceEnvironmentSnapshot,
    val checks: List<ComplianceCheckItem>
) {
    val isCompliant: Boolean get() = overallStatus == ComplianceState.COMPLIANT

    fun toWebQueryParams(history: List<ComplianceHistoryPoint> = emptyList()): String {
        val broadStorageCount = checks.firstOrNull { it.id == PlayComplianceInspector.CHECK_PERMISSIONS_MINIMIZATION }
            ?.let { if (it.status == ComplianceState.COMPLIANT) 0 else 1 } ?: 0
        val fgsOk = checks.firstOrNull { it.id == PlayComplianceInspector.CHECK_FOREGROUND_SERVICE }
            ?.status == ComplianceState.COMPLIANT

        return buildString {
            append("pkg=").append(Uri.encode(snapshot.packageName))
            append("&appName=").append(Uri.encode(snapshot.appName))
            append("&targetSdk=").append(snapshot.targetSdkVersion)
            append("&minSdk=").append(snapshot.minSdkVersion)
            append("&cleartext=").append(snapshot.usesCleartextTraffic)
            append("&broadStorage=").append(broadStorageCount)
            append("&fgs=").append(if (fgsOk) "ok" else "missing")
            append("&ageTier=").append(Uri.encode(snapshot.ageTier.id))
            append("&sigOk=").append(snapshot.signatureReport.isTrustedForGoogleSignIn)
            append("&status=").append(if (isCompliant) "compliant" else "action_required")
            val encodedHistory = PlayComplianceHistoryStore.encodeCompactHistory(history)
            if (encodedHistory.isNotBlank()) {
                append("&history=").append(Uri.encode(encodedHistory))
            }
        }
    }
}

/**
 * Dynamically inspects the live Android runtime & manifest configuration against
 * Google Play Developer Program policy requirements.
 */
object PlayComplianceInspector {

    const val CHECK_PERMISSIONS_MINIMIZATION = "permissions_minimization"
    const val CHECK_APP_METADATA = "app_metadata_title"
    const val CHECK_TARGET_SDK_TLS = "target_sdk_and_tls"
    const val CHECK_FOREGROUND_SERVICE = "foreground_service_type"
    const val CHECK_AGE_ELIGIBILITY = "age_eligibility_gate"
    const val CHECK_SIGNATURE_INTEGRITY = "signature_and_play_integrity"

    private const val MIN_COMPLIANT_TARGET_SDK = 35
    private const val MAX_PLAY_TITLE_LENGTH = 30

    private val restrictedPermissions = setOf(
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.READ_CALENDAR",
        "android.permission.WRITE_CALENDAR",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.REQUEST_INSTALL_PACKAGES"
    )

    private val prohibitedTitleBuzzwords = listOf(
        "free",
        "best",
        "#1",
        "download now",
        "100%"
    )

    fun evaluate(
        context: Context,
        settings: UserSettings,
        signatureReport: SignatureCheckReport = GoogleSignatureVerifier.verifyAppSignature(context),
        checkedAtMs: Long = System.currentTimeMillis()
    ): PlayComplianceReport {
        val snapshot = captureEnvironmentSnapshot(context, settings, signatureReport)
        return evaluateFromSnapshot(snapshot, checkedAtMs)
    }

    @Suppress("DEPRECATION")
    fun captureEnvironmentSnapshot(
        context: Context,
        settings: UserSettings,
        signatureReport: SignatureCheckReport
    ): ComplianceEnvironmentSnapshot {
        val appContext = context.applicationContext ?: context
        val pm = appContext.packageManager
        val pkgName = appContext.packageName
        val appInfo = appContext.applicationInfo

        val appName = runCatching {
            appContext.getString(R.string.app_name).trim()
        }.getOrElse {
            appInfo.loadLabel(pm).toString().trim()
        }

        val targetSdk = appInfo.targetSdkVersion
        val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            appInfo.minSdkVersion
        } else {
            24
        }
        val usesCleartext = (appInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC) != 0

        val declaredPerms = runCatching {
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    pkgName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
                )
            } else {
                pm.getPackageInfo(pkgName, PackageManager.GET_PERMISSIONS)
            }
            pkgInfo.requestedPermissions?.toSet().orEmpty()
        }.getOrDefault(emptySet())

        val fgsType = runCatching {
            val component = ComponentName(appContext, AquaWakeWordService::class.java)
            val svcInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getServiceInfo(
                    component,
                    PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                )
            } else {
                pm.getServiceInfo(component, PackageManager.GET_META_DATA)
            }
            svcInfo.foregroundServiceType
        }.getOrDefault(0)

        return ComplianceEnvironmentSnapshot(
            packageName = pkgName,
            appName = appName,
            targetSdkVersion = targetSdk,
            minSdkVersion = minSdk,
            usesCleartextTraffic = usesCleartext,
            declaredPermissions = declaredPerms,
            foregroundServiceType = fgsType,
            ageTier = UserProfilePolicy.resolveTier(settings),
            ageYears = settings.ageYears,
            signatureReport = signatureReport
        )
    }

    fun evaluateFromSnapshot(
        snapshot: ComplianceEnvironmentSnapshot,
        checkedAtMs: Long = System.currentTimeMillis()
    ): PlayComplianceReport {
        val checks = listOf(
            evaluatePermissionsMinimization(snapshot),
            evaluateAppMetadata(snapshot),
            evaluateTargetSdkAndNetwork(snapshot),
            evaluateForegroundService(snapshot),
            evaluateAgeEligibility(snapshot),
            evaluateSignatureIntegrity(snapshot)
        )
        val passed = checks.count { it.status == ComplianceState.COMPLIANT }
        val overall = if (passed == checks.size) {
            ComplianceState.COMPLIANT
        } else {
            ComplianceState.ACTION_REQUIRED
        }
        return PlayComplianceReport(
            overallStatus = overall,
            passedCount = passed,
            totalCount = checks.size,
            checkedAtMs = checkedAtMs,
            snapshot = snapshot,
            checks = checks
        )
    }

    private fun evaluatePermissionsMinimization(
        snapshot: ComplianceEnvironmentSnapshot
    ): ComplianceCheckItem {
        val violations = snapshot.declaredPermissions
            .filter { it in restrictedPermissions }
            .map { it.substringAfterLast('.') }
            .sorted()

        return if (violations.isEmpty()) {
            ComplianceCheckItem(
                id = CHECK_PERMISSIONS_MINIMIZATION,
                title = "Permissions Minimization & Least Privilege",
                category = "Permissions",
                status = ComplianceState.COMPLIANT,
                currentValue = "${snapshot.declaredPermissions.size} scoped permissions",
                detail = "Zero broad storage (READ_MEDIA_* / READ_EXTERNAL_STORAGE), overlay, or Workspace permissions declared. Media imports rely on Photo Picker / SAF."
            )
        } else {
            ComplianceCheckItem(
                id = CHECK_PERMISSIONS_MINIMIZATION,
                title = "Permissions Minimization & Least Privilege",
                category = "Permissions",
                status = ComplianceState.ACTION_REQUIRED,
                currentValue = "${violations.size} restricted declaration(s)",
                detail = "Detected restricted permission(s): ${violations.joinToString(", ")}.",
                remediation = "Remove ${violations.joinToString(", ")} from AndroidManifest.xml and migrate media flows to Photo Picker or SAF."
            )
        }
    }

    private fun evaluateAppMetadata(
        snapshot: ComplianceEnvironmentSnapshot
    ): ComplianceCheckItem {
        val title = snapshot.appName.trim()
        val issues = mutableListOf<String>()
        if (title.isBlank()) {
            issues += "App title is blank"
        }
        if (title.length > MAX_PLAY_TITLE_LENGTH) {
            issues += "Length ${title.length} exceeds 30-character Play limit"
        }
        val lettersOnly = title.filter { it.isLetter() }
        if (lettersOnly.length >= 3 && lettersOnly.all { it.isUpperCase() }) {
            issues += "Title uses ALL CAPS"
        }
        val lower = title.lowercase()
        val matchedBuzzword = prohibitedTitleBuzzwords.firstOrNull { lower.contains(it) }
        if (matchedBuzzword != null) {
            issues += "Contains promotional term '$matchedBuzzword'"
        }
        val hasEmojiOrSymbol = title.codePoints().anyMatch { cp ->
            Character.getType(cp) == Character.OTHER_SYMBOL.toInt() || cp in 0x1F300..0x1FAFF
        }
        if (hasEmojiOrSymbol) {
            issues += "Contains emoji or non-text symbol"
        }

        return if (issues.isEmpty()) {
            ComplianceCheckItem(
                id = CHECK_APP_METADATA,
                title = "App Title & Store Metadata Policy",
                category = "Metadata",
                status = ComplianceState.COMPLIANT,
                currentValue = "\"$title\" (${title.length}/30 chars)",
                detail = "Launcher title meets Google Play's 30-character cap with no emojis, ALL CAPS, or promotional buzzwords."
            )
        } else {
            ComplianceCheckItem(
                id = CHECK_APP_METADATA,
                title = "App Title & Store Metadata Policy",
                category = "Metadata",
                status = ComplianceState.ACTION_REQUIRED,
                currentValue = "\"$title\" (${title.length} chars)",
                detail = issues.joinToString("; "),
                remediation = "Update res/values/strings.xml app_name to a concise 1–2 word title <= 30 chars without emojis, ALL CAPS, or promotional words."
            )
        }
    }

    private fun evaluateTargetSdkAndNetwork(
        snapshot: ComplianceEnvironmentSnapshot
    ): ComplianceCheckItem {
        val targetOk = snapshot.targetSdkVersion >= MIN_COMPLIANT_TARGET_SDK
        val tlsOk = !snapshot.usesCleartextTraffic

        return if (targetOk && tlsOk) {
            ComplianceCheckItem(
                id = CHECK_TARGET_SDK_TLS,
                title = "Target API Level & HTTPS Transport Security",
                category = "System Integrity",
                status = ComplianceState.COMPLIANT,
                currentValue = "Target API ${snapshot.targetSdkVersion} • TLS enforced",
                detail = "Target SDK ${snapshot.targetSdkVersion} (minSdk ${snapshot.minSdkVersion}) meets Google Play API requirements with usesCleartextTraffic=\"false\"."
            )
        } else {
            val problems = buildList {
                if (!targetOk) add("Target SDK ${snapshot.targetSdkVersion} is below $MIN_COMPLIANT_TARGET_SDK")
                if (!tlsOk) add("android:usesCleartextTraffic is enabled")
            }
            ComplianceCheckItem(
                id = CHECK_TARGET_SDK_TLS,
                title = "Target API Level & HTTPS Transport Security",
                category = "System Integrity",
                status = ComplianceState.ACTION_REQUIRED,
                currentValue = "API ${snapshot.targetSdkVersion} • cleartext=${snapshot.usesCleartextTraffic}",
                detail = problems.joinToString("; "),
                remediation = "Target Android API $MIN_COMPLIANT_TARGET_SDK+ in build.gradle.kts and set android:usesCleartextTraffic=\"false\" in AndroidManifest.xml."
            )
        }
    }

    private fun evaluateForegroundService(
        snapshot: ComplianceEnvironmentSnapshot
    ): ComplianceCheckItem {
        val hasMicType = (snapshot.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0
        val hasSyncType = (snapshot.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) != 0
        val hasFgsBasePerm = Manifest.permission.FOREGROUND_SERVICE in snapshot.declaredPermissions
        val hasFgsMicPerm = "android.permission.FOREGROUND_SERVICE_MICROPHONE" in snapshot.declaredPermissions
        val hasFgsSyncPerm = "android.permission.FOREGROUND_SERVICE_DATA_SYNC" in snapshot.declaredPermissions

        return if (hasMicType && hasSyncType && hasFgsBasePerm && hasFgsMicPerm && hasFgsSyncPerm) {
            ComplianceCheckItem(
                id = CHECK_FOREGROUND_SERVICE,
                title = "Foreground Service Type & Permission Alignment",
                category = "System Integrity",
                status = ComplianceState.COMPLIANT,
                currentValue = "microphone|dataSync declared",
                detail = "AquaWakeWordService explicitly declares foregroundServiceType=\"microphone|dataSync\" with matching Android 14+ FGS permissions."
            )
        } else {
            ComplianceCheckItem(
                id = CHECK_FOREGROUND_SERVICE,
                title = "Foreground Service Type & Permission Alignment",
                category = "System Integrity",
                status = ComplianceState.ACTION_REQUIRED,
                currentValue = "flags=0x${snapshot.foregroundServiceType.toString(16)}",
                detail = "Foreground service type or Android 14+ FOREGROUND_SERVICE_* permission alignment is incomplete.",
                remediation = "Declare android:foregroundServiceType=\"microphone|dataSync\" on AquaWakeWordService and include matching FOREGROUND_SERVICE_* permissions."
            )
        }
    }

    private fun evaluateAgeEligibility(
        snapshot: ComplianceEnvironmentSnapshot
    ): ComplianceCheckItem {
        return when (snapshot.ageTier) {
            AgeTier.ADULT_18_PLUS, AgeTier.TEEN_13_17 -> ComplianceCheckItem(
                id = CHECK_AGE_ELIGIBILITY,
                title = "Age 13+ Eligibility & COPPA Gate",
                category = "User Safety",
                status = ComplianceState.COMPLIANT,
                currentValue = snapshot.ageTier.shortBadge,
                detail = "Active profile verified (${snapshot.ageTier.label}); ${snapshot.ageTier.summary}"
            )
            AgeTier.UNDER_13_BLOCKED -> ComplianceCheckItem(
                id = CHECK_AGE_ELIGIBILITY,
                title = "Age 13+ Eligibility & COPPA Gate",
                category = "User Safety",
                status = ComplianceState.ACTION_REQUIRED,
                currentValue = "Under 13 (${snapshot.ageYears}y)",
                detail = "Profile reports age under 13; AI chat, voice, and image tools remain restricted under COPPA & Google Play policy.",
                remediation = "Verify an eligible 13+ account holder in Profile & Safety or Setup Studio before enabling AI capabilities."
            )
            AgeTier.UNSET -> ComplianceCheckItem(
                id = CHECK_AGE_ELIGIBILITY,
                title = "Age 13+ Eligibility & COPPA Gate",
                category = "User Safety",
                status = ComplianceState.ACTION_REQUIRED,
                currentValue = "Age Unconfirmed",
                detail = "User age has not yet been confirmed in this environment; 13+ eligibility & Teen 13–17 guardrails cannot be attested.",
                remediation = "Set and save your age (13+) under Settings -> Profile, Age & Gender or Setup Studio."
            )
        }
    }

    private fun evaluateSignatureIntegrity(
        snapshot: ComplianceEnvironmentSnapshot
    ): ComplianceCheckItem {
        val sig = snapshot.signatureReport
        return if (sig.isTrustedForGoogleSignIn) {
            val shaLabel = if (sig.sha1MatchesRegisteredOAuth) "Registered SHA-1 Matched" else "Signed Keystore Verified"
            ComplianceCheckItem(
                id = CHECK_SIGNATURE_INTEGRITY,
                title = "Signing Certificate & Play Services Integrity",
                category = "Integrity & Auth",
                status = ComplianceState.COMPLIANT,
                currentValue = shaLabel,
                detail = "${sig.overallStatusLabel} • Web Client ID configured & Play Services ${sig.playServicesVersion}."
            )
        } else {
            val missing = buildList {
                if (sig.signingSha1.isBlank() || sig.signingSha1 == "Unavailable") add("APK signing certificate")
                if (!sig.webClientIdConfigured) add("default_web_client_id")
                if (!sig.playServicesAvailable) add("Google Play Services (${sig.playServicesStatusLabel})")
            }
            ComplianceCheckItem(
                id = CHECK_SIGNATURE_INTEGRITY,
                title = "Signing Certificate & Play Services Integrity",
                category = "Integrity & Auth",
                status = ComplianceState.ACTION_REQUIRED,
                currentValue = sig.overallStatusLabel,
                detail = "Environment verification incomplete: missing or unavailable ${missing.joinToString(", ")}.",
                remediation = "Run on a Google Play Services enabled device with valid APK signing SHA-1 and Firebase OAuth default_web_client_id."
            )
        }
    }
}

/**
 * Persists real PlayComplianceInspector audit results in device-local SharedPreferences
 * without ever synthesizing placeholder or demo history points.
 */
object PlayComplianceHistoryStore {
    private const val PREFS_NAME = "aqua_compliance_audit_history"
    private const val KEY_HISTORY_JSON = "history_json_v1"
    private const val MAX_STORED_POINTS = 40
    private const val MIN_AUTO_RECORD_INTERVAL_MS = 15 * 60 * 1000L

    fun loadHistory(context: Context): List<ComplianceHistoryPoint> {
        val appContext = context.applicationContext ?: context
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_HISTORY_JSON, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val ts = obj.optLong("ts", 0L)
                    val passed = obj.optInt("passed", -1)
                    val total = obj.optInt("total", 0)
                    if (ts <= 0L || total <= 0 || passed < 0) continue
                    val compliant = obj.optBoolean("compliant", passed == total)
                    add(
                        ComplianceHistoryPoint(
                            timestampMs = ts,
                            passedCount = passed.coerceIn(0, total),
                            totalCount = total,
                            status = if (compliant) ComplianceState.COMPLIANT else ComplianceState.ACTION_REQUIRED,
                            ageTierId = obj.optString("ageTier", ""),
                            targetSdkVersion = obj.optInt("targetSdk", 36)
                        )
                    )
                }
            }.sortedBy { it.timestampMs }
        }.getOrDefault(emptyList())
    }

    fun recordEvaluation(
        context: Context,
        report: PlayComplianceReport,
        forceRecord: Boolean = false
    ): List<ComplianceHistoryPoint> {
        val current = loadHistory(context).toMutableList()
        val last = current.lastOrNull()
        val stateChanged = last == null ||
            last.passedCount != report.passedCount ||
            last.totalCount != report.totalCount ||
            last.status != report.overallStatus ||
            last.ageTierId != report.snapshot.ageTier.id ||
            last.targetSdkVersion != report.snapshot.targetSdkVersion
        val intervalElapsed = last == null ||
            (report.checkedAtMs - last.timestampMs) >= MIN_AUTO_RECORD_INTERVAL_MS

        if (forceRecord || stateChanged || intervalElapsed) {
            current += ComplianceHistoryPoint(
                timestampMs = report.checkedAtMs,
                passedCount = report.passedCount,
                totalCount = report.totalCount,
                status = report.overallStatus,
                ageTierId = report.snapshot.ageTier.id,
                targetSdkVersion = report.snapshot.targetSdkVersion
            )
            val trimmed = current.sortedBy { it.timestampMs }.takeLast(MAX_STORED_POINTS)
            saveHistory(context, trimmed)
            return trimmed
        }
        return current
    }

    fun clearHistory(context: Context) {
        val appContext = context.applicationContext ?: context
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_HISTORY_JSON)
            .apply()
    }

    fun encodeCompactHistory(history: List<ComplianceHistoryPoint>): String {
        if (history.isEmpty()) return ""
        return history.takeLast(24).joinToString(";") { pt ->
            val ok = if (pt.status == ComplianceState.COMPLIANT) 1 else 0
            "${pt.timestampMs}:${pt.passedCount}:${pt.totalCount}:$ok"
        }
    }

    private fun saveHistory(context: Context, points: List<ComplianceHistoryPoint>) {
        val appContext = context.applicationContext ?: context
        val arr = JSONArray()
        points.forEach { pt ->
            arr.put(
                JSONObject().apply {
                    put("ts", pt.timestampMs)
                    put("passed", pt.passedCount)
                    put("total", pt.totalCount)
                    put("compliant", pt.status == ComplianceState.COMPLIANT)
                    put("ageTier", pt.ageTierId)
                    put("targetSdk", pt.targetSdkVersion)
                }
            )
        }
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HISTORY_JSON, arr.toString())
            .apply()
    }
}
