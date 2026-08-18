package com.zillit.desktop.feature.home.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.rememberWheelScroll
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField

/**
 * Creating and editing an event.
 *
 * A port of the web's `EventForm`, minus the parts that need pickers this app
 * does not have yet — recurrence rules, a map location, a colour palette and a
 * timezone list. Those are named in the summary rather than half-built: a
 * recurrence control that writes nothing is worse than its absence.
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
            current.error?.let {
                ZillitText(
                    text = it,
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.danger,
                )
            }
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
        placeholder = "Event name",
        errorText = form.errors.messageFor(EventFieldError.TitleBlank),
        modifier = Modifier.fillMaxWidth(),
    )

    DatePickerField(
        value = draft.dateText,
        onValueChange = { change(draft.copy(dateText = it)) },
        today = form.today,
        errorText = form.errors.messageFor(EventFieldError.DateInvalid),
        modifier = Modifier.fillMaxWidth(),
    )

    ZillitCheckbox(
        checked = draft.isAllDay,
        label = "All day",
        onCheckedChange = { change(draft.copy(isAllDay = it)) },
    )

    TimeFields(form, change)

    ZillitTextField(
        value = draft.location,
        onValueChange = { change(draft.copy(location = it)) },
        placeholder = "Location",
        modifier = Modifier.fillMaxWidth(),
    )

    TimezoneRow(form, change)
    ColorRow(draft, change)
    ReminderRow(draft, change)
    RecurrenceSection(form, change)

    ZillitTextField(
        value = draft.description,
        onValueChange = { change(draft.copy(description = it)) },
        placeholder = "Notes",
        singleLine = false,
        modifier = Modifier.fillMaxWidth().heightIn(min = NOTES_HEIGHT),
    )

    InviteeList(form, change)
}

/**
 * How often it repeats.
 *
 * The weekday row and the end date appear only once a repeat is chosen — a
 * fixed block of repeat controls above every one-off event is furniture, and
 * most events are one-offs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecurrenceSection(form: EventFormState, change: (EventDraft) -> Unit) {
    val draft = form.draft
    val rule = draft.recurrence

    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitText(
            text = "Repeat",
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            RecurrenceFrequency.entries.forEach { frequency ->
                ZillitButton(
                    text = frequency.label,
                    variant = if (frequency == rule.frequency) {
                        ButtonVariant.Secondary
                    } else {
                        ButtonVariant.Tertiary
                    },
                    size = ButtonSize.Small,
                    onClick = { change(draft.copy(recurrence = rule.copy(frequency = frequency))) },
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
                placeholder = "Repeat until — 2026-12-31",
                errorText = form.recurrenceErrors.messageFor(RecurrenceError.NoEndDate)
                    ?: form.recurrenceErrors.messageFor(RecurrenceError.EndBeforeStart),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Which days a custom repeat lands on. Sunday first, as the server stores them. */
@Composable
private fun WeekdayPicker(rule: Recurrence, change: (Recurrence) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        WEEKDAY_LABELS.forEachIndexed { index, label ->
            ZillitButton(
                text = label,
                variant = if (index in rule.selectedDays) {
                    ButtonVariant.Secondary
                } else {
                    ButtonVariant.Tertiary
                },
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

@Composable
private fun ErrorLine(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.danger,
    )
}

private fun Set<RecurrenceError>.messageFor(error: RecurrenceError): String? =
    if (error in this) error.message else null

/** The reminder options the web offers, as a row of choices. */
/**
 * Start and end, side by side.
 *
 * Hidden rather than disabled when the event covers the whole day: two
 * greyed-out fields say "you got something wrong" where nothing is wrong.
 */
@Composable
private fun TimeFields(form: EventFormState, change: (EventDraft) -> Unit) {
    val draft = form.draft
    if (draft.isAllDay) return

    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitTextField(
            value = draft.startText,
            onValueChange = { change(draft.copy(startText = it)) },
            placeholder = "Starts — 09:00",
            errorText = form.errors.messageFor(EventFieldError.StartTimeInvalid)
                ?: form.errors.messageFor(EventFieldError.EndBeforeStart),
            modifier = Modifier.weight(1f),
        )
        ZillitTextField(
            value = draft.endText,
            onValueChange = { change(draft.copy(endText = it)) },
            placeholder = "Ends — 17:30",
            errorText = form.errors.messageFor(EventFieldError.EndTimeInvalid),
            modifier = Modifier.weight(1f),
        )
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
    var open by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }

    val chosen = form.timezones.firstOrNull { it.identifier == draft.timezoneId }

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
                text = chosen?.label ?: "Device timezone",
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                onClick = { open = true; search = "" },
                enabled = form.timezones.isNotEmpty(),
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                TimezoneMenu(
                    form = form,
                    search = search,
                    onSearch = { search = it },
                    onPick = { id ->
                        change(draft.copy(timezoneId = id))
                        open = false
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
private fun ReminderRow(draft: EventDraft, change: (EventDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitText(
            text = "Reminder",
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
        // Wraps: a Row squeezed the last chip into a one-letter-wide column.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            REMINDER_CHOICES.forEach { minutes ->
                ZillitButton(
                    text = reminderLabel(minutes),
                    variant = if (minutes == draft.reminderMinutes) {
                        ButtonVariant.Secondary
                    } else {
                        ButtonVariant.Tertiary
                    },
                    size = ButtonSize.Small,
                    onClick = { change(draft.copy(reminderMinutes = minutes)) },
                )
            }
        }
    }
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
        ZillitText(
            text = if (draft.inviteeIds.isEmpty()) {
                "Invite crew"
            } else {
                "Invite crew — ${draft.inviteeIds.size} selected"
            },
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
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

private fun Set<EventFieldError>.messageFor(error: EventFieldError): String? =
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
