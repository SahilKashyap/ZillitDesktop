package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme

/**
 * A dropdown over a closed set of options.
 *
 * Generic over [T] so callers pass their own enum and get it back typed —
 * a select that dealt in strings would push a `when (label)` onto every call
 * site, which is where the label and the behaviour drift apart. A nullable
 * [T] with a `null` option is how a "None" row is offered.
 *
 * Opens the app's one list style ([ZillitOptionPopup]): initials tiles, the
 * picked row ticked, ↑/↓/↵ to drive it. [searchable] puts the search line on
 * top — on by default once the list is long enough to need it. [showInitials]
 * off drops the tiles, for a list whose labels are figures or symbols;
 * [optionLeading] replaces them (a colour dot, an icon). [fieldLeading] draws
 * the same mark in the closed field. [subtitle], [isEnabled] and [section]
 * are the list's own — see [ZillitOptionPopup].
 */
@Suppress("LongParameterList")
@Composable
fun <T> ZillitSelect(
    value: T,
    options: List<T>,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    searchable: Boolean = options.size > SEARCH_THRESHOLD,
    showInitials: Boolean = true,
    isError: Boolean = false,
    subtitle: ((T) -> String?)? = null,
    optionLeading: (@Composable (T) -> Unit)? = null,
    fieldLeading: (@Composable (T) -> Unit)? = null,
    isEnabled: (T) -> Boolean = { true },
    section: ((T) -> String?)? = null,
    dropdownWidth: Dp? = null,
) {
    val colors = ZillitTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val anchor = rememberZillitSelectAnchor()

    Box(modifier = modifier.zillitSelectAnchor(anchor)) {
        ZillitSelectTrigger(
            open = expanded,
            enabled = enabled,
            isError = isError,
            onClick = { expanded = !expanded },
            modifier = Modifier.widthIn(min = MIN_WIDTH),
        ) {
            fieldLeading?.invoke(value)
            ZillitText(
                text = label(value),
                style = ZillitTheme.typography.bodyMedium,
                color = if (enabled) colors.textPrimary else colors.textDisabled,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }

        if (expanded) {
            ZillitOptionPopup(
                onDismiss = { expanded = false },
                width = anchor.popupWidth(dropdownWidth),
                options = options,
                isSelected = { it == value },
                onPick = { option ->
                    expanded = false
                    // Fired even when unchanged: a caller may treat
                    // re-selecting as "refresh", and swallowing it here
                    // would make that impossible to implement.
                    onSelect(option)
                },
                label = label,
                searchable = searchable,
                subtitle = subtitle,
                showInitials = showInitials,
                optionLeading = optionLeading,
                isEnabled = isEnabled,
                section = section,
            )
        }
    }
}

private val MIN_WIDTH = 170.dp
