package com.zillit.desktop.feature.boxschedule.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.boxschedule.domain.DiaryCalendar
import com.zillit.desktop.feature.boxschedule.domain.DiaryEvent
import com.zillit.desktop.feature.boxschedule.domain.DiaryFormat
import com.zillit.desktop.feature.boxschedule.domain.DiaryKind
import com.zillit.desktop.feature.boxschedule.domain.isPersonalNote
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleEvent
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleUiState
import com.zillit.desktop.feature.boxschedule.ui.DayEvent
import com.zillit.desktop.feature.boxschedule.ui.EntryEvent
import com.zillit.desktop.feature.boxschedule.ui.PageEvent

/**
 * An entry's complete details — `ViewEventDrawer`: every filled field, and
 * Edit and Delete inside it. A Calendar-sourced event is view-only here.
 */
@Composable
internal fun EntryDetailsSheet(
    state: BoxScheduleUiState,
    entry: DiaryEvent,
    onEvent: (BoxScheduleEvent) -> Unit,
    mayCall: Boolean,
) {
    val isEvent = entry.kind == DiaryKind.Event
    val dayKey = DiaryCalendar.dayKey(entry.anchor, state.zone)
    val canChange = state.mayEdit && !entry.calendarSourced
    DiarySheet(
        title = if (isEvent) str(S.desktop_bs_event_details_upper) else str(S.desktop_bs_note_details_upper),
        onDismiss = { onEvent(DayEvent.CloseViewEntry) },
        width = 480.dp,
        padded = false,
        footer = if (canChange) {
            {
                ZillitButton(
                    text = str(S.delete),
                    onClick = { onEvent(EntryEvent.AskDelete(entry.listKey)) },
                    variant = ButtonVariant.Danger,
                    leadingIcon = ZillitIcons.Trash,
                )
                ZillitButton(
                    text = str(S.desktop_bs_edit_this_kind, if (isEvent) str(S.ce_event_tab) else str(S.note_label)),
                    onClick = { onEvent(EntryEvent.EditEntry(entry.listKey)) },
                    leadingIcon = ZillitIcons.Edit,
                )
            }
        } else {
            null
        },
    ) {
        if (state.mayEdit && entry.anchor > 0 && !state.isPast(dayKey)) AddToEntryDay(entry, dayKey, onEvent)
        EntryHero(entry, state, mayCall, onEvent)
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
            EntryFacts(entry, state)
            EntryPeople(entry, state)
        }
    }
}

/** "Add to this day" — another entry of the same kind on the date this one is on. */
@Composable
private fun AddToEntryDay(entry: DiaryEvent, dayKey: Long, onEvent: (BoxScheduleEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val isEvent = entry.kind == DiaryKind.Event
    Row(
        Modifier.fillMaxWidth().background(colors.surfaceSunken).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Caption(str(S.dd_add_to_day), modifier = Modifier.weight(1f))
        CreateButton(
            if (isEvent) str(S.create_event) else str(S.bs_create_note),
            if (isEvent) ZillitIcons.Clock else ZillitIcons.Edit,
        ) {
            onEvent(EntryEvent.NewEntry(entry.kind, dayKey))
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

/** What the entry is: its text, place, kind, call, zone, reminder and repeat. */
@Composable
private fun EntryFacts(entry: DiaryEvent, state: BoxScheduleUiState) {
    val isEvent = entry.kind == DiaryKind.Event
    if (entry.body.isNotBlank()) {
        DetailLine(ZillitIcons.Edit, if (isEvent) str(S.description) else str(S.notes)) { DetailText(entry.body) }
    }
    if (entry.location.isNotBlank()) DetailLine(ZillitIcons.Pin, str(S.location)) { LocationLink(entry) }
    if (!isEvent) {
        val label = state.noteTypes.firstOrNull { it.value == entry.noteType }?.label
            ?: if (entry.isPersonalNote) str(S.ce_note_type_personal) else str(S.ce_note_type_general)
        DetailLine(ZillitIcons.Info, str(S.type)) { DetailText(label) }
    }
    if (entry.callType.isNotBlank()) {
        DetailLine(ZillitIcons.Users, str(S.call_type)) { DetailText(DiaryFormat.callTypeLabel(entry.callType)) }
    }
    if (entry.timezone.isNotBlank()) {
        DetailLine(ZillitIcons.Clock, str(S.timezone)) { DetailText(DiaryFormat.timezoneLabel(entry.timezone)) }
    }
    if (entry.reminder.isNotBlank() && entry.reminder != "none") {
        DetailLine(ZillitIcons.Bell, str(S.reminder)) { DetailText(DiaryFormat.reminderLabel(entry.reminder)) }
    }
    if (entry.repeatStatus.isNotBlank() && entry.repeatStatus != "none" && !entry.calendarSourced) {
        DetailLine(ZillitIcons.Reload, str(S.repeat)) { DetailText(DiaryFormat.repeatLine(entry, state.zone)) }
    }
}

/** Who it is for and who made it: the audience, guests, the organizer, the author, and where it came from. */
@Composable
private fun EntryPeople(entry: DiaryEvent, state: BoxScheduleUiState) {
    if (entry.audience.isSet) {
        DetailLine(ZillitIcons.Users, str(S.distribute_to)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailText(entry.audience.mode.label)
                AudienceChip(entry.audience, state)
            }
        }
    }
    if (entry.externalEmails.isNotEmpty()) {
        DetailLine(ZillitIcons.Mail, str(S.desktop_bs_external_guests_n, entry.externalEmails.size)) {
            Column { entry.externalEmails.forEach { ZillitText(it.mail, style = ZillitTheme.typography.bodySmall) } }
        }
    }
    if (entry.kind == DiaryKind.Event && entry.fullDay) {
        DetailLine(ZillitIcons.Calendar, str(S.desktop_cal_duration)) { DetailText(str(S.desktop_bs_full_day_event)) }
    }
    if (entry.organizerExcluded) {
        DetailLine(ZillitIcons.User, str(S.desktop_bs_organizer)) { DetailText(str(S.exclude_me)) }
    }
    if (entry.createdByName.isNotBlank()) {
        val stamp = if (entry.createdAt > 0) " — ${DiaryFormat.fullStamp(entry.createdAt, state.zone)}" else ""
        DetailLine(ZillitIcons.User, str(S.created_by)) { DetailText(entry.createdByName + stamp) }
    }
    if (entry.calendarSourced) {
        DetailLine(ZillitIcons.Info, str(S.desktop_bs_calendar_event_title)) {
            ZillitText(
                str(S.desktop_bs_created_in_calendar_module),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

@Composable
private fun DetailText(text: String) {
    ZillitText(text, style = ZillitTheme.typography.bodyMedium)
}

@Composable
private fun EntryHero(
    entry: DiaryEvent,
    state: BoxScheduleUiState,
    mayCall: Boolean,
    onEvent: (BoxScheduleEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val isEvent = entry.kind == DiaryKind.Event
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(hexColor(entry.color) ?: EVENT_BLUE))
        Column(
            Modifier.weight(1f).padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ZillitText(
                entry.title.ifBlank { if (isEvent) str(S.new_box_untitled) else str(S.desktop_bs_untitled_note) },
                style = serif(20.sp, FontWeight.Bold),
                color = hexColor(entry.textColor) ?: colors.textPrimary,
            )
            if (isEvent) {
                val time = DiaryFormat.timeRange(entry, state.zone)
                if (time.isNotBlank()) {
                    ZillitText(
                        time,
                        style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = colors.textMuted,
                    )
                }
                entryDateLine(entry, state)?.let {
                    ZillitText(it, style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
                }
            } else if (entry.date > 0) {
                ZillitText(
                    DiaryFormat.longDate(entry.date, state.zone),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            if (mayCall && entry.canJoin(state)) {
                ZillitButton(
                    text = if (entry.prefersVideoCall) str(S.desktop_bs_join_video_call) else str(S.join_call),
                    onClick = { onEvent(PageEvent.JoinCall(entry.listKey)) },
                    leadingIcon = ZillitIcons.Phone,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

/** "Sunday, September 13, 2026", or a start → end across days. */
private fun entryDateLine(entry: DiaryEvent, state: BoxScheduleUiState): String? {
    if (entry.startDateTime <= 0) return null
    val start = DiaryFormat.longDate(entry.startDateTime, state.zone)
    val end = entry.endDateTime.takeIf { it > 0 }?.let { DiaryFormat.longDate(it, state.zone) }
    return if (end != null && end != start) "$start → $end" else start
}

@Composable
private fun DetailLine(icon: ImageVector, label: String, value: @Composable () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        Modifier.fillMaxWidth().gridLines(colors.border.tint(LINE_ALPHA), right = false).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(24.dp).padding(top = 2.dp), contentAlignment = Alignment.Center) {
            ZillitIcon(icon = icon, tint = colors.textMuted, size = 15.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Caption(label)
            value()
        }
    }
}

private const val LINE_ALPHA = 0.7f
