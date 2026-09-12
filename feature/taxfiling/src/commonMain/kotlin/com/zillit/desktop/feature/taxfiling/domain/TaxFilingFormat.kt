package com.zillit.desktop.feature.taxfiling.domain

import com.zillit.desktop.core.common.Money
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * How the filing surface writes things down — the web's `mtd-tokens.js`
 * formatters and `constants.js` helpers, one function each.
 */
object TaxFormat {

    private const val VRN_DIGITS = 9
    private const val GROUP = 3

    /** `123 456 789` for a nine-digit number; anything else exactly as given. */
    fun vrn(value: String): String {
        val digits = value.filterNot { it.isWhitespace() }
        if (digits.length != VRN_DIGITS || !digits.all { it.isDigit() }) return value
        return digits.chunked(GROUP).joinToString(" ")
    }

    /** `£1,234.56`, or an em dash for a figure not worked out yet. */
    fun gbp(amount: Double?, decimals: Int = 2): String = Money.format(amount, GBP, decimals)

    /** `Quarterly`, from the service's `quarterly`. */
    fun frequency(value: String): String = TaxFrequency.from(value)?.label
        ?: value.trim().replaceFirstChar { it.uppercase() }.ifBlank { "—" }

    /**
     * `zillit-films-ltd`, for a file name — the web's
     * `.replace(/[^a-z0-9]+/gi, '-').toLowerCase()`.
     */
    fun slug(value: String): String = value.lowercase().replace(NON_ALPHANUMERIC, "-")

    private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
    private const val GBP = "GBP"
}

/** The filing frequencies the register dialog offers, in the web's order. */
enum class TaxFrequency(val wire: String, val label: String) {
    Monthly("monthly", "Monthly"),
    Quarterly("quarterly", "Quarterly"),
    Annual("annual", "Annually"),
    ;

    companion object {
        fun from(value: String): TaxFrequency? = entries.firstOrNull { it.wire.equals(value.trim(), true) }
    }
}

/**
 * The per-box date window, between the `yyyy-mm-dd` the accountant types and
 * the UTC-midnight epoch milliseconds the service stores — `ymdToMs` and
 * `msToYmd` on the web.
 */
object TaxDates {

    /** UTC midnight of [ymd], or null for anything that is not a whole date. */
    fun toMillis(ymd: String): Long? {
        val trimmed = ymd.trim()
        if (trimmed.isEmpty()) return null
        val date = runCatching { LocalDate.parse(trimmed) }.getOrNull() ?: return null
        return date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
    }

    /** `yyyy-mm-dd` for a stored window edge; blank when there is none. */
    fun toYmd(millis: Long?): String {
        if (millis == null) return ""
        return runCatching {
            Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date.toString()
        }.getOrDefault("")
    }

    fun isValid(ymd: String): Boolean = toMillis(ymd) != null
}
