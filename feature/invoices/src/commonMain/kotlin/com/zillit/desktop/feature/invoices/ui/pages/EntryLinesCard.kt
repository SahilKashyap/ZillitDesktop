// The coding screen's line grid: coded lines, their splits, the reclaimable-tax row and the totals.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.CodedLine
import com.zillit.desktop.feature.invoices.domain.EntryCoding
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.InvoiceNominal
import com.zillit.desktop.feature.invoices.domain.TrackingSet
import com.zillit.desktop.feature.invoices.ui.EntryEvent
import com.zillit.desktop.feature.invoices.ui.EntryLedger
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState

/**
 * The line grid — the web's "Line Items — Ledger Posting" table
 * (`EntryDetailModal.jsx:1781-2230`): a select column, Description, Code,
 * Layers, Tags, any custom-field columns the lines carry (read-only), Amount
 * and Tax per line, split children marked ↳ under their parent, the
 * reclaimable-tax row, then Split / Add on the left and the Net / Tax / Gross
 * totals on the right. With lines from more than one order, each order's
 * lines sit under its number.
 */
@Composable
internal fun EntryLinesCard(
    state: InvoicesUiState,
    ledger: EntryLedger,
    frozen: Boolean,
    onEvent: (InvoicesEvent) -> Unit,
) {
    val currency = currencyOf(state, ledger)
    val calls = LineCallbacks(
        select = { onEvent(EntryEvent.SelectLine(it)) },
        change = { onEvent(EntryEvent.EditLine(it)) },
        splitAmount = { id, amount -> onEvent(EntryEvent.EditSplitAmount(id, amount)) },
        remove = { onEvent(EntryEvent.RemoveLine(it)) },
    )
    val columns = LineColumns(
        trackingSets = state.trackingSets,
        tags = true,
        tagOptions = state.entryRefs.assetTags,
        custom = customColumns(ledger.lines),
        accounts = state.entryRefs.accounts,
        currency = currency,
        ledger = true,
    )
    val parents = ledger.lines.count { !it.isSplit }
    ZillitSectionCard(
        title = str(S.desktop_inv_line_items_ledger_posting),
        icon = ZillitIcons.Ledger,
        meta = if (parents == 1) {
            str(S.desktop_docdist_one_item, parents)
        } else {
            str(S.desktop_docdist_items_count, parents)
        },
    ) {
        LineGridHeader(columns)
        if (ledger.ordersLoading) {
            LoadingLinesRow()
        } else {
            ledger.lines.forEachIndexed { index, line ->
                // With more than one order, each order's lines sit under its number (`:1885-1903`).
                val previous = ledger.lines.getOrNull(index - 1)?.sourcePo
                if (ledger.orders.size > 1 && line.sourcePo.isNotBlank() && line.sourcePo != previous) {
                    PoGroupRow(line.sourcePo)
                }
                LineItemRow(state, ledger.lines, line, ledger.selectedLineId == line.id, frozen, calls, columns)
            }
        }
        TaxLineRow(state, ledger, frozen, columns, onEvent)
        LinesFooter(state, ledger, frozen, currency, onEvent)
    }
}

/** What a line row does when it is used — the coding screen's events, or a credit note's or sales invoice's. */
internal class LineCallbacks(
    val select: (String) -> Unit,
    val change: (CodedLine) -> Unit,
    val splitAmount: (String, Double) -> Unit,
    val remove: (String) -> Unit,
)

/**
 * Which columns a grid shows and what they offer. The ledger shows Tags and
 * custom fields; Credits and Sales pass `showTags={false}` and have none.
 * [ledger] also picks the ledger's tax rules: switching to Other drops a
 * preset's rate (ZL-20656), where the Credits / Sales editor keeps it.
 */
internal data class LineColumns(
    val trackingSets: List<TrackingSet> = emptyList(),
    val layers: Boolean = true,
    val tags: Boolean = false,
    val tagOptions: List<String> = emptyList(),
    /** Custom-field names, in first-seen order. */
    val custom: List<String> = emptyList(),
    val accounts: List<InvoiceNominal> = emptyList(),
    val currency: String = "",
    val ledger: Boolean = false,
)

/** A line field the last save flagged — its border goes red (`errCls`). */
internal enum class LineField { Description, Account, Amount }

/** Every custom-field name the lines carry, first seen first (`customCols`, `:1027-1054`). */
internal fun customColumns(lines: List<CodedLine>): List<String> =
    lines.flatMap { line -> line.carried?.customFields.orEmpty().map { it.first } }.distinct()

@Composable
internal fun LineGridHeader(columns: LineColumns = LineColumns()) {
    LineRow(header = true) {
        Cell("", Modifier.width(SELECT_WIDTH))
        Cell(str(S.description), Modifier.weight(DESCRIPTION_WEIGHT))
        Cell(str(S.code), Modifier.width(CODE_WIDTH))
        if (columns.layers) Cell(str(S.desktop_layers), Modifier.width(LAYERS_WIDTH))
        if (columns.tags) Cell(str(S.drive_tags), Modifier.width(TAGS_WIDTH))
        columns.custom.forEach { Cell(it, Modifier.width(CUSTOM_WIDTH)) }
        Cell(str(S.amount), Modifier.width(AMOUNT_WIDTH))
        Cell(str(S.ah_lbl_vat), Modifier.width(TAX_WIDTH))
        Cell("", Modifier.width(REMOVE_WIDTH))
    }
}

/**
 * One line: a tick to pick it (a child shows ↳ instead), its description,
 * code, Layers, tags, custom fields and amount — a parent with splits shows
 * the read-only sum its children share — and, for a parent, its tax. A
 * split child's amount re-shares its siblings; the last parent line stays.
 */
@Suppress("LongMethod", "LongParameterList") // One cell per column, in the web's order.
@Composable
internal fun LineItemRow(
    state: InvoicesUiState,
    lines: List<CodedLine>,
    line: CodedLine,
    selected: Boolean,
    frozen: Boolean,
    calls: LineCallbacks,
    columns: LineColumns = LineColumns(),
    errors: Set<LineField> = emptySet(),
) {
    val hasSplits = !line.isSplit && lines.any { it.splitParentId == line.id }
    LineRow(
        selected = selected,
        split = line.isSplit,
        onClick = { calls.select(line.id) }.takeUnless { frozen },
    ) {
        Box(Modifier.width(SELECT_WIDTH).padding(top = ZillitTheme.spacing.sm), contentAlignment = Alignment.Center) {
            if (line.isSplit) {
                ZillitText(text = "↳", style = ZillitTheme.typography.numeric, color = ZillitTheme.colors.accentText)
            } else {
                ZillitCheckbox(checked = selected, onCheckedChange = { calls.select(line.id) }, enabled = !frozen)
            }
        }
        ZillitTextField(
            value = line.description,
            onValueChange = { calls.change(line.copy(description = it)) },
            enabled = !frozen,
            errorText = "".takeIf { LineField.Description in errors },
            modifier = Modifier.weight(DESCRIPTION_WEIGHT),
        )
        EntryNominalField(
            accounts = columns.accounts,
            chart = state.chart,
            code = line.account,
            enabled = !frozen,
            isError = LineField.Account in errors,
            modifier = Modifier.width(CODE_WIDTH),
        ) { calls.change(line.copy(account = it)) }
        if (columns.layers) {
            EntryLayersField(columns.trackingSets, line.trackingCodes, !frozen, Modifier.width(LAYERS_WIDTH)) {
                calls.change(line.copy(trackingCodes = it))
            }
        }
        if (columns.tags) {
            EntryTagsField(columns.tagOptions, line.tags, !frozen, Modifier.width(TAGS_WIDTH)) {
                calls.change(line.copy(tags = it))
            }
        }
        columns.custom.forEach { name ->
            val value = line.carried?.customFields?.firstOrNull { it.first == name }?.second.orEmpty()
            Cell(value.ifBlank { "—" }, Modifier.width(CUSTOM_WIDTH), muted = value.isBlank())
        }
        when {
            line.isSplit -> EntryAmountField(line.amount, !frozen, Modifier.width(AMOUNT_WIDTH)) {
                calls.splitAmount(line.id, it)
            }
            hasSplits -> EntryAmountWell(line.amount, columns.currency, Modifier.width(AMOUNT_WIDTH))
            else -> EntryAmountField(
                value = line.amount,
                enabled = !frozen,
                isError = LineField.Amount in errors,
                modifier = Modifier.width(AMOUNT_WIDTH),
            ) { calls.change(line.withAmount(it)) }
        }
        // A child takes its parent's tax; only a parent picks one.
        if (line.isSplit) {
            Cell("—", Modifier.width(TAX_WIDTH), muted = true)
        } else {
            TaxPicker(state, line, frozen, columns.ledger, Modifier.width(TAX_WIDTH)) { calls.change(it) }
        }
        val removable = !frozen && (line.isSplit || lines.count { !it.isSplit } > 1)
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = str(S.desktop_po_remove_line),
            onClick = { calls.remove(line.id) },
            enabled = removable,
            modifier = Modifier.width(REMOVE_WIDTH),
        )
    }
}

/** One order's number over its lines. */
@Composable
private fun PoGroupRow(poNumber: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.infoSoft, ZillitTheme.shapes.small)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = poNumber,
            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
            color = ZillitTheme.colors.info,
            maxLines = 1,
        )
    }
}

/** The orders' lines are still on their way (`loadingPoData`). */
@Composable
private fun LoadingLinesRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSpinner()
        ZillitText(
            text = str(S.desktop_inv_loading_po_line_items),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/**
 * The reclaimable-tax row: its own nominal, Layers and tags, and an amount
 * that follows the recoverable tax on the lines until someone types over it
 * — then a reset brings the derived figure back.
 */
@Composable
private fun TaxLineRow(
    state: InvoicesUiState,
    ledger: EntryLedger,
    frozen: Boolean,
    columns: LineColumns,
    onEvent: (InvoicesEvent) -> Unit,
) {
    val effective = EntryCoding.effectiveTax(ledger.tax, ledger.lines, state.taxTypes)
    val derived = EntryCoding.reclaimableTax(ledger.lines, state.taxTypes)
    LineRow {
        Cell("", Modifier.width(SELECT_WIDTH))
        Column(Modifier.weight(DESCRIPTION_WEIGHT)) {
            ZillitText(text = str(S.ah_lbl_vat), style = ZillitTheme.typography.bodySmall)
            ZillitText(
                text = str(S.desktop_inv_reclaimable_tax),
                style = ZillitTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
                color = ZillitTheme.colors.textMuted,
            )
        }
        EntryNominalField(
            accounts = columns.accounts,
            chart = state.chart,
            code = ledger.tax.account,
            enabled = !frozen,
            modifier = Modifier.width(CODE_WIDTH),
        ) { onEvent(EntryEvent.EditTaxAccount(it)) }
        if (columns.layers) {
            EntryLayersField(columns.trackingSets, ledger.tax.trackingCodes, !frozen, Modifier.width(LAYERS_WIDTH)) {
                onEvent(EntryEvent.EditTaxLayers(it))
            }
        }
        if (columns.tags) {
            EntryTagsField(columns.tagOptions, ledger.tax.tags, !frozen, Modifier.width(TAGS_WIDTH)) {
                onEvent(EntryEvent.EditTaxTags(it))
            }
        }
        columns.custom.forEach { Cell("—", Modifier.width(CUSTOM_WIDTH), muted = true) }
        Row(Modifier.width(AMOUNT_WIDTH), verticalAlignment = Alignment.CenterVertically) {
            if (ledger.tax.overridden && EntryCoding.round2(effective) != EntryCoding.round2(derived) && !frozen) {
                ZillitIconButton(
                    icon = ZillitIcons.Reload,
                    contentDescription = str(S.desktop_inv_recalculate_from_lines),
                    onClick = { onEvent(EntryEvent.ResetTaxAmount) },
                )
            }
            EntryAmountField(effective, !frozen, Modifier.weight(1f)) {
                onEvent(EntryEvent.EditTaxAmount(it))
            }
        }
        Cell("—", Modifier.width(TAX_WIDTH), muted = true)
        Cell("", Modifier.width(REMOVE_WIDTH))
    }
}

@Composable
private fun LinesFooter(
    state: InvoicesUiState,
    ledger: EntryLedger,
    frozen: Boolean,
    currency: String,
    onEvent: (InvoicesEvent) -> Unit,
) {
    val totals = ledger.totals(state.taxTypes, state.taxTypesKnown)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!frozen) {
            ZillitButton(
                text = str(S.desktop_po_split_line),
                onClick = { onEvent(EntryEvent.SplitLine) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = ledger.canSplit,
            )
            ZillitButton(
                text = str(S.desktop_inv_add_another_line),
                onClick = { onEvent(EntryEvent.AddLine) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        }
        Spacer(Modifier.weight(1f))
        LineTotals(totals.net, totals.tax, totals.gross, currency)
    }
}

/** Net Total · Tax Total | Gross Total — the web's `LineItemTotalsFooter`, all three alike. */
@Composable
internal fun LineTotals(net: Double, tax: Double, gross: Double, currency: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        verticalAlignment = Alignment.Bottom,
    ) {
        Total(str(S.ah_lbl_net_total), InvoiceFormat.money(net, currency))
        Total(str(S.desktop_inv_tax_total), InvoiceFormat.money(tax, currency))
        Box(Modifier.width(1.dp).height(TOTALS_RULE).background(ZillitTheme.colors.divider))
        Total(str(S.ah_lbl_gross_total), InvoiceFormat.money(gross, currency))
    }
}

@Composable
internal fun Total(label: String, value: String, strong: Boolean = false) {
    Column(horizontalAlignment = Alignment.End) {
        ZillitText(
            text = label.uppercase(),
            style = ZillitTheme.typography.columnHeader,
            color = ZillitTheme.colors.textMuted,
        )
        ZillitText(
            text = value,
            style = ZillitTheme.typography.numeric.copy(
                fontWeight = if (strong) FontWeight.Bold else FontWeight.SemiBold,
            ),
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

// -- fields ----------------------------------------------------------------------

/**
 * A money field that keeps what is typed ("12." stays "12.") and only takes
 * the model's figure when it really changed — a split re-cut, say.
 */
@Composable
internal fun AmountField(value: Double, enabled: Boolean, modifier: Modifier, onCommit: (Double) -> Unit) {
    var text by remember { mutableStateOf(InvoiceFormat.plain(value)) }
    LaunchedEffect(value) {
        if (text.parseAmount() != value) text = InvoiceFormat.plain(value)
    }
    ZillitTextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            typed.parseAmount()?.let(onCommit)
        },
        enabled = enabled,
        placeholder = "0.00",
        modifier = modifier,
    )
}

private fun String.parseAmount(): Double? =
    trim().replace(",", "").let { if (it.isEmpty()) 0.0 else it.toDoubleOrNull() }

/**
 * The line's tax: none, a Production Setup type (its rate comes with it), or
 * Other with a rate typed in, capped to 0–100 as the web clamps it.
 *
 * Switching to Other on the ledger keeps the rate only when the line was
 * already Other — a 20% VAT line switched to Other has no rate until one is
 * typed (`EntryDetailModal.jsx:2076-2089`, ZL-20656). The Credits / Sales
 * editor keeps the old rate (`LineItemsEditor.jsx:458-464`). Under a preset
 * the ledger shows its rate, read-only.
 */
@Composable
private fun TaxPicker(
    state: InvoicesUiState,
    line: CodedLine,
    frozen: Boolean,
    ledger: Boolean,
    modifier: Modifier,
    onChange: (CodedLine) -> Unit,
) {
    val options = listOf("") + state.taxTypes.map { it.identifier } + OTHER
    val current = taxOption(line, state)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitSelect(
            value = current,
            options = options,
            onSelect = { picked ->
                onChange(EntryCoding.pickTax(line, picked, state.taxTypes, keepRateOnOther = !ledger))
            },
            label = { id ->
                when (id) {
                    "" -> if (ledger) str(S.select) + "…" else str(S.ah_select_vat_tax_dots)
                    OTHER -> str(S.ah_tax_other_label)
                    else -> state.taxTypes.firstOrNull { it.identifier == id }?.optionLabel ?: id
                }
            },
            enabled = !frozen,
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            current == OTHER -> AmountField(line.taxRate ?: 0.0, !frozen, Modifier.fillMaxWidth()) { rate ->
                onChange(line.copy(taxType = OTHER, taxRate = rate.coerceIn(0.0, MAX_RATE)))
            }
            ledger && current.isNotBlank() && line.taxRate != null -> ZillitText(
                text = "${trimRate(line.taxRate)}%",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
                modifier = Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.xs),
            )
        }
    }
}

private fun trimRate(rate: Double): String =
    if (rate == rate.toLong().toDouble()) rate.toLong().toString() else rate.toString()

// -- layout -----------------------------------------------------------------------

@Composable
private fun LineRow(
    header: Boolean = false,
    selected: Boolean = false,
    split: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                when {
                    header -> colors.surfaceSunken
                    selected -> colors.accentSoft
                    split -> colors.surfaceSunken.copy(alpha = SPLIT_ALPHA)
                    else -> Color.Transparent
                },
                ZillitTheme.shapes.small,
            )
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = ZillitTheme.spacing.xs, vertical = ZillitTheme.spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = if (header) Alignment.CenterVertically else Alignment.Top,
        content = content,
    )
}

@Composable
private fun Cell(text: String, modifier: Modifier, muted: Boolean = false) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.columnHeader,
        color = if (muted) ZillitTheme.colors.textMuted else ZillitTheme.colors.textSecondary,
        maxLines = 1,
        modifier = modifier.padding(vertical = ZillitTheme.spacing.xs),
    )
}

/** The picker's value for a line: its Production Setup type, Other for a typed rate, or none. */
private fun taxOption(line: CodedLine, state: InvoicesUiState): String = when {
    line.taxType.isNotBlank() && state.taxTypes.any { it.identifier == line.taxType } -> line.taxType
    line.taxType.isNotBlank() || line.taxRate != null -> OTHER
    else -> ""
}

private const val OTHER = EntryCoding.OTHER_TAX
private const val MAX_RATE = 100.0
private const val DESCRIPTION_WEIGHT = 1f
private const val SPLIT_ALPHA = 0.5f
private val SELECT_WIDTH = 28.dp
private val CODE_WIDTH = 140.dp
private val LAYERS_WIDTH = 120.dp
private val TAGS_WIDTH = 140.dp
private val CUSTOM_WIDTH = 110.dp
private val AMOUNT_WIDTH = 130.dp
private val TAX_WIDTH = 150.dp
private val REMOVE_WIDTH = 36.dp
private val TOTALS_RULE = 32.dp
