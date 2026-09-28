// The line grid's pickers: Layers, Tags, the chart-of-accounts code and the calculator amount.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.CalcExpression
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCalcField
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitMultiSelect
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.EntryCoding
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.InvoiceNominal
import com.zillit.desktop.feature.invoices.domain.TrackingSet

/**
 * A line's Layers — the web's compact `TrackingCodesPicker`: the picked codes
 * (or "+ Layers") on a button that opens every active set with its codes;
 * picking a code sets that set, "— none —" clears it. A pick for a set that
 * is no longer offered is kept as it is.
 */
@Composable
internal fun EntryLayersField(
    sets: List<TrackingSet>,
    picked: Map<String, String>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Map<String, String>) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val text = picked.values.filter { it.isNotBlank() }.joinToString(", ")
        .ifBlank { str(S.desktop_ce_process_add_layers) }
    Box(modifier) {
        ZillitButton(
            text = text,
            onClick = { open = true },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        if (open) {
            LayersPopup(sets, picked, onDismiss = { open = false }) { next -> onChange(next) }
        }
    }
}

@Composable
private fun LayersPopup(
    sets: List<TrackingSet>,
    picked: Map<String, String>,
    onDismiss: () -> Unit,
    onChange: (Map<String, String>) -> Unit,
) {
    val colors = ZillitTheme.colors
    Popup(
        offset = IntOffset(0, POPUP_DROP),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .width(LAYERS_POPUP_WIDTH)
                .clip(ZillitTheme.shapes.large)
                .background(colors.surfaceRaised)
                .border(1.dp, colors.border, ZillitTheme.shapes.large)
                .padding(vertical = ZillitTheme.spacing.xs),
        ) {
            val shown = sets.filter { it.active }
            if (shown.isEmpty()) {
                ZillitText(
                    text = str(S.desktop_tax_no_layers),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                    modifier = Modifier.padding(ZillitTheme.spacing.md),
                )
            }
            ZillitScrollColumn(modifier = Modifier.heightIn(max = LAYERS_POPUP_HEIGHT)) {
                shown.forEach { set ->
                    LayersHeading(set.name.ifBlank { set.prefix })
                    val current = picked[set.id].orEmpty()
                    val currentCode = set.resolve(current)?.code ?: current
                    LayersOption(str(S.desktop_tax_none_dash), selected = currentCode.isBlank()) {
                        onChange(picked - set.id)
                    }
                    set.pickable.forEach { node ->
                        LayersOption(node.optionLabel, selected = node.code == currentCode) {
                            onChange(picked + (set.id to node.code))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LayersHeading(text: String) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = ZillitTheme.colors.textMuted,
        modifier = Modifier.padding(
            start = ZillitTheme.spacing.md,
            top = ZillitTheme.spacing.sm,
            bottom = ZillitTheme.spacing.xxs,
        ),
        maxLines = 1,
    )
}

@Composable
private fun LayersOption(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) colors.surfaceSelected else colors.surfaceRaised)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodySmall,
            color = if (selected) colors.accentText else colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (selected) ZillitIcon(icon = ZillitIcons.Check, tint = colors.accentText, size = CHECK_SIZE)
    }
}

/** A line's account tags — the web's `TagMultiSelect` over Production Setup's `asset_tags`. */
@Composable
internal fun EntryTagsField(
    options: List<String>,
    selected: List<String>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onChange: (List<String>) -> Unit,
) {
    ZillitMultiSelect(
        selected = selected,
        // A saved tag no longer on the list still shows, and can be taken off.
        options = (options + selected).distinct(),
        label = { it },
        onChange = onChange,
        placeholder = str(S.desktop_tax_add_tags),
        enabled = enabled,
        emptyText = str(S.desktop_ce_process_no_more_tags),
        modifier = modifier,
    )
}

/**
 * A nominal — the web's `CoaCodeInput`: the chart's posting codes to search
 * and pick (code, with its name beneath), or a code typed that the chart has
 * not got. Under it, for such a code, that saving will add it (it goes as
 * `[[code]]`).
 */
@Composable
internal fun EntryNominalField(
    accounts: List<InvoiceNominal>,
    chart: Set<String>,
    code: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    placeholder: String = str(S.code) + "…",
    onChange: (String) -> Unit,
) {
    val trimmed = code.trim()
    val names = remember(accounts) { accounts.associate { it.code to it.name } }
    val options = remember(accounts, trimmed) {
        (listOf("") + accounts.map { it.code } + listOfNotNull(trimmed.takeIf { it.isNotEmpty() })).distinct()
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        if (accounts.isEmpty()) {
            // The chart not read yet (or empty): a code is typed, as it always could be.
            ZillitTextField(
                value = code,
                onValueChange = onChange,
                placeholder = placeholder,
                enabled = enabled,
                errorText = "".takeIf { isError },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            ZillitSearchSelect(
                value = trimmed,
                options = options,
                onSelect = onChange,
                label = { it.ifBlank { placeholder } },
                enabled = enabled,
                isError = isError,
                searchText = { "$it ${names[it].orEmpty()}" },
                subtitle = { names[it] },
                onCreate = onChange,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (trimmed.isNotEmpty() && EntryCoding.wrapNominal(trimmed, chart) != trimmed) {
            ZillitText(
                text = str(S.desktop_inv_new_nominal_code, trimmed),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.warning,
                maxLines = 2,
            )
        }
    }
}

/**
 * A line amount — the web's `CalcInput`: a plain number commits as typed, a
 * sum (`2*2`) when the field is left or Enter pressed, and it reads grouped
 * at rest.
 */
@Composable
internal fun EntryAmountField(
    value: Double,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    onCommit: (Double) -> Unit,
) {
    ZillitCalcField(
        value = CalcExpression.plain(value),
        onCommit = { text -> onCommit(text.toDoubleOrNull() ?: 0.0) },
        enabled = enabled,
        errorText = "".takeIf { isError },
        modifier = modifier,
    )
}

/** A parent line with splits: its amount is the read-only sum its children share (`:2047-2050`). */
@Composable
internal fun EntryAmountWell(value: Double, currency: String, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    Box(
        modifier = modifier
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.sm),
        contentAlignment = Alignment.CenterEnd,
    ) {
        ZillitText(
            text = InvoiceFormat.money(value, currency),
            style = ZillitTheme.typography.numeric,
            color = colors.textMuted,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private val LAYERS_POPUP_WIDTH = 280.dp
private val LAYERS_POPUP_HEIGHT = 320.dp
private val CHECK_SIZE = 14.dp
private const val POPUP_DROP = 32
