package com.zillit.desktop.feature.esignature.domain

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The module's dates, as the web's `dateFmt.js` spells them: every
 * user-facing date reads `DD/MM/YYYY`; stamps add `· h:mm AM`. The dash
 * stands in for a missing moment — never a 1970.
 */
object EsignFormat {
    const val DASH = "—"

    /** `28/05/2026`. */
    fun date(millis: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val local = millis?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(zone) } ?: return DASH
        return "${pad(local.day)}/${pad((local.month.ordinal + 1))}/${local.year}"
    }

    /** `28/05/2026 · 3:18 PM`. */
    fun dateTime(millis: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val local = millis?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(zone) } ?: return DASH
        return "${date(millis, zone)} · ${time(local.hour, local.minute)}"
    }

    /** `3:18 PM`. */
    fun time(millis: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val local = millis?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(zone) } ?: return DASH
        return time(local.hour, local.minute)
    }

    /** Today as the sign builder's `DD/MM/YYYY`, the web's dateSigned default. */
    fun today(now: Long = Clock.System.now().toEpochMilliseconds()): String = date(now)

    /** Today as the wire's ISO date, for a signer-picked date field. */
    fun todayIso(
        now: Long = Clock.System.now().toEpochMilliseconds(),
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): String {
        val local = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone)
        return "${local.year}-${pad((local.month.ordinal + 1))}-${pad(local.day)}"
    }

    /** `2 min ago`, `3 h ago`, `yesterday`, or the date — for activity lines. */
    fun relative(millis: Long?, now: Long = Clock.System.now().toEpochMilliseconds()): String {
        millis ?: return DASH
        val delta = now - millis
        return when {
            delta < MINUTE -> "just now"
            delta < HOUR -> "${delta / MINUTE} min ago"
            delta < DAY -> "${delta / HOUR} h ago"
            delta < 2 * DAY -> "yesterday"
            else -> date(millis)
        }
    }

    /** `184 KB`, `1.2 MB`. */
    fun size(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < KB -> "$bytes B"
        bytes < MB -> "${bytes / KB} KB"
        else -> "${(bytes * TENTH / MB).toInt() / TENTH_D} MB"
    }

    private fun time(hour: Int, minute: Int): String {
        val h12 = when (hour % HALF_DAY) { 0 -> HALF_DAY; else -> hour % HALF_DAY }
        val suffix = if (hour < HALF_DAY) "AM" else "PM"
        return "$h12:${pad(minute)} $suffix"
    }

    private fun pad(n: Int): String = n.toString().padStart(2, '0')

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    private const val HALF_DAY = 12
    private const val KB = 1024L
    private const val MB = KB * KB
    private const val TENTH = 10L
    private const val TENTH_D = 10.0
}
