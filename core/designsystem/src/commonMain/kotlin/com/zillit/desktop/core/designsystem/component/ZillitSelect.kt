package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/**
 * A dropdown over a closed set of options.
 *
 * Generic over [T] so callers pass their own enum and get it back typed —
 * a select that dealt in strings would push a `when (label)` onto every call
 * site, which is where the label and the behaviour drift apart.
 *
 * Uses Material3's [DropdownMenu] for the popup: it handles the window
 * placement, dismissal and focus behaviour that a hand-rolled overlay gets
 * subtly wrong, and it is the one piece of Material the app does not restyle
 * away.
 */
@Composable
fun <T> ZillitSelect(
    value: T,
    options: List<T>,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = ZillitTheme.colors
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = FIELD_HEIGHT)
                .widthIn(min = MIN_WIDTH)
                .clip(ZillitTheme.shapes.medium)
                .background(colors.surfaceSunken)
                .border(HAIRLINE, colors.border, ZillitTheme.shapes.medium)
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = label(value),
                style = ZillitTheme.typography.bodyMedium,
                color = if (enabled) colors.textPrimary else colors.textDisabled,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            ZillitIcon(
                icon = ZillitIcons.ChevronDown,
                tint = colors.textMuted,
                size = ICON_SIZE,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(colors.surfaceRaised, RoundedCornerShape(MENU_RADIUS)),
        ) {
            options.forEach { option ->
                val selected = option == value
                DropdownMenuItem(
                    onClick = {
                        expanded = false
                        // Fired even when unchanged: a caller may treat
                        // re-selecting as "refresh", and swallowing it here
                        // would make that impossible to implement.
                        onSelect(option)
                    },
                    modifier = Modifier.background(
                        if (selected) colors.surfaceSelected else colors.surfaceRaised,
                    ),
                    text = {
                        ZillitText(
                            text = label(option),
                            style = ZillitTheme.typography.bodyMedium,
                            color = if (selected) colors.accentText else colors.textPrimary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
            }
        }
    }
}

private val FIELD_HEIGHT = 36.dp
private val MIN_WIDTH = 170.dp
private val ICON_SIZE = 16.dp
private val HAIRLINE = 1.dp
private val MENU_RADIUS = 8.dp
