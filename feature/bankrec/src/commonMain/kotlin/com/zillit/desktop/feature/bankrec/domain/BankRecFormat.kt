package com.zillit.desktop.feature.bankrec.domain

import com.zillit.desktop.core.common.Money
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * How this module writes money, rates and dates — the web's formatters, in one
 * place so every tab agrees.
 *
 * ## Dates are read in UTC, timestamps in local time
 *
 * A period is a first-of-month marker in UTC and a statement date is a UTC
 * midnight: read with a local calendar they move a day — or a month — either
 * side of midnight, which is how a March reconciliation comes to be labelled
 * February. An audit entry's time *is* an instant, and reads in the viewer's
 * own zone.
 */
@Suppress("TooManyFunctions") // One formatter per shape the web prints; split, the rules drift apart.
object BankRecFormat {

    const val DASH = "—"

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val FULL_MONTHS = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    // -- money --------------------------------------------------------------

    /** `£1,234.50`, `-£335.22` — a minus when negative, nothing when positive. */
    fun money(amount: Double?, currency: String?): String = Money.format(amount, currency)

    /** `£1,234.50` whatever the sign — a balance whose direction is not the point. */
    fun plainMoney(amount: Double?, currency: String?): String =
        amount?.let { Money.format(abs(it), currency) } ?: DASH

    /** `+£1,234.50` / `-£335.22` — for a column where direction *is* the point. */
    fun signedMoney(amount: Double?, currency: String?): String {
        if (amount == null) return DASH
        val body = Money.format(abs(amount), currency)
        return when {
            amount < 0 -> "-$body"
            amount > 0 -> "+$body"
            else -> body
        }
    }

    /** A foreign amount with no pence, as the FX table prints it: `€1,500`. */
    fun wholeMoney(amount: Double, currency: String?): String = Money.format(abs(amount), currency, decimals = 0)

    /** `1,234.50` — a debit or credit column, whose header already names the currency. */
    fun figure(amount: Double?): String = amount?.let { Money.group(abs(it), 2) } ?: DASH

    /**
     * `£1.2M`, `£150K`, `£150.5` — two decimals of precision with trailing zeros
     * stripped, as the web's `formatCompact` does for the panel totals.
     */
    fun compactMoney(amount: Double, currency: String?): String {
        val sign = if (amount < 0) "-" else ""
        return sign + Money.symbol(currency) + compact(abs(amount))
    }

    internal fun compact(value: Double): String {
        val scaled = listOf(TRILLION to "T", BILLION to "B", MILLION to "M", THOUSAND to "K")
            .firstOrNull { value >= it.first }
        return if (scaled == null) {
            trimZeros((value * HUNDRED).roundToLong() / HUNDRED.toDouble())
        } else {
            trimZeros((value / scaled.first * HUNDRED).roundToLong() / HUNDRED.toDouble()) + scaled.second
        }
    }

    private fun trimZeros(value: Double): String {
        val text = Money.group(value, 2).replace(",", "")
        return text.trimEnd('0').trimEnd('.')
    }

    /** A rate to four places: `1.1650`. A missing one is a dash, never `0.0000`. */
    fun rate(value: Double?): String = value?.let { Money.group(it, RATE_DECIMALS).replace(",", "") } ?: DASH

    /** `+0.0123` — the chart's rate movement, always signed when non-zero. */
    fun signedRate(value: Double): String {
        val body = Money.group(abs(value), RATE_DECIMALS).replace(",", "")
        return when {
            value > 0 -> "+$body"
            value < 0 -> "-$body"
            else -> body
        }
    }

    /** `83%` — of a total, rounded; `0%` when there is nothing to divide by. */
    fun percent(value: Int, total: Int): String =
        if (total <= 0) "0%" else "${(value * HUNDRED.toDouble() / total).roundToInt()}%"

    // -- periods ------------------------------------------------------------

    /** `Apr 2026`, from the UTC month marker — or the legacy month and year. */
    fun periodLabel(period: BankPeriod): String = when {
        period.periodMillis != null -> monthLabel(period.periodMillis)
        period.legacyMonth != null && period.legacyYear != null && period.legacyMonth in 1..MONTHS.size ->
            "${MONTHS[period.legacyMonth - 1]} ${period.legacyYear}"

        else -> DASH
    }

    /** `April 2026`. */
    fun fullPeriodLabel(period: BankPeriod): String = when {
        period.periodMillis != null -> utc(period.periodMillis).let { "${FULL_MONTHS[it.month]} ${it.year}" }
        period.legacyMonth != null && period.legacyYear != null && period.legacyMonth in 1..FULL_MONTHS.size ->
            "${FULL_MONTHS[period.legacyMonth - 1]} ${period.legacyYear}"

        else -> DASH
    }

    fun monthLabel(millis: Long?): String = millis?.let { utc(it).let { d -> "${MONTHS[d.month]} ${d.year}" } } ?: DASH

    // -- dates --------------------------------------------------------------

    /** `05 Apr`, with the year's last two digits when it is not this year: `05 Apr 25`. */
    fun statementDay(millis: Long?, nowMillis: Long = Clock.System.now().toEpochMilliseconds()): String {
        val date = millis?.let(::utc) ?: return DASH
        val base = "${date.day.toString().padStart(2, '0')} ${MONTHS[date.month]}"
        return if (date.year != utc(nowMillis).year) "$base ${date.year.toString().takeLast(2)}" else base
    }

    /** `05 Apr` — no year. */
    fun paddedDay(millis: Long?): String =
        millis?.let(::utc)?.let { "${it.day.toString().padStart(2, '0')} ${MONTHS[it.month]}" } ?: DASH

    /** `5 Apr`. */
    fun shortDay(millis: Long?): String = millis?.let(::utc)?.let { "${it.day} ${MONTHS[it.month]}" } ?: DASH

    /** `5 Apr 2026`. */
    fun day(millis: Long?): String = millis?.let(::utc)?.let { "${it.day} ${MONTHS[it.month]} ${it.year}" } ?: DASH

    /** `5 Apr 2026` in the viewer's own zone — when something happened, not which day a statement names. */
    fun localDay(millis: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val local = millis?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(zone) } ?: return DASH
        return "${local.day} ${MONTHS[local.month.ordinal]} ${local.year}"
    }

    /** `5 Apr` in the viewer's own zone — for a creation instant, which is not a statement date. */
    fun localShortDay(millis: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val local = millis?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(zone) } ?: return DASH
        return "${local.day} ${MONTHS[local.month.ordinal]}"
    }

    /** `5 Apr 2026, 14:03`, in the viewer's own zone — an instant, not a date. */
    fun dateTime(millis: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val value = millis ?: return DASH
        val local = Instant.fromEpochMilliseconds(value).toLocalDateTime(zone)
        val hh = local.hour.toString().padStart(2, '0')
        val mm = local.minute.toString().padStart(2, '0')
        return "${local.day} ${MONTHS[local.month.ordinal]} ${local.year}, $hh:$mm"
    }

    /** `2026-04-05`, the value a date field holds. */
    fun isoDate(millis: Long?): String = millis?.let(::utc)?.let {
        "${it.year}-${(it.month + 1).toString().padStart(2, '0')}-${it.day.toString().padStart(2, '0')}"
    }.orEmpty()

    /** `12-34-56` from six digits however they were typed; anything else as it came. */
    fun sortCode(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.length == SORT_CODE_DIGITS) {
            digits.chunked(2).joinToString("-")
        } else {
            raw.trim()
        }
    }

    private data class UtcDate(val year: Int, val month: Int, val day: Int)

    private fun utc(millis: Long): UtcDate {
        val date = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date
        return UtcDate(year = date.year, month = date.month.ordinal, day = date.day)
    }

    private const val HUNDRED = 100
    private const val RATE_DECIMALS = 4
    private const val SORT_CODE_DIGITS = 6
    private const val THOUSAND = 1e3
    private const val MILLION = 1e6
    private const val BILLION = 1e9
    private const val TRILLION = 1e12
}
