package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
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
 * currency's name and symbol).
 */
@Suppress("LongMethod", "LongParameterList") // A control, read top to bottom.
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
                .border(
                    1.dp,
                    when {
                        error -> colors.danger
                        open -> colors.focusRing
                        else -> colors.border
                    },
                    ZillitTheme.shapes.medium,
                )
                .clickable(enabled = enabled) { open = !open }
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = value?.let(label) ?: placeholder,
                style = ZillitTheme.typography.bodyMedium,
                color = if (value == null) colors.textMuted else colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            ZillitIcon(icon = ZillitIcons.ChevronDown, tint = colors.textMuted, size = ZillitDimens.iconSmall)
        }
        if (open) {
            Popup(
                offset = IntOffset(0, POPUP_DROP),
                onDismissRequest = {
                    open = false
                    search = ""
                },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    modifier = Modifier
                        .width(popupWidth)
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
                        placeholder = str(S.search) + "…",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val needle = search.trim()
                    val shown = options.filter { needle.isEmpty() || searchText(it).contains(needle, ignoreCase = true) }
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = LIST_MAX).verticalScroll(rememberScrollState()),
                    ) {
                        if (shown.isEmpty()) {
                            ZillitText(
                                text = str(S.desktop_nothing_to_choose_from),
                                style = ZillitTheme.typography.bodySmall,
                                color = colors.textMuted,
                                modifier = Modifier.padding(ZillitTheme.spacing.sm),
                            )
                        }
                        shown.forEach { option ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(ZillitTheme.shapes.small)
                                    .background(if (option == value) colors.accentSoft else colors.surfaceRaised)
                                    .clickable {
                                        onSelect(option)
                                        open = false
                                        search = ""
                                    }
                                    .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
                            ) {
                                ZillitText(text = label(option), style = ZillitTheme.typography.bodyMedium, maxLines = 1)
                                subline?.invoke(option)?.takeIf { it.isNotBlank() }?.let {
                                    ZillitText(
                                        text = it,
                                        style = ZillitTheme.typography.labelSmall,
                                        color = colors.textMuted,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val POPUP_WIDTH = 360.dp
private val LIST_MAX = 300.dp
private val POPUP_ELEVATION = 12.dp
private const val POPUP_DROP = 40
