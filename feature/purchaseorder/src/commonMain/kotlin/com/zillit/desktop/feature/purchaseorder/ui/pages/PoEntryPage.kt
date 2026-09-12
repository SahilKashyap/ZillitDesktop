package com.zillit.desktop.feature.purchaseorder.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitVerticalDivider
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.purchaseorder.domain.PoAccess
import com.zillit.desktop.feature.purchaseorder.domain.PoLine
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.ui.PoEntryState
import com.zillit.desktop.feature.purchaseorder.ui.PoEvent
import com.zillit.desktop.feature.purchaseorder.ui.PoUiState
import com.zillit.desktop.feature.purchaseorder.ui.balances
import com.zillit.desktop.feature.purchaseorder.domain.isoDayToUtcMidnight

/**
 * PO Entry — the accountant's processing page, the web's `POEntry`.
 *
 * The point of the surface is that an order has two documents: what the
 * department asked for, and what the books record. They must come to the same
 * number, and this page is where the second is written. The order itself is on
 * the right, read-only, so the coding can be checked against it without
 * leaving.
 */
@Composable
internal fun PoEntryPage(state: PoUiState, entry: PoEntryState, onEvent: (PoEvent) -> Unit) {
    val order = state.detail?.takeIf { it.id == entry.orderId }
    Column(modifier = Modifier.fillMaxSize()) {
        EntryTopBar(state, entry, onEvent)
        ZillitDivider()
        if (order == null) {
            ZillitNotice(
                text = "Loading PO…",
                tone = StatusTone.Progress,
                icon = ZillitIcons.Clock,
            )
            return@Column
        }
        val balanced = entry.balances(order)
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            ZillitScrollColumn(
                modifier = Modifier.weight(CODING_WEIGHT).fillMaxHeight(),
                contentPadding = PaddingValues(ZillitTheme.spacing.xl),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            ) {
                if (!balanced) {
                    ZillitNotice(
                        text = "Coded lines must match the PO total before you can post — the lines come to " +
                            "${Money.format(entry.ledgerTotal, order.currency)} against " +
                            "${Money.format(order.gross, order.currency)}.",
                        tone = StatusTone.Pending,
                        icon = ZillitIcons.Warning,
                    )
                }
                CodingCard(state, entry, onEvent)
                EntryTotals(
                    entry = entry,
                    orderGross = order.gross,
                    currency = order.currency,
                    balanced = balanced,
                    reclaimable = entry.totals.reclaimable(
                        entry.lines,
                        state.taxTypes.filter { it.recoverable }.map { it.id }.toSet(),
                    ),
                )
            }
            // Vertical, not ZillitDivider: a full-width horizontal rule inside
            // a Row claims the whole width and collapses its weighted
            // siblings, which blanks the page with no error at all.
            if (entry.previewOpen) ZillitVerticalDivider()
            if (entry.previewOpen) {
                ZillitScrollColumn(
                    modifier = Modifier.weight(PREVIEW_WEIGHT).fillMaxHeight(),
                    contentPadding = PaddingValues(ZillitTheme.spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    PoDocumentPanel(state, order)
                }
            }
        }
    }
}

@Composable
private fun EntryTopBar(state: PoUiState, entry: PoEntryState, onEvent: (PoEvent) -> Unit) {
    val order = state.detail?.takeIf { it.id == entry.orderId }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitButton(
            text = "Back to Queue",
            onClick = { onEvent(PoEvent.CloseEntry) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.ArrowLeft,
        )
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = order?.number?.ifBlank { "Purchase order" } ?: "Purchase order",
                style = ZillitTheme.typography.titleMedium,
            )
            ZillitText(
                text = listOfNotNull(
                    order?.let { state.vendorName(it) }?.takeIf { it.isNotBlank() },
                    "Assigned to: ${order?.let { state.assigneeName(it) } ?: "Unassigned"}",
                ).joinToString(" · "),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        order?.let { ZillitStatusPill(label = it.statusLabel, tone = it.status.tone()) }
        EntryActions(state, entry, order, onEvent)
    }
}

/** The processing page's actions, in the web's own order. */
@Composable
private fun EntryActions(
    state: PoUiState,
    entry: PoEntryState,
    order: PurchaseOrder?,
    onEvent: (PoEvent) -> Unit,
) {
    ZillitButton(
        text = if (entry.previewOpen) "Collapse PO preview" else "Show PO Preview",
        onClick = { onEvent(PoEvent.EditEntry(entry.copy(previewOpen = !entry.previewOpen))) },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        leadingIcon = ZillitIcons.Eye,
    )
    if (order != null) {
        ZillitButton(
            text = "View PDF",
            onClick = { onEvent(PoEvent.ViewPdf(order.id)) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Download,
            enabled = !state.busy,
        )
        // Resend is allowed here and nowhere else: this is the surface where
        // the order is corrected, so the vendor has to be able to get the
        // amended copy. The read-only detail stays one-shot.
        if (PoAccess.canSendVendorEmail(order, state.viewer, allowResend = true)) {
            ZillitButton(
                text = when {
                    entry.sending -> "Sending…"
                    order.emailed -> "Resend to Vendor"
                    else -> "Send to Vendor"
                },
                onClick = { onEvent(PoEvent.SendVendorEmail(order.id, allowResend = true)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Send,
                loading = entry.sending,
                enabled = !entry.sending,
            )
        }
    }
    ZillitButton(
        text = "Save",
        onClick = { onEvent(PoEvent.SaveEntry) },
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Small,
        loading = entry.saving,
        enabled = !entry.saving && !entry.posting,
    )
    ZillitButton(
        text = "Post to Ledger",
        onClick = { onEvent(PoEvent.PostEntry) },
        size = ButtonSize.Small,
        leadingIcon = ZillitIcons.Ledger,
        loading = entry.posting,
        enabled = !entry.saving && !entry.posting,
    )
}

/** The coded ledger lines, and the two header fields that decide where they land. */
@Suppress("LongMethod") // One row of controls per line.
@Composable
private fun CodingCard(state: PoUiState, entry: PoEntryState, onEvent: (PoEvent) -> Unit) {
    ZillitSectionCard(
        title = "Coded lines",
        icon = ZillitIcons.Ledger,
        action = {
            ZillitButton(
                text = "Add line",
                onClick = { onEvent(PoEvent.AddEntryLine) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            ZillitDateField(
                value = EpochDate.isoDate(entry.effectiveDate),
                onValueChange = { iso ->
                    onEvent(PoEvent.EditEntry(entry.copy(effectiveDate = iso.isoDayToUtcMidnight())))
                },
                label = "Effective Date",
                helperText = "The period these lines post into.",
                modifier = Modifier.width(DATE_FIELD),
            )
            ZillitTextField(
                value = entry.nominalCode,
                onValueChange = { onEvent(PoEvent.EditEntry(entry.copy(nominalCode = it))) },
                label = "Nominal Code",
                placeholder = "Search or enter code…",
                modifier = Modifier.width(DATE_FIELD),
            )
        }
        ZillitDivider()
        EntryLineHeader()
        ZillitDivider()
        entry.lines.forEachIndexed { index, line ->
            EntryLineRow(state, entry, line, index, onEvent)
            if (index != entry.lines.lastIndex) ZillitDivider()
        }
    }
}

/** The coded table's column headers — a row of bare inputs is unreadable without them. */
@Composable
private fun EntryLineHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        EntryHeaderCell("Description", Modifier.weight(1f))
        EntryHeaderCell("Qty", Modifier.width(QTY_FIELD))
        EntryHeaderCell("Unit", Modifier.width(PRICE_FIELD))
        EntryHeaderCell("Code", Modifier.width(CODE_FIELD))
        EntryHeaderCell("Tax", Modifier.width(TAX_FIELD))
        EntryHeaderCell("Amount", Modifier.width(AMOUNT_WIDTH))
    }
}

@Composable
private fun EntryHeaderCell(text: String, modifier: Modifier = Modifier) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.textMuted,
        modifier = modifier,
        maxLines = 1,
    )
}

@Suppress("LongMethod") // One coded line's controls; a table row reads as a row.
@Composable
private fun EntryLineRow(
    state: PoUiState,
    entry: PoEntryState,
    line: PoLine,
    index: Int,
    onEvent: (PoEvent) -> Unit,
) {
    val set = { next: PoLine ->
        onEvent(
            PoEvent.EditEntry(
                entry.copy(lines = entry.lines.mapIndexed { at, row -> if (at == index) next else row }),
            ),
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitTextField(
            value = line.description,
            onValueChange = { set(line.copy(description = it)) },
            placeholder = "Description…",
            modifier = Modifier.weight(1f),
        )
        ZillitTextField(
            value = line.quantity.trimmed(),
            onValueChange = { set(line.copy(quantity = it.toDoubleOrNull() ?: 0.0, amount = null)) },
            placeholder = "Qty",
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.width(QTY_FIELD),
        )
        ZillitTextField(
            value = line.unitPrice.trimmed(),
            onValueChange = { set(line.copy(unitPrice = it.toDoubleOrNull() ?: 0.0, amount = null)) },
            placeholder = "Unit",
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.width(PRICE_FIELD),
        )
        ZillitTextField(
            value = line.nominalCode.orEmpty(),
            onValueChange = { set(line.copy(nominalCode = it.takeIf { code -> code.isNotBlank() })) },
            placeholder = "Code",
            modifier = Modifier.width(CODE_FIELD),
        )
        ZillitSelect(
            value = line.taxType,
            options = listOf(null) + state.taxTypes.map { it.id },
            onSelect = { id ->
                val tax = state.taxTypes.firstOrNull { it.id == id }
                set(line.copy(taxType = id, vatRate = tax?.rate))
            },
            label = { id -> id?.let { key -> state.taxTypes.firstOrNull { it.id == key }?.name ?: key } ?: "Tax" },
            modifier = Modifier.width(TAX_FIELD),
        )
        ZillitText(
            text = Money.format(line.total, state.detail?.currency),
            style = ZillitTheme.typography.numeric,
            modifier = Modifier.width(AMOUNT_WIDTH),
        )
        ZillitButton(
            text = "Remove",
            onClick = { onEvent(PoEvent.RemoveEntryLine(index)) },
            variant = ButtonVariant.Danger,
            size = ButtonSize.Small,
        )
    }
}

/** Net, tax, ledger total, and whether it matches the order. */
@Composable
private fun EntryTotals(
    entry: PoEntryState,
    orderGross: Double,
    currency: String?,
    balanced: Boolean,
    reclaimable: Double,
) {
    val totals = entry.totals
    ZillitSectionCard(title = "Ledger Total", icon = ZillitIcons.Bank) {
        EntryTotalRow("Net Total", Money.format(totals.net, currency))
        EntryTotalRow("Tax", Money.format(totals.tax, currency))
        EntryTotalRow("Reclaimable", Money.format(reclaimable, currency))
        ZillitDivider()
        EntryTotalRow("Ledger Total", Money.format(entry.ledgerTotal, currency), strong = true)
        EntryTotalRow("PO Total", Money.format(orderGross, currency), strong = true)
        ZillitStatusPill(
            label = if (balanced) "Balanced" else "Awaiting Balance",
            tone = if (balanced) StatusTone.Done else StatusTone.Pending,
        )
    }
}

@Composable
private fun EntryTotalRow(label: String, value: String, strong: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ZillitText(
            text = label,
            style = if (strong) ZillitTheme.typography.titleSmall else ZillitTheme.typography.bodyMedium,
            color = if (strong) ZillitTheme.colors.textPrimary else ZillitTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = value,
            style = if (strong) ZillitTheme.typography.titleSmall else ZillitTheme.typography.numeric,
        )
    }
}

private const val CODING_WEIGHT = 1.6f
private const val PREVIEW_WEIGHT = 1f
private val DATE_FIELD = 220.dp
private val QTY_FIELD = 70.dp
private val PRICE_FIELD = 110.dp
private val CODE_FIELD = 110.dp
private val TAX_FIELD = 120.dp
