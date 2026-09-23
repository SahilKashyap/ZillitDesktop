package com.zillit.desktop.feature.documentdistribution.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/**
 * The order the listing is fetched in.
 *
 * Server-side, not client-side: the listing is paginated, so sorting only the
 * loaded pages would reorder a window rather than the list. [wire] is the
 * backend's whitelisted `sort_by` (`services/v2/document.js SORT_OPTIONS`) —
 * anything else is rejected outright.
 */
enum class LibrarySort(val wire: String, private val labelKey: String) {
    NameAsc("name_asc", S.dd_sort_name_asc),
    NameDesc("name_desc", S.dd_sort_name_desc),
    DateDesc("date_desc", S.dd_sort_date_desc),
    DateAsc("date_asc", S.dd_sort_date_asc),
    ;

    val label: String get() = str(labelKey)

    /**
     * Whether the listing groups by date under this order.
     *
     * A name sort flattens to one alphabetical list — date headers over an
     * A–Z listing would put one document under each of forty headings. The web
     * makes the same switch in `Library.jsx: grouped`.
     */
    val groupsByDate: Boolean get() = this == DateDesc || this == DateAsc

    /** The compact form the sort button shows once chosen. */
    val shortLabel: String
        get() = when (this) {
            NameAsc -> str(S.desktop_sort_a_to_z)
            NameDesc -> str(S.desktop_sort_z_to_a)
            DateDesc -> str(S.desktop_sort_newest)
            DateAsc -> str(S.desktop_sort_oldest)
        }

    companion object {
        fun from(wire: String?): LibrarySort =
            entries.firstOrNull { it.wire == wire } ?: NameAsc
    }
}

/** One date bucket of the library listing, with its heading. */
data class DateGroup(
    /** `YYYY-MM-DD`, or blank for the undated bucket. */
    val key: String,
    val heading: String,
    val documents: List<LibraryDocument>,
    /**
     * How many the server says are in this bucket in total.
     *
     * Not `documents.size`: the listing is paged, so a bucket can be showing
     * twelve of eighty. The header renders "12 of 80" from the server's own
     * `date_counts`, which is the only figure that stays true as pages load.
     */
    val total: Int,
)

/**
 * How the library listing is bucketed and headed.
 *
 * Pure, and takes today's date rather than reading a clock: this is common
 * code with no clock in it, and a function whose answer depends on the wall
 * time cannot be pinned by a test. The caller supplies "today" — see
 * `DocDistToolProvider`.
 */
object LibraryGrouping {

    private val months = listOf(
        S.desktop_month_short_jan, S.desktop_month_short_feb, S.desktop_month_short_mar, S.desktop_month_short_apr,
        S.desktop_month_short_may, S.desktop_month_short_jun, S.desktop_month_short_jul, S.desktop_month_short_aug,
        S.desktop_month_short_sep, S.desktop_month_short_oct, S.desktop_month_short_nov, S.desktop_month_short_dec,
    )

    private val weekdays = listOf(
        S.day_monday, S.day_tuesday, S.day_wednesday, S.day_thursday,
        S.day_friday, S.day_saturday, S.day_sunday,
    )

    /**
     * `Today · Sep 1, 2025` / `Yesterday · …` / `Wednesday, Sep 1, 2025`.
     *
     * Ported from `utils/format.js: formatDateLabel`. An unparseable key is
     * "Undated" rather than an error — the server files documents with no
     * production date under an empty key, and that bucket is a normal part of
     * every library.
     */
    fun heading(dateKey: String, today: LocalDate): String {
        val date = dateKey.takeIf { it.isNotBlank() }?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        } ?: return UNDATED

        val pretty = "${str(months[date.monthNumber - 1])} ${date.dayOfMonth}, ${date.year}"
        return when (date) {
            today -> "${str(S.today)} · $pretty"
            today.minus(DatePeriod(days = 1)) -> "${str(S.yesterday)} · $pretty"
            else -> "${str(weekdays[date.dayOfWeek.ordinal])}, $pretty"
        }
    }

    /**
     * Buckets [documents] by production date, newest bucket first.
     *
     * The undated bucket sorts last whichever direction the dates run: it is
     * the residue, and putting it at the top of a newest-first listing hides
     * the day the user actually came for.
     */
    fun group(
        documents: List<LibraryDocument>,
        counts: Map<String, Int>,
        today: LocalDate,
        ascending: Boolean = false,
    ): List<DateGroup> {
        val buckets = documents.groupBy { it.documentDate }
        val keys = buckets.keys.sortedWith(
            compareBy<String> { it.isBlank() }.thenBy { key ->
                // Reversed by negating the comparison rather than by sorting
                // twice, so the undated-last rule above survives either order.
                if (ascending) key else key.reversedForSort()
            },
        )
        return keys.map { key ->
            val rows = buckets[key].orEmpty()
            DateGroup(
                key = key,
                heading = heading(key, today),
                documents = rows,
                total = counts[key] ?: rows.size,
            )
        }
    }

    /**
     * A key that sorts a date string descending under an ascending comparator.
     *
     * `YYYY-MM-DD` is fixed-width and lexicographically ordered, so each digit
     * complemented against '9' inverts the order without parsing.
     */
    private fun String.reversedForSort(): String =
        map { if (it.isDigit()) ('9' - (it - '0')) else it }.joinToString("")

    val UNDATED: String get() = str(S.desktop_undated)
}

/** "1.20 MB" / "840 KB" — the library's size column. Ported from `formatBytes`. */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= BYTE_STEP && unit < units.lastIndex) {
        value /= BYTE_STEP
        unit++
    }
    // Two decimals below ten, none above — the web's rule, and it is the one
    // that keeps a size column from jittering between "9.99 MB" and "1024 MB".
    val rendered = if (value < BYTE_STEP_LABEL_CUTOFF) {
        val hundredths = (value * HUNDRED).toLong()
        "${hundredths / HUNDRED}.${(hundredths % HUNDRED).toString().padStart(2, '0')}"
    } else {
        value.toLong().toString()
    }
    return "$rendered ${units[unit]}"
}

private const val BYTE_STEP = 1024.0
private const val BYTE_STEP_LABEL_CUTOFF = 10.0
private const val HUNDRED = 100
