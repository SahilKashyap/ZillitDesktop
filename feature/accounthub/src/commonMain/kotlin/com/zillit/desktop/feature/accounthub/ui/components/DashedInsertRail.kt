package com.zillit.desktop.feature.accounthub.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/**
 * A dashed hairline with a round "+" at its middle — the web's
 * `SectionInsertRail`, which inserts a level in the approvals builder and a
 * section in the form editor.
 *
 * The whole rail takes the click, as the web's does, and the line warms to
 * the accent with the button when the pointer is anywhere on it: a 26dp
 * target on its own is easy to miss between two cards.
 */
@Composable
internal fun DashedInsertRail(description: String, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val line by animateColorAsState(
        if (hovered) colors.accent.copy(alpha = LINE_HOVER_ALPHA) else colors.border,
        label = "insertRailLine",
    )
    val fill by animateColorAsState(if (hovered) colors.accent else colors.surface, label = "insertRailFill")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(RAIL_HEIGHT)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(1.dp)) {
            drawLine(
                color = line,
                start = Offset(0f, 0f),
                end = Offset(size.width, 0f),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH, GAP)),
            )
        }
        ZillitTooltip(description) {
            Box(
                modifier = Modifier
                    .size(BUTTON)
                    .clip(CircleShape)
                    .background(fill)
                    .border(BORDER, if (hovered) colors.accent else colors.borderStrong, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(
                    icon = ZillitIcons.Add,
                    tint = if (hovered) colors.textOnAccent else colors.textMuted,
                    size = ICON,
                )
            }
        }
    }
}

private const val DASH = 6f
private const val GAP = 5f
private const val LINE_HOVER_ALPHA = 0.45f
private val RAIL_HEIGHT = 36.dp
private val BUTTON = 26.dp
private val BORDER = 1.5.dp
private val ICON = 12.dp
