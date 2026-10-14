package com.zillit.desktop.feature.transporthub.ui.kit

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.transporthub.data.startOfDay

/**
 * The passenger lists' filters — the web's `applyRideFilters`, rule for rule.
 *
 * One model for both of a passenger's lists (and a driver's own requests),
 * because the web shares one filter bar between them: search, a status, a
 * priority, a date range over a chosen date, and a sort.
 */
data class RideFilters(
    val query: String = "",
    /** A status wire value, or `all`. */
    val status: String = ALL,
    /** A priority wire value, or `all`. */
    val priority: String = ALL,
    /** Which of a ride's dates [range] is read against. */
    val dateField: String = FIELD_SCHEDULED,
    /** One of [RANGES]' ids; `any` leaves the date alone. */
    val range: String = RANGE_ANY,
    val sort: String = SORT_NEWEST,
) {
    /** How many filters differ from the defaults — the Advanced badge. */
    val activeCount: Int
        get() = listOf(
            query.isNotBlank(),
            status != ALL,
            priority != ALL,
            range != RANGE_ANY,
            sort != SORT_NEWEST,
        ).count { it }

    companion object {
        const val ALL = "all"
        const val FIELD_SCHEDULED = "scheduled"
        const val FIELD_COMPLETED = "completed"
        const val FIELD_CREATED = "created"
        const val RANGE_ANY = "any"
        const val SORT_NEWEST = "newest"

        val PRIORITIES: List<Pair<String, () -> String>> = listOf(
            ALL to { str(S.all) },
            "normal" to { str(S.nda_style_normal) },
            "high" to { str(S.desktop_weather_uv_high) },
            "urgent" to { str(S.ah_topup_filter_urgent) },
        )

        val SORTS: List<Pair<String, () -> String>> = listOf(
            SORT_NEWEST to { str(S.desktop_hub_newest_first) },
            "oldest" to { str(S.ah_oldest_first) },
            "priority" to { str(S.desktop_hub_highest_priority) },
            "status" to { str(S.desktop_hub_by_status) },
        )

        /** The web's own range presets. */
        val RANGES: List<Pair<String, () -> String>> = listOf(
            RANGE_ANY to { str(S.all) },
            "today" to { str(S.today) },
            "yesterday" to { str(S.yesterday) },
            "7d" to { str(S.drive_date_last_7) },
            "30d" to { str(S.drive_date_last_30) },
        )

        val DATE_FIELDS: List<Pair<String, () -> String>> = listOf(
            FIELD_SCHEDULED to { str(S.desktop_hub_pickup_date) },
            FIELD_COMPLETED to { str(S.desktop_hub_finished_date) },
            FIELD_CREATED to { str(S.desktop_hub_created_date) },
        )
    }
}

/** What the filters read off one row, whatever kind of row it is. */
data class RideRow(
    val status: String,
    val priority: String,
    /** Everything the search box matches against, already joined. */
    val text: String,
    val scheduledMs: Long,
    /** Completed, or — for a ride that never ran — when it was cancelled or refused. */
    val finishedMs: Long,
    val createdMs: Long,
)

/**
 * Filters and sorts [rows] the way the web does.
 *
 * Two of the web's own rules worth keeping: the status filter `in_transit`
 * also takes `in_progress`, so the option list does not have to carry the
 * server's aliases; and a row with no date at all drops out of any date range
 * rather than matching every one.
 */
fun <T> List<T>.applyRideFilters(filters: RideFilters, nowMs: Long, row: (T) -> RideRow): List<T> {
    val q = filters.query.trim().lowercase()
    val (from, to) = filters.rangeBounds(nowMs)
    return asSequence()
        .map { it to row(it) }
        .filter { (_, r) -> q.isEmpty() || r.text.contains(q) }
        .filter { (_, r) ->
            filters.status == RideFilters.ALL || r.status == filters.status ||
                (filters.status == "in_transit" && r.status in IN_TRANSIT_ALIASES)
        }
        .filter { (_, r) -> filters.priority == RideFilters.ALL || r.priority == filters.priority }
        .filter { (_, r) ->
            if (from == null) return@filter true
            val ms = r.dateFor(filters.dateField)
            ms > 0 && ms >= from && ms <= (to ?: Long.MAX_VALUE)
        }
        .sortedWith(comparator(filters))
        .map { it.first }
        .toList()
}

private fun <T> comparator(filters: RideFilters): Comparator<Pair<T, RideRow>> {
    val byDate = compareBy<Pair<T, RideRow>> { it.second.dateFor(filters.dateField) }
    val newestFirst = if (filters.sort == "oldest") byDate else byDate.reversed()
    return when (filters.sort) {
        "priority" -> compareBy<Pair<T, RideRow>> { PRIORITY_RANK[it.second.priority] ?: UNRANKED }
            .then(newestFirst)

        "status" -> compareBy<Pair<T, RideRow>> { STATUS_RANK[it.second.status] ?: UNRANKED }
            .then(newestFirst)

        else -> newestFirst
    }
}

private fun RideRow.dateFor(field: String): Long = when (field) {
    RideFilters.FIELD_COMPLETED -> finishedMs
    RideFilters.FIELD_CREATED -> createdMs
    else -> scheduledMs.takeIf { it > 0 } ?: createdMs
}

/** The preset as an inclusive `[from, to]`, both in epoch ms. */
private fun RideFilters.rangeBounds(nowMs: Long): Pair<Long?, Long?> {
    val today = nowMs.startOfDay()
    return when (range) {
        "today" -> today to today + DAY_MS - 1
        "yesterday" -> today - DAY_MS to today - 1
        "7d" -> today - DAYS_6 * DAY_MS to today + DAY_MS - 1
        "30d" -> today - DAYS_29 * DAY_MS to today + DAY_MS - 1
        else -> null to null
    }
}

private val IN_TRANSIT_ALIASES = setOf("in_transit", "in_progress")

private val PRIORITY_RANK = mapOf("urgent" to 0, "high" to 1, "normal" to 2, "low" to 3)

private val STATUS_RANK = mapOf(
    "in_transit" to 0, "in_progress" to 0,
    "at_pickup" to 1, "en_route_pickup" to 1,
    "assigned" to 2, "approved" to 3, "pending" to 4,
    "completed" to 5, "rejected" to 6, "cancelled" to 7,
)

private const val UNRANKED = 99
private const val DAY_MS = 24L * 60 * 60 * 1000
private const val DAYS_6 = 6
private const val DAYS_29 = 29
