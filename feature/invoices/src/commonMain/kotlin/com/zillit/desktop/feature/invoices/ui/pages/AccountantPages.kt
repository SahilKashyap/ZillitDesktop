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
        AccountantPage.Reports -> ComingSoon("Reports")
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
            placeholder = "Search ref, supplier, description, PO",
            modifier = Modifier.width(SEARCH_WIDTH).focusRequester(searchFocus),
        )
        val options = listOf<String?>(null) + state.departmentOptions
        ZillitSelect(
            value = state.registerDepartment,
            options = options,
            onSelect = { onEvent(InvoicesEvent.SelectRegisterDepartment(it)) },
            label = { id -> if (id == null) "All departments" else state.departmentName(id) },
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
        title = "Invoice Register",
        icon = ZillitIcons.Ledger,
        meta = countMeta(rows.size, "invoice"),
    ) {
        ZillitDataTable(
            rows = rows,
            columns = registerColumns(state, nowMs),
            key = { it.id },
            onRowClick = { onEvent(InvoicesEvent.Open(it)) },
            emptyTitle = "No invoices match",
            loading = state.loading && rows.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun registerColumns(state: InvoicesUiState, nowMs: Long): List<TableColumn<Invoice>> = listOf(
    TableColumn("Invoice", ColumnWidth.Weight(1f)) { CellText(it.displayNumber) },
    TableColumn("Vendor", ColumnWidth.Weight(WEIGHT_MEDIUM)) { CellText(state.vendorName(it)) },
    TableColumn("Description", ColumnWidth.Weight(WEIGHT_WIDEST)) {
        CellText(it.description.ifBlank { "—" }, muted = it.description.isBlank())
    },
    poColumn(),
    TableColumn("Department", ColumnWidth.Weight(1f)) { CellText(state.departmentName(it.departmentId), muted = true) },
    // Fixed, not weighted: the urgent badge reads "No PO · Urgent Wire
    // Request", and a weighted column clips it to "No PO · Urgent Wir…" —
    // which drops the one word that says what the row is.
    TableColumn("Status", ColumnWidth.Fixed(URGENT_STATUS_WIDTH)) { invoice ->
        if (invoice.payMethod.isUrgent && invoice.status != InvoiceStatus.Override) {
            BadgePill(InvoiceRules.urgentBadge(invoice))
        } else {
            ZillitStatusPill(label = invoice.statusLabel, tone = invoice.status.statusTone())
        }
    },
    TableColumn("Gross", ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.grossAmount, it.currency, state.projectCurrency)
    },
    TableColumn("Due", ColumnWidth.Fixed(DUE_WIDTH)) { invoice ->
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
            placeholder = "Search PO, vendor, description",
            modifier = Modifier.width(SEARCH_WIDTH).focusRequester(searchFocus),
        )
        val options = listOf<String?>(null) + state.accruals.map { it.departmentId }.filter { it.isNotBlank() }
            .distinct()
        ZillitSelect(
            value = state.registerDepartment,
            options = options,
            onSelect = { onEvent(InvoicesEvent.SelectRegisterDepartment(it)) },
            label = { id -> if (id == null) "All departments" else state.departmentName(id) },
            modifier = Modifier.width(DEPARTMENT_SELECT_WIDTH),
        )
        ZillitButton(
            text = "Regenerate All",
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
        title = "Accruals",
        icon = ZillitIcons.Ledger,
        meta = countMeta(rows.size, "accrual"),
    ) {
        ZillitDataTable(
            rows = rows,
            columns = accrualColumns(state),
            key = { it.id },
            emptyTitle = "Nothing to accrue",
            emptyMessage = "Orders with spend still to be invoiced appear here.",
            loading = state.accrualsLoading && rows.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun accrualColumns(state: InvoicesUiState): List<TableColumn<Accrual>> = listOf(
    TableColumn("PO", ColumnWidth.Weight(1f)) { CellText(it.poNumber.ifBlank { "—" }) },
    TableColumn("Vendor", ColumnWidth.Weight(WEIGHT_MEDIUM)) { CellText(it.vendorName.ifBlank { "Unknown" }) },
    TableColumn("Description", ColumnWidth.Weight(WEIGHT_WIDEST)) {
        CellText(it.description.ifBlank { "—" }, muted = it.description.isBlank())
    },
    TableColumn("PO Total", ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.poTotal, it.currency, state.projectCurrency)
    },
    TableColumn("Invoiced", ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.invoicedAmount, it.currency, state.projectCurrency)
    },
    TableColumn("Accrual", ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.accrualAmount, it.currency, state.projectCurrency)
    },
    TableColumn("Used", ColumnWidth.Fixed(PAY_WIDTH)) { accrual ->
        CellText("${(accrual.used * PERCENT).toInt()}%", muted = true)
    },
    TableColumn("Status", ColumnWidth.Weight(WEIGHT_NARROW)) { accrual ->
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
            placeholder = "Search ref, vendor, reason",
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
        title = "Credit Notes",
        icon = ZillitIcons.ArrowLeft,
        meta = countMeta(rows.size, "credit note"),
    ) {
        ZillitDataTable(
            rows = rows,
            columns = creditNoteColumns(state, onEvent),
            key = { it.id },
            emptyTitle = "No credit notes",
            emptyMessage = "Credit notes and disputes raised against a vendor appear here.",
            loading = state.creditNotesLoading && rows.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun creditNoteColumns(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
): List<TableColumn<CreditNote>> = listOf(
    TableColumn("Ref", ColumnWidth.Weight(1f)) { CellText(it.reference.ifBlank { "—" }) },
    TableColumn("Type", ColumnWidth.Fixed(PAY_WIDTH)) {
        ZillitStatusPill(
            label = it.type.label,
            tone = if (it.type == CreditNoteType.Dispute) StatusTone.Rejected else StatusTone.Progress,
        )
    },
    TableColumn("Vendor", ColumnWidth.Weight(WEIGHT_MEDIUM)) {
        CellText(it.vendorName.ifBlank { "Unknown" })
    },
    TableColumn("Reason", ColumnWidth.Weight(WEIGHT_WIDEST)) {
        CellText(it.reason.ifBlank { it.description }.ifBlank { "—" }, muted = it.reason.isBlank())
    },
    TableColumn("Amount", ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.grossAmount, it.currency, state.projectCurrency)
    },
    TableColumn("Against", ColumnWidth.Weight(1f)) { CellText(it.againstInvoice.ifBlank { "—" }, muted = true) },
    TableColumn("Date", ColumnWidth.Fixed(DUE_WIDTH)) {
        CellText(InvoiceFormat.date(it.effectiveDateMs), muted = true)
    },
    TableColumn("Status", ColumnWidth.Weight(WEIGHT_NARROW)) {
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
            placeholder = "Search vendor",
            modifier = Modifier.width(SEARCH_WIDTH).focusRequester(searchFocus),
        )
    }
    CreditorTotals(state, rows)
    AgedDebtTrend(Creditors.trend(state.shownInvoices, nowMs))
    TableCard(
        title = "Vendor Balances",
        icon = ZillitIcons.Bank,
        meta = countMeta(rows.size, "vendor"),
    ) {
        ZillitDataTable(
            rows = rows,
            columns = creditorColumns(state),
            key = { it.vendor },
            emptyTitle = "Nothing outstanding",
            emptyMessage = "Approved and entered invoices that are not paid yet appear here.",
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
        title = "Aged Debt Trend — Last 6 Weeks",
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
        last > first -> "Trend: rising"
        last < first -> "Trend: falling"
        else -> "Trend: flat"
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
            label = "Total Creditors",
            value = Money.format(rows.sumOf { it.total }, currency),
            sub = "${rows.size} vendors",
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
    TableColumn("Vendor", ColumnWidth.Weight(WEIGHT_MEDIUM)) { CellText(it.vendor) },
    TableColumn("Terms", ColumnWidth.Fixed(PAY_WIDTH)) { CellText(it.terms, muted = true) },
    TableColumn(AgeingBucket.Current.label, ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.current, it.currency, state.projectCurrency)
    },
    TableColumn(AgeingBucket.Days30.label, ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.days30, it.currency, state.projectCurrency)
    },
    TableColumn(AgeingBucket.Days60.label, ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.days60, it.currency, state.projectCurrency)
    },
    TableColumn("Total", ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
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
            placeholder = "Search pre-approval",
            modifier = Modifier.width(SEARCH_WIDTH).focusRequester(searchFocus),
        )
        ZillitStatusPill(label = "${state.matchingCount} to check", tone = StatusTone.Pending)
        ZillitStatusPill(label = "${state.heldCount} on hold", tone = StatusTone.Escalated)
    }
    if (state.selected.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(text = "${state.selected.size} selected", style = ZillitTheme.typography.bodySmall)
            ZillitButton(
                text = "Send for approval",
                onClick = { onEvent(InvoicesEvent.SendToApproval(null)) },
                size = ButtonSize.Small,
                loading = state.busy,
            )
            ZillitButton(
                text = "Hold for query",
                onClick = { onEvent(InvoicesEvent.StartHold(null)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = "Clear",
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
            emptyTitle = "Nothing waiting on pre-approval",
            emptyMessage = "Invoices land here once they are entered, and leave when they go for approval.",
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
    TableColumn("Invoice", ColumnWidth.Weight(1f)) { CellText(it.displayNumber) },
    TableColumn("Vendor", ColumnWidth.Weight(WEIGHT_MEDIUM)) { CellText(state.vendorName(it)) },
    TableColumn("Description", ColumnWidth.Weight(WEIGHT_WIDEST)) {
        CellText(it.description.ifBlank { "—" }, muted = it.description.isBlank())
    },
    TableColumn("Amount", ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.grossAmount, it.currency, state.projectCurrency)
    },
    // A linked order shows as a tag; an unmatched row offers the server's
    // suggestions instead — the web's `POSuggestionDropdown`.
    TableColumn("PO", ColumnWidth.Fixed(MATCH_PO_WIDTH)) { invoice ->
        if (invoice.hasPo) PoCell(invoice) else MatchButton(state, invoice, onEvent)
    },
    TableColumn("Pay Method", ColumnWidth.Fixed(PAY_WIDTH)) {
        ZillitStatusPill(label = it.payMethod.label, tone = StatusTone.InTransit)
    },
    TableColumn("Status", ColumnWidth.Weight(WEIGHT_NARROW)) { invoice ->
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
            text = "Match",
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
                text = "Release",
                onClick = { onEvent(InvoicesEvent.Release(invoice)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
        } else if (invoice.hasPo) {
            // With an order behind it the decision belongs in the review,
            // where the two can be compared — the web's Review button.
            ZillitButton(
                text = "Review",
                onClick = { onEvent(InvoicesEvent.OpenReview(invoice)) },
                size = ButtonSize.Small,
                trailingIcon = ZillitIcons.ChevronRight,
                enabled = !state.busy,
            )
        } else {
            ZillitButton(
                text = "Send",
                onClick = { onEvent(InvoicesEvent.SendToApproval(invoice)) },
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
            ZillitButton(
                text = "Hold",
                onClick = { onEvent(InvoicesEvent.StartHold(invoice)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
        }
        if (invoice.poId.isNotBlank()) {
            ZillitIconButton(
                icon = ZillitIcons.Close,
                contentDescription = "Remove the PO",
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
            placeholder = "Search ref, supplier, description, PO",
            modifier = Modifier.width(SEARCH_WIDTH).focusRequester(searchFocus),
        )
        val options = listOf<String?>(null) + state.departmentOptions
        ZillitSelect(
            value = state.registerDepartment,
            options = options,
            onSelect = { onEvent(InvoicesEvent.SelectRegisterDepartment(it)) },
            label = { id -> if (id == null) "All departments" else state.departmentName(id) },
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
            emptyTitle = "Nothing posted yet",
            loading = state.loading && rows.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** The web shows this here too — the section is being reworked on both clients. */
@Composable
private fun ColumnScope.ComingSoon(title: String) {
    ZillitEmptyState(
        title = "$title — Coming Soon",
        message = "This section is being reworked. It'll be available here soon.",
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
            placeholder = "Search inbox",
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
            emptyTitle = "The inbox is empty",
            emptyMessage = "Uploaded and entered invoices land here until they are processed.",
            loading = state.loading && rows.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun inboxColumns(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit): List<TableColumn<Invoice>> = listOf(
    TableColumn("Invoice", ColumnWidth.Weight(1f)) { CellText(it.displayNumber) },
    TableColumn("Vendor", ColumnWidth.Weight(WEIGHT_MEDIUM)) { CellText(state.vendorName(it)) },
    TableColumn("Description", ColumnWidth.Weight(WEIGHT_WIDEST)) {
        CellText(it.description.ifBlank { "—" }, muted = it.description.isBlank())
    },
    TableColumn("Gross", ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.grossAmount, it.currency, state.projectCurrency)
    },
    TableColumn("Pay Method", ColumnWidth.Fixed(PAY_WIDTH)) {
        ZillitStatusPill(label = it.payMethod.label, tone = StatusTone.InTransit)
    },
    poColumn(),
    TableColumn("Status", ColumnWidth.Fixed(PO_WIDTH)) { invoice ->
        if (invoice.ocrConfidence != null) {
            ZillitStatusPill(label = "OCR", tone = StatusTone.Escalated)
        } else {
            ZillitStatusPill(label = "New", tone = StatusTone.Pending)
        }
    },
    TableColumn("", ColumnWidth.Fixed(ACTIONS_WIDTH)) { invoice ->
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            if (InvoiceRules.canDeleteInbox(invoice, state.viewer)) {
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = "Delete invoice",
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
            placeholder = "Search queue",
            modifier = Modifier.width(SEARCH_WIDTH).focusRequester(searchFocus),
        )
        ZillitStatusPill(label = "${state.awaitingCount} awaiting", tone = StatusTone.Pending)
        ZillitStatusPill(label = "${state.approvedCount} approved", tone = StatusTone.Done)
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
                text = "${state.selected.size} selected",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = "Clear",
                onClick = { onEvent(InvoicesEvent.ClearSelection) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = "Approve Selected",
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
            emptyTitle = "Nothing awaiting approval",
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
    TableColumn("Action", ColumnWidth.Fixed(QUEUE_ACTION_WIDTH)) { invoice -> QueueActions(state, invoice, onEvent) }

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
                text = "Override & Pay",
                onClick = { onEvent(InvoicesEvent.OverrideAndPay(invoice)) },
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
            ApprovalChain.canApprove(invoice, tiers, viewer.userId) -> ZillitButton(
                text = "Approve",
                onClick = { onEvent(InvoicesEvent.Approve(invoice)) },
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
        }
        if (InvoiceRules.showOverride(invoice, tiers, viewer)) {
            ZillitButton(
                text = "Override",
                onClick = { onEvent(InvoicesEvent.Override(invoice)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
        }
        if (InvoiceRules.showChase(invoice, tiers, viewer)) {
            val chased = invoice.id in state.chased
            ZillitButton(
                text = if (chased) "Chased" else "Chase",
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
