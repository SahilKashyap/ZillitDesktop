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
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
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
                text = str(S.desktop_po_loading_po),
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
                        text = str(
                            S.desktop_po_coded_lines_mismatch_detail,
                            Money.format(entry.ledgerTotal, order.currency),
                            Money.format(order.gross, order.currency),
                        ),
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
            text = str(S.desktop_po_back_to_queue),
            onClick = { onEvent(PoEvent.CloseEntry) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.ArrowLeft,
        )
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = order?.number?.ifBlank { str(S.purchase_order) } ?: str(S.purchase_order),
                style = ZillitTheme.typography.titleMedium,
            )
            ZillitText(
                text = listOfNotNull(
                    order?.let { state.vendorName(it) }?.takeIf { it.isNotBlank() },
                    str(S.desktop_po_assigned_to, order?.let { state.assigneeName(it) } ?: str(S.unassigned)),
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
        text = if (entry.previewOpen) str(S.desktop_po_collapse_preview) else str(S.desktop_po_show_preview),
        onClick = { onEvent(PoEvent.EditEntry(entry.copy(previewOpen = !entry.previewOpen))) },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        leadingIcon = ZillitIcons.Eye,
    )
    if (order != null) {
        ZillitButton(
            text = str(S.ah_view_pdf),
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
                    entry.sending -> str(S.dd_busy_sending)
                    order.emailed -> str(S.desktop_po_resend_to_vendor)
                    else -> str(S.desktop_po_send_to_vendor)
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
        text = str(S.save),
        onClick = { onEvent(PoEvent.SaveEntry) },
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Small,
        loading = entry.saving,
        enabled = !entry.saving && !entry.posting,
    )
    ZillitButton(
        text = str(S.ah_post_to_ledger),
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
        title = str(S.desktop_po_coded_lines),
        icon = ZillitIcons.Ledger,
        action = {
            ZillitButton(
                text = str(S.desktop_po_add_line),
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
                label = str(S.ah_lbl_eff_date),
                helperText = str(S.desktop_po_period_these_lines_post_into),
                modifier = Modifier.width(DATE_FIELD),
            )
            ZillitTextField(
                value = entry.nominalCode,
                onValueChange = { onEvent(PoEvent.EditEntry(entry.copy(nominalCode = it))) },
                label = str(S.ah_lbl_nominal_code),
                placeholder = str(S.desktop_po_search_or_enter_code),
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
        EntryHeaderCell(str(S.description), Modifier.weight(1f))
        EntryHeaderCell(str(S.ah_lbl_qty), Modifier.width(QTY_FIELD))
        EntryHeaderCell(str(S.dm_step2_unit), Modifier.width(PRICE_FIELD))
        EntryHeaderCell(str(S.code), Modifier.width(CODE_FIELD))
        EntryHeaderCell(str(S.ah_lbl_vat_tax), Modifier.width(TAX_FIELD))
        EntryHeaderCell(str(S.amount), Modifier.width(AMOUNT_WIDTH))
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
            placeholder = str(S.ah_description_hint),
            modifier = Modifier.weight(1f),
        )
        ZillitTextField(
            value = line.quantity.trimmed(),
            onValueChange = { set(line.copy(quantity = it.toDoubleOrNull() ?: 0.0, amount = null)) },
            placeholder = str(S.ah_lbl_qty),
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.width(QTY_FIELD),
        )
        ZillitTextField(
            value = line.unitPrice.trimmed(),
            onValueChange = { set(line.copy(unitPrice = it.toDoubleOrNull() ?: 0.0, amount = null)) },
            placeholder = str(S.dm_step2_unit),
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.width(PRICE_FIELD),
        )
        ZillitTextField(
            value = line.nominalCode.orEmpty(),
            onValueChange = { set(line.copy(nominalCode = it.takeIf { code -> code.isNotBlank() })) },
            placeholder = str(S.code),
            modifier = Modifier.width(CODE_FIELD),
        )
        ZillitSelect(
            value = line.taxType,
            options = listOf(null) + state.taxTypes.map { it.id },
            onSelect = { id ->
                val tax = state.taxTypes.firstOrNull { it.id == id }
                set(line.copy(taxType = id, vatRate = tax?.rate))
            },
            label = { id ->
                id?.let { key -> state.taxTypes.firstOrNull { it.id == key }?.name ?: key } ?: str(S.ah_lbl_vat_tax)
            },
            modifier = Modifier.width(TAX_FIELD),
        )
        ZillitText(
            text = Money.format(line.total, state.detail?.currency),
            style = ZillitTheme.typography.numeric,
            modifier = Modifier.width(AMOUNT_WIDTH),
        )
        ZillitButton(
            text = str(S.remove),
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
    ZillitSectionCard(title = str(S.ah_ledger_total_upper), icon = ZillitIcons.Bank) {
        EntryTotalRow(str(S.ah_lbl_net_total), Money.format(totals.net, currency))
        EntryTotalRow(str(S.ah_lbl_vat_tax), Money.format(totals.tax, currency))
        EntryTotalRow(str(S.desktop_po_reclaimable), Money.format(reclaimable, currency))
        ZillitDivider()
        EntryTotalRow(str(S.ah_ledger_total_upper), Money.format(entry.ledgerTotal, currency), strong = true)
        EntryTotalRow(str(S.desktop_po_total), Money.format(orderGross, currency), strong = true)
        ZillitStatusPill(
            label = if (balanced) str(S.desktop_card_balanced) else str(S.desktop_po_awaiting_balance),
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
