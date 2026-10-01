package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme

/** A fixed [width], or the whole cell when [Dp.Unspecified]. */
private fun Modifier.cellWidth(width: Dp): Modifier = if (width == Dp.Unspecified) fillMaxWidth() else width(width)

/**
 * Compact label-less controls for a table cell: the web's `size="small"` antd inputs (24px tall).
 * A pick over [options] (value, label) where the empty value is "none".
 */
@Composable
internal fun CellPick(
    value: String,
    options: List<Pair<String, String>>,
    onChange: (String) -> Unit,
    width: Dp,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    searchable: Boolean = true,
    newLabel: String? = null,
    onNew: () -> Unit = {},
) {
    CompactSelect(
        value,
        options,
        onChange,
        modifier.cellWidth(width),
        enabled,
        placeholder,
        searchable,
        newLabel,
        onNew,
    )
}

/** A closed small list (Day / Night): no search box. */
@Composable
internal fun CellSelect(
    value: String,
    options: List<Pair<String, String>>,
    onChange: (String) -> Unit,
    width: Dp,
    enabled: Boolean = true,
) {
    CompactSelect(value, options, onChange, Modifier.cellWidth(width), enabled, searchable = false)
}

@Composable
internal fun CellField(
    value: String,
    onChange: (String) -> Unit,
    width: Dp,
    enabled: Boolean = true,
    error: String? = null,
    onEnter: (() -> Unit)? = null,
    numeric: Boolean = false,
    autoFocus: Boolean = false,
    placeholder: String = "",
    modifier: Modifier = Modifier,
    height: Dp = 24.dp,
) {
    CompactField(
        value,
        onChange,
        modifier.cellWidth(width),
        enabled,
        error != null,
        placeholder,
        numeric,
        autoFocus,
        onEnter,
        height,
    )
}

@Composable
internal fun CellDate(
    value: String,
    onChange: (String) -> Unit,
    width: Dp,
    enabled: Boolean = true,
    height: Dp = 24.dp,
    dmy: Boolean = false,
) {
    CompactDate(value, onChange, Modifier.cellWidth(width), enabled, height, dmy)
}

/** The radio of Edit single's pick column. */
@Composable
internal fun RadioDot(selected: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Box(
        Modifier.size(RADIO)
            .clip(CircleShape)
            .border(1.dp, if (selected) colors.accent else colors.borderStrong, CircleShape)
            .clickable(
            onClick = onClick,
        ),
    ) {
        if (selected) Box(Modifier.fillMaxSize().padding(RADIO_INSET).clip(CircleShape).background(colors.accent))
    }
}

private val RADIO = 16.dp
private val RADIO_INSET = 3.dp
