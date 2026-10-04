package com.zillit.desktop.feature.accounthub.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitOptionPopup
import com.zillit.desktop.core.designsystem.component.ZillitSearchSelect
import com.zillit.desktop.core.designsystem.component.ZillitSelectTrigger
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.popupWidth
import com.zillit.desktop.core.designsystem.component.rememberZillitSelectAnchor
import com.zillit.desktop.core.designsystem.component.zillitSelectAnchor
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/*
 * The report filter bar's pickers — the web's `RichSelect` as its filter bars
 * use it, in the app's one select look ([ZillitSelectTrigger] and
 * [ZillitOptionPopup]).
 *
 * Compact on purpose: one line whatever is picked. The hub's form pickers draw
 * each choice as a chip and grow a row per wrap, which is right in a form and
 * wrong in a bar of ten filters, where one busy picker would push the whole
 * report down. So a multi-select says its one choice, or "3 selected", as the
 * web's does.
 */

/** A filter's label above its control — the web's 10.5px uppercase eyebrow. */
@Composable
fun FilterCell(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(
            text = label.uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(
                fontSize = LABEL_SIZE,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = LABEL_TRACKING,
            ),
            color = ZillitTheme.colors.textMuted,
            maxLines = 1,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) { content() }
    }
}

/**
 * One choice, or none — "All vendors" is the empty state, and the ✕ returns to it.
 *
 * [searchText] is what the search matches, which can say more than the label:
 * the web finds a vendor by its contact and address as well as its name.
 * [row] draws a richer option than the label. [value] is matched to its
 * option by [key]. Short lists are scanned, not searched.
 */
@Suppress("LongParameterList") // A picker's surface; each parameter is one knob the call sites use.
@Composable
fun <T> FilterSelect(
    value: T?,
    options: List<T>,
    key: (T) -> String,
    label: (T) -> String,
    onSelect: (T?) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = str(S.select),
    clearable: Boolean = true,
    searchable: Boolean = true,
    searchText: (T) -> String = label,
    popupWidth: Dp? = null,
    row: (@Composable (T) -> Unit)? = null,
) {
    ZillitSearchSelect(
        value = value?.let { picked -> options.firstOrNull { key(it) == key(picked) } },
        options = options,
        onSelect = onSelect,
        label = label,
        modifier = modifier,
        placeholder = placeholder,
        searchText = searchText,
        dropdownWidth = popupWidth,
        renderOption = row?.let { draw -> @Composable { option: T, _: Boolean -> draw(option) } },
        onClear = if (clearable) ({ onSelect(null) }) else null,
        searchable = searchable && options.size > SEARCH_THRESHOLD,
    )
}

/**
 * Several choices, held as keys — empty means all.
 *
 * Keys rather than items so a choice that has left the list — a tag removed
 * from the production's settings — still counts, still shows, and can still
 * be cleared, instead of silently narrowing the report from nowhere. That,
 * and the one-line summary, is why this is the shared trigger and list rather
 * than `ZillitMultiSelect`, which holds items and draws chips.
 */
@Suppress("LongParameterList") // A picker's surface; each parameter is one knob the call sites use.
@Composable
fun <T> FilterMultiSelect(
    selected: List<String>,
    options: List<T>,
    key: (T) -> String,
    label: (T) -> String,
    onChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = str(S.select),
    searchable: Boolean = true,
    popupWidth: Dp? = null,
) {
    var open by remember { mutableStateOf(false) }
    val anchor = rememberZillitSelectAnchor()
    val colors = ZillitTheme.colors
    val summary = when (selected.size) {
        0 -> null
        1 -> options.firstOrNull { key(it) == selected.first() }?.let(label) ?: selected.first()
        else -> str(S.dd_n_selected, selected.size)
    }
    Box(modifier.zillitSelectAnchor(anchor)) {
        ZillitSelectTrigger(
            open = open,
            enabled = true,
            onClick = { open = !open },
            modifier = Modifier.fillMaxWidth(),
            minHeight = ZillitDimens.controlHeight,
            onClear = if (selected.isNotEmpty()) ({ onChange(emptyList()) }) else null,
        ) {
            ZillitText(
                text = summary ?: placeholder,
                style = ZillitTheme.typography.bodyMedium,
                color = if (summary != null) colors.textPrimary else colors.textMuted,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
        if (open) {
            ZillitOptionPopup(
                onDismiss = { open = false },
                width = anchor.popupWidth(popupWidth),
                options = options,
                isSelected = { key(it) in selected },
                // The list stays open, so several can be picked in one visit.
                onPick = { picked ->
                    val id = key(picked)
                    onChange(if (id in selected) selected - id else selected + id)
                },
                label = label,
                searchable = searchable && options.size > SEARCH_THRESHOLD,
                footer = { shown ->
                    val wording = if (shown == 1) {
                        S.desktop_multiselect_option_count_one
                    } else {
                        S.desktop_multiselect_option_count_other
                    }
                    str(wording, shown, selected.size)
                },
                // The web's "Clear" in the open list, which stays open after it.
                header = if (selected.isEmpty()) null else (@Composable { ClearAll { onChange(emptyList()) } }),
            )
        }
    }
}

/** "Clear", across the top of an open multi-select that has something picked. */
@Composable
private fun ClearAll(onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        ZillitText(
            text = str(S.ah_clear),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = ZillitTheme.colors.accentText,
            modifier = Modifier
                .clip(ZillitTheme.shapes.small)
                .clickable(onClick = onClick)
                .padding(horizontal = ZillitTheme.spacing.xs, vertical = ZillitTheme.spacing.xxs),
        )
    }
}

/** Short lists are scanned, not searched; the web's pickers show the field past a handful. */
private const val SEARCH_THRESHOLD = 6
private val LABEL_SIZE = 10.5.sp
private val LABEL_TRACKING = 0.6.sp
