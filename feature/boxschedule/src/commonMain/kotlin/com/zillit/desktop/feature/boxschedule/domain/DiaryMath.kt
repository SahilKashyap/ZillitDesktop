package com.zillit.desktop.feature.boxschedule.domain

/**
 * The diary's pure rules, transcribed from the web so both clients agree on
 * what a schedule looks like once exploded into rows.
 */
object DiaryMath {

    const val DAY_MS = 86_400_000L

    /** One day plus an hour of DST tolerance — the web's gap threshold. */
    private const val GAP_TOLERANCE_MS = DAY_MS + 3_600_000L

    /**
     * Blocks → one row per date, ascending, numbered per type name, with a
     * marker wherever the type changes from the previous row.
     */
    fun explode(blocks: List<ScheduleBlock>): List<ScheduleDayRow> {
        val dated = blocks.flatMap { block ->
            block.calendarDays.map { date -> block to date }
        }.sortedBy { it.second }

        val counters = mutableMapOf<String, Int>()
        var previousType: String? = null
        return dated.map { (block, date) ->
            val next = (counters[block.typeName] ?: 0) + 1
            counters[block.typeName] = next
            val row = ScheduleDayRow(
                block = block,
                date = date,
                dayNumber = next,
                isNewBlock = previousType != block.typeName,
            )
            previousType = block.typeName
            row
        }
    }

    /**
     * Whether a saved block's dates are non-contiguous — the web re-opens
     * such a block on its ad-hoc calendar tab rather than a range picker.
     */
    fun hasGaps(calendarDays: List<Long>): Boolean =
        calendarDays.sorted().zipWithNext().any { (a, b) -> b - a > GAP_TOLERANCE_MS }

    /** `startMidnight` + N consecutive days. */
    fun consecutiveDays(startMidnight: Long, count: Int): List<Long> =
        (0 until maxOf(count, 0)).map { startMidnight + it * DAY_MS }

    /** Every midnight from start to end inclusive. */
    fun rangeDays(startMidnight: Long, endMidnight: Long): List<Long> {
        if (endMidnight < startMidnight) return emptyList()
        val count = ((endMidnight - startMidnight) / DAY_MS).toInt() + 1
        return consecutiveDays(startMidnight, count)
    }

    /**
     * Server events mirrored into the Main Calendar come back twice — once
     * from `/events` and once from the calendar. The calendar copy is dropped
     * when the diary event knows its `calendarEventId`.
     */
    fun mergeWithCalendar(diary: List<DiaryEvent>, calendar: List<DiaryEvent>): List<DiaryEvent> {
        val mirrored = diary.mapNotNull { it.calendarEventId.takeIf(String::isNotBlank) }.toSet()
        return diary + calendar.filter { it.id.removePrefix(CALENDAR_PREFIX) !in mirrored }
    }

    /**
     * The web's `spliceEventUpdate`: patch an in-memory list from a recurring
     * PUT's response before the background refetch lands.
     */
    fun spliceUpdate(
        current: List<DiaryEvent>,
        message: String,
        updated: DiaryEvent?,
        newEvent: DiaryEvent?,
        occurrenceDate: Long,
        masterId: String,
    ): List<DiaryEvent> = when (message) {
        "occurrences_forked" -> current.filter { row ->
            row.masterId != masterId || row.occurrenceDate < occurrenceDate
        } + listOfNotNull(newEvent)
        "occurrence_modified" -> current.filter { row ->
            row.masterId != masterId || row.occurrenceDate != occurrenceDate
        } + listOfNotNull(newEvent)
        else -> current.filter { it.id != updated?.id } + listOfNotNull(updated)
    }

    const val CALENDAR_PREFIX = "cal:"
}
