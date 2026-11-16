package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.example.ai.ConversationMarkdownExporter
import com.example.ai.MessageSegment
import com.example.data.model.AquaCoreState
import com.example.data.model.ChatMessage
import com.example.data.model.ImportedAttachment
import com.example.data.model.PendingAuthorization
import com.example.ui.theme.AquaTheme
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.wallet.core.WalletSecuritySettings
import com.example.wallet.security.BiometricAuthHelper
import org.json.JSONArray
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun AquaCoreOrb(
    coreState: AquaCoreState,
    waveformBars: FloatArray,
    personaBadge: String = "AUTO",
    onTapOrb: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = AquaTheme.colors
    val transition = rememberInfiniteTransition(label = "aqua_core")
    val pulse by transition.animateFloat(
        initialValue = 0.90f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (coreState) {
                    AquaCoreState.THINKING, AquaCoreState.EXECUTING_TOOL -> 700
                    AquaCoreState.LISTENING, AquaCoreState.SPEAKING -> 900
                    else -> 2400
                },
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = LinearEasing)
        ),
        label = "rotation"
    )

    val primaryGlow = when (coreState) {
        AquaCoreState.LISTENING -> c.accentTeal
        AquaCoreState.THINKING -> c.accentAzure
        AquaCoreState.EXECUTING_TOOL -> c.amber
        AquaCoreState.SPEAKING -> c.accentCyan
        AquaCoreState.ERROR -> c.error
        AquaCoreState.IDLE -> c.accentCyan
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(168.dp)
                .clip(CircleShape)
                .clickable(onClick = onTapOrb)
                .testTag("aqua_core_orb"),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val baseRadius = size.minDimension * 0.31f * pulse

                // Outer holographic aura
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryGlow.copy(alpha = if (c.isDark) 0.36f else 0.24f),
                            c.accentAzure.copy(alpha = if (c.isDark) 0.14f else 0.10f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = size.minDimension * 0.5f
                    ),
                    radius = size.minDimension * 0.5f,
                    center = center
                )

                // Outer counter-rotating ring
                drawCircle(
                    color = c.accentTeal.copy(alpha = 0.26f),
                    radius = baseRadius * 1.40f,
                    center = center,
                    style = Stroke(width = 1.6f)
                )
                val revRad = Math.toRadians((-rotation * 0.65).toDouble())
                drawCircle(
                    color = c.accentTeal,
                    radius = 4.5f,
                    center = Offset(
                        x = center.x + (baseRadius * 1.40f * cos(revRad)).toFloat(),
                        y = center.y + (baseRadius * 1.40f * sin(revRad)).toFloat()
                    )
                )

                // Primary orbital ring
                drawCircle(
                    color = primaryGlow.copy(alpha = 0.52f),
                    radius = baseRadius * 1.22f,
                    center = center,
                    style = Stroke(width = 2.6f)
                )
                val rad = Math.toRadians(rotation.toDouble())
                val orbitDot = Offset(
                    x = center.x + (baseRadius * 1.22f * cos(rad)).toFloat(),
                    y = center.y + (baseRadius * 1.22f * sin(rad)).toFloat()
                )
                drawCircle(color = c.textPrimary, radius = 5.5f, center = orbitDot)

                // Inner luminescent core
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White, primaryGlow, c.accentAzure),
                        center = Offset(center.x - baseRadius * 0.25f, center.y - baseRadius * 0.25f),
                        radius = baseRadius * 1.2f
                    ),
                    radius = baseRadius,
                    center = center
                )
            }

            Icon(
                imageVector = if (coreState == AquaCoreState.LISTENING) Icons.Filled.Mic else Icons.Filled.GraphicEq,
                contentDescription = "Aqua Core State: $coreState",
                tint = c.onAccent,
                modifier = Modifier.size(30.dp)
            )
        }

        Spacer(Modifier.height(6.dp))

        // Live Voice Waveform Visualizer
        Canvas(
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .height(24.dp)
        ) {
            val count = waveformBars.size.coerceAtLeast(1)
            val step = size.width / count
            val midY = size.height / 2f
            for (i in 0 until count) {
                val amp = if (coreState == AquaCoreState.IDLE) 0.16f else waveformBars[i]
                val halfH = (size.height * 0.44f * amp).coerceAtLeast(2.5f)
                val x = i * step + step / 2f
                drawLine(
                    color = primaryGlow,
                    start = Offset(x, midY - halfH),
                    end = Offset(x, midY + halfH),
                    strokeWidth = (step * 0.46f).coerceAtLeast(3f),
                    cap = StrokeCap.Round
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = when (coreState) {
                    AquaCoreState.IDLE -> "AQUA CORE READY"
                    AquaCoreState.LISTENING -> "LISTENING..."
                    AquaCoreState.THINKING -> "GEMINI REASONING..."
                    AquaCoreState.EXECUTING_TOOL -> "EXECUTING TOOL..."
                    AquaCoreState.SPEAKING -> "SPEAKING (TAP TO STOP)"
                    AquaCoreState.ERROR -> "RECOVERY STANDBY"
                },
                style = MaterialTheme.typography.labelMedium,
                color = primaryGlow
            )
            Surface(
                color = primaryGlow.copy(alpha = 0.15f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.border(1.dp, primaryGlow.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            ) {
                Text(
                    text = personaBadge,
                    style = MaterialTheme.typography.labelSmall,
                    color = primaryGlow,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
fun PersistentChatBar(
    inputText: String,
    onInputTextChange: (String) -> Unit,
    isListening: Boolean,
    isExpanded: Boolean,
    pendingAttachment: ImportedAttachment?,
    onClearAttachment: () -> Unit,
    onToggleExpand: () -> Unit,
    onMicClick: () -> Unit,
    onPickDocumentClick: () -> Unit,
    onPickMediaClick: () -> Unit,
    onSendClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = AquaTheme.colors
    var attachMenuExpanded by remember { mutableStateOf(false) }

    Surface(
        color = c.deepSurface.copy(alpha = 0.96f),
        tonalElevation = 6.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, c.borderGlow.copy(alpha = 0.7f), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            AnimatedVisibility(visible = pendingAttachment != null) {
                pendingAttachment?.let { att ->
                    Surface(
                        color = c.cardSurface,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .border(1.dp, c.accentCyan.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AttachFile,
                                contentDescription = null,
                                tint = c.accentCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = att.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = c.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${att.mimeType} • Requires send approval",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = c.textMuted
                                )
                            }
                            IconButton(onClick = onClearAttachment) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove attachment", tint = c.textMuted)
                            }
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(
                    onClick = onToggleExpand,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("chat_expand_button")
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                        contentDescription = if (isExpanded) "Collapse conversation" else "Expand conversation",
                        tint = c.accentCyan
                    )
                }

                Box {
                    IconButton(
                        onClick = { attachMenuExpanded = true },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("chat_attach_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AttachFile,
                            contentDescription = "Attach file or media",
                            tint = c.textMuted
                        )
                    }
                    DropdownMenu(
                        expanded = attachMenuExpanded,
                        onDismissRequest = { attachMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Import Document / Code / PDF / ZIP") },
                            leadingIcon = { Icon(Icons.Filled.AttachFile, contentDescription = null) },
                            onClick = {
                                attachMenuExpanded = false
                                onPickDocumentClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Pick Photo or Video (Photo Picker)") },
                            leadingIcon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
                            onClick = {
                                attachMenuExpanded = false
                                onPickMediaClick()
                            }
                        )
                    }
                }

                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputTextChange,
                    placeholder = {
                        Text(
                            text = "Ask Aqua anything...",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSendClick() }),
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = c.accentCyan,
                        unfocusedBorderColor = c.borderGlow,
                        focusedTextColor = c.textPrimary,
                        unfocusedTextColor = c.textPrimary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("persistent_chat_input")
                )

                Spacer(Modifier.width(6.dp))

                IconButton(
                    onClick = onMicClick,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(CircleShape)
                        .background(if (isListening) c.accentTeal.copy(alpha = 0.25f) else Color.Transparent)
                        .testTag("chat_mic_button")
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = if (isListening) "Stop listening" else "Voice input",
                        tint = if (isListening) c.accentTeal else c.accentCyan
                    )
                }

                val canSend = inputText.isNotBlank() || pendingAttachment != null
                IconButton(
                    onClick = onSendClick,
                    enabled = canSend,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(CircleShape)
                        .background(if (canSend) c.accentCyan else c.cardSurface)
                        .testTag("chat_send_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send command",
                        tint = if (canSend) c.onAccent else c.textMuted
                    )
                }
            }
        }
    }
}

@Composable
fun ChatMessageBubble(
    message: ChatMessage,
    isSpeaking: Boolean,
    onSpeakClick: (String) -> Unit,
    onStopSpeakClick: () -> Unit,
    onCopyClick: ((String) -> Unit)? = null,
    onShareClick: ((String) -> Unit)? = null,
    onSaveMemoryClick: ((String) -> Unit)? = null
) {
    val c = AquaTheme.colors
    val isUser = message.role == "user"
    val bubbleColor = if (isUser) c.accentAzure.copy(alpha = if (c.isDark) 0.24f else 0.12f) else c.cardSurface
    val borderColor = if (isUser) c.accentCyan.copy(alpha = 0.50f) else c.borderGlow
    val segments = remember(message.content) {
        ConversationMarkdownExporter.parseMessageSegments(message.content)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (isUser) 20.dp else 6.dp,
                bottomEnd = if (isUser) 6.dp else 20.dp
            ),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .border(
                    1.dp,
                    borderColor,
                    RoundedCornerShape(
                        topStart = 20.dp,
                        topEnd = 20.dp,
                        bottomStart = if (isUser) 20.dp else 6.dp,
                        bottomEnd = if (isUser) 6.dp else 20.dp
                    )
                )
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isUser) "YOU" else "AQUA • ${message.modelUsed.ifBlank { "gemini" }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isUser) c.accentTeal else c.accentCyan
                    )
                    if (!isUser && message.content.isNotBlank()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            onCopyClick?.let { copyCb ->
                                IconButton(
                                    onClick = { copyCb(message.content) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.ContentCopy,
                                        contentDescription = "Copy response",
                                        tint = c.textMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            onShareClick?.let { shareCb ->
                                IconButton(
                                    onClick = { shareCb(message.content) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Share,
                                        contentDescription = "Share response",
                                        tint = c.textMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            onSaveMemoryClick?.let { memCb ->
                                IconButton(
                                    onClick = { memCb(message.content) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.BookmarkAdd,
                                        contentDescription = "Save to Aqua Memory",
                                        tint = c.accentTeal,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            IconButton(
                                onClick = { if (isSpeaking) onStopSpeakClick() else onSpeakClick(message.content) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = if (isSpeaking) Icons.Filled.Stop else Icons.Filled.VolumeUp,
                                    contentDescription = if (isSpeaking) "Stop speech" else "Read aloud",
                                    tint = c.accentCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                if (message.attachmentName.isNotBlank()) {
                    Text(
                        text = "Attachment: ${message.attachmentName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.amber
                    )
                }

                segments.forEach { segment ->
                    when (segment) {
                        is MessageSegment.Prose -> {
                            Text(
                                text = segment.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = c.textPrimary
                            )
                        }
                        is MessageSegment.CodeBlock -> {
                            Surface(
                                color = c.canvas.copy(alpha = if (c.isDark) 0.85f else 0.92f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, c.borderGlow, RoundedCornerShape(12.dp))
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.Code, contentDescription = null, tint = c.accentCyan, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = segment.language.uppercase(),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = c.accentCyan
                                            )
                                        }
                                        if (onCopyClick != null) {
                                            IconButton(
                                                onClick = { onCopyClick(segment.code) },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    Icons.Filled.ContentCopy,
                                                    contentDescription = "Copy code block",
                                                    tint = c.textMuted,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = segment.code,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = c.textPrimary
                                    )
                                }
                            }
                        }
                    }
                }

                if (message.toolActionsJson.isNotBlank()) {
                    val tools = remember(message.toolActionsJson) {
                        runCatching {
                            val arr = JSONArray(message.toolActionsJson)
                            (0 until arr.length()).map { arr.getJSONObject(it) }
                        }.getOrDefault(emptyList())
                    }
                    if (tools.isNotEmpty()) {
                        Surface(
                            color = c.deepSurface.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    text = "AGENT EXECUTION LOG (${tools.size})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = c.accentTeal
                                )
                                tools.forEach { obj ->
                                    Text(
                                        text = "• ${obj.optString("tool")} [${obj.optString("status")}]: ${obj.optString("detail")}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = c.textMuted
                                    )
                                }
                            }
                        }
                    }
                }

                if (message.groundingSourcesJson.isNotBlank()) {
                    val sources = remember(message.groundingSourcesJson) {
                        runCatching {
                            val arr = JSONArray(message.groundingSourcesJson)
                            (0 until arr.length()).map { arr.getString(it) }
                        }.getOrDefault(emptyList())
                    }
                    if (sources.isNotEmpty()) {
                        Text(
                            text = "Sources: ${sources.joinToString(" | ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.textMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AuthorizationModal(
    pending: PendingAuthorization,
    walletSecuritySettings: WalletSecuritySettings? = null,
    onUnlockWithPin: (String) -> Boolean = { false },
    onUnlockWithBiometric: () -> Unit = {},
    onApprove: () -> Unit,
    onDeny: () -> Unit
) {
    val c = AquaTheme.colors
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var pinInput by remember(pending.id) { mutableStateOf("") }
    var unlockError by remember(pending.id) { mutableStateOf<String?>(null) }

    val hasPin = walletSecuritySettings?.hasPin == true
    val canBiometric = walletSecuritySettings?.biometricEnabled == true &&
        activity != null &&
        BiometricAuthHelper.isBiometricAvailable(context)

    AlertDialog(
        onDismissRequest = onDeny,
        containerColor = c.deepSurface,
        icon = {
            Icon(
                imageVector = if (pending.isFinancialOrSecurity) Icons.Filled.Warning else Icons.Filled.Security,
                contentDescription = null,
                tint = if (pending.isFinancialOrSecurity) c.amber else c.accentCyan,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = pending.actionTitle,
                style = MaterialTheme.typography.titleLarge,
                color = c.textPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = pending.actionType,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (pending.isFinancialOrSecurity) c.amber else c.accentCyan
                )
                Text(
                    text = pending.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textMuted
                )
                Surface(
                    color = c.cardSurface,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        pending.parametersSummary.forEach { (k, v) ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "$k: ",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = c.textMuted,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = v,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = c.textPrimary
                                )
                            }
                        }
                    }
                }

                if (pending.requiresWalletUnlock) {
                    Surface(
                        color = c.cardSurface,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, c.amber.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Lock, contentDescription = null, tint = c.amber, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Wallet Vault Unlock Required",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = c.amber,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (hasPin) {
                                OutlinedTextField(
                                    value = pinInput,
                                    onValueChange = {
                                        pinInput = it.filter { ch -> ch.isDigit() }.take(12)
                                        unlockError = null
                                    },
                                    label = { Text("Enter Wallet PIN") },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = c.accentCyan,
                                        unfocusedBorderColor = c.borderGlow,
                                        focusedTextColor = c.textPrimary,
                                        unfocusedTextColor = c.textPrimary
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("auth_wallet_pin_input")
                                )
                            }
                            if (canBiometric && activity != null) {
                                OutlinedButton(
                                    onClick = {
                                        BiometricAuthHelper.authenticate(
                                            activity = activity,
                                            title = "Authorize Aqua Wallet Signing",
                                            subtitle = "Verify on-device biometrics to execute transaction",
                                            onSuccess = {
                                                onUnlockWithBiometric()
                                                onApprove()
                                            },
                                            onError = { err -> unlockError = err }
                                        )
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("auth_wallet_biometric_button")
                                ) {
                                    Icon(Icons.Filled.Fingerprint, contentDescription = null, tint = c.accentCyan)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Biometric Unlock & Execute", color = c.accentCyan)
                                }
                            }
                            if (!unlockError.isNullOrBlank()) {
                                Text(
                                    text = unlockError.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = c.error
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (pending.requiresWalletUnlock) {
                        if (!hasPin) {
                            unlockError = "Use biometric verification above to unlock this vault."
                            return@Button
                        }
                        if (!onUnlockWithPin(pinInput)) {
                            unlockError = "Incorrect Wallet PIN."
                            return@Button
                        }
                    }
                    onApprove()
                },
                enabled = !pending.requiresWalletUnlock || !hasPin || pinInput.length >= 4,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (pending.isFinancialOrSecurity) c.amber else c.accentCyan,
                    contentColor = c.onAccent
                ),
                modifier = Modifier.testTag("auth_approve_button")
            ) {
                Text(
                    text = if (pending.requiresWalletUnlock && hasPin) "Unlock & Execute" else "Authorize & Execute",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDeny,
                modifier = Modifier.testTag("auth_deny_button")
            ) {
                Text("Deny", color = c.textPrimary)
            }
        }
    )
}
