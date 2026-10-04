package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * A select whose list can be searched — the web's `RichSelect` with
 * `typeable`: the closed field shows the pick (or [placeholder]), and opening
 * it puts a search box above the options, filtered as it is typed.
 *
 * With [onCreate] set, a search that matches nothing offers
 * `Create "<typed>"` instead of "No results" — the vendor quick-add. Picking
 * it hands the trimmed text back; creating the record is the caller's
 * business (the invoice forms defer it to their own submit).
 *
 * [value] null (or not among [options]) shows [placeholder]. [subtitle]
 * gives an option a muted second line; [searchText] widens what the search
 * matches beyond the label. [isError] draws the danger border, as a field
 * with a validation error does.
 *
 * [renderOption], set, replaces the built-in tile/label/subtitle row
 * entirely — the web's `renderOption`, for a picker whose rows need more than
 * two lines (an avatar, a status badge, a contact line — the vendor picker's
 * own row). Every row still gets the shared container: the highlight wash,
 * the selected row's accent bar and tick, the hairline between rows.
 *
 * [onClear] draws a ✕ in the field while something is picked. [fieldLeading]
 * draws a mark before the picked label (an avatar, a colour dot), and
 * [optionLeading]/[showInitials]/[isEnabled]/[section] shape the list's rows.
 *
 * The list is the app's one style — see [ZillitOptionPopup].
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun <T> ZillitSearchSelect(
    value: T?,
    options: List<T>,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    isError: Boolean = false,
    searchText: (T) -> String = label,
    subtitle: ((T) -> String?)? = null,
    onCreate: ((String) -> Unit)? = null,
    dropdownWidth: Dp? = null,
    renderOption: (@Composable (T, selected: Boolean) -> Unit)? = null,
    onClear: (() -> Unit)? = null,
    searchable: Boolean = true,
    showInitials: Boolean = true,
    optionLeading: (@Composable (T) -> Unit)? = null,
    fieldLeading: (@Composable (T) -> Unit)? = null,
    isEnabled: (T) -> Boolean = { true },
    section: ((T) -> String?)? = null,
    pinnedAction: ZillitOptionAction? = null,
    emptyText: String? = null,
    searchPlaceholder: String? = null,
) {
    val colors = ZillitTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val anchor = rememberZillitSelectAnchor()
    val shown = value?.takeIf { it in options }

    Box(modifier = modifier.zillitSelectAnchor(anchor)) {
        ZillitSelectTrigger(
            open = expanded,
            enabled = enabled,
            isError = isError,
            onClick = { expanded = !expanded },
            modifier = Modifier.widthIn(min = MIN_WIDTH).fillMaxWidth(),
            onClear = onClear?.takeIf { shown != null },
        ) {
            if (shown != null) fieldLeading?.invoke(shown)
            ZillitText(
                text = shown?.let(label) ?: placeholder,
                style = ZillitTheme.typography.bodyMedium,
                color = when {
                    !enabled -> colors.textDisabled
                    shown == null -> colors.textMuted
                    else -> colors.textPrimary
                },
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }

        if (expanded) {
            ZillitOptionPopup(
                onDismiss = { expanded = false },
                width = anchor.popupWidth(dropdownWidth),
                options = options,
                isSelected = { it == shown },
                onPick = { option ->
                    expanded = false
                    onSelect(option)
                },
                label = label,
                searchable = searchable,
                searchText = searchText,
                subtitle = subtitle,
                showInitials = showInitials,
                optionLeading = optionLeading,
                renderOption = renderOption,
                isEnabled = isEnabled,
                section = section,
                pinnedAction = pinnedAction,
                onCreate = onCreate,
                emptyText = emptyText ?: str(S.desktop_nothing_to_choose_from),
                searchPlaceholder = searchPlaceholder,
            )
        }
    }
}

private val MIN_WIDTH = 170.dp
