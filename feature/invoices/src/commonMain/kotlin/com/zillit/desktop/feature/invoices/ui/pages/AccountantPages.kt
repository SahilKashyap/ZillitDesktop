// The three accountant pages; one composable per page piece.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.invoices.ui.pages

import com.zillit.desktop.feature.invoices.domain.InvoiceExport
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.invoices.domain.ApprovalChain
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.InvoiceRules
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.DateWindow
import com.zillit.desktop.feature.invoices.domain.PoPills
import com.zillit.desktop.feature.invoices.ui.AccountantPage
import com.zillit.desktop.feature.invoices.ui.InboxEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.RegisterChip
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

@Composable
internal fun ColumnScope.AccountantPageContent(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
    nowMs: Long,
    /** `/` puts the cursor in the open page's search box. */
    searchFocus: FocusRequester,
) {
    when (state.page) {
        AccountantPage.Overview -> OverviewPage(state, onEvent)
        AccountantPage.Register -> RegisterPage(state, onEvent, nowMs, searchFocus)
        AccountantPage.Inbox -> InboxPage(state, onEvent, searchFocus)
        AccountantPage.ApprovalQueue -> ApprovalPage(state, onEvent)
        // A posted invoice opens the coding screen frozen, as the web's Posted page does.
        AccountantPage.Posted -> state.ledger?.let { EntryLedgerView(state, it, onEvent) }
            ?: PostedPage(state, onEvent, searchFocus)
        AccountantPage.Matching -> MatchingPage(state, onEvent, searchFocus)
        AccountantPage.Creditors -> CreditorsPage(state, onEvent, nowMs, searchFocus)
        // The coding screen takes the queue's place while an invoice is open on it.
        AccountantPage.Entry -> state.ledger?.let { EntryLedgerView(state, it, onEvent) }
            ?: EntryPage(state, onEvent, searchFocus)
        AccountantPage.Payments -> PaymentsPage(state, onEvent, nowMs, searchFocus)
        AccountantPage.Vendors -> VendorsPage(state, onEvent, searchFocus)
        // New / Edit takes the list's place, as the web's form view does.
        AccountantPage.Sales -> state.salesDraft?.let { SalesInvoiceFormView(state, it, onEvent) }
            ?: SalesInvoicesPage(state, onEvent, nowMs, searchFocus)
        AccountantPage.Analytics -> AnalyticsPage(state)
        // New / Edit takes the list's place, as the web's form view does.
        AccountantPage.Credits -> state.credit.form?.let { CreditNoteFormView(state, it, onEvent) }
            ?: CreditNotesPage(state, onEvent, nowMs, searchFocus)
        AccountantPage.Accruals -> AccrualsPage(state, onEvent, searchFocus)
        AccountantPage.Reports -> ComingSoon(str(S.reports))
        AccountantPage.Settings -> SettingsPage(state, onEvent)
    }
}

// Register -----------------------------------------------------------------

/**
 * Every invoice in one place — the web's `RegisterPage`: the search bar on its
 * own line, then the status chips with the department, date and Export
 * controls beside them, then the table in its panel.
 */
@Composable
private fun ColumnScope.RegisterPage(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
    nowMs: Long,
    searchFocus: FocusRequester,
) {
    ZillitSearchField(
        value = state.search,
        onValueChange = { onEvent(InvoicesEvent.Search(it)) },
        placeholder = str(S.desktop_inv_search_register),
        modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RegisterChip.entries.forEach { chip ->
            ZillitChoiceChip(
                label = chip.label,
                selected = state.registerChip == chip,
                onClick = { onEvent(InvoicesEvent.SelectRegisterChip(chip)) },
            )
        }
        Spacer(Modifier.weight(1f))
        RegisterDepartmentFilter(
            state = state,
            value = state.registerDepartment,
            onSelect = { onEvent(InvoicesEvent.SelectRegisterDepartment(it)) },
            modifier = Modifier.width(DEPARTMENT_SELECT_WIDTH),
        )
        ZillitSelect(
            value = state.registerDate,
            options = DateWindow.entries,
            onSelect = { onEvent(InvoicesEvent.SelectRegisterDate(it)) },
            label = ::registerDateLabel,
            modifier = Modifier.width(DATE_SELECT_WIDTH),
        )
        ExportMenu(InvoiceExport.Register, state.busy, onEvent)
    }
    // The Date filter counts back from now, so it is applied here, where now is.
    val rows = state.shownInvoices.filter { state.registerDate.keeps(it.invoiceDateMs, nowMs) }
    TableCard(
        title = str(S.desktop_invoice_register),
        icon = ZillitIcons.Ledger,
        meta = countMeta(rows.size),
    ) {
        ZillitDataTable(
            rows = rows,
            columns = registerPageColumns(state, nowMs),
            key = { it.id },
            // The web routes a register row by status: inbox rows open the
            // editable review, matching rows the PO matching overlay, and
            // everything else a read-only detail.
            onRowClick = {
                onEvent(
                    when (it.status) {
                        InvoiceStatus.Inbox -> InboxEvent.Open(it)
                        InvoiceStatus.Matching -> InvoicesEvent.OpenReview(it)
                        else -> InvoicesEvent.Open(it)
                    },
                )
            },
            emptyTitle = str(S.desktop_inv_register_empty),
            loading = state.loading && rows.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The Register's columns (`RegisterPage.jsx:486-565`): the PO cell and the
 * urgent status read the web's own rules ([PoPills]), and Due counts whole
 * days past due on anything not yet settled.
 */
private fun registerPageColumns(state: InvoicesUiState, nowMs: Long): List<TableColumn<Invoice>> = listOf(
    // The row's unread under `invoice_register` (`RegisterPage.jsx:527-538`) —
    // shown, never read here: the Register is a history surface.
    TableColumn(str(S.ah_run_detail_col_invoice), ColumnWidth.Weight(1f)) {
        CellTextWithUnread(it.displayNumber, state.pageRowUnread(AccountantPage.Register, it.id))
    },
    TableColumn(str(S.ah_lbl_vendor), ColumnWidth.Weight(WEIGHT_MEDIUM)) { CellText(state.pageVendorName(it)) },
    TableColumn(str(S.description), ColumnWidth.Weight(WEIGHT_WIDEST)) {
        CellText(it.description, muted = true)
    },
    TableColumn(str(S.desktop_po), ColumnWidth.Fixed(PO_WIDTH)) { BadgePill(PoPills.register(it)) },
    TableColumn(
        str(S.department),
        ColumnWidth.Weight(1f),
    ) { CellText(state.departmentName(it.departmentId), muted = true) },
    // Fixed, not weighted: the urgent badge reads "No PO · Urgent Wire
    // Request", and a weighted column clips it to "No PO · Urgent Wir…" —
    // which drops the one word that says what the row is.
    TableColumn(str(S.status), ColumnWidth.Fixed(URGENT_STATUS_WIDTH)) { invoice ->
        if (invoice.isUrgentRaw && invoice.status != InvoiceStatus.Override) {
            BadgePill(PoPills.registerUrgentStatus(invoice))
        } else {
            ZillitStatusPill(label = invoice.statusLabel, tone = invoice.status.statusTone())
        }
    },
    TableColumn(str(S.desktop_gross), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.grossAmount, it.currency, state.projectCurrency)
    },
    TableColumn(str(S.desktop_due), ColumnWidth.Fixed(DUE_WIDTH), numeric = true) { invoice ->
        val overdue = InvoiceRules.registerOverdueDays(invoice, nowMs)
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

// Pre-approval --------------------------------------------------------------

/**
 * The pre-approval queue — the web's `MatchingPage`.
 *
 * Two statuses in one list: what is waiting to be checked, then what has
 * been held for query, tinted amber and each with its own banner under the
 * table. A held row cannot be sent on, so the tick box and the bulk bar leave
 * it alone, exactly as the web's select-all does.
 */
@Composable
private fun ColumnScope.MatchingPage(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
    searchFocus: FocusRequester,
) {
    MatchingTiles(state)
    ZillitSearchField(
        value = state.search,
        onValueChange = { onEvent(InvoicesEvent.Search(it)) },
        placeholder = str(S.desktop_inv_search_matching),
        modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
    )
    val rows = state.shownInvoices
    val colors = ZillitTheme.colors
    TableCard(
        title = str(S.desktop_inv_pre_approval_queue),
        icon = ZillitIcons.Link,
        // The whole queue, not what the search left (`MatchingPage.jsx:636`).
        meta = countMeta(state.invoices.size),
    ) {
        ZillitDataTable(
            rows = rows,
            columns = matchingColumns(state, onEvent),
            key = { it.id },
            // With a selection going, a click on a waiting row ticks it;
            // otherwise — and always on a held row — it opens the review
            // (`MatchingPage.jsx:682, 724`).
            onRowClick = {
                if (state.selected.isNotEmpty() && it.status != InvoiceStatus.Held) {
                    onEvent(InvoicesEvent.ToggleSelect(it.id))
                } else {
                    onEvent(InvoicesEvent.OpenReview(it))
                }
            },
            isSelected = { it.id in state.selected },
            rowTint = { if (it.status == InvoiceStatus.Held) colors.warningSoft else null },
            emptyTitle = str(S.desktop_inv_matching_empty),
            loading = state.loading && rows.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
    rows.heldRows().forEach { HoldBanner(state, it, onEvent) }
    if (state.selected.isNotEmpty()) MatchingBulkBar(state, onEvent)
}

private fun matchingColumns(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
): List<TableColumn<Invoice>> = listOf(
    TableColumn(
        header = "",
        width = ColumnWidth.Fixed(TICK_WIDTH),
        // Ticked when every waiting row on screen is (`MatchingPage.jsx:641`).
        headerContent = {
            val waiting = state.shownInvoices.count { it.status != InvoiceStatus.Held }
            ZillitCheckbox(
                checked = waiting > 0 && state.selected.size == waiting,
                onCheckedChange = { onEvent(InvoicesEvent.ToggleSelectAll) },
            )
        },
    ) { invoice ->
        // A held invoice is not a candidate for the bulk actions.
        if (invoice.status != InvoiceStatus.Held) {
            ZillitCheckbox(
                checked = invoice.id in state.selected,
                onCheckedChange = { onEvent(InvoicesEvent.ToggleSelect(invoice.id)) },
            )
        }
    },
    // Waiting and held rows alike carry their `invoice_matching` chip (`MatchingPage.jsx:688, 727`).
    TableColumn(str(S.ah_run_detail_col_invoice), ColumnWidth.Weight(1f)) {
        CellTextWithUnread(it.displayNumber, state.pageRowUnread(AccountantPage.Matching, it.id))
    },
    TableColumn(str(S.ah_lbl_vendor), ColumnWidth.Weight(WEIGHT_MEDIUM)) { CellText(state.pageVendorName(it)) },
    TableColumn(str(S.description), ColumnWidth.Weight(WEIGHT_WIDEST)) {
        CellText(it.description, muted = true)
    },
    TableColumn(str(S.amount), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.grossAmount, it.currency, state.projectCurrency)
    },
    // Blue when matched, amber for a typed number nobody has confirmed, red
    // for nothing — and never amber on a held row (`MatchingPage.jsx:695, 733-737`).
    TableColumn(str(S.desktop_po), ColumnWidth.Fixed(MATCH_PO_WIDTH)) { BadgePill(PoPills.matching(it)) },
    TableColumn(str(S.desktop_pay_method_title), ColumnWidth.Fixed(PAY_WIDTH)) {
        ZillitStatusPill(label = it.payMethodLabel, tone = it.payMethod.tone())
    },
    TableColumn(str(S.status), ColumnWidth.Fixed(URGENT_STATUS_WIDTH)) { BadgePill(PoPills.matchingStatus(it)) },
    TableColumn("", ColumnWidth.Fixed(MATCH_ACTIONS_WIDTH)) { invoice ->
        MatchingActions(state, invoice, onEvent)
    },
)

/**
 * Matched, Unmatched and — when there are any — On Hold, as the web's stat
 * cards count them: held rows apart, the rest by the server's `po_ids`
 * (`MatchingPage.jsx:602-607`), over what the search leaves.
 */
@Composable
private fun MatchingTiles(state: InvoicesUiState) {
    val rows = state.shownInvoices
    val held = rows.count { it.status == InvoiceStatus.Held }
    val open = rows.filter { it.status != InvoiceStatus.Held }
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitStatTile(
            label = str(S.desktop_matched),
            value = open.count { it.poIds.isNotEmpty() }.toString(),
            sub = str(S.desktop_inv_linked_to_a_po),
            tone = StatusTone.Done,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        ZillitStatTile(
            label = str(S.desktop_dm_unmatched),
            value = open.count { it.poIds.isEmpty() }.toString(),
            sub = str(S.desktop_inv_need_a_po_match),
            tone = StatusTone.Rejected,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        if (held > 0) {
            ZillitStatTile(
                label = str(S.desktop_on_hold_title),
                value = held.toString(),
                sub = str(S.desktop_inv_awaiting_query),
                tone = StatusTone.Pending,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

/**
 * What can be done to one row, routed by the PO as the web routes it
 * (`MatchingPage.jsx:708-714, 740-742`): held rows are released; a row with
 * an order goes to the review; one without is overridden by whoever may, and
 * sent for approval by everyone else.
 */
@Composable
private fun MatchingActions(state: InvoicesUiState, invoice: Invoice, onEvent: (InvoicesEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            invoice.status == InvoiceStatus.Held -> ZillitButton(
                text = str(S.desktop_release),
                onClick = { onEvent(InvoicesEvent.Release(invoice)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
            invoice.hasMatchedPo -> ZillitButton(
                text = str(S.av_review),
                onClick = { onEvent(InvoicesEvent.OpenReview(invoice)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                trailingIcon = ZillitIcons.ChevronRight,
                enabled = !state.busy,
            )
            state.viewer.canOverride -> ZillitButton(
                text = str(S.dm_nom_table_override),
                onClick = { onEvent(InvoicesEvent.Override(invoice)) },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
            else -> ZillitButton(
                text = str(S.cs_send_for_approval),
                onClick = { onEvent(InvoicesEvent.SendToApproval(invoice)) },
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
        }
    }
}

/** The web shows this here too — the section is being reworked on both clients. */
@Composable
private fun ColumnScope.ComingSoon(title: String) {
    ZillitEmptyState(
        title = str(S.desktop_named_coming_soon, title),
        message = str(S.desktop_inv_reports_coming_soon),
        icon = ZillitIcons.File,
        modifier = Modifier.weight(1f).fillMaxWidth(),
    )
}

// Inbox --------------------------------------------------------------------

// Approval queue -----------------------------------------------------------

/**
 * The accountant's Approval Queue — the web's `ApprovalPage`: one panel, the
 * awaiting and approved pills in its header, and every row's actions in the
 * last column. No search and no ticks: the web's batch approve is never
 * rendered (`ApprovalPage.jsx:524-710`).
 */
@Composable
private fun ColumnScope.ApprovalPage(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
) {
    val rows = state.shownInvoices
    val colors = ZillitTheme.colors
    TableCard(
        title = str(S.ah_approval_queue),
        icon = ZillitIcons.Shield,
        action = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitStatusPill(label = str(S.desktop_inv_n_awaiting, state.awaitingCount), tone = StatusTone.Pending)
                if (state.approvedCount > 0) {
                    ZillitStatusPill(
                        label = str(S.desktop_inv_n_approved, state.approvedCount),
                        tone = StatusTone.Ready,
                    )
                }
            }
        },
    ) {
        ZillitDataTable(
            rows = rows,
            columns = approvalColumns(state, onEvent),
            key = { it.id },
            onRowClick = { onEvent(InvoicesEvent.Open(it)) },
            // An approved row is washed green (`ApprovalPage.jsx:578`).
            rowTint = { if (it.isApprovedStatus) colors.successSoft else null },
            emptyTitle = str(S.desktop_inv_approval_empty),
            loading = state.loading && rows.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Invoice · Vendor · Gross · PO · Approval · SLA · Action — the web's seven (`ApprovalPage.jsx:543-549`). */
private fun approvalColumns(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
): List<TableColumn<Invoice>> = listOf(
    // The row's `invoice_approval_queue` chip (`ApprovalPage.jsx:166-173, 580`).
    TableColumn<Invoice>(str(S.ah_run_detail_col_invoice), ColumnWidth.Weight(WEIGHT_NARROW)) {
        CellTextWithUnread(it.displayNumber, state.pageRowUnread(AccountantPage.ApprovalQueue, it.id))
    },
    TableColumn(str(S.ah_lbl_vendor), ColumnWidth.Weight(WEIGHT_WIDE)) { CellText(state.pageVendorName(it)) },
    TableColumn(str(S.desktop_gross), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.grossAmount, it.currency, state.projectCurrency)
    },
    TableColumn(str(S.desktop_po), ColumnWidth.Fixed(PO_WIDTH)) { BadgePill(PoPills.queue(it)) },
    approvalColumn(state, queue = true),
    slaPillColumn(state),
    TableColumn(
        str(S.txt_action),
        ColumnWidth.Fixed(QUEUE_ACTION_WIDTH),
        numeric = true,
    ) { invoice -> QueueActions(state, invoice, onEvent) },
)

/**
 * One row's actions, in the web's order (`ApprovalPage.jsx:613-703`).
 *
 * A row dated in a closed cost-report period keeps only Chase (H7: `row.locked`
 * strips Override & Pay, Approve, Override and Delete). Override & Pay is the
 * forward action for an override or urgent row that is not `status`
 * approved; Approve otherwise, when the next tier is the reader's.
 */
@Composable
private fun QueueActions(state: InvoicesUiState, invoice: Invoice, onEvent: (InvoicesEvent) -> Unit) {
    val tiers = state.tiersOf(invoice)
    val viewer = state.viewer
    val locked = state.isLocked(invoice)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!locked) {
            when {
                InvoiceRules.showOverrideAndPay(invoice, viewer) && !invoice.isApprovedStatus -> ZillitButton(
                    text = str(S.desktop_override_and_pay),
                    onClick = { onEvent(InvoicesEvent.OverrideAndPay(invoice)) },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
                ApprovalChain.canApprove(invoice, tiers, viewer.userId) && !invoice.isApprovedStatus -> ZillitButton(
                    text = str(S.approve),
                    onClick = { onEvent(InvoicesEvent.Approve(invoice)) },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Check,
                    enabled = !state.busy,
                )
            }
            if (InvoiceRules.showOverride(invoice, tiers, viewer)) {
                ZillitButton(
                    text = str(S.dm_nom_table_override),
                    onClick = { onEvent(InvoicesEvent.Override(invoice)) },
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
            }
        }
        if (InvoiceRules.showChase(invoice, tiers, viewer)) ChaseControl(state, invoice, onEvent)
        if (queueCanDelete(state, invoice)) {
            ZillitButton(
                text = str(S.delete),
                onClick = { onEvent(InvoicesEvent.RequestDelete(invoice)) },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
        }
    }
}

/**
 * The hard delete's gate on the accountant queue — the web's `canDeleteInvoice`
 * with the console's own override right (`ApprovalPage.jsx:481-487`).
 */
internal fun queueCanDelete(state: InvoicesUiState, invoice: Invoice): Boolean = InvoiceRules.canDeleteFromQueue(
    invoice = invoice,
    tiers = state.tiersOf(invoice),
    viewer = state.viewer,
    canOverride = state.viewer.canOverride,
    locked = state.isLocked(invoice),
)

internal val DEPARTMENT_SELECT_WIDTH = 220.dp
private val DATE_SELECT_WIDTH = 150.dp
internal val DUE_WIDTH = 110.dp
internal val PAY_WIDTH = 120.dp
internal const val PERCENT = 100
internal val TICK_WIDTH = 40.dp
private val MATCH_ACTIONS_WIDTH = 160.dp
private val MATCH_PO_WIDTH = 104.dp
private val URGENT_STATUS_WIDTH = 200.dp
private val QUEUE_ACTION_WIDTH = 300.dp
