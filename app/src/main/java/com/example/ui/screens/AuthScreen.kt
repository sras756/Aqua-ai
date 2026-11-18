package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import com.example.R
import com.example.security.GoogleSignatureVerifier
import com.example.ui.auth.attemptAutoSignIn
import com.example.ui.auth.onGoogleSignInClicked
import com.example.ui.components.AquaAnimatedEntrance
import com.example.ui.components.AquaBackdropBox
import com.example.ui.components.AquaGlassCard
import com.example.ui.components.AquaStatusPill
import com.example.ui.components.AquaStatusPulseDot
import com.example.ui.components.aquaPressScale
import com.example.ui.theme.AquaTheme

@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit
) {
    val c = AquaTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }
    val signatureReport = remember { GoogleSignatureVerifier.verifyAppSignature(context) }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val signInInteraction = remember { MutableInteractionSource() }

    LaunchedEffect(Unit) {
        attemptAutoSignIn(
            context = context,
            credentialManager = credentialManager,
            onAuthSuccess = onAuthSuccess,
            onUnauthenticated = {},
            scope = scope
        )
    }

    AquaBackdropBox(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                AquaAnimatedEntrance(index = 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(228.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .border(1.dp, c.borderGlow, RoundedCornerShape(28.dp))
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.img_aqua_aurora_backdrop),
                            contentDescription = "Aqua AI Neural Core Banner",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(Color.Transparent, Color(0xFF030B16).copy(alpha = 0.92f))
                                    )
                                )
                        )
                        Image(
                            painter = painterResource(id = R.drawable.img_aqua_vault_emblem),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp)
                                .size(58.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .border(1.dp, c.accentCyan.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                        )
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(20.dp)
                        ) {
                            Surface(
                                color = c.accentCyan.copy(alpha = 0.18f),
                                shape = CircleShape,
                                modifier = Modifier.border(1.dp, c.accentCyan.copy(alpha = 0.5f), CircleShape)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AquaStatusPulseDot(color = c.accentCyan, size = 7.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.Filled.AutoAwesome,
                                        contentDescription = null,
                                        tint = c.accentCyan,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "ANDROID AUTONOMOUS CORE",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = c.accentCyan
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "AQUA AI",
                                style = MaterialTheme.typography.displaySmall,
                                color = Color(0xFFEAF6FF)
                            )
                            Text(
                                text = "Voice Core, Routines, Drive Vault, Android SDK & Multi-Chain Wallet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFB4D0E8)
                            )
                        }
                    }
                }

                AquaAnimatedEntrance(index = 1) {
                    AquaGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            SecurityFeatureRow(
                                icon = Icons.Filled.Shield,
                                title = "Zero-Trust Cloud & Age 13+ Policy",
                                subtitle = "Conversations, age-tiered guardrails (13–17 Teen Mode / 18+), and skills sync privately to your Google account."
                            )
                            SecurityFeatureRow(
                                icon = Icons.Filled.Memory,
                                title = "Multi-Persona Gemini & ElevenLabs",
                                subtitle = "Adaptive personas, Gemini Live voice, optional ElevenLabs Voice ID, & Google AI Studio BYOK."
                            )
                            SecurityFeatureRow(
                                icon = Icons.Filled.Lock,
                                title = "Google Signature & Action Gate",
                                subtitle = "Verified APK signing (${signatureReport.overallStatusLabel}); financial & external writes require modal consent."
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "SDK API ${android.os.Build.VERSION.SDK_INT} • Play Services: ${signatureReport.playServicesStatusLabel}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = c.textMuted,
                                    modifier = Modifier.weight(1f)
                                )
                                AquaStatusPill(
                                    label = if (signatureReport.isTrustedForGoogleSignIn) "SIGNATURE OK" else "CHECK SIG",
                                    accent = if (signatureReport.isTrustedForGoogleSignIn) c.success else c.amber
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = errorMessage != null, enter = fadeIn()) {
                    errorMessage?.let { msg ->
                        Surface(
                            color = c.error.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = msg,
                                color = c.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(14.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                AquaAnimatedEntrance(index = 2) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                errorMessage = null
                                isLoading = true
                                onGoogleSignInClicked(
                                    context = context,
                                    credentialManager = credentialManager,
                                    onAuthSuccess = {
                                        isLoading = false
                                        onAuthSuccess()
                                    },
                                    onAuthError = { msg ->
                                        isLoading = false
                                        errorMessage = msg
                                    },
                                    scope = scope,
                                    onAuthCancelled = {
                                        isLoading = false
                                    }
                                )
                            },
                            enabled = !isLoading,
                            interactionSource = signInInteraction,
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = c.accentCyan,
                                contentColor = c.onAccent
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .aquaPressScale(signInInteraction)
                                .testTag("google_sign_in_button")
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    color = c.onAccent,
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier.size(22.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.AccountCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "Sign in with Google",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Text(
                            text = "Eligible for ages 13+ • Protected by Google Play & Zero-Trust Firestore rules",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.textMuted,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SecurityFeatureRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    val c = AquaTheme.colors
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            color = c.accentAzure.copy(alpha = 0.2f),
            shape = CircleShape,
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = c.accentTeal,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        }
    }
}
