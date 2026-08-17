package com.zillit.desktop.feature.timecard.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
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
import com.zillit.desktop.feature.timecard.domain.AllowanceType
import com.zillit.desktop.feature.timecard.domain.DayType
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
                    eyebrow = "Payroll",
                    title = "Timecards",
                    description = "File your week, approve your department's, and hand them to payroll.",
                    actions = {
                        ZillitButton(
                            text = "Refresh",
                            onClick = { onEvent(TimecardEvent.Refresh) },
                            variant = ButtonVariant.Tertiary,
                            size = ButtonSize.Small,
                            leadingIcon = ZillitIcons.Reload,
                            loading = state.loading,
                        )
                    },
                )
                ZillitTabStrip(
                    tabs = state.visibleDestinations.map { ZillitTab(it.slug, it.label) },
                    activeId = state.destination.slug,
                    onSelect = { slug ->
                        TimecardDestination.entries.firstOrNull { it.slug == slug }
                            ?.let { onEvent(TimecardEvent.Open(it)) }
                    },
                )
            }
            ZillitDivider()

            val error = state.error
            when {
                error != null -> ZillitErrorState(
                    message = error.userMessage,
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
                    label = "Weeks in hand",
                    value = rows.size.toString(),
                    sub = "This processing week",
                    icon = ZillitIcons.Clock,
                    modifier = Modifier.weight(1f),
                )
                ZillitStatTile(
                    label = "Ready to pay",
                    value = rows.count { it.status.isPayable }.toString(),
                    sub = "Final approved or locked",
                    tone = StatusTone.Ready,
                    icon = ZillitIcons.Shield,
                    modifier = Modifier.weight(1f),
                )
                ZillitStatTile(
                    label = "Value",
                    value = state.totalsByCurrency.entries.firstOrNull()
                        ?.let { Money.format(it.value, it.key) } ?: "—",
                    sub = "Net across these weeks",
                    icon = ZillitIcons.Ledger,
                    modifier = Modifier.weight(1f),
                )
                ZillitStatTile(
                    label = "Paid",
                    value = rows.count { it.status.isPaid }.toString(),
                    sub = "Already settled",
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
                placeholder = "Search by crew member or week",
                modifier = Modifier.width(SEARCH_WIDTH),
            )
            ZillitText(
                text = "${rows.size} week${if (rows.size == 1) "" else "s"}",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (batchable && state.selection.isNotEmpty()) {
                ZillitButton(
                    text = "Approve ${state.selection.size}",
                    onClick = {
                        onEvent(
                            TimecardEvent.Ask(
                                TimecardPrompt.Confirm(
                                    TimecardConfirmAction.ApproveSelected,
                                    "",
                                    "Approve ${state.selection.size} timecard(s)",
                                    "They move on to the next stage together.",
                                ),
                            ),
                        )
                    },
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
                if (state.viewer.isFinalApprover) {
                    ZillitButton(
                        text = "Lock ${state.selection.size}",
                        onClick = {
                            onEvent(
                                TimecardEvent.Ask(
                                    TimecardPrompt.Confirm(
                                        TimecardConfirmAction.LockSelected,
                                        "",
                                        "Lock ${state.selection.size} timecard(s)",
                                        "Locked weeks cannot change under a payroll run.",
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
                    text = "Clear",
                    onClick = { onEvent(TimecardEvent.ClearSelection) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
            if (state.destination == TimecardDestination.MyWeeks) {
                ZillitButton(
                    text = "Fill in this week",
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
                    emptyTitle = if (state.search.isBlank()) "Nothing here" else "Nothing matches that search",
                    emptyMessage = when (state.destination) {
                        TimecardDestination.ApprovalQueue -> "Weeks submitted by your department appear here."
                        TimecardDestination.Outstanding -> "Everyone has filed. Nothing to chase."
                        TimecardDestination.MyWeeks -> "Fill in this week to get started."
                        else -> "Weeks payroll is processing appear here."
                    },
                )
            }

            ZillitSectionCard(
                title = "Week detail",
                icon = ZillitIcons.Eye,
                padded = false,
                modifier = Modifier.weight(DETAIL_WEIGHT).fillMaxHeight(),
            ) {
                if (selected == null) {
                    ZillitEmptyState(
                        title = "Pick a week",
                        message = "Its days, pay and history show here.",
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .zillitVerticalScroll()
            .padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(text = card.crewName.ifBlank { card.userId }, style = ZillitTheme.typography.titleMedium)
                ZillitText(
                    text = "Week of ${EpochDate.date(card.weekStarting).ifEmpty { "—" }}" +
                        (card.designation?.let { " · $it" } ?: ""),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
            ZillitStatusPill(card.status.label, tone = card.status.tone, dot = true)
        }

        ZillitText(text = Money.format(card.net, card.currency), style = ZillitTheme.typography.displayLarge)
        ZillitText(
            text = "${card.workedHours} hours over ${card.days.count { it.dayType.isPaidWork }} paid day(s)",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )

        card.queryNote?.takeIf { it.isNotBlank() }?.let {
            ZillitNotice(text = "Queried: $it", tone = StatusTone.Pending, icon = ZillitIcons.Warning)
        }
        card.rejectionReason?.takeIf { it.isNotBlank() }?.let {
            ZillitNotice(text = "Rejected: $it", tone = StatusTone.Rejected, icon = ZillitIcons.Warning)
        }

        ZillitDivider()
        PayLine("Basic", Money.format(card.basicPay, card.currency))
        PayLine("Overtime", Money.format(card.overtimePay, card.currency))
        PayLine("Allowances", Money.format(card.totalAllowances, card.currency))
        PayLine("Additional fees", Money.format(card.additionalFees, card.currency))
        if (card.deductions.isNotEmpty()) {
            PayLine("Deductions", "-${Money.format(card.deductionTotal, card.currency)}")
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
            ZillitText(text = "Days", style = ZillitTheme.typography.titleSmall)
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
    val actions = buildList {
        if (card.userId == state.viewer.userId && card.isEditable) {
            add(
                Triple("Submit", ButtonVariant.Primary) {
                    TimecardPrompt.Confirm(
                        TimecardConfirmAction.Submit,
                        card.id,
                        "Submit this week",
                        "It goes to your head of department to approve.",
                    ) as TimecardPrompt
                },
            )
        }
        if (state.destination == TimecardDestination.ApprovalQueue && state.viewer.isApprover) {
            add(
                Triple("Approve", ButtonVariant.Primary) {
                    TimecardPrompt.Confirm(
                        TimecardConfirmAction.Approve,
                        card.id,
                        "Approve this week",
                        "${Money.format(card.net, card.currency)} for " +
                            "${card.crewName.ifBlank { "this crew member" }}.",
                    ) as TimecardPrompt
                },
            )
            add(
                Triple("Query", ButtonVariant.Secondary) {
                    TimecardPrompt.WithReason(
                        TimecardReasonAction.Query,
                        card.id,
                        "Query this week",
                        "What needs correcting",
                    ) as TimecardPrompt
                },
            )
            add(
                Triple("Reject", ButtonVariant.Danger) {
                    TimecardPrompt.WithReason(
                        TimecardReasonAction.Reject,
                        card.id,
                        "Reject this week",
                        "Why it is being refused",
                    ) as TimecardPrompt
                },
            )
        }
        if (state.viewer.isFinalApprover) {
            if (card.status == TimecardStatus.Approved) {
                add(
                    Triple("Final approve", ButtonVariant.Primary) {
                        TimecardPrompt.Confirm(
                            TimecardConfirmAction.FinalApprove,
                            card.id,
                            "Final approve this week",
                            "It becomes available to a payroll run.",
                        ) as TimecardPrompt
                    },
                )
            }
            if (card.status.isPayable && !card.locked) {
                add(
                    Triple("Lock", ButtonVariant.Secondary) {
                        TimecardPrompt.Confirm(
                            TimecardConfirmAction.Lock,
                            card.id,
                            "Lock this week",
                            "Nothing can change on it while a run is being prepared.",
                        ) as TimecardPrompt
                    },
                )
            }
            add(
                Triple("Add deduction", ButtonVariant.Tertiary) {
                    TimecardPrompt.Deduct(card.id) as TimecardPrompt
                },
            )
        }
    }

    if (actions.isEmpty()) {
        ZillitText(
            text = "Nothing to do on this week from here.",
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
                title = "No week open",
                message = "Pick a week from My Timecards, or start this one.",
                action = {
                    ZillitButton(
                        text = "Start this week",
                        onClick = { onEvent(TimecardEvent.LoadDraft(null)) },
                        size = ButtonSize.Small,
                    )
                },
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .zillitVerticalScroll()
            .padding(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        ZillitSectionCard(
            title = "Week of ${EpochDate.date(draft.weekStarting).ifEmpty { "—" }}",
            icon = ZillitIcons.Clock,
            meta = "${draft.workedHours} hours" +
                if (draft.allowanceTotal > 0) {
                    " · ${Money.format(draft.allowanceTotal, state.selected?.currency)} allowances"
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

        ZillitSectionCard(title = "Anything else", icon = ZillitIcons.Info) {
            ZillitTextField(
                value = draft.notes,
                onValueChange = { onEvent(TimecardEvent.EditNotes(it)) },
                label = "Note for your approver (optional)",
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.padding(ZillitTheme.spacing.xs))
            ZillitButton(
                text = "Save this week",
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
            text = EpochDate.date(day.date).ifEmpty { "Day ${index + 1}" },
            style = ZillitTheme.typography.bodyMedium,
            modifier = Modifier.width(DATE_WIDTH),
            maxLines = 1,
        )
        Column(modifier = Modifier.weight(1f)) {
            if (index == 0) {
                ZillitText(
                    text = "Day type",
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
            label = if (index == 0) "Call" else null,
            placeholder = "07:00",
            // Times are only meaningful on a day that was worked; leaving them
            // enabled on a rest day invites entries nobody will pay.
            enabled = day.dayType.isPaidWork,
            modifier = Modifier.weight(SMALL_FIELD),
        )
        ZillitTextField(
            value = day.wrapTime.orEmpty(),
            onValueChange = { onChange(day.copy(wrapTime = it.takeIf(String::isNotBlank))) },
            label = if (index == 0) "Wrap" else null,
            placeholder = "19:00",
            enabled = day.dayType.isPaidWork,
            modifier = Modifier.weight(SMALL_FIELD),
        )
        ZillitTextField(
            value = if (day.workedHours == 0.0) "" else day.workedHours.toString(),
            onValueChange = { onChange(day.copy(workedHours = it.trim().toDoubleOrNull() ?: 0.0)) },
            label = if (index == 0) "Hours" else null,
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
            is TimecardPrompt.Deduct -> "Add a deduction"
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
                    label = "What is being deducted",
                    modifier = Modifier.fillMaxWidth(),
                )
                ZillitTextField(
                    value = shown.amount,
                    onValueChange = { onEvent(TimecardEvent.UpdatePrompt(shown.copy(amount = it))) },
                    label = "Amount",
                    placeholder = "0.00",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.fillMaxWidth(),
                )
                ZillitTextField(
                    value = shown.reason,
                    onValueChange = { onEvent(TimecardEvent.UpdatePrompt(shown.copy(reason = it))) },
                    label = "Reason (optional)",
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
                text = "Cancel",
                onClick = { onEvent(TimecardEvent.DismissPrompt) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Confirm",
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
        TimecardStatus.Submitted, TimecardStatus.AwaitingApproval -> StatusTone.Pending
        TimecardStatus.Approved -> StatusTone.Progress
        TimecardStatus.FinalApproved -> StatusTone.Ready
        TimecardStatus.Locked, TimecardStatus.SentToPayroll -> StatusTone.InTransit
        TimecardStatus.Paid -> StatusTone.Done
        TimecardStatus.Queried -> StatusTone.Pending
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
    add(textColumn("Crew", ColumnWidth.Weight(1.4f)) { it.crewName.ifBlank { it.userId } })
    add(textColumn("Week", ColumnWidth.Weight(1.1f), muted = true) { EpochDate.date(it.weekStarting).ifEmpty { "—" } })
    add(textColumn("Days", ColumnWidth.Fixed(NUMBER_COLUMN), numeric = true) { it.totalDays.toString() })
    add(textColumn("Hours", ColumnWidth.Fixed(NUMBER_COLUMN), numeric = true) { it.workedHours.toString() })
    add(textColumn("Net", ColumnWidth.Weight(1f), numeric = true) { Money.format(it.net, it.currency) })
    add(
        TableColumn(
            header = "Status",
            width = ColumnWidth.Fixed(STATUS_COLUMN),
            cell = { ZillitStatusPill(it.status.label, tone = it.status.tone, dot = true) },
        ),
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
