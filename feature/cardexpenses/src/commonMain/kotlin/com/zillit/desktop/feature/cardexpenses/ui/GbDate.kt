package com.zillit.desktop.feature.cardexpenses.ui

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The card tool's dates, the way its web build prints them: `toLocaleDateString("en-GB", …)`
 * (`lib/formatCurrency.js:25-38`) — `04 Aug 2026`, and with a clock `04 Aug 2026, 16:35`.
 *
 * Not [com.zillit.desktop.core.common.EpochDate], which is the cash tool's `04 Aug, 2026 | 4:35 PM`
 * and is shared; this module's screens were reading it and so differed from the web on every row.
 * Rendered in the machine's zone, as a browser does. September is `Sept`: that is how current
 * ICU spells it for en-GB, and what the web's users see.
 */
internal object GbDate {
    private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sept", "Oct", "Nov", "Dec")

    /** `04 Aug 2026`; blank for a missing or unreadable stamp. */
    fun date(millis: Long?): String {
        val moment = millis.local() ?: return ""
        return "${moment.dayOfMonth.pad()} ${months[moment.monthNumber - 1]} ${moment.year}"
    }

    /** `16:35`; blank for a missing stamp. */
    fun time(millis: Long?): String {
        val moment = millis.local() ?: return ""
        return "${moment.hour.pad()}:${moment.minute.pad()}"
    }

    /** `04 Aug 2026, 16:35`. */
    fun dateTime(millis: Long?): String {
        val day = date(millis)
        return if (day.isEmpty()) "" else "$day, ${time(millis)}"
    }

    private fun Long?.local() = this
        ?.takeIf { it > 0 }
        ?.let {
            runCatching { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault()) }
                .getOrNull()
        }

    private fun Int.pad(): String = toString().padStart(2, '0')
}
