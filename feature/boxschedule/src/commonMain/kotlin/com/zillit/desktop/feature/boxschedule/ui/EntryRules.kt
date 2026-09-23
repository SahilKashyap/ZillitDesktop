package com.zillit.desktop.feature.boxschedule.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.boxschedule.domain.DiaryAudience
import com.zillit.desktop.feature.boxschedule.domain.DiaryCalendar
import com.zillit.desktop.feature.boxschedule.domain.DiaryDraft
import com.zillit.desktop.feature.boxschedule.domain.DiaryEvent
import com.zillit.desktop.feature.boxschedule.domain.DiaryFormat
import com.zillit.desktop.feature.boxschedule.domain.DiaryKind
import com.zillit.desktop.feature.boxschedule.domain.DiaryMath
import com.zillit.desktop.feature.boxschedule.domain.GuestEmails
import com.zillit.desktop.feature.boxschedule.domain.NoteType
import com.zillit.desktop.feature.boxschedule.domain.RecurrenceScope
import com.zillit.desktop.feature.boxschedule.domain.ScheduleBlock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** What an entry's validation found: inline errors, and the one message a toast shows. */
data class EntryCheck(val errors: Map<EntryField, String>, val message: String?) {
    val passes: Boolean get() = errors.isEmpty() && message == null
}

/** One "Link to a schedule day" choice. */
data class DayLink(val key: String, val label: String, val dayKey: Long)

/**
 * The event drawer's rules — `CreateEventModal`'s `validateEvent`,
 * `validateNote`, `buildEventPayload` and the note payload — kept pure so
 * they are pinned by test rather than by clicking.
 */
object EntryRules {

    private const val MIN_TITLE = 3
    private const val MIN_DURATION_MINUTES = 15
    private const val QUARTER = 15
    private const val MINUTES_PER_HOUR = 60
    private const val MINUTES_PER_DAY = 24 * 60

    /** `validateEvent` / `validateNote`, in the web's order. */
    fun check(form: EntryForm, today: LocalDate, noteTypes: List<NoteType>): EntryCheck =
        if (form.isNote) checkNote(form, today, noteTypes) else checkEvent(form, today)

    @Suppress("CyclomaticComplexMethod") // The web's ten checks, in its order.
    private fun checkEvent(form: EntryForm, today: LocalDate): EntryCheck {
        val errors = linkedMapOf<EntryField, String>()
        var semantic: String? = null
        val title = form.title.trim()
        when {
            title.isEmpty() -> errors[EntryField.Title] = str(S.error_title_required)
            title.length < MIN_TITLE -> errors[EntryField.Title] = str(S.error_title_min)
        }
        val start = form.startDate
        when {
            start == null -> errors[EntryField.StartDate] = str(S.start_date_required)
            !form.isEdit && start < today -> errors[EntryField.StartDate] = str(S.desktop_bs_event_past_date)
        }
        if (!form.fullDay) {
            if (form.startTime == null) errors[EntryField.StartTime] = str(S.docusign_prop_required)
            if (form.endTime == null) errors[EntryField.EndTime] = str(S.docusign_prop_required)
        }
        val end = form.endDate
        if (start != null && end != null && end < start) {
            errors[EntryField.EndDate] = str(S.desktop_bs_end_date_after_start)
        }
        if (form.callType.isBlank()) errors[EntryField.CallType] = str(S.error_call_type_required)
        if (form.callType == "meet_in_person_call" && form.location.isBlank()) {
            errors[EntryField.Location] = str(S.desktop_bs_location_required_meet_call)
        }
        val minutes = durationMinutes(form)
        if (minutes != null && minutes < MIN_DURATION_MINUTES) semantic = str(S.desktop_bs_event_min_duration)
        if (!form.isSingleScope && form.repeat != "none" && form.repeatEnd == null) {
            errors[EntryField.RepeatEnd] = str(S.docusign_prop_required)
            semantic = semantic ?: str(S.desktop_bs_repeat_end_required)
        }
        // Distribute To may be skipped only when external guests are the audience.
        val audienceProblem = if (form.audience.mode.wire.isEmpty() && form.guests.isNotEmpty()) {
            null
        } else {
            form.audience.problem("event")
        }
        audienceProblem?.let { errors[EntryField.Audience] = it }
        return EntryCheck(errors, semantic ?: errors.values.firstOrNull())
    }

    private fun checkNote(form: EntryForm, today: LocalDate, noteTypes: List<NoteType>): EntryCheck {
        val errors = linkedMapOf<EntryField, String>()
        val date = form.noteDate
        when {
            form.noteTitle.isBlank() -> errors[EntryField.NoteTitle] = str(S.ce_validation_title_required)
            date == null -> errors[EntryField.NoteDate] = str(S.ce_validation_start_date_required)
            !form.isEdit && date < today -> errors[EntryField.NoteDate] = str(S.ce_note_validation_past_date)
        }
        if (!hidesDistribution(form.noteType, noteTypes)) {
            form.audience.problem("note")?.let { errors[EntryField.Audience] = it }
        }
        return EntryCheck(errors, errors.values.firstOrNull())
    }

    /** A Personal Note is never distributed — its type says so with `hide_distribution`. */
    fun hidesDistribution(noteType: String, noteTypes: List<NoteType>): Boolean =
        (noteTypes.ifEmpty { NoteType.DEFAULTS }).firstOrNull { it.value == noteType }?.hideDistribution == true

    /**
     * The wire draft. Dates go out as epoch milliseconds; a full day ends at
     * its last millisecond; an end that is not after the start crosses
     * midnight, as the validation already treats it.
     */
    fun draft(
        form: EntryForm,
        zone: TimeZone,
        noteTypes: List<NoteType>,
        createInCalendar: Boolean?,
        newId: () -> String,
    ): DiaryDraft = if (form.isNote) noteDraft(
        form,
        zone,
        noteTypes,
    ) else eventDraft(form, zone, createInCalendar, newId)

    private fun eventDraft(
        form: EntryForm,
        zone: TimeZone,
        createInCalendar: Boolean?,
        newId: () -> String,
    ): DiaryDraft {
        val startDate = checkNotNull(form.startDate) { "validated" }
        val endDate = form.endDate ?: startDate
        val start = when {
            form.fullDay || form.startTime == null -> DiaryCalendar.startOf(startDate, zone)
            else -> startDate.atTime(form.startTime).toInstant(zone).toEpochMilliseconds()
        }
        val end = when {
            form.fullDay -> DiaryCalendar.startOf(endDate.plus(1, DateTimeUnit.DAY), zone) - 1
            form.endTime == null -> start
            else -> endDate.atTime(form.endTime).toInstant(zone).toEpochMilliseconds()
                .let { if (it <= start) it + DiaryMath.DAY_MS else it }
        }
        val repeats = !form.isSingleScope && form.repeat != "none"
        return DiaryDraft(
            kind = DiaryKind.Event,
            title = form.title.trim(),
            body = form.description,
            date = DiaryCalendar.startOf(startDate, zone),
            startDateTime = start,
            endDateTime = end,
            fullDay = form.fullDay,
            location = form.location,
            locationLat = form.locationLat,
            locationLng = form.locationLng,
            color = form.color,
            scheduleDayId = linkedDay(form),
            repeatStatus = if (repeats) form.repeat else "none",
            // The last day inclusive, whatever time the event starts at.
            repeatEndDate = form.repeatEnd?.takeIf { repeats }
                ?.let { DiaryCalendar.startOf(it.plus(1, DateTimeUnit.DAY), zone) - 1 } ?: 0,
            timezone = form.timezone,
            reminder = form.reminder,
            textColor = form.textColor,
            audience = form.audience,
            externalEmails = GuestEmails.toWire(form.guests, form.originalGuests, newId),
            organizerExcluded = form.organizerExcluded,
            createInCalendar = createInCalendar.takeIf { !form.isEdit },
            callType = form.callType,
        )
    }

    private fun noteDraft(form: EntryForm, zone: TimeZone, noteTypes: List<NoteType>): DiaryDraft {
        val day = DiaryCalendar.startOf(checkNotNull(form.noteDate) { "validated" }, zone)
        return DiaryDraft(
            kind = DiaryKind.Note,
            title = form.noteTitle.trim(),
            body = form.noteText,
            date = day,
            startDateTime = day,
            endDateTime = day + DiaryMath.DAY_MS - 1,
            fullDay = true,
            color = form.noteColor,
            scheduleDayId = linkedDay(form),
            noteType = form.noteType,
            audience = if (hidesDistribution(form.noteType, noteTypes)) DiaryAudience.Nobody else form.audience,
        )
    }

    /** A create from a schedule day files on it; otherwise the picker decides, and an edit only ever the picker. */
    private fun linkedDay(form: EntryForm): String =
        (if (form.isEdit) form.linkedDayId else form.pinnedDayId ?: form.linkedDayId).orEmpty()

    /**
     * "Link to a schedule day" — every date of every schedule, in order. A new
     * entry links forward only; an edit may keep a day that has passed, and
     * always finds the day it is on.
     */
    fun linkOptions(form: EntryForm, blocks: List<ScheduleBlock>, todayKey: Long, zone: TimeZone): List<DayLink> {
        val options = blocks.flatMap { block ->
            block.calendarDays.map { DiaryCalendar.dayKey(it, zone) }.distinct()
                .filter { form.isEdit || it >= todayKey }
                .map { key -> DayLink("${block.id}|$key", linkLabel(block, key, zone), key) }
        }.sortedBy { it.dayKey }
        val current = form.linkKey ?: return options
        if (options.any { it.key == current }) return options
        val blockId = current.substringBefore('|')
        val key = current.substringAfter('|').toLongOrNull() ?: return options
        val block = blocks.firstOrNull { it.id == blockId }
        val label = block?.let { linkLabel(it, key, zone) }
            ?: str(S.desktop_bs_link_schedule_day_fallback, DiaryFormat.shortDay(key, zone))
        return listOf(DayLink(current, label, key)) + options
    }

    private fun linkLabel(block: ScheduleBlock, dayKey: Long, zone: TimeZone): String {
        val title = block.title.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return "${DiaryFormat.shortDay(dayKey, zone)} — ${block.typeName}$title"
    }

    /** The earliest occurrence of a series in the list — `masterAnchorOf`, for an all-events edit. */
    fun masterAnchor(events: List<DiaryEvent>, masterId: String): DiaryEvent? =
        events.filter { it.masterId == masterId }.minByOrNull { it.occurrenceDate }

    /** The next quarter hour from [now] — the web's `roundToNext15Min`, never the current minute. */
    fun nextQuarter(now: Long, zone: TimeZone): LocalTime {
        val t = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).time
        val minutes = (t.hour * MINUTES_PER_HOUR + t.minute + QUARTER - t.minute % QUARTER) % MINUTES_PER_DAY
        return LocalTime(minutes / MINUTES_PER_HOUR, minutes % MINUTES_PER_HOUR)
    }

    /** An hour later, wrapping at midnight. */
    fun hourAfter(time: LocalTime): LocalTime {
        val minutes = (time.hour * MINUTES_PER_HOUR + time.minute + MINUTES_PER_HOUR) % MINUTES_PER_DAY
        return LocalTime(minutes / MINUTES_PER_HOUR, minutes % MINUTES_PER_HOUR)
    }

    /** A repeat's automatic end: a day, a week or a month on from the start. */
    fun repeatEndFor(repeat: String, start: LocalDate): LocalDate? = when (repeat) {
        "daily" -> start.plus(1, DateTimeUnit.DAY)
        "weekly" -> start.plus(DiaryCalendar.DAYS_IN_WEEK, DateTimeUnit.DAY)
        "monthly" -> start.plus(DatePeriod(months = 1))
        "yearly" -> start.plus(DatePeriod(years = 1))
        else -> null
    }

    /** The friendly sentence for a recurring edit's refusal — `editErrorCopy`. */
    fun editErrorCopy(key: String?): String? = when (key) {
        "event_not_found" -> str(S.desktop_cal_event_no_longer_exists)
        "occurrence_date_not_in_series" -> str(S.desktop_bs_err_date_not_in_series)
        "occurrence_already_excluded" -> str(S.desktop_bs_err_occurrence_removed)
        "occurrence_already_moved" -> str(S.desktop_bs_err_occurrence_moved)
        "field_not_editable_on_single_occurrence" ->
            str(S.desktop_bs_err_recurrence_single_edit)
        "no_changes_to_apply" -> str(S.desktop_bs_err_nothing_changed)
        "update_transaction_failed" -> str(S.asset_save_failed)
        else -> null
    }

    private fun LocalDate.atTime(time: LocalTime): LocalDateTime = LocalDateTime(this, time)

    /** A timed event's length; an end at or before its start runs past midnight. Null until both ends are set. */
    private fun durationMinutes(form: EntryForm): Long? {
        val from = form.startDate?.let { date -> form.startTime?.let { date.atTime(it) } }
        val until = form.endDate?.let { date -> form.endTime?.let { date.atTime(it) } }
        if (form.fullDay || from == null || until == null) return null
        val to = if (until <= from) until.date.plus(1, DateTimeUnit.DAY).atTime(until.time) else until
        return minutesBetween(from, to)
    }

    private fun minutesBetween(from: LocalDateTime, to: LocalDateTime): Long {
        val zone = TimeZone.UTC
        return (to.toInstant(zone).toEpochMilliseconds() - from.toInstant(zone).toEpochMilliseconds()) / MS_PER_MINUTE
    }

    /** Which recurring scope a form's edit sends; a plain edit is the whole document. */
    fun updateScopeOf(form: EntryForm): RecurrenceScope = form.scope ?: RecurrenceScope.All

    private const val MS_PER_MINUTE = 60_000L
}
