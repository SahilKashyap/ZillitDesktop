package com.zillit.desktop.feature.payroll.ui.run

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitErrorState
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitVerticalDivider
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.Employment
import com.zillit.desktop.feature.payroll.domain.ExportFormat
import com.zillit.desktop.feature.payroll.domain.OverallStatus
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.RowAction
import com.zillit.desktop.feature.payroll.domain.RunAction
import com.zillit.desktop.feature.payroll.domain.RunRow
import com.zillit.desktop.feature.payroll.domain.RunSelection
import com.zillit.desktop.feature.payroll.domain.rowActionFor
import com.zillit.desktop.feature.payroll.ui.PayrollDestination
import com.zillit.desktop.feature.payroll.ui.PayrollEvent
import com.zillit.desktop.feature.payroll.ui.PayrollUiState
import com.zillit.desktop.feature.payroll.ui.RunEvent
import com.zillit.desktop.feature.payroll.ui.RunStatusFilter
import com.zillit.desktop.feature.payroll.ui.components.PayrollTopBar
import com.zillit.desktop.feature.payroll.ui.components.WeekNavigator
import com.zillit.desktop.feature.payroll.ui.components.tone
import com.zillit.desktop.feature.payroll.ui.runRows
import com.zillit.desktop.feature.payroll.ui.runShownRows

/**
 * Payroll Run — the web's `PayrollRunModule`.
 *
 * Filters down the left (departments, employment, status), the week's crew in
 * a grid on the right with the summary strip over it, and the role-aware
 * toolbar: Final Approve, Final Approve & Lock, Lock, Mark Paid. The Journal
 * Ledger replaces the grid when it is open; a crew row opens its drawer.
 */
@Composable
fun RunPage(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        if (state.run.journalOpen) {
            JournalPage(state, onEvent)
            return@Column
        }
        PayrollTopBar(crumb = PayrollDestination.Run.label, onBack = { onEvent(PayrollEvent.BackToLanding) }) {
            ZillitButton(
                text = str(S.desktop_payroll_journal_ledger),
                onClick = { onEvent(RunEvent.Journal(open = true)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Ledger,
            )
            ZillitButton(
                text = str(S.desktop_payroll_go_to_payroll),
                onClick = { onEvent(RunEvent.GoToProcessing) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                trailingIcon = ZillitIcons.ArrowRight,
            )
        }
        Row(Modifier.fillMaxSize()) {
            RunSidebar(state, onEvent, Modifier.width(SIDEBAR_WIDTH).fillMaxHeight())
            ZillitVerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val error = state.run.error
                if (error != null) {
                    ZillitErrorState(message = error.localised(), onRetry = { onEvent(RunEvent.Refresh) })
                } else {
                    RunMain(state, onEvent)
                }
            }
            if (state.run.drawerId != null) {
                ZillitVerticalDivider()
                RunDrawer(state, onEvent, Modifier.width(DRAWER_WIDTH).fillMaxHeight())
            }
        }
    }
}

/** Week, then the three filter cards — the web's `RunSidebar`. */
@Suppress("LongMethod") // Three filter cards over one column.
@Composable
private fun RunSidebar(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit, modifier: Modifier) {
    val run = state.run
    val rows = state.runRows()
    val week = run.weekStarting
    ZillitScrollColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        WeekNavigator(
            label = week?.let(PayPeriod::compactRangeLabel).orEmpty(),
            canGoNext = week != null && week < state.currentWeek,
            isCurrent = week == state.currentWeek,
            onShift = { onEvent(RunEvent.ShiftWeek(it)) },
            onCurrent = { onEvent(RunEvent.CurrentWeek) },
        )
        FilterCard(str(S.departments)) {
            FilterRow(str(S.all_departments), rows.size, active = run.department == null) {
                onEvent(RunEvent.Department(null))
            }
            val departments = rows.groupBy { it.department }.toList().sortedBy { state.labelOf(it.first) }
            departments.forEach { (department, group) ->
                FilterRow(state.labelOf(department), group.size, active = run.department == department) {
                    onEvent(RunEvent.Department(department))
                }
            }
        }
        FilterCard(str(S.desktop_payroll_employment)) {
            Employment.entries.forEach { employment ->
                FilterRow(
                    employment.label,
                    rows.count { it.employment == employment },
                    active = run.employment == employment,
                ) {
                    onEvent(RunEvent.EmploymentFilter(employment))
                }
            }
        }
        FilterCard(str(S.status)) {
            FilterRow(
                str(S.pending),
                rows.count { it.overall == OverallStatus.Pending },
                run.status == RunStatusFilter.Pending,
            ) {
                onEvent(RunEvent.Status(run.status.toggled(RunStatusFilter.Pending)))
            }
            FilterRow(
                str(S.approved),
                rows.count { it.overall == OverallStatus.Approved },
                run.status == RunStatusFilter.Approved,
            ) {
                onEvent(RunEvent.Status(run.status.toggled(RunStatusFilter.Approved)))
            }
        }
    }
}

/** Picking the active status again clears it — the web's toggle. */
private fun RunStatusFilter.toggled(to: RunStatusFilter): RunStatusFilter = if (this == to) RunStatusFilter.All else to

/** A department label key translated, or "Unassigned" for none. */
internal fun PayrollUiState.labelOf(department: String): String =
    department.takeIf { it.isNotBlank() }?.localised() ?: str(S.unassigned)

@Composable
private fun FilterCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(ZillitTheme.colors.surface)
            .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.large)
            .padding(vertical = ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = title.uppercase(),
            style = ZillitTheme.typography.columnHeader,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
        )
        content()
    }
}

@Composable
private fun FilterRow(label: String, count: Int, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (active) ZillitTheme.colors.accentSoft else ZillitTheme.colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodySmall
                .copy(fontWeight = if (active) FontWeight.Bold else FontWeight.Normal),
            color = if (active) ZillitTheme.colors.accentText else ZillitTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        ZillitText(
            text = count.toString(),
            style = ZillitTheme.typography.numeric,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/** Toolbar, summary strip, search and chips, then the grid. */
@Suppress("LongMethod") // The web's right pane: four stacked parts over one grid.
@Composable
private fun RunMain(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit) {
    val run = state.run
    val rows = state.runRows()
    val shown = state.runShownRows()
    val selection = RunSelection.of(rows, run.selected)
    Column(
        Modifier.fillMaxSize().padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        RunToolbar(state, selection, onEvent)
        SummaryStrip(rows)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitSearchField(
                value = run.search,
                onValueChange = { onEvent(RunEvent.Search(it)) },
                placeholder = str(S.desktop_payroll_search_crew_name_role),
                modifier = Modifier.width(SEARCH_WIDTH),
            )
            RunStatusFilter.entries.forEach { filter ->
                ZillitChoiceChip(
                    label = when (filter) {
                        RunStatusFilter.All -> str(S.all)
                        RunStatusFilter.Pending -> str(S.pending)
                        RunStatusFilter.Approved -> str(S.approved)
                    },
                    selected = run.status == filter,
                    onClick = { onEvent(RunEvent.Status(filter)) },
                )
            }
            Spacer(Modifier.weight(1f))
            ZillitText(
                text = str(S.desktop_payroll_showing_of, shown.size, rows.size),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitCheckbox(
                checked = shown.isNotEmpty() && run.selected.containsAll(shown.map { it.id }),
                onCheckedChange = { onEvent(RunEvent.ToggleAllShown) },
                label = str(S.desktop_payroll_select_shown),
                enabled = shown.isNotEmpty(),
            )
        }
        ZillitDataTable(
            rows = shown,
            columns = runColumns(state, onEvent),
            key = { it.id },
            onRowClick = { onEvent(RunEvent.OpenDrawer(it.id)) },
            isSelected = { it.id == run.drawerId },
            loading = run.loading || !state.metadataLoaded,
            emptyTitle = str(S.desktop_payroll_no_crew_match_filters),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

/**
 * Export Summary always; with a selection, the one approval button this
 * viewer's roles allow and Mark Paid — the web's `PayrollRunToolbar`.
 */
@Composable
private fun RunToolbar(state: PayrollUiState, selection: RunSelection, onEvent: (PayrollEvent) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ExportSummaryButton(exporting = state.run.exporting, onEvent = onEvent)
        ZillitButton(
            text = str(S.dd_action_export_csv),
            onClick = { onEvent(RunEvent.Export(ExportFormat.Csv)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Download,
            enabled = !state.run.exporting,
        )
        Spacer(Modifier.weight(1f))
        selection.approvalFor(state.viewer)?.let { (action, count) ->
            ZillitButton(
                text = "${action.label} · $count",
                onClick = { onEvent(RunEvent.Ask(action)) },
                size = ButtonSize.Small,
                leadingIcon = if (action == RunAction.FinalApprove) ZillitIcons.Check else ZillitIcons.Lock,
            )
        }
        if (selection.offersMarkPaid && state.viewer.seesAccountantViews) {
            val label =
                if (selection.markPaidFromLockedOnly) S.desktop_payroll_mark_locked_paid else S.desktop_mark_paid
            ZillitButton(
                text = "${str(label)} · ${selection.paidEligibleIds.size}",
                onClick = { onEvent(RunEvent.Ask(RunAction.MarkPaid)) },
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Check,
            )
        }
    }
}

/** Export Summary — the server renders the run as a PDF or an Excel workbook. */
@Composable
private fun ExportSummaryButton(exporting: Boolean, onEvent: (PayrollEvent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ZillitButton(
            text = if (exporting) str(S.desktop_exporting) else str(S.desktop_payroll_export_summary),
            onClick = { open = true },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Download,
            enabled = !exporting,
        )
        ZillitActionMenu(
            expanded = open,
            onDismissRequest = { open = false },
            entries = listOf(
                ZillitMenuEntry.Action(str(S.recce_export_pdf), ZillitIcons.File) {
                    open = false
                    onEvent(RunEvent.Export(ExportFormat.Pdf))
                },
                ZillitMenuEntry.Action(str(S.desktop_dm_export_excel), ZillitIcons.Grid) {
                    open = false
                    onEvent(RunEvent.Export(ExportFormat.Excel))
                },
            ),
        )
    }
}

/** The six tiles — the web's `SummaryStrip`. Holiday pay and NIC are the grid's own estimates where no fringes came. */
@Composable
private fun SummaryStrip(rows: List<RunRow>) {
    val currency = rows.firstNotNullOfOrNull { it.timecard.currency }
    val approved = rows.count { it.overall in APPROVED_BUCKETS }
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitStatTile(
            str(S.desktop_payroll_total_crew),
            rows.size.toString(),
            Modifier.weight(1f),
            str(S.desktop_payroll_on_payroll_this_week),
        )
        ZillitStatTile(
            str(S.desktop_payroll_gross_labour),
            Money.compact(rows.sumOf { it.gross }, currency),
            Modifier.weight(1f),
            str(S.desktop_payroll_before_fringes),
        )
        ZillitStatTile(
            str(S.dm_rates_card_hp),
            Money.compact(rows.sumOf { it.holidayPay }, currency),
            Modifier.weight(1f),
            str(S.desktop_payroll_rate_where_applicable, RunRow.HOLIDAY_PAY_LABEL),
        )
        ZillitStatTile(
            str(S.allowances_label),
            Money.compact(rows.sumOf { it.allowances }, currency),
            Modifier.weight(1f),
            str(S.desktop_payroll_kit_mileage_box),
        )
        ZillitStatTile(
            str(S.desktop_payroll_total_cost),
            Money.compact(rows.sumOf { it.totalCost }, currency),
            Modifier.weight(1f),
            str(S.desktop_payroll_full_liability),
            tone = StatusTone.Ready,
        )
        ZillitStatTile(
            str(S.approved),
            approved.toString(),
            Modifier.weight(1f),
            str(S.desktop_payroll_of_count, rows.size),
            tone = if (rows.isNotEmpty() && approved == rows.size) StatusTone.Done else null,
        )
    }
}

@Suppress("LongMethod") // One column list: the grid's nine columns side by side.
private fun runColumns(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit): List<TableColumn<RunRow>> {
    val week = state.run.weekStarting
    val letters = week?.let(PayPeriod::weekdayLetters) ?: List(RunRow.DAYS) { "" }
    return listOf(
        TableColumn(header = "", width = ColumnWidth.Fixed(CHECK_COLUMN)) { row ->
            ZillitCheckbox(
                checked = row.id in state.run.selected,
                onCheckedChange = { onEvent(RunEvent.ToggleRow(row.id)) },
            )
        },
        TableColumn(header = str(S.crew_member), width = ColumnWidth.Weight(CREW_WEIGHT)) { row ->
            Column {
                ZillitText(
                    text = row.name,
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                val otherWeek = row.timecard.weekStarting?.takeIf { it != week }
                    ?.let { " (${PayPeriod.compactRangeLabel(it)})" }.orEmpty()
                ZillitText(
                    text = state.roleOf(row.timecard.userId).ifBlank { str(S.crew) } + otherWeek,
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                    maxLines = 1,
                )
            }
        },
        TableColumn(header = str(S.desktop_po_dept_column), width = ColumnWidth.Weight(DEPT_WEIGHT)) { row ->
            ZillitText(text = state.labelOf(row.department), style = ZillitTheme.typography.bodySmall, maxLines = 1)
        },
        TableColumn(header = str(S.type), width = ColumnWidth.Fixed(TYPE_COLUMN)) { row ->
            ZillitTag(label = row.employment.shortLabel, tone = TagTone.Neutral)
        },
        TableColumn(header = str(S.dm_ds_unit_days), width = ColumnWidth.Fixed(DAYS_COLUMN), numeric = true) { row ->
            ZillitText(text = row.daysWorked.count { it }.toString(), style = ZillitTheme.typography.numeric)
        },
        TableColumn(header = "", width = ColumnWidth.Fixed(WEEK_COLUMN)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                row.daysWorked.forEachIndexed { index, worked -> DayChip(letters.getOrElse(index) { "" }, worked) }
            }
        },
        TableColumn(header = str(S.desktop_gross), width = ColumnWidth.Fixed(GROSS_COLUMN), numeric = true) { row ->
            Column(horizontalAlignment = Alignment.End) {
                val buyOut = row.employment == Employment.BuyOut
                ZillitText(
                    text = Money.format(row.gross, row.timecard.currency, decimals = if (buyOut) 0 else 2),
                    style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
                )
                when {
                    buyOut -> ZillitText(
                        text = str(S.desktop_payroll_buy_out_caps),
                        style = ZillitTheme.typography.labelSmall,
                    )
                    row.claimsTotal > 0 -> ZillitText(
                        text = str(S.desktop_payroll_plus_claims, Money.format(row.claimsTotal, row.timecard.currency)),
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.success,
                    )
                }
            }
        },
        TableColumn(header = str(S.status), width = ColumnWidth.Fixed(STATUS_COLUMN)) { row ->
            ZillitStatusPill(label = row.status.label, tone = row.status.tone, dot = true)
        },
        TableColumn(header = "", width = ColumnWidth.Fixed(ACTION_COLUMN)) { row -> RowButton(state, row, onEvent) },
    )
}

/** The row's one button, in the web's first-match order. */
@Composable
private fun RowButton(state: PayrollUiState, row: RunRow, onEvent: (PayrollEvent) -> Unit) {
    val action = rowActionFor(row.status, state.viewer, state.canOverride)
    val busy = state.run.busyRowId == row.id
    ZillitButton(
        text = action.label,
        onClick = { onEvent(RunEvent.Row(row.id, action)) },
        variant = when (action) {
            RowAction.View -> ButtonVariant.Tertiary
            RowAction.MarkUnpaid -> ButtonVariant.Danger
            else -> ButtonVariant.Primary
        },
        size = ButtonSize.Small,
        enabled = state.run.busyRowId == null,
        loading = busy,
    )
}

@Composable
private fun DayChip(letter: String, worked: Boolean) {
    Box(
        Modifier.size(DAY_CHIP)
            .clip(ZillitTheme.shapes.small)
            .background(if (worked) ZillitTheme.colors.warningSoft else ZillitTheme.colors.surfaceSunken)
            .border(
                1.dp,
                if (worked) ZillitTheme.colors.warning.copy(alpha = CHIP_RING) else ZillitTheme.colors.border,
                ZillitTheme.shapes.small,
            ),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = letter,
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = if (worked) ZillitTheme.colors.warning else ZillitTheme.colors.textMuted,
        )
    }
}

/** The toolbar's words for each bulk action. */
internal val RunAction.label: String
    get() = str(
        when (this) {
            RunAction.FinalApprove -> S.desktop_final_approve
            RunAction.FinalApproveAndLock -> S.desktop_payroll_final_approve_lock
            RunAction.Lock -> S.desktop_lock
            RunAction.MarkPaid -> S.desktop_mark_paid
        },
    )

/** A row button's words. */
internal val RowAction.label: String
    get() = str(
        when (this) {
            RowAction.Override -> S.dm_nom_table_override
            RowAction.FinalApprove -> S.desktop_final_approve
            RowAction.FinalApproveAndLock -> S.desktop_payroll_final_approve_lock
            RowAction.Lock -> S.desktop_lock
            RowAction.MarkPaid -> S.desktop_mark_paid
            RowAction.MarkUnpaid -> S.desktop_payroll_mark_unpaid
            RowAction.View -> S.view
        },
    )

/** The sidebar's words — the web's employment rows. */
internal val Employment.label: String
    get() = str(
        when (this) {
            Employment.Paye -> S.desktop_payroll_paye
            Employment.ScheduleD -> S.desktop_payroll_schedule_d
            Employment.LoanOut -> S.desktop_payroll_loan_out
            Employment.BuyOut -> S.desktop_dm_deal_type_buy_out
        },
    )

/** The grid's badge — the web's `EMP_LABEL`. */
internal val Employment.shortLabel: String
    get() = if (this == Employment.ScheduleD) str(S.desktop_payroll_sch_d) else label

private val APPROVED_BUCKETS = setOf(OverallStatus.Approved, OverallStatus.Processed, OverallStatus.Paid)
private val SIDEBAR_WIDTH = 240.dp
private val DRAWER_WIDTH = 560.dp
private val SEARCH_WIDTH = 280.dp
private val CHECK_COLUMN = 40.dp
private const val CREW_WEIGHT = 1.6f
private const val DEPT_WEIGHT = 1f
private val TYPE_COLUMN = 88.dp
private val DAYS_COLUMN = 56.dp
private val WEEK_COLUMN = 180.dp
private val GROSS_COLUMN = 130.dp
private val STATUS_COLUMN = 130.dp
private val ACTION_COLUMN = 170.dp
private val DAY_CHIP = 22.dp
private const val CHIP_RING = 0.35f
