package com.zillit.desktop.feature.payroll.data

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Reading a production-report time — the web's `prDayTimeToHHMM`.
 *
 * The same field arrives as three different things depending on which service
 * wrote it and when: epoch milliseconds (read in the report's own zone, since
 * those are real instants), an ISO stamp, or a clock string in 12- or 24-hour
 * form. All three answer `HH:mm`; anything that is not a time — an em dash,
 * `TBD`, a bare date — answers null, so a cell stays empty rather than
 * inventing a time nobody recorded.
 */
internal object ReportTime {

    private val SENTINELS = setOf(
        "", "-", "--", "—", "–", "--:--", "n/a", "na", "tbd", "tba", "null", "undefined", "nan",
    )

    /** `7 : 30` reads as `7:30`; everything else collapses to single spaces. */
    private val AROUND_COLON = Regex("\\s*:\\s*")
    private val WHITESPACE = Regex("\\s+")
    private val ISO_ZONED = Regex(
        "^\\d{4}-\\d{2}-\\d{2}[Tt ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|z|[+-]\\d{2}:?\\d{2})$",
    )
    private val ISO_LOCAL = Regex("^\\d{4}-\\d{2}-\\d{2}[Tt ](\\d{2}):(\\d{2})(?::\\d{2}(?:\\.\\d+)?)?$")
    private val DATE_ONLY = Regex("^\\d{4}-\\d{2}(-\\d{2})?$")
    private val DIGITS = Regex("^\\d+$")
    private val CLOCK = Regex("^(\\d{1,2}):(\\d{2})\\s*([AaPp][Mm])?$")

    /** An epoch-ms string is only an epoch when it is long enough to be one. */
    private const val EPOCH_DIGITS = 13
    private const val HOURS_IN_HALF_DAY = 12
    private const val MAX_HOUR = 23
    private const val MAX_MINUTE = 59

    /** `HH:mm`, or null when [value] does not state a time. */
    fun hhmm(value: String?, zoneId: String?): String? {
        val raw = value?.replace(AROUND_COLON, ":")?.replace(WHITESPACE, " ")?.trim().orEmpty()
        return when {
            raw.isEmpty() || raw.lowercase() in SENTINELS -> null
            // An all-digit value is an epoch only when it is long enough to be
            // one; anything shorter is an id or a code, not a time of day.
            DIGITS.matches(raw) ->
                raw.takeIf { it.length >= EPOCH_DIGITS }?.toLongOrNull()?.let { epoch(it, zoneId) }

            ISO_ZONED.matches(raw) -> runCatching { Instant.parse(raw.replace(' ', 'T')) }.getOrNull()
                ?.let { epoch(it.toEpochMilliseconds(), zoneId) }

            // No zone on an ISO stamp means an authored wall clock: read it
            // verbatim rather than shifting it into anyone else's zone.
            ISO_LOCAL.matches(raw) -> ISO_LOCAL.matchEntire(raw)?.destructured
                ?.let { (hour, minute) -> clock(hour.toInt(), minute.toInt(), meridiem = null) }

            DATE_ONLY.matches(raw) -> null
            else -> CLOCK.matchEntire(raw.replace(" ", ""))?.destructured
                ?.let { (hour, minute, meridiem) ->
                    clock(hour.toInt(), minute.toInt(), meridiem.takeIf { it.isNotEmpty() })
                }
        }
    }

    /** `HH:mm` for an epoch, read in the report's own zone (UTC when it names none). */
    fun epoch(millis: Long, zoneId: String?): String? {
        if (millis <= 0) return null
        val zone = zoneId?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { TimeZone.of(it) }.getOrNull() }
            ?: TimeZone.UTC
        val local = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)
        return "${local.hour.pad()}:${local.minute.pad()}"
    }

    private fun clock(hour: Int, minute: Int, meridiem: String?): String? {
        if (minute > MAX_MINUTE) return null
        val lower = meridiem?.lowercase()
        val resolved = when {
            lower == null -> hour.takeIf { it <= MAX_HOUR } ?: return null
            hour !in 1..HOURS_IN_HALF_DAY -> return null
            lower == "pm" -> if (hour == HOURS_IN_HALF_DAY) hour else hour + HOURS_IN_HALF_DAY
            else -> if (hour == HOURS_IN_HALF_DAY) 0 else hour
        }
        return "${resolved.pad()}:${minute.pad()}"
    }

    private fun Int.pad(): String = toString().padStart(2, '0')
}
