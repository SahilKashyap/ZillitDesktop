package com.zillit.desktop.feature.crewdood.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.crewdood.domain.DayCode
import com.zillit.desktop.feature.crewdood.domain.DoodBand
import com.zillit.desktop.feature.crewdood.domain.DoodCell
import com.zillit.desktop.feature.crewdood.domain.DoodColumn
import com.zillit.desktop.feature.crewdood.domain.DoodPhase
import com.zillit.desktop.feature.crewdood.domain.DoodPhaseKind
import com.zillit.desktop.feature.crewdood.domain.sundayBasedDay
import kotlinx.datetime.LocalDate

/**
 * The grid's words — the web's `lib/format.js` and the label helpers in
 * `CrewDoodGrid.jsx`.
 *
 * A day is formatted from its parts, never through a stamp, so no zone can
 * move what is written on a column.
 */
internal object DoodWords {

    private val MONTHS = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )

    private val MONTHS_LONG = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    /** Sunday first, as the calendar API numbers the week. */
    private val WEEKDAYS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    /** Monday first, as the grid's weeks and the editor's calendar run. */
    val WEEK_ORDER = listOf(1, 2, 3, 4, 5, 6, 0)

    fun month(date: LocalDate): String = MONTHS[date.monthNumber - 1]

    fun monthLong(monthNumber: Int): String = MONTHS_LONG[monthNumber - 1]

    fun weekday(date: LocalDate): String = WEEKDAYS[date.sundayBasedDay()]

    fun weekdayName(sundayBased: Int): String = WEEKDAYS[sundayBased]

    /** "6 Apr" */
    fun dayMonth(date: LocalDate): String = "${date.day} ${month(date)}"

    /** "6 Apr 2026" */
    fun dayMonthYear(date: LocalDate): String = "${dayMonth(date)} ${date.year}"

    /** "Oct 2026", the month header's run. */
    fun monthYear(date: LocalDate): String = "${month(date)} ${date.year}"

    /** A band's name, for the header strip and Jump to. */
    fun band(band: DoodBand): String = when (band) {
        DoodBand.Prep -> str(S.desktop_csync_prep)
        DoodBand.Shoot -> str(S.dm_ds_phase_shoot)
        DoodBand.Post -> str(S.txt_post)
    }

    /** A column's day label: Shoot Day 4, Shoot Days 3–7, Prep W2, Post W1, Rest day. */
    fun label(phase: DoodPhase?): String = when (phase?.kind) {
        null -> ""
        DoodPhaseKind.Shoot -> phase.to
            ?.let { str(S.desktop_cdood_shoot_days, phase.n ?: 0, it) }
            ?: str(S.desktop_cdood_shoot_day, phase.n ?: 0)
        DoodPhaseKind.Prep -> str(S.desktop_cdood_prep_week, phase.n ?: 0)
        DoodPhaseKind.Post -> str(S.desktop_cdood_post_week, phase.n ?: 0)
        DoodPhaseKind.Rest -> str(S.desktop_cdood_rest_day)
    }

    /**
     * What a bar says. In Days view the code alone; in Weeks view the week's
     * worked days with it — "SW 3", "5W", "WF 2" — counted against the week's
     * working days.
     */
    fun bar(code: DayCode, booked: Int, weeks: Boolean): String = when {
        code == DayCode.Off -> str(S.desktop_cdood_off)
        code == DayCode.Hol -> str(S.desktop_cdood_hol)
        code == DayCode.Single -> str(S.desktop_cdood_swf)
        !weeks -> when (code) {
            DayCode.Start -> str(S.desktop_cdood_sw)
            DayCode.Finish -> str(S.desktop_cdood_wf)
            else -> str(S.desktop_cdood_w)
        }
        code == DayCode.Start -> "${str(S.desktop_cdood_sw)} $booked"
        code == DayCode.Finish -> "${str(S.desktop_cdood_wf)} $booked"
        else -> "$booked${str(S.desktop_cdood_w)}"
    }

    /** The hover card's "when" line: the day (or the week), the holiday, the phase. */
    fun whenLine(column: DoodColumn, weeks: Boolean): String = buildString {
        if (weeks) {
            append("${dayMonth(column.start)} – ${dayMonth(column.end)}")
        } else {
            append("${weekday(column.start)} ${dayMonth(column.start)}")
        }
        column.holiday?.let { name ->
            append(" · ")
            append(name.ifBlank { str(S.desktop_holiday) })
        }
        label(column.label).takeIf { it.isNotBlank() }?.let { append(" · $it") }
    }

    /** The hover card's state line for one person on one column. */
    fun stateLine(column: DoodColumn, cell: DoodCell, weeks: Boolean): String =
        if (weeks) weekState(column, cell) else dayState(column, cell.code)

    private fun dayState(column: DoodColumn, code: DayCode): String = when (code) {
        DayCode.Single -> str(S.desktop_cdood_tip_single_day)
        DayCode.Start -> str(S.desktop_cdood_tip_start)
        DayCode.Finish -> str(S.desktop_cdood_tip_finish)
        DayCode.Work -> str(S.desktop_cdood_tip_work)
        DayCode.Hol -> str(S.desktop_holiday)
        DayCode.Off -> str(S.desktop_cdood_legend_day_off)
        // Outside a booking the column's own day-off shading is all there is
        // to say — the cell is blank by design (§2.7).
        DayCode.Blank -> when {
            column.holiday != null -> str(S.desktop_holiday)
            column.off -> str(S.desktop_cdood_legend_day_off)
            else -> str(S.desktop_cdood_tip_not_booked)
        }
    }

    private fun weekState(column: DoodColumn, cell: DoodCell): String {
        if (cell.booked == 0) return str(S.desktop_cdood_tip_not_booked)
        return buildString {
            append(str(S.desktop_cdood_tip_days_booked, cell.booked, column.workdays))
            if (cell.code == DayCode.Start || cell.code == DayCode.Single) {
                append(" · ${str(S.desktop_cdood_tip_starts)}")
            }
            if (cell.code == DayCode.Finish || cell.code == DayCode.Single) {
                append(" · ${str(S.desktop_cdood_tip_finishes)}")
            }
        }
    }
}
