// Creditors Control and Posted Invoices; one composable per page piece.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.AgeingBucket
import com.zillit.desktop.feature.invoices.domain.AgeingWeek
import com.zillit.desktop.feature.invoices.domain.CreditorAgeing
import com.zillit.desktop.feature.invoices.domain.CreditorFilter
import com.zillit.desktop.feature.invoices.domain.CreditorRow
import com.zillit.desktop.feature.invoices.domain.CreditorSort
import com.zillit.desktop.feature.invoices.domain.Creditors
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.MoneyTotal
import com.zillit.desktop.feature.invoices.ui.EntryEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.PaymentsEvent
import com.zillit.desktop.feature.invoices.ui.PostedFilter
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

// Creditors -----------------------------------------------------------------

/**
 * What the production owes, by vendor and by age — the web's `CreditorsPage`,
 * in its order: the four tiles, the aged-debt trend, the search and the three
 * selects, then the vendor balances.
 *
 * The tiles and the trend are the whole open set; the search and the selects
 * narrow only the balance rows, and the search matches the vendor's name
 * alone (`CreditorsPage.jsx:315-344`). Every figure is totalled in its own
 * currency, or converted to the project's when a set mixes them.
 */
@Composable
internal fun ColumnScope.CreditorsPage(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
    nowMs: Long,
    searchFocus: FocusRequester,
) {
    val owed = Creditors.owed(state.invoices)
    val balances = Creditors.rows(invoices = owed, nowMs = nowMs, nameOf = { creditorName(state, it) })
    CreditorTiles(state, owed, balances.size, nowMs)
    AgedDebtTrend(Creditors.trend(owed, nowMs))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = state.search,
            onValueChange = { onEvent(InvoicesEvent.Search(it)) },
            placeholder = str(S.desktop_inv_search_vendors_amounts),
            modifier = Modifier.weight(1f).focusRequester(searchFocus),
        )
        ZillitSelect(
            value = state.creditors.filter,
            options = CreditorFilter.entries,
            onSelect = { onEvent(PaymentsEvent.SelectCreditorFilter(it)) },
            label = { it.label(balances.size) },
            modifier = Modifier.width(CREDITOR_SELECT_WIDTH),
        )
        ZillitSelect(
            value = state.creditors.sort,
            options = CreditorSort.entries,
            onSelect = { onEvent(PaymentsEvent.SelectCreditorSort(it)) },
            label = { it.label },
            modifier = Modifier.width(CREDITOR_SELECT_WIDTH),
        )
        ZillitSelect(
            value = state.creditors.ageing,
            options = CreditorAgeing.entries,
            onSelect = { onEvent(PaymentsEvent.SelectCreditorAgeing(it)) },
            label = { it.label },
            modifier = Modifier.width(AGEING_SELECT_WIDTH),
        )
    }
    val rows = Creditors.shown(
        rows = balances,
        search = state.search,
        filter = state.creditors.filter,
        ageing = state.creditors.ageing,
        sort = state.creditors.sort,
    )
    TableCard(
        title = str(S.desktop_vendor_balances),
        icon = ZillitIcons.Bank,
        meta = if (state.loading && owed.isEmpty()) {
            null
        } else {
            countMeta(rows.size, S.desktop_inv_one_supplier, S.desktop_inv_n_suppliers)
        },
    ) {
        ZillitDataTable(
            rows = rows,
            columns = creditorColumns(state),
            key = { it.vendor },
            emptyTitle = str(S.desktop_inv_no_vendor_balances),
            loading = state.loading && owed.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** The web groups by `supplier_name`, then the vendor's own name, then "Unknown Vendor". */
private fun creditorName(state: InvoicesUiState, invoice: Invoice): String =
    invoice.supplierName.ifBlank { null }
        ?: state.vendors[invoice.vendorId]?.name?.ifBlank { null }
        ?: str(S.desktop_inv_unknown_vendor)

/** Total Creditors, Current, 30 Days and 60+ Days — with the vendor count and each bucket's share. */
@Composable
private fun CreditorTiles(state: InvoicesUiState, owed: List<Invoice>, vendorCount: Int, nowMs: Long) {
    val stats = Creditors.stats(owed, vendorCount, nowMs)
    StatGrid(
        columns = CREDITOR_TILES,
        tiles = listOf(
            {
                MoneyStat(
                    label = str(S.desktop_total_creditors),
                    total = state.rates.total(stats.total),
                    sub = countMeta(stats.vendorCount, S.desktop_vendor_count_one, S.ah_run_detail_summary_vendors),
                    tone = StatusTone.Pending,
                )
            },
            {
                MoneyStat(
                    label = AgeingBucket.Current.label,
                    total = state.rates.total(stats.current),
                    sub = str(S.docusign_percent_value, stats.currentPercent),
                    tone = StatusTone.Ready,
                )
            },
            {
                MoneyStat(
                    label = AgeingBucket.Days30.label,
                    total = state.rates.total(stats.days30),
                    sub = str(S.docusign_percent_value, stats.days30Percent),
                    tone = StatusTone.Pending,
                )
            },
            {
                MoneyStat(
                    label = AgeingBucket.Days60.label,
                    total = state.rates.total(stats.days60),
                    sub = str(S.docusign_percent_value, stats.days60Percent),
                    tone = StatusTone.Rejected,
                )
            },
        ),
    )
}

@Composable
private fun RowScope.MoneyStat(label: String, total: MoneyTotal, sub: String, tone: StatusTone?) {
    ZillitStatTile(
        label = label,
        value = total.text,
        sub = total.caveat?.let { "$sub · $it" } ?: sub,
        tone = tone,
        modifier = statTile,
    )
}

/**
 * "Aged Debt Trend — Last 6 Weeks": three bars a week, drawn even when all
 * are zero (a 2px floor), with the web's fixed "Trend: decreasing ↓" beside
 * the title (`CreditorsPage.jsx:372-416`).
 */
@Composable
private fun AgedDebtTrend(weeks: List<AgeingWeek>) {
    val tallest = weeks.maxOfOrNull { it.tallest }?.takeIf { it > 0 } ?: 1.0
    ZillitSectionCard(
        modifier = Modifier.fillMaxWidth(),
        title = str(S.desktop_inv_aged_debt_trend_title),
        icon = ZillitIcons.BarChart,
        action = {
            ZillitText(
                text = str(S.desktop_inv_trend_decreasing),
                style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                color = ZillitTheme.colors.success,
                maxLines = 1,
            )
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(TREND_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.Bottom,
        ) {
            weeks.forEach { week -> TrendWeek(week, tallest, Modifier.weight(1f)) }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            LegendDot(str(S.dv_current), AgeingBucket.Current)
            LegendDot(str(S.drive_expiry_30d), AgeingBucket.Days30)
            LegendDot(str(S.desktop_inv_legend_60_plus_days), AgeingBucket.Days60)
        }
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
                // A zero bar still shows as a sliver; a non-zero one is never thinner than a few pixels.
                val share = if (amount > 0) (amount / tallest).coerceIn(BAR_MIN, 1.0) else BAR_ZERO
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(share.toFloat())
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
private fun LegendDot(label: String, bucket: AgeingBucket) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(LEGEND_DOT).clip(ZillitTheme.shapes.small).background(bucket.barColour()))
        ZillitText(text = label, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
    }
}

/** The web's three colours: teal for current, amber at 30 days, red at 60. */
@Composable
private fun AgeingBucket.barColour(): Color = when (this) {
    AgeingBucket.Current -> ZillitTheme.colors.success
    AgeingBucket.Days30 -> ZillitTheme.colors.warning
    AgeingBucket.Days60 -> ZillitTheme.colors.danger
}

/** Vendor (with its CIS tag), Terms, Current, 30d, 60d+ (red once owed) and Total. */
private fun creditorColumns(state: InvoicesUiState): List<TableColumn<CreditorRow>> = listOf(
    TableColumn(str(S.ah_lbl_vendor), ColumnWidth.Weight(WEIGHT_WIDEST)) { row ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = row.vendor,
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            if (row.cis) ZillitStatusPill(label = str(S.desktop_inv_cis), tone = StatusTone.Done)
        }
    },
    TableColumn(str(S.desktop_terms), ColumnWidth.Fixed(PAY_WIDTH)) { CellText(it.terms, muted = true) },
    TableColumn(str(S.dv_current), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        AmountCell(state.rates.total(it.currentItems).text)
    },
    TableColumn(str(S.desktop_inv_col_30d), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        AmountCell(state.rates.total(it.days30Items).text)
    },
    TableColumn(str(S.desktop_inv_col_60d_plus), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) { row ->
        val owing = row.days60 > 0
        AmountCell(
            text = state.rates.total(row.days60Items).text,
            color = if (owing) ZillitTheme.colors.danger else ZillitTheme.colors.textMuted,
            bold = owing,
        )
    },
    TableColumn(str(S.ah_total_label), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        AmountCell(state.rates.total(it.allItems).text, bold = true)
    },
)

@Composable
private fun AmountCell(text: String, color: Color = ZillitTheme.colors.textPrimary, bold: Boolean = false) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.numeric.let { if (bold) it.copy(fontWeight = FontWeight.Bold) else it },
        color = color,
        maxLines = 1,
        textAlign = TextAlign.End,
        modifier = Modifier.fillMaxWidth(),
    )
}

// Posted -------------------------------------------------------------------

/**
 * Invoices that have reached the ledger — the web's `PostedPage`, in its
 * order: the four split tiles, the search, the status chips beside the
 * department select, then the "Posted Ledger" panel, newest posting first.
 * A row opens the coding screen frozen, as `/posted/:id` does on the web.
 */
@Composable
internal fun ColumnScope.PostedPage(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
    searchFocus: FocusRequester,
) {
    PostedTiles(state)
    ZillitSearchField(
        value = state.search,
        onValueChange = { onEvent(InvoicesEvent.Search(it)) },
        placeholder = str(S.desktop_inv_search_posted),
        modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
    )
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
        Box(Modifier.weight(1f))
        ZillitSelect(
            value = state.registerDepartment,
            options = listOf<String?>(null) + postedDepartments(state),
            onSelect = { onEvent(InvoicesEvent.SelectRegisterDepartment(it)) },
            label = { id -> if (id == null) str(S.all_departments) else state.departmentName(id) },
            modifier = Modifier.width(DEPARTMENT_SELECT_WIDTH),
        )
    }
    val rows = state.shownInvoices
    val loaded = state.invoices.size
    TableCard(
        title = str(S.desktop_inv_posted_ledger),
        icon = ZillitIcons.Check,
        // "Showing N of {total}" when the page stopped short of the ledger, else the rows on screen.
        meta = if (loaded < state.postedTotal) {
            str(S.desktop_payroll_showing_of, loaded, state.postedTotal)
        } else {
            str(S.ah_run_invoices_count, rows.size)
        },
    ) {
        ZillitDataTable(
            rows = rows,
            columns = postedColumns(state),
            key = { it.id },
            onRowClick = { onEvent(EntryEvent.Open(it, readOnly = true)) },
            emptyTitle = str(S.desktop_inv_no_posted_yet),
            loading = state.loading && rows.isEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The departments present in the posted set, in the admin's listing order;
 * one the listing does not know goes last, in the order it was met
 * (`PostedPage.jsx:249-266`).
 */
private fun postedDepartments(state: InvoicesUiState): List<String> {
    val present = state.invoices.map { it.departmentId }.filter { it.isNotBlank() }.distinct()
    val rank = state.departmentOrder.withIndex().associate { (index, id) -> id to index }
    return present.sortedBy { rank[it] ?: Int.MAX_VALUE }
}

/** The posted ledger's columns — a plain status pill and a plain due date, no urgency or overdue colour. */
private fun postedColumns(state: InvoicesUiState): List<TableColumn<Invoice>> = listOf(
    TableColumn(str(S.ah_run_detail_col_invoice), ColumnWidth.Weight(1f)) { CellText(it.displayNumber) },
    TableColumn(str(S.ah_lbl_vendor), ColumnWidth.Weight(WEIGHT_MEDIUM)) { CellText(postedSupplier(state, it)) },
    TableColumn(str(S.description), ColumnWidth.Weight(WEIGHT_WIDEST)) { CellText(it.description, muted = true) },
    TableColumn(str(S.desktop_po), ColumnWidth.Fixed(PO_WIDTH)) { invoice ->
        if (invoice.hasMatchedPo) {
            ZillitStatusPill(label = invoice.linkedPoLabel.orEmpty(), tone = StatusTone.InTransit)
        } else {
            ZillitStatusPill(label = str(S.desktop_no_po), tone = StatusTone.Rejected)
        }
    },
    TableColumn(str(S.department), ColumnWidth.Weight(1f)) {
        CellText(state.departmentName(it.departmentId), muted = true)
    },
    TableColumn(str(S.status), ColumnWidth.Fixed(POSTED_STATUS_WIDTH)) {
        ZillitStatusPill(label = it.statusLabel, tone = it.status.statusTone())
    },
    TableColumn(str(S.desktop_gross), ColumnWidth.Fixed(GROSS_WIDTH), numeric = true) {
        MoneyText(it.grossAmount, it.currency, state.projectCurrency)
    },
    TableColumn(str(S.desktop_due), ColumnWidth.Fixed(DUE_WIDTH), numeric = true) {
        AmountCell(postedDate(it.dueDateMs), color = ZillitTheme.colors.textMuted)
    },
)

/** `vendorMap[vendor_id] || description.split("–")[0].trim() || "Unknown"` (`PostedPage.jsx:142`). */
private fun postedSupplier(state: InvoicesUiState, invoice: Invoice): String =
    state.vendors[invoice.vendorId]?.name?.ifBlank { null }
        ?: invoice.description.substringBefore('–').trim().ifBlank { null }
        ?: str(S.desktop_unknown)

/** `DD Mon YYYY`, day padded — the web's `fmtDateOnly`; "—" without a date. */
private fun postedDate(ms: Long?): String {
    if (ms == null || ms <= 0) return "—"
    val d = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.UTC).date
    return "${d.day.toString().padStart(2, '0')} ${str(SHORT_MONTHS[d.month.ordinal])} ${d.year}"
}

/**
 * The Posted page's four cards — Awaiting Payment, Paid, and the Net / Tax
 * split — over the whole posted set, not the filtered one. A mixed-currency
 * sum is converted to the project's and says so. An invoice whose split was
 * never captured adds nothing to Net or Tax, and the sub-line counts them.
 */
@Composable
private fun PostedTiles(state: InvoicesUiState) {
    val all = state.invoices
    val awaiting = all.filter { it.status == InvoiceStatus.ReadyToPay }
    val paid = all.filter { it.status == InvoiceStatus.Paid }
    val split = all.count { it.netAmount != null || it.taxAmount != null }
    StatGrid(
        columns = POSTED_TILES,
        tiles = listOf(
            {
                MoneyStat(
                    label = str(S.desktop_inv_awaiting_payment),
                    total = state.rates.total(awaiting.map { it.grossAmount to it.currency }),
                    sub = str(S.desktop_payroll_ready_to_pay_count, awaiting.size),
                    tone = StatusTone.Ready,
                )
            },
            {
                MoneyStat(
                    label = str(S.desktop_paid),
                    total = state.rates.total(paid.map { it.grossAmount to it.currency }),
                    sub = str(S.desktop_inv_n_settled, paid.size),
                    tone = StatusTone.Done,
                )
            },
            {
                MoneyStat(
                    label = str(S.ah_lbl_net_total),
                    total = state.rates.total(all.map { (it.netAmount ?: 0.0) to it.currency }),
                    sub = str(S.desktop_inv_n_of_m_split, split, all.size),
                    tone = null,
                )
            },
            {
                MoneyStat(
                    label = str(S.desktop_inv_tax_total),
                    total = state.rates.total(all.map { (it.taxAmount ?: 0.0) to it.currency }),
                    sub = str(S.desktop_inv_recoverable_and_not),
                    tone = StatusTone.Pending,
                )
            },
        ),
    )
}

private val SHORT_MONTHS = listOf(
    S.desktop_month_short_jan, S.desktop_month_short_feb, S.desktop_month_short_mar,
    S.desktop_month_short_apr, S.desktop_month_short_may, S.desktop_month_short_jun,
    S.desktop_month_short_jul, S.desktop_month_short_aug, S.desktop_month_short_sep,
    S.desktop_month_short_oct, S.desktop_month_short_nov, S.desktop_month_short_dec,
)

private const val CREDITOR_TILES = 4
private const val POSTED_TILES = 4
private val CREDITOR_SELECT_WIDTH = 220.dp
private val AGEING_SELECT_WIDTH = 150.dp
private val POSTED_STATUS_WIDTH = 140.dp
private val TREND_HEIGHT = 170.dp
private val WEEK_MAX = 120.dp
private val BAR_GAP = 4.dp
private val LEGEND_DOT = 10.dp

/** 2px of a 140px chart, and 4px — the web's floors for an empty and a small bar. */
private const val BAR_ZERO = 0.015
private const val BAR_MIN = 0.03
