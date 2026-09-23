package com.zillit.desktop.feature.payroll.ui.history

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
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitErrorState
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitVerticalDivider
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import com.zillit.desktop.feature.payroll.domain.TimecardStatus
import com.zillit.desktop.feature.payroll.ui.HistoryEvent
import com.zillit.desktop.feature.payroll.ui.HistoryTab
import com.zillit.desktop.feature.payroll.ui.PayrollDestination
import com.zillit.desktop.feature.payroll.ui.PayrollEvent
import com.zillit.desktop.feature.payroll.ui.PayrollUiState
import com.zillit.desktop.feature.payroll.ui.components.PayrollTopBar
import com.zillit.desktop.feature.payroll.ui.components.WeekNavigator
import com.zillit.desktop.feature.payroll.ui.components.historyTone
import com.zillit.desktop.feature.payroll.ui.historyGroups

/**
 * Payroll History — the web's `AccountantPayrollModule`.
 *
 * The week's paid queue down the left, grouped by department; the open crew
 * member on the right with the week's seven totals and three tabs — the pay
 * code breakdown, a payslip preview with its PDF, and the timecard's own
 * audit trail. Amounts stay off the list, as on the web; only the detail
 * shows money.
 */
@Composable
fun HistoryPage(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        PayrollTopBar(crumb = PayrollDestination.History.label, onBack = { onEvent(PayrollEvent.BackToLanding) })
        Row(Modifier.fillMaxSize()) {
            HistoryRail(state, onEvent, Modifier.width(RAIL_WIDTH).fillMaxHeight())
            ZillitVerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val error = state.history.error
                if (error != null) {
                    ZillitErrorState(message = error.localised(), onRetry = { onEvent(HistoryEvent.Refresh) })
                } else {
                    HistoryDetail(state, onEvent)
                }
            }
        }
    }
}

/**
 * The rail: week, the two counts, search and the grouped queue.
 *
 * No post button and no ticks: the web took History's posting out on
 * 2026-07-09 (1f836cbe7) — the ledger is posted from Payroll Run's Journal
 * Ledger, line by line. The web still draws row checkboxes here that no
 * longer lead anywhere; they are not copied.
 */
@Composable
private fun HistoryRail(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit, modifier: Modifier) {
    val history = state.history
    val week = history.weekStarting
    Column(
        modifier = modifier.padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        WeekNavigator(
            label = week?.let(PayPeriod::rangeLabel).orEmpty(),
            canGoNext = week != null && week < state.currentWeek,
            isCurrent = week == state.currentWeek,
            onShift = { onEvent(HistoryEvent.ShiftWeek(it)) },
            onCurrent = { onEvent(HistoryEvent.CurrentWeek) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            MiniCount(history.readyIds.size, str(S.ah_ready_to_post), StatusTone.Ready, Modifier.weight(1f))
            MiniCount(history.postedCount, str(S.ah_step_posted), StatusTone.Escalated, Modifier.weight(1f))
        }
        ZillitSearchField(
            value = history.search,
            onValueChange = { onEvent(HistoryEvent.Search(it)) },
            placeholder = str(S.desktop_payroll_search_name_designation_department),
            modifier = Modifier.fillMaxWidth(),
        )
        val groups = state.historyGroups()
        ZillitScrollColumn(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .clip(ZillitTheme.shapes.large)
                .background(ZillitTheme.colors.surface)
                .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.large),
        ) {
            when {
                history.loading || !state.metadataLoaded -> repeat(SKELETON_ROWS) { SkeletonRow() }
                history.rows.isEmpty() -> RailNote(str(S.desktop_payroll_no_timecards_on_week))
                groups.isEmpty() -> RailNote(str(S.desktop_payroll_no_crew_match, history.search))
                else -> groups.forEach { (department, rows) ->
                    ZillitText(
                        text = department.uppercase(),
                        style = ZillitTheme.typography.columnHeader,
                        color = ZillitTheme.colors.textMuted,
                        modifier = Modifier.fillMaxWidth().background(ZillitTheme.colors.surfaceSunken)
                            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
                    )
                    rows.forEach { row -> QueueRow(state, row, onEvent) }
                }
            }
        }
    }
}

/** One crew row: name, role and where the timecard stands. */
@Composable
private fun QueueRow(state: PayrollUiState, row: PayrollTimecard, onEvent: (PayrollEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val active = state.history.selectedId == row.id
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (active) colors.surfaceSelected else colors.surface)
            .clickable { onEvent(HistoryEvent.Select(row.id)) }
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = state.nameOf(row.userId),
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = if (active) colors.accentText else colors.textPrimary,
                maxLines = 1,
            )
            ZillitText(
                text = state.roleOf(row.userId).ifBlank { str(S.crew) },
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
            )
        }
        ZillitStatusPill(label = row.status.historyLabel, tone = row.status.historyTone)
    }
}

@Composable
private fun MiniCount(count: Int, label: String, tone: StatusTone, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(ZillitTheme.colors.surface)
            .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = count.toString(),
            style = ZillitTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = when (tone) {
                StatusTone.Ready -> ZillitTheme.colors.success
                else -> ZillitTheme.colors.violet
            },
        )
        ZillitText(text = label, style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted)
    }
}

@Composable
private fun SkeletonRow() {
    Row(
        Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitSkeletonBar(Modifier.width(SKELETON_AVATAR))
        ZillitSkeletonBar(Modifier.weight(1f))
    }
}

@Composable
private fun RailNote(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textMuted,
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg),
    )
}

/** The right pane: the open crew member, or why there is none. */
@Composable
private fun HistoryDetail(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit) {
    val history = state.history
    val row = history.selectedRow
    when {
        history.loading || !state.metadataLoaded -> Column(
            Modifier.fillMaxSize().padding(ZillitTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) { repeat(SKELETON_ROWS) { ZillitSkeletonBar(Modifier.fillMaxWidth()) } }
        row == null -> ZillitEmptyState(title = str(S.desktop_payroll_no_crew_on_week), icon = ZillitIcons.Users)
        else -> ZillitScrollColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(ZillitTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            CrewHeader(state, row)
            HistoryFigures(state)
            ZillitTabStrip(
                tabs = listOf(
                    ZillitTab(HistoryTab.PayCode.name, str(S.desktop_payroll_pay_code_breakdown)),
                    ZillitTab(HistoryTab.Payslip.name, str(S.desktop_payroll_payslip_preview)),
                    ZillitTab(HistoryTab.Audit.name, str(S.dm_history_title)),
                ),
                activeId = history.tab.name,
                onSelect = { id -> onEvent(HistoryEvent.Tab(HistoryTab.valueOf(id))) },
            )
            when (history.tab) {
                HistoryTab.PayCode -> PayCodeTab(state)
                HistoryTab.Payslip -> PayslipTab(state, row, onEvent)
                HistoryTab.Audit -> AuditTab(state)
            }
        }
    }
}

/** Name, role · department · week ending, the posting date once posted, and the holiday-pay treatment. */
@Composable
private fun CrewHeader(state: PayrollUiState, row: PayrollTimecard) {
    val colors = ZillitTheme.colors
    val week = state.history.weekStarting
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitAvatar(name = state.nameOf(row.userId), userId = row.userId, size = AVATAR)
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = state.nameOf(row.userId),
                style = ZillitTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            val effective = state.history.detail?.effectiveDate
            ZillitText(
                text = listOfNotNull(
                    state.roleOf(row.userId).ifBlank { str(S.crew) },
                    state.departmentOf(row.userId),
                    week?.let { str(S.desktop_payroll_week_ending, PayPeriod.weekEnding(it)) },
                    effective?.let { str(S.desktop_payroll_effective_short, PayPeriod.dayMonthYear(it)) },
                ).joinToString(" · "),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
        state.history.deal?.holidayPayTreatment?.let { treatment ->
            ZillitStatusPill(
                label = str(
                    if (treatment == "incl") S.desktop_payroll_hp_inclusive else S.desktop_payroll_hp_exclusive,
                ),
                tone = if (treatment == "incl") StatusTone.Ready else StatusTone.Pending,
            )
        }
        ZillitStatusPill(label = str(S.desktop_payroll_rates_visible), tone = StatusTone.Escalated)
        Spacer(Modifier.width(ZillitTheme.spacing.xs))
    }
}

/** The history pill's words — the web's `STATUS_PILL`: approved reads "Ready", queried "Review". */
private val TimecardStatus.historyLabel: String
    get() = when (this) {
        TimecardStatus.Approved -> str(S.dd_csv_status_ready)
        TimecardStatus.Queried -> str(S.av_review)
        TimecardStatus.AwaitingApproval, TimecardStatus.Submitted -> str(S.pending)
        else -> label
    }

private val RAIL_WIDTH = 320.dp
private val AVATAR = 48.dp
private val SKELETON_AVATAR = 32.dp
private const val SKELETON_ROWS = 6
