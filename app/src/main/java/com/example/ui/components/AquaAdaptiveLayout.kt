package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class AquaWidthClass {
    COMPACT,
    MEDIUM,
    EXPANDED
}

enum class AquaHeightClass {
    COMPACT,
    MEDIUM,
    EXPANDED
}

data class AquaAdaptiveSpec(
    val widthClass: AquaWidthClass,
    val heightClass: AquaHeightClass,
    val availableWidth: Dp,
    val availableHeight: Dp,
    val useNavigationRail: Boolean,
    val isTwoPane: Boolean,
    val bentoColumns: Int,
    val cardColumns: Int,
    val horizontalPadding: Dp,
    val paneSpacing: Dp,
    val maxContentWidth: Dp
) {
    val isTabletOrFoldable: Boolean
        get() = widthClass != AquaWidthClass.COMPACT
}

fun classifyAquaWidth(maxWidth: Dp): AquaWidthClass = when {
    maxWidth < 600.dp -> AquaWidthClass.COMPACT
    maxWidth < 840.dp -> AquaWidthClass.MEDIUM
    else -> AquaWidthClass.EXPANDED
}

fun classifyAquaHeight(maxHeight: Dp): AquaHeightClass = when {
    maxHeight < 480.dp -> AquaHeightClass.COMPACT
    maxHeight < 900.dp -> AquaHeightClass.MEDIUM
    else -> AquaHeightClass.EXPANDED
}

fun computeAquaAdaptiveSpec(maxWidth: Dp, maxHeight: Dp): AquaAdaptiveSpec {
    val widthClass = classifyAquaWidth(maxWidth)
    val heightClass = classifyAquaHeight(maxHeight)
    val isLargeDesktopTablet = maxWidth >= 1160.dp
    return when (widthClass) {
        AquaWidthClass.COMPACT -> AquaAdaptiveSpec(
            widthClass = widthClass,
            heightClass = heightClass,
            availableWidth = maxWidth,
            availableHeight = maxHeight,
            useNavigationRail = false,
            isTwoPane = false,
            bentoColumns = 2,
            cardColumns = 1,
            horizontalPadding = 16.dp,
            paneSpacing = 12.dp,
            maxContentWidth = Dp.Unspecified
        )

        AquaWidthClass.MEDIUM -> AquaAdaptiveSpec(
            widthClass = widthClass,
            heightClass = heightClass,
            availableWidth = maxWidth,
            availableHeight = maxHeight,
            useNavigationRail = true,
            isTwoPane = false,
            bentoColumns = 3,
            cardColumns = 2,
            horizontalPadding = 22.dp,
            paneSpacing = 16.dp,
            maxContentWidth = 760.dp
        )

        AquaWidthClass.EXPANDED -> AquaAdaptiveSpec(
            widthClass = widthClass,
            heightClass = heightClass,
            availableWidth = maxWidth,
            availableHeight = maxHeight,
            useNavigationRail = true,
            isTwoPane = true,
            bentoColumns = 4,
            cardColumns = if (isLargeDesktopTablet) 3 else 2,
            horizontalPadding = 28.dp,
            paneSpacing = 20.dp,
            maxContentWidth = 1320.dp
        )
    }
}

@Composable
fun AquaAdaptiveContainer(
    modifier: Modifier = Modifier,
    content: @Composable BoxWithConstraintsScope.(AquaAdaptiveSpec) -> Unit
) {
    BoxWithConstraints(modifier = modifier) {
        val spec = computeAquaAdaptiveSpec(maxWidth = maxWidth, maxHeight = maxHeight)
        content(spec)
    }
}

/**
 * Centers content cleanly within [maxWidth] on tablets while preserving edge-to-edge full width on phones.
 */
@Composable
fun AquaConstrainedColumn(
    maxWidth: Dp,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter
    ) {
        val innerModifier = if (maxWidth != Dp.Unspecified) {
            Modifier
                .fillMaxWidth()
                .widthIn(max = maxWidth)
        } else {
            Modifier.fillMaxWidth()
        }
        Column(
            modifier = innerModifier,
            horizontalAlignment = horizontalAlignment
        ) {
            content()
        }
    }
}

/**
 * Renders balanced multi-column rows inside non-lazy or lazy item slots without nested LazyGrids.
 */
@Composable
fun <T> AquaAdaptiveGridRows(
    items: List<T>,
    columns: Int,
    horizontalSpacing: Dp = 12.dp,
    verticalSpacing: Dp = 12.dp,
    modifier: Modifier = Modifier,
    itemContent: @Composable (T) -> Unit
) {
    val safeCols = columns.coerceAtLeast(1)
    val rows = items.chunked(safeCols)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing)
    ) {
        rows.forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(horizontalSpacing)
            ) {
                rowItems.forEach { item ->
                    Box(modifier = Modifier.weight(1f)) {
                        itemContent(item)
                    }
                }
                val emptySlots = safeCols - rowItems.size
                repeat(emptySlots) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
