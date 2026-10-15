package com.zillit.desktop.feature.crewdood.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber

/**
 * What the Crew DOOD is made of — the web's `lib/dood.js` shapes, as types.
 *
 * Every date here is a [LocalDate]: a calendar day, never a stamp. The wire's
 * stamps are read into days once, at the repository boundary, because the two
 * sources disagree about which midnight they mean — a deal's `start_date` is a
 * **UTC** midnight and the Production Calendar's `activity_date` a **local**
 * one. Reading either in the other's zone shifts a bar by a day for anyone
 * east or west of UTC, which is the single most expensive mistake in this
 * model (web README, "Deal dates are UTC days; production dates are LOCAL").
 */

/** One deal's booking window. A null [end] is open-ended: it runs to the chart's last day. */
data class DoodBooking(val start: LocalDate, val end: LocalDate?)

/**
 * One grid day's state for one person.
 *
 * [rank] settles an overlap: where two of a person's deals meet on a day, the
 * higher rank is drawn, so a hand-over day reads as the new deal's start.
 * [worked] is what counts in the Days column and the footer — a day off inside
 * a booking is marked but never worked (product decision, 2026-10-13).
 */
@Suppress("MagicNumber") // The ranks ARE the numbers; a constant per rank says less, not more.
enum class DayCode(val rank: Int, val worked: Boolean) {
    /** Not booked — including days off and holidays outside a booking. */
    Blank(0, false),

    /** A weekly day off between SW and WF. */
    Off(1, false),

    /** A holiday between SW and WF; it beats [Off] when both fall on one day. */
    Hol(2, false),

    /** A working day between SW and WF. */
    Work(3, true),

    /** The last working day of a booking. */
    Finish(4, true),

    /** The first working day of a booking. */
    Start(5, true),

    /** A booking that is one working day long — SWF. */
    Single(6, true),
    ;
}

/** Which band a grid day belongs to: the three the Production Calendar implies. */
enum class DoodBand { Prep, Shoot, Post }

/** What a day's label says. [Rest] is a non-shoot day between the first and last shoot days. */
enum class DoodPhaseKind { Prep, Shoot, Post, Rest }

/**
 * A day's phase: its band, what kind of day it is, and the number that names
 * it — Shoot Day *n*, Prep W*n*, Post W*n*. [to] is set only on a week column
 * that holds several shoot days ("Shoot Days 3–7").
 */
data class DoodPhase(
    val band: DoodBand,
    val kind: DoodPhaseKind,
    val n: Int? = null,
    val to: Int? = null,
)

/** A holiday as the API carries it: one entry per day, the name possibly blank. */
data class DoodHoliday(val date: LocalDate, val name: String)

/**
 * Consecutive days with the same name, as the editor lists and bands them.
 * However a range was added, the wire keeps one entry per day; this is only
 * how it is shown, renamed and removed.
 */
data class DoodHolidayRun(val start: LocalDate, val end: LocalDate, val name: String, val dates: List<LocalDate>)

/** Saturday and Sunday, until the project saves its own (0 = Sunday, 6 = Saturday). */
val DEFAULT_WEEKLY_OFF: Set<Int> = setOf(0, 6)

/** 0 = Sunday … 6 = Saturday, the week numbering the calendar API speaks. */
fun LocalDate.sundayBasedDay(): Int = dayOfWeek.isoDayNumber % DAYS_PER_WEEK

const val DAYS_PER_WEEK = 7

/**
 * The project's days off — one calendar for the whole production.
 *
 * [weeklyOff] is the API's `weekly_off`, **0 = Sunday … 6 = Saturday**. Nothing
 * saved (or an endpoint that is not live yet) means Saturday and Sunday off
 * and no holidays: see [DoodCalendar.Default].
 */
data class DoodCalendar(
    val weeklyOff: Set<Int> = DEFAULT_WEEKLY_OFF,
    val holidays: Map<LocalDate, String> = emptyMap(),
) {
    fun isWeeklyOff(date: LocalDate): Boolean = weeklyOff.contains(date.sundayBasedDay())

    fun isHoliday(date: LocalDate): Boolean = holidays.containsKey(date)

    /** A weekly day off or a holiday: nobody works it, not even a shoot day. */
    fun isDayOff(date: LocalDate): Boolean = isWeeklyOff(date) || isHoliday(date)

    companion object {
        val Default = DoodCalendar()
    }
}

/** A person on the grid, with the row they draw. */
data class DoodCrew(
    /** `user_id`, else `ext:<crew name>` — one row however many deals they hold. */
    val key: String,
    val userId: String,
    val name: String,
    val role: String,
    /** The crew list's own photo reference, when it carries one. */
    val picture: String? = null,
    /** True for someone with a booking deal but no crew-list row. */
    val external: Boolean = false,
    /** One code per grid day, same length as the model's dates. */
    val cells: List<DayCode> = emptyList(),
    /** Worked days in [cells] — OFF and HOL do not count. */
    val days: Int = 0,
)

/** A department group on the grid, in the crew list's order. */
data class DoodDepartment(val key: String, val name: String, val crew: List<DoodCrew>) {
    val bookedCount: Int get() = crew.count { it.days > 0 }
}

/**
 * One column of the grid: a day in Days view, a Monday–Sunday week in Weeks
 * view. [days] are indices into the model's dates.
 */
data class DoodColumn(
    val days: List<Int>,
    val start: LocalDate,
    val end: LocalDate,
    val band: DoodBand?,
    val label: DoodPhase?,
    /** A weekly day off — Days view only; a week column is never shaded. */
    val off: Boolean,
    /** The holiday's name (possibly blank) when this single day is one. */
    val holiday: String?,
    /** Days in this column that anyone could work — what the Weeks view counts against. */
    val workdays: Int,
)

/** What one row shows in one column: the code, and how many of its days are worked. */
data class DoodCell(val code: DayCode, val booked: Int)

/** The whole chart. */
data class DoodModel(
    val dates: List<LocalDate> = emptyList(),
    val phases: List<DoodPhase?> = emptyList(),
    val departments: List<DoodDepartment> = emptyList(),
    val calendar: DoodCalendar = DoodCalendar.Default,
    val hasPhases: Boolean = false,
)
