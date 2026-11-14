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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.fragment.app.FragmentActivity
import com.example.ui.theme.AquaAbyss
import com.example.ui.theme.AquaAmber
import com.example.ui.theme.AquaBorderGlow
import com.example.ui.theme.AquaCardSurface
import com.example.ui.theme.AquaCoralError
import com.example.ui.theme.AquaCyan
import com.example.ui.theme.AquaDeepSurface
import com.example.ui.theme.AquaIceText
import com.example.ui.theme.AquaMutedText
import com.example.wallet.core.AquaWalletCoordinator
import com.example.wallet.core.WalletSecuritySettings
import com.example.wallet.security.BiometricAuthHelper

@Composable
fun WalletLockGateScreen(
    settings: WalletSecuritySettings,
    coordinator: AquaWalletCoordinator
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var pinInput by remember { mutableStateOf("") }
    var authFeedback by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AquaAbyss)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (settings.emergencyLocked) Icons.Filled.Warning else Icons.Filled.Lock,
            contentDescription = "Wallet locked",
            tint = if (settings.emergencyLocked) AquaCoralError else AquaCyan,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = if (settings.emergencyLocked) "EMERGENCY WALLET LOCK ACTIVE" else "Aqua Wallet Locked",
            style = MaterialTheme.typography.headlineSmall,
            color = if (settings.emergencyLocked) AquaCoralError else AquaIceText,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (settings.emergencyLocked) {
                "All private key access, signing, and outbound transfers are frozen on this device. Authenticate to lift Emergency Lock."
            } else {
                "Verify your Wallet PIN or Biometrics to unlock local signing and recovery backup access."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = AquaMutedText
        )
        Spacer(Modifier.height(20.dp))

        if (settings.hasPin) {
            OutlinedTextField(
                value = pinInput,
                onValueChange = { if (it.length <= 12 && it.all(Char::isDigit)) pinInput = it },
                label = { Text("Wallet PIN") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AquaCyan,
                    unfocusedBorderColor = AquaBorderGlow,
                    focusedTextColor = AquaIceText,
                    unfocusedTextColor = AquaIceText
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("wallet_unlock_pin_input")
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val ok = if (settings.emergencyLocked) {
                        coordinator.clearEmergencyLockWithPin(pinInput)
                    } else {
                        coordinator.unlockWithPin(pinInput)
                    }
                    if (!ok) authFeedback = "Incorrect PIN." else pinInput = ""
                },
                enabled = pinInput.length >= 4,
                colors = ButtonDefaults.buttonColors(containerColor = AquaCyan, contentColor = AquaAbyss),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("wallet_unlock_pin_button")
            ) {
                Text(
                    if (settings.emergencyLocked) "Clear Emergency Lock with PIN" else "Unlock with PIN",
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (settings.biometricEnabled && activity != null && BiometricAuthHelper.isBiometricAvailable(context)) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    BiometricAuthHelper.authenticate(
                        activity = activity,
                        title = if (settings.emergencyLocked) "Deactivate Emergency Lock" else "Unlock Aqua Crypto Wallet",
                        subtitle = "Biometric verification stays strictly on-device",
                        onSuccess = {
                            if (settings.emergencyLocked) {
                                coordinator.clearEmergencyLockWithBiometric()
                            } else {
                                coordinator.unlockWithBiometric()
                            }
                        },
                        onError = { err -> authFeedback = err }
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("wallet_unlock_biometric_button")
            ) {
                Icon(Icons.Filled.Fingerprint, contentDescription = null, tint = AquaCyan)
                Spacer(Modifier.width(8.dp))
                Text("Authenticate with Biometrics", color = AquaCyan)
            }
        } else if (!settings.hasPin && settings.emergencyLocked) {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { coordinator.clearEmergencyLockWithPin("") },
                colors = ButtonDefaults.buttonColors(containerColor = AquaCoralError, contentColor = AquaAbyss),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("wallet_clear_emergency_no_pin")
            ) {
                Text("Deactivate Emergency Lock")
            }
        }

        authFeedback?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = AquaAmber, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun WalletOnboardingView(
    coordinator: AquaWalletCoordinator,
    busy: Boolean,
    showCancel: Boolean = false,
    onDismiss: () -> Unit = {}
) {
    var mode by remember { mutableIntStateOf(0) } // 0 Create, 1 Import Phrase, 2 Watch Only, 3 Restore Encrypted JSON
    var label by remember { mutableStateOf("") }
    var wordCount by remember { mutableIntStateOf(12) }
    var passphrase by remember { mutableStateOf("") }
    var mnemonicInput by remember { mutableStateOf("") }
    var watchEvm by remember { mutableStateOf("") }
    var watchSol by remember { mutableStateOf("") }
    var watchBtc by remember { mutableStateOf("") }
    var backupJson by remember { mutableStateOf("") }
    var backupPassword by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AquaAbyss),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Surface(
                color = AquaCardSurface,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, AquaCyan.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(Icons.Filled.Security, contentDescription = null, tint = AquaCyan)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Self-Custodial Native Multi-Chain Vault",
                            style = MaterialTheme.typography.titleMedium,
                            color = AquaIceText
                        )
                        Text(
                            text = "Entropy is generated locally via SecureRandom and encrypted inside Android Keystore AES-256-GCM. Keys never touch Firebase, AI models, or external servers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AquaMutedText
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Create", "Phrase", "Watch", "Backup").forEachIndexed { idx, title ->
                    FilterChip(
                        selected = mode == idx,
                        onClick = { mode = idx },
                        label = { Text(title) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AquaCyan.copy(alpha = 0.2f),
                            selectedLabelColor = AquaCyan,
                            labelColor = AquaMutedText
                        ),
                        modifier = Modifier.weight(1f).testTag("onboarding_mode_$idx")
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, AquaBorderGlow, RoundedCornerShape(20.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (mode) {
                        0 -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.AddCircleOutline, contentDescription = null, tint = AquaCyan)
                                Spacer(Modifier.width(8.dp))
                                Text("Create New Multi-Chain Wallet", style = MaterialTheme.typography.titleMedium, color = AquaIceText)
                            }
                            Text(
                                "Generates 128-bit (12 words) or 256-bit (24 words) BIP-39 phrase and derives Ethereum, BSC, Polygon, Base, Arbitrum, Solana & Bitcoin SegWit accounts.",
                                style = MaterialTheme.typography.bodySmall,
                                color = AquaMutedText
                            )
                            WalletField(label, { label = it }, "Wallet Label", "Aqua Primary")
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                listOf(12, 24).forEach { count ->
                                    FilterChip(
                                        selected = wordCount == count,
                                        onClick = { wordCount = count },
                                        label = { Text("$count Words") },
                                        modifier = Modifier.testTag("create_word_count_$count")
                                    )
                                }
                            }
                            WalletField(passphrase, { passphrase = it }, "Optional BIP-39 Passphrase", "Leave empty for standard derivation", isSecret = true)
                            Button(
                                onClick = {
                                    coordinator.createWallet(label.ifBlank { "Aqua Primary Wallet" }, wordCount, passphrase)
                                    if (showCancel) onDismiss()
                                },
                                enabled = !busy,
                                colors = ButtonDefaults.buttonColors(containerColor = AquaCyan, contentColor = AquaAbyss),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("wallet_create_submit_button")
                            ) {
                                Text("Generate Native Wallet", fontWeight = FontWeight.Bold)
                            }
                        }

                        1 -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Key, contentDescription = null, tint = AquaCyan)
                                Spacer(Modifier.width(8.dp))
                                Text("Import BIP-39 Recovery Phrase", style = MaterialTheme.typography.titleMedium, color = AquaIceText)
                            }
                            WalletField(label, { label = it }, "Wallet Label", "Imported Wallet")
                            OutlinedTextField(
                                value = mnemonicInput,
                                onValueChange = { mnemonicInput = it },
                                label = { Text("12 or 24 Recovery Words") },
                                placeholder = { Text("Enter space-separated BIP-39 words") },
                                minLines = 3,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AquaCyan,
                                    unfocusedBorderColor = AquaBorderGlow,
                                    focusedTextColor = AquaIceText,
                                    unfocusedTextColor = AquaIceText
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("wallet_import_mnemonic_input")
                            )
                            WalletField(passphrase, { passphrase = it }, "Optional BIP-39 Passphrase", "Leave empty if none", isSecret = true)
                            Button(
                                onClick = {
                                    coordinator.importMnemonicWallet(label.ifBlank { "Imported Wallet" }, mnemonicInput, passphrase)
                                    if (showCancel) onDismiss()
                                },
                                enabled = !busy && mnemonicInput.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = AquaCyan, contentColor = AquaAbyss),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("wallet_import_mnemonic_button")
                            ) {
                                Text("Validate & Import Wallet", fontWeight = FontWeight.Bold)
                            }
                        }

                        2 -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Visibility, contentDescription = null, tint = AquaAmber)
                                Spacer(Modifier.width(8.dp))
                                Text("Add Watch-Only Portfolio", style = MaterialTheme.typography.titleMedium, color = AquaIceText)
                            }
                            Text("Monitor live on-chain balances & transactions without storing private keys.", style = MaterialTheme.typography.bodySmall, color = AquaMutedText)
                            WalletField(label, { label = it }, "Label", "Cold Storage Watch")
                            WalletField(watchEvm, { watchEvm = it }, "EVM Address (Optional)", "0x...")
                            WalletField(watchSol, { watchSol = it }, "Solana Address (Optional)", "Base58 public key")
                            WalletField(watchBtc, { watchBtc = it }, "Bitcoin Address (Optional)", "bc1q... or tb1q...")
                            Button(
                                onClick = {
                                    coordinator.importWatchOnlyWallet(label.ifBlank { "Watch-Only Portfolio" }, watchEvm, watchSol, watchBtc)
                                    if (showCancel) onDismiss()
                                },
                                enabled = !busy && (watchEvm.isNotBlank() || watchSol.isNotBlank() || watchBtc.isNotBlank()),
                                colors = ButtonDefaults.buttonColors(containerColor = AquaAmber, contentColor = AquaAbyss),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("wallet_import_watch_button")
                            ) {
                                Text("Add Watch-Only Wallet", fontWeight = FontWeight.Bold)
                            }
                        }

                        3 -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Restore, contentDescription = null, tint = AquaCyan)
                                Spacer(Modifier.width(8.dp))
                                Text("Restore Password-Encrypted Backup", style = MaterialTheme.typography.titleMedium, color = AquaIceText)
                            }
                            Text("Restores portable aqua_encrypted_wallet_v1 JSON independently of Firebase.", style = MaterialTheme.typography.bodySmall, color = AquaMutedText)
                            OutlinedTextField(
                                value = backupJson,
                                onValueChange = { backupJson = it },
                                label = { Text("Encrypted Backup JSON") },
                                minLines = 3,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AquaCyan,
                                    unfocusedBorderColor = AquaBorderGlow,
                                    focusedTextColor = AquaIceText,
                                    unfocusedTextColor = AquaIceText
                                ),
                                modifier = Modifier.fillMaxWidth().testTag("wallet_restore_json_input")
                            )
                            WalletField(backupPassword, { backupPassword = it }, "Backup Decryption Password", "Enter encryption password", isSecret = true)
                            Button(
                                onClick = {
                                    coordinator.restoreEncryptedBackup(backupJson, backupPassword)
                                    if (showCancel) onDismiss()
                                },
                                enabled = !busy && backupJson.isNotBlank() && backupPassword.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = AquaCyan, contentColor = AquaAbyss),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("wallet_restore_backup_button")
                            ) {
                                Text("Decrypt & Restore Wallet", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (showCancel) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("Cancel", color = AquaMutedText)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun WalletField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    isSecret: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        visualTransformation = if (isSecret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AquaCyan,
            unfocusedBorderColor = AquaBorderGlow,
            focusedTextColor = AquaIceText,
            unfocusedTextColor = AquaIceText
        ),
        modifier = modifier.fillMaxWidth()
    )
}
