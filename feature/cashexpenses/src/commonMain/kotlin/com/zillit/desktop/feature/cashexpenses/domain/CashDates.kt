package com.zillit.desktop.feature.cashexpenses.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The ledger dates this module sends, and the cost-report lock that bounds them.
 *
 * The web's `useCrLock` helpers: an effective date goes out as UTC midnight
 * (`Date.UTC(y, m, d)`), the picker runs from the day after the lock to today,
 * and it opens on the first day that range allows — never on a locked day.
 */
object CashDates {

    /** `YYYY-MM-DD` → UTC midnight epoch millis, or null for anything unreadable. */
    fun utcMillis(ymd: String): Long? = parse(ymd)?.atStartOfDayIn(TimeZone.UTC)?.toEpochMilliseconds()

    /** Epoch millis → `YYYY-MM-DD` in UTC, as the web's `toISOString().slice(0, 10)`. */
    fun utcYmd(millis: Long?): String? =
        millis?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date.toString() }

    fun today(zone: TimeZone = TimeZone.currentSystemDefault()): String = Clock.System.todayIn(zone).toString()

    /** The first day a post may be dated: the day after the lock, or null with no lock. */
    fun minimum(lockedThrough: String?): String? =
        lockedThrough?.let(::parse)?.plus(1, DateTimeUnit.DAY)?.toString()

    /** `max(today, lock + 1)` — the web's `lockedDefaultDateMs`. */
    fun defaultEffective(lockedThrough: String?, today: String = today()): String {
        val floor = minimum(lockedThrough) ?: return today
        return if (floor > today) floor else today
    }

    /** Whether [ymd] is a date a post may carry: readable, after the lock, not in the future. */
    fun isPostable(ymd: String, lockedThrough: String?, today: String = today()): Boolean {
        val date = parse(ymd)?.toString() ?: return false
        val floor = minimum(lockedThrough)
        return date <= today && (floor == null || date >= floor)
    }

    /**
     * Whether a stored ledger date falls inside the locked period.
     *
     * A batch with no date is never locked; one dated on or before the lock is
     * read-only everywhere — the web removes every mutating button.
     */
    fun isLocked(effectiveDate: Long?, lockedThrough: String?): Boolean {
        val lock = lockedThrough?.let(::parse)?.toString() ?: return false
        val date = utcYmd(effectiveDate) ?: return false
        return date <= lock
    }

    /**
     * A calendar month's first and last instant, in the machine's own zone.
     *
     * The reconciliation periods, as the web's `periodToEpochs`: local
     * midnight on the first to 23:59:59.999 on the last day.
     */
    fun monthBounds(year: Int, month: Int, zone: TimeZone = TimeZone.currentSystemDefault()): Pair<Long, Long> {
        val first = LocalDate(year, month, 1)
        val next = first.plus(1, DateTimeUnit.MONTH)
        val start = LocalDateTime(first, LocalTime(0, 0)).toInstant(zone).toEpochMilliseconds()
        val end = LocalDateTime(next, LocalTime(0, 0)).toInstant(zone).toEpochMilliseconds() - 1
        return start to end
    }

    /** This month and the eleven before it, newest first — the web's `PERIOD_OPTIONS`. */
    fun recentMonths(count: Int = MONTHS, zone: TimeZone = TimeZone.currentSystemDefault()): List<Pair<Int, Int>> {
        val first = Clock.System.todayIn(zone).let { LocalDate(it.year, it.month.ordinal + 1, 1) }
        return (0 until count).map { offset ->
            val month = first.minus(offset, DateTimeUnit.MONTH)
            month.year to month.month.ordinal + 1
        }
    }

    /** The month an epoch falls in, locally. */
    fun monthOf(millis: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): Pair<Int, Int>? =
        millis?.let {
            val date = Instant.fromEpochMilliseconds(it).toLocalDateTime(zone).date
            date.year to date.month.ordinal + 1
        }

    private fun parse(ymd: String): LocalDate? = runCatching { LocalDate.parse(ymd.trim().take(YMD)) }.getOrNull()

    private const val YMD = 10
    private const val MONTHS = 12
}
