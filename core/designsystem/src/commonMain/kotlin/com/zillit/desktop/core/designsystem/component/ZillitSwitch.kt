package com.zillit.desktop.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme

/**
 * An on/off switch — the web's `ToggleSwitch`.
 *
 * A checkbox says "include this"; a switch says "this is on". Settings pages
 * are full of the second kind — auto-split rentals, group by category, an
 * alert preference — and drawing them as checkboxes made every one read as an
 * item in a list rather than a state of the production.
 */
@Composable
fun ZillitSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
) {
    val colors = ZillitTheme.colors
    val track by animateColorAsState(
        targetValue = when {
            !enabled -> colors.border
            checked -> colors.accent
            else -> colors.borderStrong
        },
        label = "switch-track",
    )
    val offset by animateDpAsState(
        targetValue = if (checked) TRACK_WIDTH - THUMB - INSET else INSET,
        label = "switch-thumb",
    )
    val interaction = remember { MutableInteractionSource() }

    Row(
        modifier = modifier.clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = Role.Switch,
            onClick = { onCheckedChange(!checked) },
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(width = TRACK_WIDTH, height = TRACK_HEIGHT)
                .clip(CircleShape)
                .background(track),
        ) {
            Box(
                modifier = Modifier
                    .padding(vertical = INSET)
                    .offset(x = offset)
                    .size(THUMB)
                    .clip(CircleShape)
                    .background(colors.surface),
            )
        }
        if (label != null) {
            ZillitText(
                text = label,
                style = ZillitTheme.typography.bodyMedium,
                color = if (enabled) colors.textPrimary else colors.textDisabled,
            )
        }
    }
}

private val TRACK_WIDTH: Dp = 36.dp
private val TRACK_HEIGHT: Dp = 20.dp
private val THUMB: Dp = 16.dp
private val INSET: Dp = 2.dp
