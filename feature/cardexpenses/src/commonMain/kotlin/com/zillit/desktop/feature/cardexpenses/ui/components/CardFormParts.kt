package com.zillit.desktop.feature.cardexpenses.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * A searchable picker whose options carry a second line — the web's
 * `RichSelect` as the card forms use it: a holder with their department and
 * role, a provider with its bank and company.
 *
 * A plain column in the popup rather than a lazy list: a lazy list inside a
 * popup crashes unless its height is fixed, and these lists are short.
 */
@Suppress("LongMethod") // The field and its popup are one control.
@Composable
fun <T> CardRichSelect(
    value: T?,
    options: List<T>,
    onSelect: (T) -> Unit,
    title: (T) -> String,
    placeholder: String,
    modifier: Modifier = Modifier,
    subtitle: (T) -> String = { "" },
    searchText: (T) -> String = { title(it) + " " + subtitle(it) },
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val colors = ZillitTheme.colors
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = FIELD_HEIGHT)
                .clip(ZillitTheme.shapes.medium)
                .background(if (enabled) colors.surface else colors.surfaceSunken)
                .border(1.dp, if (open) colors.focusRing else colors.border, ZillitTheme.shapes.medium)
                .clickable(enabled = enabled) { open = !open }
                .padding(horizontal = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = value?.let(title) ?: placeholder,
                style = ZillitTheme.typography.bodyMedium,
                color = if (value == null) colors.textMuted else colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            ZillitIcon(ZillitIcons.ChevronDown, tint = colors.textMuted, size = ZillitDimens.iconSmall)
        }
        if (open) {
            Popup(
                offset = IntOffset(0, POPUP_DROP),
                onDismissRequest = { open = false; query = "" },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    modifier = Modifier
                        .width(POPUP_WIDTH)
                        .shadow(8.dp, ZillitTheme.shapes.large)
                        .clip(ZillitTheme.shapes.large)
                        .background(colors.surfaceRaised)
                        .border(1.dp, colors.border, ZillitTheme.shapes.large)
                        .padding(ZillitTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    ZillitSearchField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth())
                    val shown = options.filter { query.isBlank() || searchText(it).contains(query.trim(), true) }
                    Column(Modifier.fillMaxWidth().heightIn(max = LIST_MAX).verticalScroll(rememberScrollState())) {
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
                                    .clip(ZillitTheme.shapes.medium)
                                    .background(if (option == value) colors.surfaceSelected else colors.surfaceRaised)
                                    .clickable {
                                        onSelect(option)
                                        open = false
                                        query = ""
                                    }
                                    .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
                            ) {
                                ZillitText(
                                    text = title(option),
                                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                )
                                subtitle(option).takeIf { it.isNotBlank() }?.let {
                                    ZillitText(
                                        text = it,
                                        style = ZillitTheme.typography.bodySmall,
                                        color = colors.textSecondary,
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

/** A form label as the web's card forms draw it: small caps, and a red star when required. */
@Composable
fun FormLabel(text: String, required: Boolean = false) {
    Row {
        ZillitText(
            text = text.uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = ZillitTheme.colors.textSecondary,
        )
        if (required) {
            ZillitText(
                text = " *",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.danger,
            )
        }
    }
}

/** A read-only value in a form, sunken like the web's grey inputs. */
@Composable
fun ReadOnlyField(value: String, modifier: Modifier = Modifier, placeholder: String = "") {
    val colors = ZillitTheme.colors
    ZillitText(
        text = value.ifBlank { placeholder },
        style = ZillitTheme.typography.bodyMedium,
        color = if (value.isBlank()) colors.textMuted else colors.textPrimary,
        maxLines = 1,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = FIELD_HEIGHT)
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
    )
}

/**
 * The register's one joined bar (`CardRegisterPage.jsx:887-945`): search, then
 * Funds, the Export menu and the orange request button, flush inside one
 * rounded shell and split by hairlines.
 */
@Suppress("LongMethod") // One bar, segment by segment, as the web lays it out.
@Composable
fun SeamlessBar(
    search: String,
    onSearch: (String) -> Unit,
    placeholder: String,
    exporting: Boolean,
    onFunds: () -> Unit,
    onExport: (pdf: Boolean) -> Unit,
    requestLabel: String,
    onRequest: () -> Unit,
) {
    val colors = ZillitTheme.colors
    var exportOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).padding(horizontal = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIcon(ZillitIcons.Search, tint = colors.textMuted, size = SEARCH_ICON)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (search.isEmpty()) {
                    ZillitText(text = placeholder, color = colors.textMuted, maxLines = 1)
                }
                BasicTextField(
                    value = search,
                    onValueChange = onSearch,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = ZillitTheme.typography.bodyMedium.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accent),
                )
            }
        }
        BarSeparator()
        BarSegment(text = str(S.desktop_ce_funds), onClick = onFunds)
        BarSeparator()
        Box {
            BarSegment(
                text = if (exporting) str(S.desktop_exporting) else str(S.asset_export),
                icon = ZillitIcons.Download,
                trailing = ZillitIcons.ChevronDown,
                enabled = !exporting,
                onClick = { exportOpen = true },
            )
            ZillitActionMenu(
                expanded = exportOpen,
                onDismissRequest = { exportOpen = false },
                entries = listOf(
                    ZillitMenuEntry.Action(str(S.av_pdf), onClick = { onExport(true) }),
                    ZillitMenuEntry.Action(str(S.excel), onClick = { onExport(false) }),
                ),
            )
        }
        BarSeparator()
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .background(colors.accent)
                .clickable(onClick = onRequest)
                .padding(horizontal = SEGMENT_PADDING),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitIcon(ZillitIcons.Add, tint = colors.textOnAccent, size = SEARCH_ICON)
            ZillitText(
                text = requestLabel,
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = colors.textOnAccent,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun BarSeparator() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(ZillitTheme.colors.border))
}

@Composable
private fun BarSegment(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    trailing: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .height(BAR_HEIGHT)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = SEGMENT_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        icon?.let { ZillitIcon(it, tint = colors.textPrimary, size = SEARCH_ICON) }
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = if (enabled) colors.textPrimary else colors.textMuted,
            maxLines = 1,
        )
        trailing?.let { ZillitIcon(it, tint = colors.textMuted, size = TRAILING_ICON) }
    }
}

/** The status pills over a register (`QuickFilters`), in place of a dropdown. */
@Composable
fun StatusPills(options: List<Pair<String, String>>, active: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        options.forEach { (value, label) ->
            ZillitChoiceChip(label = label, selected = value == active, onClick = { onSelect(value) })
        }
    }
}

private val FIELD_HEIGHT = 36.dp
private val BAR_HEIGHT = 44.dp
private val SEGMENT_PADDING = 22.dp
private val SEARCH_ICON = 16.dp
private val TRAILING_ICON = 12.dp
private val POPUP_WIDTH = 340.dp
private val LIST_MAX = 280.dp
private const val POPUP_DROP = 40
