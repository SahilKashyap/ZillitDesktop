package com.zillit.desktop.feature.timecard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
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
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitErrorState
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.timecard.domain.AllowanceType
import com.zillit.desktop.feature.timecard.domain.DayType
import com.zillit.desktop.feature.timecard.domain.LocalWeek
import com.zillit.desktop.feature.timecard.domain.Timecard
import com.zillit.desktop.feature.timecard.domain.TimecardDay
import com.zillit.desktop.feature.timecard.domain.TimecardStatus

/**
 * The Timecards tool.
 *
 * ## A grid, not a form
 *
 * The week is entered as seven rows with the same columns, because that is
 * how a paper timecard reads and how crew already think about it. A wizard
 * over the same data would be seven times the clicking for a week that often
 * differs from the last one only in one day.
 */
@Composable
fun TimecardScreen(
    state: TimecardUiState,
    onEvent: (TimecardEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ZillitTheme.colors.surface)
                    .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                ZillitPageHeader(
                    eyebrow = str(S.dm_step9_title),
                    title = str(S.desktop_timecards),
                    description = str(S.desktop_timecard_header_blurb),
                    actions = {
                        ZillitButton(
                            text = str(S.refresh_text),
                            onClick = { onEvent(TimecardEvent.Refresh) },
                            variant = ButtonVariant.Tertiary,
                            size = ButtonSize.Small,
                            leadingIcon = ZillitIcons.Reload,
                            loading = state.loading,
                        )
                    },
                )
                ZillitTabStrip(
                    tabs = state.visibleDestinations.map { tab ->
                        ZillitTab(tab.slug, tab.label, count = tab.badgeKeys.sumOf { state.unread[it] ?: 0 })
                    },
                    activeId = state.destination.slug,
                    onSelect = { slug ->
                        TimecardDestination.entries.firstOrNull { it.slug == slug }
                            ?.let { onEvent(TimecardEvent.Open(it)) }
                    },
                )
            }
            ZillitDivider()
            OfflineBanner(state)

            val error = state.error
            when {
                error != null -> ZillitErrorState(
                    message = error.localised(),
                    onRetry = { onEvent(TimecardEvent.Refresh) },
                )

                state.destination == TimecardDestination.Edit -> WeekEditor(state, onEvent)
                else -> TimecardListPage(state, onEvent)
            }
        }

        TimecardPromptDialog(state.prompt, onEvent)
        ZillitToast(
            message = state.notice,
            onDismiss = { onEvent(TimecardEvent.ClearNotice) },
            tone = ZillitToastTone.Success,
        )
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod") // Filters, list and detail: one screen read together.
@Composable
private fun TimecardListPage(state: TimecardUiState, onEvent: (TimecardEvent) -> Unit) {
    val rows = state.rows
    val selected = state.selected
    val batchable = state.destination == TimecardDestination.ApprovalQueue ||
        state.destination == TimecardDestination.Processing

    Column(
        modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        if (state.destination == TimecardDestination.Processing) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                ZillitStatTile(
                    label = str(S.desktop_timecard_weeks_in_hand),
                    value = rows.size.toString(),
                    sub = str(S.desktop_timecard_this_processing_week),
                    icon = ZillitIcons.Clock,
                    modifier = Modifier.weight(1f),
                )
                ZillitStatTile(
                    label = str(S.desktop_timecard_ready_to_pay),
                    value = rows.count { it.status.isPayable }.toString(),
                    sub = str(S.desktop_timecard_final_approved_or_locked),
                    tone = StatusTone.Ready,
                    icon = ZillitIcons.Shield,
                    modifier = Modifier.weight(1f),
                )
                ZillitStatTile(
                    label = str(S.ah_addl_value_hint),
                    value = state.totalsByCurrency.entries.firstOrNull()
                        ?.let { Money.format(it.value, it.key) } ?: "—",
                    sub = str(S.desktop_timecard_net_across_weeks),
                    icon = ZillitIcons.Ledger,
                    modifier = Modifier.weight(1f),
                )
                ZillitStatTile(
                    label = str(S.desktop_paid),
                    value = rows.count { it.status.isPaid }.toString(),
                    sub = str(S.desktop_timecard_already_settled),
                    tone = StatusTone.Done,
                    icon = ZillitIcons.Wallet,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitSearchField(
                value = state.search,
                onValueChange = { onEvent(TimecardEvent.Search(it)) },
                placeholder = str(S.desktop_timecard_search_placeholder),
                modifier = Modifier.width(SEARCH_WIDTH),
            )
            ZillitText(
                text = if (rows.size == 1) str(S.desktop_week_count_one, rows.size) else str(
                    S.desktop_week_count_other,
                    rows.size,
                ),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (batchable && state.selection.isNotEmpty()) {
                ZillitButton(
                    text = str(S.desktop_approve_count, state.selection.size),
                    onClick = {
                        onEvent(
                            TimecardEvent.Ask(
                                TimecardPrompt.Confirm(
                                    TimecardConfirmAction.ApproveSelected,
                                    "",
                                    str(S.desktop_timecard_approve_selected_title, state.selection.size),
                                    str(S.desktop_timecard_approve_selected_message),
                                ),
                            ),
                        )
                    },
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
                if (state.viewer.isFinalApprover) {
                    ZillitButton(
                        text = str(S.desktop_lock_count, state.selection.size),
                        onClick = {
                            onEvent(
                                TimecardEvent.Ask(
                                    TimecardPrompt.Confirm(
                                        TimecardConfirmAction.LockSelected,
                                        "",
                                        str(S.desktop_timecard_lock_selected_title, state.selection.size),
                                        str(S.desktop_timecard_lock_selected_message),
                                    ),
                                ),
                            )
                        },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        enabled = !state.busy,
                    )
                }
                ZillitButton(
                    text = str(S.ah_clear),
                    onClick = { onEvent(TimecardEvent.ClearSelection) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
            if (state.destination == TimecardDestination.MyWeeks) {
                ZillitButton(
                    text = str(S.desktop_timecard_fill_in_this_week),
                    onClick = { onEvent(TimecardEvent.Open(TimecardDestination.Edit)) },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Edit,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            ZillitSectionCard(
                title = state.destination.label,
                icon = ZillitIcons.Clock,
                padded = false,
                modifier = Modifier.weight(LIST_WEIGHT).fillMaxHeight(),
            ) {
                ZillitDataTable(
                    rows = rows,
                    columns = timecardColumns(state, batchable, onEvent),
                    key = { it.id },
                    loading = state.loading,
                    onRowClick = { onEvent(TimecardEvent.Select(it.id)) },
                    isSelected = { it.id == state.selectedId },
                    emptyTitle = if (state.search.isBlank()) {
                        str(S.desktop_nothing_here)
                    } else {
                        str(S.dm_nda_empty_search)
                    },
                    emptyMessage = when (state.destination) {
                        TimecardDestination.ApprovalQueue -> str(S.desktop_timecard_empty_approval)
                        TimecardDestination.Outstanding -> str(S.desktop_timecard_empty_outstanding)
                        TimecardDestination.MyWeeks -> str(S.desktop_timecard_empty_my_weeks)
                        else -> str(S.desktop_timecard_empty_processing)
                    },
                )
            }

            ZillitSectionCard(
                title = str(S.desktop_timecard_week_detail),
                icon = ZillitIcons.Eye,
                padded = false,
                modifier = Modifier.weight(DETAIL_WEIGHT).fillMaxHeight(),
            ) {
                if (selected == null) {
                    ZillitEmptyState(
                        title = str(S.desktop_timecard_pick_a_week),
                        message = str(S.desktop_timecard_pick_a_week_message),
                        icon = ZillitIcons.Eye,
                    )
                } else {
                    TimecardDetail(state, selected, onEvent)
                }
            }
        }
    }
}

@Suppress("LongMethod") // One week, top to bottom.
@Composable
private fun TimecardDetail(state: TimecardUiState, card: Timecard, onEvent: (TimecardEvent) -> Unit) {
    ZillitScrollColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(text = card.crewName.ifBlank { card.userId }, style = ZillitTheme.typography.titleMedium)
                ZillitText(
                    text = str(S.desktop_sa_week_of, EpochDate.date(card.weekStarting).ifEmpty { "—" }) +
                        (card.designation?.let { " · ${it.localised()}" } ?: ""),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
            WeekStatusPill(card)
        }

        card.local?.let { LocalWeekNotice(it) }

        ZillitText(text = Money.format(card.net, card.currency), style = ZillitTheme.typography.displayLarge)
        ZillitText(
            text = str(S.desktop_timecard_hours_over_days, card.workedHours, card.days.count { it.dayType.isPaidWork }),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )

        card.queryNote?.takeIf { it.isNotBlank() }?.let {
            ZillitNotice(text = str(S.desktop_queried_value, it), tone = StatusTone.Pending, icon = ZillitIcons.Warning)
        }
        card.rejectionReason?.takeIf { it.isNotBlank() }?.let {
            ZillitNotice(
                text = str(S.desktop_rejected_value, it),
                tone = StatusTone.Rejected,
                icon = ZillitIcons.Warning,
            )
        }

        ZillitDivider()
        PayLine(str(S.desktop_payroll_basic), Money.format(card.basicPay, card.currency))
        PayLine(str(S.overtime), Money.format(card.overtimePay, card.currency))
        PayLine(str(S.allowances_label), Money.format(card.totalAllowances, card.currency))
        PayLine(str(S.desktop_additional_fees), Money.format(card.additionalFees, card.currency))
        if (card.deductions.isNotEmpty()) {
            PayLine(str(S.desktop_deductions), "-${Money.format(card.deductionTotal, card.currency)}")
            card.deductions.forEach { deduction ->
                ZillitText(
                    text = "· ${deduction.label} ${Money.format(deduction.amount, card.currency)}",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        }

        if (card.days.isNotEmpty()) {
            ZillitDivider()
            ZillitText(text = str(S.dm_ds_unit_days), style = ZillitTheme.typography.titleSmall)
            card.days.forEach { day -> DayRow(day) }
        }

        ZillitDivider()
        TimecardActions(state, card, onEvent)
    }
}

@Composable
private fun PayLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        ZillitText(text = value, style = ZillitTheme.typography.numeric, maxLines = 1)
    }
}

@Composable
private fun DayRow(day: TimecardDay) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = EpochDate.date(day.date).ifEmpty { "—" },
            style = ZillitTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        ZillitStatusPill(
            label = day.dayType.label,
            tone = if (day.dayType.isPaidWork) StatusTone.Progress else StatusTone.Neutral,
        )
        ZillitText(
            text = listOfNotNull(day.callTime, day.wrapTime).joinToString(" – ").ifBlank { "—" },
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
            maxLines = 1,
        )
        ZillitText(
            text = "${day.workedHours}h",
            style = ZillitTheme.typography.numeric,
            maxLines = 1,
        )
    }
}

@Suppress("LongMethod") // A rights table; flattening it is what makes it readable.
@Composable
private fun TimecardActions(state: TimecardUiState, card: Timecard, onEvent: (TimecardEvent) -> Unit) {
    // A week that is only on this computer can be queued for submission behind
    // its save — once — and nothing else; the outbox owns retry and discard.
    if (card.local.blocksActions()) return
    val actions = buildList {
        // Own weeks from `my-summary` carry no user id at all (the slim
        // projection has no owner field — MyTimecardsModule.jsx:126-167), so
        // ownership rides the route they came from, not an id comparison.
        if ((card.ownedByViewer || card.userId == state.viewer.userId) && card.isEditable) {
            add(
                Triple(str(S.submit), ButtonVariant.Primary) {
                    TimecardPrompt.Confirm(
                        TimecardConfirmAction.Submit,
                        card.id,
                        str(S.desktop_timecard_submit_this_week),
                        str(S.desktop_timecard_submit_message),
                    ) as TimecardPrompt
                },
            )
        }
        if (state.destination == TimecardDestination.ApprovalQueue && state.viewer.isApprover) {
            add(
                Triple(str(S.approve), ButtonVariant.Primary) {
                    TimecardPrompt.Confirm(
                        TimecardConfirmAction.Approve,
                        card.id,
                        str(S.desktop_timecard_approve_this_week),
                        str(
                            S.desktop_timecard_approve_message,
                            Money.format(card.net, card.currency),
                            card.crewName.ifBlank { str(S.desktop_this_crew_member) },
                        ),
                    ) as TimecardPrompt
                },
            )
            add(
                Triple(str(S.ah_cd_query), ButtonVariant.Secondary) {
                    TimecardPrompt.WithReason(
                        TimecardReasonAction.Query,
                        card.id,
                        str(S.desktop_timecard_query_this_week),
                        str(S.desktop_timecard_query_label),
                    ) as TimecardPrompt
                },
            )
            add(
                Triple(str(S.reject), ButtonVariant.Danger) {
                    TimecardPrompt.WithReason(
                        TimecardReasonAction.Reject,
                        card.id,
                        str(S.desktop_timecard_reject_this_week),
                        str(S.desktop_timecard_reject_label),
                    ) as TimecardPrompt
                },
            )
        }
        if (state.viewer.isFinalApprover) {
            if (card.status == TimecardStatus.Approved) {
                add(
                    Triple(str(S.desktop_final_approve), ButtonVariant.Primary) {
                        TimecardPrompt.Confirm(
                            TimecardConfirmAction.FinalApprove,
                            card.id,
                            str(S.desktop_timecard_final_approve_this_week),
                            str(S.desktop_timecard_final_approve_message),
                        ) as TimecardPrompt
                    },
                )
            }
            if (card.status.isPayable && !card.locked) {
                add(
                    Triple(str(S.desktop_lock), ButtonVariant.Secondary) {
                        TimecardPrompt.Confirm(
                            TimecardConfirmAction.Lock,
                            card.id,
                            str(S.desktop_timecard_lock_this_week),
                            str(S.desktop_timecard_lock_message),
                        ) as TimecardPrompt
                    },
                )
            }
            add(
                Triple(str(S.desktop_add_deduction), ButtonVariant.Tertiary) {
                    TimecardPrompt.Deduct(card.id) as TimecardPrompt
                },
            )
        }
    }

    if (actions.isEmpty()) {
        ZillitText(
            text = str(S.desktop_timecard_nothing_to_do),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        actions.chunked(ACTIONS_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                row.forEach { (label, variant, prompt) ->
                    ZillitButton(
                        text = label,
                        onClick = { onEvent(TimecardEvent.Ask(prompt())) },
                        variant = variant,
                        size = ButtonSize.Small,
                        enabled = !state.busy,
                    )
                }
            }
        }
    }
}

/** The week grid: seven rows, one per day. */
@Suppress("LongMethod") // The grid, its allowances and the save, read as one week.
@Composable
private fun WeekEditor(state: TimecardUiState, onEvent: (TimecardEvent) -> Unit) {
    val draft = state.draft
    if (draft == null) {
        Column(modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.xl)) {
            ZillitEmptyState(
                title = str(S.desktop_timecard_no_week_open),
                message = str(S.desktop_timecard_no_week_open_message),
                action = {
                    ZillitButton(
                        text = str(S.desktop_timecard_start_this_week),
                        onClick = { onEvent(TimecardEvent.LoadDraft(null)) },
                        size = ButtonSize.Small,
                    )
                },
            )
        }
        return
    }

    ZillitScrollColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        ZillitSectionCard(
            title = str(S.desktop_sa_week_of, EpochDate.date(draft.weekStarting).ifEmpty { "—" }),
            icon = ZillitIcons.Clock,
            meta = str(S.duration_hours, draft.workedHours) +
                if (draft.allowanceTotal > 0) {
                    " · " + str(
                        S.desktop_allowances_value,
                        Money.format(draft.allowanceTotal, state.selected?.currency),
                    )
                } else {
                    ""
                },
        ) {
            draft.days.forEachIndexed { index, day ->
                DayEditorRow(
                    index = index,
                    day = day,
                    onChange = { onEvent(TimecardEvent.EditDay(index, it)) },
                )
                AllowanceStrip(
                    dayIndex = index,
                    day = day,
                    types = state.viewer.metadata.allowanceTypes,
                    currency = state.selected?.currency,
                    onEvent = onEvent,
                )
            }
        }

        ZillitSectionCard(title = str(S.desktop_anything_else), icon = ZillitIcons.Info) {
            ZillitTextField(
                value = draft.notes,
                onValueChange = { onEvent(TimecardEvent.EditNotes(it)) },
                label = str(S.desktop_timecard_note_for_approver),
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.padding(ZillitTheme.spacing.xs))
            if (state.offline) {
                ZillitNotice(
                    text = str(S.desktop_timecard_offline_save_notice),
                    tone = StatusTone.InTransit,
                    icon = ZillitIcons.Info,
                )
            }
            ZillitButton(
                text = if (state.offline) {
                    str(S.desktop_save_and_send_when_online)
                } else {
                    str(S.desktop_timecard_save_this_week)
                },
                onClick = { onEvent(TimecardEvent.SaveDraft) },
                leadingIcon = ZillitIcons.Check,
                loading = state.busy,
            )
        }
    }
}

@Composable
private fun DayEditorRow(index: Int, day: TimecardDay, onChange: (TimecardDay) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.Bottom,
    ) {
        ZillitText(
            text = EpochDate.date(day.date).ifEmpty { str(S.desktop_ad_day_number, index + 1) },
            style = ZillitTheme.typography.bodyMedium,
            modifier = Modifier.width(DATE_WIDTH),
            maxLines = 1,
        )
        Column(modifier = Modifier.weight(1f)) {
            if (index == 0) {
                ZillitText(
                    text = str(S.dm_rule_day_type),
                    style = ZillitTheme.typography.label,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
            ZillitSelect(
                value = day.dayType,
                options = DayType.entries.filter { it != DayType.Unknown },
                onSelect = { onChange(day.copy(dayType = it)) },
                label = { it.label },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ZillitTextField(
            value = day.callTime.orEmpty(),
            onValueChange = { onChange(day.copy(callTime = it.takeIf(String::isNotBlank))) },
            label = if (index == 0) str(S.call) else null,
            placeholder = "07:00",
            // Times are only meaningful on a day that was worked; leaving them
            // enabled on a rest day invites entries nobody will pay.
            enabled = day.dayType.isPaidWork,
            modifier = Modifier.weight(SMALL_FIELD),
        )
        ZillitTextField(
            value = day.wrapTime.orEmpty(),
            onValueChange = { onChange(day.copy(wrapTime = it.takeIf(String::isNotBlank))) },
            label = if (index == 0) str(S.dm_ds_phase_wrap) else null,
            placeholder = "19:00",
            enabled = day.dayType.isPaidWork,
            modifier = Modifier.weight(SMALL_FIELD),
        )
        ZillitTextField(
            value = if (day.workedHours == 0.0) "" else day.workedHours.toString(),
            onValueChange = { onChange(day.copy(workedHours = it.trim().toDoubleOrNull() ?: 0.0)) },
            label = if (index == 0) str(S.dm_ds_unit_hours) else null,
            keyboardType = KeyboardType.Decimal,
            enabled = day.dayType.isPaidWork,
            modifier = Modifier.weight(SMALL_FIELD),
        )
    }
}

@Suppress("LongMethod") // Three dialog shapes plus the deduction form; splitting lets them drift.
/**
 * What was claimed on one day, and what else could be.
 *
 * Only on a day that was worked: an allowance on a rest day is almost always a
 * mis-click on the row above, and it is paid before anyone notices — so the
 * strip is simply absent there rather than offered and then refused on save.
 */
@Composable
private fun AllowanceStrip(
    dayIndex: Int,
    day: TimecardDay,
    types: List<AllowanceType>,
    currency: String?,
    onEvent: (TimecardEvent) -> Unit,
) {
    if (!day.dayType.isPaidWork) return
    if (types.isEmpty() && day.allowances.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = DATE_WIDTH, bottom = ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        day.allowances.forEachIndexed { claimIndex, allowance ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitStatusPill(label = allowance.label, tone = StatusTone.Progress)
                ZillitTextField(
                    value = if (allowance.amount == 0.0) "" else allowance.amount.toString(),
                    onValueChange = {
                        onEvent(
                            TimecardEvent.EditAllowance(
                                dayIndex,
                                claimIndex,
                                allowance.copy(amount = it.trim().toDoubleOrNull() ?: 0.0),
                            ),
                        )
                    },
                    label = null,
                    placeholder = "0.00",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.width(ALLOWANCE_FIELD),
                )
                ZillitTextField(
                    value = if (allowance.quantity == 1.0) "" else allowance.quantity.toString(),
                    onValueChange = {
                        onEvent(
                            TimecardEvent.EditAllowance(
                                dayIndex,
                                claimIndex,
                                allowance.copy(quantity = it.trim().toDoubleOrNull() ?: 1.0),
                            ),
                        )
                    },
                    label = null,
                    placeholder = "×1",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.width(QUANTITY_FIELD),
                )
                ZillitText(
                    text = Money.format(allowance.total, currency),
                    style = ZillitTheme.typography.numeric,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                ZillitButton(
                    text = "",
                    onClick = { onEvent(TimecardEvent.RemoveAllowance(dayIndex, claimIndex)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Trash,
                )
            }
        }

        if (types.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                types.forEach { type ->
                    val claimed = day.allowances.any { it.code == type.code }
                    // A claimed once-a-day allowance is shown as taken rather
                    // than offered again — pressing it would do nothing.
                    ZillitButton(
                        text = type.label,
                        onClick = { onEvent(TimecardEvent.AddAllowance(dayIndex, type)) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Add,
                        enabled = type.perUnit || !claimed,
                    )
                }
            }
        }
    }
}

@Suppress("LongMethod") // Three dialog shapes plus the deduction form; splitting lets them drift.
@Composable
private fun TimecardPromptDialog(prompt: TimecardPrompt?, onEvent: (TimecardEvent) -> Unit) {
    val shown = remember(prompt) { prompt }
    ZillitDialogShell(
        title = when (shown) {
            is TimecardPrompt.Confirm -> shown.title
            is TimecardPrompt.WithReason -> shown.title
            is TimecardPrompt.Deduct -> str(S.desktop_add_a_deduction)
            null -> ""
        },
        icon = ZillitIcons.Info,
        visible = prompt != null,
        onDismiss = { onEvent(TimecardEvent.DismissPrompt) },
    ) {
        when (shown) {
            is TimecardPrompt.Confirm -> ZillitText(
                text = shown.message,
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
            )

            is TimecardPrompt.WithReason -> ZillitTextField(
                value = shown.reason,
                onValueChange = { onEvent(TimecardEvent.UpdatePrompt(shown.copy(reason = it))) },
                label = shown.label,
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )

            is TimecardPrompt.Deduct -> {
                ZillitTextField(
                    value = shown.label,
                    onValueChange = { onEvent(TimecardEvent.UpdatePrompt(shown.copy(label = it))) },
                    label = str(S.desktop_timecard_what_is_deducted),
                    modifier = Modifier.fillMaxWidth(),
                )
                ZillitTextField(
                    value = shown.amount,
                    onValueChange = { onEvent(TimecardEvent.UpdatePrompt(shown.copy(amount = it))) },
                    label = str(S.amount),
                    placeholder = "0.00",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.fillMaxWidth(),
                )
                // The web modal's third field is the GL code, not a reason —
                // the deduction wire has no reason (AddDeductionModal.jsx:127-132).
                ZillitTextField(
                    value = shown.nominalCode,
                    onValueChange = { onEvent(TimecardEvent.UpdatePrompt(shown.copy(nominalCode = it))) },
                    label = str(S.desktop_nominal_code_optional),
                    modifier = Modifier.fillMaxWidth(),
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
                onClick = { onEvent(TimecardEvent.DismissPrompt) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.confirm),
                onClick = { onEvent(TimecardEvent.ConfirmPrompt) },
                variant = if (shown.isDestructive()) ButtonVariant.Danger else ButtonVariant.Primary,
            )
        }
    }
}

private fun TimecardPrompt?.isDestructive(): Boolean = when (this) {
    is TimecardPrompt.WithReason -> action == TimecardReasonAction.Reject
    is TimecardPrompt.Confirm -> action == TimecardConfirmAction.Lock ||
        action == TimecardConfirmAction.LockSelected ||
        action == TimecardConfirmAction.MarkPaid

    else -> false
}

internal val TimecardStatus.tone: StatusTone
    get() = when (this) {
        TimecardStatus.Draft, TimecardStatus.Unknown -> StatusTone.Neutral
        TimecardStatus.Submitted, TimecardStatus.AwaitingApproval, TimecardStatus.Pending -> StatusTone.Pending
        TimecardStatus.Approved -> StatusTone.Progress
        TimecardStatus.FinalApproved -> StatusTone.Ready
        TimecardStatus.Locked -> StatusTone.InTransit
        TimecardStatus.Paid, TimecardStatus.Posted -> StatusTone.Done
        TimecardStatus.Queried, TimecardStatus.Unpaid -> StatusTone.Pending
        TimecardStatus.Rejected -> StatusTone.Rejected
    }

@Suppress("MagicNumber") // Column proportions.
private fun timecardColumns(
    state: TimecardUiState,
    batchable: Boolean,
    onEvent: (TimecardEvent) -> Unit,
): List<TableColumn<Timecard>> = buildList {
    if (batchable) {
        add(
            TableColumn(
                header = "",
                width = ColumnWidth.Fixed(CHECK_COLUMN),
                cell = { row ->
                    ZillitCheckbox(
                        checked = row.id in state.selection,
                        onCheckedChange = { onEvent(TimecardEvent.ToggleSelection(row.id)) },
                    )
                },
            ),
        )
    }
    add(textColumn(str(S.crew), ColumnWidth.Weight(1.4f)) { it.crewName.ifBlank { it.userId } })
    add(
        textColumn(str(S.weekly), ColumnWidth.Weight(1.1f), muted = true) {
            EpochDate.date(it.weekStarting).ifEmpty { "—" }
        },
    )
    add(
        textColumn(str(S.dm_ds_unit_days), ColumnWidth.Fixed(NUMBER_COLUMN), numeric = true) {
            it.totalDays.toString()
        },
    )
    add(
        textColumn(str(S.dm_ds_unit_hours), ColumnWidth.Fixed(NUMBER_COLUMN), numeric = true) {
            it.workedHours.toString()
        },
    )
    add(textColumn(str(S.desktop_net), ColumnWidth.Weight(1f), numeric = true) { Money.format(it.net, it.currency) })
    add(
        TableColumn(
            header = str(S.status),
            width = ColumnWidth.Fixed(STATUS_COLUMN),
            cell = { WeekStatusPill(it) },
        ),
    )
}

/**
 * The status pill, with one exception: a week that exists only on this
 * computer is not "Draft" — it is waiting to be sent, or could not be.
 */
@Composable
private fun WeekStatusPill(card: Timecard) {
    val local = card.local
    when {
        local == null -> ZillitStatusPill(card.status.label, tone = card.status.tone, dot = true)
        local.failed -> ZillitStatusPill(str(S.dm_nda_status_not_sent), tone = StatusTone.Rejected, dot = true)
        local.submitQueued -> ZillitStatusPill(
            str(S.desktop_submitting_when_online),
            tone = StatusTone.InTransit,
            dot = true,
        )
        else -> ZillitStatusPill(str(S.desktop_waiting_to_send), tone = StatusTone.InTransit, dot = true)
    }
}

private fun LocalWeek?.blocksActions(): Boolean = this != null && (failed || submitQueued)

/** Why a local-only week is where it is, and what to do if it could not be sent. */
@Composable
private fun LocalWeekNotice(local: LocalWeek) {
    ZillitNotice(
        text = when {
            local.failed ->
                str(S.desktop_timecard_local_failed, local.error ?: str(S.desktop_the_server_refused_it))
            local.submitQueued -> str(S.desktop_timecard_local_submit_queued)
            else -> str(S.desktop_timecard_local_saved)
        },
        tone = if (local.failed) StatusTone.Rejected else StatusTone.InTransit,
        icon = ZillitIcons.Info,
    )
}

/**
 * The line under the tabs when the list is a saved copy: the network is gone
 * and these are the weeks as of the last time it answered.
 */
@Composable
private fun OfflineBanner(state: TimecardUiState) {
    val since = state.staleSince ?: return
    ZillitNotice(
        text = str(S.desktop_timecard_offline_banner, EpochDate.dateTime(since)),
        tone = StatusTone.Pending,
        icon = ZillitIcons.Info,
        modifier = Modifier.padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.sm),
    )
}

private const val LIST_WEIGHT = 1.6f
private const val DETAIL_WEIGHT = 1f
private const val ACTIONS_PER_ROW = 3
private const val SMALL_FIELD = 0.7f
private val SEARCH_WIDTH = 320.dp
private val STATUS_COLUMN = 150.dp
private val NUMBER_COLUMN = 70.dp
private val CHECK_COLUMN = 40.dp
private val DATE_WIDTH = 120.dp
private val ALLOWANCE_FIELD = 110.dp
private val QUANTITY_FIELD = 70.dp
