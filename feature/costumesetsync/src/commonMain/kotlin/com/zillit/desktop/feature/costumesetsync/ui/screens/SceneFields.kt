package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitSearchSelect
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitTextField

/**
 * Compact label-less controls for a table cell: the web's `size="small"` antd
 * inputs. A pick over [options] (value, label) where the empty value is "none".
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
) {
    ZillitSearchSelect(
        value = options.firstOrNull { it.first == value },
        options = options,
        onSelect = { onChange(it.first) },
        label = { it.second },
        modifier = modifier.width(width),
        placeholder = placeholder,
        enabled = enabled,
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
    ZillitSelect(
        value = options.firstOrNull { it.first == value } ?: options.first(),
        options = options,
        onSelect = { onChange(it.first) },
        label = { it.second },
        modifier = Modifier.width(width),
        enabled = enabled,
    )
}

@Composable
internal fun CellField(
    value: String,
    onChange: (String) -> Unit,
    width: Dp,
    enabled: Boolean = true,
    error: String? = null,
    onEnter: (() -> Unit)? = null,
) {
    ZillitTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.width(width),
        enabled = enabled,
        errorText = error,
        onImeAction = { onEnter?.invoke() },
    )
}

@Composable
internal fun CellDate(value: String, onChange: (String) -> Unit, width: Dp, enabled: Boolean = true) {
    ZillitDateField(value = value, onValueChange = onChange, modifier = Modifier.width(width), enabled = enabled)
}

/** The radio of Edit single's pick column. */
@Composable
internal fun RadioDot(selected: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Box(
        Modifier.size(RADIO).clip(CircleShape).border(1.dp, if (selected) colors.accent else colors.borderStrong, CircleShape).clickable(onClick = onClick),
    ) {
        if (selected) Box(Modifier.fillMaxSize().padding(RADIO_INSET).clip(CircleShape).background(colors.accent))
    }
}

private val RADIO = 16.dp
private val RADIO_INSET = 3.dp
