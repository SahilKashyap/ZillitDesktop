package com.zillit.desktop.feature.addashboard.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSegmented
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.addashboard.domain.AdDates
import com.zillit.desktop.feature.addashboard.domain.AdPhases
import com.zillit.desktop.feature.addashboard.domain.AdShootDay
import com.zillit.desktop.feature.addashboard.domain.AgencyPurchaseOrder
import com.zillit.desktop.feature.addashboard.domain.DayReportAction
import com.zillit.desktop.feature.addashboard.domain.MealBreak
import com.zillit.desktop.feature.addashboard.domain.PendingPayrollItem
import com.zillit.desktop.feature.addashboard.domain.SupportingArtistDay
import com.zillit.desktop.feature.addashboard.ui.AdEvent
import com.zillit.desktop.feature.addashboard.ui.AdUiState
import com.zillit.desktop.feature.addashboard.ui.DayEvent
import com.zillit.desktop.feature.addashboard.ui.ReportEvent
import com.zillit.desktop.feature.addashboard.ui.RunnerEvent
import com.zillit.desktop.feature.addashboard.ui.RunnerStep

/**
 * Run Current Day, and a day opened from the schedule.
 *
 * Four steps in order — Attendance, Mid-Day, Wrap, Submit — and the gating is
 * the only difference between the two: the tab unlocks each step as the one
 * before it completes, while a day opened from the schedule has them all open
 * and an Additionals section of its own.
 */
@Composable
internal fun RunnerPage(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    AdPage {
        if (state.runner.openedDate != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitButton(
                    text = str(S.desktop_ad_back_to_schedule),
                    onClick = { onEvent(RunnerEvent.CloseDay) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.ArrowLeft,
                )
                ZillitText(
                    text = AdDates.formatDayFull(state.shootDate),
                    style = ZillitTheme.typography.titleSmall,
                )
            }
        }

        if (!state.dateEditable) {
            // A future day opens read-only: it may be looked at but not run.
            ZillitNotice(
                text = str(S.desktop_ad_future_day_read_only),
                tone = StatusTone.Neutral,
                icon = ZillitIcons.Calendar,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ZillitSegmented(
            options = RunnerStep.entries.map { step ->
                ZillitTab(id = step.name, label = step.label)
            },
            activeId = state.runner.step.name,
            onSelect = { name ->
                RunnerStep.entries.firstOrNull { it.name == name }?.let { onEvent(RunnerEvent.Step(it)) }
            },
        )

        when (state.runner.step) {
            RunnerStep.Attendance -> AttendanceStep(state, onEvent)
            RunnerStep.MidDay -> MidDayStep(state, onEvent)
            RunnerStep.Wrap -> WrapStep(state, onEvent)
            RunnerStep.Submit -> SubmitDayPage(state, onEvent)
        }

        // Only a day opened from the schedule carries Additionals inline; for
        // today the AD uses the Additionals tab.
        if (state.runner.openedDate != null && state.runner.step != RunnerStep.Submit) {
            ZillitSectionLabel(text = str(S.desktop_ad_additionals))
            state.dayList.forEach { row -> AdditionalsInline(state, row, onEvent) }
        }
    }

    ArtisteMealsDialog(state, onEvent)
}

@Composable
private fun AttendanceStep(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    ZillitSectionCard(
        title = str(S.desktop_ad_attendance),
        meta = str(S.desktop_ad_n_of_m_marked, state.dayList.count { it.isHandled }, state.onToday),
        padded = false,
        action = {
            if (state.canEditDay) {
                ZillitButton(
                    text = str(S.desktop_ad_mark_all_present),
                    onClick = { onEvent(DayEvent.MarkAllPresent) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    loading = state.runner.bulkBusy,
                )
            }
        },
    ) {
        ZillitDataTable(
            rows = state.dayList,
            columns = listOf(
                textColumn(str(S.name), width = ColumnWidth.Weight(2f)) { it.artisteName },
                textColumn(str(S.av_category)) { it.category.label },
                textColumn(str(S.desktop_ad_time_in), width = ColumnWidth.Fixed(TIME_WIDTH)) {
                    it.callTime.ifBlank { EM_DASH }
                },
                TableColumn(str(S.status), width = ColumnWidth.Fixed(STATUS_WIDTH)) { row ->
                    ZillitStatusPill(label = row.statusLabel(), tone = row.statusTone())
                },
                TableColumn(str(S.dd_actions), width = ColumnWidth.Fixed(ACTION_WIDTH)) { row ->
                    if (!state.canEditDay) return@TableColumn
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                        ZillitButton(
                            text = str(S.desktop_call_present),
                            onClick = { onEvent(DayEvent.MarkPresent(row.id)) },
                            variant = ButtonVariant.Tertiary,
                            size = ButtonSize.Small,
                        )
                        ZillitButton(
                            text = str(S.desktop_ad_absent),
                            onClick = { onEvent(DayEvent.MarkAbsent(row.id)) },
                            variant = ButtonVariant.Tertiary,
                            size = ButtonSize.Small,
                        )
                    }
                },
            ),
            key = { it.id },
            emptyTitle = str(S.desktop_ad_nobody_on_day),
            virtualised = false,
        )
    }
}

/**
 * Mid-Day: the unit's meal breaks, and each artiste's own.
 *
 * The day's meals are a **draft** until Save meals, and saving them is silent —
 * the server copies them onto every artiste-day that is not already submitted,
 * and the header is not re-read, so the draft is also what keeps them on screen
 * across a step change.
 */
@Suppress("LongMethod") // One screen, laid out once; splitting it hides the page's shape.
@Composable
private fun MidDayStep(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val meals = state.runner.mealDraft ?: state.day?.meals.orEmpty()
    ZillitSectionCard(
        title = str(S.desktop_ad_day_meals),
        meta = str(S.desktop_ad_copied_to_everyone),
        action = {
            if (state.canEditDay) {
                ZillitButton(
                    text = str(S.desktop_ad_add_meal),
                    onClick = { onEvent(RunnerEvent.AddMeal("")) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                )
                ZillitButton(
                    text = str(S.desktop_ad_save_meals),
                    onClick = { onEvent(RunnerEvent.SaveMeals) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = state.runner.mealDraft != null,
                    loading = state.runner.mealsSaving,
                )
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            if (meals.isEmpty()) {
                ZillitText(
                    text = str(S.desktop_ad_no_meals_yet),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
            meals.forEachIndexed { index, meal ->
                MealRow(
                    meal = meal,
                    enabled = state.canEditDay,
                    onType = { onEvent(RunnerEvent.MealType(index, it)) },
                    onStart = { onEvent(RunnerEvent.MealStart(index, it)) },
                    onEnd = { onEvent(RunnerEvent.MealEnd(index, it)) },
                    onRemove = { onEvent(RunnerEvent.RemoveMeal(index)) },
                )
            }
        }
    }

    ZillitSectionCard(title = str(S.desktop_ad_per_artiste_meals), padded = false) {
        ZillitDataTable(
            rows = state.dayList.filter { it.isPresent },
            columns = listOf(
                textColumn(str(S.name), width = ColumnWidth.Weight(2f)) { it.artisteName },
                textColumn(str(S.desktop_ad_meals)) { row ->
                    row.meals.joinToString(", ") { "${it.startTime}–${it.endTime}" }.ifBlank { EM_DASH }
                },
                TableColumn(str(S.dd_actions), width = ColumnWidth.Fixed(ACTION_WIDTH)) { row ->
                    ZillitButton(
                        text = str(S.edit),
                        onClick = { onEvent(RunnerEvent.OpenArtisteMeals(row.id)) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                        enabled = state.canEditDay,
                    )
                },
            ),
            key = { it.id },
            emptyTitle = str(S.desktop_ad_nobody_present),
            virtualised = false,
        )
    }
}

@Composable
private fun MealRow(
    meal: MealBreak,
    enabled: Boolean,
    onType: (String) -> Unit,
    onStart: (String) -> Unit,
    onEnd: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(text = meal.order.toString(), style = ZillitTheme.typography.numeric)
        ZillitTextField(
            value = meal.type,
            onValueChange = onType,
            modifier = Modifier.fillMaxWidth().weight(1f),
            placeholder = str(S.desktop_ad_meal_type),
            enabled = enabled,
        )
        ZillitTextField(
            value = meal.startTime,
            onValueChange = onStart,
            modifier = Modifier.width(TIME_WIDTH),
            placeholder = "13:00",
            enabled = enabled,
        )
        ZillitTextField(
            value = meal.endTime,
            onValueChange = onEnd,
            modifier = Modifier.width(TIME_WIDTH),
            placeholder = "13:30",
            enabled = enabled,
        )
        if (enabled) {
            ZillitIconButton(
                icon = ZillitIcons.Trash,
                contentDescription = str(S.remove),
                onClick = onRemove,
            )
        }
    }
}

/** Wrap: a time out per artiste, by hand or stamped, and the way to clear one. */
@Suppress("LongMethod") // One screen, laid out once; splitting it hides the page's shape.
@Composable
private fun WrapStep(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    ZillitSectionCard(
        title = str(S.dm_ds_phase_wrap),
        meta = str(S.desktop_ad_n_of_m_wrapped, state.wrappedToday, state.presentToday),
        padded = false,
        action = {
            if (state.canEditDay) {
                ZillitButton(
                    text = str(S.desktop_ad_mark_all_out),
                    onClick = { onEvent(DayEvent.MarkAllOut) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    loading = state.runner.bulkBusy,
                )
                ZillitButton(
                    text = str(S.desktop_ad_go_to_submit),
                    onClick = { onEvent(RunnerEvent.Step(RunnerStep.Submit)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    // Writes nothing: it only moves to the Submit step, which
                    // is the one place the day is actually sent.
                    enabled = AdPhases.everyoneWrapped(state.dayList),
                )
            }
        },
    ) {
        ZillitDataTable(
            rows = state.dayList.filter { it.isPresent },
            columns = listOf(
                textColumn(str(S.name), width = ColumnWidth.Weight(2f)) { it.artisteName },
                textColumn(str(S.desktop_ad_time_in), width = ColumnWidth.Fixed(TIME_WIDTH)) { it.callTime },
                TableColumn(str(S.desktop_ad_time_out), width = ColumnWidth.Fixed(TIME_ENTRY_WIDTH)) { row ->
                    ZillitTextField(
                        value = state.runner.timeDraft[row.id] ?: row.wrapTime,
                        onValueChange = { onEvent(DayEvent.TimeDraft(row.id, it)) },
                        // Committed on leaving the field as well as on Enter.
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focus ->
                                if (!focus.isFocused) {
                                    state.runner.timeDraft[row.id]
                                        ?.takeIf { it != row.wrapTime }
                                        ?.let { onEvent(DayEvent.MarkOutAt(row.id, it)) }
                                }
                            },
                        placeholder = "19:30",
                        enabled = state.canEditDay,
                        onImeAction = {
                            state.runner.timeDraft[row.id]?.let { onEvent(DayEvent.MarkOutAt(row.id, it)) }
                        },
                    )
                },
                TableColumn(str(S.dd_actions), width = ColumnWidth.Fixed(ACTION_WIDTH)) { row ->
                    if (!state.canEditDay) return@TableColumn
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                        ZillitButton(
                            text = str(S.desktop_ad_mark_out),
                            onClick = { onEvent(DayEvent.MarkOut(row.id)) },
                            variant = ButtonVariant.Tertiary,
                            size = ButtonSize.Small,
                        )
                        if (row.isWrapped) {
                            ZillitIconButton(
                                icon = ZillitIcons.Close,
                                contentDescription = str(S.desktop_ad_clear_out_time),
                                onClick = { onEvent(DayEvent.ClearOut(row.id)) },
                            )
                        }
                    }
                },
            ),
            key = { it.id },
            emptyTitle = str(S.desktop_ad_nobody_present),
            virtualised = false,
        )
    }
}

@Composable
private fun AdditionalsInline(state: AdUiState, row: SupportingArtistDay, onEvent: (AdEvent) -> Unit) {
    ZillitSectionCard(title = row.artisteName, meta = state.money(row.payTotal, row.currency)) {
        ZillitText(
            text = str(S.desktop_ad_n_allowances_n_fees, row.allowances.size, row.additionalFees.size),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        ZillitButton(
            text = str(S.desktop_ad_open_additionals),
            onClick = { onEvent(AdEvent.Open(com.zillit.desktop.feature.addashboard.ui.AdDestination.Additionals)) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
        )
    }
}

/**
 * Shoot Schedules: every day header, split into today, upcoming and past.
 *
 * "SD n" is ranked here by date rather than read off the day, which is why a
 * manual day number a report wrote does not change it. Upcoming days are listed
 * too, which the product spec does not ask for but the web does.
 */
@Composable
internal fun SchedulePage(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    AdPage {
        state.todayRow?.let { today ->
            ZillitSectionLabel(text = str(S.today))
            ScheduleRow(state, today, onEvent)
        }
        if (state.upcomingDays.isNotEmpty()) {
            ZillitSectionLabel(text = str(S.desktop_csync_when_upcoming))
            state.upcomingDays.forEach { day -> ScheduleRow(state, day, onEvent) }
        }
        if (state.pastDays.isNotEmpty()) {
            ZillitSectionLabel(text = str(S.desktop_bs_past))
            state.pastDays.forEach { day -> ScheduleRow(state, day, onEvent) }
        }
        if (state.shootDays.isEmpty() && !state.loading) {
            ZillitEmptyState(
                title = str(S.desktop_ad_no_shoot_days),
                message = str(S.desktop_ad_no_shoot_days_message),
                icon = ZillitIcons.Calendar,
            )
        }
    }
}

@Composable
private fun ScheduleRow(state: AdUiState, day: AdShootDay, onEvent: (AdEvent) -> Unit) {
    ZillitSectionCard(
        title = "${str(S.desktop_ad_sd_n, state.scheduleRank(day))} · ${AdDates.formatDayShort(day.shootDate)}",
        meta = day.unitName.ifBlank { day.location },
        action = {
            ZillitStatusPill(label = day.status.label, tone = day.status.tone())
            when (day.reportAction) {
                // Only a submitted day may generate; only a published one has
                // a report to view. `approved` offers neither, because nothing
                // writes it.
                DayReportAction.Generate -> if (state.viewer.canPost) {
                    ZillitButton(
                        text = str(S.desktop_ad_generate_report),
                        onClick = {
                            val template = state.reports.templates.firstOrNull { it.schedulePickable }
                            if (template == null) {
                                onEvent(ReportEvent.OpenTemplatePicker)
                            } else {
                                onEvent(ReportEvent.GenerateForDay(day.shootDate ?: 0L, template))
                            }
                        },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.File,
                    )
                }

                DayReportAction.View -> ZillitButton(
                    text = str(S.desktop_ad_view_report),
                    onClick = { onEvent(ReportEvent.OpenDayLink(day.shootDate ?: 0L)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Eye,
                )

                DayReportAction.None -> Unit
            }
            // Rows are openable only for a posting viewer; the web gives a
            // read-only one no chevron at all.
            if (state.viewer.canPost) {
                ZillitIconButton(
                    icon = ZillitIcons.ChevronRight,
                    contentDescription = str(S.desktop_ad_open_day),
                    onClick = { onEvent(RunnerEvent.OpenDay(day)) },
                )
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
            AdDetailRow(label = str(S.desktop_ad_crew_call), value = day.callTime)
            AdDetailRow(label = str(S.desktop_ad_unit_wrap), value = day.wrapTime)
        }
    }
}

/**
 * The PO / Payroll Queue: read-only, and built by the server from the submitted
 * day headers.
 *
 * Three lists: the agency orders each day raised, the direct-PAYE runs grouped
 * from the days' non-agency artistes, and the artistes held back from payroll.
 */
@Suppress("LongMethod") // One screen, laid out once; splitting it hides the page's shape.
@Composable
internal fun PoQueuePage(state: AdUiState) {
    AdPage {
        ZillitSectionCard(title = str(S.desktop_ad_agency_pos), padded = false) {
            ZillitDataTable(
                rows = state.poQueue.agencyPos,
                columns = listOf(
                    textColumn(str(S.desktop_reference), width = ColumnWidth.Weight(1.4f)) {
                        it.poReference.ifBlank { it.poId }
                    },
                    textColumn(str(S.desktop_dm_agency)) { it.agency },
                    textColumn(str(S.bs_day)) { row ->
                        row.dayNumber?.let { str(S.desktop_ad_sd_n, it) }
                            ?: AdDates.formatDayShort(row.shootDate)
                    },
                    TableColumn(str(S.amount), width = ColumnWidth.Fixed(AMOUNT_WIDTH), numeric = true) { row ->
                        ZillitText(
                            text = state.money(row.amount, row.currency),
                            style = ZillitTheme.typography.numeric,
                        )
                    },
                    TableColumn(str(S.status), width = ColumnWidth.Fixed(STATUS_WIDTH)) { row ->
                        ZillitStatusPill(label = row.statusLabel(), tone = row.tone())
                    },
                    textColumn(str(S.desktop_ad_approved_by)) { it.approvedBy },
                ),
                key = { it.poId + it.poReference },
                emptyTitle = str(S.desktop_ad_no_agency_pos),
                emptyMessage = str(S.desktop_ad_no_agency_pos_message),
                loading = state.poQueueLoading,
                virtualised = false,
            )
        }

        ZillitSectionCard(title = str(S.desktop_ad_direct_paye_payroll), padded = false) {
            ZillitDataTable(
                rows = state.poQueue.payroll,
                columns = listOf(
                    textColumn(str(S.name), width = ColumnWidth.Weight(1.4f)) { it.label },
                    textColumn(str(S.cr_meta_period)) { it.period },
                    TableColumn(str(S.amount), width = ColumnWidth.Fixed(AMOUNT_WIDTH), numeric = true) { row ->
                        ZillitText(
                            text = state.money(row.amount, row.currency),
                            style = ZillitTheme.typography.numeric,
                        )
                    },
                    textColumn(str(S.desktop_ad_artistes)) { row ->
                        row.artisteCount?.toString().orEmpty()
                    },
                    textColumn(str(S.status)) { it.status },
                ),
                key = { it.label + it.period },
                emptyTitle = str(S.desktop_ad_no_payroll_runs),
                loading = state.poQueueLoading,
                virtualised = false,
            )
        }

        ZillitSectionCard(title = str(S.desktop_ad_pending_payroll_items), padded = false) {
            ZillitDataTable(
                rows = state.poQueue.pending,
                columns = listOf(
                    textColumn(str(S.name), width = ColumnWidth.Weight(1.4f)) { it.name },
                    textColumn(str(S.reason), width = ColumnWidth.Weight(2f)) { it.reason },
                    TableColumn(str(S.status), width = ColumnWidth.Fixed(STATUS_WIDTH)) { row ->
                        ZillitStatusPill(
                            label = row.label(),
                            tone = if (row.blocked) StatusTone.Rejected else StatusTone.Pending,
                        )
                    },
                ),
                key = { it.artisteId + it.name },
                emptyTitle = str(S.desktop_ad_nothing_on_hold),
                loading = state.poQueueLoading,
                virtualised = false,
            )
        }
    }
}

/**
 * Budget.
 *
 * Not built on any client: the web's tab shows "Coming Soon" over a commented
 * out mock, and there is no endpoint behind it. Ported as the same placeholder
 * rather than invented here, so nobody reads a number that means nothing.
 */
@Composable
internal fun BudgetPage() {
    ZillitEmptyState(
        title = str(S.desktop_ad_budget_coming_soon),
        message = str(S.desktop_ad_budget_coming_soon_message),
        icon = ZillitIcons.Calculator,
    )
}

/** How a queue order's status reads, tolerant of whatever wording arrives. */
private fun AgencyPurchaseOrder.statusLabel(): String =
    status.replace('_', ' ').ifBlank { EM_DASH }

private fun AgencyPurchaseOrder.tone(): StatusTone = when {
    listOf("approv", "paid", "post", "complet").any { status.contains(it, ignoreCase = true) } ->
        StatusTone.Done

    listOf("block", "reject", "fail").any { status.contains(it, ignoreCase = true) } -> StatusTone.Rejected
    awaiting -> StatusTone.Pending
    else -> StatusTone.Neutral
}

private fun PendingPayrollItem.label(): String =
    if (blocked) str(S.desktop_ad_status_blocked) else str(S.desktop_call_on_hold)

private val TIME_WIDTH = 110.dp
private val TIME_ENTRY_WIDTH = 120.dp
private val AMOUNT_WIDTH = 120.dp
private val STATUS_WIDTH = 130.dp
private val ACTION_WIDTH = 200.dp
