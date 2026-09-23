package com.zillit.desktop.feature.home.calendar

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus

/**
 * How often an event repeats.
 *
 * The numbers are the wire values, not display order — the server stores the
 * integer, so reordering this enum would silently change every existing
 * recurring event.
 */
@Suppress("MagicNumber") // These literals *are* the constants: the wire values.
enum class RecurrenceFrequency(val wireValue: Int, val label: String) {
    Never(0, S.does_not_repeat),
    Daily(1, S.daily),
    Weekly(2, S.ce_weekly),
    Monthly(3, S.ce_monthly),
    Yearly(4, S.yearly),

    /** Repeats on chosen weekdays. */
    Custom(5, S.custom),
    ;

    val repeats: Boolean get() = this != Never

    companion object {
        fun of(wireValue: Int?): RecurrenceFrequency =
            entries.firstOrNull { it.wireValue == wireValue } ?: Never
    }
}

/**
 * A repeat rule.
 *
 * [selectedDays] holds **Sunday-first indices**, 0..6, because that is what the
 * server stores and what the web's `DAY_LABELS` are ordered by. `DayOfWeek` is
 * Monday-first and 1-based, so the two are converted explicitly rather than
 * cast — an off-by-one here books a shoot day on the wrong day of the week.
 */
data class Recurrence(
    val frequency: RecurrenceFrequency = RecurrenceFrequency.Never,
    val selectedDays: Set<Int> = emptySet(),
    /** ISO date the repeat stops on, as typed. */
    val endDateText: String = "",
) {
    val repeats: Boolean get() = frequency.repeats

    /** Weekdays only matter for [RecurrenceFrequency.Custom]. */
    val needsWeekdays: Boolean get() = frequency == RecurrenceFrequency.Custom
}

/** Why a repeat rule was refused. */
enum class RecurrenceError { NoWeekdays, NoEndDate, EndBeforeStart }

val RecurrenceError.message: String
    get() = when (this) {
        RecurrenceError.NoWeekdays -> str(S.desktop_cal_choose_a_weekday)
        RecurrenceError.NoEndDate -> str(S.desktop_cal_repeat_needs_end_date)
        RecurrenceError.EndBeforeStart -> str(S.desktop_cal_repeat_end_after_start)
    }

/**
 * Checks a repeat rule against the event's own date.
 *
 * Both rules exist because the server enforces them and reports them badly: a
 * custom repeat with no weekday comes back as
 * `recurrence_rule.selectedDays must contain at least 1 items`, which is a
 * sentence written for whoever wrote the schema.
 */
fun Recurrence.validate(startDateText: String): Set<RecurrenceError> {
    if (!repeats) return emptySet()

    val errors = linkedSetOf<RecurrenceError>()

    if (needsWeekdays && selectedDays.isEmpty()) errors += RecurrenceError.NoWeekdays

    val end = endDateText.trim().toLocalDateOrNull()
    val start = startDateText.trim().toLocalDateOrNull()

    when {
        end == null -> errors += RecurrenceError.NoEndDate
        // Strictly after, not on: a repeat that stops the day it starts is a
        // one-off wearing a rule. The web's picker disables everything before
        // the day after the event for the same reason.
        start != null && end <= start -> errors += RecurrenceError.EndBeforeStart
    }

    return errors
}

/**
 * When the repeat stops, in epoch millis.
 *
 * The very end of the chosen day, not its start — the web uses `23:59:59` so
 * an occurrence *on* the end date is included. Stopping at midnight would drop
 * the last one, which nobody would report as a bug and everybody would notice.
 */
fun Recurrence.endMillis(zone: kotlinx.datetime.TimeZone): Long? {
    val end = endDateText.trim().toLocalDateOrNull() ?: return null
    val nextMidnight = LocalDate(end.year, end.month, end.day)
        .plusDays(1)
        .atStartOfDayMillisIn(zone)
    return nextMidnight - 1
}

/**
 * Sunday-first index, as the server stores weekdays.
 *
 * `DayOfWeek` counts Monday as 1 and Sunday as 7; the server counts Sunday as
 * 0. Written out rather than arithmetic so the conversion is readable at the
 * point someone comes looking for an off-by-one.
 */
fun DayOfWeek.toSundayFirstIndex(): Int = isoDayNumber % DAYS_IN_WEEK

/** The reverse, for rendering a stored rule back into the picker. */
fun sundayFirstIndexToDayOfWeek(index: Int): DayOfWeek =
    DayOfWeek(if (index == 0) DAYS_IN_WEEK else index)

/** `Sun`, `Mon`, … in the order the server indexes them. */
val WEEKDAY_LABELS: List<String> = listOf(S.sun, S.mon, S.tue, S.wed, S.thu, S.fri, S.sat)

private const val DAYS_IN_WEEK = 7

/**
 * Where a repeat stops by default, for a freshly chosen frequency.
 *
 * The web's `getDefaultEndDate`: one more of whatever the unit is. It gives the
 * required end date a sensible value the moment a repeat is picked, rather than
 * an empty field that only announces itself as a problem at save time.
 */
fun defaultRecurrenceEnd(frequency: RecurrenceFrequency, startDate: LocalDate): LocalDate? =
    when (frequency) {
        RecurrenceFrequency.Never -> null
        RecurrenceFrequency.Daily -> startDate.plus(1, DateTimeUnit.DAY)
        RecurrenceFrequency.Weekly -> startDate.plus(1, DateTimeUnit.WEEK)
        RecurrenceFrequency.Monthly -> startDate.plus(1, DateTimeUnit.MONTH)
        RecurrenceFrequency.Yearly -> startDate.plus(1, DateTimeUnit.YEAR)
        // A custom rule repeats on weekdays, so it is measured in weeks.
        RecurrenceFrequency.Custom -> startDate.plus(1, DateTimeUnit.WEEK)
    }
