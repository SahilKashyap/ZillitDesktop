package com.zillit.desktop.feature.payroll.ui

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitErrorState
import com.zillit.desktop.core.designsystem.component.ZillitMeter
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.payroll.domain.PayrollLine
import com.zillit.desktop.feature.payroll.domain.PayrollWeek
import com.zillit.desktop.feature.payroll.domain.TimecardStatus
import com.zillit.desktop.feature.payroll.domain.WeekStatus
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The Payroll tool.
 *
 * ## Weeks on the left, the open week's crew on the right
 *
 * Payroll is worked a week at a time: the week's timecards are checked, the
 * approved ones are marked paid, and the paid ones are posted to the ledger in
 * a batch. There is no "run" object behind any of this — the week is the unit,
 * and every figure in the header is derived from the rows underneath it, so
 * the two can never disagree.
 */
@Composable
fun PayrollScreen(
    state: PayrollUiState,
    onEvent: (PayrollEvent) -> Unit,
    modifier: Modifier = Modifier,
    today: () -> Long = { 0L },
) {
    Box(modifier = modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ZillitTheme.colors.surface)
                    .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.lg),
            ) {
                ZillitPageHeader(
                    eyebrow = str(S.dm_section_payroll),
                    title = str(S.desktop_payroll_runs),
                    description = str(S.desktop_payroll_description),
                    actions = {
                        ZillitButton(
                            text = str(S.refresh_text),
                            onClick = { onEvent(PayrollEvent.Refresh) },
                            variant = ButtonVariant.Tertiary,
                            size = ButtonSize.Small,
                            leadingIcon = ZillitIcons.Reload,
                            loading = state.loading,
                        )
                    },
                )
            }
            ZillitDivider()

            val error = state.error
            if (error != null) {
                ZillitErrorState(
                    message = error.localised(),
                    onRetry = { onEvent(PayrollEvent.Refresh) },
                )
            } else {
                PayrollBody(state, onEvent, today)
            }
        }

        PayrollPromptDialog(state, onEvent)

        // Over the grid: the split is read against the payslip beside it, and
        // the week behind stays where it was.
        LineDetailDialog(state, onEvent)
        ZillitToast(
            message = state.notice,
            onDismiss = { onEvent(PayrollEvent.ClearNotice) },
            tone = ZillitToastTone.Success,
        )
    }
}

@Suppress("LongMethod") // Tiles, the week list and the crew grid: one working screen.
@Composable
private fun PayrollBody(
    state: PayrollUiState,
    onEvent: (PayrollEvent) -> Unit,
    today: () -> Long,
) {
    val week = state.week

    Column(
        modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            ZillitStatTile(
                label = str(S.desktop_payroll_crew_this_week),
                value = week.crewCount.toString(),
                sub = str(S.desktop_payroll_ready_to_pay_count, week.payableLines.size),
                icon = ZillitIcons.Users,
                modifier = Modifier.weight(1f),
            )
            ZillitStatTile(
                label = str(S.ah_queried),
                value = week.queriedCount.toString(),
                sub = if (week.queriedCount == 0) {
                    str(S.desktop_payroll_nothing_outstanding)
                } else {
                    str(S.desktop_payroll_held_for_answer)
                },
                tone = if (week.queriedCount == 0) StatusTone.Done else StatusTone.Rejected,
                icon = ZillitIcons.Warning,
                modifier = Modifier.weight(1f),
            )
            ZillitStatTile(
                label = str(S.desktop_gross),
                value = Money.format(week.grossTotal, week.currency),
                sub = str(S.desktop_payroll_before_deductions),
                icon = ZillitIcons.Ledger,
                modifier = Modifier.weight(1f),
            )
            ZillitStatTile(
                label = str(S.desktop_payroll_net_to_pay),
                value = Money.format(week.netTotal, week.currency),
                sub = str(S.desktop_payroll_after_deducted, Money.format(week.deductionsTotal, week.currency)),
                tone = StatusTone.Ready,
                icon = ZillitIcons.Bank,
                modifier = Modifier.weight(1f),
            )
        }

        if (week.queriedCount > 0) {
            ZillitNotice(
                text = str(S.desktop_payroll_queried_notice, week.queriedCount),
                tone = StatusTone.Rejected,
                icon = ZillitIcons.Warning,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            ZillitSectionCard(
                title = str(S.dm_ds_unit_weeks),
                icon = ZillitIcons.Ledger,
                meta = str(S.desktop_most_recent_first),
                padded = false,
                modifier = Modifier.weight(WEEKS_WEIGHT).fillMaxHeight(),
            ) {
                ZillitDataTable(
                    rows = state.weekOptions,
                    columns = weekColumns(state),
                    key = { it },
                    loading = state.loading && state.weekOptions.isEmpty(),
                    onRowClick = { onEvent(PayrollEvent.SelectWeek(it)) },
                    isSelected = { it == state.weekStarting },
                    emptyTitle = str(S.desktop_payroll_no_weeks),
                    emptyMessage = str(S.desktop_payroll_no_weeks_message),
                )
            }

            Column(
                modifier = Modifier.weight(CREW_WEIGHT).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                WeekToolbar(state, week, onEvent, today)
                ZillitSectionCard(
                    title = EpochDate.date(state.weekStarting)
                        .takeIf { it.isNotEmpty() }
                        ?.let { str(S.desktop_payroll_crew_for_week, it) }
                        ?: str(S.desktop_payroll_crew_for_this_week),
                    icon = ZillitIcons.Users,
                    meta = str(S.desktop_payroll_timecard_count_status, state.lines.size, week.status.label),
                    padded = false,
                    modifier = Modifier.fillMaxHeight(),
                ) {
                    ZillitDataTable(
                        rows = state.visibleLines,
                        columns = lineColumns(state, onEvent),
                        key = { it.id },
                        // Opening a line is how a questioned figure gets
                        // answered, so the whole row is the affordance.
                        onRowClick = { onEvent(PayrollEvent.OpenLine(it.crewId)) },
                        isSelected = { it.crewId == state.openCrewId },
                        loading = state.loading,
                        emptyTitle = if (state.search.isBlank()) {
                            str(S.desktop_payroll_no_timecards_this_week)
                        } else {
                            str(S.desktop_nobody_matches_search)
                        },
                        emptyMessage = str(S.desktop_payroll_no_timecards_message),
                    )
                }
            }
        }
    }
}

/**
 * Search, progress and the two batch actions.
 *
 * Paying and posting are offered in that order and only where they apply: a
 * Post button beside a week nobody has paid yet would move nothing, and the
 * server's silent skip would make that look like it had worked.
 */
@Suppress("LongMethod") // Search, progress and both batch actions are one control strip.
@Composable
private fun WeekToolbar(
    state: PayrollUiState,
    week: PayrollWeek,
    onEvent: (PayrollEvent) -> Unit,
    today: () -> Long,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = state.search,
            onValueChange = { onEvent(PayrollEvent.Search(it)) },
            placeholder = str(S.desktop_search_crew),
            modifier = Modifier.width(SEARCH_WIDTH),
        )
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = if (state.selection.isEmpty()) {
                    str(S.desktop_payroll_posted_of_crew, week.postedCount, week.crewCount)
                } else {
                    str(
                        S.desktop_payroll_selected_total,
                        state.selection.size,
                        Money.format(state.selectedTotal, week.currency),
                    )
                },
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
            ZillitMeter(
                fraction = week.postedFraction,
                tone = if (week.status == WeekStatus.Posted) StatusTone.Done else StatusTone.Progress,
            )
        }

        if (state.viewer.canOperate) {
            val payable = state.selectedPayable
            val postable = state.selectedPostable

            ZillitButton(
                text = if (state.selection.isEmpty()) {
                    str(S.desktop_payroll_select_approved, week.payableLines.size)
                } else {
                    str(S.desktop_payroll_mark_paid, payable.size)
                },
                onClick = {
                    if (state.selection.isEmpty()) {
                        onEvent(PayrollEvent.SelectAllPayable)
                    } else {
                        onEvent(
                            PayrollEvent.Ask(
                                PayrollPrompt.Confirm(
                                    action = PayrollConfirmAction.MarkPaid,
                                    ids = payable.map { it.id },
                                    title = str(S.desktop_payroll_mark_paid_title, payable.size),
                                    message = str(
                                        S.desktop_payroll_mark_paid_message,
                                        Money.format(payable.sumOf { it.net }, week.currency),
                                    ),
                                ),
                            ),
                        )
                    }
                },
                size = ButtonSize.Small,
                enabled = !state.busy &&
                    (state.selection.isEmpty() && week.payableLines.isNotEmpty() || payable.isNotEmpty()),
            )

            if (state.viewer.canPost) {
                ZillitButton(
                    text = str(S.desktop_payroll_post_to_ledger_count, postable.size),
                    onClick = {
                        onEvent(
                            PayrollEvent.Ask(
                                PayrollPrompt.Post(
                                    ids = postable.map { it.id },
                                    effectiveDate = today(),
                                ),
                            ),
                        )
                    },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                    enabled = !state.busy && postable.isNotEmpty(),
                )
            }
        }
    }
}

@Suppress("LongMethod") // The post dialog carries its two required fields inline.
@Composable
private fun PayrollPromptDialog(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit) {
    val prompt = state.prompt
    val shown = remember(prompt) { prompt }
    ZillitDialogShell(
        title = when (shown) {
            is PayrollPrompt.Confirm -> shown.title
            is PayrollPrompt.Post -> str(S.desktop_payroll_post_title, shown.ids.size)
            null -> ""
        },
        icon = ZillitIcons.Info,
        visible = prompt != null,
        onDismiss = { onEvent(PayrollEvent.DismissPrompt) },
    ) {
        when (shown) {
            is PayrollPrompt.Confirm -> ZillitText(
                text = shown.message,
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
            )

            is PayrollPrompt.Post -> {
                ZillitText(
                    text = str(S.desktop_payroll_post_explainer),
                    style = ZillitTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textSecondary,
                )
                ZillitSelect(
                    value = shown.bankId,
                    options = listOf(null) + state.bankAccounts.map { it.id },
                    onSelect = { onEvent(PayrollEvent.UpdatePrompt(shown.copy(bankId = it))) },
                    label = { id ->
                        state.bankAccounts.firstOrNull { it.id == id }?.display
                            ?: str(S.desktop_payroll_choose_settling_account)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.bankAccounts.isEmpty()) {
                    ZillitNotice(
                        text = str(S.desktop_payroll_no_bank_accounts),
                        tone = StatusTone.Escalated,
                        icon = ZillitIcons.Warning,
                    )
                }
                ZillitText(
                    text = str(S.desktop_payroll_effective_date, EpochDate.date(shown.effectiveDate)),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }

            null -> Unit
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(PayrollEvent.DismissPrompt) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = if (shown is PayrollPrompt.Post) str(S.ah_post_to_ledger) else str(S.confirm),
                onClick = { onEvent(PayrollEvent.ConfirmPrompt) },
                variant = if (shown is PayrollPrompt.Post) ButtonVariant.Danger else ButtonVariant.Primary,
                // Disabled rather than failing on click: the missing account is
                // named right above, so a refusal here would only repeat it.
                enabled = shown !is PayrollPrompt.Post || !shown.bankId.isNullOrBlank(),
            )
        }
    }
}

@Suppress("MagicNumber") // Column proportions.
private fun weekColumns(state: PayrollUiState): List<TableColumn<Long>> = listOf(
    textColumn(str(S.desktop_payroll_week_starting), ColumnWidth.Weight(1.4f)) {
        EpochDate.date(it).ifEmpty { "—" }
    },
    TableColumn(
        header = "",
        width = ColumnWidth.Fixed(STATUS_COLUMN),
        cell = { weekStarting ->
            // Only the open week has rows to describe; the others are offered
            // as somewhere to go, not summarised. Claiming otherwise would mean
            // a figure per week and a request per week to compute it.
            if (weekStarting == state.weekStarting) {
                ZillitStatusPill(state.week.status.label, tone = state.week.status.tone, dot = true)
            }
        },
    ),
)

@Suppress("MagicNumber", "LongMethod") // Column proportions, and the tick box beside them.
private fun lineColumns(
    state: PayrollUiState,
    onEvent: (PayrollEvent) -> Unit,
): List<TableColumn<PayrollLine>> = listOf(
    TableColumn(
        header = "",
        width = ColumnWidth.Fixed(CHECK_COLUMN),
        cell = { line ->
            // Only rows a batch could actually move are tickable. The server
            // silently skips the rest, and a tick that turns out to have done
            // nothing is worse than no tick at all.
            if (state.viewer.canOperate && (line.status.isPayable || line.status.isPostable)) {
                ZillitCheckbox(
                    checked = line.id in state.selection,
                    onCheckedChange = { onEvent(PayrollEvent.ToggleSelection(line.id)) },
                )
            }
        },
    ),
    TableColumn(
        header = str(S.crew),
        width = ColumnWidth.Weight(1.5f),
        cell = { line ->
            Column {
                ZillitText(
                    text = line.crewName.ifBlank { line.crewId },
                    style = ZillitTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                // A line whose parts do not reach its gross is called out on
                // the row rather than only in a detail nobody opens.
                if (line.figuresDisagree) {
                    ZillitText(
                        text = str(S.desktop_payroll_figures_disagree),
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.danger,
                        maxLines = 1,
                    )
                } else {
                    line.designation?.takeIf { it.isNotBlank() }?.let {
                        ZillitText(
                            text = it.localised(),
                            style = ZillitTheme.typography.labelSmall,
                            color = ZillitTheme.colors.textMuted,
                            maxLines = 1,
                        )
                    }
                }
            }
        },
    ),
    textColumn(str(S.desktop_payroll_basic), ColumnWidth.Weight(1f), numeric = true) {
        Money.format(it.basicPay, it.currency)
    },
    textColumn(str(S.overtime), ColumnWidth.Weight(1f), numeric = true) { Money.format(it.overtimePay, it.currency) },
    textColumn(str(S.allowances_label), ColumnWidth.Weight(1f), numeric = true) {
        Money.format(it.allowances, it.currency)
    },
    textColumn(str(S.desktop_net), ColumnWidth.Weight(1f), numeric = true) { Money.format(it.net, it.currency) },
    TableColumn(
        header = str(S.status),
        width = ColumnWidth.Fixed(LINE_STATUS_COLUMN),
        cell = { ZillitStatusPill(it.status.label, tone = it.status.tone, dot = true) },
    ),
    TableColumn(
        header = "",
        width = ColumnWidth.Fixed(ACTION_COLUMN),
        cell = { line ->
            if (state.viewer.canOperate && line.status == TimecardStatus.Paid) {
                ZillitButton(
                    text = str(S.desktop_payroll_unpay),
                    onClick = {
                        onEvent(
                            PayrollEvent.Ask(
                                PayrollPrompt.Confirm(
                                    action = PayrollConfirmAction.MarkUnpaid,
                                    ids = listOf(line.id),
                                    title = str(S.desktop_payroll_unpay_title),
                                    message = str(
                                        S.desktop_payroll_unpay_message,
                                        Money.format(line.net, line.currency),
                                        line.crewName.ifBlank { str(S.desktop_this_crew_member) },
                                    ),
                                ),
                            ),
                        )
                    },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
            } else {
                ZillitText(
                    text = "—",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        },
    ),
)

internal val WeekStatus.tone: StatusTone
    get() = when (this) {
        WeekStatus.Empty -> StatusTone.Neutral
        WeekStatus.InProgress -> StatusTone.Progress
        WeekStatus.Queried -> StatusTone.Rejected
        WeekStatus.ReadyToPay -> StatusTone.Pending
        WeekStatus.ReadyToPost -> StatusTone.InTransit
        WeekStatus.Posted -> StatusTone.Done
    }

internal val TimecardStatus.tone: StatusTone
    get() = when (this) {
        TimecardStatus.Draft, TimecardStatus.Unknown -> StatusTone.Neutral
        TimecardStatus.Submitted, TimecardStatus.AwaitingApproval -> StatusTone.Pending
        TimecardStatus.Approved -> StatusTone.Ready
        TimecardStatus.Queried -> StatusTone.Rejected
        TimecardStatus.Rejected -> StatusTone.Rejected
        TimecardStatus.Locked -> StatusTone.InTransit
        TimecardStatus.Paid -> StatusTone.Progress
        TimecardStatus.Posted -> StatusTone.Done
    }

private const val WEEKS_WEIGHT = 1f
private const val CREW_WEIGHT = 2.6f
private val SEARCH_WIDTH = 260.dp
private val STATUS_COLUMN = 130.dp
private val LINE_STATUS_COLUMN = 130.dp
private val CHECK_COLUMN = 40.dp
private val ACTION_COLUMN = 110.dp
