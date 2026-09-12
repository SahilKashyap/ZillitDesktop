package com.zillit.desktop.feature.invoices.ui.pages

import com.zillit.desktop.feature.invoices.domain.CurrencyRates
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.InvoiceRules
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.PaymentRun
import com.zillit.desktop.feature.invoices.domain.PaymentRunStatus
import com.zillit.desktop.feature.invoices.domain.PaymentTab
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState

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
    if (!state.viewer.isRunApprover && !state.viewer.isSenior) {
        // The web raises the same banner: without an authoriser nobody can
        // action a run, so say it here rather than failing at the button.
        MutedLine("Nobody on the team has run access yet, so runs cannot be authorised.")
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
            placeholder = "Search ref, vendor, description",
            modifier = Modifier.width(SEARCH_WIDTH).focusRequester(searchFocus),
        )
        ProcessActions(state, onEvent)
    }
    val rows = state.paymentRows
    TableCard(
        title = "Open Items",
        icon = ZillitIcons.Wallet,
        meta = countMeta(rows.size, "invoice"),
    ) {
        ZillitDataTable(
            rows = rows,
            columns = openItemColumns(state, nowMs, onEvent),
            key = { it.id },
            onRowClick = { onEvent(InvoicesEvent.Open(it)) },
            emptyTitle = "Nothing waiting to be paid",
            emptyMessage = "Approved invoices appear here once they are ready to pay.",
            loading = state.loading && rows.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun openItemColumns(
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
    TableColumn("Ref", ColumnWidth.Weight(1f)) { CellText(it.displayNumber) },
    TableColumn("Vendor", ColumnWidth.Weight(WEIGHT_MEDIUM)) { CellText(state.vendorName(it)) },
    TableColumn("Description", ColumnWidth.Weight(WEIGHT_WIDEST)) {
        CellText(it.description.ifBlank { "—" }, muted = it.description.isBlank())
    },
    TableColumn("Amount", ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.grossAmount, it.currency, state.projectCurrency)
    },
    poColumn(),
    TableColumn("Method", ColumnWidth.Fixed(PAY_WIDTH)) {
        ZillitStatusPill(label = it.payMethod.label, tone = it.payMethod.tone())
    },
    TableColumn("Days", ColumnWidth.Fixed(DUE_WIDTH)) { invoice ->
        val overdue = InvoiceRules.daysOverdue(invoice, nowMs)
        if (overdue != null) {
            ZillitText(
                text = "${overdue}d overdue",
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
        Tile("Open Items", state.invoices, StatusTone.Pending, state.rates)
        Tile("BACs Queue", state.bacsInvoices, StatusTone.InTransit, state.rates)
        Tile("Wires Pending", state.wireInvoices, StatusTone.Rejected, state.rates)
        Tile("Cheques", state.chequeInvoices, StatusTone.Escalated, state.rates)
        val groups = state.openItemGroups.size
        ZillitStatTile(
            label = "Vendors",
            value = groups.toString(),
            sub = if (groups == 1) "1 vendor group" else "$groups vendor groups",
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
        text = if (allTicked) "Select none" else "Select all",
        onClick = { onEvent(InvoicesEvent.ToggleSelectAll) },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        enabled = rows.isNotEmpty(),
    )
    if (state.selected.isEmpty()) return
    ZillitText(text = "${state.selected.size} selected", style = ZillitTheme.typography.bodySmall)
    ZillitButton(
        text = processLabel(state),
        onClick = { onEvent(InvoicesEvent.ProcessSelected(null)) },
        size = ButtonSize.Small,
        enabled = (state.viewer.isRunApprover || state.viewer.isSenior) && !state.busy,
    )
    ZillitButton(
        text = "Clear",
        onClick = { onEvent(InvoicesEvent.ClearSelection) },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
    )
}

private fun processLabel(state: InvoicesUiState): String = when (state.selectedPayMethod) {
    PayMethod.Bacs -> "Create BACs Run"
    PayMethod.Wire, PayMethod.Faster -> "Mark Paid"
    PayMethod.Cheque -> "Ready to Print"
    null -> "Process ${state.selected.size}"
    else -> "Process ${state.selected.size}"
}

/** The batches themselves: what each pays, by what method, and where it stands. */
@Composable
private fun ColumnScope.RunsTab(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    val runs = state.paymentRuns
    TableCard(
        title = "Active Runs",
        icon = ZillitIcons.Wallet,
        meta = countMeta(runs.size, "run"),
    ) {
        ZillitDataTable(
            rows = runs,
            columns = runColumns(state, onEvent),
            key = { it.id },
            emptyTitle = "No runs yet",
            emptyMessage = "Tick open items and build a run to pay them together.",
            loading = state.loading && runs.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun runColumns(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit): List<TableColumn<PaymentRun>> =
    listOf(
        TableColumn("Run", ColumnWidth.Weight(1f)) { CellText(it.number.ifBlank { it.id.take(RUN_ID_CHARS) }) },
        TableColumn("Description", ColumnWidth.Weight(WEIGHT_WIDEST)) {
            CellText(it.name.ifBlank { "—" }, muted = it.name.isBlank())
        },
        TableColumn("Method", ColumnWidth.Fixed(PAY_WIDTH)) {
            ZillitStatusPill(label = it.payMethod.label, tone = it.payMethod.tone())
        },
        TableColumn("Total", ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
            MoneyText(it.total, it.currency, state.projectCurrency)
        },
        TableColumn("Inv", ColumnWidth.Fixed(TICK_COUNT_WIDTH), numeric = true) {
            CellText(it.invoiceCount.toString())
        },
        TableColumn("Status", ColumnWidth.Weight(WEIGHT_NARROW)) {
            ZillitStatusPill(label = it.status.label, tone = it.status.tone())
        },
        TableColumn("", ColumnWidth.Fixed(RUN_ACTIONS_WIDTH)) { run ->
            RunActions(state, run, onEvent)
        },
    )

@Composable
private fun RunActions(state: InvoicesUiState, run: PaymentRun, onEvent: (InvoicesEvent) -> Unit) {
    val mayDecide = state.viewer.isRunApprover || state.viewer.isSenior
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (run.status.isDecidable && mayDecide) {
            ZillitButton(
                text = "Approve",
                onClick = { onEvent(InvoicesEvent.ApproveRun(run)) },
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
            ZillitButton(
                text = "Reject",
                onClick = { onEvent(InvoicesEvent.StartRejectRun(run)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
        }
        if (run.status == PaymentRunStatus.Draft && mayDecide) {
            ZillitButton(
                text = "Delete",
                onClick = { onEvent(InvoicesEvent.DeleteRun(run)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
        }
    }
}

private fun PaymentRunStatus.tone(): StatusTone = when (this) {
    PaymentRunStatus.Draft -> StatusTone.Neutral
    PaymentRunStatus.Pending -> StatusTone.Pending
    PaymentRunStatus.Approved -> StatusTone.Ready
    PaymentRunStatus.Rejected -> StatusTone.Rejected
    PaymentRunStatus.Paid -> StatusTone.Done
}

private const val RUN_ID_CHARS = 6
