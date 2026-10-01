package com.zillit.desktop.feature.costumesetsync.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.roundToLong

/** Formatting for Costumes & Set Sync — the web's `lib/format.js`. The service sends SCREAMING_SNAKE enums and epoch-millisecond dates. */

/** `DRY_CLEANING` → `Dry Cleaning`: every word capitalised, as the reference. Used for any enum the backend has no label for. */
fun humanize(value: String?): String {
    if (value.isNullOrBlank()) return ""
    return value.replace('_', ' ').lowercase().split(' ').joinToString(" ") { word ->
        word.replaceFirstChar { it.uppercase() }
    }
}

/**
 * Epoch ms → a local date-time, or null for anything unset. The service uses
 * `0` — not null — for a timestamp that has not happened yet (a fresh ticket's
 * `started_at`), and read as an epoch that prints "1 Jan 1970". Nothing in a
 * production is dated 1970, so `0` means "not yet".
 */
fun localTime(ms: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): LocalDateTime? {
    if (ms == null || ms == 0L) return null
    return Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
}

private fun Month.short(): String = name.take(SHORT).lowercase().replaceFirstChar { it.uppercase() }

private fun two(n: Int) = n.toString().padStart(2, '0')

/** `12 Mar 2026`; empty for anything unset, never a bogus date. */
fun fmtDate(ms: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String =
    localTime(ms, zone)?.let { "${it.dayOfMonth} ${it.month.short()} ${it.year}" }.orEmpty()

/** `12 Mar 2026, 14:05`. */
fun fmtDateTime(ms: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String =
    localTime(ms, zone)?.let { "${it.dayOfMonth} ${it.month.short()} ${it.year}, ${two(it.hour)}:${two(it.minute)}" }.orEmpty()

/** `14:05`. */
fun fmtTime(ms: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String =
    localTime(ms, zone)?.let { "${two(it.hour)}:${two(it.minute)}" }.orEmpty()

/** `2026-10-01` → `Thu, 1 Oct 2026` (the dashboard's heading date); the input itself when it does not parse. */
fun longDay(ymd: String): String {
    val date = runCatching { kotlinx.datetime.LocalDate.parse(ymd.take(DAY_CHARS)) }.getOrNull() ?: return ymd
    val weekday = date.dayOfWeek.name.take(SHORT).lowercase().replaceFirstChar { it.uppercase() }
    return "$weekday, ${date.dayOfMonth} ${date.month.short()} ${date.year}"
}

private const val DAY_CHARS = 10

/** Today as `YYYY-MM-DD`, which the dashboard and report `date` params take. */
fun todayParam(now: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val d = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone)
    return "${d.year}-${two(d.monthNumber)}-${two(d.dayOfMonth)}"
}

/**
 * Money in the production's currency, whole units, grouped ("₹1,00,000" with
 * Indian grouping for INR, "£58,450"); "—" when there is no value. The
 * reference's `fmtMoney`. Only where the caller knows the user may see it.
 */
fun fmtMoney(value: Double?, currency: String = ""): String {
    if (value == null || value.isNaN()) return DASH
    val whole = value.roundToLong()
    val digits = group(abs(whole).toString(), indian = currency == "INR")
    val sign = if (whole < 0) "-" else ""
    val symbol = currencySymbol(currency)
    return when {
        currency.isBlank() -> "$sign$digits"
        symbol != null -> "$sign$symbol$digits"
        else -> "$sign$currency $digits"
    }
}

private fun group(digits: String, indian: Boolean): String {
    if (digits.length <= GROUP) return digits
    val head = digits.dropLast(GROUP)
    val tail = digits.takeLast(GROUP)
    val size = if (indian) 2 else GROUP
    val grouped = head.reversed().chunked(size).joinToString(",").reversed()
    return "$grouped,$tail"
}

private fun currencySymbol(code: String): String? = when (code) {
    "INR" -> "₹"
    "GBP" -> "£"
    "USD" -> "$"
    "EUR" -> "€"
    "JPY" -> "¥"
    else -> null
}

const val DASH = "—"

/** How a status reads as a badge colour — the reference's `tone()`, value for value. */
enum class Tone { Ok, Info, Warn, Danger, Accent, Muted }

fun statusTone(status: String?): Tone = when (status) {
    "AVAILABLE", "READY", "COMPLETED", "FITTED", "PASS", "SHOT", "FOUND", "REPAIRED", "RETURNED", "OK", "SUCCESS" -> Tone.Ok
    "ISSUED", "ON_SET", "CLEANING", "RECEIVED", "DRYING", "IRONING", "IN_PROGRESS", "SHOOTING", "PICKED_UP", "INFO", "SCHEDULED" -> Tone.Info
    "ALTERATION", "ALTERATION_REQUIRED", "QUALITY_CHECK", "HIGH", "WARNING", "REPAIRING", "PENDING", "BOOKED", "REQUESTED", "ASSIGNED", "DUE", "PARTIAL" -> Tone.Warn
    "MISSING", "DAMAGED", "URGENT", "CRITICAL", "FAIL", "REJECTED", "OVERDUE", "OPEN", "WRITTEN_OFF" -> Tone.Danger
    "LEAD" -> Tone.Accent
    else -> Tone.Muted
}

/** True when a deadline has passed and the ticket is still open. */
fun isOverdue(deadlineMs: Long?, status: String?, closed: Collection<String> = emptyList(), now: Long): Boolean {
    if (deadlineMs == null || deadlineMs == 0L || status in closed) return false
    return deadlineMs < now
}

/** Free-text match across a record's fields; an empty needle matches everything. */
fun matches(needle: String, vararg fields: String?): Boolean {
    val q = needle.trim().lowercase()
    if (q.isEmpty()) return true
    return fields.any { !it.isNullOrEmpty() && it.lowercase().contains(q) }
}

private const val SHORT = 3
private const val GROUP = 3
