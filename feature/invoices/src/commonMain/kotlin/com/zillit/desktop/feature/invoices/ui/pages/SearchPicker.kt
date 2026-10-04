package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitSearchSelect
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * One choice from a list you can search — the web's `RichSelect` /
 * `SearchableSelect`: the field shows the chosen option's label (or the
 * placeholder), and opens a list under it with a search box and each option
 * on one or two lines, closing on a pick or a click outside.
 *
 * [searchText] is what the box matches against — more than the label where
 * the web's `getSearchText` adds to it (a vendor's email and contact, a
 * currency's name and symbol). The field and list are the app's shared
 * [ZillitSearchSelect]; this keeps the invoice forms' own defaults.
 */
@Suppress("LongParameterList")
@Composable
internal fun <T> SearchPicker(
    value: T?,
    options: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = str(S.select) + "…",
    searchText: (T) -> String = label,
    subline: ((T) -> String)? = null,
    enabled: Boolean = true,
    error: Boolean = false,
    popupWidth: Dp = POPUP_WIDTH,
) {
    ZillitSearchSelect(
        value = value,
        options = options,
        onSelect = onSelect,
        label = label,
        modifier = modifier,
        placeholder = placeholder,
        enabled = enabled,
        isError = error,
        searchText = searchText,
        subtitle = subline,
        dropdownWidth = popupWidth,
    )
}

private val POPUP_WIDTH = 360.dp
