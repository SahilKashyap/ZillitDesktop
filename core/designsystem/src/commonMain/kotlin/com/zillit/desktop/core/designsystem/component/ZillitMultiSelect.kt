package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Several choices from a list — the web's `MultiSelect`.
 *
 * The chosen items are chips in the field, each removable; the list opens
 * under the field in the app's one list style ([ZillitOptionPopup]) with a
 * search line, a tick on every chosen row, and stays open while rows are
 * toggled (click or ↵), closing on a click outside or Esc. The row options
 * ([subtitle], [optionLeading], [isEnabled], [section]…) are the list's own.
 */
@Suppress("LongMethod", "LongParameterList") // A control, read top to bottom; the order is the reading order.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ZillitMultiSelect(
    selected: List<T>,
    options: List<T>,
    label: (T) -> String,
    onChange: (List<T>) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = str(S.select) + "…",
    enabled: Boolean = true,
    /** What the open list says when there is nothing to offer. */
    emptyText: String = str(S.desktop_nothing_to_choose_from),
    searchable: Boolean = true,
    searchText: (T) -> String = label,
    subtitle: ((T) -> String?)? = null,
    showInitials: Boolean = true,
    optionLeading: (@Composable (T) -> Unit)? = null,
    isEnabled: (T) -> Boolean = { true },
    section: ((T) -> String?)? = null,
    dropdownWidth: Dp? = null,
    searchPlaceholder: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    val anchor = rememberZillitSelectAnchor()
    val colors = ZillitTheme.colors

    Box(modifier = modifier.zillitSelectAnchor(anchor)) {
        ZillitSelectTrigger(
            open = open,
            enabled = enabled,
            onClick = { open = !open },
            modifier = Modifier.fillMaxWidth(),
            minHeight = ZillitDimens.controlHeight,
            contentPadding = ZillitTheme.spacing.sm,
        ) {
            if (selected.isEmpty()) {
                ZillitText(
                    text = placeholder,
                    style = ZillitTheme.typography.bodyMedium,
                    color = colors.textMuted,
                    modifier = Modifier.weight(1f).padding(start = ZillitTheme.spacing.xs),
                )
            } else {
                FlowRow(
                    modifier = Modifier.weight(1f).padding(vertical = ZillitTheme.spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    selected.forEach { item ->
                        SelectedChip(
                            text = label(item),
                            onRemove = if (enabled) ({ onChange(selected - item) }) else null,
                        )
                    }
                }
            }
        }
        if (open) {
            ZillitOptionPopup(
                onDismiss = { open = false },
                width = anchor.popupWidth(dropdownWidth),
                options = options,
                isSelected = { it in selected },
                onPick = { item -> onChange(if (item in selected) selected - item else selected + item) },
                label = label,
                footer = { shown ->
                    str(
                        if (shown == 1) {
                            S.desktop_multiselect_option_count_one
                        } else {
                            S.desktop_multiselect_option_count_other
                        },
                        shown,
                        selected.size,
                    )
                },
                searchable = searchable,
                searchText = searchText,
                subtitle = subtitle,
                showInitials = showInitials,
                optionLeading = optionLeading,
                isEnabled = isEnabled,
                section = section,
                emptyText = emptyText,
                searchPlaceholder = searchPlaceholder,
            )
        }
    }
}

/** One chosen item, removable — as the field draws each choice. */
@Composable
private fun SelectedChip(text: String, onRemove: (() -> Unit)?) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .clip(ZillitTheme.shapes.pill)
            .background(colors.accentSoft)
            .padding(
                start = ZillitTheme.spacing.sm,
                end = if (onRemove == null) ZillitTheme.spacing.sm else ZillitTheme.spacing.xxs,
                top = CHIP_VERTICAL,
                bottom = CHIP_VERTICAL,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ZillitText(text = text, style = ZillitTheme.typography.labelSmall, color = colors.accentText, maxLines = 1)
        if (onRemove != null) {
            ZillitIconButton(
                icon = ZillitIcons.Close,
                contentDescription = str(S.bs_chip_remove, text),
                onClick = onRemove,
                size = CHIP_BUTTON,
                tint = colors.accentText,
            )
        }
    }
}

private val CHIP_VERTICAL = 2.dp
private val CHIP_BUTTON = 18.dp
