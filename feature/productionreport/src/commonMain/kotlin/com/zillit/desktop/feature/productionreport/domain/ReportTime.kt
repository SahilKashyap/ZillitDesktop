package com.zillit.desktop.feature.productionreport.domain

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The wall-clock wire codec — a transcription of the web's pinned behaviour
 * (`__tests__/wallClockWire.test.js`).
 *
 * Writes are strings only: time is 24-hour `HH:mm`, date is `YYYY-MM-DD`,
 * no epoch, no ISO, no offset. A 06:30 crew call is 06:30 on set regardless
 * of who opens the report. Legacy epoch-ms values (from older writers) are
 * folded to the LOCAL wall clock on read and never re-emitted.
 */
object ReportTime {

    private val CLOCK = Regex("""([01]?\d|2[0-3]):([0-5]\d)""")
    private val YMD = Regex("""\d{4}-\d{2}-\d{2}""")

    /**
     * A valid clock passes (zero-padded), a legacy epoch folds to local
     * `HH:mm`, and anything else — `""`, `O/C`, `Per HOD` — becomes `""`:
     * the codec never invents a time. Idempotent.
     */
    fun toWireTime(value: String, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val trimmed = value.trim()
        CLOCK.matchEntire(trimmed)?.let { match ->
            return "${match.groupValues[1].padStart(2, '0')}:${match.groupValues[2]}"
        }
        legacyEpoch(trimmed)?.let { epoch ->
            val time = Instant.fromEpochMilliseconds(epoch).toLocalDateTime(zone)
            return "${time.hour.pad()}:${time.minute.pad()}"
        }
        return ""
    }

    /** `YYYY-MM-DD` passes; a legacy epoch folds to the local calendar date. */
    fun toWireDate(value: String, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val trimmed = value.trim()
        if (YMD.matchEntire(trimmed) != null) return trimmed
        legacyEpoch(trimmed)?.let { epoch ->
            val date = Instant.fromEpochMilliseconds(epoch).toLocalDateTime(zone).date
            return "${date.year}-${date.monthNumber.pad()}-${date.dayOfMonth.pad()}"
        }
        return ""
    }

    /**
     * The In/Out column is mode-prefixed, not a bare clock: exactly one of
     * `Per HOD` | `O/C` | `Time:HH:mm` | `Other:<free text>` | `""`. Free
     * clock input becomes a `Time:` entry; the two fixed modes pass through
     * case-insensitively; anything else is preserved as `Other:`.
     */
    fun encodeInOut(input: String, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val trimmed = input.trim()
        return when {
            trimmed.isEmpty() -> ""
            trimmed.equals("Per HOD", ignoreCase = true) -> "Per HOD"
            trimmed.equals("O/C", ignoreCase = true) -> "O/C"
            trimmed.startsWith("Other:") -> trimmed
            trimmed.startsWith("Time:") -> "Time:" + toWireTime(trimmed.removePrefix("Time:"), zone)
            toWireTime(trimmed, zone).isNotEmpty() -> "Time:" + toWireTime(trimmed, zone)
            else -> "Other:$trimmed"
        }
    }

    /** The readable face of [encodeInOut] — what the editor shows. */
    fun displayInOut(stored: String, zone: TimeZone = TimeZone.currentSystemDefault()): String =
        when {
            stored.startsWith("Time:") -> toWireTime(stored.removePrefix("Time:"), zone)
            stored.startsWith("Other:") -> stored.removePrefix("Other:")
            else -> stored
        }

    /** Whether [value] is an epoch-ms number from an older writer — the only thing the codec rewrites. */
    fun isLegacyEpoch(value: String): Boolean = legacyEpoch(value.trim()) != null

    /** Today's local calendar date in wire shape — `todayYmd()`. */
    fun todayYmd(nowMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val date = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date
        return "${date.year}-${date.monthNumber.pad()}-${date.dayOfMonth.pad()}"
    }

    /**
     * The document header's date — `Wednesday 5th March, 2025`. A wire date
     * is a LOCAL calendar day (the web parsed it as UTC midnight and showed the
     * day before west of UTC); a legacy epoch folds to local; blank is `""`;
     * anything unreadable shows as written.
     */
    fun headerDate(value: String, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val ymd = toWireDate(value, zone)
        if (ymd.isEmpty()) return value.trim()
        val date = runCatching { LocalDate.parse(ymd) }.getOrNull() ?: return value.trim()
        val day = date.dayOfMonth
        val suffix = when {
            day in TEENS -> "th"
            day % DECADE == 1 -> "st"
            day % DECADE == 2 -> "nd"
            day % DECADE == THIRD -> "rd"
            else -> "th"
        }
        val weekday = date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        val month = date.month.name.lowercase().replaceFirstChar { it.uppercase() }
        return "$weekday $day$suffix $month, ${date.year}"
    }

    private val TEENS = 11..13
    private const val DECADE = 10
    private const val THIRD = 3

    private fun legacyEpoch(value: String): Long? =
        value.toLongOrNull()?.takeIf { it in EPOCH_MS_MIN until EPOCH_MS_MAX }

    private fun Int.pad(): String = toString().padStart(2, '0')

    /** The plausible-epoch window: 2001–2286. Outside it, a number is a number. */
    private const val EPOCH_MS_MIN = 1_000_000_000_000L
    private const val EPOCH_MS_MAX = 10_000_000_000_000L
}
