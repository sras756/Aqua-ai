package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.AquaPersona
import com.example.domain.AgeTier
import com.example.domain.UserGender
import com.example.domain.UserProfilePolicy
import com.example.ui.AquaMainViewModel
import com.example.ui.components.AquaAnimatedEntrance
import com.example.ui.components.AquaBackdropBox
import com.example.ui.components.AquaGlassCard
import com.example.ui.components.AquaProfileTierPill
import com.example.ui.components.TelemetrySparklineCard
import com.example.ui.components.aquaPressScale
import com.example.ui.theme.AquaTheme

@Composable
fun HubScreen(
    viewModel: AquaMainViewModel,
    onOpenTab: (String) -> Unit,
    onOpenCommandPalette: () -> Unit
) {
    val c = AquaTheme.colors
    val routines by viewModel.routines.collectAsStateWithLifecycle()
    val skills by viewModel.skills.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val stats by viewModel.storageAndMemory.collectAsStateWithLifecycle()
    val samples by viewModel.telemetryHistory.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val personaId by viewModel.personaId.collectAsStateWithLifecycle()
    val persona = AquaPersona.fromId(personaId)
    val installedCount = remember { viewModel.deviceController.getInstalledLaunchableApps().size }

    val ageTier = UserProfilePolicy.resolveTier(settings)
    val gender = UserGender.fromId(settings.gender)

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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AQUA ECOSYSTEM",
                        style = MaterialTheme.typography.headlineSmall,
                        color = c.textPrimary
                    )
                    Text(
                        text = "Persona: ${persona.label} • Model: ${settings.preferredModel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.accentCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onOpenCommandPalette,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(CircleShape)
                        .background(c.cardSurface)
                        .border(1.dp, c.borderGlow, CircleShape)
                        .testTag("hub_command_palette_button")
                ) {
                    Icon(Icons.Filled.Search, contentDescription = "Open Command Palette", tint = c.accentCyan)
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Hero Ecosystem Banner
                item {
                    AquaAnimatedEntrance(index = 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(148.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .border(1.dp, c.borderGlow, RoundedCornerShape(24.dp))
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.img_aqua_aurora_backdrop),
                                contentDescription = "Aqua Ecosystem Neural Surface",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.horizontalGradient(
                                            colors = listOf(
                                                Color(0xFF030B16).copy(alpha = 0.90f),
                                                Color(0xFF030B16).copy(alpha = 0.40f)
                                            )
                                        )
                                    )
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(18.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "COMMAND SUITE",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = c.accentTeal
                                    )
                                    Text(
                                        text = "Unified Android & AI Control",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = Color(0xFFEAF6FF)
                                    )
                                    Text(
                                        text = "$installedCount launchable apps • ${skills.count { it.enabled }} skills • ${memories.size} memories",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFB8D4EC)
                                    )
                                    AquaProfileTierPill(
                                        ageTier = ageTier,
                                        gender = gender,
                                        onClick = { onOpenTab("settings") },
                                        modifier = Modifier.testTag("hub_profile_tier_pill")
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Image(
                                    painter = painterResource(id = R.drawable.img_aqua_vault_emblem),
                                    contentDescription = "Aqua Crystalline Emblem",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .border(1.dp, c.accentCyan.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                                )
                            }
                        }
                    }
                }

                // Bento Grid Row 1: Aqua Core & Routines
                item {
                    AquaAnimatedEntrance(index = 1) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            HubBentoTile(
                                icon = Icons.Filled.GraphicEq,
                                title = "Aqua Core",
                                subtitle = "Orb, Chat & Live Voice",
                                accent = c.accentCyan,
                                tag = "hub_card_core",
                                onClick = { onOpenTab("home") },
                                modifier = Modifier.weight(1f)
                            )
                            HubBentoTile(
                                icon = Icons.Filled.AutoMode,
                                title = "Routines",
                                subtitle = "${routines.size} automation${if (routines.size == 1) "" else "s"}",
                                accent = c.accentTeal,
                                tag = "hub_card_routines",
                                onClick = { onOpenTab("routines") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Bento Grid Row 2: Drive Vault & SDK / Signature
                item {
                    AquaAnimatedEntrance(index = 2) {
                        val driveBackups by viewModel.driveBackups.collectAsStateWithLifecycle()
                        val sigReport by viewModel.signatureReport.collectAsStateWithLifecycle()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            HubBentoTile(
                                icon = Icons.Filled.CloudSync,
                                title = "Drive Vault",
                                subtitle = "${driveBackups.size} backup(s) • API Key",
                                accent = c.success,
                                tag = "hub_card_drive",
                                onClick = { onOpenTab("drive") },
                                modifier = Modifier.weight(1f)
                            )
                            HubBentoTile(
                                icon = Icons.Filled.VerifiedUser,
                                title = "SDK & Signature",
                                subtitle = if (sigReport.isTrustedForGoogleSignIn) "API ${android.os.Build.VERSION.SDK_INT} • Verified" else "Inspect SHA-1 & SDK",
                                accent = c.accentCyan,
                                tag = "hub_card_sdk",
                                onClick = { onOpenTab("sdk") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Bento Grid Row 3: Aqua Browser & Crypto Wallet
                item {
                    AquaAnimatedEntrance(index = 3) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            HubBentoTile(
                                icon = Icons.Filled.Language,
                                title = "Aqua Browser",
                                subtitle = "Live Web & Reader",
                                accent = c.accentAzure,
                                tag = "hub_card_browser",
                                onClick = { onOpenTab("browser") },
                                modifier = Modifier.weight(1f)
                            )
                            HubBentoTile(
                                icon = Icons.Filled.AccountBalanceWallet,
                                title = "Crypto Wallet",
                                subtitle = "Multi-chain Vaults",
                                accent = c.accentTeal,
                                tag = "hub_card_wallet",
                                onClick = { onOpenTab("wallet") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Bento Grid Row 4: Skills Engine & Memory & Logs
                item {
                    AquaAnimatedEntrance(index = 4) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            HubBentoTile(
                                icon = Icons.Filled.Extension,
                                title = "Skills Engine",
                                subtitle = "${skills.count { it.enabled }} enabled • ${skills.size} total",
                                accent = c.accentCyan,
                                tag = "hub_card_skills",
                                onClick = { onOpenTab("skills") },
                                modifier = Modifier.weight(1f)
                            )
                            HubBentoTile(
                                icon = Icons.Filled.Memory,
                                title = "Memory & Logs",
                                subtitle = "${conversations.size} chats • ${memories.size} notes",
                                accent = c.accentAzure,
                                tag = "hub_card_memory",
                                onClick = { onOpenTab("memory") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Bento Grid Row 5: Settings, Age Tier & Profile
                item {
                    AquaAnimatedEntrance(index = 5) {
                        HubBentoTile(
                            icon = if (ageTier == AgeTier.UNSET || ageTier == AgeTier.UNDER_13_BLOCKED) {
                                Icons.Filled.Person
                            } else {
                                Icons.Filled.Settings
                            },
                            title = "Settings, Age & Profile",
                            subtitle = "${ageTier.shortBadge} • ${gender.label} • AI Freedom & Legal",
                            accent = c.amber,
                            tag = "hub_card_settings",
                            onClick = { onOpenTab("settings") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Live Session Device Telemetry Sparkline
                item {
                    AquaAnimatedEntrance(index = 6) {
                        TelemetrySparklineCard(
                            telemetry = telemetry,
                            stats = stats,
                            samples = samples,
                            onOpenSettingsSection = { section -> viewModel.deviceController.openAndroidSettings(section) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HubBentoTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = AquaTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    AquaGlassCard(
        modifier = modifier
            .aquaPressScale(interactionSource)
            .clip(RoundedCornerShape(22.dp))
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick)
            .testTag(tag)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                color = accent.copy(alpha = 0.16f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = title, tint = accent, modifier = Modifier.size(22.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
