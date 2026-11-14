package com.example.ui.screens.wallet

import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import com.example.ui.theme.AquaMintSuccess
import com.example.ui.theme.AquaMutedText
import com.example.ui.theme.AquaTeal
import com.example.wallet.QrCodeGenerator
import com.example.wallet.core.AddressRiskReport
import com.example.wallet.core.AquaWalletCoordinator
import com.example.wallet.core.FeeEstimate
import com.example.wallet.core.NetworkConfig
import com.example.wallet.core.PreparedChainTransaction
import com.example.wallet.core.RiskLevel
import com.example.wallet.core.TokenDefinition
import com.example.wallet.core.WalletAccountInfo
import com.example.wallet.core.WalletMetadata
import com.example.wallet.core.WalletSecuritySettings
import com.example.wallet.security.BiometricAuthHelper
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletSendSection(
    walletMeta: WalletMetadata,
    networks: List<NetworkConfig>,
    selectedNetworkId: String,
    tokens: List<TokenDefinition>,
    selectedTokenId: String,
    toAddress: String,
    amountText: String,
    onSelectNetwork: (String) -> Unit,
    onSelectToken: (String) -> Unit,
    onToAddressChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onOpenScanner: () -> Unit,
    feeEstimate: FeeEstimate?,
    riskReport: AddressRiskReport?,
    preparedTx: PreparedChainTransaction?,
    securitySettings: WalletSecuritySettings,
    isLocked: Boolean,
    busy: Boolean,
    coordinator: AquaWalletCoordinator,
    onBroadcastSuccess: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var netExpanded by remember { mutableStateOf(false) }
    var tokenExpanded by remember { mutableStateOf(false) }
    var confirmPin by remember { mutableStateOf("") }

    val selectedNet = networks.firstOrNull { it.id == selectedNetworkId } ?: networks.firstOrNull()
    val selectedTok = tokens.firstOrNull { it.id == selectedTokenId } ?: tokens.firstOrNull()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AquaAbyss),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (walletMeta.isWatchOnly) {
            item {
                Surface(
                    color = AquaAmber.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, AquaAmber, RoundedCornerShape(14.dp))
                ) {
                    Text(
                        text = "Watch-Only Mode: This wallet holds public addresses only and cannot sign transactions.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AquaAmber,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        }

        if (securitySettings.emergencyLocked) {
            item {
                Surface(
                    color = AquaCoralError.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, AquaCoralError, RoundedCornerShape(14.dp))
                ) {
                    Text(
                        text = "Emergency Wallet Lock Active: All signing and transfers are disabled until cleared in Security Center.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AquaCoralError,
                        modifier = Modifier.padding(14.dp)
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
                    Text(
                        text = "Construct Native Transaction",
                        style = MaterialTheme.typography.titleMedium,
                        color = AquaIceText
                    )

                    ExposedDropdownMenuBox(
                        expanded = netExpanded,
                        onExpandedChange = { netExpanded = !netExpanded }
                    ) {
                        OutlinedTextField(
                            value = selectedNet?.name ?: "Select Network",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Blockchain Network") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = netExpanded) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AquaCyan,
                                unfocusedBorderColor = AquaBorderGlow,
                                focusedTextColor = AquaIceText,
                                unfocusedTextColor = AquaIceText
                            ),
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = netExpanded,
                            onDismissRequest = { netExpanded = false }
                        ) {
                            networks.forEach { n ->
                                DropdownMenuItem(
                                    text = { Text("${n.name} (${n.nativeSymbol})${if (n.isTestnet) " [Testnet]" else ""}") },
                                    onClick = {
                                        onSelectNetwork(n.id)
                                        netExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    ExposedDropdownMenuBox(
                        expanded = tokenExpanded,
                        onExpandedChange = { tokenExpanded = !tokenExpanded }
                    ) {
                        OutlinedTextField(
                            value = selectedTok?.let { "${it.symbol} — ${it.name}" } ?: "Select Asset",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Asset") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = tokenExpanded) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AquaCyan,
                                unfocusedBorderColor = AquaBorderGlow,
                                focusedTextColor = AquaIceText,
                                unfocusedTextColor = AquaIceText
                            ),
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = tokenExpanded,
                            onDismissRequest = { tokenExpanded = false }
                        ) {
                            tokens.forEach { t ->
                                DropdownMenuItem(
                                    text = { Text("${t.symbol} — ${t.name}") },
                                    onClick = {
                                        onSelectToken(t.id)
                                        tokenExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = toAddress,
                            onValueChange = onToAddressChange,
                            label = { Text("Recipient Address") },
                            placeholder = { Text("0x... / bc1q... / Solana address") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AquaCyan,
                                unfocusedBorderColor = AquaBorderGlow,
                                focusedTextColor = AquaIceText,
                                unfocusedTextColor = AquaIceText
                            ),
                            modifier = Modifier.weight(1f).testTag("send_recipient_input")
                        )
                        OutlinedButton(
                            onClick = onOpenScanner,
                            modifier = Modifier.height(54.dp).testTag("send_scan_qr_button")
                        ) {
                            Icon(Icons.Filled.QrCodeScanner, contentDescription = "Scan recipient QR", tint = AquaCyan)
                        }
                    }

                    OutlinedTextField(
                        value = amountText,
                        onValueChange = onAmountChange,
                        label = { Text("Amount (${selectedTok?.symbol ?: ""})") },
                        placeholder = { Text("0.01") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AquaCyan,
                            unfocusedBorderColor = AquaBorderGlow,
                            focusedTextColor = AquaIceText,
                            unfocusedTextColor = AquaIceText
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("send_amount_input")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (selectedNet != null && selectedTok != null) {
                                    coordinator.estimateFee(selectedNet.id, selectedTok.id, toAddress, amountText)
                                }
                            },
                            enabled = selectedNet != null && selectedTok != null && !busy,
                            modifier = Modifier.weight(1f).height(48.dp).testTag("send_estimate_fee_button")
                        ) {
                            Text("Estimate Fee", color = AquaTeal)
                        }
                        OutlinedButton(
                            onClick = {
                                if (selectedNet != null && toAddress.isNotBlank()) {
                                    coordinator.analyzeAddressRisk(selectedNet.id, toAddress)
                                }
                            },
                            enabled = selectedNet != null && toAddress.isNotBlank() && !busy,
                            modifier = Modifier.weight(1f).height(48.dp).testTag("send_audit_risk_button")
                        ) {
                            Text("Risk Audit", color = AquaAmber)
                        }
                    }

                    feeEstimate?.let { est ->
                        Surface(
                            color = AquaDeepSurface,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                val usdStr = est.estimatedFeeUsd?.let { " (~$${"%.4f".format(Locale.US, it)} USD)" } ?: ""
                                Text("Estimated Fee: ${est.estimatedFeeDisplay}$usdStr", color = AquaCyan, style = MaterialTheme.typography.labelLarge)
                                Text("Slow: ${est.slowLabel} | Standard: ${est.standardLabel} | Fast: ${est.fastLabel}", color = AquaMutedText, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    riskReport?.let { risk ->
                        val riskColor = when (risk.riskLevel) {
                            RiskLevel.LOW -> AquaMintSuccess
                            RiskLevel.MEDIUM -> AquaAmber
                            RiskLevel.HIGH, RiskLevel.CRITICAL -> AquaCoralError
                        }
                        Surface(
                            color = riskColor.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().border(1.dp, riskColor, RoundedCornerShape(12.dp))
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Security, contentDescription = null, tint = riskColor, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Address Risk: ${risk.riskLevel.name}", color = riskColor, style = MaterialTheme.typography.labelLarge)
                                }
                                risk.findings.forEach { f ->
                                    Text("• $f", color = AquaIceText, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            if (selectedNet != null && selectedTok != null) {
                                coordinator.prepareTransaction(selectedNet.id, selectedTok.id, toAddress, amountText)
                            }
                        },
                        enabled = !walletMeta.isWatchOnly && !securitySettings.emergencyLocked && !busy &&
                            selectedNet != null && selectedTok != null && toAddress.isNotBlank() && amountText.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = AquaCyan, contentColor = AquaAbyss),
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("send_prepare_preview_button")
                    ) {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AquaAbyss)
                        } else {
                            Text("Preview & Simulate Transfer", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Explicit Authorization Preview Card
        preparedTx?.let { prep ->
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AquaDeepSurface),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.5.dp, AquaAmber, RoundedCornerShape(20.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = AquaAmber)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "TRANSACTION SIGNING PREVIEW",
                                style = MaterialTheme.typography.titleMedium,
                                color = AquaAmber,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        PreviewLine("Network", prep.network.name)
                        PreviewLine("From", prep.fromAddress)
                        PreviewLine("Recipient", prep.toAddress)
                        PreviewLine("Amount", "${prep.amountDecimal.stripTrailingZeros().toPlainString()} ${prep.token.symbol}")
                        PreviewLine("Estimated Fee", prep.feeEstimate.estimatedFeeDisplay)
                        PreviewLine("Total Cost", prep.totalCostDisplay)
                        PreviewLine("Pre-Flight Simulation", prep.simulationSummary)
                        PreviewLine("Risk Level", "${prep.riskReport.riskLevel} (${prep.riskReport.findings.firstOrNull().orEmpty()})")

                        if (isLocked && securitySettings.hasPin) {
                            OutlinedTextField(
                                value = confirmPin,
                                onValueChange = { if (it.length <= 12 && it.all(Char::isDigit)) confirmPin = it },
                                label = { Text("Enter Wallet PIN to Sign") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AquaAmber,
                                    unfocusedBorderColor = AquaBorderGlow,
                                    focusedTextColor = AquaIceText,
                                    unfocusedTextColor = AquaIceText
                                ),
                                modifier = Modifier.fillMaxWidth().testTag("send_confirm_pin_input")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { coordinator.clearPreparedTx() },
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) {
                                Text("Cancel", color = AquaMutedText)
                            }
                            Button(
                                onClick = {
                                    coordinator.signAndBroadcastPrepared(
                                        pinAttempt = confirmPin.takeIf { it.isNotBlank() },
                                        biometricVerified = false
                                    ) { res ->
                                        if (res.isSuccess) {
                                            confirmPin = ""
                                            onBroadcastSuccess()
                                        }
                                    }
                                },
                                enabled = !busy && (!isLocked || !securitySettings.hasPin || confirmPin.length >= 4),
                                colors = ButtonDefaults.buttonColors(containerColor = AquaCyan, contentColor = AquaAbyss),
                                modifier = Modifier.weight(1.4f).height(48.dp).testTag("send_sign_broadcast_button")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Sign & Broadcast", fontWeight = FontWeight.Bold)
                            }
                        }

                        if (securitySettings.biometricEnabled && activity != null && BiometricAuthHelper.isBiometricAvailable(context)) {
                            OutlinedButton(
                                onClick = {
                                    BiometricAuthHelper.authenticate(
                                        activity = activity,
                                        title = "Authorize Local Transaction Signing",
                                        subtitle = "Sign ${prep.amountDecimal.stripTrailingZeros().toPlainString()} ${prep.token.symbol} on ${prep.network.name}",
                                        onSuccess = {
                                            coordinator.signAndBroadcastPrepared(biometricVerified = true) { res ->
                                                if (res.isSuccess) onBroadcastSuccess()
                                            }
                                        },
                                        onError = {}
                                    )
                                },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("send_biometric_sign_button")
                            ) {
                                Icon(Icons.Filled.Fingerprint, contentDescription = null, tint = AquaCyan)
                                Spacer(Modifier.width(6.dp))
                                Text("Sign & Broadcast with Biometrics", color = AquaCyan)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewLine(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = AquaMutedText)
        Text(value, style = MaterialTheme.typography.bodySmall, color = AquaIceText)
    }
}

@Composable
fun WalletReceiveSection(
    account: WalletAccountInfo,
    includeTestnets: Boolean,
    onCopyAddress: (String, String) -> Unit,
    onShareAddress: (String, String) -> Unit
) {
    val entries = buildList {
        if (account.evmAddress.isNotBlank()) add("EVM (ETH / BSC / Polygon / Base / ARB)" to account.evmAddress)
        if (account.solanaAddress.isNotBlank()) add("Solana (SOL & SPL)" to account.solanaAddress)
        if (account.bitcoinMainnetAddress.isNotBlank()) add("Bitcoin Mainnet (SegWit)" to account.bitcoinMainnetAddress)
        if (includeTestnets && account.bitcoinTestnetAddress.isNotBlank()) {
            add("Bitcoin Testnet4 (SegWit)" to account.bitcoinTestnetAddress)
        }
    }
    var selectedIdx by remember(entries.size) { mutableIntStateOf(0) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AquaAbyss),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (entries.isEmpty()) {
            item {
                Text("No receiving addresses available on this account.", color = AquaMutedText)
            }
            return@LazyColumn
        }

        val current = entries[selectedIdx.coerceIn(0, entries.lastIndex)]
        val matrix = QrCodeGenerator.encode(current.second)

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
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.QrCode2, contentDescription = null, tint = AquaCyan)
                        Spacer(Modifier.width(8.dp))
                        Text("Receive Multi-Chain Assets", style = MaterialTheme.typography.titleMedium, color = AquaIceText)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        entries.forEachIndexed { idx, (label, _) ->
                            FilterChip(
                                selected = selectedIdx == idx,
                                onClick = { selectedIdx = idx },
                                label = { Text(label.substringBefore(" (")) },
                                modifier = Modifier.testTag("receive_chain_chip_$idx")
                            )
                        }
                    }

                    Text(
                        text = current.first,
                        style = MaterialTheme.typography.labelLarge,
                        color = AquaTeal
                    )

                    if (matrix != null) {
                        Surface(
                            color = Color.White,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.padding(4.dp)
                        ) {
                            Canvas(
                                modifier = Modifier
                                    .size(216.dp)
                                    .padding(14.dp)
                            ) {
                                val n = matrix.size
                                val cell = size.minDimension / n
                                for (r in 0 until n) {
                                    for (c in 0 until n) {
                                        if (matrix[r][c]) {
                                            drawRect(
                                                color = Color.Black,
                                                topLeft = Offset(c * cell, r * cell),
                                                size = Size(cell + 0.5f, cell + 0.5f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Surface(
                        color = AquaDeepSurface,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = current.second,
                            style = MaterialTheme.typography.bodySmall,
                            color = AquaIceText,
                            modifier = Modifier.padding(12.dp).testTag("receive_selected_address")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { onCopyAddress(current.first, current.second) },
                            colors = ButtonDefaults.buttonColors(containerColor = AquaCyan, contentColor = AquaAbyss),
                            modifier = Modifier.weight(1f).height(48.dp).testTag("receive_copy_button")
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Copy Address", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { onShareAddress(current.first, current.second) },
                            modifier = Modifier.weight(1f).height(48.dp).testTag("receive_share_button")
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null, tint = AquaCyan, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Share", color = AquaCyan)
                        }
                    }
                }
            }
        }
    }
}
