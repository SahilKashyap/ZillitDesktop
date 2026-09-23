package com.zillit.desktop.feature.boxschedule.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.boxschedule.domain.DiaryCalendar
import com.zillit.desktop.feature.boxschedule.domain.DiaryDraft
import com.zillit.desktop.feature.boxschedule.domain.DiaryEvent
import com.zillit.desktop.feature.boxschedule.domain.DiaryKind
import com.zillit.desktop.feature.boxschedule.domain.RecurrenceScope
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Events and notes — the web's `CreateEventModal`, the recurring scope
 * prompts, `BoxScheduleEventDeletePrompt` and the Calendar-sourced guard.
 */
@Suppress("TooManyFunctions") // One handler per act on an entry.
internal class EntryActions(private val vm: BoxScheduleViewModel) {

    private val audience = AudienceActions(vm)

    @Suppress("CyclomaticComplexMethod", "LongMethod") // Event fan-out: one line per field.
    fun onEvent(event: EntryEvent) {
        if (audience.onEvent(event)) return
        when (event) {
            is EntryEvent.NewEntry -> newEntry(event.kind, event.date, event.scheduleDayId)
            is EntryEvent.EditEntry -> editEntry(event.listKey)
            is EntryEvent.ChooseUpdateScope -> vm.updateOverlays {
                copy(updateScope = updateScope?.copy(scope = event.scope))
            }
            EntryEvent.ConfirmUpdateScope -> confirmUpdateScope()
            EntryEvent.CancelUpdateScope -> vm.updateOverlays { copy(updateScope = null) }
            is EntryEvent.SetLink -> form { link(event.key) }
            is EntryEvent.SetTitle -> form { copy(title = event.text, errors = errors - EntryField.Title) }
            is EntryEvent.SetDescription -> form { copy(description = event.text) }
            is EntryEvent.SetFullDay -> form {
                copy(fullDay = event.on, errors = errors - EntryField.EndTime - EntryField.EndDate)
            }
            is EntryEvent.SetStartDate -> form { startDate(event.date) }
            is EntryEvent.SetStartTime -> form {
                // Picking a start slides the end an hour on — ZL-18868.
                copy(
                    startTime = event.time,
                    endTime = event.time?.let(EntryRules::hourAfter) ?: endTime,
                    errors = errors - EntryField.StartTime - EntryField.EndTime,
                )
            }
            is EntryEvent.SetEndTime -> form { copy(endTime = event.time, errors = errors - EntryField.EndTime) }
            is EntryEvent.SetRepeat -> form {
                val base = startDate ?: vm.currentState.today
                copy(
                    repeat = event.value,
                    repeatEnd = EntryRules.repeatEndFor(event.value, base),
                    errors = errors - EntryField.RepeatEnd,
                )
            }
            is EntryEvent.SetRepeatEnd -> form {
                val after = startDate ?: vm.currentState.today
                if (event.date != null && event.date <= after) this else copy(
                    repeatEnd = event.date,
                    errors = errors - EntryField.RepeatEnd,
                )
            }
            is EntryEvent.SetTimezone -> form { copy(timezone = event.zoneId) }
            is EntryEvent.SetReminder -> form { copy(reminder = event.value) }
            is EntryEvent.SetCallType -> form {
                copy(callType = event.value, errors = errors - EntryField.CallType - EntryField.Location)
            }
            is EntryEvent.SetTextColor -> form { copy(textColor = event.color) }
            is EntryEvent.SetLocation -> form {
                // Typing clears the coordinates it no longer describes; a map pick sets both.
                copy(
                    location = event.text,
                    locationLat = event.lat,
                    locationLng = event.lng,
                    errors = if (event.text.isNotBlank()) errors - EntryField.Location else errors,
                )
            }
            is EntryEvent.SetColor -> form { copy(color = event.color) }
            is EntryEvent.SetOrganizerExcluded -> form { copy(organizerExcluded = event.on) }
            is EntryEvent.SetNoteType -> form { copy(noteType = event.value, errors = errors - EntryField.Audience) }
            is EntryEvent.SetNoteDate -> form { noteDate(event.date) }
            is EntryEvent.SetNoteTitle -> form { copy(noteTitle = event.text, errors = errors - EntryField.NoteTitle) }
            is EntryEvent.SetNoteText -> form { copy(noteText = event.text) }
            is EntryEvent.SetNoteColor -> form { copy(noteColor = event.color) }
            EntryEvent.Save -> save()
            is EntryEvent.AnswerCalendar -> answerCalendar(event.mirror)
            EntryEvent.Close -> vm.updateOverlays { copy(entryForm = null) }
            is EntryEvent.AskDelete -> askDelete(event.listKey)
            is EntryEvent.ChooseDeleteScope -> vm.updateOverlays {
                copy(deleteEntry = deleteEntry?.copy(scope = event.scope))
            }
            EntryEvent.ConfirmDelete -> confirmDelete()
            EntryEvent.CancelDelete -> vm.updateOverlays { copy(deleteEntry = null) }
            EntryEvent.CloseCalendarInfo -> vm.updateOverlays { copy(calendarInfo = null) }
            else -> Unit
        }
    }

    private fun form(change: EntryForm.() -> EntryForm) = vm.updateOverlays { copy(entryForm = entryForm?.change()) }

    private val zone: TimeZone get() = vm.currentState.zone

    private fun dateOf(ms: Long): LocalDate = DiaryCalendar.dateOf(ms, zone)

    // Opening ------------------------------------------------------------------

    /**
     * A new event or note. A date from the calendar seeds it; a schedule day
     * files it there and locks its date (the web's entry point B).
     */
    private fun newEntry(kind: DiaryKind, date: Long?, scheduleDayId: String?) {
        if (!vm.mayEdit()) return
        val state = vm.currentState
        val day = date?.let(::dateOf)
        val start = EntryRules.nextQuarter(vm.now(), zone)
        val form = EntryForm(
            kind = kind,
            pinnedDayId = scheduleDayId?.takeIf { it.isNotBlank() },
            headerDate = day,
            startDate = day ?: state.today,
            endDate = day ?: state.today,
            startTime = start,
            endTime = EntryRules.hourAfter(start),
            timezone = zone.id,
            noteDate = day ?: state.today,
        )
        vm.updateOverlays { copy(entryForm = form, day = null, quickAction = null, viewing = null) }
    }

    /** Calendar-sourced rows explain themselves; a recurring row asks its scope first. */
    private fun editEntry(listKey: String) {
        if (!vm.mayEdit()) return
        val entry = vm.currentState.entry(listKey) ?: return
        when {
            entry.calendarSourced -> vm.updateOverlays {
                copy(calendarInfo = CalendarInfo(forDelete = false), viewing = null)
            }
            entry.isRecurring -> vm.updateOverlays { copy(updateScope = EntryScopePrompt(listKey), viewing = null) }
            else -> openForm(entry, scope = null, occurrenceDate = null, anchor = null)
        }
    }

    /**
     * An all-events edit is seeded from the series' first occurrence — sending
     * a mid-series start would ask the server to move the series. A single
     * occurrence becomes a standalone that does not repeat.
     */
    private fun confirmUpdateScope() {
        val prompt = vm.currentState.overlays.updateScope ?: return
        val entry = vm.currentState.entry(prompt.listKey)
        vm.updateOverlays { copy(updateScope = null) }
        if (entry == null || !vm.mayEdit()) return
        val all = prompt.scope == RecurrenceScope.All
        openForm(
            entry = entry,
            scope = prompt.scope,
            occurrenceDate = if (all) null else entry.occurrenceDate,
            anchor = if (all) EntryRules.masterAnchor(vm.currentState.events, entry.masterId) else null,
        )
    }

    private fun openForm(entry: DiaryEvent, scope: RecurrenceScope?, occurrenceDate: Long?, anchor: DiaryEvent?) {
        val source = anchor ?: entry
        val single = scope == RecurrenceScope.Single
        val startMs = source.startDateTime.takeIf { it > 0 } ?: source.date.takeIf { it > 0 } ?: vm.now()
        val endMs = source.endDateTime.takeIf { it > 0 } ?: startMs
        val form = EntryForm(
            kind = entry.kind,
            listKey = entry.listKey,
            masterId = entry.masterId,
            scope = scope,
            occurrenceDate = occurrenceDate,
            linkKey = entry.scheduleDayId.takeIf { it.isNotBlank() && entry.date > 0 }
                ?.let { "$it|${DiaryCalendar.dayKey(entry.date, zone)}" },
            title = entry.title,
            description = entry.body,
            startDate = dateOf(startMs),
            startTime = if (source.fullDay) null else timeOf(startMs),
            endDate = dateOf(endMs),
            endTime = if (source.fullDay) null else timeOf(endMs),
            fullDay = entry.fullDay,
            location = entry.location,
            locationLat = entry.locationLat,
            locationLng = entry.locationLng,
            repeat = if (single) "none" else source.repeatStatus.ifBlank { "none" },
            repeatEnd = if (single) null else source.repeatEndDate.takeIf { it > 0 }?.let(::dateOf),
            timezone = entry.timezone.ifBlank { zone.id },
            reminder = entry.reminder.ifBlank { "none" },
            callType = entry.callType,
            textColor = entry.textColor,
            color = entry.color.ifBlank { DiaryDraft.DEFAULT_EVENT_COLOR },
            guests = entry.externalEmails.map { it.mail },
            originalGuests = entry.externalEmails,
            organizerExcluded = entry.organizerExcluded,
            noteType = entry.noteType.ifBlank { "general" },
            noteDate = dateOf(entry.date.takeIf { it > 0 } ?: startMs),
            noteTitle = entry.title,
            noteText = entry.body,
            noteColor = entry.color.ifBlank { DiaryDraft.DEFAULT_EVENT_COLOR },
            audience = entry.audience,
        )
        vm.updateOverlays { copy(entryForm = form, viewing = null) }
    }

    private fun timeOf(ms: Long) = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone).time

    // Fields -------------------------------------------------------------------

    /**
     * Picking a schedule day moves a new entry — or an edited note — onto that
     * day; an edited event keeps its own dates, the link is all that changes.
     */
    private fun EntryForm.link(key: String?): EntryForm {
        val next = copy(linkKey = key, errors = errors - EntryField.NoteDate)
        if (isEdit && !isNote) return next
        val day = key?.substringAfter('|')?.toLongOrNull()?.let(::dateOf)
        return if (day != null) {
            next.copy(startDate = day, endDate = day, noteDate = day)
        } else {
            next.copy(startDate = vm.currentState.today, endDate = vm.currentState.today)
        }
    }

    /**
     * The start date, with the end date mirroring it. On a create, a date the
     * linked day is not on drops the link rather than sending a contradiction;
     * "this and following" keeps its occurrence's date.
     */
    private fun EntryForm.startDate(date: LocalDate?): EntryForm {
        if (isThisAndFollowing || (date != null && date < vm.currentState.today)) return this
        val linkedDay = linkKey?.substringAfter('|')?.toLongOrNull()?.let(::dateOf)
        val keepLink = isEdit || date == null || linkedDay == null || linkedDay == date
        return copy(
            startDate = date,
            endDate = date,
            linkKey = if (keepLink) linkKey else null,
            errors = errors - EntryField.StartDate - EntryField.EndDate - EntryField.EndTime,
        )
    }

    /** A note's own date; choosing one by hand breaks any schedule-day link. */
    private fun EntryForm.noteDate(date: LocalDate?): EntryForm {
        if (noteDateLocked || date == null || date < vm.currentState.today) return this
        return copy(noteDate = date, linkKey = null, errors = errors - EntryField.NoteDate)
    }

    // Saving -------------------------------------------------------------------

    private fun save() {
        val form = vm.currentState.overlays.entryForm ?: return
        if (!vm.mayEdit() || form.saving) return
        val check = EntryRules.check(form, vm.currentState.today, vm.currentState.noteTypes)
        form { copy(errors = check.errors) }
        if (!check.passes) {
            check.message?.let { vm.notice(it) }
            return
        }
        // A new event asks whether to mirror onto the Home calendar first — ZL-18836.
        if (!form.isNote && !form.isEdit) {
            form { copy(askCalendar = true) }
            return
        }
        submit(form, createInCalendar = null)
    }

    private fun answerCalendar(mirror: Boolean) {
        val form = vm.currentState.overlays.entryForm ?: return
        if (!form.askCalendar) return
        form { copy(askCalendar = false) }
        submit(form.copy(askCalendar = false), createInCalendar = mirror)
    }

    private fun submit(form: EntryForm, createInCalendar: Boolean?) {
        val state = vm.currentState
        val draft = EntryRules.draft(form, state.zone, state.noteTypes, createInCalendar, vm.host.newId)
        form { copy(saving = true) }
        vm.work {
            val masterId = form.masterId
            val result = if (masterId != null) {
                vm.repo.updateEvent(masterId, draft, EntryRules.updateScopeOf(form), form.occurrenceDate)
            } else {
                vm.repo.createEvent(draft)
            }
            when (result) {
                is ZillitResult.Success -> {
                    vm.updateOverlays { copy(entryForm = null) }
                    val message = when {
                        form.isNote && form.isEdit -> S.desktop_bs_note_updated
                        form.isNote -> S.desktop_bs_note_created
                        form.isEdit -> S.desktop_bs_event_updated
                        else -> S.desktop_bs_event_created
                    }
                    vm.notice(str(message), success = true)
                    vm.refresh()
                    // Follow the saved item, so a create in another month is seen.
                    vm.focusOn(dateOf(draft.date))
                }
                is ZillitResult.Failure -> {
                    form { copy(saving = false) }
                    vm.notice(saveFailure(result.error))
                }
            }
        }
    }

    private fun saveFailure(error: ZillitError): String {
        val key = (error as? ZillitError.Http)?.serverMessage
        return EntryRules.editErrorCopy(key) ?: error.localised().ifBlank { str(S.desktop_failed_to_save) }
    }

    // Deleting -----------------------------------------------------------------

    private fun askDelete(listKey: String) {
        if (!vm.mayEdit()) return
        val entry = vm.currentState.entry(listKey) ?: return
        if (entry.calendarSourced) {
            vm.updateOverlays { copy(calendarInfo = CalendarInfo(forDelete = true), viewing = null) }
        } else {
            vm.updateOverlays { copy(deleteEntry = EntryScopePrompt(listKey), viewing = null) }
        }
    }

    /**
     * A plain row is one whole-document delete. A recurring row sends its
     * scope, and — for anything short of the whole series — the occurrence's
     * own `startDateTime`: the start-of-day matches nothing on the server.
     */
    private fun confirmDelete() {
        val prompt = vm.currentState.overlays.deleteEntry ?: return
        if (!vm.mayEdit() || prompt.working) return
        val entry = vm.currentState.entry(prompt.listKey)
        if (entry == null) {
            vm.updateOverlays { copy(deleteEntry = null) }
            return
        }
        val scope = prompt.scope.takeIf { entry.isRecurring }
        val occurrence = entry.occurrenceDate.takeIf { scope != null && scope != RecurrenceScope.All }
        vm.updateOverlays { copy(deleteEntry = prompt.copy(working = true)) }
        vm.work {
            when (val result = vm.repo.deleteEvent(entry.masterId, scope, occurrence)) {
                is ZillitResult.Success -> {
                    vm.updateOverlays { copy(deleteEntry = null) }
                    val deleted =
                        if (entry.kind == DiaryKind.Note) S.desktop_bs_note_deleted else S.desktop_bs_event_deleted
                    vm.notice(str(deleted), success = true)
                    vm.refresh()
                }
                is ZillitResult.Failure -> {
                    vm.updateOverlays { copy(deleteEntry = deleteEntry?.copy(working = false)) }
                    vm.notice(result.error.localised().ifBlank { str(S.desktop_bs_failed_to_delete_event) })
                }
            }
        }
    }
}
