package com.example.ui.screens.settings

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsApplications
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.data.model.UserSettings
import com.example.device.DeviceTelemetry
import com.example.ui.components.AquaGlassCard
import com.example.ui.theme.AquaTheme

@Composable
fun AppearanceAndBrightnessCard(
    settings: UserSettings,
    onUpdateSettings: (UserSettings) -> Unit,
    onBrightnessChange: (Int) -> Unit
) {
    val c = AquaTheme.colors
    var brightnessSlider by remember { mutableFloatStateOf(75f) }

    AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Palette, contentDescription = null, tint = c.accentCyan)
                Spacer(Modifier.width(8.dp))
                Text("Appearance & Window Brightness", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "aqua_abyss" to "Aqua Abyss",
                    "aqua_midnight" to "Midnight OLED",
                    "aqua_light" to "Oceanic Light",
                    "system" to "System"
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = settings.darkThemeMode == mode,
                        onClick = { onUpdateSettings(settings.copy(darkThemeMode = mode)) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = c.accentCyan.copy(alpha = 0.2f),
                            selectedLabelColor = c.accentCyan,
                            labelColor = c.textMuted
                        ),
                        modifier = Modifier.testTag("settings_theme_$mode")
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.BrightnessMedium, contentDescription = null, tint = c.amber, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("App Window Brightness: ${brightnessSlider.toInt()}%", style = MaterialTheme.typography.labelMedium, color = c.textPrimary)
            }
            Slider(
                value = brightnessSlider,
                onValueChange = {
                    brightnessSlider = it
                    onBrightnessChange(it.toInt())
                },
                valueRange = 10f..100f,
                colors = SliderDefaults.colors(thumbColor = c.amber, activeTrackColor = c.amber)
            )
        }
    }
}

@Composable
fun PermissionsAndDiagnosticsCard(
    context: Context,
    telemetry: DeviceTelemetry,
    onRequestMic: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onRequestLocation: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onExportDiagnostics: () -> Unit
) {
    val c = AquaTheme.colors
    val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val locGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Security, contentDescription = null, tint = c.success)
                Spacer(Modifier.width(8.dp))
                Text("Android Permissions Dashboard", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            }
            PermissionRow(
                label = "Microphone (Voice & Wake Word)",
                granted = micGranted,
                onAction = onRequestMic
            )
            PermissionRow(
                label = "Notification Access (Read Summaries)",
                granted = telemetry.notificationAccessGranted,
                onAction = onOpenNotificationSettings
            )
            PermissionRow(
                label = "Coarse Location (Maps Context)",
                granted = locGranted,
                onAction = onRequestLocation
            )
            OutlinedButton(
                onClick = onOpenAppSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Icon(Icons.Filled.SettingsApplications, contentDescription = null, tint = c.accentCyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open Android App Permissions", color = c.accentCyan)
            }
            OutlinedButton(
                onClick = onExportDiagnostics,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("settings_export_diagnostics_button")
            ) {
                Icon(Icons.Filled.FileDownload, contentDescription = null, tint = c.accentTeal, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Export Live System Diagnostics (.md)", color = c.accentTeal)
            }
        }
    }
}

@Composable
fun LegalTrustLinksCard(
    onOpenLegalDoc: (String) -> Unit
) {
    val c = AquaTheme.colors
    AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Policy, contentDescription = null, tint = c.accentCyan)
                Spacer(Modifier.width(8.dp))
                Text("Legal, Privacy Policy & Terms", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            }
            Text(
                text = "Browse our updated Trust Portal, Privacy Policy, Age 13+ & Teen Guardrail disclosures, and self-custodial Wallet Terms (updated Nov 17, 2026).",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textMuted
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { onOpenLegalDoc("privacy") },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("settings_privacy_policy_button")
                ) {
                    Icon(Icons.Filled.Policy, contentDescription = null, tint = c.accentCyan, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Privacy Policy", color = c.accentCyan)
                }
                OutlinedButton(
                    onClick = { onOpenLegalDoc("terms") },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("settings_terms_conditions_button")
                ) {
                    Icon(Icons.Filled.Description, contentDescription = null, tint = c.accentCyan, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Terms & Conditions", color = c.accentCyan)
                }
            }
            OutlinedButton(
                onClick = { onOpenLegalDoc("index") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("settings_legal_portal_button")
            ) {
                Icon(Icons.Filled.Language, contentDescription = null, tint = c.accentTeal, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open Legal & Data Safety Portal", color = c.accentTeal)
            }
        }
    }
}

@Composable
fun LegalReaderFullScreenModal(
    context: Context,
    activeDoc: String,
    onChangeDoc: (String) -> Unit,
    onOpenInAppBrowser: (String) -> Unit,
    onClose: () -> Unit
) {
    BackHandler { onClose() }

    val c = AquaTheme.colors
    val externalUrl = when (activeDoc) {
        "index" -> "https://sras756.github.io/aqua-ai/"
        "terms" -> "https://sras756.github.io/aqua-ai/terms.html"
        else -> "https://sras756.github.io/aqua-ai/privacy.html"
    }
    val assetUrl = when (activeDoc) {
        "index" -> "file:///android_asset/legal/index.html"
        "terms" -> "file:///android_asset/legal/terms.html"
        else -> "file:///android_asset/legal/privacy.html"
    }
    var copiedFeedback by remember(activeDoc) { mutableStateOf(false) }
    var useWebViewMode by remember { mutableStateOf(false) }
    var clauseFilter by remember(activeDoc) { mutableStateOf("") }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accentCyan,
        unfocusedBorderColor = c.borderGlow,
        focusedTextColor = c.textPrimary,
        unfocusedTextColor = c.textPrimary
    )

    val sections: List<Pair<String, String>> = remember(activeDoc) {
        when (activeDoc) {
            "index" -> listOf(
                "Aqua AI — Package: com.aistudio.aquaai.vqrzmn" to
                    "Trust & Legal Portal (Effective Nov 17, 2026) • Animated Modular Android AI Assistant, Age 13+ Safety & Gender Personalization, Optional BYOK AI Studio & ElevenLabs Voice, Routines, Workspace Hub & Self-Custodial Multi-Chain Crypto Wallet.",
                "New & Redesigned Features" to
                    "• Animated Modular UI Architecture: Fluid spring-physics orb interactions, sonar halo pulse, ambient drift canvas, staggered card motion & bottom/rail 5-tab workflow.\n" +
                    "• Age System (13+) & Optional Gender Personalization: Strict under-13 COPPA block, Teen Mode (13–17) autonomy guardrails, Adult (18+) mode, and optional pronoun/gender preference.\n" +
                    "• Post-Login AI Setup Studio & Freedom Lab: Optional Google AI Studio API Key verifier + live model discovery, optional non-compulsory ElevenLabs Voice ID setup, and granular sampling/tool autonomy.",
                "Core Platform Capabilities" to
                    "• Gemini Agent & Live Voice: Streaming chat, Deep Thinking traces, Gemini Live voice, Memories & Skills validator.\n" +
                    "• Routines, Browser & App Launching: Reusable automation routines, multi-tab browser & live DuckDuckGo/Wikipedia search.\n" +
                    "• Google Workspace & Drive: Limited Use Gmail, Calendar, Contacts, Sheets, Forms, Chat & Drive snapshot backups.\n" +
                    "• Self-Custodial Multi-Chain Wallet: BIP-39 EVM/Solana/Bitcoin vault in Android Keystore AES-256-GCM, ENS, ERC-20 revokes & CoinGecko charts.",
                "Data Safety Summary" to
                    "• Google Account Profile: Used strictly for Firebase Authentication & per-user Firestore isolation.\n" +
                    "• Age Tier & Gender Preference: Stored inside your user-scoped Firestore preferences solely for 13+ eligibility, Teen guardrails & assistant personalization — never sold or shared for ads.\n" +
                    "• API Keys & Wallet Vault: AI Studio / ElevenLabs keys, wallet keys & PINs stay encrypted strictly in Android Keystore.",
                "Support" to
                    "Developer support & data deletion requests: srasmarkhor7289@gmail.com"
            )
            "terms" -> listOf(
                "Effective Date: November 17, 2026" to
                    "App Package: com.aistudio.aquaai.vqrzmn | Developer Contact: srasmarkhor7289@gmail.com",
                "1. Acceptance & Age 13+ Eligibility" to
                    "By accessing Aqua AI, you confirm you meet the minimum age of 13 years (under COPPA and Google Play policy). Accounts reporting age under 13 are restricted from AI chat and live voice features. Users aged 13–17 operate under mandatory Teen Guardrails limiting autonomy to supervised Balanced mode.",
                "2. AI Assistant, Autonomy Modes & Supervision" to
                    "• Aqua AI uses Gemini models (managed Firebase AI or your optional personal Google AI Studio key), custom directives, live web search, and connected tools.\n" +
                    "• Adults (18+) may select Advisory, Balanced, or Unrestricted Tools modes inside the AI Freedom Lab; users 13–17 are automatically capped at Balanced autonomy.\n" +
                    "• High-impact actions (outbound messages, HTTP writes, wallet contact edits, or blockchain signing) always require explicit modal confirmation.",
                "3. Foreground Service & Modular UI Operation" to
                    "Background voice/task prioritization is strictly optional, displays a persistent Android foreground notification with pause/stop controls when active, and remains reversible anytime. Note: Dedicated Home Launcher / Live Wallpaper functionality was permanently removed.",
                "4. Self-Custodial Wallet & Financial Disclaimers" to
                    "• Non-Custodial Software: Aqua Wallet is purely self-custodial software. You alone control recovery phrases, private keys, PINs, and device security.\n" +
                    "• Irreversible Transactions: Digital asset transfers and smart-contract approvals/revocations are irreversible once broadcast.\n" +
                    "• Not Financial Advice: Market data and portfolio analytics are informational only.",
                "5. Third-Party Services" to
                    "Integrations with Google (Firebase, Workspace, Drive, Google AI Studio APIs), optional ElevenLabs TTS APIs, public blockchain RPC nodes, and CoinGecko depend on those third parties' uptime, quotas, and terms.",
                "6. Imported Skills & Acceptable Use" to
                    "Declarative YAML, JSON, and Markdown skills are scanned against executable payloads; do not import untrusted instructions or misuse Aqua AI.",
                "7. Disclaimer of Warranties & Limitation of Liability" to
                    "Aqua AI is provided \"as is\" without warranties of any kind. To the fullest extent permitted by law, the developer is not liable for indirect, incidental, consequential, or digital-asset damages.",
                "8. Changes & Contact" to
                    "Continued use after revised terms take effect constitutes acceptance. Contact srasmarkhor7289@gmail.com with questions."
            )
            else -> listOf(
                "Effective Date: November 17, 2026" to
                    "App Package: com.aistudio.aquaai.vqrzmn | Developer Contact: srasmarkhor7289@gmail.com",
                "1. Information We Access & Process" to
                    "• Account Information: Google Sign-In via Firebase Authentication provides Firebase UID, display name, email, and avatar for user-scoped sync.\n" +
                    "• Age Tier & Optional Gender Preference: Age in years (validated strictly for 13+ eligibility and 13–17 Teen Guardrails) and optional gender identity preference are saved solely inside your private Firestore preferences.\n" +
                    "• User Prompts & Attachments: Messages, voice transcripts, images, and SAF files selected by you are processed via Gemini when submitted.\n" +
                    "• Live Web & On-Device Telemetry: Web fetches use HTTPS without ad trackers; RAM/battery sparklines stay strictly on-device.",
                "2. AI Personas, Custom Keys, ElevenLabs Voice & Freedom Controls" to
                    "Personal Google AI Studio API keys and optional (non-compulsory) ElevenLabs API keys & Voice IDs remain encrypted locally in Android Keystore AES-256-GCM. Personas and directives stay local per user.",
                "3. Permissions & Foreground Service (Zero Broad Storage)" to
                    "• Opt-In Runtime Permissions: Microphone, Camera, Biometrics, Coarse Location, and Alarms.\n" +
                    "• Zero Broad Storage Scans: Uses Android Photo Picker & SAF.\n" +
                    "• Foreground Service: Optional background automation/wake-word service displays a persistent notification with pause/stop actions.",
                "4. Google Workspace & Google Drive Data (Limited Use)" to
                    "• Google Drive (drive.appdata, drive.file) & Workspace (Gmail, Calendar, Contacts, Sheets, Forms, Chat) are accessed strictly on request and adhere to Google API Services User Data Policy (Limited Use). Never sold or used for ad targeting or model training.",
                "5. Self-Custodial Crypto Wallet & AI Boundary Controls" to
                    "BIP-39 seeds and private keys remain encrypted strictly inside Android Keystore AES-256-GCM and never leave your device. Wallet Read and Wallet Write permissions are separately gated.",
                "6. Storage, Children's Privacy (Under 13 Restricted) & Deletion" to
                    "Aqua AI is strictly not intended for children under 13 and actively restricts chat/voice execution when under-13 age is set. Delete data anytime in-app or email srasmarkhor7289@gmail.com."
            )
        }
    }

    val visibleSections = remember(sections, clauseFilter) {
        val q = clauseFilter.trim().lowercase()
        if (q.isEmpty()) sections
        else sections.filter {
            it.first.lowercase().contains(q) || it.second.lowercase().contains(q)
        }
    }

    Surface(
        color = c.canvas,
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = when (activeDoc) {
                        "index" -> "Aqua AI Trust Portal"
                        "terms" -> "Terms & Conditions — Aqua AI"
                        else -> "Privacy Policy — Aqua AI"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = c.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.testTag("legal_reader_close_button")
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = c.accentCyan)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = activeDoc == "privacy",
                    onClick = { onChangeDoc("privacy") },
                    label = { Text("Privacy Policy") }
                )
                FilterChip(
                    selected = activeDoc == "terms",
                    onClick = { onChangeDoc("terms") },
                    label = { Text("Terms & Conditions") }
                )
                FilterChip(
                    selected = activeDoc == "index",
                    onClick = { onChangeDoc("index") },
                    label = { Text("Overview") }
                )
                FilterChip(
                    selected = useWebViewMode,
                    onClick = { useWebViewMode = !useWebViewMode },
                    label = { Text(if (useWebViewMode) "Cards View" else "Web Portal View") },
                    modifier = Modifier.testTag("legal_toggle_webview_mode")
                )
            }

            if (!useWebViewMode) {
                OutlinedTextField(
                    value = clauseFilter,
                    onValueChange = { clauseFilter = it },
                    placeholder = { Text("Filter clauses (e.g. age, gender, wallet, Drive)...") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = c.accentCyan) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("legal_clause_search")
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(visibleSections) { (heading, body) ->
                        AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = heading,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = c.accentCyan,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = body,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = c.textMuted
                                )
                            }
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .border(1.dp, c.borderGlow, RoundedCornerShape(16.dp))
                ) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                @Suppress("SetJavaScriptEnabled")
                                settings.apply {
                                    javaScriptEnabled = true
                                    allowFileAccess = true
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        val target = request?.url?.toString().orEmpty()
                                        return !target.startsWith("file:///android_asset/legal/")
                                    }
                                }
                                loadUrl(assetUrl)
                            }
                        },
                        update = { wv ->
                            if (wv.url != assetUrl) wv.loadUrl(assetUrl)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.cardSurface)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Public Website (GitHub Pages)", style = MaterialTheme.typography.labelSmall, color = c.accentCyan)
                    Text(externalUrl, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("Aqua AI Legal URL", externalUrl))
                            copiedFeedback = true
                        }
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = c.accentCyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (copiedFeedback) "Copied" else "Copy", color = c.accentCyan)
                    }
                    OutlinedButton(
                        onClick = { onOpenInAppBrowser(externalUrl) }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = c.accentTeal, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Web", color = c.accentTeal)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    onAction: () -> Unit
) {
    val c = AquaTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
            Text(
                text = if (granted) "Granted" else "Not Granted",
                style = MaterialTheme.typography.labelSmall,
                color = if (granted) c.success else c.amber
            )
        }
        OutlinedButton(
            onClick = onAction,
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Text(if (granted) "Manage" else "Configure", color = c.accentCyan)
        }
    }
}
