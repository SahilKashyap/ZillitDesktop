package com.zillit.desktop.feature.addashboard.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
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
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
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

        // Today's run is gated step by step; a day opened from the schedule
        // has every section open, so it gets directed cards instead of a
        // progress board that would read as complete on a day long finished.
        if (state.runner.openedDate == null) {
            DayProgress(state, onEvent)
        } else {
            SectionCards(state, onEvent)
        }

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
    val pending = state.dayList.count { !it.isHandled }
    val marked = AdPhases.attendanceComplete(state.dayList)
    ZillitSectionCard(
        title = str(S.desktop_ad_mark_arrivals),
        meta = str(S.desktop_ad_n_of_m_marked, state.dayList.count { it.isHandled }, state.onToday),
        padded = false,
        action = {
            ZillitSearchField(
                value = state.attendance.search,
                onValueChange = { onEvent(DayEvent.Search(it)) },
                modifier = Modifier.width(SEARCH_WIDTH),
            )
            if (state.canEditDay) {
                // Nothing left to mark in bulk means no bar, as the web drops
                // it rather than offering a button that would do nothing.
                if (pending > 0) {
                    ZillitButton(
                        text = str(S.desktop_ad_mark_all_present),
                        onClick = { onEvent(DayEvent.MarkAllPresent) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        loading = state.runner.bulkBusy,
                    )
                }
                // The step button says what is in the way while it is barred,
                // and where it goes once it is not.
                ZillitButton(
                    text = str(
                        if (marked) S.desktop_ad_go_to_mid_day else S.desktop_ad_mark_everyone_first_cta,
                    ),
                    onClick = { onEvent(RunnerEvent.Step(RunnerStep.MidDay)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = marked,
                )
            }
        },
    ) {
        ZillitDataTable(
            rows = state.attendanceRows,
            columns = runnerAttendanceColumns(state, onEvent),
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
            ZillitSearchField(
                value = state.attendance.search,
                onValueChange = { onEvent(DayEvent.Search(it)) },
                modifier = Modifier.width(SEARCH_WIDTH),
            )
            if (state.canEditDay) {
                val wrapped = AdPhases.everyoneWrapped(state.dayList)
                if (state.dayList.any { it.isPresent && !it.isWrapped }) {
                    ZillitButton(
                        text = str(S.desktop_ad_mark_all_out),
                        onClick = { onEvent(DayEvent.MarkAllOut) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        loading = state.runner.bulkBusy,
                    )
                }
                ZillitButton(
                    text = str(
                        if (wrapped) S.desktop_ad_go_to_submit else S.desktop_ad_wrap_everyone_first_cta,
                    ),
                    onClick = { onEvent(RunnerEvent.Step(RunnerStep.Submit)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    // Writes nothing: it only moves to the Submit step, which
                    // is the one place the day is actually sent.
                    enabled = wrapped,
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


/**
 * Where the day has got to, and the only way through it.
 *
 * Four cells in a row: a numbered disc, the step's name, and a word for where
 * it stands. A step is locked until the one before it is finished — mid-day
 * and wrap until everybody is marked, submit until everybody is wrapped — so
 * the board is the gate as well as the picture of it.
 *
 * Wrap reads done on a day with nobody present, because there is nothing left
 * to wrap; that is the web's rule, not an oversight.
 */
@Composable
private fun DayProgress(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val rows = state.dayList
    val marked = AdPhases.attendanceComplete(rows)
    val wrapped = AdPhases.everyoneWrapped(rows)
    val locked = mapOf(
        RunnerStep.Attendance to false,
        RunnerStep.MidDay to !marked,
        RunnerStep.Wrap to !marked,
        RunnerStep.Submit to !wrapped,
    )
    val complete = mapOf(
        RunnerStep.Attendance to marked,
        RunnerStep.MidDay to (state.day?.meals?.isNotEmpty() == true),
        RunnerStep.Wrap to wrapped,
        RunnerStep.Submit to false,
    )
    ZillitSectionCard(
        title = str(S.desktop_ad_day_progress),
        icon = ZillitIcons.Clock,
        meta = str(S.desktop_ad_phases_complete, complete.count { it.value }, RunnerStep.entries.size),
        padded = false,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            RunnerStep.entries.forEachIndexed { index, step ->
                ProgressCell(
                    step = step,
                    number = index + 1,
                    active = state.runner.step == step,
                    done = complete[step] == true && state.runner.step != step,
                    locked = locked[step] == true,
                    modifier = Modifier.weight(1f),
                    onClick = { onEvent(RunnerEvent.Step(step)) },
                )
            }
        }
    }
}

/** One cell of the progress board. */
@Composable
private fun ProgressCell(
    step: RunnerStep,
    number: Int,
    active: Boolean,
    done: Boolean,
    locked: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = modifier
            .background(if (active) colors.accentSoft else Color.Transparent)
            .alpha(if (locked) LOCKED_ALPHA else 1f)
            .clickable(enabled = !locked, onClick = onClick)
            .padding(ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProgressDisc(number = number, active = active, done = done)
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(
                text = step.label,
                style = ZillitTheme.typography.titleSmall,
                color = when {
                    done -> colors.success
                    active -> colors.accentText
                    else -> colors.textPrimary
                },
                maxLines = 1,
            )
            ZillitText(
                text = str(progressWord(locked = locked, done = done, active = active)),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

/**
 * The same four steps on a day opened from the schedule.
 *
 * No gating and no completion marks: a past day is being corrected rather than
 * run, so every section is open and the cards only say where you are.
 */
@Composable
private fun SectionCards(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        RunnerStep.entries.forEachIndexed { index, step ->
            val active = state.runner.step == step
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(ZillitTheme.shapes.medium)
                    .background(if (active) ZillitTheme.colors.accent else ZillitTheme.colors.surface)
                    .clickable { onEvent(RunnerEvent.Step(step)) }
                    .padding(ZillitTheme.spacing.md),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
                ) {
                    ZillitText(
                        text = str(S.desktop_ad_section_number, index + 1),
                        style = ZillitTheme.typography.labelSmall,
                        color = if (active) {
                            ZillitTheme.colors.textOnAccent
                        } else {
                            ZillitTheme.colors.textMuted
                        },
                        maxLines = 1,
                    )
                    ZillitText(
                        text = step.label,
                        style = ZillitTheme.typography.titleSmall,
                        color = if (active) {
                            ZillitTheme.colors.textOnAccent
                        } else {
                            ZillitTheme.colors.textPrimary
                        },
                        maxLines = 1,
                    )
                }
                ZillitIcon(
                    icon = ZillitIcons.ArrowRight,
                    tint = if (active) ZillitTheme.colors.textOnAccent else ZillitTheme.colors.textMuted,
                    size = SECTION_ARROW,
                )
            }
        }
    }
}

/** A locked cell is dimmed rather than hidden — the step is still coming. */
private const val LOCKED_ALPHA = 0.5f

private val PROGRESS_DISC: Dp = 30.dp
private val PROGRESS_TICK: Dp = 15.dp
private val SECTION_ARROW: Dp = 16.dp
private val SEARCH_WIDTH: Dp = 240.dp


/** The runner's own attendance columns — present or absent, and nothing typed. */
private fun runnerAttendanceColumns(
    state: AdUiState,
    onEvent: (AdEvent) -> Unit,
): List<TableColumn<SupportingArtistDay>> = listOf(
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
)

/** The numbered disc on a progress cell — a tick once the step is behind you. */
@Composable
private fun ProgressDisc(number: Int, active: Boolean, done: Boolean) {
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier
            .size(PROGRESS_DISC)
            .clip(CircleShape)
            .background(
                when {
                    done -> colors.success
                    active -> colors.accent
                    else -> colors.surfaceSunken
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            ZillitIcon(icon = ZillitIcons.Tick, tint = colors.textOnAccent, size = PROGRESS_TICK)
        } else {
            ZillitText(
                text = number.toString(),
                style = ZillitTheme.typography.labelSmall,
                color = if (active) colors.textOnAccent else colors.textMuted,
            )
        }
    }
}

/** The one word under a step's name. Locked wins: it says why, not just where. */
private fun progressWord(locked: Boolean, done: Boolean, active: Boolean): String = when {
    locked -> S.docusign_prop_locked
    done -> S.done_text
    active -> S.in_progress
    else -> S.dd_csv_status_ready
}
