package com.zillit.desktop.feature.cardexpenses.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

/**
 * The card module's one date round trip: `YYYY-MM-DD` ↔ epoch milliseconds.
 *
 * **UTC midnight, not local midnight.** The web's date fields hand
 * `new Date("2026-08-12").getTime()` to the wire, which JavaScript parses as
 * UTC midnight, and every card record on every production was written that
 * way. Stamping local midnight instead would put a receipt on the day before
 * for anyone west of Greenwich and the day after for anyone far enough east —
 * and the discrepancy only shows up when a period is closed.
 *
 * Reading back is deliberately *not* the mirror image: [EpochDate.date] renders
 * in the machine's zone, matching what the web draws. A receipt's stored day
 * and its displayed day can therefore differ by one in an extreme zone, which
 * is a known property of the whole platform rather than of this module.
 */
object CardDates {

    /** `2026-08-12` → the millisecond at UTC midnight on that day. */
    fun toMillis(iso: String?): Long? {
        val trimmed = iso?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val date = runCatching { LocalDate.parse(trimmed) }.getOrNull() ?: return null
        return date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
    }

    /** The inverse, for seeding a date field from a stored stamp. */
    fun toIso(millis: Long?): String {
        val value = millis?.takeIf { it > 0 } ?: return ""
        val days = value.floorDiv(MILLIS_PER_DAY)
        return LocalDate.fromEpochDays(days.toInt()).toString()
    }

    private const val MILLIS_PER_DAY = 86_400_000L
}
