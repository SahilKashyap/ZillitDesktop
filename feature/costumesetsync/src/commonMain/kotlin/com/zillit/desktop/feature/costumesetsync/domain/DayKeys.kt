package com.zillit.desktop.feature.costumesetsync.domain

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * `YYYY-MM-DD` day keys, the way the web's `lib/sceneDraft.js` (`dateKey`, `dateMs`)
 * and the continuity / report screens name a calendar day. The service stores a
 * shoot date as epoch ms at LOCAL midnight, and `0` means "not set".
 */
object DayKeys {
    /** Epoch ms → `YYYY-MM-DD` in [zone]; empty for an unset (0) date. */
    fun of(ms: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        if (ms == null || ms == 0L) return ""
        val d = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
        return "${d.year}-${two(d.monthNumber)}-${two(d.dayOfMonth)}"
    }

    /** `YYYY-MM-DD` → epoch ms at local midnight; 0 for anything that is not a day. */
    fun toMs(key: String, zone: TimeZone = TimeZone.currentSystemDefault()): Long =
        parse(key)?.atStartOfDayIn(zone)?.toEpochMilliseconds() ?: 0L

    fun parse(key: String): LocalDate? = runCatching { LocalDate.parse(key.trim()) }.getOrNull()

    /** `Tue 29 Sep` (the on-set heading's `ddd DD MMM`). */
    fun short(key: String): String = parse(key)
        ?.let { "${weekday(it.dayOfWeek, long = false)} ${two(it.dayOfMonth)} ${month(it.month, long = false)}" }
        .orEmpty()

    /** `Tue, 29 Sep 2026` (the book's `ddd, DD MMM YYYY`). */
    fun medium(key: String): String =
        parse(key)
            ?.let {
                "${weekday(it.dayOfWeek, long = false)}, ${two(it.dayOfMonth)} " +
                    "${month(it.month, long = false)} ${it.year}"
            }
            .orEmpty()

    /** `Tuesday, 29 September 2026` (the printed book's cover). */
    fun long(key: String): String =
        parse(key)
            ?.let {
                "${weekday(it.dayOfWeek, long = true)}, ${two(it.dayOfMonth)} " +
                    "${month(it.month, long = true)} ${it.year}"
            }
            .orEmpty()

    /** `DD/MM/YYYY`, the web date pickers' display. */
    fun slashed(key: String): String = parse(key)
        ?.let { "${two(it.dayOfMonth)}/${two(it.monthNumber)}/${it.year}" }
        .orEmpty()

    private fun two(n: Int) = n.toString().padStart(2, '0')

    private fun weekday(day: DayOfWeek, long: Boolean): String =
        day.name.lowercase().replaceFirstChar { it.uppercase() }.let { if (long) it else it.take(SHORT) }

    private fun month(month: Month, long: Boolean): String =
        month.name.lowercase().replaceFirstChar { it.uppercase() }.let { if (long) it else it.take(SHORT) }

    private const val SHORT = 3
}
