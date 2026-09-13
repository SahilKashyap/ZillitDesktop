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
        title = "Schedule Conflict",
        icon = ZillitIcons.Warning,
        onDismiss = { onEvent(ScheduleEvent.CancelConflict) },
        visible = true,
        width = 520.dp,
        actions = {
            ZillitButton(
                "← Back to Edit Dates",
                onClick = { onEvent(ScheduleEvent.ConflictBack) },
                variant = ButtonVariant.Tertiary,
                enabled = !prompt.saving,
            )
            Box(Modifier.weight(1f))
            ZillitButton(
                "Cancel",
                onClick = { onEvent(ScheduleEvent.CancelConflict) },
                variant = ButtonVariant.Secondary,
                enabled = !prompt.saving,
            )
            ZillitButton(
                "Done",
                onClick = { onEvent(ScheduleEvent.ResolveConflict) },
                enabled = prompt.choice != null,
                loading = prompt.saving,
            )
        },
    ) {
        ZillitText(
            "${prompt.conflicts.size} date(s) overlap with existing schedules:",
            style = ZillitTheme.typography.bodyMedium,
        )
        ConflictTable(state, prompt.conflicts)
        ZillitText("What would you like to do?", style = ZillitTheme.typography.titleSmall)
        CONFLICT_OPTIONS.forEach { (action, description) ->
            OptionCard(
                selected = prompt.choice == action,
                title = action.label,
                description = description,
                badge = "Recommended".takeIf { action == ConflictAction.Replace },
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
            Caption("Date", Modifier.weight(1f))
            Caption("Current Type", Modifier.weight(1.2f))
            Caption("Schedule", Modifier.weight(1f))
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

private val CONFLICT_OPTIONS = listOf(
    ConflictAction.Replace to "Remove existing schedule on these dates and use your new schedule instead",
    ConflictAction.Extend to "Keep existing schedule, only fill empty dates with new schedule",
    ConflictAction.Overlap to "Keep existing schedule and also add the new one on the same dates",
)

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
        title = if (delete) "Delete schedule" else "Edit schedule",
        subtitle = "This schedule covers more than one day.",
        icon = if (delete) ZillitIcons.Trash else ZillitIcons.Edit,
        onDismiss = { onEvent(ScheduleEvent.CancelScope) },
        visible = true,
        width = 460.dp,
        actions = {
            ZillitButton("Cancel", onClick = { onEvent(ScheduleEvent.CancelScope) }, variant = ButtonVariant.Secondary)
            ZillitButton(
                text = "${if (delete) "Delete" else "Edit"} ${if (single) "this date" else "schedule"}",
                onClick = { onEvent(ScheduleEvent.ConfirmScope) },
                variant = if (delete) ButtonVariant.Danger else ButtonVariant.Primary,
                loading = prompt.working,
            )
        },
    ) {
        BlockSummary(block, state)
        ZillitText(
            if (delete) "What do you want to delete?" else "What do you want to edit?",
            style = ZillitTheme.typography.titleSmall,
        )
        OptionCard(
            selected = single,
            title = "This date only",
            description = if (delete) {
                "Remove $date from this schedule only. Every other day stays as it is."
            } else {
                "Change the type for $date only. Every other day in this schedule stays as it is."
            },
            accent = accent,
            onClick = { onEvent(ScheduleEvent.ChooseScope(ScheduleScope.Single)) },
        )
        OptionCard(
            selected = !single,
            title = "Complete schedule",
            description = if (delete) {
                "Delete the complete schedule — all $count day(s). This action cannot be undone."
            } else {
                "Change the type and dates for all $count day(s) in this schedule."
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
        title = "Delete Schedule Day",
        message = "Are you sure you want to delete this schedule day? This action cannot be undone.",
        confirm = "Delete",
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
        title = "Delete Script",
        icon = ZillitIcons.Trash,
        onDismiss = { onEvent(ScheduleEvent.CancelDeleteBlock) },
        visible = true,
        width = 440.dp,
        actions = {
            ZillitButton(
                "Cancel",
                onClick = { onEvent(ScheduleEvent.CancelDeleteBlock) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                "Delete Script",
                onClick = { onEvent(ScheduleEvent.ConfirmDeleteBlock) },
                variant = ButtonVariant.Danger,
            )
        },
    ) {
        ZillitText(
            "Are you sure you want to delete this script? " +
                "This will remove all ${block.calendarDays.size} day(s) and any linked events.",
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
            "This action cannot be undone.",
            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Medium),
            color = colors.danger,
        )
    }
}

/** "Delete all schedules on this day?" */
@Composable
internal fun DeleteAllOnDialog(state: BoxScheduleUiState, dayKey: Long, onEvent: (BoxScheduleEvent) -> Unit) {
    ConfirmDialog(
        title = "Delete all schedules on this day?",
        message = "Every schedule on ${DiaryFormat.longDate(dayKey, state.zone)} loses this date. " +
            "A schedule that is only this day is removed.",
        confirm = "Delete all",
        onConfirm = { onEvent(ScheduleEvent.ConfirmDeleteAllOn) },
        onCancel = { onEvent(ScheduleEvent.CancelDeleteAllOn) },
    )
}

/** Delete Selected's confirm. */
@Composable
internal fun BulkDeleteDialog(count: Int, onEvent: (BoxScheduleEvent) -> Unit) {
    ConfirmDialog(
        title = "Delete selected days",
        message = "Are you sure you want to delete $count day(s)?",
        confirm = "Delete",
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
            ZillitButton("Cancel", onClick = onCancel, variant = ButtonVariant.Secondary)
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
    val noun = if (entry.kind == DiaryKind.Note) "note" else "event"
    if (delete && !entry.isRecurring) {
        ConfirmDialog(
            title = "Delete $noun?",
            message = "This will permanently remove the $noun “${entry.title.ifBlank { noun }}”.",
            confirm = "Delete $noun",
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
    val title: String,
    val subtitle: String,
    val question: String,
    val cancel: BoxScheduleEvent,
    val confirm: BoxScheduleEvent,
    val choose: (RecurrenceScope) -> BoxScheduleEvent,
)

private val DELETE_SCOPE = ScopeFlavour(
    delete = true,
    title = "Delete recurring event?",
    subtitle = "This action cannot be undone.",
    question = "Choose how much of the series to delete:",
    cancel = EntryEvent.CancelDelete,
    confirm = EntryEvent.ConfirmDelete,
    choose = { EntryEvent.ChooseDeleteScope(it) },
)

private val EDIT_SCOPE = ScopeFlavour(
    delete = false,
    title = "Edit recurring event",
    subtitle = "Choose which events this change applies to.",
    question = "Choose which events to edit:",
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
        title = flavour.title,
        subtitle = flavour.subtitle,
        icon = if (delete) ZillitIcons.Warning else ZillitIcons.Edit,
        onDismiss = { onEvent(flavour.cancel) },
        visible = true,
        width = 500.dp,
        actions = {
            ZillitButton(
                "Cancel",
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
        ZillitText(flavour.question, style = ZillitTheme.typography.bodyMedium, color = colors.textSecondary)
        val day = entry.occurrenceDate.takeIf { it > 0 }?.let { DiaryFormat.shortWeekdayDate(it, state.zone) }
        RecurrenceScope.entries.forEach { scope ->
            OptionCard(
                selected = prompt.scope == scope,
                title = SCOPE_TITLES.getValue(scope),
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

private val SCOPE_TITLES = mapOf(
    RecurrenceScope.Single to "This event only",
    RecurrenceScope.ThisAndFollowing to "This and following events",
    RecurrenceScope.All to "All events in the series",
)

/** What each scope does, named with the occurrence's day when there is one. */
private fun scopeDescription(scope: RecurrenceScope, day: String?, delete: Boolean): String = when (scope) {
    RecurrenceScope.Single -> when {
        day == null -> "Only this occurrence."
        delete -> "Removes the $day occurrence. Earlier and later dates stay."
        else -> "Changes the $day occurrence alone; it becomes a separate event."
    }
    RecurrenceScope.ThisAndFollowing -> when {
        day == null -> "This occurrence and every later date."
        delete -> "Removes the $day occurrence and every date after it. Earlier dates stay."
        else -> "Changes $day and every date after it. Earlier dates stay."
    }
    RecurrenceScope.All -> if (delete) {
        "Removes every occurrence, past and future. The series is gone."
    } else {
        "Changes every occurrence, past and future."
    }
}

private fun scopeCta(scope: RecurrenceScope, delete: Boolean): String {
    val verb = if (delete) "Delete" else "Edit"
    return when (scope) {
        RecurrenceScope.Single -> "$verb this event"
        RecurrenceScope.ThisAndFollowing -> "$verb this and following"
        RecurrenceScope.All -> "$verb all events"
    }
}

private fun cadence(entry: DiaryEvent, state: BoxScheduleUiState): String {
    val label = when (entry.repeatStatus) {
        "daily" -> "Daily"
        "weekly" -> "Weekly"
        "monthly" -> "Monthly"
        "yearly" -> "Yearly"
        else -> "Recurring"
    }
    if (entry.repeatEndDate <= 0) return label
    return "$label · until ${DiaryFormat.mediumDate(entry.repeatEndDate, state.zone)}"
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
        title = "Calendar Event",
        icon = ZillitIcons.Calendar,
        onDismiss = { onEvent(EntryEvent.CloseCalendarInfo) },
        visible = true,
        width = 420.dp,
        actions = { ZillitButton("Got it", onClick = { onEvent(EntryEvent.CloseCalendarInfo) }) },
    ) {
        ZillitText(
            text = if (info.forDelete) {
                "This event was created in the Calendar module and cannot be deleted from " +
                    "Production Diary/Box Schedule. Open it from the Calendar module to delete it."
            } else {
                "This event was created in the Calendar module and cannot be edited from " +
                    "Production Diary/Box Schedule. Open it from the Calendar module to make changes."
            },
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.width(360.dp),
        )
    }
}
