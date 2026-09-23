package com.zillit.desktop.feature.invoices.ui.pages

import com.zillit.desktop.feature.invoices.domain.CurrencyRates
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import androidx.compose.foundation.layout.RowScope
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.OpenItemRow
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.InvoiceRules
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.PaymentRun
import com.zillit.desktop.feature.invoices.domain.PaymentRunStatus
import com.zillit.desktop.feature.invoices.domain.PaymentTab
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Payment Runs — the web's `PaymentsPage`.
 *
 * Four surfaces over the same idea: everything approved and waiting (open
 * items), the two methods that need their own handling (wires and cheques),
 * and the batches already built. A run is made from whatever is ticked.
 */
@Composable
internal fun ColumnScope.PaymentsPage(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
    nowMs: Long,
    searchFocus: FocusRequester,
) {
    ZillitTabStrip(
        tabs = PaymentTab.entries.map { tab ->
            ZillitTab(tab.id, tab.label, count = state.paymentTabCount(tab))
        },
        activeId = state.paymentTab.id,
        onSelect = { id ->
            PaymentTab.entries.firstOrNull { it.id == id }?.let { onEvent(InvoicesEvent.SelectPaymentTab(it)) }
        },
    )
    if (state.paymentTab == PaymentTab.Runs) {
        RunsTab(state, onEvent)
        return
    }
    if (state.showNoRunAuthoriser) {
        // The web raises the same banner: without an authoriser nobody can
        // action a run, so say it here rather than failing at the button.
        MutedLine(str(S.desktop_inv_nobody_has_run_access))
    }
    if (state.paymentTab == PaymentTab.OpenItems) PaymentTiles(state)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = state.search,
            onValueChange = { onEvent(InvoicesEvent.Search(it)) },
            placeholder = str(S.desktop_inv_search_ref_vendor_description),
            modifier = Modifier.width(SEARCH_WIDTH).focusRequester(searchFocus),
        )
        ProcessActions(state, onEvent)
    }
    OpenItemsTable(state, nowMs, onEvent)
}

/** The open items — grouped by vendor on Open Items, flat on Wires and Cheques. */
@Composable
private fun ColumnScope.OpenItemsTable(state: InvoicesUiState, nowMs: Long, onEvent: (InvoicesEvent) -> Unit) {
    val rows = state.paymentRows
    TableCard(
        title = str(S.desktop_open_items),
        icon = ZillitIcons.Wallet,
        meta = countMeta(rows.size),
    ) {
        val hint = when (state.paymentTab) {
            PaymentTab.Wires -> str(S.desktop_inv_wires_manual)
            PaymentTab.OpenItems -> str(S.desktop_inv_select_per_vendor)
            else -> null
        }
        hint?.let {
            Box(Modifier.padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs)) {
                MutedLine(it)
            }
        }
        if (state.paymentTab == PaymentTab.OpenItems) {
            // The web groups Open Items by vendor and currency — one run each.
            ZillitDataTable(
                rows = state.openItemRows,
                columns = groupedColumns(state, nowMs, onEvent),
                key = { it.key },
                onRowClick = { row ->
                    when (row) {
                        is OpenItemRow.Header -> onEvent(InvoicesEvent.ToggleGroupOpen(row.group.key))
                        is OpenItemRow.Item -> onEvent(InvoicesEvent.Open(row.invoice))
                    }
                },
                emptyTitle = str(S.desktop_inv_nothing_waiting_to_be_paid),
                emptyMessage = str(S.desktop_inv_open_items_empty_message),
                loading = state.loading && rows.isEmpty(),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            ZillitDataTable(
                rows = rows,
                columns = openItemColumns(state, nowMs, onEvent),
                key = { it.id },
                onRowClick = { onEvent(InvoicesEvent.Open(it)) },
                emptyTitle = str(S.desktop_inv_nothing_waiting_to_be_paid),
                emptyMessage = str(S.desktop_inv_open_items_empty_message),
                loading = state.loading && rows.isEmpty(),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The grouped table: a header line per vendor and currency — its tick, its
 * name and count, its total, and ▾ / ▸ — then that group's invoices, cell for
 * cell as the flat tables draw them.
 */
private fun groupedColumns(
    state: InvoicesUiState,
    nowMs: Long,
    onEvent: (InvoicesEvent) -> Unit,
): List<TableColumn<OpenItemRow>> {
    val flat = baseColumns(state, nowMs, onEvent)
    return flat.mapIndexed { index, column ->
        TableColumn<OpenItemRow>(column.header, column.width, numeric = column.numeric) { row ->
            when (row) {
                is OpenItemRow.Item -> column.cell(row.invoice)
                is OpenItemRow.Header -> GroupCell(state, row, index, onEvent)
            }
        }
    }
}

@Composable
private fun GroupCell(state: InvoicesUiState, row: OpenItemRow.Header, index: Int, onEvent: (InvoicesEvent) -> Unit) {
    val group = row.group
    when (index) {
        TICK_COLUMN -> ZillitCheckbox(
            checked = group.ids.isNotEmpty() && state.selected.containsAll(group.ids),
            onCheckedChange = { onEvent(InvoicesEvent.SelectGroup(group.ids)) },
        )
        REF_COLUMN -> ZillitText(
            text = group.vendorName,
            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        DESCRIPTION_COLUMN -> {
            val picked = group.ids.count { it in state.selected }
            val count = countMeta(group.invoices.size)
            val partial = picked > 0 && picked < group.ids.size
            CellText(if (partial) "$count (${str(S.dd_n_selected, picked)})" else count, muted = true)
        }
        AMOUNT_COLUMN -> MoneyText(group.total, group.currency, state.projectCurrency)
        LAST_COLUMN -> CellText(if (row.open) "▾" else "▸", muted = true)
        else -> Unit
    }
}

private fun openItemColumns(
    state: InvoicesUiState,
    nowMs: Long,
    onEvent: (InvoicesEvent) -> Unit,
): List<TableColumn<Invoice>> = baseColumns(state, nowMs, onEvent) + listOfNotNull(
    // The Wires tab pays one at a time too — the web's per-row Mark Paid.
    TableColumn<Invoice>("", ColumnWidth.Fixed(MARK_PAID_WIDTH)) { invoice ->
        ZillitButton(
            text = str(S.desktop_mark_paid_title),
            onClick = { onEvent(InvoicesEvent.MarkPaidOne(invoice)) },
            variant = ButtonVariant.Danger,
            size = ButtonSize.Small,
            enabled = state.viewer.canOperateRuns && !state.busy,
        )
    }.takeIf { state.paymentTab == PaymentTab.Wires },
)

private fun baseColumns(
    state: InvoicesUiState,
    nowMs: Long,
    onEvent: (InvoicesEvent) -> Unit,
): List<TableColumn<Invoice>> = listOf(
    TableColumn("", ColumnWidth.Fixed(TICK_WIDTH)) { invoice ->
        ZillitCheckbox(
            checked = invoice.id in state.selected,
            onCheckedChange = { onEvent(InvoicesEvent.ToggleSelect(invoice.id)) },
        )
    },
    TableColumn(str(S.desktop_ref), ColumnWidth.Weight(1f)) { CellText(it.displayNumber) },
    TableColumn(str(S.ah_lbl_vendor), ColumnWidth.Weight(WEIGHT_MEDIUM)) { CellText(state.vendorName(it)) },
    TableColumn(str(S.description), ColumnWidth.Weight(WEIGHT_WIDEST)) {
        CellText(it.description.ifBlank { "—" }, muted = it.description.isBlank())
    },
    TableColumn(str(S.amount), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.grossAmount, it.currency, state.projectCurrency)
    },
    poColumn(),
    TableColumn(str(S.desktop_method), ColumnWidth.Fixed(PAY_WIDTH)) {
        ZillitStatusPill(label = it.payMethod.label, tone = it.payMethod.tone())
    },
    TableColumn(str(S.dm_ds_unit_days), ColumnWidth.Fixed(DUE_WIDTH)) { invoice ->
        val overdue = InvoiceRules.daysOverdue(invoice, nowMs)
        if (overdue != null) {
            ZillitText(
                text = str(S.desktop_inv_n_days_overdue, overdue),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.danger,
                maxLines = 1,
            )
        } else {
            CellText(InvoiceFormat.date(invoice.dueDateMs), muted = true)
        }
    },
)

/**
 * What the queue holds, at a glance — the web's five tiles.
 *
 * The wires tile counts wire *and* faster payment, because that is what the
 * Wires tab itself holds; counting only `wire` made the tile disagree with
 * the tab it summarises.
 */
@Composable
private fun PaymentTiles(state: InvoicesUiState) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Tile(str(S.desktop_open_items), state.invoices, StatusTone.Pending, state.rates)
        Tile(str(S.desktop_bacs_queue), state.bacsInvoices, StatusTone.InTransit, state.rates)
        Tile(str(S.desktop_wires_pending), state.wireInvoices, StatusTone.Rejected, state.rates)
        Tile(str(S.desktop_cheques), state.chequeInvoices, StatusTone.Escalated, state.rates)
        val groups = state.openItemGroups.size
        ZillitStatTile(
            label = str(S.ah_vendors),
            value = groups.toString(),
            sub = if (groups == 1) str(S.desktop_inv_one_vendor_group) else str(S.desktop_inv_n_vendor_groups, groups),
            tone = StatusTone.Ready,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
private fun RowScope.Tile(label: String, rows: List<Invoice>, tone: StatusTone, rates: CurrencyRates) {
    // Adding pounds to yuan and calling the answer yuan is a wrong number on
    // a payment screen, so a mixed queue is converted and says so.
    val total = rates.total(rows.map { it.grossAmount to it.currency })
    ZillitStatTile(
        label = label,
        value = rows.size.toString(),
        sub = total.caveat?.let { "${total.text} · $it" } ?: total.text,
        tone = tone,
        modifier = Modifier.weight(1f).fillMaxHeight(),
    )
}

/**
 * What can be done with the ticks.
 *
 * One method in the selection gets that method's own button; a mixed one gets
 * "Process", which opens the sheet that offers each method separately.
 */
@Composable
private fun ProcessActions(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    val rows = state.paymentRows
    val allTicked = rows.isNotEmpty() && rows.all { it.id in state.selected }
    ZillitButton(
        text = if (allTicked) str(S.desktop_select_none) else str(S.select_all),
        onClick = { onEvent(InvoicesEvent.ToggleSelectAll) },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        enabled = rows.isNotEmpty(),
    )
    if (state.selected.isEmpty()) return
    ZillitText(text = str(S.dd_n_selected, state.selected.size), style = ZillitTheme.typography.bodySmall)
    ZillitButton(
        text = processLabel(state),
        onClick = { onEvent(InvoicesEvent.ProcessSelected(null)) },
        size = ButtonSize.Small,
        // The web's `canAuthorisePaymentRuns`: run access from Settings, or a senior designation.
        enabled = state.viewer.canOperateRuns && !state.busy,
    )
    ZillitButton(
        text = str(S.ah_clear),
        onClick = { onEvent(InvoicesEvent.ClearSelection) },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
    )
}

private fun processLabel(state: InvoicesUiState): String = when (state.selectedPayMethod) {
    PayMethod.Bacs -> str(S.desktop_create_bacs_run)
    PayMethod.Wire, PayMethod.Faster -> str(S.desktop_mark_paid_title)
    PayMethod.Cheque -> str(S.desktop_ready_to_print)
    null -> str(S.desktop_process_n, state.selected.size)
    else -> str(S.desktop_process_n, state.selected.size)
}

/** The batches themselves: what each pays, by what method, and where it stands. */
@Composable
private fun ColumnScope.RunsTab(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    val runs = state.paymentRuns
    TableCard(
        title = str(S.desktop_active_runs),
        icon = ZillitIcons.Wallet,
        meta = countMeta(runs.size, S.desktop_run_count_one, S.desktop_run_count_other),
    ) {
        ZillitDataTable(
            rows = runs,
            columns = runColumns(state),
            key = { it.id },
            // The web opens the run; its footer carries the decisions.
            onRowClick = { onEvent(InvoicesEvent.OpenRun(it)) },
            emptyTitle = str(S.email_rule_exec_empty_title),
            emptyMessage = str(S.desktop_inv_runs_empty_message),
            loading = state.loading && runs.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun runColumns(state: InvoicesUiState): List<TableColumn<PaymentRun>> =
    listOf(
        TableColumn(
            str(S.ah_run_card_run_label),
            ColumnWidth.Weight(1f),
        ) { CellText(it.number.ifBlank { it.id.take(RUN_ID_CHARS) }) },
        TableColumn(str(S.description), ColumnWidth.Weight(WEIGHT_WIDEST)) {
            CellText(it.name.ifBlank { "—" }, muted = it.name.isBlank())
        },
        TableColumn(str(S.desktop_method), ColumnWidth.Fixed(PAY_WIDTH)) {
            ZillitStatusPill(label = it.payMethod.label, tone = it.payMethod.tone())
        },
        TableColumn(str(S.ah_total_label), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
            MoneyText(it.total, it.currency, state.projectCurrency)
        },
        TableColumn(str(S.desktop_inv_col_inv), ColumnWidth.Fixed(TICK_COUNT_WIDTH), numeric = true) {
            CellText(it.invoiceCount.toString())
        },
        TableColumn(str(S.status), ColumnWidth.Weight(WEIGHT_NARROW)) {
            ZillitStatusPill(label = it.status.label, tone = it.status.tone())
        },
    )

private fun PaymentRunStatus.tone(): StatusTone = when (this) {
    PaymentRunStatus.Draft -> StatusTone.Neutral
    PaymentRunStatus.Pending -> StatusTone.Pending
    PaymentRunStatus.Approved -> StatusTone.Ready
    PaymentRunStatus.Rejected -> StatusTone.Rejected
    PaymentRunStatus.Paid -> StatusTone.Done
}

private const val RUN_ID_CHARS = 6

/** The flat table's columns, as the grouped header lines up against them. */
private const val TICK_COLUMN = 0
private const val REF_COLUMN = 1
private const val DESCRIPTION_COLUMN = 3
private const val AMOUNT_COLUMN = 4
private const val LAST_COLUMN = 7
private val MARK_PAID_WIDTH = 110.dp
