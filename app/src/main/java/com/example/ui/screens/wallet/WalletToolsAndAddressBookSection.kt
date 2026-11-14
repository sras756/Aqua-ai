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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
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
import com.example.wallet.core.AddressBookEntry
import com.example.wallet.core.AquaWalletCoordinator
import com.example.wallet.core.ChainFamily
import com.example.wallet.core.Erc20AllowanceResult
import com.example.wallet.core.EvmChainTelemetry
import com.example.wallet.core.NetworkConfig
import com.example.wallet.core.SignedMessageProof
import com.example.wallet.core.TokenDefinition
import com.example.wallet.core.WalletMetadata
import com.example.wallet.core.WalletSecuritySettings

@Composable
fun WalletToolsAndAddressBookSection(
    walletMeta: WalletMetadata,
    networks: List<NetworkConfig>,
    activeNetworkId: String,
    tokens: List<TokenDefinition>,
    addressBook: List<AddressBookEntry>,
    evmTelemetry: EvmChainTelemetry?,
    erc20Allowance: Erc20AllowanceResult?,
    signedProof: SignedMessageProof?,
    verificationResult: Boolean?,
    securitySettings: WalletSecuritySettings,
    isLocked: Boolean,
    busy: Boolean,
    coordinator: AquaWalletCoordinator,
    onUseContactInSend: (AddressBookEntry) -> Unit,
    onNavigateToSendPreview: () -> Unit,
    onCopyText: (String, String) -> Unit
) {
    var contactLabel by remember { mutableStateOf("") }
    var contactChain by remember { mutableStateOf(ChainFamily.EVM) }
    var contactAddress by remember { mutableStateOf("") }
    var contactNote by remember { mutableStateOf("") }

    var spenderAddress by remember { mutableStateOf("") }
    val evmTokens = remember(tokens) { tokens.filter { !it.isNative && it.contractOrMintAddress.isNotBlank() } }
    var selectedAllowanceTokenId by remember(evmTokens) {
        mutableStateOf(evmTokens.firstOrNull()?.id.orEmpty())
    }

    var signChain by remember { mutableStateOf(ChainFamily.EVM) }
    var signMessageInput by remember { mutableStateOf("") }
    var signPinInput by remember { mutableStateOf("") }

    var verifyAddress by remember { mutableStateOf("") }
    var verifyMessage by remember { mutableStateOf("") }
    var verifySignature by remember { mutableStateOf("") }

    val activeNet = networks.firstOrNull { it.id == activeNetworkId } ?: networks.firstOrNull()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AquaAbyss),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Live EVM Gas, Block & Nonce Telemetry
        if (activeNet?.chainFamily == ChainFamily.EVM) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, AquaBorderGlow, RoundedCornerShape(18.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Speed, contentDescription = null, tint = AquaCyan)
                                Spacer(Modifier.width(8.dp))
                                Text("Live Chain Telemetry (${activeNet.name})", style = MaterialTheme.typography.titleMedium, color = AquaIceText)
                            }
                            IconButton(
                                onClick = { coordinator.tools.refreshEvmTelemetry(activeNet.id) },
                                enabled = !busy,
                                modifier = Modifier.size(48.dp).testTag("tools_refresh_telemetry_button")
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Refresh telemetry", tint = AquaCyan)
                            }
                        }
                        if (evmTelemetry != null && evmTelemetry.networkId == activeNet.id) {
                            Text(
                                text = "Block #${evmTelemetry.blockNumber} • Base Gas: ${evmTelemetry.gasPriceGwei.stripTrailingZeros().toPlainString()} Gwei",
                                color = AquaMintSuccess,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Account Nonce — Confirmed: ${evmTelemetry.confirmedNonce} | Pending: ${evmTelemetry.pendingNonce}",
                                color = AquaMutedText,
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            Text(
                                text = "Tap refresh to query live block height, gas price (Gwei), and confirmed/pending nonces.",
                                color = AquaMutedText,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            // 2. ERC-20 Token Allowance Inspector & Revoker
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, AquaBorderGlow, RoundedCornerShape(18.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Security, contentDescription = null, tint = AquaAmber)
                            Spacer(Modifier.width(8.dp))
                            Text("ERC-20 Allowance & Revoke", style = MaterialTheme.typography.titleMedium, color = AquaIceText)
                        }
                        if (evmTokens.isEmpty()) {
                            Text(
                                text = "Add an ERC-20 token on ${activeNet.name} in the Networks tab to inspect or revoke spender allowances.",
                                color = AquaMutedText,
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                evmTokens.forEach { t ->
                                    FilterChip(
                                        selected = selectedAllowanceTokenId == t.id,
                                        onClick = { selectedAllowanceTokenId = t.id },
                                        label = { Text(t.symbol) }
                                    )
                                }
                            }
                            WalletField(spenderAddress, { spenderAddress = it }, "Spender / Router Contract (0x...)", "0x...", modifier = Modifier.testTag("tools_spender_input"))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        val tokId = selectedAllowanceTokenId.ifBlank { evmTokens.first().id }
                                        coordinator.tools.inspectErc20Allowance(activeNet.id, tokId, spenderAddress)
                                    },
                                    enabled = spenderAddress.isNotBlank() && !busy,
                                    modifier = Modifier.weight(1f).height(48.dp).testTag("tools_check_allowance_button")
                                ) {
                                    Text("Check", color = AquaCyan)
                                }
                                Button(
                                    onClick = {
                                        val tokId = selectedAllowanceTokenId.ifBlank { evmTokens.first().id }
                                        coordinator.tools.prepareErc20Revoke(activeNet.id, tokId, spenderAddress) {
                                            onNavigateToSendPreview()
                                        }
                                    },
                                    enabled = !walletMeta.isWatchOnly && !securitySettings.emergencyLocked && spenderAddress.isNotBlank() && !busy,
                                    colors = ButtonDefaults.buttonColors(containerColor = AquaAmber, contentColor = AquaAbyss),
                                    modifier = Modifier.weight(1f).height(48.dp).testTag("tools_revoke_allowance_button")
                                ) {
                                    Text("Revoke (0)", fontWeight = FontWeight.Bold)
                                }
                            }
                            erc20Allowance?.let { al ->
                                Surface(color = AquaDeepSurface, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            text = "${al.tokenSymbol} Allowance: ${al.formattedAllowance}",
                                            color = if (al.isUnlimited) AquaCoralError else AquaMintSuccess,
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                        Text(
                                            text = "Spender: ${al.spenderAddress}",
                                            color = AquaMutedText,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Cryptographic Message Signer & Verifier (EIP-191 & Ed25519)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, AquaBorderGlow, RoundedCornerShape(18.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Draw, contentDescription = null, tint = AquaCyan)
                        Spacer(Modifier.width(8.dp))
                        Text("Off-Chain Message Signer (EIP-191 / Ed25519)", style = MaterialTheme.typography.titleMedium, color = AquaIceText)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(ChainFamily.EVM, ChainFamily.SOLANA).forEach { fam ->
                            FilterChip(
                                selected = signChain == fam,
                                onClick = { signChain = fam },
                                label = { Text(if (fam == ChainFamily.EVM) "EVM (EIP-191)" else "Solana (Ed25519)") },
                                modifier = Modifier.testTag("tools_sign_chain_${fam.name}")
                            )
                        }
                    }
                    WalletField(signMessageInput, { signMessageInput = it }, "Message / Ownership Challenge", "Sign in to...", modifier = Modifier.testTag("tools_sign_message_input"))
                    if (isLocked && securitySettings.hasPin) {
                        OutlinedTextField(
                            value = signPinInput,
                            onValueChange = { if (it.length <= 12 && it.all(Char::isDigit)) signPinInput = it },
                            label = { Text("Wallet PIN to Sign") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AquaCyan,
                                unfocusedBorderColor = AquaBorderGlow,
                                focusedTextColor = AquaIceText,
                                unfocusedTextColor = AquaIceText
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Button(
                        onClick = {
                            coordinator.tools.signMessageLocally(
                                chainFamily = signChain,
                                message = signMessageInput,
                                pinAttempt = signPinInput.takeIf { it.isNotBlank() }
                            )
                            signPinInput = ""
                        },
                        enabled = !walletMeta.isWatchOnly && !securitySettings.emergencyLocked && signMessageInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = AquaCyan, contentColor = AquaAbyss),
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("tools_sign_message_button")
                    ) {
                        Text("Sign Message Locally (0 Gas)", fontWeight = FontWeight.Bold)
                    }

                    signedProof?.let { proof ->
                        Surface(color = AquaDeepSurface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(proof.standardName, color = AquaTeal, style = MaterialTheme.typography.labelMedium)
                                Text("Signer: ${proof.signerAddress}", color = AquaIceText, style = MaterialTheme.typography.labelSmall)
                                Text("Signature: ${proof.signature}", color = AquaMutedText, style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("tools_signed_signature_output"))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { onCopyText("Signed Proof", proof.signature) },
                                        modifier = Modifier.weight(1f).height(48.dp)
                                    ) {
                                        Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = AquaCyan, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Copy Sig", color = AquaCyan)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            verifyAddress = proof.signerAddress
                                            verifyMessage = proof.message
                                            verifySignature = proof.signature
                                            coordinator.tools.verifyMessageSignature(proof.chainFamily, proof.signerAddress, proof.message, proof.signature)
                                        },
                                        modifier = Modifier.weight(1f).height(48.dp).testTag("tools_verify_self_button")
                                    ) {
                                        Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = AquaMintSuccess, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Verify", color = AquaMintSuccess)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Text("VERIFY EXTERNAL SIGNATURE", style = MaterialTheme.typography.labelMedium, color = AquaCyan)
                    WalletField(verifyAddress, { verifyAddress = it }, "Expected Signer Address", "0x... or Solana address")
                    WalletField(verifyMessage, { verifyMessage = it }, "Original Message", "Exact UTF-8 message")
                    WalletField(verifySignature, { verifySignature = it }, "Signature (Hex or Base58)", "0x... or Base58")
                    OutlinedButton(
                        onClick = {
                            val fam = if (verifyAddress.trim().startsWith("0x")) ChainFamily.EVM else ChainFamily.SOLANA
                            coordinator.tools.verifyMessageSignature(fam, verifyAddress, verifyMessage, verifySignature)
                        },
                        enabled = verifyAddress.isNotBlank() && verifyMessage.isNotBlank() && verifySignature.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("tools_verify_external_button")
                    ) {
                        Text("Run Cryptographic Verification", color = AquaAzure)
                    }
                    verificationResult?.let { ok ->
                        Text(
                            text = if (ok) "VERIFIED: Cryptographic signature matches signer address." else "INVALID: Signature does not match signer & message.",
                            color = if (ok) AquaMintSuccess else AquaCoralError,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }

        // 4. Multi-Chain Trusted Address Book
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = AquaCardSurface),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, AquaBorderGlow, RoundedCornerShape(18.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Contacts, contentDescription = null, tint = AquaTeal)
                        Spacer(Modifier.width(8.dp))
                        Text("Multi-Chain Address Book", style = MaterialTheme.typography.titleMedium, color = AquaIceText)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChainFamily.entries.forEach { fam ->
                            FilterChip(
                                selected = contactChain == fam,
                                onClick = { contactChain = fam },
                                label = { Text(fam.name) }
                            )
                        }
                    }
                    WalletField(contactLabel, { contactLabel = it }, "Contact Name", "Alice Vault", modifier = Modifier.testTag("addressbook_label_input"))
                    WalletField(contactAddress, { contactAddress = it }, "${contactChain.name} Public Address", "Validated checksum / format", modifier = Modifier.testTag("addressbook_address_input"))
                    WalletField(contactNote, { contactNote = it }, "Optional Note", "Hardware cold storage")
                    Button(
                        onClick = {
                            coordinator.tools.saveAddressBookContact(contactLabel, contactChain, contactAddress, contactNote)
                            contactLabel = ""
                            contactAddress = ""
                            contactNote = ""
                        },
                        enabled = contactLabel.isNotBlank() && contactAddress.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = AquaTeal, contentColor = AquaAbyss),
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("addressbook_save_button")
                    ) {
                        Text("Save Trusted Contact", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        items(addressBook, key = { it.id }) { contact ->
            Surface(
                color = AquaDeepSurface,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, AquaBorderGlow, RoundedCornerShape(14.dp))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${contact.label} (${contact.chainFamily.name})", color = AquaIceText, style = MaterialTheme.typography.titleSmall)
                        Text(contact.address, color = AquaCyan, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (contact.note.isNotBlank()) {
                            Text(contact.note, color = AquaMutedText, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row {
                        IconButton(
                            onClick = { onUseContactInSend(contact) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send to ${contact.label}", tint = AquaCyan)
                        }
                        IconButton(
                            onClick = { onCopyText(contact.label, contact.address) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy address", tint = AquaTeal)
                        }
                        IconButton(
                            onClick = { coordinator.tools.deleteAddressBookContact(contact.id) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete contact", tint = AquaCoralError)
                        }
                    }
                }
            }
        }
    }
}
