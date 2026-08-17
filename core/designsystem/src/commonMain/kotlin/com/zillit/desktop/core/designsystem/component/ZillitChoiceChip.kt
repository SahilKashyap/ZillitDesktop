package com.zillit.desktop.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme

/**
 * One choice in a small closed set — a filter, a mode, a tab that acts like a
 * pill. The selected chip is filled with the accent the way the primary button
 * is, so "which one is on" reads at a glance; the rest sit quietly on the
 * surface and answer hover.
 *
 * Not a [ZillitTag]: a tag states a fact, a chip takes a click.
 */
@Composable
fun ZillitChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val background by animateColorAsState(
        when {
            selected -> colors.accent
            hovered -> colors.surfaceHover
            else -> colors.surface
        },
        label = "chipBackground",
    )
    val border by animateColorAsState(
        when {
            selected -> colors.accent
            hovered -> colors.borderStrong
            else -> colors.border
        },
        label = "chipBorder",
    )

    ZillitText(
        text = label,
        style = ZillitTheme.typography.button,
        color = if (selected) Color.White else colors.textSecondary,
        modifier = modifier
            .clip(ZillitTheme.shapes.pill)
            .background(background)
            .border(ChipBorderWidth, border, ZillitTheme.shapes.pill)
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
    )
}

private val ChipBorderWidth = 1.dp
