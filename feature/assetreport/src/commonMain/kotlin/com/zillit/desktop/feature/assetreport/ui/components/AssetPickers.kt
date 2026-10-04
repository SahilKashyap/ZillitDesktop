package com.zillit.desktop.feature.assetreport.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitOptionPopup
import com.zillit.desktop.core.designsystem.component.ZillitSelectTrigger
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.popupWidth
import com.zillit.desktop.core.designsystem.component.rememberZillitSelectAnchor
import com.zillit.desktop.core.designsystem.component.zillitSelectAnchor
import com.zillit.desktop.core.designsystem.component.zillitOptionCount
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The web's `RichSelect`, as the register's filter bar uses it: a field that
 * names one choice or counts several, over the app's one list.
 *
 * The shared trigger and list rather than `ZillitSearchSelect` /
 * `ZillitMultiSelect`: the field is 44dp to line up with the bar's search box,
 * a multi-select says "3 selected" rather than growing a row of chips, and the
 * choices are keys — the caller toggles them in [onPick], so one no longer
 * offered still counts and can still be cleared.
 */
@Suppress("LongParameterList") // A picker's surface; each parameter is one knob a call site uses.
@Composable
internal fun <T> AssetSelect(
    options: List<T>,
    selectedKeys: List<String>,
    key: (T) -> String,
    label: (T) -> String,
    onPick: (T) -> Unit,
    onClear: (() -> Unit)?,
    placeholder: String,
    modifier: Modifier = Modifier,
    multiple: Boolean = false,
    searchText: (T) -> String = label,
    row: (@Composable (T) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val anchor = rememberZillitSelectAnchor()
    val colors = ZillitTheme.colors
    val summary = when {
        selectedKeys.isEmpty() -> null
        !multiple || selectedKeys.size == 1 ->
            options.firstOrNull { key(it) == selectedKeys.first() }?.let(label)
                ?: if (multiple) str(S.dd_n_selected, 1) else null
        else -> str(S.dd_n_selected, selectedKeys.size)
    }
    Box(modifier.zillitSelectAnchor(anchor)) {
        ZillitSelectTrigger(
            open = open,
            enabled = true,
            onClick = { open = !open },
            modifier = Modifier.fillMaxWidth(),
            minHeight = FIELD_HEIGHT,
            onClear = onClear?.takeIf { selectedKeys.isNotEmpty() },
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
                width = anchor.popupWidth(),
                options = options,
                isSelected = { key(it) in selectedKeys },
                onPick = { picked ->
                    // A multi-select stays open, so several can be picked in one visit.
                    if (!multiple) open = false
                    onPick(picked)
                },
                label = label,
                searchable = true,
                searchText = searchText,
                renderOption = row?.let { draw -> @Composable { option: T, _: Boolean -> draw(option) } },
                footer = { shown ->
                    if (multiple) {
                        val wording = if (shown == 1) {
                            S.desktop_multiselect_option_count_one
                        } else {
                            S.desktop_multiselect_option_count_other
                        }
                        str(wording, shown, selectedKeys.size)
                    } else {
                        zillitOptionCount(shown)
                    }
                },
            )
        }
    }
}

internal val FIELD_HEIGHT = 44.dp
internal val FIELD_SHAPE = RoundedCornerShape(12.dp)
