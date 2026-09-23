// The coding screen's line grid: coded lines, their splits, the reclaimable-tax row and the totals.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.CodedLine
import com.zillit.desktop.feature.invoices.domain.EntryCoding
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.ui.EntryEvent
import com.zillit.desktop.feature.invoices.ui.EntryLedger
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState

/**
 * The line grid — Description, Code, Amount and Tax per line, split children
 * indented under their parent, the reclaimable-tax row, then Split / Add on
 * the left and Net / Tax / Gross on the right.
 *
 * Layers, Tags and custom fields are not edited here; they are carried over
 * untouched on save.
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
    ZillitSectionCard(title = str(S.ah_line_items), icon = ZillitIcons.Ledger) {
        LineGridHeader()
        ledger.lines.forEachIndexed { index, line ->
            LineItemRow(state, ledger.lines, line, index, ledger.selectedLineId == line.id, frozen, calls)
        }
        TaxLineRow(state, ledger, frozen, onEvent)
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

@Composable
internal fun LineGridHeader() {
    LineRow(header = true) {
        Cell(str(S.description), Modifier.weight(DESCRIPTION_WEIGHT))
        Cell(str(S.code), Modifier.width(CODE_WIDTH))
        Cell(str(S.amount), Modifier.width(AMOUNT_WIDTH))
        Cell(str(S.ah_lbl_vat), Modifier.width(TAX_WIDTH))
        Cell("", Modifier.width(REMOVE_WIDTH))
    }
}

/**
 * One line: its description (a child indented, with the order it came off
 * when it came off one), its code, its amount and — for a parent — its tax.
 * A split child's amount re-shares its siblings; the last parent line stays.
 */
@Composable
internal fun LineItemRow(
    state: InvoicesUiState,
    lines: List<CodedLine>,
    line: CodedLine,
    index: Int,
    selected: Boolean,
    frozen: Boolean,
    calls: LineCallbacks,
    flagged: Boolean = false,
) {
    LineRow(
        selected = selected,
        flagged = flagged,
        onClick = { calls.select(line.id) }.takeUnless { frozen },
    ) {
        Column(
            Modifier.weight(DESCRIPTION_WEIGHT),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            ZillitTextField(
                value = line.description,
                onValueChange = { calls.change(line.copy(description = it)) },
                placeholder = if (line.isSplit) "↳ ${index + 1}" else null,
                enabled = !frozen,
                modifier = Modifier.fillMaxWidth().padding(start = if (line.isSplit) SPLIT_INDENT else 0.dp),
            )
            if (line.sourcePo.isNotBlank()) MutedLine(str(S.docusign_from_prefix, line.sourcePo))
        }
        NominalField(state, line.account, frozen, Modifier.width(CODE_WIDTH)) {
            calls.change(line.copy(account = it))
        }
        AmountField(line.amount, !frozen, Modifier.width(AMOUNT_WIDTH)) { amount ->
            if (line.isSplit) calls.splitAmount(line.id, amount) else calls.change(line.withAmount(amount))
        }
        // A child takes its parent's tax; only a parent picks one.
        if (line.isSplit) {
            Cell("—", Modifier.width(TAX_WIDTH), muted = true)
        } else {
            TaxPicker(state, line, frozen, Modifier.width(TAX_WIDTH)) { calls.change(it) }
        }
        val removable = !frozen && (line.isSplit || lines.count { !it.isSplit } > 1)
        ZillitIconButton(
            icon = ZillitIcons.Trash,
            contentDescription = str(S.desktop_po_remove_line),
            onClick = { calls.remove(line.id) },
            enabled = removable,
            modifier = Modifier.width(REMOVE_WIDTH),
        )
    }
}

/**
 * The reclaimable-tax row: its own nominal, and an amount that follows the
 * recoverable tax on the lines until someone types over it — then a reset
 * brings the derived figure back.
 */
@Composable
private fun TaxLineRow(state: InvoicesUiState, ledger: EntryLedger, frozen: Boolean, onEvent: (InvoicesEvent) -> Unit) {
    val effective = EntryCoding.effectiveTax(ledger.tax, ledger.lines, state.taxTypes)
    val derived = EntryCoding.reclaimableTax(ledger.lines, state.taxTypes)
    LineRow {
        Column(Modifier.weight(DESCRIPTION_WEIGHT)) {
            ZillitText(text = str(S.ah_lbl_vat), style = ZillitTheme.typography.bodySmall)
            ZillitText(
                text = str(S.desktop_inv_reclaimable_tax),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        NominalField(state, ledger.tax.account, frozen, Modifier.width(CODE_WIDTH)) {
            onEvent(EntryEvent.EditTaxAccount(it))
        }
        Row(Modifier.width(AMOUNT_WIDTH), verticalAlignment = Alignment.CenterVertically) {
            AmountField(effective, !frozen, Modifier.weight(1f)) { onEvent(EntryEvent.EditTaxAmount(it)) }
            if (ledger.tax.overridden && EntryCoding.round2(effective) != EntryCoding.round2(derived) && !frozen) {
                ZillitIconButton(
                    icon = ZillitIcons.Reload,
                    contentDescription = str(S.desktop_inv_recalculate_from_lines),
                    onClick = { onEvent(EntryEvent.ResetTaxAmount) },
                )
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
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        Total(str(S.desktop_net), InvoiceFormat.money(totals.net, currency))
        Total(str(S.ah_lbl_vat), InvoiceFormat.money(totals.tax, currency))
        Total(str(S.desktop_gross), InvoiceFormat.money(totals.gross, currency), strong = true)
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
                fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal,
            ),
            color = if (strong) ZillitTheme.colors.accentText else ZillitTheme.colors.textPrimary,
        )
    }
}

// -- fields ----------------------------------------------------------------------

/**
 * A nominal, typed. Under it, the chart's name for the code — or, for a code
 * the chart has not got, that saving will add it (it goes as `[[code]]`).
 */
@Composable
private fun NominalField(
    state: InvoicesUiState,
    code: String,
    frozen: Boolean,
    modifier: Modifier,
    onChange: (String) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitTextField(
            value = code,
            onValueChange = onChange,
            placeholder = str(S.code),
            enabled = !frozen,
            modifier = Modifier.fillMaxWidth(),
        )
        val trimmed = code.trim()
        if (trimmed.isNotEmpty() && EntryCoding.wrapNominal(trimmed, state.chart) != trimmed) {
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
 */
@Composable
private fun TaxPicker(
    state: InvoicesUiState,
    line: CodedLine,
    frozen: Boolean,
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
                val type = state.taxTypes.firstOrNull { it.identifier == picked }
                onChange(
                    when {
                        picked.isBlank() -> line.copy(taxType = "", taxRate = null)
                        type != null -> line.copy(taxType = type.identifier, taxRate = type.rate)
                        else -> line.copy(taxType = OTHER, taxRate = line.taxRate ?: 0.0)
                    },
                )
            },
            label = { id ->
                when (id) {
                    "" -> str(S.desktop_inv_no_tax)
                    OTHER -> str(S.ah_tax_other_label)
                    else -> state.taxTypes.firstOrNull { it.identifier == id }?.optionLabel ?: id
                }
            },
            enabled = !frozen,
            modifier = Modifier.fillMaxWidth(),
        )
        if (current == OTHER) {
            AmountField(line.taxRate ?: 0.0, !frozen, Modifier.fillMaxWidth()) { rate ->
                onChange(line.copy(taxType = OTHER, taxRate = rate.coerceIn(0.0, MAX_RATE)))
            }
        }
    }
}

// -- layout -----------------------------------------------------------------------

@Composable
private fun LineRow(
    header: Boolean = false,
    selected: Boolean = false,
    flagged: Boolean = false,
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
                    flagged -> colors.dangerSoft
                    selected -> colors.accentSoft
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

/** The web's `OTHER_TAX_OPTION` value — a rate typed in, not a Production Setup type. */
private const val OTHER = "other"
private const val MAX_RATE = 100.0
private const val DESCRIPTION_WEIGHT = 1f
private val CODE_WIDTH = 150.dp
private val AMOUNT_WIDTH = 130.dp
private val TAX_WIDTH = 170.dp
private val REMOVE_WIDTH = 36.dp
private val SPLIT_INDENT = 16.dp
