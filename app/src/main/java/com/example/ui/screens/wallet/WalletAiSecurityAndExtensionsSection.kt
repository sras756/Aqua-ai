package com.example.ui.screens.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AquaAbyss
import com.example.ui.theme.AquaAmber
import com.example.ui.theme.AquaAzure
import com.example.ui.theme.AquaBorderGlow
import com.example.ui.theme.AquaCardSurface
import com.example.ui.theme.AquaCoralError
import com.example.ui.theme.AquaCyan
import com.example.ui.theme.AquaDeepSurface
import com.example.ui.theme.AquaIceText
import com.example.ui.theme.AquaMintSuccess
import com.example.ui.theme.AquaMutedText
import com.example.ui.theme.AquaTeal
import com.example.data.model.UserSettings
import com.example.wallet.core.AquaWalletCoordinator
import com.example.wallet.core.DefaultWalletRegistry
import com.example.wallet.core.WalletAccountInfo
import com.example.wallet.core.WalletMetadata
import com.example.wallet.core.WalletSecuritySettings
import com.example.wallet.security.BiometricAuthHelper
import java.util.Locale

@Composable
fun WalletAiAssistantSection(
    settings: UserSettings = UserSettings(),
    onUpdateSettings: (UserSettings) -> Unit = {},
    onSendAiWalletPrompt: (String) -> Unit
) {
    var customPrompt by remember { mutableStateOf("") }
    val presets = listOf(
        "Show my EVM, Solana, and Bitcoin receiving addresses and active network.",
        "Analyze my wallet portfolio allocation, concentration, and security posture.",
        "Check live EVM gas price, block height, and my account nonce on Ethereum.",
        "Check live gas and network transfer fees across Ethereum, Solana, and Bitcoin.",
        "Inspect my pending and confirmed wallet transaction history.",
        "Show the 7-day CoinGecko market chart for ETH and SOL in USD."
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AquaAbyss),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, AquaCyan.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = AquaCyan)
                            Spacer(Modifier.width(8.dp))
                            Text("Aqua AI Wallet Copilot (Zero-Key Access)", style = MaterialTheme.typography.titleMedium, color = AquaIceText)
                        }
                        Surface(
                            color = if (settings.aiWalletAccessEnabled) AquaMintSuccess.copy(alpha = 0.18f) else AquaCoralError.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = when {
                                    !settings.aiWalletAccessEnabled -> "PAUSED"
                                    settings.aiWalletTransferPrepareEnabled -> "ACTIVE"
                                    else -> "READ-ONLY"
                                },
                                color = if (settings.aiWalletAccessEnabled) AquaMintSuccess else AquaCoralError,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Grant Wallet Read & Analysis Access", style = MaterialTheme.typography.bodyMedium, color = AquaIceText, fontWeight = FontWeight.SemiBold)
                            Text("Balances, addresses, history, EVM telemetry, ENS, allowances & charts.", style = MaterialTheme.typography.labelSmall, color = AquaMutedText)
                        }
                        Switch(
                            checked = settings.aiWalletAccessEnabled,
                            onCheckedChange = { enabled ->
                                onUpdateSettings(
                                    settings.copy(
                                        aiWalletAccessEnabled = enabled,
                                        aiWalletTransferPrepareEnabled = if (enabled) settings.aiWalletTransferPrepareEnabled else false
                                    )
                                )
                            },
                            modifier = Modifier.testTag("wallet_ai_read_access_switch")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Allow AI Transfer & Revoke Proposals", style = MaterialTheme.typography.bodyMedium, color = AquaIceText, fontWeight = FontWeight.SemiBold)
                            Text("Always gated by your explicit approval dialog + PIN/Biometrics.", style = MaterialTheme.typography.labelSmall, color = AquaMutedText)
                        }
                        Switch(
                            checked = settings.aiWalletAccessEnabled && settings.aiWalletTransferPrepareEnabled,
                            enabled = settings.aiWalletAccessEnabled,
                            onCheckedChange = { enabled ->
                                onUpdateSettings(settings.copy(aiWalletTransferPrepareEnabled = enabled))
                            },
                            modifier = Modifier.testTag("wallet_ai_transfer_access_switch")
                        )
                    }

                    Text(
                        text = "Blocked by Architecture: AI can NEVER view recovery phrases, seeds, private keys, PINs, raise spending limits, unlock Emergency Lock, or broadcast unapproved transactions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AquaAmber
                    )
                }
            }
        }

        item {
            OutlinedTextField(
                value = customPrompt,
                onValueChange = { customPrompt = it },
                label = { Text("Ask Aqua AI about your wallet") },
                placeholder = { Text("e.g. Check my ETH balance, resolve vitalik.eth, or prepare 0.01 SOL transfer") },
                minLines = 2,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AquaCyan,
                    unfocusedBorderColor = AquaBorderGlow,
                    focusedTextColor = AquaIceText,
                    unfocusedTextColor = AquaIceText
                ),
                modifier = Modifier.fillMaxWidth().testTag("wallet_ai_prompt_input")
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (customPrompt.isNotBlank()) {
                        val p = customPrompt.trim()
                        customPrompt = ""
                        onSendAiWalletPrompt(p)
                    }
                },
                enabled = customPrompt.isNotBlank() && settings.aiWalletAccessEnabled,
                colors = ButtonDefaults.buttonColors(containerColor = AquaCyan, contentColor = AquaAbyss),
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("wallet_ai_ask_button")
            ) {
                Text(
                    text = if (settings.aiWalletAccessEnabled) "Run in Aqua Core Assistant" else "Enable Wallet Access Above First",
                    fontWeight = FontWeight.Bold
                )
            }
        }

        item {
            Text("QUICK INTELLIGENCE PROMPTS", style = MaterialTheme.typography.labelMedium, color = AquaCyan)
        }

        items(presets) { preset ->
            OutlinedButton(
                onClick = { onSendAiWalletPrompt(preset) },
                enabled = settings.aiWalletAccessEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = preset,
                    color = if (settings.aiWalletAccessEnabled) AquaIceText else AquaMutedText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
fun WalletSecurityRecoveryAndExtensionsSection(
    walletMeta: WalletMetadata,
    accounts: List<WalletAccountInfo>,
    activeAccountIndex: Int,
    securitySettings: WalletSecuritySettings,
    revealedMnemonic: String?,
    exportedBackupJson: String?,
    coordinator: AquaWalletCoordinator,
    onCopyText: (String, String) -> Unit,
    onShareText: (String, String) -> Unit
) {
    val context = LocalContext.current
    val biometricSupported = remember { BiometricAuthHelper.isBiometricAvailable(context) }

    var pinNewInput by remember { mutableStateOf("") }
    var pinClearInput by remember { mutableStateOf("") }
    var dailyLimitInput by remember(securitySettings.dailyLimitUsd) {
        mutableStateOf(securitySettings.dailyLimitUsd.toInt().toString())
    }
    var perTxLimitInput by remember(securitySettings.perTxLimitUsd) {
        mutableStateOf(securitySettings.perTxLimitUsd.toInt().toString())
    }
    var newAccountLabel by remember { mutableStateOf("") }
    var verifyWord1 by remember { mutableStateOf("") }
    var verifyWord3 by remember { mutableStateOf("") }
    var backupPassword by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AquaAbyss),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Multi-Account Derivation
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, AquaBorderGlow, RoundedCornerShape(18.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AccountTree, contentDescription = null, tint = AquaCyan)
                        Spacer(Modifier.width(8.dp))
                        Text("Multi-Account HD Derivation", style = MaterialTheme.typography.titleMedium, color = AquaIceText)
                    }
                    accounts.forEach { acc ->
                        Surface(
                            color = AquaDeepSurface,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${acc.label} (Index #${acc.accountIndex})", color = AquaIceText, style = MaterialTheme.typography.titleSmall)
                                    Text("EVM: ${acc.evmAddress.take(14)}... | BTC: ${acc.bitcoinMainnetAddress.take(12)}...", color = AquaMutedText, style = MaterialTheme.typography.labelSmall)
                                }
                                if (acc.accountIndex == activeAccountIndex) {
                                    Text("Active", color = AquaMintSuccess, style = MaterialTheme.typography.labelMedium)
                                } else {
                                    OutlinedButton(onClick = { coordinator.selectAccount(acc.accountIndex) }) {
                                        Text("Switch", color = AquaCyan)
                                    }
                                }
                            }
                        }
                    }
                    if (!walletMeta.isWatchOnly) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            WalletField(newAccountLabel, { newAccountLabel = it }, "New Account Label", "Trading", modifier = Modifier.weight(1f))
                            Button(
                                onClick = {
                                    coordinator.deriveNewAccount(newAccountLabel)
                                    newAccountLabel = ""
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AquaCyan, contentColor = AquaAbyss),
                                modifier = Modifier.height(48.dp).testTag("security_derive_account_button")
                            ) {
                                Text("Derive", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Security Center: PIN, Biometric & Emergency Lock
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, AquaBorderGlow, RoundedCornerShape(18.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Security, contentDescription = null, tint = AquaCyan)
                        Spacer(Modifier.width(8.dp))
                        Text("Security Center & Vault Locks", style = MaterialTheme.typography.titleMedium, color = AquaIceText)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Biometric Unlock & Signing", color = AquaIceText, style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (biometricSupported) "Use device fingerprint / face authentication" else "No biometric hardware enrolled on this device",
                                color = AquaMutedText,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = securitySettings.biometricEnabled,
                            enabled = biometricSupported,
                            onCheckedChange = { coordinator.setBiometricEnabled(it) },
                            modifier = Modifier.testTag("security_biometric_switch")
                        )
                    }

                    if (!securitySettings.hasPin) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = pinNewInput,
                                onValueChange = { if (it.length <= 12 && it.all(Char::isDigit)) pinNewInput = it },
                                label = { Text("Set Wallet PIN (4-12 digits)") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AquaCyan,
                                    unfocusedBorderColor = AquaBorderGlow,
                                    focusedTextColor = AquaIceText,
                                    unfocusedTextColor = AquaIceText
                                ),
                                modifier = Modifier.weight(1f).testTag("security_set_pin_input")
                            )
                            Button(
                                onClick = {
                                    coordinator.setPin(pinNewInput)
                                    pinNewInput = ""
                                },
                                enabled = pinNewInput.length >= 4,
                                colors = ButtonDefaults.buttonColors(containerColor = AquaCyan, contentColor = AquaAbyss),
                                modifier = Modifier.height(48.dp).testTag("security_set_pin_button")
                            ) {
                                Text("Set PIN", fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = pinClearInput,
                                onValueChange = { if (it.length <= 12 && it.all(Char::isDigit)) pinClearInput = it },
                                label = { Text("Current PIN (to remove)") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AquaAmber,
                                    unfocusedBorderColor = AquaBorderGlow,
                                    focusedTextColor = AquaIceText,
                                    unfocusedTextColor = AquaIceText
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(
                                onClick = {
                                    coordinator.clearPin(pinClearInput)
                                    pinClearInput = ""
                                },
                                enabled = pinClearInput.length >= 4,
                                modifier = Modifier.height(48.dp)
                            ) {
                                Text("Remove PIN", color = AquaAmber)
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { coordinator.lockNow() },
                            modifier = Modifier.weight(1f).height(48.dp).testTag("security_lock_now_button")
                        ) {
                            Icon(Icons.Filled.Lock, contentDescription = null, tint = AquaCyan, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Lock Session", color = AquaCyan)
                        }
                        Button(
                            onClick = { coordinator.triggerEmergencyLock() },
                            colors = ButtonDefaults.buttonColors(containerColor = AquaCoralError, contentColor = AquaAbyss),
                            modifier = Modifier.weight(1.2f).height(48.dp).testTag("security_emergency_lock_button")
                        ) {
                            Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Emergency Lock", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Spending Limits
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, AquaBorderGlow, RoundedCornerShape(18.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Transaction & Daily Spending Limits", style = MaterialTheme.typography.titleMedium, color = AquaIceText)
                    Text(
                        text = "Spent today: $${"%.2f".format(Locale.US, securitySettings.spentTodayUsd)} / $${"%.2f".format(Locale.US, securitySettings.dailyLimitUsd)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AquaTeal
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WalletField(perTxLimitInput, { if (it.all(Char::isDigit)) perTxLimitInput = it }, "Per-Tx USD", "500", modifier = Modifier.weight(1f))
                        WalletField(dailyLimitInput, { if (it.all(Char::isDigit)) dailyLimitInput = it }, "Daily USD", "1000", modifier = Modifier.weight(1f))
                    }
                    Button(
                        onClick = {
                            val perTx = perTxLimitInput.toDoubleOrNull() ?: 500.0
                            val daily = dailyLimitInput.toDoubleOrNull() ?: 1000.0
                            coordinator.updateSpendingLimits(daily, perTx)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AquaAzure, contentColor = AquaIceText),
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("security_save_limits_button")
                    ) {
                        Text("Save Spending Limits")
                    }
                }
            }
        }

        // Recovery Phrase & Portable Encrypted Backup
        if (!walletMeta.isWatchOnly) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, AquaAmber.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Backup, contentDescription = null, tint = AquaAmber)
                            Spacer(Modifier.width(8.dp))
                            Text("Recovery Phrase & Portable Backup", style = MaterialTheme.typography.titleMedium, color = AquaIceText)
                        }
                        Text(
                            text = "Never share your BIP-39 phrase. Portable backups use PBKDF2-HMAC-SHA256 (120k rounds) + AES-256-GCM independent of Firebase.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AquaMutedText
                        )

                        Button(
                            onClick = {
                                if (revealedMnemonic == null) coordinator.revealMnemonic() else coordinator.clearRevealedMnemonic()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AquaAmber, contentColor = AquaAbyss),
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("backup_reveal_mnemonic_button")
                        ) {
                            Icon(
                                if (revealedMnemonic == null) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (revealedMnemonic == null) "Reveal Recovery Phrase" else "Hide Recovery Phrase", fontWeight = FontWeight.Bold)
                        }

                        if (!revealedMnemonic.isNullOrBlank()) {
                            Surface(
                                color = AquaDeepSurface,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().border(1.dp, AquaAmber, RoundedCornerShape(12.dp))
                            ) {
                                Text(
                                    text = revealedMnemonic,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AquaIceText,
                                    modifier = Modifier.padding(12.dp).testTag("backup_mnemonic_text")
                                )
                            }

                            if (!walletMeta.backupVerified) {
                                Text("Confirm Word #1 & Word #3 to mark backup verified:", color = AquaCyan, style = MaterialTheme.typography.labelMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    WalletField(verifyWord1, { verifyWord1 = it }, "Word #1", "first word", modifier = Modifier.weight(1f))
                                    WalletField(verifyWord3, { verifyWord3 = it }, "Word #3", "third word", modifier = Modifier.weight(1f))
                                }
                                OutlinedButton(
                                    onClick = {
                                        coordinator.verifyBackupWords(mapOf(0 to verifyWord1, 2 to verifyWord3))
                                    },
                                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("backup_verify_words_button")
                                ) {
                                    Text("Verify Recovery Backup", color = AquaMintSuccess)
                                }
                            }
                        }

                        WalletField(backupPassword, { backupPassword = it }, "Backup Encryption Password (min 8 chars)", "Strong passphrase", isSecret = true)
                        OutlinedButton(
                            onClick = {
                                coordinator.exportEncryptedBackup(backupPassword)
                            },
                            enabled = backupPassword.length >= 8,
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("backup_export_encrypted_button")
                        ) {
                            Text("Generate Encrypted JSON Backup", color = AquaCyan)
                        }

                        if (!exportedBackupJson.isNullOrBlank()) {
                            Surface(
                                color = AquaDeepSurface,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = exportedBackupJson,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AquaIceText,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { onCopyText("Aqua Encrypted Backup", exportedBackupJson) },
                                    modifier = Modifier.weight(1f).height(48.dp)
                                ) {
                                    Text("Copy JSON", color = AquaCyan)
                                }
                                OutlinedButton(
                                    onClick = { onShareText("Aqua Encrypted Wallet Backup", exportedBackupJson) },
                                    modifier = Modifier.weight(1f).height(48.dp)
                                ) {
                                    Text("Share", color = AquaCyan)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Guarded Architectural Extension Points
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Extension, contentDescription = null, tint = AquaTeal)
                Spacer(Modifier.width(8.dp))
                Text("MODULAR EXTENSION BOUNDARIES", style = MaterialTheme.typography.labelLarge, color = AquaTeal)
            }
        }

        items(DefaultWalletRegistry.extensionPoints, key = { it.id }) { ext ->
            Card(
                colors = CardDefaults.cardColors(containerColor = AquaDeepSurface),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, AquaBorderGlow, RoundedCornerShape(14.dp))
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(ext.title, color = AquaIceText, style = MaterialTheme.typography.titleSmall)
                        Surface(color = AquaCardSurface, shape = RoundedCornerShape(6.dp)) {
                            Text(
                                text = ext.statusLabel,
                                color = AquaAmber,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(ext.description, color = AquaMutedText, style = MaterialTheme.typography.bodySmall)
                    Text("Security Gate: ${ext.securityRequirement}", color = AquaCyan, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        item {
            OutlinedButton(
                onClick = { coordinator.deleteWallet(walletMeta.walletId) },
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("security_delete_wallet_button")
            ) {
                Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = AquaCoralError, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Remove '${walletMeta.label}' from Device", color = AquaCoralError)
            }
        }
    }
}
