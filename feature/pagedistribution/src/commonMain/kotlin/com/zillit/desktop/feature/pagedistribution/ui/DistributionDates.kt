package com.zillit.desktop.feature.pagedistribution.ui

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** The web's `MMM DD, YYYY [at] hh:mm A` and `MMM DD, YYYY`, local time. */
internal object DistributionDates {
    fun dateTime(epochMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        if (epochMs <= 0) return "—"
        val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)
        val hour12 = when (t.hour % HALF_DAY) { 0 -> HALF_DAY; else -> t.hour % HALF_DAY }
        val meridiem = if (t.hour < HALF_DAY) "AM" else "PM"
        val clock = "${hour12.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
        return "${date(epochMs, zone)} at $clock $meridiem"
    }

    fun date(epochMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        if (epochMs <= 0) return ""
        val d = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).date
        val month = d.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(ABBREV)
        return "$month ${d.dayOfMonth.toString().padStart(2, '0')}, ${d.year}"
    }

    private const val HALF_DAY = 12
    private const val ABBREV = 3
}
