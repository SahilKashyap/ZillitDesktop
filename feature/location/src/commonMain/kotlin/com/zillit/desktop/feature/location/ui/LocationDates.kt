package com.zillit.desktop.feature.location.ui

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

internal object LocationDates {
    /** "Aug 20, 2026" from epoch ms; "—" for none. */
    fun date(epochMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        if (epochMs <= 0) return "—"
        val d = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).date
        val month = d.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(ABBREV)
        return "$month ${d.dayOfMonth.toString().padStart(2, '0')}, ${d.year}"
    }

    private const val ABBREV = 3
}
