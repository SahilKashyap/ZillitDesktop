package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/**
 * Several choices from a list — the web's `MultiSelect`.
 *
 * The chosen items are chips in the field, each removable; the list opens
 * under the field with a search box and a checkbox per option, and closes on
 * a click outside. Composed in full inside a bounded, scrolling column under
 * a `Popup` rather than as a lazy list in a `DropdownMenu`, which is measured
 * against an unbounded height and crashes on open.
 */
@Suppress("LongMethod") // A control, read top to bottom; the order is the reading order.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ZillitMultiSelect(
    selected: List<T>,
    options: List<T>,
    label: (T) -> String,
    onChange: (List<T>) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Select…",
    enabled: Boolean = true,
    /** What the open list says when there is nothing to offer. */
    emptyText: String = "Nothing to choose from",
) {
    var open by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    val colors = ZillitTheme.colors

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = ZillitDimens.controlHeight)
                .clip(ZillitTheme.shapes.medium)
                .background(if (enabled) colors.surface else colors.surfaceSunken)
                .border(1.dp, if (open) colors.focusRing else colors.border, ZillitTheme.shapes.medium)
                .clickable(enabled = enabled) { open = !open }
                .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
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
                    modifier = Modifier.weight(1f),
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
            ZillitIcon(icon = ZillitIcons.ChevronDown, tint = colors.textMuted, size = ZillitDimens.iconSmall)
        }
        if (open) {
            Popup(
                offset = IntOffset(0, POPUP_DROP),
                onDismissRequest = { open = false; search = "" },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    modifier = Modifier
                        .width(POPUP_WIDTH)
                        .shadow(POPUP_ELEVATION, ZillitTheme.shapes.large)
                        .clip(ZillitTheme.shapes.large)
                        .background(colors.surfaceRaised)
                        .border(1.dp, colors.border, ZillitTheme.shapes.large)
                        .padding(ZillitTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    ZillitSearchField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = "Search…",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val shown = options.filter { search.isBlank() || label(it).contains(search, ignoreCase = true) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = LIST_MAX)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        if (shown.isEmpty()) {
                            ZillitText(
                                text = if (options.isEmpty()) emptyText else "No results for “$search”",
                                style = ZillitTheme.typography.bodySmall,
                                color = colors.textMuted,
                                modifier = Modifier.padding(ZillitTheme.spacing.sm),
                            )
                        }
                        shown.forEach { item ->
                            ZillitCheckbox(
                                checked = item in selected,
                                onCheckedChange = { on -> onChange(if (on) selected + item else selected - item) },
                                label = label(item),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
                            )
                        }
                    }
                    val plural = if (options.size == 1) "" else "s"
                    ZillitText(
                        text = "${options.size} option$plural · ${selected.size} selected",
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted,
                        modifier = Modifier.padding(horizontal = ZillitTheme.spacing.xs),
                    )
                }
            }
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
                contentDescription = "Remove $text",
                onClick = onRemove,
                size = CHIP_BUTTON,
                tint = colors.accentText,
            )
        }
    }
}

private val POPUP_WIDTH = 340.dp
private val LIST_MAX = 280.dp
private val POPUP_ELEVATION = 12.dp
private const val POPUP_DROP = 36
private val CHIP_VERTICAL = 2.dp
private val CHIP_BUTTON = 18.dp
