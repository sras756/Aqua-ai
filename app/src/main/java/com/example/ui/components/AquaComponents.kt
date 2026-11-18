package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
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

internal fun computeOrbWaveformAmplitude(
    index: Int,
    totalPoints: Int,
    phaseRad: Float,
    secondaryPhaseRad: Float,
    waveformBars: FloatArray,
    isLiveSessionActive: Boolean,
    coreState: AquaCoreState
): Float {
    val safeTotal = totalPoints.coerceAtLeast(1)
    val normPos = index.toFloat() / safeTotal.toFloat()
    val angle = (normPos * 2.0 * kotlin.math.PI).toFloat()

    val barSample = if (waveformBars.isNotEmpty()) {
        waveformBars[index % waveformBars.size].coerceIn(0.05f, 1.0f)
    } else {
        0.12f
    }

    return when {
        isLiveSessionActive -> {
            val h1 = kotlin.math.abs(sin(angle * 3f + phaseRad))
            val h2 = kotlin.math.abs(cos(angle * 5f - secondaryPhaseRad * 1.4f))
            val h3 = kotlin.math.abs(sin(angle * 8f + phaseRad * 1.9f))
            val liveWave = 0.24f + 0.42f * h1 + 0.20f * h2 + 0.14f * h3
            val merged = (liveWave * 0.72f) + (barSample * 0.45f)
            merged.coerceIn(0.18f, 1.0f)
        }
        coreState == AquaCoreState.LISTENING || coreState == AquaCoreState.SPEAKING -> {
            val dynamicHarmonic = 0.14f * kotlin.math.abs(sin(angle * 4f + phaseRad))
            (barSample * 0.86f + dynamicHarmonic).coerceIn(0.12f, 1.0f)
        }
        coreState == AquaCoreState.THINKING || coreState == AquaCoreState.EXECUTING_TOOL -> {
            val pulseWave = 0.20f + 0.18f * kotlin.math.abs(sin(angle * 2f + phaseRad))
            pulseWave.coerceIn(0.14f, 0.50f)
        }
        else -> {
            val gentleIdle = 0.10f + 0.08f * kotlin.math.abs(sin(angle * 2f + phaseRad * 0.5f))
            gentleIdle.coerceIn(0.08f, 0.24f)
        }
    }
}

@Composable
fun AquaCoreOrb(
    coreState: AquaCoreState,
    waveformBars: FloatArray,
    personaBadge: String = "AUTO",
    isLiveSessionActive: Boolean = false,
    onTapOrb: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = AquaTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val transition = rememberInfiniteTransition(label = "aqua_core")
    val twoPi = (2.0 * kotlin.math.PI).toFloat()

    val pulse by transition.animateFloat(
        initialValue = 0.90f,
        targetValue = if (isLiveSessionActive) 1.15f else 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when {
                    isLiveSessionActive -> 720
                    coreState == AquaCoreState.THINKING || coreState == AquaCoreState.EXECUTING_TOOL -> 650
                    coreState == AquaCoreState.LISTENING || coreState == AquaCoreState.SPEAKING -> 850
                    else -> 2400
                },
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val wavePhaseRad by transition.animateFloat(
        initialValue = 0f,
        targetValue = twoPi,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isLiveSessionActive) 1450 else 2800,
                easing = LinearEasing
            )
        ),
        label = "wave_phase"
    )
    val secondaryPhaseRad by transition.animateFloat(
        initialValue = 0f,
        targetValue = twoPi,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isLiveSessionActive) 2250 else 4100,
                easing = LinearEasing
            )
        ),
        label = "secondary_wave_phase"
    )
    val ripplePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isLiveSessionActive) 1200 else if (coreState == AquaCoreState.IDLE) 3200 else 1500,
                easing = LinearEasing
            )
        ),
        label = "ripple"
    )
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isLiveSessionActive) 5200 else 8500, easing = LinearEasing)
        ),
        label = "rotation"
    )
    val stateScale by animateFloatAsState(
        targetValue = when {
            isLiveSessionActive -> 1.07f
            coreState == AquaCoreState.LISTENING || coreState == AquaCoreState.SPEAKING -> 1.05f
            coreState == AquaCoreState.THINKING || coreState == AquaCoreState.EXECUTING_TOOL -> 1.02f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "orb_state_scale"
    )
    val liveIntensity by animateFloatAsState(
        targetValue = when {
            isLiveSessionActive -> 1.0f
            coreState == AquaCoreState.LISTENING || coreState == AquaCoreState.SPEAKING -> 0.74f
            coreState == AquaCoreState.THINKING || coreState == AquaCoreState.EXECUTING_TOOL -> 0.42f
            else -> 0.16f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "live_intensity"
    )
    val orbDiameter by animateDpAsState(
        targetValue = if (isLiveSessionActive) 204.dp else 180.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "orb_diameter"
    )

    val primaryGlow = when {
        isLiveSessionActive -> c.accentTeal
        coreState == AquaCoreState.LISTENING -> c.accentTeal
        coreState == AquaCoreState.THINKING -> c.accentAzure
        coreState == AquaCoreState.EXECUTING_TOOL -> c.amber
        coreState == AquaCoreState.SPEAKING -> c.accentCyan
        coreState == AquaCoreState.ERROR -> c.error
        else -> c.accentCyan
    }
    val secondaryGlow = if (isLiveSessionActive) c.accentCyan else c.accentTeal

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(orbDiameter)
                .graphicsLayer {
                    scaleX = stateScale
                    scaleY = stateScale
                }
                .aquaPressScale(interactionSource, pressedScale = 0.94f)
                .clip(CircleShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onTapOrb
                )
                .testTag("aqua_core_orb"),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val baseRadius = size.minDimension * 0.265f * pulse

                // Outer holographic aura
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryGlow.copy(alpha = (if (c.isDark) 0.44f else 0.28f) * (0.7f + 0.4f * liveIntensity)),
                            secondaryGlow.copy(alpha = if (c.isDark) 0.18f else 0.12f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = size.minDimension * 0.5f
                    ),
                    radius = size.minDimension * 0.5f,
                    center = center
                )

                // Expanding sonar wave ring
                val waveRadius = baseRadius * (1.06f + ripplePhase * (0.48f + 0.26f * liveIntensity))
                val waveAlpha = (1f - ripplePhase) * (0.18f + 0.36f * liveIntensity)
                drawCircle(
                    color = primaryGlow.copy(alpha = waveAlpha),
                    radius = waveRadius,
                    center = center,
                    style = Stroke(width = if (isLiveSessionActive) 2.6f else 2.0f)
                )

                // Closed harmonic spline voice waveform halo around the orb
                val haloVertices = 48
                val haloRadiusBase = baseRadius * (1.16f + 0.08f * liveIntensity)
                val haloMaxDeviation = size.minDimension * (0.04f + 0.095f * liveIntensity)
                val haloPts = Array(haloVertices) { i ->
                    val theta = (i.toFloat() / haloVertices) * twoPi + Math.toRadians((rotation * 0.35f).toDouble()).toFloat()
                    val amp = computeOrbWaveformAmplitude(
                        index = i,
                        totalPoints = haloVertices,
                        phaseRad = wavePhaseRad,
                        secondaryPhaseRad = secondaryPhaseRad,
                        waveformBars = waveformBars,
                        isLiveSessionActive = isLiveSessionActive,
                        coreState = coreState
                    )
                    val r = haloRadiusBase + amp * haloMaxDeviation
                    Offset(
                        x = center.x + r * cos(theta),
                        y = center.y + r * sin(theta)
                    )
                }
                val haloPath = Path().apply {
                    val startMid = Offset(
                        (haloPts[haloVertices - 1].x + haloPts[0].x) / 2f,
                        (haloPts[haloVertices - 1].y + haloPts[0].y) / 2f
                    )
                    moveTo(startMid.x, startMid.y)
                    for (i in 0 until haloVertices) {
                        val curr = haloPts[i]
                        val next = haloPts[(i + 1) % haloVertices]
                        val mid = Offset((curr.x + next.x) / 2f, (curr.y + next.y) / 2f)
                        quadraticTo(curr.x, curr.y, mid.x, mid.y)
                    }
                    close()
                }
                drawPath(
                    path = haloPath,
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryGlow.copy(alpha = 0.06f + 0.18f * liveIntensity),
                            secondaryGlow.copy(alpha = 0.02f + 0.12f * liveIntensity),
                            Color.Transparent
                        ),
                        center = center,
                        radius = haloRadiusBase + haloMaxDeviation
                    )
                )
                drawPath(
                    path = haloPath,
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            primaryGlow.copy(alpha = 0.35f + 0.55f * liveIntensity),
                            secondaryGlow.copy(alpha = 0.45f + 0.50f * liveIntensity),
                            c.accentAzure.copy(alpha = 0.35f + 0.45f * liveIntensity),
                            primaryGlow.copy(alpha = 0.35f + 0.55f * liveIntensity)
                        ),
                        center = center
                    ),
                    style = Stroke(width = 1.8f + 1.8f * liveIntensity)
                )

                // Radial spectrum spokes orbiting the orb perimeter
                val spokeCount = 36
                val spokeInnerBase = baseRadius * 1.07f
                val spokeMaxLen = size.minDimension * (0.045f + 0.115f * liveIntensity)
                val spinOffsetRad = Math.toRadians((rotation * 0.5).toDouble()).toFloat()
                for (i in 0 until spokeCount) {
                    val theta = (i.toFloat() / spokeCount) * twoPi + spinOffsetRad
                    val amp = computeOrbWaveformAmplitude(
                        index = i,
                        totalPoints = spokeCount,
                        phaseRad = wavePhaseRad,
                        secondaryPhaseRad = secondaryPhaseRad,
                        waveformBars = waveformBars,
                        isLiveSessionActive = isLiveSessionActive,
                        coreState = coreState
                    )
                    val len = spokeMaxLen * amp
                    val cosT = cos(theta)
                    val sinT = sin(theta)
                    val start = Offset(center.x + spokeInnerBase * cosT, center.y + spokeInnerBase * sinT)
                    val end = Offset(center.x + (spokeInnerBase + len) * cosT, center.y + (spokeInnerBase + len) * sinT)
                    val barColor = lerp(primaryGlow, secondaryGlow, amp.coerceIn(0f, 1f))
                    drawLine(
                        color = barColor.copy(alpha = 0.38f + 0.56f * liveIntensity),
                        start = start,
                        end = end,
                        strokeWidth = (2.4f + 1.8f * liveIntensity),
                        cap = StrokeCap.Round
                    )
                }

                // Counter-rotating outer orbital track
                drawCircle(
                    color = secondaryGlow.copy(alpha = 0.28f),
                    radius = baseRadius * 1.46f,
                    center = center,
                    style = Stroke(width = 1.5f)
                )
                val revRad = Math.toRadians((-rotation * 0.7).toDouble())
                drawCircle(
                    color = secondaryGlow,
                    radius = 4.6f,
                    center = Offset(
                        x = center.x + (baseRadius * 1.46f * cos(revRad)).toFloat(),
                        y = center.y + (baseRadius * 1.46f * sin(revRad)).toFloat()
                    )
                )

                // Primary orbital ring + dot
                drawCircle(
                    color = primaryGlow.copy(alpha = 0.56f),
                    radius = baseRadius * 1.24f,
                    center = center,
                    style = Stroke(width = 2.6f)
                )
                val rad = Math.toRadians(rotation.toDouble())
                val orbitDot = Offset(
                    x = center.x + (baseRadius * 1.24f * cos(rad)).toFloat(),
                    y = center.y + (baseRadius * 1.24f * sin(rad)).toFloat()
                )
                drawCircle(color = c.textPrimary, radius = 5.5f, center = orbitDot)

                // Inner luminescent core
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White, primaryGlow, c.accentAzure),
                        center = Offset(center.x - baseRadius * 0.25f, center.y - baseRadius * 0.25f),
                        radius = baseRadius * 1.24f
                    ),
                    radius = baseRadius,
                    center = center
                )
            }

            Icon(
                imageVector = if (isLiveSessionActive || coreState == AquaCoreState.LISTENING) Icons.Filled.Mic else Icons.Filled.GraphicEq,
                contentDescription = if (isLiveSessionActive) "Gemini Live Voice Active" else "Aqua Core State: $coreState",
                tint = c.onAccent,
                modifier = Modifier.size(if (isLiveSessionActive) 34.dp else 30.dp)
            )
        }

        Spacer(Modifier.height(6.dp))

        // Horizontal Canvas Voice Waveform Ribbon & Equalizer Visualizer
        Canvas(
            modifier = Modifier
                .fillMaxWidth(if (isLiveSessionActive) 0.78f else 0.68f)
                .height(if (isLiveSessionActive) 40.dp else 30.dp)
                .testTag("aqua_orb_waveform_canvas")
        ) {
            val midY = size.height / 2f
            drawLine(
                color = primaryGlow.copy(alpha = 0.16f),
                start = Offset(0f, midY),
                end = Offset(size.width, midY),
                strokeWidth = 1.4f
            )

            // Mirrored equalizer bars
            val count = waveformBars.size.coerceAtLeast(1)
            val step = size.width / count
            for (i in 0 until count) {
                val amp = computeOrbWaveformAmplitude(
                    index = i,
                    totalPoints = count,
                    phaseRad = wavePhaseRad,
                    secondaryPhaseRad = secondaryPhaseRad,
                    waveformBars = waveformBars,
                    isLiveSessionActive = isLiveSessionActive,
                    coreState = coreState
                )
                val halfH = (size.height * 0.44f * amp).coerceAtLeast(2.5f)
                val x = i * step + step / 2f
                drawLine(
                    color = lerp(primaryGlow, secondaryGlow, (i.toFloat() / count)).copy(alpha = 0.35f + 0.55f * liveIntensity),
                    start = Offset(x, midY - halfH),
                    end = Offset(x, midY + halfH),
                    strokeWidth = (step * 0.40f).coerceAtLeast(2.8f),
                    cap = StrokeCap.Round
                )
            }

            // Dual overlapping Bezier harmonic ribbons active during voice / Gemini Live sessions
            if (isLiveSessionActive || coreState == AquaCoreState.LISTENING || coreState == AquaCoreState.SPEAKING) {
                val ribbonSamples = 40
                val dx = size.width / (ribbonSamples - 1)
                val wavePath1 = Path()
                val wavePath2 = Path()
                for (s in 0 until ribbonSamples) {
                    val x = s * dx
                    val normX = s.toFloat() / (ribbonSamples - 1)
                    // Window taper smoothly at edges to 0
                    val window = sin(normX * kotlin.math.PI).toFloat()
                    val amp = computeOrbWaveformAmplitude(
                        index = s,
                        totalPoints = ribbonSamples,
                        phaseRad = wavePhaseRad,
                        secondaryPhaseRad = secondaryPhaseRad,
                        waveformBars = waveformBars,
                        isLiveSessionActive = isLiveSessionActive,
                        coreState = coreState
                    )
                    val y1 = midY + sin(normX * 4f * twoPi + wavePhaseRad) * (size.height * 0.42f * amp * window)
                    val y2 = midY + cos(normX * 3f * twoPi - secondaryPhaseRad) * (size.height * 0.32f * amp * window)
                    if (s == 0) {
                        wavePath1.moveTo(x, y1)
                        wavePath2.moveTo(x, y2)
                    } else {
                        val prevX = (s - 1) * dx
                        val prevNormX = (s - 1).toFloat() / (ribbonSamples - 1)
                        val prevWin = sin(prevNormX * kotlin.math.PI).toFloat()
                        val prevAmp = computeOrbWaveformAmplitude(
                            index = s - 1,
                            totalPoints = ribbonSamples,
                            phaseRad = wavePhaseRad,
                            secondaryPhaseRad = secondaryPhaseRad,
                            waveformBars = waveformBars,
                            isLiveSessionActive = isLiveSessionActive,
                            coreState = coreState
                        )
                        val py1 = midY + sin(prevNormX * 4f * twoPi + wavePhaseRad) * (size.height * 0.42f * prevAmp * prevWin)
                        val py2 = midY + cos(prevNormX * 3f * twoPi - secondaryPhaseRad) * (size.height * 0.32f * prevAmp * prevWin)
                        val midX = (prevX + x) / 2f
                        wavePath1.quadraticTo(prevX, py1, midX, (py1 + y1) / 2f)
                        wavePath2.quadraticTo(prevX, py2, midX, (py2 + y2) / 2f)
                    }
                }
                drawPath(
                    path = wavePath1,
                    brush = Brush.horizontalGradient(listOf(c.accentCyan, c.accentTeal, c.accentAzure)),
                    style = Stroke(width = if (isLiveSessionActive) 3.0f else 2.2f, cap = StrokeCap.Round)
                )
                drawPath(
                    path = wavePath2,
                    color = secondaryGlow.copy(alpha = 0.65f),
                    style = Stroke(width = 1.8f, cap = StrokeCap.Round)
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AquaStatusPulseDot(
                color = primaryGlow,
                active = isLiveSessionActive || coreState != AquaCoreState.IDLE,
                size = 8.dp
            )
            Text(
                text = when {
                    isLiveSessionActive -> "GEMINI LIVE ACTIVE (TAP TO END)"
                    coreState == AquaCoreState.IDLE -> "AQUA CORE READY"
                    coreState == AquaCoreState.LISTENING -> "LISTENING..."
                    coreState == AquaCoreState.THINKING -> "GEMINI REASONING..."
                    coreState == AquaCoreState.EXECUTING_TOOL -> "EXECUTING TOOL..."
                    coreState == AquaCoreState.SPEAKING -> "SPEAKING (TAP TO STOP)"
                    else -> "RECOVERY STANDBY"
                },
                style = MaterialTheme.typography.labelMedium,
                color = primaryGlow,
                fontWeight = FontWeight.SemiBold
            )
            if (isLiveSessionActive) {
                Surface(
                    color = c.accentTeal.copy(alpha = 0.22f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .border(1.dp, c.accentTeal.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                        .testTag("aqua_orb_live_badge")
                ) {
                    Text(
                        text = "LIVE",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.accentTeal,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
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
    val canSend = inputText.isNotBlank() || pendingAttachment != null
    val sendScale by animateFloatAsState(
        targetValue = if (canSend) 1.04f else 0.96f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "send_btn_scale"
    )

    Surface(
        color = c.deepSurface.copy(alpha = 0.97f),
        tonalElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, c.borderGlow.copy(alpha = 0.75f), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
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

                IconButton(
                    onClick = onSendClick,
                    enabled = canSend,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .graphicsLayer {
                            scaleX = sendScale
                            scaleY = sendScale
                        }
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

    AquaAnimatedEntrance {
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
