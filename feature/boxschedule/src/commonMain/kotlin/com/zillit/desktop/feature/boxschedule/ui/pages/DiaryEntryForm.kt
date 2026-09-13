package com.zillit.desktop.feature.boxschedule.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.locationpicker.LocalLocationPicker
import com.zillit.desktop.core.locationpicker.PickedLocation
import com.zillit.desktop.core.locationpicker.oneLine
import com.zillit.desktop.feature.boxschedule.domain.DiaryDraft
import com.zillit.desktop.feature.boxschedule.domain.DiaryFormat
import com.zillit.desktop.feature.boxschedule.domain.NoteType
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleEvent
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleUiState
import com.zillit.desktop.feature.boxschedule.ui.DayLink
import com.zillit.desktop.feature.boxschedule.ui.EntryEvent
import com.zillit.desktop.feature.boxschedule.ui.EntryField
import com.zillit.desktop.feature.boxschedule.ui.EntryForm
import com.zillit.desktop.feature.boxschedule.ui.EntryRules
import kotlinx.coroutines.launch

/**
 * "Add Event" / "Add Note" and their edits — `CreateEventModal`. The event
 * side carries every field the web's form does; the note side follows the
 * phones' Add Note: type, date, title, notes, colour, audience.
 */
@Composable
internal fun EntryFormSheet(state: BoxScheduleUiState, form: EntryForm, onEvent: (BoxScheduleEvent) -> Unit) {
    val headerDate = if (form.isNote) form.noteDate else form.headerDate ?: linkedDate(form, state)
    DiarySheet(
        title = form.heading.uppercase(),
        onDismiss = { onEvent(EntryEvent.Close) },
        width = ENTRY_WIDTH,
        headerTrailing = headerDate?.let { date ->
            {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(ZillitTheme.colors.accentSoft)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    ZillitText(
                        text = "${DiaryFormat.weekdayShort(date)}, ${DiaryFormat.mediumDate(date)}",
                        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = ZillitTheme.colors.accentText,
                    )
                }
            }
        },
        footer = {
            ZillitButton("Cancel", onClick = { onEvent(EntryEvent.Close) }, variant = ButtonVariant.Secondary)
            ZillitButton(form.saveLabel, onClick = { onEvent(EntryEvent.Save) }, loading = form.saving)
        },
    ) {
        if (form.showsLinkPicker) LinkPicker(state, form, onEvent)
        if (form.isNote) NoteFields(state, form, onEvent) else EventFields(state, form, onEvent)
    }
}

private fun linkedDate(form: EntryForm, state: BoxScheduleUiState) =
    form.linkKey?.substringAfter('|')?.toLongOrNull()?.let {
        com.zillit.desktop.feature.boxschedule.domain.DiaryCalendar.dateOf(it, state.zone)
    }

@Composable
private fun LinkPicker(state: BoxScheduleUiState, form: EntryForm, onEvent: (BoxScheduleEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val options = remember(state.blocks, form.linkKey, form.isEdit, state.today, state.zone) {
        EntryRules.linkOptions(form, state.blocks, state.todayKey, state.zone)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FieldLabel("Link to a schedule day", note = "(optional)")
        DiaryDropdown(
            selected = options.firstOrNull { it.key == form.linkKey },
            options = options,
            onSelect = { link: DayLink -> onEvent(EntryEvent.SetLink(link.key)) },
            label = { it.label },
            placeholder = "Select a schedule day...",
            searchable = true,
            onClear = { onEvent(EntryEvent.SetLink(null)) },
            emptyText = if (state.blocks.isEmpty()) {
                "No schedule days yet. Create a schedule first."
            } else {
                "No matching days"
            },
            modifier = Modifier.fillMaxWidth(),
            menuWidth = 420.dp,
        )
    }
}

// Event ----------------------------------------------------------------------

@Composable
private fun EventFields(state: BoxScheduleUiState, form: EntryForm, onEvent: (BoxScheduleEvent) -> Unit) {
    EventTitle(form, onEvent)
    Labelled("Event Description") {
        ZillitTextField(
            value = form.description,
            onValueChange = { onEvent(EntryEvent.SetDescription(it)) },
            placeholder = "Event Description",
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    WhenRow(state, form, onEvent)
    if (!form.isSingleScope) RepeatRow(state, form, onEvent)
    TimezoneField(form, onEvent)
    ReminderAndCallRow(form, onEvent)
    TextColorField(form, onEvent)
    LocationBlock(form, onEvent)
    AudienceField(state, form, onEvent)
    ZillitText(
        "Select the department which you want to send the notification",
        style = ZillitTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
        color = ZillitTheme.colors.textMuted,
    )
    GuestsField(form, onEvent)
    OrganizerBox(form, onEvent)
    Labelled("Color") {
        SwatchRow(
            current = form.color,
            options = DiaryDraft.EVENT_COLORS,
            onPick = { onEvent(EntryEvent.SetColor(it)) },
        )
    }
}

@Composable
private fun EventTitle(form: EntryForm, onEvent: (BoxScheduleEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { FieldLabel("Title", required = true) }
            ZillitCheckbox(
                checked = form.fullDay,
                onCheckedChange = { onEvent(EntryEvent.SetFullDay(it)) },
                label = "Full Day",
            )
        }
        ZillitTextField(
            value = form.title,
            onValueChange = { onEvent(EntryEvent.SetTitle(it)) },
            placeholder = "Add Title *",
            errorText = form.errors[EntryField.Title],
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TimezoneField(form: EntryForm, onEvent: (BoxScheduleEvent) -> Unit) {
    Labelled("Select Timezone") {
        val zones = remember(form.timezone) {
            (DiaryFormat.TIMEZONES + form.timezone).filter { it.isNotBlank() }.distinct()
        }
        DiaryDropdown(
            selected = form.timezone.takeIf { it.isNotBlank() },
            options = zones,
            onSelect = { zone: String -> onEvent(EntryEvent.SetTimezone(zone)) },
            label = DiaryFormat::timezoneLabel,
            searchable = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ReminderAndCallRow(form: EntryForm, onEvent: (BoxScheduleEvent) -> Unit) {
    val callError = form.errors[EntryField.CallType]
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Labelled("Add Reminder/Alert", Modifier.weight(1f)) {
            DiaryDropdown(
                selected = DiaryFormat.REMINDERS.firstOrNull { it.first == form.reminder },
                options = DiaryFormat.REMINDERS,
                onSelect = { onEvent(EntryEvent.SetReminder(it.first)) },
                label = { it.second },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Labelled("Call Type", Modifier.weight(1f), required = true, error = callError) {
            DiaryDropdown(
                selected = DiaryFormat.CALL_TYPES.firstOrNull { it.first == form.callType },
                options = DiaryFormat.CALL_TYPES,
                onSelect = { onEvent(EntryEvent.SetCallType(it.first)) },
                label = { it.second },
                placeholder = "Select Call Type",
                error = callError != null,
                modifier = Modifier.fillMaxWidth(),
                menuWidth = 240.dp,
            )
        }
    }
}

/** The title's own colour — black until chosen; Reset hands it back to the theme. */
@Composable
private fun TextColorField(form: EntryForm, onEvent: (BoxScheduleEvent) -> Unit) {
    Labelled("Select Text Color") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ColorPickerButton(
                color = form.textColor.ifBlank { "#000000" },
                onPick = { onEvent(EntryEvent.SetTextColor(it)) },
                presets = TEXT_COLORS,
                showText = true,
            )
            if (form.textColor.isNotBlank()) {
                ZillitButton(
                    "Reset",
                    onClick = { onEvent(EntryEvent.SetTextColor("")) },
                    variant = ButtonVariant.Tertiary,
                )
            }
        }
    }
}

@Composable
private fun WhenRow(state: BoxScheduleUiState, form: EntryForm, onEvent: (BoxScheduleEvent) -> Unit) {
    val errors = form.errors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Labelled(
            label = "Start Date",
            modifier = Modifier.weight(1f),
            required = true,
            note = "(locked to this occurrence)".takeIf { form.isThisAndFollowing },
            error = errors[EntryField.StartDate],
        ) {
            DiaryDateField(
                value = form.startDate,
                onPick = { onEvent(EntryEvent.SetStartDate(it)) },
                today = state.today,
                enabled = !form.isThisAndFollowing,
                error = errors[EntryField.StartDate] != null,
                selectable = { it >= state.today },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (!form.fullDay) {
            Labelled("Start Time", Modifier.width(TIME_WIDTH), required = true, error = errors[EntryField.StartTime]) {
                DiaryTimeField(
                    value = form.startTime,
                    onPick = { onEvent(EntryEvent.SetStartTime(it)) },
                    error = errors[EntryField.StartTime] != null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Labelled("End Time", Modifier.width(TIME_WIDTH), required = true, error = errors[EntryField.EndTime]) {
                DiaryTimeField(
                    value = form.endTime,
                    onPick = { onEvent(EntryEvent.SetEndTime(it)) },
                    error = errors[EntryField.EndTime] != null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Labelled("End Date", Modifier.weight(1f), error = errors[EntryField.EndDate]) {
            // View-only: the end mirrors the start; a multi-day event is a repeat.
            DiaryDateField(
                value = form.endDate,
                onPick = {},
                today = state.today,
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RepeatRow(state: BoxScheduleUiState, form: EntryForm, onEvent: (BoxScheduleEvent) -> Unit) {
    val repeats = form.repeat != "none"
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Labelled("Repeat", Modifier.weight(1f)) {
            DiaryDropdown(
                selected = DiaryFormat.REPEATS.firstOrNull { it.first == form.repeat },
                options = DiaryFormat.REPEATS,
                onSelect = { onEvent(EntryEvent.SetRepeat(it.first)) },
                label = { it.second },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Labelled(
            "Repeat End Date",
            Modifier.weight(1f),
            required = repeats,
            error = form.errors[EntryField.RepeatEnd],
        ) {
            val after = form.startDate ?: state.today
            DiaryDateField(
                value = form.repeatEnd,
                onPick = { onEvent(EntryEvent.SetRepeatEnd(it)) },
                today = state.today,
                placeholder = "Repeat End Date",
                enabled = repeats,
                clearable = true,
                error = form.errors[EntryField.RepeatEnd] != null,
                selectable = { it > after },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The place — typed, or picked on the map where the host has a picker. A
 * pick sends its coordinates beside the line; typing clears them.
 */
@Composable
private fun LocationBlock(form: EntryForm, onEvent: (BoxScheduleEvent) -> Unit) {
    val required = form.callType == "meet_in_person_call"
    val picker = LocalLocationPicker.current
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldLabel("Add Location", required = required)
        ZillitTextField(
            value = form.location,
            onValueChange = { onEvent(EntryEvent.SetLocation(it)) },
            placeholder = "Search a place or type an address",
            errorText = form.errors[EntryField.Location],
            leadingIcon = ZillitIcons.Pin,
            modifier = Modifier.fillMaxWidth(),
            trailingContent = picker?.let {
                {
                    ZillitButton(
                        text = "Pick on map",
                        onClick = {
                            picking = true
                            scope.launch {
                                try {
                                    val initial = form.locationLat?.let { lat ->
                                        form.locationLng?.let { lng -> PickedLocation("", form.location, lat, lng) }
                                    }
                                    picker.pick(initial = initial, title = "Add Location")?.let { place ->
                                        onEvent(EntryEvent.SetLocation(place.oneLine(), place.lat, place.lng))
                                    }
                                } finally {
                                    picking = false
                                }
                            }
                        },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Pin,
                        loading = picking,
                    )
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuestsField(form: EntryForm, onEvent: (BoxScheduleEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel("External Guests")
        if (form.guests.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                form.guests.forEach { mail ->
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(colors.surfaceSunken)
                            .border(1.dp, colors.border, RoundedCornerShape(999.dp))
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        ZillitIcon(icon = ZillitIcons.Mail, tint = colors.textMuted, size = 11.dp)
                        ZillitText(mail, style = ZillitTheme.typography.labelSmall)
                    }
                }
            }
        }
        ZillitButton(
            text = if (form.guests.isEmpty()) "Add External Guests" else "Manage External Guests (${form.guests.size})",
            onClick = { onEvent(EntryEvent.OpenGuests) },
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Mail,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun OrganizerBox(form: EntryForm, onEvent: (BoxScheduleEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        ZillitCheckbox(
            checked = form.organizerExcluded,
            onCheckedChange = { onEvent(EntryEvent.SetOrganizerExcluded(it)) },
            label = "The organizer will not be a part of this event.",
        )
    }
}

// Note -----------------------------------------------------------------------

@Composable
private fun NoteFields(state: BoxScheduleUiState, form: EntryForm, onEvent: (BoxScheduleEvent) -> Unit) {
    val errors = form.errors
    Labelled("Type", required = true) {
        NoteTypeSegments(state.noteTypes.ifEmpty { NoteType.DEFAULTS }, form.noteType, onEvent)
    }
    Labelled(
        "Date",
        required = true,
        note = "(locked to schedule day)".takeIf { form.noteDateLocked },
        error = errors[EntryField.NoteDate],
    ) {
        DiaryDateField(
            value = form.noteDate,
            onPick = { onEvent(EntryEvent.SetNoteDate(it)) },
            today = state.today,
            enabled = !form.noteDateLocked,
            error = errors[EntryField.NoteDate] != null,
            selectable = { it >= state.today },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Labelled("Title", required = true) {
        ZillitTextField(
            value = form.noteTitle,
            onValueChange = { onEvent(EntryEvent.SetNoteTitle(it)) },
            placeholder = "e.g., Rain backup plan needed",
            errorText = errors[EntryField.NoteTitle],
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Labelled("Notes") {
        ZillitTextField(
            value = form.noteText,
            onValueChange = { onEvent(EntryEvent.SetNoteText(it)) },
            placeholder = "Details...",
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Labelled("Color") {
        SwatchRow(
            current = form.noteColor,
            options = DiaryDraft.EVENT_COLORS,
            onPick = { onEvent(EntryEvent.SetNoteColor(it)) },
        )
    }
    if (!EntryRules.hidesDistribution(form.noteType, state.noteTypes)) AudienceField(state, form, onEvent)
}

/** General or Personal Note — a segmented control, as the web's note tab draws it. */
@Composable
private fun NoteTypeSegments(kinds: List<NoteType>, current: String, onEvent: (BoxScheduleEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        kinds.forEach { kind ->
            val active = kind.value == current
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (active) colors.surface else Color.Transparent)
                    .clickable { onEvent(EntryEvent.SetNoteType(kind.value)) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                ZillitText(
                    kind.label,
                    style = ZillitTheme.typography.bodyMedium.copy(
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (active) colors.textPrimary else colors.textMuted,
                )
            }
        }
    }
}

// Shared ---------------------------------------------------------------------

/** The Distribute To field: its summary, opening the five-tab picker. */
@Composable
private fun AudienceField(state: BoxScheduleUiState, form: EntryForm, onEvent: (BoxScheduleEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val error = form.errors[EntryField.Audience]
    val presetName = form.audience.presetId?.let { id ->
        form.audiencePicker?.presets?.firstOrNull { it.id == id }?.name
    }
    val summary = form.audience.summary(presetName)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldLabel("Distribute To", required = true)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(colors.surfaceSunken)
                .border(1.dp, if (error != null) colors.danger else colors.border, RoundedCornerShape(6.dp))
                .clickable { onEvent(EntryEvent.OpenAudience) }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ZillitIcon(icon = ZillitIcons.Users, tint = colors.textMuted, size = 14.dp)
            ZillitText(
                text = summary.ifBlank { "Select" },
                style = ZillitTheme.typography.bodyMedium,
                color = if (summary.isBlank()) colors.textMuted else colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            ZillitIcon(icon = ZillitIcons.ChevronRight, tint = colors.textMuted, size = 14.dp)
        }
        FieldError(error)
        if (form.audience.isSet) AudienceChip(form.audience, state)
    }
}

/** A label over its field, with a note and an error line. */
@Composable
private fun Labelled(
    label: String,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    note: String? = null,
    error: String? = null,
    field: @Composable () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldLabel(label, required = required, note = note)
        field()
        FieldError(error)
    }
}

private val ENTRY_WIDTH = 720.dp
private val TIME_WIDTH = 124.dp
private val TEXT_COLORS = listOf(
    "#000000", "#34495E", "#7F8C8D", "#C0392B", "#D35400", "#27AE60", "#2980B9", "#8E44AD", "#16A085", "#F39C12",
)
