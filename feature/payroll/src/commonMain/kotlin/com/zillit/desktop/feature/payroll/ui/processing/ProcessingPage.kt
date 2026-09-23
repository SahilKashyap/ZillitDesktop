package com.zillit.desktop.feature.payroll.ui.processing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.SideNavItem
import com.zillit.desktop.core.designsystem.component.SideNavSection
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitErrorState
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSideNav
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitVerticalDivider
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.ProcessingRow
import com.zillit.desktop.feature.payroll.domain.TimecardStatus
import com.zillit.desktop.feature.payroll.ui.PayrollDestination
import com.zillit.desktop.feature.payroll.ui.PayrollEvent
import com.zillit.desktop.feature.payroll.ui.PayrollUiState
import com.zillit.desktop.feature.payroll.ui.ProcessingEvent
import com.zillit.desktop.feature.payroll.ui.ProcessingNav
import com.zillit.desktop.feature.payroll.ui.ProcessingView
import com.zillit.desktop.feature.payroll.ui.components.CrewDrawer
import com.zillit.desktop.feature.payroll.ui.components.PayrollTopBar
import com.zillit.desktop.feature.payroll.ui.processingBucket

/**
 * Payroll Processing — the web's `PayrollGridModule`: the current processing
 * week in four views (Day View, Week to Date, Weekly Total, Outstanding),
 * filtered by status, department and search, with Mark Paid / Mark Unpaid on
 * the rows for the approver and a crew drawer for the rest.
 */
@Composable
fun ProcessingPage(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        PayrollTopBar(crumb = PayrollDestination.Processing.label, onBack = { onEvent(PayrollEvent.BackToLanding) })
        Row(Modifier.fillMaxSize()) {
            ProcessingNavRail(state, onEvent)
            ZillitVerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val error = state.processing.error
                if (error != null) {
                    ZillitErrorState(message = error.localised(), onRetry = { onEvent(ProcessingEvent.Refresh) })
                } else {
                    ProcessingMain(state, onEvent)
                }
            }
            val drawerId = state.processing.drawerId
            if (drawerId != null) {
                ZillitVerticalDivider()
                ProcessingDrawer(state, onEvent, Modifier.width(DRAWER_WIDTH).fillMaxHeight())
            }
        }
    }
}

/** "Payroll": All Time Cards, Pending, Approved with counts; "Output": Export CSV. */
@Composable
private fun ProcessingNavRail(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit) {
    val counts = state.processingCounts()
    ZillitSideNav(
        sections = listOf(
            SideNavSection(
                title = str(S.dm_section_payroll),
                items = listOf(
                    SideNavItem(
                        ProcessingNav.All.name,
                        str(S.desktop_payroll_all_time_cards),
                        ZillitIcons.Grid,
                        counts.all,
                    ),
                    SideNavItem(ProcessingNav.Pending.name, str(S.pending), ZillitIcons.Clock, counts.pending),
                    SideNavItem(ProcessingNav.Approved.name, str(S.approved), ZillitIcons.Check, counts.approved),
                ),
            ),
            SideNavSection(
                title = str(S.desktop_payroll_output),
                items = listOf(SideNavItem(EXPORT_ID, str(S.dd_action_export_csv), ZillitIcons.Download)),
            ),
        ),
        activeId = state.processing.nav.name,
        onSelect = { id ->
            if (id == EXPORT_ID) {
                onEvent(ProcessingEvent.Export(open = true))
            } else {
                onEvent(ProcessingEvent.Nav(ProcessingNav.valueOf(id)))
            }
        },
    )
}

/** View tabs, the day picker, the tiles, the department chips and search, then the grid. */
@Suppress("LongMethod") // The web's right pane stacks five controls over one grid.
@Composable
private fun ProcessingMain(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit) {
    val processing = state.processing
    Column(
        Modifier.fillMaxSize().padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitTabStrip(
                tabs = listOf(
                    ZillitTab(ProcessingView.Daily.name, str(S.desktop_payroll_day_view)),
                    ZillitTab(ProcessingView.WeekToDate.name, str(S.desktop_payroll_week_to_date)),
                    ZillitTab(ProcessingView.Weekly.name, str(S.desktop_payroll_weekly_total)),
                    ZillitTab(ProcessingView.Outstanding.name, str(S.desktop_outstanding)),
                ),
                activeId = processing.view.name,
                onSelect = { onEvent(ProcessingEvent.View(ProcessingView.valueOf(it))) },
                modifier = Modifier.weight(1f),
            )
            processing.weekStarting?.let {
                ZillitText(
                    text = PayPeriod.compactRangeLabel(it),
                    style = ZillitTheme.typography.numeric,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
        }
        if (processing.view == ProcessingView.Daily) DayPicker(state, onEvent)
        if (processing.view != ProcessingView.Outstanding) ProcessingTiles(state)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitSearchField(
                value = processing.search,
                onValueChange = { onEvent(ProcessingEvent.Search(it)) },
                placeholder = str(S.desktop_payroll_search_crew_role),
                modifier = Modifier.width(SEARCH_WIDTH),
            )
            ZillitChoiceChip(
                label = str(S.all),
                selected = processing.department == null,
                onClick = { onEvent(ProcessingEvent.Department(null)) },
            )
            state.processingDepartments().forEach { (department, count) ->
                ZillitChoiceChip(
                    label = "${state.labelOfDepartment(department)} ($count)",
                    selected = processing.department == department,
                    onClick = { onEvent(ProcessingEvent.Department(department)) },
                )
            }
            Spacer(Modifier.weight(1f))
            if (processing.exporting) {
                ZillitText(
                    text = str(S.desktop_payroll_generating_csv),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (processing.view == ProcessingView.Outstanding) {
                OutstandingGrid(state, onEvent)
            } else {
                ProcessingGrid(state, onEvent)
            }
        }
    }
}

/** The seven days, today highlighted; a day that has not happened yet cannot be picked. */
@Composable
private fun DayPicker(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit) {
    val week = state.processing.weekStarting ?: return
    val today = ((state.now - week) / PayPeriod.DAY_MILLIS).toInt()
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        for (index in 0 until ProcessingRow.DAYS) {
            val date = week + index * PayPeriod.DAY_MILLIS
            ZillitButton(
                text = PayPeriod.dayLabel(date),
                onClick = { onEvent(ProcessingEvent.Day(index)) },
                variant = if (state.processing.day == index) ButtonVariant.Primary else ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = index <= today,
            )
        }
    }
}

/**
 * The KPI tiles over the views — the lead gross for the view's range, OTs and
 * premiums, allowances, rentals, and how many are approved. Summed from the
 * days in range, as the web sums them.
 */
@Composable
private fun ProcessingTiles(state: PayrollUiState) {
    val processing = state.processing
    val rows = state.processingShownRows()
    val range = when (processing.view) {
        ProcessingView.Daily -> processing.day..processing.day
        ProcessingView.WeekToDate -> 0..processing.day
        else -> 0 until ProcessingRow.DAYS
    }
    val inRange = rows.flatMap { row -> range.map { row.days[it] } }.filterNot { it.isOff }
    val currency = rows.firstNotNullOfOrNull { it.timecard.currency }
    val gross = inRange.sumOf { it.total }
    val lead = when (processing.view) {
        ProcessingView.Daily -> str(S.desktop_payroll_day_gross)
        ProcessingView.WeekToDate -> str(S.desktop_payroll_wtd_gross)
        else -> str(S.desktop_payroll_gross_payroll)
    }
    val approved = rows.count { it.timecard.processingBucket == ProcessingNav.Approved }
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitStatTile(
            lead,
            Money.format(gross, currency),
            Modifier.weight(1f),
            str(S.desktop_payroll_crew_count, rows.size),
        )
        if (processing.view == ProcessingView.WeekToDate) {
            val projected = gross / (processing.day + 1) * ProcessingRow.DAYS
            ZillitStatTile(
                str(S.desktop_payroll_projected_weekly),
                Money.format(projected, currency),
                Modifier.weight(1f),
                str(S.desktop_payroll_extrapolated),
            )
        }
        ZillitStatTile(
            str(S.desktop_payroll_ots_premiums),
            Money.format(inRange.sumOf { it.ots }, currency),
            Modifier.weight(1f),
            tone = StatusTone.Pending,
        )
        ZillitStatTile(
            str(S.desktop_payroll_allowances_rental),
            Money.format(inRange.sumOf { it.allowances }, currency),
            Modifier.weight(1f),
        )
        ZillitStatTile(
            str(S.approved),
            approved.toString(),
            Modifier.weight(1f),
            str(S.desktop_payroll_of_count, rows.size),
            tone = StatusTone.Ready,
        )
    }
}

/** The drawer with Processing's footer: Override Approval, and Mark Paid on a locked or unpaid week. */
@Composable
private fun ProcessingDrawer(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit, modifier: Modifier) {
    CrewDrawer(
        state = state,
        timecard = state.processing.drawer,
        loading = state.processing.drawerLoading,
        onClose = { onEvent(ProcessingEvent.OpenDrawer(null)) },
        onEvent = onEvent,
        modifier = modifier,
    ) { timecard ->
        val busy = state.processing.busyRowId == timecard.id
        if (timecard.status.isAwaitingApproval && state.canOverride) {
            ZillitButton(
                text = str(S.desktop_payroll_override_approval),
                onClick = { onEvent(PayrollEvent.AskOverride(timecard)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
        }
        val canPay = timecard.status == TimecardStatus.Locked ||
            (timecard.status == TimecardStatus.Unpaid && state.viewer.isFinalApprover)
        if (canPay) {
            ZillitButton(
                text = str(S.desktop_mark_paid),
                onClick = { onEvent(ProcessingEvent.DrawerMarkPaid(timecard.id)) },
                size = ButtonSize.Small,
                enabled = state.processing.busyRowId == null,
                loading = busy,
            )
        }
    }
}

private const val EXPORT_ID = "export"
private val DRAWER_WIDTH = 560.dp
private val SEARCH_WIDTH = 260.dp

/** A department label key translated, or "Unassigned". */
internal fun PayrollUiState.labelOfDepartment(department: String): String =
    department.takeIf { it.isNotBlank() }?.localised() ?: str(S.unassigned)

