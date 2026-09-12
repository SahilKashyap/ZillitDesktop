package com.zillit.desktop.feature.invoices.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Instant

/** Money and date rendering, kept off the platform so the tests can pin it. */
object InvoiceFormat {

    /** `symbol(currency) + amount` at 2 dp with thousands separators: `£1,234.50`. */
    fun money(amount: Double?, currency: String): String {
        if (amount == null) return "—"
        return symbol(currency) + amount(amount)
    }

    fun amount(value: Double): String {
        val cents = (abs(value) * CENTS).roundToLong()
        val whole = (cents / CENTS.toLong()).toString().reversed().chunked(GROUP).joinToString(",").reversed()
        val fraction = (cents % CENTS.toLong()).toString().padStart(2, '0')
        return (if (value < 0) "-" else "") + "$whole.$fraction"
    }

    /** 2 dp, no separators — for text fields. */
    fun plain(value: Double): String = amount(value).replace(",", "")

    fun symbol(currency: String): String = when (currency.trim().uppercase()) {
        "GBP" -> "£"
        "USD" -> "$"
        "EUR" -> "€"
        "INR" -> "₹"
        "JPY" -> "¥"
        "AUD" -> "A$"
        "CAD" -> "C$"
        "NZD" -> "NZ$"
        "ZAR" -> "R"
        "" -> ""
        else -> currency.trim().uppercase() + " "
    }

    /** A date-only field (stored at UTC midnight) as `12 Aug 2026`. */
    fun date(ms: Long?): String {
        if (ms == null || ms <= 0) return "—"
        val d = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.UTC).date
        return "${d.day} ${MONTHS[d.month.ordinal]} ${d.year}"
    }

    /**
     * `Period: September 2026 · Week 2` — the sidebar's line under the title.
     *
     * The week is the week of the month, counted from the first, which is how
     * a production office numbers its weeks.
     */
    fun periodLabel(ms: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val today = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone).date
        val week = (today.day - 1) / DAYS_IN_WEEK + 1
        return "Period: ${MONTH_NAMES[today.month.ordinal]} ${today.year} · Week $week"
    }

    /** A timestamp as `12 Aug 2026, 14:05` in the local zone. */
    fun dateTime(ms: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        if (ms == null || ms <= 0) return "—"
        val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
        return "${t.day} ${MONTHS[t.month.ordinal]} ${t.year}, ${t.hour.pad()}:${t.minute.pad()}"
    }

    /** Epoch ms → `YYYY-MM-DD` (UTC), for the form fields. */
    fun toDateInput(ms: Long?): String {
        if (ms == null || ms <= 0) return ""
        val d = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.UTC).date
        return "${d.year}-${d.month.number.pad()}-${d.day.pad()}"
    }

    /** `YYYY-MM-DD` → epoch ms at UTC midnight; null when it is not a date. */
    /** `YYYY-MM-DD_HHMM` — the web's `exportTs`, so two exports never collide. */
    fun fileStamp(millis: Long): String {
        val moment = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())
        val pad = { value: Int -> value.toString().padStart(2, '0') }
        return "${moment.year}-${pad(moment.month.number)}-${pad(moment.day)}" +
            "_${pad(moment.hour)}${pad(moment.minute)}"
    }

    fun parseDateInput(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.length < DATE_LENGTH) return null
        return runCatching { LocalDate.parse(trimmed.take(DATE_LENGTH)) }.getOrNull()
            ?.atStartOfDayIn(TimeZone.UTC)?.toEpochMilliseconds()
    }

    /** Today's `YYYY-MM-DD` in the local zone. */
    fun today(nowMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val d = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date
        return "${d.year}-${d.month.number.pad()}-${d.day.pad()}"
    }

    private fun Int.pad(): String = toString().padStart(2, '0')

    private const val CENTS = 100.0
    private const val GROUP = 3
    private const val DATE_LENGTH = 10
    private const val DAYS_IN_WEEK = 7

    private val MONTH_NAMES = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
}
