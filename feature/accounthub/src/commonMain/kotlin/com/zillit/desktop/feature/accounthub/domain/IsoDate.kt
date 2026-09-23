package com.zillit.desktop.feature.accounthub.domain

/**
 * `YYYY-MM-DD` to epoch millis at UTC midnight.
 *
 * Two callers need the same answer: the schedule's date fields, which are typed
 * in this form, and the reader for schedules persisted before the server moved
 * to epoch millis. Two implementations would be two off-by-one bugs.
 *
 * Done by hand rather than with a date library because this module has no
 * platform date dependency and the format is fixed. Anything that is not three
 * integers in that order is left unread rather than guessed at — a schedule
 * that silently reads as unset would pre-fill every new deal memo with blanks.
 */
object IsoDate {

    fun toEpochMillis(text: String?): Long? {
        val parts = text?.trim()?.takeIf { it.isNotEmpty() }?.split('-') ?: return null
        if (parts.size != PARTS) return null
        val year = parts[0].toIntOrNull()
        val month = parts[1].toIntOrNull()?.takeIf { it in 1..MONTHS }
        val day = parts[2].take(DAY_DIGITS).toIntOrNull()?.takeIf { it in 1..MAX_DAY }
        if (year == null || month == null || day == null) return null
        return daysFromCivil(year, month, day) * MILLIS_PER_DAY
    }

    /**
     * Epoch millis to `YYYY-MM-DD`, read **in UTC** — the inverse of
     * [toEpochMillis], and what the web does (`toISOString().slice(0, 10)`).
     *
     * Reading the stored UTC midnight in the machine's zone showed every
     * schedule date a day early west of Greenwich, and saving it back moved
     * the stored date by that day too.
     */
    fun fromEpochMillis(millis: Long?): String {
        val value = millis ?: return ""
        val days = value.floorDiv(MILLIS_PER_DAY)
        val (year, month, day) = civilFromDays(days)
        return "${year.toString().padStart(YEAR_DIGITS, '0')}-${month.pad()}-${day.pad()}"
    }

    /** Whether [text] is blank or a whole, readable date — anything else is half-typed. */
    fun isBlankOrValid(text: String): Boolean = text.isBlank() || toEpochMillis(text) != null

    private fun Int.pad(): String = toString().padStart(2, '0')

    /** The inverse of [daysFromCivil], same derivation (Hinnant's `civil_from_days`). */
    @Suppress("MagicNumber")
    private fun civilFromDays(daysSinceEpoch: Long): Triple<Int, Int, Int> {
        val z = daysSinceEpoch + EPOCH_OFFSET_DAYS
        val era = z.floorDiv(DAYS_PER_ERA)
        val dayOfEra = z - era * DAYS_PER_ERA
        val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / DAYS_PER_YEAR
        val dayOfYear = dayOfEra - (DAYS_PER_YEAR * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val monthIndex = (5 * dayOfYear + 2) / DAYS_PER_5_MONTHS
        val day = (dayOfYear - (DAYS_PER_5_MONTHS * monthIndex + 2) / 5 + 1).toInt()
        val month = (if (monthIndex < 10) monthIndex + 3 else monthIndex - 9).toInt()
        val year = (yearOfEra + era * ERA_YEARS).toInt() + if (month <= 2) 1 else 0
        return Triple(year, month, day)
    }

    /**
     * Days from 1970-01-01, by Howard Hinnant's algorithm.
     *
     * Exact for every proleptic-Gregorian date and needs no lookup table, which
     * is what makes it worth transcribing rather than approximating with a
     * 365.25 multiplier that drifts a day around leap years.
     *
     * The bare numbers are left as they are: they are the algorithm's own
     * constants — the 4/100/400 leap cadence and the month-shift arithmetic —
     * and naming them individually would obscure a published derivation rather
     * than explain it.
     */
    @Suppress("MagicNumber")
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val shifted = if (month <= 2) year - 1 else year
        val era = (if (shifted >= 0) shifted else shifted - ERA_YEARS + 1) / ERA_YEARS
        val yearOfEra = shifted - era * ERA_YEARS
        val monthIndex = if (month > 2) month - 3 else month + 9
        val dayOfYear = (DAYS_PER_5_MONTHS * monthIndex + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * DAYS_PER_YEAR + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era.toLong() * DAYS_PER_ERA + dayOfEra - EPOCH_OFFSET_DAYS
    }

    private const val PARTS = 3
    private const val MONTHS = 12
    private const val MAX_DAY = 31

    /** `31T00:00:00Z` and `31` must both read as the 31st. */
    private const val DAY_DIGITS = 2
    private const val ERA_YEARS = 400
    private const val DAYS_PER_ERA = 146_097L
    private const val DAYS_PER_YEAR = 365
    private const val DAYS_PER_5_MONTHS = 153
    private const val EPOCH_OFFSET_DAYS = 719_468L
    private const val MILLIS_PER_DAY = 86_400_000L
    private const val YEAR_DIGITS = 4
}
