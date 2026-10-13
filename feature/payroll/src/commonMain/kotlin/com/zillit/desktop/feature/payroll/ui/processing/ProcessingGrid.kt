package com.zillit.desktop.feature.payroll.ui.processing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitMeter
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.OutstandingRow
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.ProcessingRow
import com.zillit.desktop.feature.payroll.domain.TimecardStatus
import com.zillit.desktop.feature.payroll.domain.payrollMoneyTotal
import com.zillit.desktop.feature.payroll.ui.PayrollEvent
import com.zillit.desktop.feature.payroll.ui.PayrollUiState
import com.zillit.desktop.feature.payroll.ui.ProcessingEvent
import com.zillit.desktop.feature.payroll.ui.ProcessingNav
import com.zillit.desktop.feature.payroll.ui.ProcessingView
import com.zillit.desktop.feature.payroll.ui.components.tone
import com.zillit.desktop.feature.payroll.ui.processingBucket

/** Every crew row of the processing week, placed on the week's days. */
internal fun PayrollUiState.processingRows(): List<ProcessingRow> {
    val week = processing.weekStarting ?: currentWeek
    return processing.timecards.map { ProcessingRow(it, week) }
}

/**
 * The rows the view shows: Day View only the crew working that day; then
 * the status nav, the department and the search (name, role, department).
 */
internal fun PayrollUiState.processingShownRows(): List<ProcessingRow> {
    val p = processing
    val query = p.search.trim().lowercase()
    return processingRows().filter { row ->
        val userId = row.timecard.userId
        (p.view != ProcessingView.Daily || !row.days[p.day].isOff) &&
            navMatches(p.nav, row.timecard.processingBucket) &&
            (p.department == null || people[userId]?.department.orEmpty() == p.department) &&
            (
                query.isEmpty() ||
                    "${nameOf(userId)} ${roleOf(userId)} ${departmentOf(userId)}".lowercase().contains(query)
            )
    }
}

private fun navMatches(nav: ProcessingNav, bucket: ProcessingNav?): Boolean = nav == ProcessingNav.All || bucket == nav

/**
 * Outstanding's crew: one row per person with unsettled weeks, heaviest gross
 * first, through the same status, department and search the other views use —
 * search matching the department's name too, as the web's does.
 */
internal fun PayrollUiState.outstandingShownRows(): List<OutstandingRow> {
    val week = processing.weekStarting ?: currentWeek
    val query = processing.search.trim().lowercase()
    val department = processing.department
    return OutstandingRow.of(processing.outstanding, week).filter { row ->
        row.status != TimecardStatus.Paid &&
            navMatches(processing.nav, row.weeks.firstOrNull()?.timecard?.processingBucket) &&
            (department == null || people[row.userId]?.department.orEmpty() == department) &&
            (
                query.isEmpty() ||
                    "${nameOf(row.userId)} ${roleOf(row.userId)} ${departmentOf(row.userId)}".lowercase()
                        .contains(query)
            )
    }
}

/** The nav's three counts — always the whole week's crew, working that day in Day View. */
internal data class ProcessingCounts(val all: Int, val pending: Int, val approved: Int)

internal fun PayrollUiState.processingCounts(): ProcessingCounts {
    val rows = processingRows().filter { processing.view != ProcessingView.Daily || !it.days[processing.day].isOff }
    return ProcessingCounts(
        all = rows.size,
        pending = rows.count { it.timecard.processingBucket == ProcessingNav.Pending },
        approved = rows.count { it.timecard.processingBucket == ProcessingNav.Approved },
    )
}

/** The departments with anyone in them, for the chips. */
internal fun PayrollUiState.processingDepartments(): List<Pair<String, Int>> =
    processingRows().groupBy { people[it.timecard.userId]?.department.orEmpty() }
        .map { (department, rows) -> department to rows.size }
        .sortedBy { labelOfDepartment(it.first) }

@Composable
internal fun ProcessingGrid(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit) {
    val view = state.processing.view
    val columns = when (view) {
        ProcessingView.Daily -> dayColumns(state, onEvent)
        ProcessingView.WeekToDate -> weekToDateColumns(state, onEvent)
        else -> weeklyColumns(state, onEvent)
    }
    val rows = state.processingShownRows()
    // The department header's money, per the view: Day View the day's gross,
    // the weekly total the week's net, and Week to Date none — as the web's.
    val money: ((List<ProcessingRow>) -> String)? = when (view) {
        ProcessingView.Daily -> { group ->
            moneyLabel(group.map { it.days[state.processing.day].total to it.timecard.currency }, state)
        }
        ProcessingView.WeekToDate -> null
        else -> { group -> moneyLabel(group.map { it.totalPay to it.timecard.currency }, state) }
    }
    GroupedTable(
        items = state.groupedUnderDepartments(
            rows = rows,
            key = { it.timecard.id },
            department = { state.people[it.timecard.userId]?.department.orEmpty() },
            money = money,
        ),
        columns = columns,
        onOpen = { onEvent(ProcessingEvent.OpenDrawer(it.timecard.id)) },
        isSelected = { it.timecard.id == state.processing.drawerId },
        loading = state.processing.loading || !state.metadataLoaded,
        emptyTitle = str(S.desktop_payroll_no_approved_this_week),
    )
}

@Composable
internal fun OutstandingGrid(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit) {
    GroupedTable(
        items = state.groupedUnderDepartments(
            rows = state.outstandingShownRows(),
            key = { it.id },
            department = { state.people[it.userId]?.department.orEmpty() },
            // Outstanding rows aggregate several weeks, each in its own currency.
            money = { group -> moneyLabel(group.map { it.totalMoney }, state) },
        ),
        columns = listOf(
            crewColumn(state) { it.userId },
            textColumn(str(S.dm_ds_unit_days), ColumnWidth.Fixed(DAYS_COLUMN), numeric = true) { "${it.days}d" },
            moneyColumn(str(S.desktop_payroll_basic_pay)) { it.basicMoney },
            moneyColumn(str(S.desktop_payroll_ots)) { it.otsMoney },
            moneyColumn(str(S.desktop_payroll_allowances_rental)) { it.allowancesMoney },
            moneyColumn(str(S.desktop_payroll_total_pay), bold = true) { it.totalMoney },
        ),
        onOpen = { onEvent(ProcessingEvent.OpenOutstanding(it.userId)) },
        isSelected = { false },
        loading = state.processing.outstandingLoading,
        emptyTitle = str(S.desktop_payroll_no_outstanding),
    )
}

/** A sum across crew rows, in the production's default where they differ — the tiles' own rule. */
private fun moneyLabel(entries: List<Pair<Double, String?>>, state: PayrollUiState): String =
    payrollMoneyTotal(entries, state.currencyRates).let { Money.format(it.first, it.second) }

/**
 * The rows under their department headers, or bare when one department is
 * chosen — the web draws a header only on "All".
 */
private fun <T> PayrollUiState.groupedUnderDepartments(
    rows: List<T>,
    key: (T) -> String,
    department: (T) -> String,
    money: ((List<T>) -> String)?,
): List<GridItem<T>> = if (processing.department == null) {
    groupedByDepartment(rows, key, department, { labelOfDepartment(it) }, money)
} else {
    ungrouped(rows, key)
}

/** [ZillitDataTable] over [GridItem]s: the columns draw crew rows only, a header spans the width. */
@Composable
private fun <T> GroupedTable(
    items: List<GridItem<T>>,
    columns: List<TableColumn<T>>,
    onOpen: (T) -> Unit,
    isSelected: (T) -> Boolean,
    loading: Boolean,
    emptyTitle: String,
) {
    val headerTint = ZillitTheme.colors.surfaceSunken
    ZillitDataTable(
        rows = items,
        columns = columns.map { column ->
            TableColumn<GridItem<T>>(column.header, column.width, column.numeric, column.headerContent) { item ->
                if (item is GridItem.Entry) column.cell(item.row)
            }
        },
        key = { it.key },
        onRowClick = { item -> if (item is GridItem.Entry) onOpen(item.row) },
        isSelected = { item -> item is GridItem.Entry && isSelected(item.row) },
        loading = loading,
        emptyTitle = emptyTitle,
        modifier = Modifier.fillMaxSize(),
        rowTint = { item -> if (item is GridItem.Header) headerTint else null },
        rowContent = { item -> (item as? GridItem.Header)?.let { header -> { DepartmentHeader(header) } } },
    )
}

/** `Camera  (12 crew)  ·  £14,200.00` across the row. */
@Composable
private fun RowScope.DepartmentHeader(header: GridItem.Header) {
    Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = header.label,
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        ZillitText(
            text = "(${str(S.desktop_payroll_crew_count, header.crew)})",
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
        header.money?.let {
            ZillitText(
                text = it,
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
                color = ZillitTheme.colors.accentText,
            )
        }
    }
}

// -- the sidebar's Week Summary -----------------------------------------------------------------

/** What a summary figure is, for the colour it is drawn in. */
internal enum class SummaryEmphasis { Plain, Gross, Approved, Overtime }

internal data class SummaryLine(
    val label: String,
    val value: String,
    val emphasis: SummaryEmphasis = SummaryEmphasis.Plain,
)

/** The sidebar's summary card: a title and four figures over the rows the view shows. */
internal data class ProcessingSummary(val title: String, val lines: List<SummaryLine>)

/**
 * The web's `sideCard` (`PayrollGridModule.jsx` 1363-1410). Day View sums the
 * crew working the open day; every other view the crew it lists. The web's
 * Camera / Non-Camera OT lines read zero on the processing projection, which
 * carries one OT figure per day, so overtime is the one OTs / Premiums line.
 */
internal fun PayrollUiState.processingSummary(): ProcessingSummary {
    val week = processing.weekStarting ?: currentWeek
    return when (processing.view) {
        ProcessingView.Daily -> {
            val rows = processingShownRows()
            val day = processing.day
            ProcessingSummary(
                title = str(S.desktop_payroll_day_summary, PayPeriod.dayLabel(week + day * PayPeriod.DAY_MILLIS)),
                lines = listOf(
                    SummaryLine(str(S.desktop_payroll_crew_working), rows.size.toString()),
                    SummaryLine(
                        str(S.desktop_payroll_day_gross),
                        moneyLabel(rows.map { it.days[day].total to it.timecard.currency }, this),
                        SummaryEmphasis.Gross,
                    ),
                    SummaryLine(
                        str(S.desktop_payroll_ots_premiums),
                        moneyLabel(rows.map { it.days[day].ots to it.timecard.currency }, this),
                        SummaryEmphasis.Overtime,
                    ),
                ),
            )
        }

        ProcessingView.Outstanding -> {
            val rows = outstandingShownRows()
            weekSummary(
                crew = rows.size,
                payroll = moneyLabel(rows.map { it.totalMoney }, this),
                approved = rows.count { it.weeks.firstOrNull()?.timecard?.processingBucket == ProcessingNav.Approved },
                overtime = moneyLabel(rows.map { it.otsMoney }, this),
            )
        }

        else -> {
            val rows = processingShownRows()
            weekSummary(
                crew = rows.size,
                // Net of deductions — "the timecard amount" everywhere in payroll.
                payroll = moneyLabel(rows.map { it.totalPay to it.timecard.currency }, this),
                approved = rows.count { it.timecard.processingBucket == ProcessingNav.Approved },
                overtime = moneyLabel(rows.map { it.otTotal to it.timecard.currency }, this),
            )
        }
    }
}

private fun weekSummary(crew: Int, payroll: String, approved: Int, overtime: String) = ProcessingSummary(
    title = str(S.desktop_payroll_week_summary),
    lines = listOf(
        SummaryLine(str(S.desktop_payroll_total_crew), crew.toString()),
        SummaryLine(str(S.desktop_payroll_total_payroll), payroll, SummaryEmphasis.Gross),
        SummaryLine(str(S.approved), approved.toString(), SummaryEmphasis.Approved),
        SummaryLine(str(S.desktop_payroll_ots_premiums), overtime, SummaryEmphasis.Overtime),
    ),
)

private fun dayColumns(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit): List<TableColumn<ProcessingRow>> {
    val day = state.processing.day
    val currency: (ProcessingRow) -> String? = { it.timecard.currency }
    return listOf(
        crewColumn(state) { it.timecard.userId },
        TableColumn(str(S.type), ColumnWidth.Fixed(TYPE_COLUMN)) { row ->
            row.days[day].dayType?.let { ZillitTag(label = it.localised(), tone = TagTone.Info) }
        },
        textColumn(
            str(S.desktop_payroll_my_call),
            ColumnWidth.Fixed(TIME_COLUMN),
        ) { PayPeriod.hhmm(it.days[day].myCall) },
        textColumn(
            str(S.desktop_payroll_unit_call),
            ColumnWidth.Fixed(TIME_COLUMN),
        ) { PayPeriod.hhmm(it.days[day].unitCall) },
        textColumn(
            str(S.desktop_payroll_unit_wrap),
            ColumnWidth.Fixed(TIME_COLUMN),
        ) { PayPeriod.hhmm(it.days[day].unitWrap) },
        textColumn(str(S.desktop_release), ColumnWidth.Fixed(TIME_COLUMN)) { PayPeriod.hhmm(it.days[day].release) },
        textColumn(str(S.desktop_payroll_basic), ColumnWidth.Weight(1f), numeric = true) {
            it.days[day].basic.takeIf { v -> v > 0 }?.let { v -> Money.format(v, currency(it)) } ?: "—"
        },
        textColumn(
            str(S.desktop_payroll_ots),
            ColumnWidth.Weight(1f),
            numeric = true,
        ) { Money.format(it.days[day].ots, currency(it)) },
        textColumn(str(S.desktop_payroll_allowances_rental), ColumnWidth.Weight(1f), numeric = true) {
            Money.format(it.days[day].allowances, currency(it))
        },
        textColumn(
            str(S.desktop_payroll_day_total),
            ColumnWidth.Weight(1f),
            numeric = true,
        ) { Money.format(it.days[day].total, currency(it)) },
        statusColumn(),
        actionColumn(state, onEvent),
    )
}

@Suppress("LongMethod") // The Week to Date grid's nine columns, the web's order.
private fun weekToDateColumns(
    state: PayrollUiState,
    onEvent: (PayrollEvent) -> Unit,
): List<TableColumn<ProcessingRow>> {
    val through = state.processing.day
    fun worked(row: ProcessingRow) = row.days.take(through + 1).filterNot { it.isOff }
    return listOf(
        crewColumn(state) { it.timecard.userId },
        textColumn(str(S.dm_ds_unit_days), ColumnWidth.Fixed(DAYS_COLUMN), numeric = true) { row ->
            str(S.desktop_bs_n_days, worked(row).size)
        },
        TableColumn(str(S.desktop_payroll_types), ColumnWidth.Weight(1f)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                worked(row).mapNotNull { it.dayType }.distinct().forEach {
                    ZillitTag(
                        label = it.localised(),
                        tone = TagTone.Info,
                    )
                }
            }
        },
        textColumn(str(S.desktop_payroll_basic_pay), ColumnWidth.Weight(1f), numeric = true) { row ->
            Money.format(worked(row).sumOf { it.basic }, row.timecard.currency)
        },
        textColumn(str(S.desktop_payroll_ots_wtd), ColumnWidth.Weight(1f), numeric = true) { row ->
            Money.format(worked(row).sumOf { it.ots }, row.timecard.currency)
        },
        textColumn(str(S.desktop_payroll_allowances_wtd), ColumnWidth.Weight(1f), numeric = true) { row ->
            Money.format(worked(row).sumOf { it.allowances }, row.timecard.currency)
        },
        textColumn(str(S.desktop_payroll_wtd_total), ColumnWidth.Weight(1f), numeric = true) { row ->
            Money.format(worked(row).sumOf { it.total }, row.timecard.currency)
        },
        textColumn(str(S.desktop_payroll_projected_weekly), ColumnWidth.Weight(1f), numeric = true) { row ->
            val days = worked(row)
            val projected = if (days.isEmpty()) 0.0 else days.sumOf { it.total } / days.size * row.daysWorked
            Money.format(projected, row.timecard.currency)
        },
        TableColumn(str(S.desktop_progress), ColumnWidth.Fixed(PROGRESS_COLUMN)) { row ->
            val gross = row.timecard.gross
            val pct = if (gross > 0) (worked(row).sumOf { it.total } / gross * PERCENT).toInt() else 0
            Column {
                ZillitMeter(
                    fraction = (pct / PERCENT.toFloat()).coerceIn(0f, 1f),
                    tone = when {
                        pct > HIGH -> StatusTone.Ready
                        pct > MID -> StatusTone.Pending
                        else -> StatusTone.InTransit
                    },
                )
                ZillitText(text = "$pct%", style = ZillitTheme.typography.labelSmall)
            }
        },
        statusColumn(),
        actionColumn(state, onEvent),
    )
}

private fun weeklyColumns(
    state: PayrollUiState,
    onEvent: (PayrollEvent) -> Unit,
): List<TableColumn<ProcessingRow>> = listOf(
    crewColumn(state) { it.timecard.userId },
    textColumn(str(S.dm_ds_unit_days), ColumnWidth.Fixed(DAYS_COLUMN), numeric = true) { "${it.daysWorked}d" },
    textColumn(
        str(S.desktop_payroll_basic_pay),
        ColumnWidth.Weight(1f),
        numeric = true,
    ) { Money.format(it.basicTotal, it.timecard.currency) },
    textColumn(
        str(S.desktop_payroll_ots),
        ColumnWidth.Weight(1f),
        numeric = true,
    ) { Money.format(it.otTotal, it.timecard.currency) },
    textColumn(str(S.desktop_payroll_allowances_rental), ColumnWidth.Weight(1f), numeric = true) {
        Money.format(it.allowanceTotal, it.timecard.currency)
    },
    textColumn(
        str(S.desktop_payroll_total_pay),
        ColumnWidth.Weight(1f),
        numeric = true,
    ) { Money.format(it.totalPay, it.timecard.currency) },
    statusColumn(),
    actionColumn(state, onEvent),
)

private fun <T> crewColumn(state: PayrollUiState, userId: (T) -> String): TableColumn<T> =
    TableColumn(str(S.crew_member), ColumnWidth.Weight(CREW_WEIGHT)) { row ->
        Column {
            ZillitText(
                text = state.nameOf(userId(row)),
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            ZillitText(
                text = state.roleOf(userId(row)).ifBlank { str(S.crew) },
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
            )
        }
    }

/**
 * [value] returns the row's own (amount, currency) — never a column-wide
 * currency, see the fix note in [OutstandingGrid].
 */
private fun <T> moneyColumn(
    header: String,
    bold: Boolean = false,
    value: (T) -> Pair<Double, String?>,
): TableColumn<T> =
    TableColumn(header, ColumnWidth.Weight(1f), numeric = true) { row ->
        val (amount, currency) = value(row)
        ZillitText(
            text = Money.format(amount, currency),
            style = ZillitTheme.typography.numeric.copy(fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
        )
    }

private fun statusColumn(): TableColumn<ProcessingRow> = TableColumn(
    str(S.status),
    ColumnWidth.Fixed(STATUS_COLUMN),
) { row ->
    ZillitStatusPill(label = row.timecard.status.label, tone = row.timecard.status.tone, dot = true)
}

/**
 * Mark Paid on a locked or unpaid week and Mark Unpaid on a paid one — the
 * final approver's, from the table (`PayrollGridModule.jsx` 1972-1974); a
 * "View" for everything else.
 */
private fun actionColumn(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit): TableColumn<ProcessingRow> =
    TableColumn("", ColumnWidth.Fixed(ACTION_COLUMN)) { row ->
        val status = row.timecard.status
        val approver = state.viewer.isFinalApprover
        val busy = state.processing.busyRowId == row.timecard.id
        when {
            approver && status.isPayable -> ZillitButton(
                text = if (busy) str(S.desktop_payroll_marking) else str(S.desktop_mark_paid),
                onClick = { onEvent(ProcessingEvent.MarkPaid(row.timecard.id)) },
                size = ButtonSize.Small,
                enabled = state.processing.busyRowId == null,
            )
            approver && status.isUnpayable -> ZillitButton(
                text = if (busy) str(S.desktop_payroll_marking) else str(S.desktop_payroll_mark_unpaid),
                onClick = { onEvent(ProcessingEvent.MarkUnpaid(row.timecard.id)) },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
                enabled = state.processing.busyRowId == null,
            )
            else -> ZillitButton(
                text = str(S.view),
                onClick = { onEvent(ProcessingEvent.OpenDrawer(row.timecard.id)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
    }

private const val CREW_WEIGHT = 1.6f
private val TYPE_COLUMN = 80.dp
private val TIME_COLUMN = 76.dp
private val DAYS_COLUMN = 64.dp
private val PROGRESS_COLUMN = 110.dp
private val STATUS_COLUMN = 130.dp
private val ACTION_COLUMN = 130.dp
private const val PERCENT = 100
private const val HIGH = 80
private const val MID = 50
