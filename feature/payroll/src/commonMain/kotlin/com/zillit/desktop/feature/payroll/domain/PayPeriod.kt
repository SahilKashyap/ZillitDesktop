package com.zillit.desktop.feature.payroll.domain

/**
 * Pay-period weeks, in UTC — the web's `startOfPeriodTz(…, "UTC", …)`.
 *
 * ## Why UTC and not the machine's zone
 *
 * Payroll derives calendar dates in UTC module-wide: `week_starting` is a
 * server match key that timecards are written under at 00:00Z. A week derived
 * in the viewer's own zone is a different number west of Greenwich, and the
 * server answers a week that does not exist with an empty list and no error.
 *
 * The period need not start on a Monday: the production's
 * `pay_period.start_day_of_week` (ISO, 1 = Monday … 7 = Sunday) decides it.
 */
object PayPeriod {
    const val MONDAY = 1
    const val DAY_MILLIS = 86_400_000L
    const val WEEK_MILLIS = 7 * DAY_MILLIS
    private const val DAYS_IN_WEEK = 7
    const val SUNDAY_ISO = 7

    /** 1970-01-01 was a Thursday — day 4 when Sunday is 0. */
    private const val EPOCH_WEEKDAY = 4

    private val MONTHS = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )
    private val WEEKDAYS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    /** The start of the period [epochMillis] falls in. */
    fun startOf(epochMillis: Long, startDay: Int): Long {
        val day = epochMillis.floorDiv(DAY_MILLIS)
        val weekday = (day + EPOCH_WEEKDAY).mod(DAYS_IN_WEEK)
        val start = if (startDay == SUNDAY_ISO) 0 else startDay.coerceIn(MONDAY, SUNDAY_ISO)
        val back = (weekday - start + DAYS_IN_WEEK).mod(DAYS_IN_WEEK)
        return (day - back) * DAY_MILLIS
    }

    /** The week [steps] periods away, snapped to the period boundary. */
    fun shift(weekStarting: Long, steps: Int, startDay: Int): Long =
        startOf(weekStarting + steps * WEEK_MILLIS, startDay)

    /** `28 Apr – 04 May 2026` — the history queue's label, spaced like the web's. */
    fun rangeLabel(weekStarting: Long): String =
        "${dayMonth(weekStarting)} – ${dayMonthYear(weekStarting + (DAYS_IN_WEEK - 1) * DAY_MILLIS)}"

    /** `28 Apr–04 May 2026` — the run and processing label, unspaced like the web's. */
    fun compactRangeLabel(weekStarting: Long): String =
        "${dayMonth(weekStarting)}–${dayMonthYear(weekStarting + (DAYS_IN_WEEK - 1) * DAY_MILLIS)}"

    /** `04 May 2026` — the week-ending date a history header prints after "W/E". */
    fun weekEnding(weekStarting: Long): String =
        dayMonthYear(weekStarting + (DAYS_IN_WEEK - 1) * DAY_MILLIS)

    /** `Mon 04 May`, read in UTC like every payroll calendar date. */
    fun dayLabel(dayMillis: Long): String {
        val day = dayMillis.floorDiv(DAY_MILLIS)
        return "${WEEKDAYS[(day + EPOCH_WEEKDAY).mod(DAYS_IN_WEEK)]} ${dayMonth(dayMillis)}"
    }

    /** `Mon`, `Tue` … for the seven days from [weekStarting]. */
    fun weekdayLetters(weekStarting: Long): List<String> = (0 until DAYS_IN_WEEK).map { offset ->
        val day = (weekStarting + offset * DAY_MILLIS).floorDiv(DAY_MILLIS)
        WEEKDAYS[(day + EPOCH_WEEKDAY).mod(DAYS_IN_WEEK)].take(1)
    }

    /** `04 May`. */
    fun dayMonth(millis: Long): String {
        val (_, month, day) = civil(millis)
        return "${day.toString().padStart(2, '0')} ${MONTHS[month - 1]}"
    }

    /** `04 May 2026`. */
    fun dayMonthYear(millis: Long): String = "${dayMonth(millis)} ${civil(millis).first}"

    /** `07:30` — a worked time, stored as UTC wall-clock, or an em dash for none. */
    fun hhmm(millis: Long?): String {
        if (millis == null || millis <= 0) return "—"
        val minutes = millis.mod(DAY_MILLIS) / MINUTE_MILLIS
        return "${(minutes / MINUTES_PER_HOUR).toString().padStart(2, '0')}:" +
            (minutes % MINUTES_PER_HOUR).toString().padStart(2, '0')
    }

    private const val MINUTE_MILLIS = 60_000L
    private const val MINUTES_PER_HOUR = 60L

    /** `2026-05-04` — what a date field holds. */
    fun isoDate(millis: Long): String {
        val (year, month, day) = civil(millis)
        return "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
    }

    /** Midnight UTC of a `YYYY-MM-DD` date, or null for anything else. */
    fun parseIsoDate(text: String?): Long? {
        val match = ISO_DATE.matchEntire(text?.trim().orEmpty()) ?: return null
        val (year, month, day) = match.destructured
        val m = month.toInt()
        val d = day.toInt()
        if (m !in 1..MONTHS.size || d !in 1..MAX_DAY) return null
        val millis = daysFromCivil(year.toInt(), m, d) * DAY_MILLIS
        return millis.takeIf { isoDate(it) == text?.trim() }
    }

    /** The day after [isoDate], as `YYYY-MM-DD` — the first date a lock leaves open. */
    fun dayAfter(isoDate: String?): String? = parseIsoDate(isoDate)?.let { isoDate(it + DAY_MILLIS) }

    private val ISO_DATE = Regex("(\\d{4})-(\\d{2})-(\\d{2})")
    private const val MAX_DAY = 31

    /** Howard Hinnant's civil-from-days, so the dates need no zone database. */
    @Suppress("MagicNumber")
    private fun civil(millis: Long): Triple<Int, Int, Int> {
        val z = millis.floorDiv(DAY_MILLIS) + 719_468
        val era = z.floorDiv(146_097)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
        return Triple(year, month, day)
    }

    @Suppress("MagicNumber")
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = y.floorDiv(400)
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }
}
