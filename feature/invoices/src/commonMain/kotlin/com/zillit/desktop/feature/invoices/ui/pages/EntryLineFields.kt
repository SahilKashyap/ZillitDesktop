// The line grid's pickers: Layers, Tags, the chart-of-accounts code and the calculator amount.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.CalcExpression
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCalcField
import com.zillit.desktop.core.designsystem.component.ZillitMultiSelect
import com.zillit.desktop.core.designsystem.component.ZillitOptionPopup
import com.zillit.desktop.core.designsystem.component.ZillitSearchSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.popupWidth
import com.zillit.desktop.core.designsystem.component.rememberZillitSelectAnchor
import com.zillit.desktop.core.designsystem.component.zillitSelectAnchor
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
    val anchor = rememberZillitSelectAnchor()
    val text = picked.values.filter { it.isNotBlank() }.joinToString(", ")
        .ifBlank { str(S.desktop_ce_process_add_layers) }
    Box(modifier.zillitSelectAnchor(anchor)) {
        ZillitButton(
            text = text,
            onClick = { open = true },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        if (open) {
            val none = str(S.desktop_tax_none_dash)
            val rows = sets.filter { it.active }.flatMap { set ->
                listOf(LayerRow(set, null, none)) + set.pickable.map { LayerRow(set, it.code, it.optionLabel) }
            }
            // One pick per set: a code sets its set, "none" clears it, and the
            // list stays open so every set can be coded in one visit.
            ZillitOptionPopup(
                onDismiss = { open = false },
                width = maxOf(anchor.popupWidth(), LAYERS_POPUP_WIDTH),
                options = rows,
                isSelected = { row ->
                    val current = picked[row.set.id].orEmpty()
                    val currentCode = row.set.resolve(current)?.code ?: current
                    if (row.code == null) currentCode.isBlank() else row.code == currentCode
                },
                onPick = { row ->
                    onChange(if (row.code == null) picked - row.set.id else picked + (row.set.id to row.code))
                },
                label = LayerRow::label,
                searchText = { "${it.heading} ${it.label}" },
                showInitials = false,
                section = LayerRow::heading,
                emptyText = str(S.desktop_tax_no_layers),
            )
        }
    }
}

/** One row of the Layers list: a set's code, or its "none" ([code] null). */
private data class LayerRow(val set: TrackingSet, val code: String?, val label: String) {
    val heading: String get() = set.name.ifBlank { set.prefix }
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
