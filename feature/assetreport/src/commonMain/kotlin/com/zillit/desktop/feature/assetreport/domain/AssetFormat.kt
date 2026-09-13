package com.zillit.desktop.feature.assetreport.domain

import com.zillit.desktop.core.common.Money
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.time.Instant

/** How the register writes its figures and days — the web's `fmtAmount`, `fmtDate` and `fmtRange`. */
object AssetFormat {

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** `£1,234.50` — the picked currency's symbol, en-GB grouping, always two decimals. */
    fun money(amount: Double, symbol: String): String {
        val safe = if (amount.isNaN() || amount.isInfinite()) 0.0 else amount
        val body = symbol + Money.group(abs(safe), 2)
        return if (safe < 0 && Money.group(abs(safe), 2) != "0.00") "-$body" else body
    }

    /** `12 Sep 2026`, on the reader's calendar; blank for no date. */
    fun date(millis: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        if (millis == null) return ""
        val day = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).date
        return "${day.day} ${MONTHS[day.month.ordinal]} ${day.year}"
    }

    /** `1 Sep 2026 – 30 Sep 2026`, or whichever end is known. */
    fun range(startMillis: Long?, endMillis: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val start = date(startMillis, zone)
        val end = date(endMillis, zone)
        return when {
            start.isNotEmpty() && end.isNotEmpty() -> "$start – $end"
            else -> start.ifEmpty { end }
        }
    }

    /** `3` for a whole quantity, `2.5` for a fraction — what the web prints. */
    fun quantity(value: Double): String {
        val whole = value == kotlin.math.floor(value) && abs(value) < WHOLE_LIMIT
        return if (whole) value.toLong().toString() else value.toString()
    }

    /** `asset-register_2026-09-13_0142.pdf` — the web's `exportTs` stamp, local time. */
    fun exportFileName(
        format: AssetExportFormat,
        nowMillis: Long,
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): String {
        val moment = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone)
        fun two(value: Int) = value.toString().padStart(2, '0')
        // LocalDate prints ISO, `2026-09-13`, which is the stamp's date half.
        return "asset-register_${moment.date}_${two(moment.hour)}${two(moment.minute)}.${format.wire}"
    }

    /** Past this a double stops holding whole numbers exactly; print it as it is. */
    private const val WHOLE_LIMIT = 1e15
}
