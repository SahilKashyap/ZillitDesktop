package com.zillit.desktop.feature.home.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.config.CallLineNumbering
import com.zillit.desktop.core.config.EventCallLine
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitOptionPopup
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSelectTrigger
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.popupWidth
import com.zillit.desktop.core.designsystem.component.rememberZillitSelectAnchor
import com.zillit.desktop.core.designsystem.component.zillitSelectAnchor
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.locationpicker.PickedLocation
import com.zillit.desktop.core.locationpicker.ZillitLocationField
import com.zillit.desktop.core.locationpicker.oneLine
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.callLineLabel
import com.zillit.desktop.core.strings.str

/**
 * Creating and editing an event.
 *
 * A port of the web's `calendarV3` `EventForm`, field for field and rule for
 * rule — and now row for row: the fields sit in the web's own four-across
 * lines (date and times, repeat and zone and reminder, where and how and what
 * colour) rather than in stacked cards, so somebody who books events on the
 * web finds each control where their eye already goes.
 *
 * The location is picked on a map, as the web picks it, and its coordinates
 * travel with it (`AddCalendarEvent.jsx:433-438`) — see [MeetingRow].
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

/** The two buttons, pinned under the scrolling fields — the web's drawer footer. */
@Composable
private fun RowScope.FormActions(form: EventFormState, onEvent: (CalendarEvent2Event) -> Unit) {
    Spacer(Modifier.weight(1f))
    ZillitButton(
        text = str(S.cancel),
        variant = ButtonVariant.Tertiary,
        onClick = { onEvent(CalendarEvent2Event.CloseForm) },
    )
    ZillitButton(
        text = if (form.draft.isEdit) str(S.ce_update_event) else str(S.create_event),
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

    TitleRow(form, change)
    AudienceRow(form, change)
    WhenRow(form, change)
    RepeatRow(form, change)
    MeetingRow(form, change)

    if (draft.isForMembers) {
        InviteeList(form, change, loadAvatar)
        ExternalGuests(draft, change)

        ZillitCheckbox(
            checked = draft.excludeOrganiser,
            label = str(S.exclude_me),
            onCheckedChange = { change(draft.copy(excludeOrganiser = it)) },
        )

        form.errors.messageFor(EventFieldError.NoInvitees)?.let { ErrorLine(it) }
    }

    ZillitTextField(
        value = draft.description,
        onValueChange = { change(draft.copy(description = it)) },
        label = str(S.description),
        placeholder = str(S.description_hint),
        singleLine = false,
        modifier = Modifier.fillMaxWidth().heightIn(min = NOTES_HEIGHT),
    )
}

/** The name, and whether it takes the whole day — the web's top line. */
@Composable
private fun TitleRow(form: EventFormState, change: (EventDraft) -> Unit) {
    val draft = form.draft

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        ZillitTextField(
            value = draft.title,
            onValueChange = { change(draft.copy(title = it)) },
            placeholder = str(S.add_title),
            errorText = form.errors.messageFor(EventFieldError.TitleBlank)
                ?: form.errors.messageFor(EventFieldError.TitleTooShort),
            modifier = Modifier.weight(1f),
        )
        ZillitSwitch(
            checked = draft.isAllDay,
            onCheckedChange = { change(draft.copy(isAllDay = it)) },
            label = str(S.all_day),
        )
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

    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        modifier = Modifier.fillMaxWidth(),
    ) {
        EventAudience.entries.forEach { audience ->
            ZillitButton(
                text = str(audience.label),
                variant = chosen(audience == draft.audience),
                size = ButtonSize.Small,
                leadingIcon = if (audience == EventAudience.Members) ZillitIcons.Users else ZillitIcons.Edit,
                onClick = { change(draft.copy(audience = audience)) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * When it happens: start date, the two times, and the date it ends on.
 *
 * The end date is shown and not edited, as on the web: an event covers one day
 * unless its end time runs past midnight, and then it covers two. Showing the
 * second date makes a 22:00–04:00 night shoot legible instead of looking like
 * a typo that somehow saved.
 */
@Composable
private fun WhenRow(form: EventFormState, change: (EventDraft) -> Unit) {
    val draft = form.draft

    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        DatePickerField(
            value = draft.dateText,
            onValueChange = { change(draft.withDate(it)) },
            today = form.today,
            label = required(str(S.start_date)),
            errorText = form.errors.messageFor(EventFieldError.DateInvalid)
                ?: form.errors.messageFor(EventFieldError.DateInPast),
            modifier = Modifier.weight(1f),
            // A new event cannot be put in the past, so the grid does not offer
            // it. An edit can: last week's call sheet still needs its typos fixed,
            // which is the same line `validate` draws.
            minDate = form.today.takeUnless { draft.isEdit },
        )

        if (!draft.isAllDay) {
            TimePickerField(
                value = draft.startText,
                onValueChange = { change(draft.withStartTime(it)) },
                label = required(str(S.start_time)),
                placeholder = "09:00",
                errorText = form.errors.messageFor(EventFieldError.StartTimeInvalid)
                    ?: form.errors.messageFor(EventFieldError.TooShort),
                modifier = Modifier.weight(1f),
            )
            TimePickerField(
                value = draft.endText,
                onValueChange = { change(draft.copy(endText = it)) },
                label = required(str(S.end_time)),
                placeholder = "17:30",
                errorText = form.errors.messageFor(EventFieldError.EndTimeInvalid),
                modifier = Modifier.weight(1f),
            )
        }

        // Shown, never typed into: it is a reading of the other three fields.
        ZillitTextField(
            value = draft.endDateText,
            onValueChange = {},
            label = str(S.end_date),
            enabled = false,
            readOnly = true,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * How often, in which zone, and how long before.
 *
 * The web's second line. The weekday row, the end of the repeat and its length
 * follow underneath, and only once a repeat has been chosen — a fixed block of
 * repeat controls above every one-off event is furniture, and most events are
 * one-offs.
 */
@Composable
private fun RepeatRow(form: EventFormState, change: (EventDraft) -> Unit) {
    val draft = form.draft
    val rule = draft.recurrence

    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        LabelledField(str(S.repeat), Modifier.weight(1f)) {
            ZillitSelect(
                value = rule.frequency,
                options = RecurrenceFrequency.entries,
                // Choosing a repeat fills in where it stops, rather than
                // leaving a required field blank to be discovered on save.
                onSelect = { change(draft.repeating(it)) },
                label = { str(it.label) },
                showInitials = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        TimezoneField(form, change, Modifier.weight(1f))
        LabelledField(
            label = str(S.reminder),
            modifier = Modifier.weight(1f),
            error = form.errors.messageFor(EventFieldError.ReminderPassed),
        ) {
            ZillitSelect(
                value = draft.reminderMinutes,
                options = REMINDER_CHOICES,
                onSelect = { change(draft.copy(reminderMinutes = it)) },
                label = { reminderLabel(it) },
                showInitials = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (rule.needsWeekdays) {
        LabelledField(str(S.txt_select_days)) {
            WeekdayPicker(rule) { change(draft.copy(recurrence = it)) }
        }
        form.recurrenceErrors.messageFor(RecurrenceError.NoWeekdays)?.let { ErrorLine(it) }
    }

    if (rule.repeats) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            DatePickerField(
                value = rule.endDateText,
                onValueChange = { change(draft.copy(recurrence = rule.copy(endDateText = it))) },
                today = form.today,
                label = required(str(S.repeat_until)),
                placeholder = "2026-12-31",
                errorText = form.recurrenceErrors.messageFor(RecurrenceError.NoEndDate)
                    ?: form.recurrenceErrors.messageFor(RecurrenceError.EndBeforeStart),
                modifier = Modifier.weight(REPEAT_UNTIL_SHARE),
            )
            RepeatCountField(form, change, Modifier.weight(1f))
        }
    }
}

/**
 * How long the repeat runs, in its own unit.
 *
 * The web's count box beside the end date: the same fact said the other way
 * round, because "every week for six weeks" is how a schedule is spoken and
 * "until 30 November" is how it is checked. Typing in either moves the other.
 */
@Composable
private fun RepeatCountField(
    form: EventFormState,
    change: (EventDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = form.draft
    val unit = draft.recurrence.frequency.unit ?: return
    val counted = draft.recurrence.repeatCount(draft.dateText)
    val typed = remember { mutableStateOf(counted?.toString().orEmpty()) }

    // A count the user did not type — the end date moved, the frequency
    // changed, the event was dragged to another day — shows through here.
    // One being typed is left alone, or every second keystroke would be
    // rewritten by the date it had just produced.
    if (counted != null && typed.value.toIntOrNull() != counted) typed.value = counted.toString()

    ZillitTextField(
        value = typed.value,
        onValueChange = { text ->
            val digits = text.filter { it.isDigit() }.take(COUNT_DIGITS)
            typed.value = digits
            digits.toIntOrNull()?.let {
                change(draft.copy(recurrence = draft.recurrence.withRepeatCount(it, draft.dateText)))
            }
        },
        label = required(str(unit.label)),
        keyboardType = KeyboardType.Number,
        modifier = modifier,
    )
}

/**
 * Where, how, and in what colour.
 *
 * The call type belongs to a members event; a personal one is a note to
 * yourself and has nobody to call. The location is asked for — marked
 * required, as the web marks it — only when some of the attendees are
 * physically travelling to it, the web's `meet_in_person_call`.
 */
@Composable
private fun MeetingRow(form: EventFormState, change: (EventDraft) -> Unit) {
    val draft = form.draft

    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        // Picked on a map, as the web picks it, and the point travels with the
        // words: `eventBody` sends `location: {lat, long}` beside
        // `location_description`, the shape `AddCalendarEvent.jsx:433-438` sends.
        ZillitLocationField(
            text = draft.location,
            // Typing replaces a picked place, so its coordinates go with it.
            onTextChange = { change(draft.copy(location = it, locationLat = null, locationLng = null)) },
            onPicked = { change(draft.copy(location = it.oneLine(), locationLat = it.lat, locationLng = it.lng)) },
            label = if (draft.callType?.needsLocation == true) required(str(S.location)) else str(S.location),
            placeholder = str(S.desktop_select_location),
            errorText = form.errors.messageFor(EventFieldError.LocationMissing),
            // The event's own place, when it has one. Without this the picker gets
            // nothing and now opens on the user's own position — one click from
            // overwriting the address of an event being edited.
            initial = draft.pickedLocation(),
            modifier = Modifier.weight(1f),
        )

        if (draft.isForMembers) {
            LabelledField(
                label = required(str(S.call_type)),
                modifier = Modifier.weight(1f),
                error = form.errors.messageFor(EventFieldError.CallTypeMissing),
            ) {
                ZillitSelect<CallType?>(
                    value = draft.callType,
                    options = CallType.entries,
                    onSelect = { change(draft.copy(callType = it)) },
                    // Nothing chosen reads as the field's own name, the way the
                    // web's `placeholder="Call Type"` does.
                    label = { it?.let { type -> str(type.label) } ?: str(S.call_type) },
                    isError = EventFieldError.CallTypeMissing in form.errors,
                    showInitials = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // The same condition the body sends `line` under — never one without the other.
            if (draft.carriesLine) CallLineField(draft, change, Modifier.weight(1f))
        }

        ColorField(draft, change, Modifier.weight(1f))
    }
}

/** What the draft already holds as a place to reopen the map on, or null. */
private fun EventDraft.pickedLocation(): PickedLocation? {
    val lat = locationLat ?: return null
    val lng = locationLng ?: return null
    return PickedLocation(name = location, address = location, lat = lat, lng = lng)
}

/**
 * Which line the event's call runs on: Line 1 is a LiveKit meet with a lobby,
 * Line 2 the CnC room. Numbered as `calling_lines` numbers those engines, like
 * every other call menu.
 */
@Composable
private fun CallLineField(
    draft: EventDraft,
    change: (EventDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    LabelledField(str(S.desktop_call_line_picker), modifier) {
        ZillitSelect(
            value = draft.line,
            options = EventCallLine.entries,
            onSelect = { change(draft.copy(line = it)) },
            label = { callLineLabel(CallLineNumbering.numberOf(it.engine)) },
            showInitials = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The event's colour: the web's twelve presets, named by their hex.
 *
 * A dropdown rather than a row of swatches, because the row shared the line
 * with three other controls and a dozen circles would have owned it.
 */
@Composable
private fun ColorField(
    draft: EventDraft,
    change: (EventDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    LabelledField(str(S.color), modifier) {
        ZillitSelect(
            value = draft.colorHex.ifBlank { DEFAULT_EVENT_COLOR },
            options = EVENT_PALETTE,
            onSelect = { change(draft.copy(colorHex = it)) },
            label = { it },
            showInitials = false,
            searchable = false,
            optionLeading = { Swatch(it) },
            fieldLeading = { Swatch(it) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Swatch(hex: String) {
    Box(
        modifier = Modifier
            .size(SWATCH)
            .clip(ZillitTheme.shapes.small)
            .background(parseEventColor(hex) ?: ZillitTheme.colors.accent)
            .border(HAIRLINE, ZillitTheme.colors.border, ZillitTheme.shapes.small),
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

/**
 * Which zone the typed times are meant in — the web's `SelectTimezone`, as a
 * searchable dropdown. Disabled for all-day events: a whole day is a whole day.
 */
@Composable
private fun TimezoneField(
    form: EventFormState,
    change: (EventDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = form.draft
    val open = remember { mutableStateOf(false) }
    val anchor = rememberZillitSelectAnchor()

    val chosenZone = form.timezones.firstOrNull { it.identifier == draft.timezoneId }
    val deviceZone = str(S.desktop_cal_device_timezone)
    // A whole day has no clock to reinterpret, so the field stays but is shut.
    val enabled = form.timezones.isNotEmpty() && !draft.isAllDay

    LabelledField(str(S.timezone), modifier) {
        Box(Modifier.fillMaxWidth().zillitSelectAnchor(anchor)) {
            ZillitSelectTrigger(
                open = open.value,
                enabled = enabled,
                onClick = { open.value = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                ZillitText(
                    text = chosenZone?.label ?: deviceZone,
                    style = ZillitTheme.typography.bodyMedium,
                    color = if (enabled) ZillitTheme.colors.textPrimary else ZillitTheme.colors.textDisabled,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }

            if (open.value) {
                // null is the device's own zone, stored as "".
                ZillitOptionPopup(
                    onDismiss = { open.value = false },
                    width = anchor.popupWidth(TIMEZONE_MENU_WIDTH),
                    options = listOf<TimezoneOption?>(null) + form.timezones,
                    isSelected = { zone ->
                        if (zone == null) draft.timezoneId.isBlank() else zone.identifier == draft.timezoneId
                    },
                    onPick = { option ->
                        // The moment is kept and the clock moves, as on the
                        // web — switching zone must not silently reschedule.
                        change(draft.inTimezone(option?.identifier ?: "", form.zone))
                        open.value = false
                    },
                    label = { it?.label ?: deviceZone },
                    searchable = true,
                    searchPlaceholder = str(S.desktop_cal_search_timezones),
                    searchText = { it?.let { zone -> "${zone.label} ${zone.identifier}" } ?: deviceZone },
                    // "Device timezone" stays on top whatever is typed, as it always did.
                    keepOnSearch = { it == null },
                    showInitials = false,
                )
            }
        }
    }
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

    // The web's one label, whatever is chosen: the chips below it already say
    // who is coming, and a label that counts them says it twice.
    LabelledField(str(S.invitees)) {
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

    LabelledField(str(S.external_guests)) {
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
                text = str(S.ce_add_external_guests),
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

/**
 * A field that is not a text field, wearing a text field's label.
 *
 * The selects and pickers carry no label of their own, and a row of controls
 * where two are titled and two are not reads as a layout accident.
 */
@Composable
private fun LabelledField(
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
        content()
        error?.let { ErrorLine(it) }
    }
}

@Composable
private fun ErrorLine(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.danger,
    )
}

/** The web's red `*` on a label the server will not do without. */
private fun required(label: String): String = "$label *"

/**
 * The chip style for a choice that is on.
 *
 * Filled for the chosen one and outlined for the rest, the way the web's
 * `buttonStyle="solid"` radio group reads: two outlined chips side by side say
 * nothing about which of them is in force.
 */
private fun chosen(selected: Boolean): ButtonVariant =
    if (selected) ButtonVariant.Primary else ButtonVariant.Secondary

private fun Set<EventFieldError>.messageFor(error: EventFieldError): String? =
    if (error in this) error.message else null

private fun Set<RecurrenceError>.messageFor(error: RecurrenceError): String? =
    if (error in this) error.message else null

/** The web's `NOTIFY_OPTIONS`. */
private val REMINDER_CHOICES = listOf(0, 5, 10, 15, 30, 60)

/** The web's three-to-one split between the repeat's end date and its length. */
private const val REPEAT_UNTIL_SHARE = 3f

/** Enough for 365, the longest repeat any unit allows. */
private const val COUNT_DIGITS = 3

private val FORM_WIDTH = 840.dp
private val FORM_MAX_HEIGHT = 700.dp
private val NOTES_HEIGHT = 72.dp

private val SWATCH = 18.dp
private val HAIRLINE = 1.dp
private val TIMEZONE_MENU_WIDTH = 340.dp
