// Payment Runs: one composable per panel of the web's page.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.CurrencyRates
import com.zillit.desktop.feature.invoices.domain.DueLabel
import com.zillit.desktop.feature.invoices.domain.DueTone
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.OpenItemRow
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.PaymentRun
import com.zillit.desktop.feature.invoices.domain.PaymentRunStatus
import com.zillit.desktop.feature.invoices.domain.PaymentRuns
import com.zillit.desktop.feature.invoices.domain.PaymentTab
import com.zillit.desktop.feature.invoices.ui.AccountantPage
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.PaymentsEvent

/**
 * Payment Runs — the web's `PaymentsPage`, top to bottom as it draws it: the
 * "no authoriser" banner, the five tiles, the run authorisation bar, the tab
 * chips, then the tab's own panel. Everything above the chips shows on every
 * tab, Active Runs included.
 *
 * There is no search box, no Select All and no Clear on Open Items: the web
 * has only the vendor groups' ticks (`PaymentsPage.jsx:1517-1810`).
 */
@Composable
@Suppress("UNUSED_PARAMETER", "UnusedParameter") // The page has no search box to focus.
internal fun ColumnScope.PaymentsPage(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
    nowMs: Long,
    searchFocus: FocusRequester,
) {
    if (state.showNoRunAuthoriser) NoAuthoriserBanner()
    PaymentTiles(state)
    if (state.runAuth.isNotEmpty()) RunAuthBar(state)
    ZillitTabStrip(
        tabs = PaymentTab.entries.map { tab -> ZillitTab(tab.id, tab.label, count = state.paymentTabCount(tab)) },
        activeId = state.paymentTab.id,
        onSelect = { id ->
            PaymentTab.entries.firstOrNull { it.id == id }?.let { onEvent(InvoicesEvent.SelectPaymentTab(it)) }
        },
    )
    when (state.paymentTab) {
        PaymentTab.OpenItems -> OpenItemsPanel(state, nowMs, onEvent)
        PaymentTab.Wires -> WiresTab(state, nowMs, onEvent)
        PaymentTab.Cheques -> ChequesPanel(state, onEvent)
        PaymentTab.Runs -> RunsPanel(state, onEvent)
    }
}

// -- above the tabs ------------------------------------------------------------

/** The amber banner: nobody can authorise a run until Settings says who may (`:1528-1540`). */
@Composable
private fun NoAuthoriserBanner() {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.warningSoft)
            .border(1.dp, colors.warning.copy(alpha = BORDER_ALPHA), ZillitTheme.shapes.large)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(ZillitIcons.Warning, tint = colors.warning, size = ZillitTheme.spacing.lg)
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(
                text = str(S.desktop_inv_no_run_authoriser_title),
                style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Bold),
                color = colors.textPrimary,
            )
            ZillitText(
                text = str(S.desktop_inv_no_run_authoriser_body),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }
}

/**
 * The web's five tiles. The wires tile counts wire *and* faster payment,
 * because that is what the Wires tab itself holds.
 */
@Composable
private fun PaymentTiles(state: InvoicesUiState) {
    val groups = state.openItemGroups.size
    StatGrid(
        columns = TILE_COLUMNS,
        tiles = listOf(
            {
                Tile(str(S.desktop_open_items), state.invoices, StatusTone.Pending, state.rates) {
                    str(S.desktop_ce_cards_topups_detail, it)
                }
            },
            { Tile(str(S.desktop_bacs_queue), state.bacsInvoices, StatusTone.InTransit, state.rates) },
            { Tile(str(S.desktop_wires_pending), state.wireInvoices, StatusTone.Rejected, state.rates) },
            { Tile(str(S.desktop_cheques), state.chequeInvoices, StatusTone.Escalated, state.rates) },
            {
                ZillitStatTile(
                    label = str(S.ah_vendors),
                    value = groups.toString(),
                    sub = if (groups == 1) {
                        str(S.desktop_inv_one_vendor_group)
                    } else {
                        str(S.desktop_inv_n_vendor_groups, groups)
                    },
                    tone = StatusTone.Ready,
                    modifier = statTile,
                )
            },
        ),
    )
}

@Composable
private fun RowScope.Tile(
    label: String,
    rows: List<Invoice>,
    tone: StatusTone,
    rates: CurrencyRates,
    sub: (String) -> String = { it },
) {
    ZillitStatTile(
        label = label,
        value = rows.size.toString(),
        sub = sub(paymentSum(rates, rows)),
        tone = tone,
        modifier = statTile,
    )
}

/**
 * "Run Authorisation: L1 [names] → L2 [names] … All levels must authorise"
 * — who signs a run, tier by tier (`:1584-1627`).
 */
@Composable
private fun RunAuthBar(state: InvoicesUiState) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = str(S.desktop_inv_run_authorisation),
            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Bold),
            color = colors.textMuted,
            maxLines = 1,
        )
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.runAuth.sortedBy { it.tier }.forEachIndexed { index, level ->
                if (index > 0) {
                    ZillitText(text = "→", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
                }
                ZillitText(
                    text = str(S.desktop_inv_tier_short, level.tier),
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = colors.textMuted,
                )
                val names = level.userIds.map { state.pay.authLabels[it] ?: it }
                AuthChip(
                    text = if (names.isEmpty()) str(S.desktop_inv_no_users) else names.joinToString(", "),
                    empty = names.isEmpty(),
                )
            }
        }
        ZillitText(
            text = str(S.desktop_inv_all_levels_must_authorise),
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
            maxLines = 1,
        )
    }
}

@Composable
private fun AuthChip(text: String, empty: Boolean) {
    val colors = ZillitTheme.colors
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodySmall.let { if (empty) it.copy(fontStyle = FontStyle.Italic) else it },
        color = if (empty) colors.textMuted else colors.textPrimary,
        maxLines = 1,
        modifier = Modifier
            .clip(ZillitTheme.shapes.pill)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, ZillitTheme.shapes.pill)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
    )
}

// -- Open Items ------------------------------------------------------------------

/**
 * Open Items: the vendor-and-currency groups, each ticked whole or row by
 * row, and the header's one action for what is ticked (`:1630-1807`).
 */
@Composable
private fun ColumnScope.OpenItemsPanel(state: InvoicesUiState, nowMs: Long, onEvent: (InvoicesEvent) -> Unit) {
    TableCard(
        title = str(S.desktop_open_items),
        icon = ZillitIcons.Wallet,
        action = { OpenItemsActions(state, onEvent) },
    ) {
        PanelHint(str(S.desktop_inv_select_per_vendor))
        ZillitDataTable(
            rows = state.openItemRows,
            columns = groupedColumns(state, nowMs, onEvent),
            key = { it.key },
            // A group's line opens or shuts it; the web's invoice rows have no click.
            onRowClick = { row ->
                if (row is OpenItemRow.Header) onEvent(InvoicesEvent.ToggleGroupOpen(row.group.key))
            },
            emptyTitle = str(S.desktop_inv_no_posted_awaiting_payment),
            loading = state.loading && state.invoices.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * "{n} Selected", always, then — with anything ticked — one button: the
 * shared method's own ("Create BACs Run", "Mark Paid", a disabled "Print
 * Cheque", or "Process" for any other method), or "Process" for a mix, which
 * opens the sheet. Without run access the button is disabled and says why.
 */
@Composable
private fun OpenItemsActions(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    val ticked = state.selectedPaymentRows.size
    ZillitStatusPill(label = str(S.desktop_inv_n_selected_pill, ticked), tone = StatusTone.Progress)
    if (ticked == 0) return
    val code = state.selectedPayCode
    val canAct = state.viewer.canOperateRuns
    val cheque = code == PayMethod.Cheque.wire
    val working = when (code) {
        PayMethod.Bacs.wire -> state.pay.creatingRun
        in PaymentRuns.WIRE_CODES -> state.pay.bulkMarking
        else -> false
    }
    val label = when {
        code == null -> str(S.ah_process)
        code == PayMethod.Bacs.wire -> if (working) str(S.desktop_creating) else str(S.desktop_create_bacs_run)
        code in PaymentRuns.WIRE_CODES ->
            if (working) str(S.desktop_payroll_marking) else str(S.desktop_mark_paid_title)
        cheque -> str(S.desktop_inv_print_cheque)
        else -> str(S.ah_process)
    }
    val tooltip = when {
        cheque -> str(S.desktop_inv_cheque_printing_unavailable)
        !canAct -> str(S.desktop_inv_no_run_access_tooltip)
        else -> ""
    }
    ZillitTooltip(tooltip) {
        ZillitButton(
            text = label,
            onClick = { onEvent(InvoicesEvent.ProcessSelected(null)) },
            size = ButtonSize.Small,
            enabled = canAct && !cheque && !working,
            loading = working,
        )
    }
}

/**
 * A header line per vendor and currency — its tick, "{vendor} · {currency}"
 * and count, its total, and ▾ / ▸ — then that group's invoices.
 */
private fun groupedColumns(
    state: InvoicesUiState,
    nowMs: Long,
    onEvent: (InvoicesEvent) -> Unit,
): List<TableColumn<OpenItemRow>> = openItemColumns(state, nowMs, onEvent).mapIndexed { index, column ->
    TableColumn<OpenItemRow>(column.header, column.width, numeric = column.numeric) { row ->
        when (row) {
            is OpenItemRow.Item -> column.cell(row.invoice)
            is OpenItemRow.Header -> GroupCell(state, row, index, onEvent)
        }
    }
}

@Composable
private fun GroupCell(state: InvoicesUiState, row: OpenItemRow.Header, index: Int, onEvent: (InvoicesEvent) -> Unit) {
    val group = row.group
    val ticked = state.pay.openItemsSelected
    when (index) {
        TICK_COLUMN -> ZillitCheckbox(
            checked = group.ids.isNotEmpty() && ticked.containsAll(group.ids),
            onCheckedChange = { onEvent(InvoicesEvent.SelectGroup(group.ids)) },
        )
        REF_COLUMN -> ZillitText(
            text = "${group.vendorName} · ${group.currency}",
            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        DESCRIPTION_COLUMN -> {
            val picked = group.ids.count { it in ticked }
            val count = countMeta(group.invoices.size)
            val partial = picked > 0 && picked < group.ids.size
            CellText(if (partial) "$count (${str(S.dd_n_selected, picked)})" else count, muted = true)
        }
        AMOUNT_COLUMN -> MoneyText(group.total, group.currency, state.projectCurrency)
        LAST_COLUMN -> CellText(if (row.open) "▾" else "▸", muted = true)
        else -> Unit
    }
}

/** Ref, vendor, description, amount, PO, method (a plain chip) and days, as the web's rows. */
private fun openItemColumns(
    state: InvoicesUiState,
    nowMs: Long,
    onEvent: (InvoicesEvent) -> Unit,
): List<TableColumn<Invoice>> = listOf(
    TableColumn("", ColumnWidth.Fixed(TICK_WIDTH)) { invoice ->
        ZillitCheckbox(
            checked = invoice.id in state.pay.openItemsSelected,
            onCheckedChange = { onEvent(PaymentsEvent.ToggleOpenItem(invoice.id)) },
        )
    },
    TableColumn(str(S.desktop_ref), ColumnWidth.Weight(1f)) { CellText(it.displayNumber) },
    TableColumn(str(S.ah_lbl_vendor), ColumnWidth.Weight(WEIGHT_MEDIUM)) { CellText(supplierOf(state, it)) },
    TableColumn(str(S.description), ColumnWidth.Weight(WEIGHT_WIDEST)) { CellText(it.description, muted = true) },
    TableColumn(str(S.amount), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.grossAmount, it.currency, state.projectCurrency)
    },
    TableColumn(str(S.desktop_po), ColumnWidth.Fixed(PO_WIDTH)) {
        ZillitStatusPill(label = PaymentRuns.openItemPo(it) ?: str(S.desktop_no_po), tone = StatusTone.InTransit)
    },
    TableColumn(str(S.desktop_method), ColumnWidth.Fixed(PAY_WIDTH)) {
        ZillitStatusPill(label = payMethodName(it.payCode), tone = StatusTone.Neutral)
    },
    TableColumn(str(S.dm_ds_unit_days), ColumnWidth.Fixed(DUE_WIDTH)) {
        DueText(PaymentRuns.openItemDays(it.dueDateMs, nowMs))
    },
    TableColumn("", ColumnWidth.Fixed(CHEVRON_WIDTH)) { },
)

// -- Wires -----------------------------------------------------------------------

/**
 * The Wires tab: the unpaid wires and faster payments, "Recently Marked as
 * Paid" under them, and — while any are ticked — the bulk bar (`:1810-1975`).
 */
@Composable
private fun ColumnScope.WiresTab(state: InvoicesUiState, nowMs: Long, onEvent: (InvoicesEvent) -> Unit) {
    val wires = state.wireInvoices
    TableCard(
        title = str(S.desktop_inv_wires_panel_title),
        icon = ZillitIcons.Bank,
        action = { ZillitStatusPill(label = str(S.desktop_inv_n_pending, wires.size), tone = StatusTone.Rejected) },
    ) {
        PanelHint(str(S.desktop_inv_wires_manual))
        PanelList(
            rows = wires,
            empty = str(S.desktop_inv_no_wire_invoices),
            loading = state.loading,
            emptyIcon = ZillitIcons.Bank,
        ) { invoice -> WireRow(state, invoice, nowMs, onEvent) }
    }
    if (state.pay.recentlyPaid.isNotEmpty()) RecentlyPaidPanel(state, onEvent)
    if (state.pay.wiresSelected.isNotEmpty()) WiresBulkBar(state, onEvent)
}

@Composable
private fun WireRow(state: InvoicesUiState, invoice: Invoice, nowMs: Long, onEvent: (InvoicesEvent) -> Unit) {
    val ticked = invoice.id in state.pay.wiresSelected
    val marking = invoice.id in state.pay.markingPaid
    PanelRow(
        selected = ticked,
        selectedTone = StatusTone.Rejected,
        onClick = { onEvent(PaymentsEvent.ClickRow(invoice)) },
    ) {
        ZillitCheckbox(checked = ticked, onCheckedChange = { onEvent(PaymentsEvent.ToggleWire(invoice.id)) })
        PayeeLines(state, invoice, Modifier.weight(1f))
        ZillitText(
            text = moneyOf(state, invoice),
            style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
            color = ZillitTheme.colors.danger,
            maxLines = 1,
        )
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            ZillitStatusPill(label = str(S.desktop_unpaid), tone = StatusTone.Rejected)
            DueText(PaymentRuns.wireDue(invoice.dueDateMs, nowMs))
        }
        RunActionTooltip(state) {
            ZillitButton(
                text = if (marking) str(S.desktop_payroll_marking) else str(S.desktop_mark_paid_title),
                onClick = { onEvent(InvoicesEvent.MarkPaidOne(invoice)) },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
                enabled = state.viewer.canOperateRuns && !marking,
                loading = marking,
            )
        }
    }
}

/** "Recently Marked as Paid" — each with its confirmations (`:1889-1939`). */
@Composable
private fun ColumnScope.RecentlyPaidPanel(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    val paid = state.pay.recentlyPaid
    TableCard(
        title = str(S.desktop_inv_recently_marked_paid),
        icon = ZillitIcons.Check,
        action = { ZillitStatusPill(label = paid.size.toString(), tone = StatusTone.Ready) },
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(paid, key = { it.id }) { invoice ->
                PanelRow(selected = true, selectedTone = StatusTone.Ready, onClick = null) {
                    ZillitIcon(ZillitIcons.Check, tint = ZillitTheme.colors.success, size = ZillitTheme.spacing.lg)
                    PayeeLines(state, invoice, Modifier.weight(1f))
                    ZillitText(
                        text = moneyOf(state, invoice),
                        style = ZillitTheme.typography.numeric,
                        maxLines = 1,
                    )
                    ZillitStatusPill(label = str(S.desktop_paid), tone = StatusTone.Ready)
                    val filed = invoice.wireAttachments.size
                    ZillitButton(
                        text = if (filed > 0) {
                            str(S.ah_view_attachments_count, filed)
                        } else {
                            str(S.desktop_inv_attach_confirmation)
                        },
                        onClick = { onEvent(PaymentsEvent.OpenWireAttachments(invoice)) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                    )
                }
            }
        }
    }
}

/** "{n} invoice(s) selected" with Mark Paid — every ticked wire, wire or faster — and Clear. */
@Composable
private fun WiresBulkBar(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val count = state.pay.wiresSelected.size
    val busy = state.pay.bulkMarking
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.accent.copy(alpha = BORDER_ALPHA), ZillitTheme.shapes.large)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = if (count == 1) {
                str(S.desktop_inv_one_invoice_selected, count)
            } else {
                str(S.desktop_inv_n_invoices_selected_many, count)
            },
            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f),
        )
        RunActionTooltip(state) {
            ZillitButton(
                text = if (busy) str(S.desktop_payroll_marking) else str(S.desktop_mark_paid_title),
                onClick = { onEvent(PaymentsEvent.MarkWiresPaid) },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
                enabled = state.viewer.canOperateRuns && !busy,
                loading = busy,
            )
        }
        ZillitButton(
            text = str(S.ah_clear),
            onClick = { onEvent(PaymentsEvent.ClearWires) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Close,
        )
    }
}

// -- Cheques ---------------------------------------------------------------------

/** "Cheques — Print on Stock": each row printed from its detail, via "Ready to Print" (`:1978-2048`). */
@Composable
private fun ColumnScope.ChequesPanel(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    val cheques = state.chequeInvoices
    TableCard(
        title = str(S.desktop_inv_cheques_panel_title),
        icon = ZillitIcons.Print,
        action = { ZillitStatusPill(label = str(S.desktop_inv_n_to_print, cheques.size), tone = StatusTone.Pending) },
    ) {
        PanelHint(str(S.desktop_inv_cheques_hint))
        PanelList(
            rows = cheques,
            empty = str(S.desktop_inv_no_cheque_invoices),
            loading = state.loading,
            emptyIcon = ZillitIcons.Print,
        ) { invoice ->
            val ticked = invoice.id in state.pay.chequesSelected
            PanelRow(
                selected = ticked,
                selectedTone = StatusTone.Pending,
                onClick = { onEvent(PaymentsEvent.ClickRow(invoice)) },
            ) {
                ZillitCheckbox(checked = ticked, onCheckedChange = { onEvent(PaymentsEvent.ToggleCheque(invoice.id)) })
                PayeeLines(state, invoice, Modifier.weight(1f))
                ZillitText(
                    text = moneyOf(state, invoice),
                    style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                ZillitButton(
                    text = str(S.desktop_ready_to_print),
                    onClick = { onEvent(InvoicesEvent.Open(invoice)) },
                    size = ButtonSize.Small,
                )
            }
        }
    }
}

// -- Active Runs -----------------------------------------------------------------

/** "Active Payment Runs": each run's number, name, method, total, count and status (`:2050-2116`). */
@Composable
private fun ColumnScope.RunsPanel(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    val runs = state.paymentRuns
    TableCard(title = str(S.desktop_inv_active_payment_runs), icon = ZillitIcons.Wallet) {
        if (state.pay.runsLoading) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.xl),
                horizontalArrangement = Arrangement.Center,
            ) { MutedLine(str(S.desktop_inv_loading_runs)) }
            return@TableCard
        }
        ZillitDataTable(
            rows = runs,
            columns = runColumns(state),
            key = { it.id },
            // The web opens the run; its footer carries the decisions.
            onRowClick = { onEvent(InvoicesEvent.OpenRun(it)) },
            emptyTitle = str(S.desktop_inv_no_active_payment_runs),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun runColumns(state: InvoicesUiState): List<TableColumn<PaymentRun>> = listOf(
    TableColumn(str(S.ah_run_card_run_label), ColumnWidth.Weight(1f)) { CellText(it.number) },
    TableColumn(str(S.description), ColumnWidth.Weight(WEIGHT_WIDEST)) { CellText(it.name) },
    TableColumn(str(S.desktop_method), ColumnWidth.Fixed(PAY_WIDTH)) {
        ZillitStatusPill(label = it.payMethod.label, tone = runMethodTone(it.payMethod))
    },
    TableColumn(str(S.ah_total_label), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.total, it.currency, state.projectCurrency)
    },
    TableColumn(str(S.desktop_inv_col_inv), ColumnWidth.Fixed(TICK_COUNT_WIDTH), numeric = true) {
        CellText(it.invoiceCount.toString(), muted = true)
    },
    TableColumn(str(S.status), ColumnWidth.Weight(WEIGHT_NARROW)) {
        ZillitStatusPill(label = it.statusLabel, tone = it.status.tone())
    },
)

/** The web's `methodColor`: BACs blue, wire and faster red, cheque amber, anything else grey. */
private fun runMethodTone(method: PayMethod): StatusTone = when (method) {
    PayMethod.Bacs -> StatusTone.InTransit
    PayMethod.Wire, PayMethod.Faster -> StatusTone.Rejected
    PayMethod.Cheque -> StatusTone.Pending
    else -> StatusTone.Neutral
}

/** The web's `runStatusColor`: amber pending, green approved / paid / sent, blue waiting, red rejected. */
private fun PaymentRunStatus.tone(): StatusTone = when (this) {
    PaymentRunStatus.Pending -> StatusTone.Pending
    PaymentRunStatus.Approved, PaymentRunStatus.Paid, PaymentRunStatus.Sent -> StatusTone.Ready
    PaymentRunStatus.Waiting -> StatusTone.Progress
    PaymentRunStatus.Rejected -> StatusTone.Rejected
    PaymentRunStatus.Draft, PaymentRunStatus.Other -> StatusTone.Neutral
}

// -- shared pieces -----------------------------------------------------------------

/** The muted line under a panel's title. */
@Composable
private fun PanelHint(text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm)) {
        MutedLine(text)
    }
}

/** A panel's rows as the web lists them (not a table), or its empty line. */
@Composable
private fun ColumnScope.PanelList(
    rows: List<Invoice>,
    empty: String,
    loading: Boolean,
    emptyIcon: ImageVector,
    row: @Composable (Invoice) -> Unit,
) {
    when {
        rows.isEmpty() && loading -> LoadingRow()
        rows.isEmpty() ->
            ZillitEmptyState(title = empty, icon = emptyIcon, modifier = Modifier.weight(1f).fillMaxWidth())
        else -> LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(rows, key = { it.id }) { invoice -> row(invoice) }
        }
    }
}

@Composable
private fun PanelRow(
    selected: Boolean,
    selectedTone: StatusTone,
    onClick: (() -> Unit)?,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = ZillitTheme.colors
    val tint = when (selectedTone) {
        StatusTone.Rejected -> colors.dangerSoft
        StatusTone.Ready -> colors.successSoft
        else -> colors.warningSoft
    }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (selected) tint else colors.surface)
                .let { if (onClick != null) it.clickable(onClick = onClick) else it }
                .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        ZillitDivider()
    }
}

/** The vendor, then "ref [unread] · description" — the web's two lines on a wire or cheque. */
@Composable
private fun PayeeLines(state: InvoicesUiState, invoice: Invoice, modifier: Modifier) {
    val colors = ZillitTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitText(
            text = state.payeeName(invoice),
            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Bold),
            color = colors.textPrimary,
            maxLines = 1,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(text = invoice.displayNumber, style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
            ZillitBadge(count = state.rowUnread(PAYMENTS_KEY, invoice.id))
            ZillitText(
                text = "· ${invoice.description.ifBlank { "—" }}",
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

/** A due line in its colour: pink overdue, amber due today, muted otherwise. */
@Composable
private fun DueText(due: DueLabel) {
    val colors = ZillitTheme.colors
    ZillitText(
        text = due.text,
        style = ZillitTheme.typography.bodySmall,
        color = when (due.tone) {
            DueTone.Overdue -> colors.danger
            DueTone.Today -> colors.warning
            DueTone.Plain -> colors.textMuted
        },
        maxLines = 1,
    )
}

/** A run action's disabled reason, as the web's `title` — "You don't have access…". */
@Composable
private fun RunActionTooltip(state: InvoicesUiState, content: @Composable () -> Unit) {
    ZillitTooltip(if (state.viewer.canOperateRuns) "" else str(S.desktop_inv_no_run_access_tooltip), content)
}

/** A row's supplier on Open Items — `vendorMap[vendor_id] || "Unknown"`. */
private fun supplierOf(state: InvoicesUiState, invoice: Invoice): String =
    state.vendors[invoice.vendorId]?.name?.ifBlank { null } ?: str(S.desktop_unknown)

private fun moneyOf(state: InvoicesUiState, invoice: Invoice): String =
    InvoiceFormat.money(invoice.grossAmount, invoice.currency.ifBlank { state.projectCurrency })

/** A method's name, `METHOD_LABELS` first, an unknown code humanised. */
private fun payMethodName(code: String): String = PaymentRuns.methodLabel(code) { it.localised() }

/** A mixed-currency total is converted and says so. */
private fun paymentSum(rates: CurrencyRates, rows: List<Invoice>): String {
    val total = rates.total(rows.map { it.grossAmount to it.currency })
    return total.caveat?.let { "${total.text} · $it" } ?: total.text
}

/** Payment Runs' `level_1` — every per-row unread chip on this page is filed under it. */
private val PAYMENTS_KEY: String = AccountantPage.Payments.badgeKey.orEmpty()

private const val TILE_COLUMNS = 5
private const val BORDER_ALPHA = 0.3f

/** The flat table's columns, as the grouped header lines up against them. */
private const val TICK_COLUMN = 0
private const val REF_COLUMN = 1
private const val DESCRIPTION_COLUMN = 3
private const val AMOUNT_COLUMN = 4
private const val LAST_COLUMN = 8
private val CHEVRON_WIDTH = 40.dp
