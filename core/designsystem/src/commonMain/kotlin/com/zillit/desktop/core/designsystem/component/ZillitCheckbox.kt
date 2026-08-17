package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/**
 * A checkbox with its label.
 *
 * The whole row toggles, not just the 16pt box — a target that small is a
 * usability problem on its own, and users click labels.
 */
@Composable
fun ZillitCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
    errorText: String? = null,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }

    Row(
        modifier = modifier.clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
        ) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(BOX_SIZE)
                // Dimmed when it cannot be clicked. Greying only the label left
                // a disabled *ticked* box looking exactly like a live one —
                // which is how a right the server had locked was read as an
                // ordinary granted right and clicked.
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .clip(ZillitTheme.shapes.small)
                .background(if (checked) colors.accent else colors.surfaceSunken)
                .border(
                    width = HAIRLINE,
                    // An unchecked box in an error state has to look wrong on
                    // its own: the message sits below and is easy to miss.
                    color = when {
                        errorText != null -> colors.danger
                        checked -> colors.accent
                        else -> colors.border
                    },
                    shape = ZillitTheme.shapes.small,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                ZillitIcon(
                    icon = ZillitIcons.Check,
                    tint = colors.textOnAccent,
                    size = CHECK_SIZE,
                )
            }
        }

        Column {
            label?.let {
                ZillitText(
                    text = it,
                    style = ZillitTheme.typography.bodySmall,
                    color = if (enabled) colors.textSecondary else colors.textDisabled,
                )
            }
            errorText?.let {
                ZillitText(
                    text = it,
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.danger,
                )
            }
        }
    }
}

/** How far a box that cannot be clicked is faded. */
private const val DISABLED_ALPHA = 0.45f

private val BOX_SIZE = 18.dp
private val CHECK_SIZE = 12.dp
private val HAIRLINE = 1.5.dp
