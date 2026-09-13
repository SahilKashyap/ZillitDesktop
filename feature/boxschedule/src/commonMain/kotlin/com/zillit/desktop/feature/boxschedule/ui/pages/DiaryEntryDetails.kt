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
        title = if (isEvent) "EVENT DETAILS" else "NOTE DETAILS",
        onDismiss = { onEvent(DayEvent.CloseViewEntry) },
        width = 480.dp,
        padded = false,
        footer = if (canChange) {
            {
                ZillitButton(
                    text = "Delete",
                    onClick = { onEvent(EntryEvent.AskDelete(entry.listKey)) },
                    variant = ButtonVariant.Danger,
                    leadingIcon = ZillitIcons.Trash,
                )
                ZillitButton(
                    text = "Edit This ${if (isEvent) "Event" else "Note"}",
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
        Caption("Add to this day", modifier = Modifier.weight(1f))
        CreateButton(
            if (isEvent) "Create Event" else "Create Note",
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
        DetailLine(ZillitIcons.Edit, if (isEvent) "Description" else "Notes") { DetailText(entry.body) }
    }
    if (entry.location.isNotBlank()) DetailLine(ZillitIcons.Pin, "Location") { LocationLink(entry) }
    if (!isEvent) {
        val label = state.noteTypes.firstOrNull { it.value == entry.noteType }?.label
            ?: if (entry.isPersonalNote) "Personal Note" else "General"
        DetailLine(ZillitIcons.Info, "Type") { DetailText(label) }
    }
    if (entry.callType.isNotBlank()) {
        DetailLine(ZillitIcons.Users, "Call Type") { DetailText(DiaryFormat.callTypeLabel(entry.callType)) }
    }
    if (entry.timezone.isNotBlank()) {
        DetailLine(ZillitIcons.Clock, "Timezone") { DetailText(DiaryFormat.timezoneLabel(entry.timezone)) }
    }
    if (entry.reminder.isNotBlank() && entry.reminder != "none") {
        DetailLine(ZillitIcons.Bell, "Reminder") { DetailText(DiaryFormat.reminderLabel(entry.reminder)) }
    }
    if (entry.repeatStatus.isNotBlank() && entry.repeatStatus != "none" && !entry.calendarSourced) {
        DetailLine(ZillitIcons.Reload, "Repeat") { DetailText(DiaryFormat.repeatLine(entry, state.zone)) }
    }
}

/** Who it is for and who made it: the audience, guests, the organizer, the author, and where it came from. */
@Composable
private fun EntryPeople(entry: DiaryEvent, state: BoxScheduleUiState) {
    if (entry.audience.isSet) {
        DetailLine(ZillitIcons.Users, "Distribute To") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailText(entry.audience.mode.label)
                AudienceChip(entry.audience, state)
            }
        }
    }
    if (entry.externalEmails.isNotEmpty()) {
        DetailLine(ZillitIcons.Mail, "External Guests (${entry.externalEmails.size})") {
            Column { entry.externalEmails.forEach { ZillitText(it.mail, style = ZillitTheme.typography.bodySmall) } }
        }
    }
    if (entry.kind == DiaryKind.Event && entry.fullDay) {
        DetailLine(ZillitIcons.Calendar, "Duration") { DetailText("Full Day Event") }
    }
    if (entry.organizerExcluded) {
        DetailLine(ZillitIcons.User, "Organizer") { DetailText("The organizer will not be a part of this event") }
    }
    if (entry.createdByName.isNotBlank()) {
        val stamp = if (entry.createdAt > 0) " — ${DiaryFormat.fullStamp(entry.createdAt, state.zone)}" else ""
        DetailLine(ZillitIcons.User, "Created By") { DetailText(entry.createdByName + stamp) }
    }
    if (entry.calendarSourced) {
        DetailLine(ZillitIcons.Info, "Calendar Event") {
            ZillitText(
                "Created in the Calendar module — open it there to make changes.",
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
                entry.title.ifBlank { if (isEvent) "(untitled)" else "Untitled note" },
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
                    text = if (entry.prefersVideoCall) "Join video call" else "Join call",
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
