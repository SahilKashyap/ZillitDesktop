package com.zillit.desktop.feature.payroll.ui.producer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.ui.EstimateStatus
import com.zillit.desktop.feature.payroll.ui.PayrollEvent
import com.zillit.desktop.feature.payroll.ui.PayrollUiState
import com.zillit.desktop.feature.payroll.ui.ProductionReportEvent
import com.zillit.desktop.feature.payroll.ui.components.CrewHero
import com.zillit.desktop.feature.payroll.ui.components.CrewRail
import com.zillit.desktop.feature.payroll.ui.components.PayrollTopBar
import com.zillit.desktop.feature.payroll.ui.components.RAIL_WIDTH
import com.zillit.desktop.feature.payroll.ui.components.RailRow
import com.zillit.desktop.feature.payroll.ui.components.ReadOnlyWeeklyView
import com.zillit.desktop.feature.payroll.ui.components.TimecardNotesButton
import com.zillit.desktop.feature.payroll.ui.components.WeekNavigator
import com.zillit.desktop.feature.payroll.ui.components.tone
import com.zillit.desktop.feature.payroll.ui.blankOnSelected
import com.zillit.desktop.feature.payroll.ui.canFillSelected
import com.zillit.desktop.feature.payroll.ui.estimateTimecard

/**
 * Production Report Payroll — the web's `ProductionReportPayrollModule`.
 *
 * Every crew member with a deal, week by week. A crew member with no timecard
 * is shown an estimate built from the unit's production report; one who has a
 * timecard is shown it, with the estimate overlaying only its blank days.
 * Nothing on this board is ever saved.
 */
@Composable
internal fun ProductionReportPage(
    state: PayrollUiState,
    onEvent: (PayrollEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val estimate = state.estimate
    val week = estimate.weekStarting
    Column(modifier.fillMaxSize()) {
        PayrollTopBar(
            crumb = str(S.desktop_payroll_production_report),
            onBack = { onEvent(PayrollEvent.BackToLanding) },
        )
        Row(
            modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            Column(
                modifier = Modifier.width(RAIL_WIDTH).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                WeekNavigator(
                    label = week?.let(PayPeriod::compactRangeLabel).orEmpty(),
                    canGoNext = week != null && week < state.currentWeek,
                    isCurrent = week == state.currentWeek,
                    onShift = { onEvent(ProductionReportEvent.ShiftWeek(it)) },
                    onCurrent = { onEvent(ProductionReportEvent.CurrentWeek) },
                    weekEnding = false,
                )
                AutoFillToggle(state, onEvent)
                CrewRail(
                    rows = state.estimateRailRows(),
                    selectedKey = estimate.selectedUserId,
                    loading = estimate.loading || !state.metadataLoaded,
                    emptyTitle = str(S.desktop_payroll_no_crew_with_deals),
                    emptyMessage = str(S.desktop_payroll_no_members_have_deal),
                    onSelect = { onEvent(ProductionReportEvent.Select(it)) },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    enabled = !estimate.fillBusy,
                )
            }
            EstimatePane(state, Modifier.weight(1f).fillMaxHeight(), onEvent)
        }
    }
}

/**
 * Arms the board: each crew member opened from here on is estimated from the
 * report as they are opened. Not an up-front pass over the whole unit — that
 * fetched every crew member's deal, hundreds of round trips for people the
 * producer never looked at.
 */
@Composable
private fun AutoFillToggle(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit) {
    ZillitButton(
        text = str(S.desktop_payroll_auto_fill_from_report),
        onClick = { onEvent(ProductionReportEvent.ToggleAutoFill) },
        variant = if (state.estimate.autoFill) ButtonVariant.Primary else ButtonVariant.Secondary,
        size = ButtonSize.Small,
        enabled = state.estimate.crew.isNotEmpty() && !state.estimate.fillBusy,
        leadingIcon = ZillitIcons.Reload,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EstimatePane(state: PayrollUiState, modifier: Modifier, onEvent: (PayrollEvent) -> Unit) {
    val estimate = state.estimate
    val person = estimate.selectedPerson
    val timecard = state.estimateTimecard()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        person?.let { crew ->
            CrewHero(
                name = state.nameOf(crew.userId),
                userId = crew.userId,
                designation = state.roleOf(crew.userId),
                department = state.departmentOf(crew.userId),
                weekLabel = estimate.weekStarting?.let(PayPeriod::compactRangeLabel).orEmpty(),
                statusLabel = estimate.rowStatus(crew.userId).view.label,
                statusTone = estimate.rowStatus(crew.userId).view.tone,
                actions = {
                    // Only on the real-timecard path: an estimate is not a
                    // timecard, so it has no notes to read.
                    TimecardNotesButton(
                        viewer = state.viewer,
                        timecard = estimate.timecard,
                        crewName = state.nameOf(crew.userId),
                    )
                    FillActions(state, onEvent)
                },
            )
        }
        EstimateBanner(state)
        ReadOnlyWeeklyView(
            timecard = timecard,
            loading = estimate.timecardLoading && estimate.selectedExisting != null,
            emptyMessage = if (estimate.crew.isEmpty()) {
                str(S.desktop_payroll_no_crew_with_deals)
            } else {
                str(S.desktop_payroll_select_crew_short)
            },
            modifier = Modifier.weight(1f),
            holidayPay = state.holidayPayFor(timecard),
            dayCellExtra = { row -> FillDayChip(state, row.index, onEvent) },
        )
    }
}

/** Fill the week, and clear it once anything has been filled. */
@Composable
private fun RowScope.FillActions(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit) {
    val estimate = state.estimate
    if (!state.canFillSelected) return
    ZillitButton(
        text = str(
            if (estimate.fillingAll) S.desktop_payroll_filling else S.desktop_payroll_fill_from_production_report,
        ),
        onClick = { onEvent(ProductionReportEvent.FillWeek) },
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Small,
        enabled = !estimate.fillBusy && !estimate.dealLoading && estimate.deal != null,
        leadingIcon = ZillitIcons.Reload,
    )
    if (estimate.selectedDays.any { it != null }) {
        ZillitButton(
            text = str(S.docusign_initials_clear_all),
            onClick = { onEvent(ProductionReportEvent.ClearWeek) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            enabled = !estimate.fillBusy,
        )
    }
}

/** The per-day control under the date — fill this day, or clear what was filled. */
@Composable
private fun FillDayChip(state: PayrollUiState, index: Int, onEvent: (PayrollEvent) -> Unit) {
    val estimate = state.estimate
    if (!state.canFillSelected || !state.blankOnSelected(index)) return
    val filled = estimate.selectedDays.getOrNull(index) != null
    if (filled) {
        ZillitButton(
            text = str(S.desktop_payroll_from_report),
            onClick = { onEvent(ProductionReportEvent.ClearDay(index)) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Check,
        )
        return
    }
    val week = estimate.weekStarting ?: return
    val busy = estimate.fillingDate == week + index * PayPeriod.DAY_MILLIS
    ZillitButton(
        text = str(if (busy) S.desktop_payroll_filling else S.desktop_payroll_fill_from_report),
        onClick = { onEvent(ProductionReportEvent.FillDay(index)) },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        enabled = !estimate.fillBusy && !estimate.dealLoading && estimate.deal != null,
        leadingIcon = ZillitIcons.Reload,
    )
}

/** Why this week is an estimate, or why it cannot be one. */
@Composable
private fun EstimateBanner(state: PayrollUiState) {
    val estimate = state.estimate
    if (estimate.selectedUserId == null || estimate.selectedExisting != null) return
    val message = when {
        estimate.dealLoading -> str(S.desktop_payroll_loading_deal)
        estimate.deal == null && state.hasPayEngine -> str(S.desktop_payroll_no_deal_no_estimate)
        estimate.deal == null -> str(S.desktop_payroll_no_pay_engine)
        else -> str(S.desktop_payroll_estimate_hint)
    }
    ZillitNotice(text = message, tone = StatusTone.InTransit, icon = ZillitIcons.Info)
}

/** The rail's rows: a real status when there is a timecard, else what the estimate says. */
private fun PayrollUiState.estimateRailRows(): List<RailRow> = estimate.crew.map { person ->
    val status = estimate.rowStatus(person.userId).view
    RailRow(
        key = person.userId,
        userId = person.userId,
        name = nameOf(person.userId),
        designation = roleOf(person.userId),
        department = departmentOf(person.userId),
        statusLabel = status.label,
        statusTone = status.tone,
    )
}

/** What a pill says about an estimate, and the hue it says it in. */
private data class EstimateStatusView(val label: String, val tone: StatusTone)

/** The pill an estimate status wears. */
private val EstimateStatus.view: EstimateStatusView
    get() = when (this) {
        is EstimateStatus.Real -> EstimateStatusView(status.railLabel, status.tone)
        EstimateStatus.Estimate -> EstimateStatusView(str(S.desktop_cr_estimate), StatusTone.Progress)
        EstimateStatus.Auto -> EstimateStatusView(str(S.desktop_auto), StatusTone.Pending)
        EstimateStatus.None -> EstimateStatusView(str(S.desktop_payroll_no_timecard), StatusTone.Neutral)
    }
