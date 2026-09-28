package com.zillit.desktop.feature.payroll.ui.producer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitErrorState
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.PayrollCrewRow
import com.zillit.desktop.feature.payroll.domain.TimecardStatus
import com.zillit.desktop.feature.payroll.ui.PayrollEvent
import com.zillit.desktop.feature.payroll.ui.PayrollUiState
import com.zillit.desktop.feature.payroll.ui.ProducerBoardEvent
import com.zillit.desktop.feature.payroll.ui.components.CrewHero
import com.zillit.desktop.feature.payroll.ui.components.CrewRail
import com.zillit.desktop.feature.payroll.ui.components.PayrollTopBar
import com.zillit.desktop.feature.payroll.ui.components.RAIL_WIDTH
import com.zillit.desktop.feature.payroll.ui.components.RailRow
import com.zillit.desktop.feature.payroll.ui.components.ReadOnlyWeeklyView
import com.zillit.desktop.feature.payroll.ui.components.TimecardNotesButton
import com.zillit.desktop.feature.payroll.ui.components.WeekNavigator
import com.zillit.desktop.feature.payroll.ui.components.tone

/**
 * Producer Board Payroll Status — the web's `ProducerBoardModule`.
 *
 * The week's crew down the left, the selected crew member's week read-only on
 * the right. Nothing here writes: the producer reads, and approval lives
 * elsewhere in the chain.
 *
 * The board holds until the production's pay period is known — every week
 * figure depends on it, and painting a Monday-defaulted board shows the wrong
 * dates and lets the reader page through weeks the realignment then discards.
 */
@Composable
internal fun ProducerBoardPage(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit, modifier: Modifier = Modifier) {
    val producer = state.producer
    val week = producer.weekStarting
    Column(modifier.fillMaxSize()) {
        PayrollTopBar(
            crumb = str(S.desktop_payroll_producer_board),
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
                    onShift = { onEvent(ProducerBoardEvent.ShiftWeek(it)) },
                    onCurrent = { onEvent(ProducerBoardEvent.CurrentWeek) },
                    weekEnding = false,
                )
                CrewRail(
                    rows = state.railRows(producer.crew),
                    selectedKey = producer.selectedId,
                    loading = producer.loading || !state.metadataLoaded,
                    emptyTitle = str(S.desktop_payroll_no_timecards_yet),
                    emptyMessage = str(S.desktop_payroll_no_timecards_exist_week),
                    onSelect = { onEvent(ProducerBoardEvent.Select(it)) },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
            BoardPane(state, Modifier.weight(1f).fillMaxHeight(), onEvent)
        }
    }
}

@Composable
private fun BoardPane(state: PayrollUiState, modifier: Modifier, onEvent: (PayrollEvent) -> Unit) {
    val producer = state.producer
    val error = producer.error
    if (error != null) {
        ZillitErrorState(
            message = error.localised(),
            onRetry = { onEvent(ProducerBoardEvent.CurrentWeek) },
            modifier = modifier,
        )
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        producer.selectedRow?.let { row ->
            CrewHero(
                name = state.nameOf(row.userId),
                userId = row.userId,
                designation = state.roleOf(row.userId),
                department = state.departmentOf(row.userId),
                weekLabel = producer.weekStarting?.let(PayPeriod::compactRangeLabel).orEmpty(),
                statusLabel = row.status.label,
                statusTone = row.status.tone,
                actions = {
                    TimecardNotesButton(
                        viewer = state.viewer,
                        timecard = producer.timecard,
                        crewName = state.nameOf(row.userId),
                    )
                },
            )
        }
        ReadOnlyWeeklyView(
            timecard = producer.timecard,
            loading = producer.timecardLoading && producer.selectedId != null,
            emptyMessage = if (producer.crew.isEmpty()) {
                str(S.desktop_payroll_no_timecards_exist_week)
            } else {
                str(S.desktop_payroll_select_crew_member)
            },
            modifier = Modifier.weight(1f),
            holidayPay = state.holidayPayFor(producer.timecard),
        )
    }
}

/** The rail's rows, with the names the crew list gives and the department it files them under. */
private fun PayrollUiState.railRows(crew: List<PayrollCrewRow>): List<RailRow> = crew.map { row ->
    RailRow(
        key = row.id,
        userId = row.userId,
        name = nameOf(row.userId),
        designation = roleOf(row.userId),
        department = departmentOf(row.userId),
        statusLabel = row.status.railLabel,
        statusTone = row.status.tone,
    )
}

/**
 * The pill's words. `Awaiting Approval` is shortened on the rail, as the web
 * shortens it — the pill is narrow and the full phrase wraps onto two lines.
 */
internal val TimecardStatus.railLabel: String
    get() = if (this == TimecardStatus.AwaitingApproval) str(S.ds_sent_filter_awaiting) else label
