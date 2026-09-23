// The three accountant pages; one composable per page piece.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.invoices.ui.pages

import com.zillit.desktop.feature.invoices.domain.InvoiceExport
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import com.zillit.desktop.feature.invoices.domain.AgeingWeek
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.Money
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
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.invoices.domain.Accrual
import com.zillit.desktop.feature.invoices.domain.AccrualFilter
import com.zillit.desktop.feature.invoices.domain.AccrualStatus
import com.zillit.desktop.feature.invoices.domain.AgeingBucket
import com.zillit.desktop.feature.invoices.domain.ApprovalChain
import com.zillit.desktop.feature.invoices.domain.CreditNote
import com.zillit.desktop.feature.invoices.domain.CreditNoteFilter
import com.zillit.desktop.feature.invoices.domain.CreditNoteStatus
import com.zillit.desktop.feature.invoices.domain.CreditNoteType
import com.zillit.desktop.feature.invoices.domain.CreditorRow
import com.zillit.desktop.feature.invoices.domain.Creditors
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.InvoiceRules
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.ui.AccountantPage
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.PostedFilter
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
        AccountantPage.ApprovalQueue -> ApprovalPage(state, onEvent, searchFocus)
        AccountantPage.Posted -> PostedPage(state, onEvent, nowMs, searchFocus)
        AccountantPage.Matching -> MatchingPage(state, onEvent, searchFocus)
        AccountantPage.Creditors -> CreditorsPage(state, onEvent, nowMs, searchFocus)
        AccountantPage.Entry -> EntryPage(state, onEvent, searchFocus)
        AccountantPage.Payments -> PaymentsPage(state, onEvent, nowMs, searchFocus)
        AccountantPage.Vendors -> VendorsPage(state, onEvent, searchFocus)
        AccountantPage.Sales -> SalesInvoicesPage(state, onEvent, searchFocus)
        AccountantPage.Analytics -> AnalyticsPage(state)
        AccountantPage.Credits -> CreditNotesPage(state, onEvent, searchFocus)
        AccountantPage.Accruals -> AccrualsPage(state, onEvent, searchFocus)
        AccountantPage.Reports -> ComingSoon(str(S.reports))
        AccountantPage.Settings -> SettingsPage(state, onEvent)
    }
}

// Register -----------------------------------------------------------------

@Composable
private fun ColumnScope.RegisterPage(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
    nowMs: Long,
    searchFocus: FocusRequester,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = state.search,
            onValueChange = { onEvent(InvoicesEvent.Search(it)) },
            placeholder = str(S.desktop_inv_search_ref_supplier_description_po),
            modifier = Modifier.width(SEARCH_WIDTH).focusRequester(searchFocus),
        )
        val options = listOf<String?>(null) + state.departmentOptions
        ZillitSelect(
            value = state.registerDepartment,
            options = options,
            onSelect = { onEvent(InvoicesEvent.SelectRegisterDepartment(it)) },
            label = { id -> if (id == null) str(S.all_departments) else state.departmentName(id) },
            modifier = Modifier.width(DEPARTMENT_SELECT_WIDTH),
        )
        Spacer(Modifier.weight(1f))
        ExportActions(InvoiceExport.Register, state.busy, onEvent)
    }
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
    }
    val rows = state.shownInvoices
    TableCard(
        title = str(S.desktop_invoice_register),
        icon = ZillitIcons.Ledger,
        meta = countMeta(rows.size),
    ) {
        ZillitDataTable(
            rows = rows,
            columns = registerColumns(state, nowMs),
            key = { it.id },
            onRowClick = { onEvent(InvoicesEvent.Open(it)) },
            emptyTitle = str(S.desktop_inv_no_invoices_match),
            loading = state.loading && rows.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun registerColumns(state: InvoicesUiState, nowMs: Long): List<TableColumn<Invoice>> = listOf(
    TableColumn(str(S.ah_run_detail_col_invoice), ColumnWidth.Weight(1f)) { CellText(it.displayNumber) },
    TableColumn(str(S.ah_lbl_vendor), ColumnWidth.Weight(WEIGHT_MEDIUM)) { CellText(state.vendorName(it)) },
    TableColumn(str(S.description), ColumnWidth.Weight(WEIGHT_WIDEST)) {
        CellText(it.description.ifBlank { "—" }, muted = it.description.isBlank())
    },
    poColumn(),
    TableColumn(
        str(S.department),
        ColumnWidth.Weight(1f),
    ) { CellText(state.departmentName(it.departmentId), muted = true) },
    // Fixed, not weighted: the urgent badge reads "No PO · Urgent Wire
    // Request", and a weighted column clips it to "No PO · Urgent Wir…" —
    // which drops the one word that says what the row is.
    TableColumn(str(S.status), ColumnWidth.Fixed(URGENT_STATUS_WIDTH)) { invoice ->
        if (invoice.payMethod.isUrgent && invoice.status != InvoiceStatus.Override) {
            BadgePill(InvoiceRules.urgentBadge(invoice))
        } else {
            ZillitStatusPill(label = invoice.statusLabel, tone = invoice.status.statusTone())
        }
    },
    TableColumn(str(S.desktop_gross), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.grossAmount, it.currency, state.projectCurrency)
    },
    TableColumn(str(S.desktop_due), ColumnWidth.Fixed(DUE_WIDTH)) { invoice ->
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

// Accruals ------------------------------------------------------------------

/**
 * What is committed but not yet invoiced — the web's `AccrualsPage`.
 *
 * One row per purchase order: its total, what has been invoiced against it,
 * and the difference the period must carry. Recalculating is the server's
 * job; this page only asks for it.
 */
@Composable
private fun ColumnScope.AccrualsPage(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
    searchFocus: FocusRequester,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = state.search,
            onValueChange = { onEvent(InvoicesEvent.Search(it)) },
            placeholder = str(S.desktop_inv_search_po_vendor_description),
            modifier = Modifier.width(SEARCH_WIDTH).focusRequester(searchFocus),
        )
        val options = listOf<String?>(null) + state.accruals.map { it.departmentId }.filter { it.isNotBlank() }
            .distinct()
        ZillitSelect(
            value = state.registerDepartment,
            options = options,
            onSelect = { onEvent(InvoicesEvent.SelectRegisterDepartment(it)) },
            label = { id -> if (id == null) str(S.all_departments) else state.departmentName(id) },
            modifier = Modifier.width(DEPARTMENT_SELECT_WIDTH),
        )
        ZillitButton(
            text = str(S.desktop_regenerate_all),
            onClick = { onEvent(InvoicesEvent.RegenerateAccruals) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            loading = state.busy,
            enabled = !state.busy,
        )
        ExportActions(InvoiceExport.Accruals, state.busy, onEvent)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccrualFilter.entries.forEach { filter ->
            ZillitChoiceChip(
                label = filter.label,
                selected = state.accrualFilter == filter,
                onClick = { onEvent(InvoicesEvent.SelectAccrualFilter(filter)) },
            )
        }
    }
    val rows = state.shownAccruals
    TableCard(
        title = str(S.desktop_accruals),
        icon = ZillitIcons.Ledger,
        meta = countMeta(rows.size, S.desktop_accrual_count_one, S.desktop_accrual_count_other),
    ) {
        ZillitDataTable(
            rows = rows,
            columns = accrualColumns(state),
            key = { it.id },
            emptyTitle = str(S.desktop_inv_nothing_to_accrue),
            emptyMessage = str(S.desktop_inv_accruals_empty_message),
            loading = state.accrualsLoading && rows.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun accrualColumns(state: InvoicesUiState): List<TableColumn<Accrual>> = listOf(
    TableColumn(str(S.desktop_po), ColumnWidth.Weight(1f)) { CellText(it.poNumber.ifBlank { "—" }) },
    TableColumn(
        str(S.ah_lbl_vendor),
        ColumnWidth.Weight(WEIGHT_MEDIUM),
    ) { CellText(it.vendorName.ifBlank { str(S.desktop_unknown) }) },
    TableColumn(str(S.description), ColumnWidth.Weight(WEIGHT_WIDEST)) {
        CellText(it.description.ifBlank { "—" }, muted = it.description.isBlank())
    },
    TableColumn(str(S.desktop_po_total), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.poTotal, it.currency, state.projectCurrency)
    },
    TableColumn(str(S.desktop_invoiced), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.invoicedAmount, it.currency, state.projectCurrency)
    },
    TableColumn(str(S.desktop_accrual), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.accrualAmount, it.currency, state.projectCurrency)
    },
    TableColumn(str(S.desktop_used), ColumnWidth.Fixed(PAY_WIDTH)) { accrual ->
        CellText("${(accrual.used * PERCENT).toInt()}%", muted = true)
    },
    TableColumn(str(S.status), ColumnWidth.Weight(WEIGHT_NARROW)) { accrual ->
        ZillitStatusPill(
            label = accrual.status.label,
            tone = if (accrual.status == AccrualStatus.Reversed) StatusTone.Neutral else StatusTone.Pending,
        )
    },
)

// Credit notes --------------------------------------------------------------

/**
 * Credit notes and disputes — the web's `CreditsPage`.
 *
 * One record with two shapes: a credit note reduces what is owed once
 * applied, a dispute holds the argument until it is resolved. Each row's
 * button is what its status allows, which is the web's own mapping.
 */
@Composable
private fun ColumnScope.CreditNotesPage(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
    searchFocus: FocusRequester,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = state.search,
            onValueChange = { onEvent(InvoicesEvent.Search(it)) },
            placeholder = str(S.desktop_inv_search_ref_vendor_reason),
            modifier = Modifier.width(SEARCH_WIDTH).focusRequester(searchFocus),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CreditNoteFilter.entries.forEach { filter ->
            ZillitChoiceChip(
                label = filter.label,
                selected = state.creditNoteFilter == filter,
                onClick = { onEvent(InvoicesEvent.SelectCreditNoteFilter(filter)) },
            )
        }
    }
    val rows = state.shownCreditNotes
    TableCard(
        title = str(S.desktop_credit_notes),
        icon = ZillitIcons.ArrowLeft,
        meta = countMeta(rows.size, S.desktop_credit_note_count_one, S.desktop_credit_note_count_other),
    ) {
        ZillitDataTable(
            rows = rows,
            columns = creditNoteColumns(state, onEvent),
            key = { it.id },
            emptyTitle = str(S.desktop_inv_no_credit_notes),
            emptyMessage = str(S.desktop_inv_credit_notes_empty_message),
            loading = state.creditNotesLoading && rows.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun creditNoteColumns(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
): List<TableColumn<CreditNote>> = listOf(
    TableColumn(str(S.desktop_ref), ColumnWidth.Weight(1f)) { CellText(it.reference.ifBlank { "—" }) },
    TableColumn(str(S.type), ColumnWidth.Fixed(PAY_WIDTH)) {
        ZillitStatusPill(
            label = it.type.label,
            tone = if (it.type == CreditNoteType.Dispute) StatusTone.Rejected else StatusTone.Progress,
        )
    },
    TableColumn(str(S.ah_lbl_vendor), ColumnWidth.Weight(WEIGHT_MEDIUM)) {
        CellText(it.vendorName.ifBlank { str(S.desktop_unknown) })
    },
    TableColumn(str(S.reason), ColumnWidth.Weight(WEIGHT_WIDEST)) {
        CellText(it.reason.ifBlank { it.description }.ifBlank { "—" }, muted = it.reason.isBlank())
    },
    TableColumn(str(S.amount), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.grossAmount, it.currency, state.projectCurrency)
    },
    TableColumn(
        str(S.desktop_against),
        ColumnWidth.Weight(1f),
    ) { CellText(it.againstInvoice.ifBlank { "—" }, muted = true) },
    TableColumn(str(S.date), ColumnWidth.Fixed(DUE_WIDTH)) {
        CellText(InvoiceFormat.date(it.effectiveDateMs), muted = true)
    },
    TableColumn(str(S.status), ColumnWidth.Weight(WEIGHT_NARROW)) {
        ZillitStatusPill(label = it.status.label, tone = it.status.tone())
    },
    TableColumn("", ColumnWidth.Fixed(ACTIONS_WIDTH)) { note ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            ZillitButton(
                text = note.status.action,
                onClick = { onEvent(InvoicesEvent.ActOnCreditNote(note)) },
                variant = if (note.status.isActionable) ButtonVariant.Secondary else ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = note.status.isActionable && !state.busy,
            )
        }
    },
)

/** The web's per-status colour. */
private fun CreditNoteStatus.tone(): StatusTone = when (this) {
    CreditNoteStatus.Applied -> StatusTone.Done
    CreditNoteStatus.Pending -> StatusTone.Pending
    CreditNoteStatus.Disputed -> StatusTone.Rejected
    CreditNoteStatus.Resolved -> StatusTone.Ready
}

// Creditors -----------------------------------------------------------------

/**
 * What the production owes, by vendor and by age — the web's `CreditorsPage`.
 *
 * The buckets are measured from the invoice date, not the due date: this
 * report asks how long the money has been owed, not how late it is.
 */
@Composable
private fun ColumnScope.CreditorsPage(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
    nowMs: Long,
    searchFocus: FocusRequester,
) {
    val rows = Creditors.rows(
        invoices = state.shownInvoices,
        nowMs = nowMs,
        nameOf = { state.vendorName(it) },
        termsOf = { state.vendors[it.vendorId]?.slaLabel },
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = state.search,
            onValueChange = { onEvent(InvoicesEvent.Search(it)) },
            placeholder = str(S.desktop_search_vendor),
            modifier = Modifier.width(SEARCH_WIDTH).focusRequester(searchFocus),
        )
    }
    CreditorTotals(state, rows)
    AgedDebtTrend(Creditors.trend(state.shownInvoices, nowMs))
    TableCard(
        title = str(S.desktop_vendor_balances),
        icon = ZillitIcons.Bank,
        meta = countMeta(rows.size, S.desktop_vendor_count_one, S.ah_run_detail_summary_vendors),
    ) {
        ZillitDataTable(
            rows = rows,
            columns = creditorColumns(state),
            key = { it.vendor },
            emptyTitle = str(S.desktop_payroll_nothing_outstanding),
            emptyMessage = str(S.desktop_inv_creditors_empty_message),
            loading = state.loading && rows.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Six weeks of what was owed, by age — the web's "Aged Debt Trend".
 *
 * Three bars a week rather than a stack, as the web draws it: a stack hides
 * the one number this chart exists for, which is whether the oldest bucket is
 * growing.
 */
@Composable
private fun ColumnScope.AgedDebtTrend(weeks: List<AgeingWeek>) {
    if (weeks.none { it.total > 0 }) return
    val tallest = weeks.maxOf { it.tallest }.takeIf { it > 0 } ?: return
    ZillitSectionCard(
        modifier = Modifier.fillMaxWidth(),
        title = str(S.desktop_inv_aged_debt_trend_title),
        icon = ZillitIcons.BarChart,
        meta = trendWord(weeks),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(TREND_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.Bottom,
        ) {
            weeks.forEach { week -> TrendWeek(week, tallest, Modifier.weight(1f)) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(ZillitTheme.colors.border))
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            AgeingBucket.entries.forEach { bucket -> LegendDot(bucket) }
        }
    }
}

/** "Trend: rising" / "falling" — measured on the oldest bucket, which is the worry. */
private fun trendWord(weeks: List<AgeingWeek>): String {
    val first = weeks.first().days60
    val last = weeks.last().days60
    return when {
        last > first -> str(S.desktop_trend_rising)
        last < first -> str(S.desktop_trend_falling)
        else -> str(S.desktop_trend_flat)
    }
}

@Composable
private fun TrendWeek(week: AgeingWeek, tallest: Double, modifier: Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        Row(
            // Bars share the week's slot rather than sitting at a fixed width:
            // three sticks in the middle of a wide pane read as a rendering
            // fault, not as a chart.
            modifier = Modifier.weight(1f).fillMaxWidth().widthIn(max = WEEK_MAX),
            horizontalArrangement = Arrangement.spacedBy(BAR_GAP, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.Bottom,
        ) {
            AgeingBucket.entries.forEach { bucket ->
                val amount = when (bucket) {
                    AgeingBucket.Current -> week.current
                    AgeingBucket.Days30 -> week.days30
                    AgeingBucket.Days60 -> week.days60
                }
                val share = (amount / tallest).coerceIn(0.0, 1.0)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(share.toFloat().coerceAtLeast(BAR_FLOOR))
                        .clip(ZillitTheme.shapes.small)
                        .background(bucket.barColour()),
                )
            }
        }
        ZillitText(
            text = week.label,
            style = ZillitTheme.typography.labelSmall,
            color = if (week.isNow) ZillitTheme.colors.accent else ZillitTheme.colors.textMuted,
            modifier = Modifier.padding(top = ZillitTheme.spacing.xs),
        )
    }
}

@Composable
private fun LegendDot(bucket: AgeingBucket) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(LEGEND_DOT).clip(ZillitTheme.shapes.small).background(bucket.barColour()))
        ZillitText(
            text = bucket.label,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/** The web's three colours: teal for current, amber at 30 days, red at 60. */
@Composable
private fun AgeingBucket.barColour(): Color = when (this) {
    AgeingBucket.Current -> ZillitTheme.colors.success
    AgeingBucket.Days30 -> ZillitTheme.colors.warning
    AgeingBucket.Days60 -> ZillitTheme.colors.danger
}

@Composable
private fun ColumnScope.CreditorTotals(state: InvoicesUiState, rows: List<CreditorRow>) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        val currency = rows.firstOrNull()?.currency.orEmpty().ifBlank { state.projectCurrency }
        ZillitStatTile(
            label = str(S.desktop_total_creditors),
            value = Money.format(rows.sumOf { it.total }, currency),
            sub = str(S.ah_run_detail_summary_vendors, rows.size),
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        ZillitStatTile(
            label = AgeingBucket.Current.label,
            value = Money.format(rows.sumOf { it.current }, currency),
            tone = StatusTone.Done,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        ZillitStatTile(
            label = AgeingBucket.Days30.label,
            value = Money.format(rows.sumOf { it.days30 }, currency),
            tone = StatusTone.Pending,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        ZillitStatTile(
            label = AgeingBucket.Days60.label,
            value = Money.format(rows.sumOf { it.days60 }, currency),
            tone = StatusTone.Rejected,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

private fun creditorColumns(state: InvoicesUiState): List<TableColumn<CreditorRow>> = listOf(
    TableColumn(str(S.ah_lbl_vendor), ColumnWidth.Weight(WEIGHT_MEDIUM)) { CellText(it.vendor) },
    TableColumn(str(S.desktop_terms), ColumnWidth.Fixed(PAY_WIDTH)) { CellText(it.terms, muted = true) },
    TableColumn(AgeingBucket.Current.label, ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.current, it.currency, state.projectCurrency)
    },
    TableColumn(AgeingBucket.Days30.label, ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.days30, it.currency, state.projectCurrency)
    },
    TableColumn(AgeingBucket.Days60.label, ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.days60, it.currency, state.projectCurrency)
    },
    TableColumn(str(S.ah_total_label), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.total, it.currency, state.projectCurrency)
    },
)

// Pre-approval --------------------------------------------------------------

/**
 * The pre-approval queue — the web's `MatchingPage`.
 *
 * Two statuses in one list: what is waiting to be checked, and what has been
 * held for query. A held row cannot be sent on, so the tick box and the bulk
 * bar leave it alone, exactly as the web's select-all does.
 */
@Composable
private fun ColumnScope.MatchingPage(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
    searchFocus: FocusRequester,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = state.search,
            onValueChange = { onEvent(InvoicesEvent.Search(it)) },
            placeholder = str(S.desktop_inv_search_pre_approval),
            modifier = Modifier.width(SEARCH_WIDTH).focusRequester(searchFocus),
        )
        ZillitStatusPill(label = str(S.desktop_inv_n_to_check, state.matchingCount), tone = StatusTone.Pending)
        ZillitStatusPill(label = str(S.desktop_inv_n_on_hold, state.heldCount), tone = StatusTone.Escalated)
    }
    if (state.selected.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(text = str(S.dd_n_selected, state.selected.size), style = ZillitTheme.typography.bodySmall)
            ZillitButton(
                text = str(S.av_send_for_approval),
                onClick = { onEvent(InvoicesEvent.SendToApproval(null)) },
                size = ButtonSize.Small,
                loading = state.busy,
            )
            ZillitButton(
                text = str(S.desktop_inv_hold_for_query),
                onClick = { onEvent(InvoicesEvent.StartHold(null)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = str(S.ah_clear),
                onClick = { onEvent(InvoicesEvent.ClearSelection) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
    }
    val rows = state.shownInvoices
    CountLine(rows.size)
    Box(Modifier.weight(1f).fillMaxWidth()) {
        ZillitDataTable(
            rows = rows,
            columns = matchingColumns(state, onEvent),
            key = { it.id },
            // The web opens the side-by-side review here, not the read-only
            // detail: this queue exists to judge an invoice against its order.
            onRowClick = { onEvent(InvoicesEvent.OpenReview(it)) },
            emptyTitle = str(S.desktop_inv_nothing_waiting_pre_approval),
            emptyMessage = str(S.desktop_inv_pre_approval_empty_message),
            loading = state.loading && rows.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun matchingColumns(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
): List<TableColumn<Invoice>> = listOf(
    TableColumn("", ColumnWidth.Fixed(TICK_WIDTH)) { invoice ->
        // A held invoice is not a candidate for the bulk actions.
        if (invoice.status != InvoiceStatus.Held) {
            ZillitCheckbox(
                checked = invoice.id in state.selected,
                onCheckedChange = { onEvent(InvoicesEvent.ToggleSelect(invoice.id)) },
            )
        }
    },
    TableColumn(str(S.ah_run_detail_col_invoice), ColumnWidth.Weight(1f)) { CellText(it.displayNumber) },
    TableColumn(str(S.ah_lbl_vendor), ColumnWidth.Weight(WEIGHT_MEDIUM)) { CellText(state.vendorName(it)) },
    TableColumn(str(S.description), ColumnWidth.Weight(WEIGHT_WIDEST)) {
        CellText(it.description.ifBlank { "—" }, muted = it.description.isBlank())
    },
    TableColumn(str(S.amount), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.grossAmount, it.currency, state.projectCurrency)
    },
    // A linked order shows as a tag; an unmatched row offers the server's
    // suggestions instead — the web's `POSuggestionDropdown`.
    TableColumn(str(S.desktop_po), ColumnWidth.Fixed(MATCH_PO_WIDTH)) { invoice ->
        if (invoice.hasPo) PoCell(invoice) else MatchButton(state, invoice, onEvent)
    },
    TableColumn(str(S.desktop_pay_method_title), ColumnWidth.Fixed(PAY_WIDTH)) {
        ZillitStatusPill(label = it.payMethod.label, tone = StatusTone.InTransit)
    },
    TableColumn(str(S.status), ColumnWidth.Weight(WEIGHT_NARROW)) { invoice ->
        ZillitStatusPill(label = invoice.statusLabel, tone = invoice.status.statusTone())
    },
    TableColumn("", ColumnWidth.Fixed(MATCH_ACTIONS_WIDTH)) { invoice ->
        MatchingActions(state, invoice, onEvent)
    },
)

/** The picker on an unmatched row, and the suggestions it drops. */
@Composable
private fun MatchButton(state: InvoicesUiState, invoice: Invoice, onEvent: (InvoicesEvent) -> Unit) {
    val picker = state.poPicker?.takeIf { it.invoice.id == invoice.id }
    Box {
        ZillitButton(
            text = str(S.desktop_match),
            onClick = { onEvent(InvoicesEvent.OpenPoSuggestions(invoice)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            enabled = !state.busy,
        )
        if (picker != null) PoSuggestionMenu(picker, state.projectCurrency, onEvent)
    }
}

/** What can be done to one row: held rows are released, the rest are sent on or held. */
@Composable
private fun MatchingActions(state: InvoicesUiState, invoice: Invoice, onEvent: (InvoicesEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (invoice.status == InvoiceStatus.Held) {
            ZillitButton(
                text = str(S.desktop_release),
                onClick = { onEvent(InvoicesEvent.Release(invoice)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
        } else if (invoice.hasPo) {
            // With an order behind it the decision belongs in the review,
            // where the two can be compared — the web's Review button.
            ZillitButton(
                text = str(S.av_review),
                onClick = { onEvent(InvoicesEvent.OpenReview(invoice)) },
                size = ButtonSize.Small,
                trailingIcon = ZillitIcons.ChevronRight,
                enabled = !state.busy,
            )
        } else {
            ZillitButton(
                text = str(S.send),
                onClick = { onEvent(InvoicesEvent.SendToApproval(invoice)) },
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
            ZillitButton(
                text = str(S.desktop_hold),
                onClick = { onEvent(InvoicesEvent.StartHold(invoice)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
        }
        if (invoice.poId.isNotBlank()) {
            ZillitIconButton(
                icon = ZillitIcons.Close,
                contentDescription = str(S.desktop_remove_the_po),
                onClick = { onEvent(InvoicesEvent.Unmatch(invoice)) },
                enabled = !state.busy,
            )
        }
    }
}

// Posted -------------------------------------------------------------------

/**
 * Invoices that have reached the ledger — the web's `PostedPage`.
 *
 * The same columns as the register, because it is the same question asked of
 * a later stage; the filter is the web's three, and the list is its own route
 * rather than a status query.
 */
@Composable
private fun ColumnScope.PostedPage(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
    nowMs: Long,
    searchFocus: FocusRequester,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = state.search,
            onValueChange = { onEvent(InvoicesEvent.Search(it)) },
            placeholder = str(S.desktop_inv_search_ref_supplier_description_po),
            modifier = Modifier.width(SEARCH_WIDTH).focusRequester(searchFocus),
        )
        val options = listOf<String?>(null) + state.departmentOptions
        ZillitSelect(
            value = state.registerDepartment,
            options = options,
            onSelect = { onEvent(InvoicesEvent.SelectRegisterDepartment(it)) },
            label = { id -> if (id == null) str(S.all_departments) else state.departmentName(id) },
            modifier = Modifier.width(DEPARTMENT_SELECT_WIDTH),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PostedFilter.entries.forEach { filter ->
            ZillitChoiceChip(
                label = filter.label,
                selected = state.postedFilter == filter,
                onClick = { onEvent(InvoicesEvent.SelectPostedFilter(filter)) },
            )
        }
    }
    val rows = state.shownInvoices
    CountLine(rows.size)
    Box(Modifier.weight(1f).fillMaxWidth()) {
        ZillitDataTable(
            rows = rows,
            columns = registerColumns(state, nowMs),
            key = { it.id },
            onRowClick = { onEvent(InvoicesEvent.Open(it)) },
            emptyTitle = str(S.desktop_board_nothing_posted_yet),
            loading = state.loading && rows.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
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

@Composable
private fun ColumnScope.InboxPage(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
    searchFocus: FocusRequester,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = state.search,
            onValueChange = { onEvent(InvoicesEvent.Search(it)) },
            placeholder = str(S.desktop_search_inbox),
            modifier = Modifier.width(SEARCH_WIDTH).focusRequester(searchFocus),
        )
    }
    val rows = state.shownInvoices
    CountLine(rows.size)
    Box(Modifier.weight(1f).fillMaxWidth()) {
        ZillitDataTable(
            rows = rows,
            columns = inboxColumns(state, onEvent),
            key = { it.id },
            onRowClick = { onEvent(InvoicesEvent.Open(it)) },
            emptyTitle = str(S.desktop_inv_inbox_empty),
            emptyMessage = str(S.desktop_inv_inbox_empty_message),
            loading = state.loading && rows.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun inboxColumns(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit): List<TableColumn<Invoice>> = listOf(
    TableColumn(str(S.ah_run_detail_col_invoice), ColumnWidth.Weight(1f)) { CellText(it.displayNumber) },
    TableColumn(str(S.ah_lbl_vendor), ColumnWidth.Weight(WEIGHT_MEDIUM)) { CellText(state.vendorName(it)) },
    TableColumn(str(S.description), ColumnWidth.Weight(WEIGHT_WIDEST)) {
        CellText(it.description.ifBlank { "—" }, muted = it.description.isBlank())
    },
    TableColumn(str(S.desktop_gross), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.grossAmount, it.currency, state.projectCurrency)
    },
    TableColumn(str(S.desktop_pay_method_title), ColumnWidth.Fixed(PAY_WIDTH)) {
        ZillitStatusPill(label = it.payMethod.label, tone = StatusTone.InTransit)
    },
    poColumn(),
    TableColumn(str(S.status), ColumnWidth.Fixed(PO_WIDTH)) { invoice ->
        if (invoice.ocrConfidence != null) {
            ZillitStatusPill(label = str(S.desktop_ocr), tone = StatusTone.Escalated)
        } else {
            ZillitStatusPill(label = str(S.continue_new), tone = StatusTone.Pending)
        }
    },
    TableColumn("", ColumnWidth.Fixed(ACTIONS_WIDTH)) { invoice ->
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            if (InvoiceRules.canDeleteInbox(invoice, state.viewer)) {
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = str(S.ah_delete_invoice),
                    tint = ZillitTheme.colors.danger,
                    onClick = { onEvent(InvoicesEvent.RequestDelete(invoice)) },
                )
            }
        }
    },
)

// Approval queue -----------------------------------------------------------

@Composable
private fun ColumnScope.ApprovalPage(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
    searchFocus: FocusRequester,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = state.search,
            onValueChange = { onEvent(InvoicesEvent.Search(it)) },
            placeholder = str(S.desktop_search_queue),
            modifier = Modifier.width(SEARCH_WIDTH).focusRequester(searchFocus),
        )
        ZillitStatusPill(label = str(S.desktop_inv_n_awaiting, state.awaitingCount), tone = StatusTone.Pending)
        ZillitStatusPill(label = str(S.desktop_inv_n_approved, state.approvedCount), tone = StatusTone.Done)
    }
    val rows = state.shownInvoices
    val canBatch = rows.any {
        it.id in state.selected && ApprovalChain.canApprove(it, state.tiersOf(it), state.viewer.userId)
    }
    if (state.selected.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ZillitTheme.colors.accentSoft, ZillitTheme.shapes.medium)
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = str(S.dd_n_selected, state.selected.size),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = str(S.ah_clear),
                onClick = { onEvent(InvoicesEvent.ClearSelection) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = str(S.desktop_approve_selected),
                onClick = { onEvent(InvoicesEvent.ApproveSelected) },
                size = ButtonSize.Small,
                enabled = canBatch && !state.busy,
                loading = state.busy,
            )
        }
    } else {
        CountLine(rows.size)
    }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        ZillitDataTable(
            rows = rows,
            columns = approvalColumns(state, onEvent),
            key = { it.id },
            onRowClick = { onEvent(InvoicesEvent.Open(it)) },
            emptyTitle = str(S.desktop_inv_nothing_awaiting_approval),
            loading = state.loading && rows.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun approvalColumns(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
): List<TableColumn<Invoice>> = listOf(
    TableColumn<Invoice>("", ColumnWidth.Fixed(CHECK_WIDTH)) { invoice ->
        val tiers = state.tiersOf(invoice)
        if (ApprovalChain.canApprove(invoice, tiers, state.viewer.userId)) {
            ZillitCheckbox(
                checked = invoice.id in state.selected,
                onCheckedChange = { onEvent(InvoicesEvent.ToggleSelect(invoice.id)) },
            )
        }
    },
) + leadingColumns(state) + poColumn() + approvalColumn(state, queue = true) + slaColumn(state) +
    TableColumn(
        str(S.txt_action),
        ColumnWidth.Fixed(QUEUE_ACTION_WIDTH),
    ) { invoice -> QueueActions(state, invoice, onEvent) }

@Composable
private fun QueueActions(state: InvoicesUiState, invoice: Invoice, onEvent: (InvoicesEvent) -> Unit) {
    val tiers = state.tiersOf(invoice)
    val viewer = state.viewer
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            InvoiceRules.showOverrideAndPay(invoice, viewer) && !invoice.isApproved -> ZillitButton(
                text = str(S.desktop_override_and_pay),
                onClick = { onEvent(InvoicesEvent.OverrideAndPay(invoice)) },
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
            ApprovalChain.canApprove(invoice, tiers, viewer.userId) -> ZillitButton(
                text = str(S.approve),
                onClick = { onEvent(InvoicesEvent.Approve(invoice)) },
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
        }
        if (InvoiceRules.showOverride(invoice, tiers, viewer)) {
            ZillitButton(
                text = str(S.dm_nom_table_override),
                onClick = { onEvent(InvoicesEvent.Override(invoice)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
        }
        if (InvoiceRules.showChase(invoice, tiers, viewer)) {
            val chased = invoice.id in state.chased
            ZillitButton(
                text = if (chased) str(S.desktop_chased) else str(S.dm_row_action_chase),
                onClick = { onEvent(InvoicesEvent.Chase(invoice)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = !chased,
            )
        }
    }
}

internal val DEPARTMENT_SELECT_WIDTH = 220.dp
internal val DUE_WIDTH = 110.dp
internal val PAY_WIDTH = 120.dp
internal const val PERCENT = 100
internal val TICK_WIDTH = 40.dp
private val TREND_HEIGHT = 170.dp
private val WEEK_MAX = 120.dp
private val BAR_GAP = 4.dp
private val LEGEND_DOT = 10.dp
private const val BAR_FLOOR = 0.015f
private val MATCH_ACTIONS_WIDTH = 180.dp
private val MATCH_PO_WIDTH = 104.dp
private val URGENT_STATUS_WIDTH = 200.dp
private val CHECK_WIDTH = 40.dp
private val QUEUE_ACTION_WIDTH = 250.dp
