package com.zillit.desktop.feature.callsheet.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Time cells on the wire are epoch milliseconds anchored to the sheet's date;
 * on screen they are `HH:mm`. This is the only place the two meet.
 */
object SheetTime {

    private val CLOCK = Regex("""([01]?\d|2[0-3]):([0-5]\d)""")

    /** Epoch-ms → local `HH:mm`; anything else passes through untouched. */
    fun display(raw: String, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val epoch = raw.toLongOrNull() ?: return raw
        if (epoch < EPOCH_MS_MIN || epoch >= EPOCH_MS_MAX) return raw
        val time = Instant.fromEpochMilliseconds(epoch).toLocalDateTime(zone)
        return "${time.hour.pad()}:${time.minute.pad()}"
    }

    /**
     * `HH:mm` → epoch-ms string anchored to [sheetDateMs]'s local calendar
     * day; blank stays blank, and anything unparseable passes through so a
     * legacy free-text value ("O/C") survives an edit round-trip.
     */
    fun encode(
        input: String,
        sheetDateMs: Long?,
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ""
        val match = CLOCK.matchEntire(trimmed) ?: return trimmed
        val anchor = sheetDateMs ?: return trimmed
        val day = Instant.fromEpochMilliseconds(anchor).toLocalDateTime(zone).date
        val moment = LocalDateTime(
            year = day.year,
            month = day.month,
            day = day.day,
            hour = match.groupValues[1].toInt(),
            minute = match.groupValues[2].toInt(),
        )
        return moment.toInstant(zone).toEpochMilliseconds().toString()
    }

    private fun Int.pad(): String = toString().padStart(2, '0')

    /** The plausible-epoch window: 2001–2286. Outside it, a number is a number. */
    private const val EPOCH_MS_MIN = 1_000_000_000_000L
    private const val EPOCH_MS_MAX = 10_000_000_000_000L
}
