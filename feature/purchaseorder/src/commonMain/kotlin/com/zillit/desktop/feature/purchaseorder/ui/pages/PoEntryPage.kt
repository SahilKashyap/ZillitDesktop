package com.zillit.desktop.feature.purchaseorder.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.ZillitVerticalDivider
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.purchaseorder.domain.PoAccess
import com.zillit.desktop.feature.purchaseorder.domain.PoLine
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.isoDayToUtcMidnight
import com.zillit.desktop.feature.purchaseorder.ui.PoConfirmAction
import com.zillit.desktop.feature.purchaseorder.ui.PoEntryLedger
import com.zillit.desktop.feature.purchaseorder.ui.PoEntryState
import com.zillit.desktop.feature.purchaseorder.ui.PoEvent
import com.zillit.desktop.feature.purchaseorder.ui.PoProcessActions
import com.zillit.desktop.feature.purchaseorder.ui.PoPrompt
import com.zillit.desktop.feature.purchaseorder.ui.PoUiState
import com.zillit.desktop.feature.purchaseorder.ui.ledger
import com.zillit.desktop.feature.purchaseorder.ui.mayQuery

/**
 * PO Entry — the accountant's processing page, the web's `POEntry`.
 *
 * The point of the surface is that an order has two documents: what the
 * department asked for, and what the books record. They must come to the same
 * number, and this page is where the second is written. The order itself is on
 * the right, read-only, so the coding can be checked against it without
 * leaving.
 */
@Suppress("LongMethod") // The page top to bottom: banners, header, coding, tax, totals, preview.
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
        val ledger = entry.ledger(state)
        val balanced = ledger.balances(order)
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            ZillitScrollColumn(
                modifier = Modifier.weight(CODING_WEIGHT).fillMaxHeight(),
                contentPadding = PaddingValues(ZillitTheme.spacing.xl),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            ) {
                if (state.isLocked(order)) {
                    ZillitNotice(
                        text = str(S.desktop_po_period_locked_banner, state.periodLock.lockedThrough),
                        tone = StatusTone.Pending,
                        icon = ZillitIcons.Lock,
                    )
                }
                if (!balanced) {
                    ZillitNotice(
                        text = str(
                            S.desktop_po_coded_lines_mismatch_detail,
                            Money.format(ledger.gross, order.currency),
                            Money.format(order.gross, order.currency),
                        ),
                        tone = StatusTone.Pending,
                        icon = ZillitIcons.Warning,
                    )
                }
                HeaderCard(state, entry, onEvent)
                CodingCard(state, entry, onEvent)
                TaxLineCard(entry, ledger, order.currency, onEvent)
                EntryTotals(
                    ledger = ledger,
                    orderGross = order.gross,
                    currency = order.currency,
                    balanced = balanced,
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
        order?.let { ZillitStatusPill(label = state.statusLabel(it), tone = it.status.tone()) }
        EntryActions(state, entry, order, onEvent)
    }
}

/**
 * The processing page's actions, in the web's own order — and each drawn only
 * when it can succeed. On an order in the locked period nothing that writes is
 * offered; Post appears only where the order can be posted at all, and reads
 * "Awaiting balance" until the coded lines carry money.
 */
@Suppress("LongMethod") // One button per web action, each with its own gate.
@Composable
private fun EntryActions(
    state: PoUiState,
    entry: PoEntryState,
    order: PurchaseOrder?,
    onEvent: (PoEvent) -> Unit,
) {
    val locked = order != null && state.isLocked(order)
    ZillitButton(
        text = if (entry.previewOpen) str(S.desktop_po_collapse_preview) else str(S.desktop_po_show_preview),
        onClick = { onEvent(PoEvent.EditEntry(entry.copy(previewOpen = !entry.previewOpen))) },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        leadingIcon = ZillitIcons.Eye,
    )
    if (order == null) return
    val ledger = entry.ledger(state)
    // Delete on an order accounts entered and nobody has posted — the web's
    // processing-page affordance for whoever may process it.
    if (!locked && PoAccess.canDelete(order, state.viewer, onProcessingPage = true)) {
        EntryDeleteButton(state, order, onEvent)
    }
    // Query when the coding does not balance, which is when there is a
    // question to ask — the web's `!balanced` button.
    if (!ledger.balances(order) && state.mayQuery(order)) {
        ZillitButton(
            text = str(S.ah_query_label),
            onClick = { onEvent(PoEvent.OpenQuery(order.id)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Chat,
        )
    }
    ZillitButton(
        text = str(S.ah_view_pdf),
        onClick = { onEvent(PoEvent.ViewPdf(order.id)) },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        leadingIcon = ZillitIcons.Download,
        enabled = !state.busy,
    )
    // Resend is allowed here and nowhere else: this is the surface where the
    // order is corrected, so the vendor has to be able to get the amended copy.
    // The send saves the page first.
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
            enabled = !entry.sending && !entry.saving,
        )
    }
    if (!locked) EntryWriteActions(state, entry, order, onEvent)
}

@Composable
private fun EntryDeleteButton(state: PoUiState, order: PurchaseOrder, onEvent: (PoEvent) -> Unit) {
    ZillitButton(
        text = str(S.desktop_po_delete_po),
        onClick = {
            onEvent(
                PoEvent.Ask(
                    PoPrompt.Confirm(
                        action = PoConfirmAction.Delete,
                        targetId = order.id,
                        title = str(S.desktop_po_delete_this_order),
                        message = str(
                            S.desktop_po_order_will_be_removed,
                            order.number.ifBlank { str(S.desktop_po_this_order_capital) },
                        ),
                        destructive = true,
                    ),
                ),
            )
        },
        variant = ButtonVariant.Danger,
        size = ButtonSize.Small,
        enabled = !state.busy,
    )
}

/** Save, and Post where the order can be posted at all — never on a locked order. */
@Composable
private fun EntryWriteActions(
    state: PoUiState,
    entry: PoEntryState,
    order: PurchaseOrder,
    onEvent: (PoEvent) -> Unit,
) {
    val ledger = entry.ledger(state)
    ZillitButton(
        text = str(S.save),
        onClick = { onEvent(PoEvent.SaveEntry) },
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Small,
        loading = entry.saving,
        enabled = !entry.saving && !entry.posting,
    )
    if (PoProcessActions.canPostStatus(order.status)) {
        val canPost = ledger.net > 0
        ZillitButton(
            text = if (canPost) str(S.ah_post_to_ledger) else str(S.desktop_po_awaiting_balance),
            onClick = { onEvent(PoEvent.PostEntry) },
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Ledger,
            loading = entry.posting,
            enabled = canPost && !entry.saving && !entry.posting,
        )
    }
}

/**
 * The header the web's process surface lets the accountant correct — vendor,
 * company, department, currency, delivery date — plus the effective date and
 * the header nominal. Saved and posted with the lines.
 */
@Suppress("LongMethod") // Seven header fields in two rows.
@Composable
private fun HeaderCard(state: PoUiState, entry: PoEntryState, onEvent: (PoEvent) -> Unit) {
    val set = { next: PoEntryState -> onEvent(PoEvent.EditEntry(next)) }
    ZillitSectionCard(title = str(S.desktop_header), icon = ZillitIcons.File) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            LabelledSelect(str(S.ah_lbl_vendor), Modifier.weight(1f)) {
                ZillitSelect(
                    value = entry.vendorId,
                    options = listOf(null) + state.vendors.map { it.id },
                    onSelect = { set(entry.copy(vendorId = it)) },
                    label = { id -> id?.let { key -> state.vendors.firstOrNull { it.id == key }?.name } ?: "—" },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            LabelledSelect(str(S.company), Modifier.weight(1f)) {
                ZillitSelect(
                    value = entry.companyId,
                    options = listOf(null) + state.companies.map { it.id },
                    onSelect = { set(entry.copy(companyId = it)) },
                    label = { id -> id?.let { key -> state.companies.firstOrNull { it.id == key }?.name } ?: "—" },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            LabelledSelect(str(S.department), Modifier.weight(1f)) {
                ZillitSelect(
                    value = entry.departmentId,
                    options = listOf(null) + state.departments.map { it.id },
                    onSelect = { set(entry.copy(departmentId = it)) },
                    label = { id -> id?.let { state.departmentName(it) }?.ifBlank { null } ?: "—" },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            LabelledSelect(str(S.ah_lbl_currency), Modifier.width(CURRENCY_FIELD)) {
                val currencies = (listOfNotNull(entry.currency) + state.currencies).distinct()
                ZillitSelect(
                    value = entry.currency,
                    options = currencies.ifEmpty { listOf(null) },
                    onSelect = { set(entry.copy(currency = it)) },
                    label = { it ?: "—" },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ZillitDateField(
                value = EpochDate.isoDate(entry.effectiveDate),
                onValueChange = { iso -> set(entry.copy(effectiveDate = iso.isoDayToUtcMidnight())) },
                label = str(S.ah_lbl_eff_date),
                helperText = str(S.desktop_po_period_these_lines_post_into),
                modifier = Modifier.width(DATE_FIELD),
            )
            ZillitDateField(
                value = EpochDate.isoDate(entry.deliveryDate),
                onValueChange = { iso -> set(entry.copy(deliveryDate = iso.isoDayToUtcMidnight())) },
                label = str(S.delivery_date),
                modifier = Modifier.width(DATE_FIELD),
            )
            ZillitTextField(
                value = entry.nominalCode,
                onValueChange = { set(entry.copy(nominalCode = it)) },
                label = str(S.ah_lbl_nominal_code),
                placeholder = str(S.desktop_po_search_or_enter_code),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LabelledSelect(label: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitText(text = label, style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted)
        content()
    }
}

/** The coded ledger lines. */
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
        EntryHeaderCell("", Modifier.width(LINE_ACTIONS))
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
                entry.copy(
                    lines = entry.lines.mapIndexed { at, row -> if (at == index) next else row },
                    // An edited row is re-judged on the next Post.
                    missingCodes = entry.missingCodes - (index + 1),
                ),
            ),
        )
    }
    // The last Post named this row for want of a nominal.
    val flagged = (index + 1) in entry.missingCodes
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitTextField(
            value = line.description,
            onValueChange = { set(line.copy(description = it)) },
            placeholder = if (line.isSplitChild) str(S.desktop_po_split_child) else str(S.ah_description_hint),
            leadingIcon = if (line.isSplitChild) ZillitIcons.ArrowRight else null,
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
            errorText = if (flagged) str(S.desktop_po_nominal_required) else null,
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
        LineActions(line, index, onEvent)
    }
}

/**
 * Split and Remove. The two splits are exclusive, as the web's are: a rental
 * line with a divisible window splits by period only, anything else evenly.
 */
@Composable
private fun LineActions(line: PoLine, index: Int, onEvent: (PoEvent) -> Unit) {
    Row(
        modifier = Modifier.width(LINE_ACTIONS),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        if (!line.isSplitChild) {
            val byPeriod = line.isDivisibleRental
            ZillitButton(
                text = if (byPeriod) str(S.desktop_po_split_by_period) else str(S.desktop_po_split_line),
                onClick = {
                    onEvent(if (byPeriod) PoEvent.SplitEntryLineByPeriod(index) else PoEvent.SplitEntryLine(index))
                },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
        ZillitButton(
            text = str(S.remove),
            onClick = { onEvent(PoEvent.RemoveEntryLine(index)) },
            variant = ButtonVariant.Danger,
            size = ButtonSize.Small,
        )
    }
}

/**
 * The consolidated tax line — the reclaimable tax, posted to its own nominal.
 *
 * The amount is derived until somebody types one; a typed amount sticks (a 0
 * included, so a removed tax stays removed) until Reset brings back the
 * derived figure — the web's override and its ↻.
 */
@Composable
private fun TaxLineCard(
    entry: PoEntryState,
    ledger: PoEntryLedger,
    currency: String?,
    onEvent: (PoEvent) -> Unit,
) {
    val set = { next: PoEntryState -> onEvent(PoEvent.EditEntry(next)) }
    val flagged = ledger.sendsTaxLine && (entry.lines.size + 1) in entry.missingCodes
    ZillitSectionCard(title = str(S.desktop_po_reclaimable_tax), icon = ZillitIcons.Bank) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitTextField(
                value = entry.taxCode,
                onValueChange = { set(entry.copy(taxCode = it)) },
                label = str(S.ah_lbl_nominal_code),
                placeholder = str(S.code),
                errorText = if (flagged) str(S.desktop_po_nominal_required) else null,
                modifier = Modifier.width(DATE_FIELD),
            )
            ZillitTextField(
                value = ledger.taxAmount.trimmed(),
                onValueChange = { typed -> set(entry.copy(taxOverride = typed.toDoubleOrNull() ?: 0.0)) },
                label = str(S.amount),
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.width(PRICE_FIELD),
            )
            ZillitText(
                text = Money.format(ledger.reclaimable, currency),
                style = ZillitTheme.typography.numeric,
                color = ZillitTheme.colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            if (entry.taxOverride != null) {
                ZillitButton(
                    text = str(S.reset),
                    onClick = { set(entry.copy(taxOverride = null)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
        }
    }
}

/** Net, tax, ledger total, and whether it matches the order. */
@Composable
private fun EntryTotals(
    ledger: PoEntryLedger,
    orderGross: Double,
    currency: String?,
    balanced: Boolean,
) {
    ZillitSectionCard(title = str(S.ah_ledger_total_upper), icon = ZillitIcons.Bank) {
        EntryTotalRow(str(S.ah_lbl_net_total), Money.format(ledger.net, currency))
        EntryTotalRow(str(S.ah_lbl_vat_tax), Money.format(ledger.tax, currency))
        EntryTotalRow(str(S.desktop_po_reclaimable), Money.format(ledger.taxAmount, currency))
        ZillitDivider()
        EntryTotalRow(str(S.ah_ledger_total_upper), Money.format(ledger.gross, currency), strong = true)
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
private val CURRENCY_FIELD = 120.dp
private val QTY_FIELD = 70.dp
private val PRICE_FIELD = 110.dp
private val CODE_FIELD = 110.dp
private val TAX_FIELD = 120.dp
private val LINE_ACTIONS = 200.dp
