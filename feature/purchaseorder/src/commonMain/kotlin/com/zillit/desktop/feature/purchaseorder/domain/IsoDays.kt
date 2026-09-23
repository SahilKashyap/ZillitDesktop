@file:Suppress("MagicNumber")
// Every number below is part of Howard Hinnant's civil-from-days algorithm —
// 146097 days per 400-year era, 719468 days from 0000-03-01 to the epoch, the
// 153/5 month-length trick. Naming them individually would obscure the
// algorithm rather than explain it; the reference is `chrono`'s `days_from_civil`.

package com.zillit.desktop.feature.purchaseorder.domain

/**
 * Whole-day arithmetic on ISO dates, without a calendar library.
 *
 * The purchase order tool needs three things a rental window asks for: how many
 * days between two dates, the day N days after one, and a calendar day as the
 * UTC-midnight epoch this backend stores. All three are whole days, so the
 * proleptic Gregorian day number is the whole model — and this module does not
 * depend on kotlinx-datetime.
 */

/**
 * An ISO day (`2026-03-01`) as a day number, or null when it is not one.
 *
 * Four early returns, one per way the string can fail to be a date. Folding
 * them into one expression would answer null for all four without saying which,
 * and this is the function a mis-typed rental window passes through.
 */
@Suppress("ReturnCount")
internal fun String.isoDayNumber(): Int? {
    val parts = take(ISO_LENGTH).split('-')
    if (parts.size != 3) return null
    val year = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull() ?: return null
    val day = parts[2].toIntOrNull() ?: return null
    return daysFromCivil(year, month, day)
}

/** A day number back to an ISO day. */
internal fun Int.toIsoDay(): String {
    val (year, month, day) = civilFromDays(this)
    return "$year-${month.twoDigits()}-${day.twoDigits()}"
}

/**
 * A calendar day as UTC midnight, in epoch milliseconds.
 *
 * UTC, never local: every date on this backend is a UTC-midnight epoch, and a
 * local-midnight conversion shifts the day by one west of Greenwich — which
 * lands a 1 March order in February's accounting period.
 */
internal fun utcMidnight(year: Int, month: Int, day: Int): Long =
    daysFromCivil(year, month, day).toLong() * MILLIS_PER_DAY

/** An ISO day as UTC midnight, or null when the string is not a date. */
internal fun String.isoDayToUtcMidnight(): Long? = isoDayNumber()?.let { it.toLong() * MILLIS_PER_DAY }

/**
 * Epoch milliseconds as the UTC calendar day they fall in — the inverse of
 * [isoDayToUtcMidnight], and the day the cost-report lock is compared on.
 */
internal fun Long.utcIsoDay(): String {
    val days = this / MILLIS_PER_DAY - if (this % MILLIS_PER_DAY < 0) 1 else 0
    return days.toInt().toIsoDay()
}

private fun Int.twoDigits() = toString().padStart(2, '0')

private fun daysFromCivil(year: Int, month: Int, day: Int): Int {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yearOfEra = y - era * 400
    val monthIndex = (month + 9) % 12
    val dayOfYear = (153 * monthIndex + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146097 + dayOfEra - 719468
}

private fun civilFromDays(days: Int): Triple<Int, Int, Int> {
    val z = days + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val dayOfEra = z - era * 146097
    val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365
    val y = yearOfEra + era * 400
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val monthIndex = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * monthIndex + 2) / 5 + 1
    val month = if (monthIndex < 10) monthIndex + 3 else monthIndex - 9
    return Triple(if (month <= 2) y + 1 else y, month, day)
}

private const val ISO_LENGTH = 10
private const val MILLIS_PER_DAY = 86_400_000L
