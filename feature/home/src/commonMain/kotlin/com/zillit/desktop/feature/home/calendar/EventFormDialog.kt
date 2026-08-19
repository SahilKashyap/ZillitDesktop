package com.zillit.desktop.feature.home.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.rememberWheelScroll
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/**
 * Creating and editing an event.
 *
 * A port of the web's `calendarV3` `EventForm`, field for field and rule for
 * rule. The one thing not carried over is the map: the web picks a location on
 * a map and sends coordinates alongside the description, and this sends the
 * description alone until the calendar has a map picker of its own.
 */
@Composable
internal fun EventFormDialog(
    form: EventFormState?,
    onEvent: (CalendarEvent2Event) -> Unit,
    modifier: Modifier = Modifier,
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
        title = current?.draft?.formTitle ?: "Event",
        icon = ZillitIcons.Calendar,
        visible = form != null,
        onDismiss = { onEvent(CalendarEvent2Event.CloseForm) },
        width = FORM_WIDTH,
        maxHeight = FORM_MAX_HEIGHT,
        modifier = modifier,
        actions = { current?.let { FormActions(it, onEvent) } },
    ) {
        if (current != null) {
            FormFields(current, onEvent)
            current.error?.let { ErrorLine(it) }
        }
    }
}

/** The two buttons, pinned under the scrolling fields. */
@Composable
private fun RowScope.FormActions(form: EventFormState, onEvent: (CalendarEvent2Event) -> Unit) {
    Spacer(Modifier.weight(1f))
    ZillitButton(
        text = "Cancel",
        variant = ButtonVariant.Tertiary,
        onClick = { onEvent(CalendarEvent2Event.CloseForm) },
    )
    ZillitButton(
        text = if (form.draft.isEdit) "Save" else "Create",
        loading = form.isSaving,
        onClick = { onEvent(CalendarEvent2Event.SaveForm) },
    )
}

@Composable
private fun FormFields(form: EventFormState, onEvent: (CalendarEvent2Event) -> Unit) {
    val draft = form.draft
    val change = { updated: EventDraft -> onEvent(CalendarEvent2Event.FormChanged(updated)) }

    ZillitTextField(
        value = draft.title,
        onValueChange = { change(draft.copy(title = it)) },
        label = "Title",
        placeholder = "Event name",
        errorText = form.errors.messageFor(EventFieldError.TitleBlank)
            ?: form.errors.messageFor(EventFieldError.TitleTooShort),
        modifier = Modifier.fillMaxWidth(),
    )

    AudienceRow(form, change)
    WhenSection(form, change)
    MeetingSection(form, change)
    TimezoneRow(form, change)
    ColorRow(draft, change)
    ReminderRow(form, change)
    RecurrenceSection(form, change)
    GuestSection(form, change)

    ZillitTextField(
        value = draft.description,
        onValueChange = { change(draft.copy(description = it)) },
        label = "Description",
        placeholder = "Notes or agenda",
        singleLine = false,
        modifier = Modifier.fillMaxWidth().heightIn(min = NOTES_HEIGHT),
    )
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
                text = audience.label,
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
        label = "Start date",
        errorText = form.errors.messageFor(EventFieldError.DateInvalid)
            ?: form.errors.messageFor(EventFieldError.DateInPast),
        modifier = Modifier.fillMaxWidth(),
    )

    ZillitCheckbox(
        checked = draft.isAllDay,
        label = "All day",
        onCheckedChange = { change(draft.copy(isAllDay = it)) },
    )

    if (!draft.isAllDay) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = draft.startText,
                onValueChange = { change(draft.withStartTime(it)) },
                label = "Start time",
                placeholder = "09:00",
                errorText = form.errors.messageFor(EventFieldError.StartTimeInvalid)
                    ?: form.errors.messageFor(EventFieldError.TooShort),
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = draft.endText,
                onValueChange = { change(draft.copy(endText = it)) },
                label = "End time",
                placeholder = "17:30",
                errorText = form.errors.messageFor(EventFieldError.EndTimeInvalid),
                modifier = Modifier.weight(1f),
            )
        }

        draft.endDateText.takeIf { it.isNotBlank() }?.let { ends ->
            ZillitText(
                text = if (draft.isOvernight) "Ends the next day, $ends" else "Ends $ends",
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
            FieldLabel("Call type")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
            ) {
                CallType.entries.forEach { callType ->
                    ZillitButton(
                        text = callType.label,
                        variant = chosen(callType == draft.callType),
                        size = ButtonSize.Small,
                        onClick = { change(draft.copy(callType = callType)) },
                    )
                }
            }
            form.errors.messageFor(EventFieldError.CallTypeMissing)?.let { ErrorLine(it) }
        }
    }

    ZillitTextField(
        value = draft.location,
        onValueChange = { change(draft.copy(location = it)) },
        label = if (draft.callType?.needsLocation == true) "Location — required" else "Location",
        placeholder = "Where to meet",
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
        FieldLabel("Repeat")

        FlowRow(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            RecurrenceFrequency.entries.forEach { frequency ->
                ZillitButton(
                    text = frequency.label,
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
                label = "Repeat until",
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
                text = label,
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
            text = "Timezone",
            style = ZillitTheme.typography.label,
            color = ZillitTheme.colors.textSecondary,
        )
        Box {
            ZillitButton(
                text = chosenZone?.label ?: "Device timezone",
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
            placeholder = "Search timezones",
            modifier = Modifier.fillMaxWidth(),
        )
        val matches = form.timezones.filter {
            search.isBlank() || it.label.contains(search, ignoreCase = true) ||
                it.identifier.contains(search, ignoreCase = true)
        }
        val zoneState = rememberLazyListState()
        LazyColumn(
            state = zoneState,
            modifier = Modifier
                .heightIn(max = TIMEZONE_LIST_HEIGHT)
                .then(rememberWheelScroll(zoneState)),
        ) {
            item(key = "device") {
                TimezoneChoice("Device timezone", form.draft.timezoneId.isBlank()) { onPick("") }
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
            text = "Colour",
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
        FieldLabel("Reminder")
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
private fun GuestSection(form: EventFormState, change: (EventDraft) -> Unit) {
    val draft = form.draft
    if (!draft.isForMembers) return

    InviteeList(form, change)
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
 * offline for the people most events go to.
 */
@Composable
private fun InviteeList(form: EventFormState, change: (EventDraft) -> Unit) {
    if (form.invitees.isEmpty()) return
    val draft = form.draft

    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        FieldLabel(
            if (draft.inviteeIds.isEmpty()) {
                "Invite crew"
            } else {
                "Invite crew — ${draft.inviteeIds.size} selected"
            },
        )

        Column(
            modifier = Modifier.heightIn(max = INVITEE_LIST_HEIGHT).zillitVerticalScroll(),
        ) {
            form.invitees.forEach { invitee ->
                ZillitCheckbox(
                    checked = invitee.userId in draft.inviteeIds,
                    label = invitee.name,
                    onCheckedChange = { on ->
                        change(
                            draft.copy(
                                inviteeIds = if (on) {
                                    draft.inviteeIds + invitee.userId
                                } else {
                                    draft.inviteeIds - invitee.userId
                                },
                            ),
                        )
                    },
                )
            }
        }
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
        FieldLabel("External guests")

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
                text = "Add",
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
            text = "Remove",
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
private val INVITEE_LIST_HEIGHT = 160.dp

private val SWATCH = 22.dp
private val TIMEZONE_MENU_WIDTH = 340.dp
private val TIMEZONE_LIST_HEIGHT = 260.dp
private val SWATCH_RING = 2.dp
