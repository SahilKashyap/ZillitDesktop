package com.zillit.desktop.feature.boxschedule.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.plural
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.boxschedule.domain.ConflictAction
import com.zillit.desktop.feature.boxschedule.domain.DateConflict
import com.zillit.desktop.feature.boxschedule.domain.DiaryEvent
import com.zillit.desktop.feature.boxschedule.domain.DiaryFormat
import com.zillit.desktop.feature.boxschedule.domain.DiaryKind
import com.zillit.desktop.feature.boxschedule.domain.RecurrenceScope
import com.zillit.desktop.feature.boxschedule.domain.ScheduleBlock
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleEvent
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleUiState
import com.zillit.desktop.feature.boxschedule.ui.CalendarInfo
import com.zillit.desktop.feature.boxschedule.ui.ConflictPrompt
import com.zillit.desktop.feature.boxschedule.ui.DeleteDayPrompt
import com.zillit.desktop.feature.boxschedule.ui.EntryEvent
import com.zillit.desktop.feature.boxschedule.ui.EntryScopePrompt
import com.zillit.desktop.feature.boxschedule.ui.ScheduleEvent
import com.zillit.desktop.feature.boxschedule.ui.ScheduleScope
import com.zillit.desktop.feature.boxschedule.ui.ScheduleScopePrompt
import com.zillit.desktop.feature.boxschedule.ui.ScopeMode

/** "Schedule Conflict" — which dates collide, and Replace, Extend or Overlap before Done. */
@Composable
internal fun ConflictDialog(state: BoxScheduleUiState, prompt: ConflictPrompt, onEvent: (BoxScheduleEvent) -> Unit) {
    ZillitDialogShell(
        title = str(S.conflict_title),
        icon = ZillitIcons.Warning,
        onDismiss = { onEvent(ScheduleEvent.CancelConflict) },
        visible = true,
        width = 520.dp,
        actions = {
            ZillitButton(
                "← ${str(S.conflict_back)}",
                onClick = { onEvent(ScheduleEvent.ConflictBack) },
                variant = ButtonVariant.Tertiary,
                enabled = !prompt.saving,
            )
            Box(Modifier.weight(1f))
            ZillitButton(
                str(S.cancel),
                onClick = { onEvent(ScheduleEvent.CancelConflict) },
                variant = ButtonVariant.Secondary,
                enabled = !prompt.saving,
            )
            ZillitButton(
                str(S.done_text),
                onClick = { onEvent(ScheduleEvent.ResolveConflict) },
                enabled = prompt.choice != null,
                loading = prompt.saving,
            )
        },
    ) {
        ZillitText(
            str(S.desktop_bs_conflict_dates_overlap, prompt.conflicts.size),
            style = ZillitTheme.typography.bodyMedium,
        )
        ConflictTable(state, prompt.conflicts)
        ZillitText(str(S.desktop_bs_what_would_you_like_to_do), style = ZillitTheme.typography.titleSmall)
        CONFLICT_OPTIONS.forEach { (action, description) ->
            OptionCard(
                selected = prompt.choice == action,
                title = action.label,
                description = str(description),
                badge = str(S.desktop_recommended).takeIf { action == ConflictAction.Replace },
                enabled = !prompt.saving,
                onClick = { onEvent(ScheduleEvent.PickConflict(action)) },
            )
        }
    }
}

/** Date, the type already there, and that schedule's title — one row per colliding date. */
@Composable
private fun ConflictTable(state: BoxScheduleUiState, conflicts: List<DateConflict>) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, colors.border, shape)) {
        Row(Modifier.fillMaxWidth().background(colors.surfaceSunken).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Caption(str(S.date), Modifier.weight(1f))
            Caption(str(S.desktop_bs_current_type), Modifier.weight(1.2f))
            Caption(str(S.schedule), Modifier.weight(1f))
        }
        conflicts.forEach { conflict ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .gridLines(colors.border, right = false)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    DiaryFormat.shortDay(conflict.date, state.zone),
                    style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Medium),
                    modifier = Modifier.weight(1f),
                )
                Row(
                    Modifier.weight(1.2f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Dot(swatchColor(conflict.existingColor), size = 8.dp, square = true)
                    ZillitText(conflict.existingType, style = ZillitTheme.typography.label)
                }
                ZillitText(
                    conflict.existingTitle.ifBlank { "—" },
                    style = ZillitTheme.typography.label,
                    color = colors.textMuted,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Each resolution with the catalogue key of its description. */
private val CONFLICT_OPTIONS = listOf(
    ConflictAction.Replace to S.conflict_replace_desc,
    ConflictAction.Extend to S.desktop_bs_conflict_extend_desc,
    ConflictAction.Overlap to S.conflict_overlap_desc,
)

/** Cancel, and the one confirm whose wording turns on delete-vs-edit and scope. */
@Composable
private fun ScopeDialogActions(
    delete: Boolean,
    single: Boolean,
    prompt: ScheduleScopePrompt,
    onEvent: (BoxScheduleEvent) -> Unit,
) {
    ZillitButton(
        str(S.cancel),
        onClick = { onEvent(ScheduleEvent.CancelScope) },
        variant = ButtonVariant.Secondary,
    )
    ZillitButton(
        text = str(
            when {
                delete && single -> S.bs_sched_scope_btn_delete_this
                delete -> S.bs_sched_scope_btn_delete_all
                single -> S.bs_sched_scope_btn_edit_this
                else -> S.bs_sched_scope_btn_edit_all
            },
        ),
        onClick = { onEvent(ScheduleEvent.ConfirmScope) },
        variant = if (delete) ButtonVariant.Danger else ButtonVariant.Primary,
        loading = prompt.working,
    )
}

/** "This schedule covers more than one day." — this date only, or the complete schedule. */
@Composable
internal fun ScheduleScopeDialog(
    state: BoxScheduleUiState,
    prompt: ScheduleScopePrompt,
    block: ScheduleBlock,
    onEvent: (BoxScheduleEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val delete = prompt.mode == ScopeMode.Delete
    val accent = if (delete) colors.danger else colors.accent
    val date = DiaryFormat.shortWeekdayDate(prompt.date, state.zone)
    val count = block.calendarDays.size
    val single = prompt.scope == ScheduleScope.Single
    ZillitDialogShell(
        title = str(if (delete) S.bs_sched_scope_delete_title else S.bs_sched_scope_edit_title),
        subtitle = str(S.bs_sched_scope_subtitle),
        icon = if (delete) ZillitIcons.Trash else ZillitIcons.Edit,
        onDismiss = { onEvent(ScheduleEvent.CancelScope) },
        visible = true,
        width = 460.dp,
        actions = { ScopeDialogActions(delete, single, prompt, onEvent) },
    ) {
        BlockSummary(block, state)
        ZillitText(
            str(if (delete) S.bs_sched_scope_choose_delete else S.bs_sched_scope_choose_edit),
            style = ZillitTheme.typography.titleSmall,
        )
        OptionCard(
            selected = single,
            title = str(S.bs_sched_scope_this_date_title),
            description = if (delete) {
                str(S.desktop_bs_scope_this_date_desc_delete, date)
            } else {
                str(S.bs_sched_scope_this_date_desc_edit, date)
            },
            accent = accent,
            onClick = { onEvent(ScheduleEvent.ChooseScope(ScheduleScope.Single)) },
        )
        OptionCard(
            selected = !single,
            title = str(S.bs_sched_scope_complete_title),
            description = if (delete) {
                str(S.desktop_bs_scope_complete_desc_delete, plural(S.bs_day_count, count))
            } else {
                str(S.bs_sched_scope_complete_desc_edit, plural(S.bs_day_count, count))
            },
            accent = accent,
            onClick = { onEvent(ScheduleEvent.ChooseScope(ScheduleScope.Complete)) },
        )
    }
}

/** The schedule a scope prompt is about: its colour, type, and span. */
@Composable
private fun BlockSummary(block: ScheduleBlock, state: BoxScheduleUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dot(swatchColor(block.color), size = 10.dp)
            ZillitText(block.typeName, style = ZillitTheme.typography.titleSmall)
        }
        ZillitText(
            DiaryFormat.spanLabel(block, state.zone),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.padding(start = 18.dp),
        )
    }
}

/** "Delete Schedule Day" — the plain confirm for a one-day schedule. */
@Composable
internal fun DeleteDayDialog(prompt: DeleteDayPrompt, onEvent: (BoxScheduleEvent) -> Unit) {
    ConfirmDialog(
        title = str(S.desktop_bs_delete_schedule_day_title),
        message = str(S.desktop_bs_delete_schedule_day_message),
        confirm = str(S.delete),
        working = prompt.working,
        onConfirm = { onEvent(ScheduleEvent.ConfirmDeleteDay) },
        onCancel = { onEvent(ScheduleEvent.CancelDeleteDay) },
    )
}

/** "Delete Script" — a whole block from its card. */
@Composable
internal fun DeleteBlockDialog(state: BoxScheduleUiState, block: ScheduleBlock, onEvent: (BoxScheduleEvent) -> Unit) {
    val colors = ZillitTheme.colors
    ZillitDialogShell(
        title = str(S.bs_delete_script),
        icon = ZillitIcons.Trash,
        onDismiss = { onEvent(ScheduleEvent.CancelDeleteBlock) },
        visible = true,
        width = 440.dp,
        actions = {
            ZillitButton(
                str(S.cancel),
                onClick = { onEvent(ScheduleEvent.CancelDeleteBlock) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                str(S.bs_delete_script),
                onClick = { onEvent(ScheduleEvent.ConfirmDeleteBlock) },
                variant = ButtonVariant.Danger,
            )
        },
    ) {
        ZillitText(
            str(S.desktop_bs_delete_script_message, block.calendarDays.size),
            style = ZillitTheme.typography.bodyMedium,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surfaceSunken)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Dot(swatchColor(block.color), size = 10.dp, square = true)
            ZillitText(block.typeName, style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold))
            ZillitText(
                DiaryFormat.blockRange(block, state.zone),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
        ZillitText(
            str(S.action_cannot_be_undone),
            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Medium),
            color = colors.danger,
        )
    }
}

/** "Delete all schedules on this day?" */
@Composable
internal fun DeleteAllOnDialog(state: BoxScheduleUiState, dayKey: Long, onEvent: (BoxScheduleEvent) -> Unit) {
    ConfirmDialog(
        title = str(S.desktop_bs_delete_all_on_day_title),
        message = str(S.desktop_bs_delete_all_on_day_message, DiaryFormat.longDate(dayKey, state.zone)),
        confirm = str(S.delete_all),
        onConfirm = { onEvent(ScheduleEvent.ConfirmDeleteAllOn) },
        onCancel = { onEvent(ScheduleEvent.CancelDeleteAllOn) },
    )
}

/** Delete Selected's confirm. */
@Composable
internal fun BulkDeleteDialog(count: Int, onEvent: (BoxScheduleEvent) -> Unit) {
    ConfirmDialog(
        title = str(S.desktop_bs_delete_selected_days_title),
        message = str(S.desktop_bs_delete_selected_days_message, count),
        confirm = str(S.delete),
        onConfirm = { onEvent(ScheduleEvent.ConfirmBulkDelete) },
        onCancel = { onEvent(ScheduleEvent.CancelBulkDelete) },
    )
}

@Composable
internal fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    working: Boolean = false,
    danger: Boolean = true,
) {
    ZillitDialogShell(
        title = title,
        icon = if (danger) ZillitIcons.Trash else ZillitIcons.Info,
        onDismiss = onCancel,
        visible = true,
        width = 420.dp,
        actions = {
            ZillitButton(str(S.cancel), onClick = onCancel, variant = ButtonVariant.Secondary)
            ZillitButton(
                confirm,
                onClick = onConfirm,
                variant = if (danger) ButtonVariant.Danger else ButtonVariant.Primary,
                loading = working,
            )
        },
    ) {
        ZillitText(message, style = ZillitTheme.typography.bodyMedium, color = ZillitTheme.colors.textSecondary)
    }
}

/**
 * A recurring occurrence's scope — `BoxScheduleEventUpdateScopePrompt` and
 * `BoxScheduleEventDeletePrompt`. A plain row's delete is a plain confirm.
 */
@Composable
internal fun EntryScopeDialog(
    state: BoxScheduleUiState,
    prompt: EntryScopePrompt,
    entry: DiaryEvent,
    delete: Boolean,
    onEvent: (BoxScheduleEvent) -> Unit,
) {
    val isNote = entry.kind == DiaryKind.Note
    val noun = str(if (isNote) S.note else S.ce_event_tab)
    if (delete && !entry.isRecurring) {
        ConfirmDialog(
            title = str(if (isNote) S.desktop_bs_delete_note_title else S.desktop_bs_delete_event_title),
            message = str(
                if (isNote) S.desktop_bs_delete_note_message else S.desktop_bs_delete_event_message,
                entry.title.ifBlank { noun },
            ),
            confirm = str(if (isNote) S.desktop_bs_delete_note_confirm else S.confirm_delete_title),
            working = prompt.working,
            onConfirm = { onEvent(EntryEvent.ConfirmDelete) },
            onCancel = { onEvent(EntryEvent.CancelDelete) },
        )
    } else {
        RecurringScopeDialog(state, prompt, entry, noun, if (delete) DELETE_SCOPE else EDIT_SCOPE, onEvent)
    }
}

/** How the recurring prompt reads, and what it sends, for an edit or for a delete. */
private class ScopeFlavour(
    val delete: Boolean,
    val titleKey: String,
    val subtitleKey: String,
    val questionKey: String,
    val cancel: BoxScheduleEvent,
    val confirm: BoxScheduleEvent,
    val choose: (RecurrenceScope) -> BoxScheduleEvent,
)

private val DELETE_SCOPE = ScopeFlavour(
    delete = true,
    titleKey = S.bs_delete_recurring_dialog_title,
    subtitleKey = S.bs_delete_recurring_dialog_subtitle,
    questionKey = S.bs_delete_recurring_choose,
    cancel = EntryEvent.CancelDelete,
    confirm = EntryEvent.ConfirmDelete,
    choose = { EntryEvent.ChooseDeleteScope(it) },
)

private val EDIT_SCOPE = ScopeFlavour(
    delete = false,
    titleKey = S.edit_recurring_event,
    subtitleKey = S.desktop_bs_edit_recurring_subtitle,
    questionKey = S.desktop_bs_edit_recurring_choose,
    cancel = EntryEvent.CancelUpdateScope,
    confirm = EntryEvent.ConfirmUpdateScope,
    choose = { EntryEvent.ChooseUpdateScope(it) },
)

@Composable
private fun RecurringScopeDialog(
    state: BoxScheduleUiState,
    prompt: EntryScopePrompt,
    entry: DiaryEvent,
    noun: String,
    flavour: ScopeFlavour,
    onEvent: (BoxScheduleEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val delete = flavour.delete
    ZillitDialogShell(
        title = str(flavour.titleKey),
        subtitle = str(flavour.subtitleKey),
        icon = if (delete) ZillitIcons.Warning else ZillitIcons.Edit,
        onDismiss = { onEvent(flavour.cancel) },
        visible = true,
        width = 500.dp,
        actions = {
            ZillitButton(
                str(S.cancel),
                onClick = { onEvent(flavour.cancel) },
                variant = ButtonVariant.Secondary,
                enabled = !prompt.working,
            )
            ZillitButton(
                text = scopeCta(prompt.scope, delete),
                onClick = { onEvent(flavour.confirm) },
                variant = if (delete) ButtonVariant.Danger else ButtonVariant.Primary,
                loading = prompt.working,
            )
        },
    ) {
        SeriesSummary(entry, state, noun)
        ZillitText(str(flavour.questionKey), style = ZillitTheme.typography.bodyMedium, color = colors.textSecondary)
        val day = entry.occurrenceDate.takeIf { it > 0 }?.let { DiaryFormat.shortWeekdayDate(it, state.zone) }
        RecurrenceScope.entries.forEach { scope ->
            OptionCard(
                selected = prompt.scope == scope,
                title = str(SCOPE_TITLES.getValue(scope)),
                description = scopeDescription(scope, day, delete),
                accent = if (delete) colors.danger else colors.accent,
                enabled = !prompt.working,
                onClick = { onEvent(flavour.choose(scope)) },
            )
        }
    }
}

/** The series a scope prompt is about: its title, this occurrence, and how it repeats. */
@Composable
private fun SeriesSummary(entry: DiaryEvent, state: BoxScheduleUiState, noun: String) {
    val colors = ZillitTheme.colors
    val moment = entry.occurrenceDate.takeIf { it > 0 }?.let {
        val date = DiaryFormat.shortWeekdayDate(it, state.zone)
        if (entry.fullDay) date else "$date • ${DiaryFormat.time(it, state.zone)}"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dot(hexColor(entry.color) ?: EVENT_BLUE, size = 10.dp)
            ZillitText(entry.title.ifBlank { noun }, style = ZillitTheme.typography.titleSmall, maxLines = 1)
        }
        moment?.let { IconLine(ZillitIcons.Calendar, it) }
        IconLine(ZillitIcons.Reload, cadence(entry, state))
    }
}

/** Each scope to the catalogue key of its title. */
private val SCOPE_TITLES = mapOf(
    RecurrenceScope.Single to S.bs_delete_opt_single_title,
    RecurrenceScope.ThisAndFollowing to S.bs_delete_opt_following_title,
    RecurrenceScope.All to S.bs_delete_opt_all_title,
)

/** What each scope does, named with the occurrence's day when there is one. */
private fun scopeDescription(scope: RecurrenceScope, day: String?, delete: Boolean): String = when (scope) {
    RecurrenceScope.Single -> when {
        day == null -> str(S.desktop_bs_scope_only_this_occurrence)
        delete -> str(S.bs_delete_opt_single_desc, day)
        else -> str(S.desktop_bs_scope_single_edit_desc, day)
    }
    RecurrenceScope.ThisAndFollowing -> when {
        day == null -> str(S.desktop_bs_scope_this_and_later)
        delete -> str(S.bs_delete_opt_following_desc, day)
        else -> str(S.desktop_bs_scope_following_edit_desc, day)
    }
    RecurrenceScope.All -> if (delete) {
        str(S.bs_delete_opt_all_desc)
    } else {
        str(S.desktop_bs_scope_all_edit_desc)
    }
}

private fun scopeCta(scope: RecurrenceScope, delete: Boolean): String = str(
    when (scope) {
        RecurrenceScope.Single -> if (delete) S.bs_delete_btn_single else S.bs_update_btn_continue_single
        RecurrenceScope.ThisAndFollowing ->
            if (delete) S.bs_delete_btn_following else S.bs_update_btn_continue_following
        RecurrenceScope.All -> if (delete) S.bs_delete_btn_all else S.bs_update_btn_continue_all
    },
)

private fun cadence(entry: DiaryEvent, state: BoxScheduleUiState): String {
    val label = when (entry.repeatStatus) {
        "daily" -> str(S.daily)
        "weekly" -> str(S.ce_weekly)
        "monthly" -> str(S.ce_monthly)
        "yearly" -> str(S.yearly)
        else -> str(S.recurring)
    }
    if (entry.repeatEndDate <= 0) return label
    return str(S.bs_delete_recurring_summary, label, DiaryFormat.mediumDate(entry.repeatEndDate, state.zone))
}

@Composable
private fun IconLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ZillitIcon(icon = icon, tint = ZillitTheme.colors.textMuted, size = 12.dp)
        ZillitText(text, style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted)
    }
}

/** A Calendar-sourced event cannot be changed from the diary. */
@Composable
internal fun CalendarInfoDialog(info: CalendarInfo, onEvent: (BoxScheduleEvent) -> Unit) {
    ZillitDialogShell(
        title = str(S.desktop_bs_calendar_event_title),
        icon = ZillitIcons.Calendar,
        onDismiss = { onEvent(EntryEvent.CloseCalendarInfo) },
        visible = true,
        width = 420.dp,
        actions = { ZillitButton(str(S.dd_action_got_it), onClick = { onEvent(EntryEvent.CloseCalendarInfo) }) },
    ) {
        ZillitText(
            text = str(
                if (info.forDelete) S.desktop_bs_calendar_event_no_delete else S.desktop_bs_calendar_event_no_edit,
            ),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.width(360.dp),
        )
    }
}
