package com.zillit.desktop.feature.boxschedule.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.datetime.TimeZone

/** The Filter dialog's "Show" chips — the web's `contentFilter`. */
enum class ContentFilter(private val labelKey: String) {
    All(S.bs_filter_all),
    Schedules(S.bs_filter_schedules),
    Events(S.bs_filter_events),
    Notes(S.bs_filter_notes),
    ;

    val label: String get() = str(labelKey)

    val showsSchedules: Boolean get() = this == All || this == Schedules
    val showsEvents: Boolean get() = this == All || this == Events
    val showsNotes: Boolean get() = this == All || this == Notes

    fun shows(kind: DiaryKind): Boolean = if (kind == DiaryKind.Note) showsNotes else showsEvents
}

/**
 * The page's search box and Filter dialog, applied the way each view of the
 * web applies them.
 *
 * The search matches schedules by title and type name, events and notes by
 * title and body; the List view also matches dates, written the way the web
 * writes them for search (`ddd MMM DD YYYY`). A type filter narrows schedules
 * only — events and notes have no type.
 */
data class DiaryFilter(
    val search: String = "",
    /** A schedule type's title; blank means every type. */
    val typeName: String = "",
    val content: ContentFilter = ContentFilter.All,
) {
    private val query: String get() = search.trim().lowercase()

    /** The Filter button's badge — search does not count. */
    val activeCount: Int get() = (if (typeName.isNotBlank()) 1 else 0) + (if (content != ContentFilter.All) 1 else 0)

    private fun typeMatches(block: ScheduleBlock): Boolean = typeName.isBlank() || block.typeName == typeName

    private fun matches(vararg fields: String): Boolean {
        val q = query
        return q.isEmpty() || fields.any { it.lowercase().contains(q) }
    }

    /** The calendar's schedules — `filteredCalendarData`. */
    fun calendarBlocks(blocks: List<ScheduleBlock>): List<ScheduleBlock> =
        if (!content.showsSchedules) {
            emptyList()
        } else {
            blocks.filter { typeMatches(it) && matches(it.title, it.typeName) }
        }

    /** The calendar's events and notes — `filteredStandaloneEvents`. */
    fun calendarEvents(events: List<DiaryEvent>): List<DiaryEvent> =
        events.filter { content.shows(it.kind) && matches(it.title, it.body) }

    /** The list's date rows — `filteredSchedule`; the table drops them when schedules are hidden. */
    fun listRows(rows: List<ScheduleDayRow>, zone: TimeZone): List<ScheduleDayRow> =
        rows.filter { row ->
            typeMatches(row.block) &&
                matches(row.block.title, row.block.typeName, DiaryFormat.searchable(row.date, zone))
        }

    /** The list's blocks, By Schedule — any of the block's dates can match. */
    fun listBlocks(blocks: List<ScheduleBlock>, zone: TimeZone): List<ScheduleBlock> =
        if (!content.showsSchedules) {
            emptyList()
        } else {
            blocks.filter { block ->
                typeMatches(block) &&
                    (
                        matches(block.title, block.typeName) ||
                            block.calendarDays.any { matches(DiaryFormat.searchable(it, zone)) }
                        )
            }.sortedBy { it.firstDay }
        }

    /** The list's events and notes — `visibleEvents`. */
    fun listEvents(events: List<DiaryEvent>, zone: TimeZone): List<DiaryEvent> =
        events.filter { event ->
            val stamp = event.date.takeIf { it > 0 } ?: event.startDateTime
            content.shows(event.kind) &&
                matches(event.title, event.body, if (stamp > 0) DiaryFormat.searchable(stamp, zone) else "")
        }
}
