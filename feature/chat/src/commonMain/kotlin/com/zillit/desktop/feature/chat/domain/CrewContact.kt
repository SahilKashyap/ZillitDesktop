package com.zillit.desktop.feature.chat.domain

import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * One person on the production, as the directory shows them.
 *
 * Crew who asked to keep their name private never reach this type — the host
 * filters them out before the screen sees the list, the same honour Android's
 * members tab pays.
 */
data class CrewContact(
    val userId: String,
    val fullName: String,
    val designation: String? = null,
    val department: String? = null,
    val email: String? = null,
    val isAdmin: Boolean = false,
    /** Their primary device — the address a 1:1 call rings. Null: not callable. */
    val deviceId: String? = null,
)

/**
 * The search box's rule: name, role or department, case-blind. A coordinator
 * looking for "the gaffer" should not need to remember who holds the job.
 */
fun List<CrewContact>.searchCrew(query: String): List<CrewContact> {
    val needle = query.trim()
    if (needle.isEmpty()) return this
    return filter { contact ->
        contact.fullName.contains(needle, ignoreCase = true) ||
            contact.designation?.contains(needle, ignoreCase = true) == true ||
            contact.department?.contains(needle, ignoreCase = true) == true
    }
}

/**
 * The directory's shape: departments alphabetically, people alphabetically
 * within each, and the department-less gathered at the end — a call-sheet
 * order, not a server order.
 */
fun List<CrewContact>.byDepartment(): List<Pair<String, List<CrewContact>>> {
    val named = filter { !it.department.isNullOrBlank() }
        .groupBy { it.department!!.trim() }
        .toSortedMap(String.CASE_INSENSITIVE_ORDER)
        .map { (department, people) -> department to people.sortedBy(CrewContact::fullName) }
    val unnamed = filter { it.department.isNullOrBlank() }
        .sortedBy(CrewContact::fullName)
    return if (unnamed.isEmpty()) named else named + (NO_DEPARTMENT to unnamed)
}

const val NO_DEPARTMENT = "No department"

/**
 * Recents in reading order: threads with known activity newest first, the
 * never-opened rest behind them in the server's order. Stable, so ties keep
 * their place.
 */
fun sortedRecents(ids: List<String>, newest: Map<String, Long>): List<String> =
    ids.sortedByDescending { newest[it] ?: Long.MIN_VALUE }

/** The listing's filter chips — Android's tabs, as one closed set. */
enum class ChatFilter(val label: String) {
    All("All"),
    Unread("Unread"),
    Groups("Groups"),
    Members("Members"),
    Favourites("Favourites"),
}

/**
 * The listing's time column, the mail list's convention: a clock today, a
 * date this year, month-and-year beyond, nothing for the epoch.
 */
fun chatTimeLabel(
    atMillis: Long,
    nowMillis: Long,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    if (atMillis <= 0) return ""
    val at = Instant.fromEpochMilliseconds(atMillis).toLocalDateTime(zone)
    val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date
    val month = MONTHS[at.date.monthNumber - 1]
    return when {
        at.date == today -> "${at.hour.pad()}:${at.minute.pad()}"
        at.date.year == today.year -> "${at.date.dayOfMonth} $month"
        else -> "$month ${at.date.year}"
    }
}

/**
 * The day chip a thread hangs over each day's first message — "Today",
 * "Yesterday", a weekday-and-date this year, date-and-year beyond. Words
 * where words are shorter, because the chip is furniture, not data.
 */
fun chatDayLabel(
    atMillis: Long,
    nowMillis: Long,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    if (atMillis <= 0) return ""
    val day = Instant.fromEpochMilliseconds(atMillis).toLocalDateTime(zone).date
    val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date
    val month = MONTHS[day.monthNumber - 1]
    return when {
        day == today -> "Today"
        day.toEpochDays() == today.toEpochDays() - 1 -> "Yesterday"
        day.year == today.year -> "${WEEKDAYS[day.dayOfWeek.isoDayNumber - 1]} ${day.dayOfMonth} $month"
        else -> "${day.dayOfMonth} $month ${day.year}"
    }
}

private fun Int.pad(): String = toString().padStart(2, '0')

private val MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
