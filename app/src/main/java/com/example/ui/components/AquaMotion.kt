package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.domain.AgeTier
import com.example.domain.UserGender
import com.example.ui.theme.AquaTheme

/**
 * Staggered entrance transition under 300ms for lists, cards, and dashboard blocks.
 */
@Composable
fun AquaAnimatedEntrance(
    index: Int = 0,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(
            animationSpec = tween(
                durationMillis = 220,
                delayMillis = (index.coerceIn(0, 8) * 28),
                easing = FastOutSlowInEasing
            )
        ) + slideInVertically(
            animationSpec = tween(
                durationMillis = 240,
                delayMillis = (index.coerceIn(0, 8) * 28),
                easing = FastOutSlowInEasing
            ),
            initialOffsetY = { it / 8 }
        ),
        modifier = modifier
    ) {
        content()
    }
}

/**
 * Tactile spring press micro-interaction for cards and primary buttons.
 */
fun Modifier.aquaPressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.972f
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "aqua_press_scale"
    )
    this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@Composable
fun AquaStatusPulseDot(
    color: Color,
    active: Boolean = true,
    size: Dp = 8.dp,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "status_pulse")
    val pulseScale by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot_scale"
    )
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot_alpha"
    )

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                val s = if (active) pulseScale else 1f
                scaleX = s
                scaleY = s
                alpha = if (active) pulseAlpha else 0.7f
            }
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
fun AquaProfileTierPill(
    ageTier: AgeTier,
    gender: UserGender,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val c = AquaTheme.colors
    val tint = when (ageTier) {
        AgeTier.UNDER_13_BLOCKED -> c.error
        AgeTier.TEEN_13_17 -> c.amber
        AgeTier.ADULT_18_PLUS -> c.success
        AgeTier.UNSET -> c.accentCyan
    }
    val icon = when (ageTier) {
        AgeTier.UNDER_13_BLOCKED -> Icons.Filled.Block
        AgeTier.TEEN_13_17 -> Icons.Filled.Shield
        AgeTier.ADULT_18_PLUS -> Icons.Filled.VerifiedUser
        AgeTier.UNSET -> Icons.Filled.Shield
    }
    val genderSuffix = if (gender != UserGender.UNSPECIFIED) " • ${gender.label}" else ""

    Surface(
        color = tint.copy(alpha = 0.14f),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.45f)),
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = ageTier.label,
                tint = tint,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = "${ageTier.shortBadge}$genderSuffix",
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
