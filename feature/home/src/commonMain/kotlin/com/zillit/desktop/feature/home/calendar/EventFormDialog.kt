package com.zillit.desktop.feature.home.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.rememberWheelScroll
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.locationpicker.PickedLocation
import com.zillit.desktop.core.locationpicker.oneLine
import com.zillit.desktop.core.locationpicker.ZillitLocationField
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Creating and editing an event.
 *
 * A port of the web's `calendarV3` `EventForm`, field for field and rule for
 * rule. The location is picked on a map, as the web picks it, and its
 * coordinates travel with it (`AddCalendarEvent.jsx:433-438`) — see
 * [MeetingSection].
 */
@Composable
internal fun EventFormDialog(
    form: EventFormState?,
    onEvent: (CalendarEvent2Event) -> Unit,
    modifier: Modifier = Modifier,
    loadAvatar: suspend (String) -> ByteArray? = { null },
) {
    // The last opened form survives the exit animation — the state goes null
    // on close, but the fields must not blank while fading out.
    val shown = remember { mutableStateOf<EventFormState?>(null) }
    if (form != null) shown.value = form
    val current = shown.value

    // The shell scrolls the body and pins the buttons beneath it. The fields
    // used to sit in their own `weight + verticalScroll` column inside that
    // body — a scroll nested in a scroll under unbounded height, which
    // measured to nothing: the dialog opened as a title over two buttons and
    // no event could be made (seen live 2026-08-17).
    ZillitDialogShell(
        title = current?.draft?.formTitle ?: str(S.ce_event_tab),
        icon = ZillitIcons.Calendar,
        visible = form != null,
        onDismiss = { onEvent(CalendarEvent2Event.CloseForm) },
        width = FORM_WIDTH,
        maxHeight = FORM_MAX_HEIGHT,
        modifier = modifier,
        actions = { current?.let { FormActions(it, onEvent) } },
    ) {
        if (current != null) {
            FormFields(current, onEvent, loadAvatar)
            current.error?.let { ErrorLine(it) }
        }
    }
}

/** The two buttons, pinned under the scrolling fields. */
@Composable
private fun RowScope.FormActions(form: EventFormState, onEvent: (CalendarEvent2Event) -> Unit) {
    Spacer(Modifier.weight(1f))
    ZillitButton(
        text = str(S.cancel),
        variant = ButtonVariant.Tertiary,
        onClick = { onEvent(CalendarEvent2Event.CloseForm) },
    )
    ZillitButton(
        text = if (form.draft.isEdit) str(S.save) else str(S.create),
        loading = form.isSaving,
        onClick = { onEvent(CalendarEvent2Event.SaveForm) },
    )
}

@Composable
private fun FormFields(
    form: EventFormState,
    onEvent: (CalendarEvent2Event) -> Unit,
    loadAvatar: suspend (String) -> ByteArray?,
) {
    val draft = form.draft
    val change = { updated: EventDraft -> onEvent(CalendarEvent2Event.FormChanged(updated)) }

    ZillitTextField(
        value = draft.title,
        onValueChange = { change(draft.copy(title = it)) },
        label = str(S.title),
        placeholder = str(S.event_name),
        errorText = form.errors.messageFor(EventFieldError.TitleBlank)
            ?: form.errors.messageFor(EventFieldError.TitleTooShort),
        modifier = Modifier.fillMaxWidth(),
    )

    AudienceRow(form, change)
    ColorRow(draft, change)

    FormSection(icon = ZillitIcons.Clock, title = str(S.section_when)) {
        WhenSection(form, change)
        TimezoneRow(form, change)
    }

    FormSection(
        icon = if (draft.isForMembers) ZillitIcons.Phone else ZillitIcons.Home,
        title = if (draft.isForMembers) str(S.desktop_cal_section_meeting) else str(S.desktop_cal_section_where),
    ) {
        MeetingSection(form, change)
    }

    FormSection(icon = ZillitIcons.Reload, title = str(S.repeat)) {
        RecurrenceSection(form, change)
    }

    FormSection(icon = ZillitIcons.Bell, title = str(S.reminder)) {
        ReminderRow(form, change)
    }

    if (draft.isForMembers) {
        FormSection(icon = ZillitIcons.Users, title = str(S.section_people)) {
            GuestSection(form, change, loadAvatar)
        }
    }

    ZillitTextField(
        value = draft.description,
        onValueChange = { change(draft.copy(description = it)) },
        label = str(S.description),
        placeholder = str(S.desktop_cal_notes_or_agenda),
        singleLine = false,
        modifier = Modifier.fillMaxWidth().heightIn(min = NOTES_HEIGHT),
    )
}

/**
 * A titled group of fields on a sunken card.
 *
 * The form asks a dozen questions; ungrouped they read as one long
 * interrogation. Each card is one topic — when, where, how often — so the eye
 * can skip whole cards that don't apply, the way the web's form separates its
 * panels.
 */
@Composable
private fun FormSection(
    icon: ImageVector,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitIcon(icon, tint = ZillitTheme.colors.accentText, size = SECTION_ICON)
            ZillitText(
                text = title,
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        content()
    }
}

/**
 * Who the event is for.
 *
 * Offered when creating only, exactly as the web hides the choice on an edit:
 * a personal event turned into a members one part-way through its life leaves
 * the people newly on it with no idea where it came from, and the server
 * treats the two as different things.
 */
@Composable
private fun AudienceRow(form: EventFormState, change: (EventDraft) -> Unit) {
    val draft = form.draft
    if (draft.isEdit) return

    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        EventAudience.entries.forEach { audience ->
            ZillitButton(
                text = str(audience.label),
                variant = chosen(audience == draft.audience),
                size = ButtonSize.Small,
                onClick = { change(draft.copy(audience = audience)) },
            )
        }
    }
}

/**
 * When it happens.
 *
 * The end date is shown and not edited, as on the web: an event covers one day
 * unless its end time runs past midnight, and then it covers two. Showing the
 * second date makes a 22:00–04:00 night shoot legible instead of looking like
 * a typo that somehow saved.
 */
@Composable
private fun WhenSection(form: EventFormState, change: (EventDraft) -> Unit) {
    val draft = form.draft

    DatePickerField(
        value = draft.dateText,
        onValueChange = { change(draft.withDate(it)) },
        today = form.today,
        label = str(S.start_date),
        errorText = form.errors.messageFor(EventFieldError.DateInvalid)
            ?: form.errors.messageFor(EventFieldError.DateInPast),
        modifier = Modifier.fillMaxWidth(),
    )

    ZillitCheckbox(
        checked = draft.isAllDay,
        label = str(S.all_day),
        onCheckedChange = { change(draft.copy(isAllDay = it)) },
    )

    if (!draft.isAllDay) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            TimePickerField(
                value = draft.startText,
                onValueChange = { change(draft.withStartTime(it)) },
                label = str(S.start_time),
                placeholder = "09:00",
                errorText = form.errors.messageFor(EventFieldError.StartTimeInvalid)
                    ?: form.errors.messageFor(EventFieldError.TooShort),
                modifier = Modifier.weight(1f),
            )
            TimePickerField(
                value = draft.endText,
                onValueChange = { change(draft.copy(endText = it)) },
                label = str(S.end_time),
                placeholder = "17:30",
                errorText = form.errors.messageFor(EventFieldError.EndTimeInvalid),
                modifier = Modifier.weight(1f),
            )
        }

        draft.endDateText.takeIf { it.isNotBlank() }?.let { ends ->
            ZillitText(
                text = if (draft.isOvernight) {
                    str(S.desktop_cal_ends_next_day, ends)
                } else {
                    str(S.desktop_cal_ends_at, ends)
                },
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

/**
 * How people are meeting, and where.
 *
 * Both belong to a members event; a personal one is a note to yourself and has
 * nobody to call. The location is asked for only when some of the attendees
 * are physically travelling to it — the web's `meet_in_person_call`.
 */
@Composable
private fun MeetingSection(form: EventFormState, change: (EventDraft) -> Unit) {
    val draft = form.draft

    if (draft.isForMembers) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            FieldLabel(str(S.call_type))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
            ) {
                CallType.entries.forEach { callType ->
                    ZillitButton(
                        text = str(callType.label),
                        variant = chosen(callType == draft.callType),
                        size = ButtonSize.Small,
                        onClick = { change(draft.copy(callType = callType)) },
                    )
                }
            }
            form.errors.messageFor(EventFieldError.CallTypeMissing)?.let { ErrorLine(it) }
        }
    }

    // Picked on a map, as the web picks it, and the point travels with the
    // words: `eventBody` sends `location: {lat, long}` beside
    // `location_description`, the shape `AddCalendarEvent.jsx:433-438` sends.
    ZillitLocationField(
        text = draft.location,
        // Typing replaces a picked place, so its coordinates go with it.
        onTextChange = { change(draft.copy(location = it, locationLat = null, locationLng = null)) },
        onPicked = { change(draft.copy(location = it.oneLine(), locationLat = it.lat, locationLng = it.lng)) },
        label = if (draft.callType?.needsLocation == true) str(S.desktop_cal_location_required) else str(S.location),
        placeholder = str(S.desktop_cal_where_to_meet),
        errorText = form.errors.messageFor(EventFieldError.LocationMissing),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * How often it repeats.
 *
 * The weekday row and the end date appear only once a repeat is chosen — a
 * fixed block of repeat controls above every one-off event is furniture, and
 * most events are one-offs.
 */
@Composable
private fun RecurrenceSection(form: EventFormState, change: (EventDraft) -> Unit) {
    val draft = form.draft
    val rule = draft.recurrence

    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            RecurrenceFrequency.entries.forEach { frequency ->
                ZillitButton(
                    text = str(frequency.label),
                    variant = chosen(frequency == rule.frequency),
                    size = ButtonSize.Small,
                    // Choosing a repeat fills in where it stops, rather than
                    // leaving a required field blank to be discovered on save.
                    onClick = { change(draft.repeating(frequency)) },
                )
            }
        }

        if (rule.needsWeekdays) {
            WeekdayPicker(rule) { change(draft.copy(recurrence = it)) }
            form.recurrenceErrors.messageFor(RecurrenceError.NoWeekdays)?.let { ErrorLine(it) }
        }

        if (rule.repeats) {
            DatePickerField(
                value = rule.endDateText,
                onValueChange = { change(draft.copy(recurrence = rule.copy(endDateText = it))) },
                today = form.today,
                label = str(S.repeat_until),
                placeholder = "2026-12-31",
                errorText = form.recurrenceErrors.messageFor(RecurrenceError.NoEndDate)
                    ?: form.recurrenceErrors.messageFor(RecurrenceError.EndBeforeStart),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Picks a frequency and moves the end of the repeat to suit it.
 *
 * The web's `RecurrenceSelect`: switching to weekly means "for a week from
 * here" unless the user says otherwise.
 */
private fun EventDraft.repeating(frequency: RecurrenceFrequency): EventDraft {
    val start = dateText.trim().toLocalDateOrNull()
    val end = start?.let { defaultRecurrenceEnd(frequency, it) }
    return copy(
        recurrence = recurrence.copy(
            frequency = frequency,
            endDateText = end?.isoText() ?: recurrence.endDateText,
        ),
    )
}

/** Which days a custom repeat lands on. Sunday first, as the server stores them. */
@Composable
private fun WeekdayPicker(rule: Recurrence, change: (Recurrence) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        WEEKDAY_LABELS.forEachIndexed { index, label ->
            ZillitButton(
                text = str(label),
                variant = chosen(index in rule.selectedDays),
                size = ButtonSize.Small,
                onClick = {
                    change(
                        rule.copy(
                            selectedDays = if (index in rule.selectedDays) {
                                rule.selectedDays - index
                            } else {
                                rule.selectedDays + index
                            },
                        ),
                    )
                },
            )
        }
    }
}

/**
 * Which zone the typed times are meant in — the web's `SelectTimezone`, as a
 * searchable dropdown. Hidden for all-day events: a whole day is a whole day.
 */
@Composable
private fun TimezoneRow(form: EventFormState, change: (EventDraft) -> Unit) {
    val draft = form.draft
    // A whole day is a whole day — no clock to reinterpret.
    if (draft.isAllDay) return
    val open = remember { mutableStateOf(false) }
    val search = remember { mutableStateOf("") }

    val chosenZone = form.timezones.firstOrNull { it.identifier == draft.timezoneId }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = str(S.timezone),
            style = ZillitTheme.typography.label,
            color = ZillitTheme.colors.textSecondary,
        )
        Box {
            ZillitButton(
                text = chosenZone?.label ?: str(S.desktop_cal_device_timezone),
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                onClick = { open.value = true; search.value = "" },
                enabled = form.timezones.isNotEmpty(),
            )
            DropdownMenu(expanded = open.value, onDismissRequest = { open.value = false }) {
                TimezoneMenu(
                    form = form,
                    search = search.value,
                    onSearch = { search.value = it },
                    onPick = { id ->
                        // The moment is kept and the clock moves, as on the
                        // web — switching zone must not silently reschedule.
                        change(draft.inTimezone(id, form.zone))
                        open.value = false
                    },
                )
            }
        }
    }
}

/** The searchable list inside the dropdown; picking "" means the device zone. */
@Composable
private fun TimezoneMenu(
    form: EventFormState,
    search: String,
    onSearch: (String) -> Unit,
    onPick: (String) -> Unit,
) {
    Column(
        modifier = Modifier.width(TIMEZONE_MENU_WIDTH).padding(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitTextField(
            value = search,
            onValueChange = onSearch,
            placeholder = str(S.desktop_cal_search_timezones),
            modifier = Modifier.fillMaxWidth(),
        )
        val matches = form.timezones.filter {
            search.isBlank() || it.label.contains(search, ignoreCase = true) ||
                it.identifier.contains(search, ignoreCase = true)
        }
        val zoneState = rememberLazyListState()
        // Fixed, not heightIn: the menu asks its content for intrinsic
        // measurements, which a lazy list cannot answer — opening the
        // dropdown died on exactly that before the height was pinned.
        LazyColumn(
            state = zoneState,
            modifier = Modifier
                .height(TIMEZONE_LIST_HEIGHT)
                .then(rememberWheelScroll(zoneState)),
        ) {
            item(key = "device") {
                TimezoneChoice(str(S.desktop_cal_device_timezone), form.draft.timezoneId.isBlank()) { onPick("") }
            }
            items(matches, key = TimezoneOption::identifier) { option ->
                TimezoneChoice(
                    label = option.label,
                    selected = option.identifier == form.draft.timezoneId,
                ) { onPick(option.identifier) }
            }
        }
    }
}

@Composable
private fun TimezoneChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    ZillitText(
        text = label,
        style = ZillitTheme.typography.bodySmall,
        color = if (selected) ZillitTheme.colors.accent else ZillitTheme.colors.textPrimary,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.xs, vertical = ZillitTheme.spacing.xxs),
    )
}

/** The event's colour: a swatch per palette entry, the chosen one ringed. */
@Composable
private fun ColorRow(draft: EventDraft, change: (EventDraft) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = str(S.av_color),
            style = ZillitTheme.typography.label,
            color = ZillitTheme.colors.textSecondary,
        )
        EVENT_PALETTE.forEach { hex ->
            val swatch = parseEventColor(hex) ?: ZillitTheme.colors.accent
            val selected = draft.colorHex == hex
            Box(
                modifier = Modifier
                    .size(SWATCH)
                    .clip(CircleShape)
                    .background(swatch)
                    .border(
                        width = if (selected) SWATCH_RING else 0.dp,
                        color = ZillitTheme.colors.textPrimary,
                        shape = CircleShape,
                    )
                    .clickable { change(draft.copy(colorHex = hex)) },
            )
        }
    }
}

@Composable
private fun ReminderRow(form: EventFormState, change: (EventDraft) -> Unit) {
    val draft = form.draft

    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        // Wraps: a Row squeezed the last chip into a one-letter-wide column.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            REMINDER_CHOICES.forEach { minutes ->
                ZillitButton(
                    text = reminderLabel(minutes),
                    variant = chosen(minutes == draft.reminderMinutes),
                    size = ButtonSize.Small,
                    onClick = { change(draft.copy(reminderMinutes = minutes)) },
                )
            }
        }
        form.errors.messageFor(EventFieldError.ReminderPassed)?.let { ErrorLine(it) }
    }
}

/**
 * Who is coming.
 *
 * Only a members event has guests. The section disappears for a personal one
 * rather than greying out, so switching to "Personal" reads as "this is just
 * mine" instead of "these controls broke".
 */
@Composable
private fun GuestSection(
    form: EventFormState,
    change: (EventDraft) -> Unit,
    loadAvatar: suspend (String) -> ByteArray?,
) {
    val draft = form.draft
    if (!draft.isForMembers) return

    InviteeList(form, change, loadAvatar)
    ExternalGuests(draft, change)

    ZillitCheckbox(
        checked = draft.excludeOrganiser,
        label = "I will not be part of this event",
        onCheckedChange = { change(draft.copy(excludeOrganiser = it)) },
    )

    form.errors.messageFor(EventFieldError.NoInvitees)?.let { ErrorLine(it) }
}

/**
 * Who to invite, from the production's crew.
 *
 * The list the session already holds, so this needs no request — and works
 * offline for the people most events go to. The picking itself lives in
 * [InviteePicker]: a searchable dropdown of faces and designations, with the
 * chosen crew as removable chips.
 */
@Composable
private fun InviteeList(
    form: EventFormState,
    change: (EventDraft) -> Unit,
    loadAvatar: suspend (String) -> ByteArray?,
) {
    if (form.invitees.isEmpty()) return
    val draft = form.draft

    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        FieldLabel(
            if (draft.inviteeIds.isEmpty()) {
                str(S.desktop_cal_invite_crew)
            } else {
                str(S.desktop_cal_invite_crew_selected, draft.inviteeIds.size)
            },
        )

        InviteePicker(
            invitees = form.invitees,
            selectedIds = draft.inviteeIds,
            onToggle = { userId ->
                change(
                    draft.copy(
                        inviteeIds = if (userId in draft.inviteeIds) {
                            draft.inviteeIds - userId
                        } else {
                            draft.inviteeIds + userId
                        },
                    ),
                )
            },
            loadAvatar = loadAvatar,
        )
    }
}

/**
 * People outside the production, invited by address.
 *
 * The web opens a modal that also searches previously invited outsiders; this
 * is the part of it that does not need a request — type an address, add it,
 * remove it again. Bad addresses and repeats are refused here rather than
 * accepted and dropped by the server, where nobody would see it happen.
 */
@Composable
private fun ExternalGuests(draft: EventDraft, change: (EventDraft) -> Unit) {
    val typed = remember { mutableStateOf("") }
    val problem = remember { mutableStateOf<String?>(null) }

    val add = {
        when (val outcome = draft.addingGuest(typed.value)) {
            GuestAddition.Empty -> Unit
            is GuestAddition.Refused -> problem.value = outcome.reason
            is GuestAddition.Added -> {
                change(outcome.draft)
                typed.value = ""
                problem.value = null
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        FieldLabel(str(S.external_guests))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitTextField(
                value = typed.value,
                onValueChange = { typed.value = it; problem.value = null },
                placeholder = "name@example.com",
                errorText = problem.value,
                onImeAction = add,
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = str(S.add),
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = typed.value.isNotBlank(),
                onClick = add,
            )
        }

        draft.externalEmails.forEach { address ->
            GuestRow(address) {
                change(draft.copy(externalEmails = draft.externalEmails - address))
            }
        }
    }
}

@Composable
private fun GuestRow(address: String, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        ZillitText(
            text = address,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        ZillitButton(
            text = str(S.remove),
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            onClick = onRemove,
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.textMuted,
    )
}

@Composable
private fun ErrorLine(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.danger,
    )
}

/** The one line a picked place reads as: its name, then its address. */

/** The chip style for a choice that is on. */
private fun chosen(selected: Boolean): ButtonVariant =
    if (selected) ButtonVariant.Secondary else ButtonVariant.Tertiary

private fun Set<EventFieldError>.messageFor(error: EventFieldError): String? =
    if (error in this) error.message else null

private fun Set<RecurrenceError>.messageFor(error: RecurrenceError): String? =
    if (error in this) error.message else null

/** The web's `NOTIFY_OPTIONS`. */
private val REMINDER_CHOICES = listOf(0, 5, 10, 15, 30, 60)

private val FORM_WIDTH = 520.dp
private val FORM_MAX_HEIGHT = 680.dp
private val NOTES_HEIGHT = 72.dp

private val SWATCH = 22.dp
private val SECTION_ICON = 14.dp
private val TIMEZONE_MENU_WIDTH = 340.dp
private val TIMEZONE_LIST_HEIGHT = 260.dp
private val SWATCH_RING = 2.dp
