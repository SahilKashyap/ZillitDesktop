package com.zillit.desktop.feature.payroll.ui.processing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
    val columns = when (state.processing.view) {
        ProcessingView.Daily -> dayColumns(state, onEvent)
        ProcessingView.WeekToDate -> weekToDateColumns(state, onEvent)
        else -> weeklyColumns(state, onEvent)
    }
    ZillitDataTable(
        rows = state.processingShownRows(),
        columns = columns,
        key = { it.timecard.id },
        onRowClick = { onEvent(ProcessingEvent.OpenDrawer(it.timecard.id)) },
        isSelected = { it.timecard.id == state.processing.drawerId },
        loading = state.processing.loading || !state.metadataLoaded,
        emptyTitle = str(S.desktop_payroll_no_approved_this_week),
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
internal fun OutstandingGrid(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit) {
    val week = state.processing.weekStarting ?: state.currentWeek
    val query = state.processing.search.trim().lowercase()
    val department = state.processing.department
    val rows = OutstandingRow.of(state.processing.outstanding, week).filter { row ->
        row.status != TimecardStatus.Paid &&
            navMatches(state.processing.nav, row.weeks.firstOrNull()?.timecard?.processingBucket) &&
            (department == null || state.people[row.userId]?.department.orEmpty() == department) &&
            (query.isEmpty() || "${state.nameOf(row.userId)} ${state.roleOf(row.userId)}".lowercase().contains(query))
    }
    val currency = state.processing.outstanding.firstNotNullOfOrNull { it.currency }
    ZillitDataTable(
        rows = rows,
        columns = listOf(
            crewColumn(state) { it.userId },
            textColumn(str(S.dm_ds_unit_days), ColumnWidth.Fixed(DAYS_COLUMN), numeric = true) { "${it.days}d" },
            moneyColumn(str(S.desktop_payroll_basic_pay), currency) { it.basic },
            moneyColumn(str(S.desktop_payroll_ots), currency) { it.ots },
            moneyColumn(str(S.desktop_payroll_allowances_rental), currency) { it.allowances },
            moneyColumn(str(S.desktop_payroll_total_pay), currency, bold = true) { it.total },
        ),
        key = { it.id },
        onRowClick = { onEvent(ProcessingEvent.OpenOutstanding(it.userId)) },
        loading = state.processing.outstandingLoading,
        emptyTitle = str(S.desktop_payroll_no_outstanding),
        modifier = Modifier.fillMaxSize(),
    )
}

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

private fun <T> moneyColumn(
    header: String,
    currency: String?,
    bold: Boolean = false,
    value: (T) -> Double,
): TableColumn<T> =
    TableColumn(header, ColumnWidth.Weight(1f), numeric = true) { row ->
        ZillitText(
            text = Money.format(value(row), currency),
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
