package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.AquaMainViewModel
import com.example.ui.components.AquaAnimatedEntrance
import com.example.ui.components.AquaBackdropBox
import com.example.ui.components.AquaGlassCard
import com.example.ui.components.AquaSectionHeader
import com.example.ui.components.AquaStatusPill
import com.example.ui.theme.AquaTheme
import com.example.ui.theme.JetBrainsMonoFontFamily

/**
 * Real-time Android SDK Inspector and Google Signature Check screen.
 * Surfaces live PackageManager APK signing SHA-1/SHA-256 certificates,
 * Firebase OAuth Web Client ID verification, Google Play Services check,
 * Build SDK / ABI targets, JVM heap headroom, and hardware capabilities.
 */
@Composable
fun SdkAndSignatureScreen(viewModel: AquaMainViewModel) {
    val c = AquaTheme.colors
    val context = LocalContext.current

    val sdkReport by viewModel.sdkReport.collectAsStateWithLifecycle()
    val sig = sdkReport.signatureReport

    LaunchedEffect(Unit) {
        viewModel.refreshSystemAndSignatureDiagnostics()
    }

    val exportMdLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        if (uri != null) {
            runCatching {
                val md = viewModel.buildSdkAndSignatureMarkdown()
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(md.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    AquaBackdropBox(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AquaSectionHeader(
                    eyebrow = "Security & Runtime",
                    title = "Android SDK & Signature",
                    subtitle = "Live APK SHA-1/SHA-256 verification & Android 16 (API 36) diagnostics",
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { viewModel.refreshSystemAndSignatureDiagnostics() },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("sdk_refresh_button")
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh SDK and signature inspection", tint = c.accentCyan)
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Google Signature Check Card
                item {
                    AquaAnimatedEntrance(index = 0) {
                        AquaGlassCard(
                            borderColor = if (sig.isTrustedForGoogleSignIn) c.success else c.amber,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("google_signature_check_card")
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Icon(
                                            imageVector = Icons.Filled.VerifiedUser,
                                            contentDescription = null,
                                            tint = if (sig.isTrustedForGoogleSignIn) c.success else c.amber
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "Google Signature Check",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = c.textPrimary
                                        )
                                    }
                                    AquaStatusPill(
                                        label = if (sig.sha1MatchesRegisteredOAuth) "SHA-1 MATCHED" else if (sig.isTrustedForGoogleSignIn) "VERIFIED" else "CHECK",
                                        accent = if (sig.isTrustedForGoogleSignIn) c.success else c.amber
                                    )
                                }

                                Text(
                                    text = sig.overallStatusLabel,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (sig.isTrustedForGoogleSignIn) c.accentTeal else c.amber
                                )

                                FingerprintRow(
                                    label = "Live APK SHA-1 (${sig.packageName})",
                                    value = sig.signingSha1,
                                    onCopy = { viewModel.copyToClipboard("APK SHA-1", sig.signingSha1) }
                                )
                                FingerprintRow(
                                    label = "Live APK SHA-256",
                                    value = sig.signingSha256,
                                    onCopy = { viewModel.copyToClipboard("APK SHA-256", sig.signingSha256) }
                                )
                                Text(
                                    text = "Firebase OAuth SHA-1 Match: ${if (sig.sha1MatchesRegisteredOAuth) "Exact Match" else "Custom Keystore / Unmatched"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (sig.sha1MatchesRegisteredOAuth) c.success else c.textMuted
                                )
                                Text(
                                    text = "OAuth Web Client ID: ${if (sig.webClientIdConfigured) "Configured" else "Missing"} • Google Play Services: ${sig.playServicesStatusLabel} (${sig.playServicesVersion})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = c.textMuted
                                )
                                Text(
                                    text = "Firebase Google Provider: ${if (sig.googleAuthProviderLinked) "Linked (${sig.signedInEmail.ifBlank { "Active" }})" else "Standard Session"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = c.textMuted
                                )
                            }
                        }
                    }
                }

                // 2. Android SDK Alignment & Runtime Card
                item {
                    AquaAnimatedEntrance(index = 1) {
                        AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Android, contentDescription = null, tint = c.accentTeal)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "Android SDK Environment",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = c.textPrimary
                                        )
                                    }
                                    AquaStatusPill(label = sdkReport.apiBadge, accent = c.accentCyan)
                                }

                                Text(
                                    text = "Compile SDK: ${sdkReport.compileSdkLabel} • Target API ${sdkReport.targetSdkVersion} • Min API ${sdkReport.minSdkVersion}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = c.textPrimary
                                )
                                Text(
                                    text = "Device: ${sdkReport.manufacturer} ${sdkReport.model} (${sdkReport.hardware}) • Security Patch: ${sdkReport.securityPatch}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = c.textMuted
                                )
                                Text(
                                    text = "Supported ABIs: ${sdkReport.supportedAbis.joinToString(", ")}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = c.accentCyan
                                )

                                val heapFrac = (sdkReport.allocatedHeapMb.toFloat() / sdkReport.maxHeapMb.toFloat()).coerceIn(0f, 1f)
                                LinearProgressIndicator(
                                    progress = { heapFrac },
                                    color = c.accentCyan,
                                    trackColor = c.deepSurface,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                )
                                Text(
                                    text = "Runtime: ${sdkReport.cpuCores} CPU core(s) • JVM Heap ${sdkReport.allocatedHeapMb} / ${sdkReport.maxHeapMb} MB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = c.textMuted
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            viewModel.copyToClipboard(
                                                "Aqua SDK & Signature Report",
                                                viewModel.buildSdkAndSignatureMarkdown()
                                            )
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = c.accentCyan, contentColor = c.onAccent),
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 48.dp)
                                            .testTag("sdk_copy_report_button")
                                    ) {
                                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Copy Report", fontWeight = FontWeight.Bold)
                                    }
                                    OutlinedButton(
                                        onClick = { exportMdLauncher.launch("aqua-sdk-signature.md") },
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 48.dp)
                                            .testTag("sdk_export_report_button")
                                    ) {
                                        Icon(Icons.Filled.FileDownload, contentDescription = null, tint = c.accentTeal, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Export .md", color = c.accentTeal)
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. Hardware Capability Matrix
                item {
                    Text(
                        text = "ANDROID HARDWARE CAPABILITY MATRIX",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.accentCyan
                    )
                }

                items(sdkReport.features, key = { it.featureKey }) { feat ->
                    AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (feat.supported) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = if (feat.supported) c.success else c.textMuted,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(feat.label, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                                    Text(
                                        text = feat.featureKey,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        color = c.textMuted
                                    )
                                }
                            }
                            AquaStatusPill(
                                label = if (feat.supported) "SUPPORTED" else "ABSENT",
                                accent = if (feat.supported) c.success else c.textMuted
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FingerprintRow(
    label: String,
    value: String,
    onCopy: () -> Unit
) {
    val c = AquaTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = c.textMuted)
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = JetBrainsMonoFontFamily,
                color = c.textPrimary
            )
        }
        IconButton(
            onClick = onCopy,
            modifier = Modifier.minimumInteractiveComponentSize()
        ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy $label", tint = c.accentCyan, modifier = Modifier.size(18.dp))
        }
    }
}
