package com.zillit.desktop.feature.boxschedule.ui

import com.zillit.desktop.feature.boxschedule.domain.BlockDraft
import com.zillit.desktop.feature.boxschedule.domain.ConflictAction
import com.zillit.desktop.feature.boxschedule.domain.DateConflict
import com.zillit.desktop.feature.boxschedule.domain.DateTab
import com.zillit.desktop.feature.boxschedule.domain.DiaryCalendar
import com.zillit.desktop.feature.boxschedule.domain.RangeMode
import com.zillit.desktop.feature.boxschedule.domain.ScheduleBlock
import com.zillit.desktop.feature.boxschedule.domain.ScheduleDates
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * The schedule drawer — `CreateScheduleModal`: a type, and dates set one of
 * three ways. Three shapes share it: a new schedule, a whole block edited,
 * and one date of a block moved to another type ([singleDate]).
 */
data class ScheduleForm(
    /** The block being edited; null for a new schedule. */
    val blockId: String? = null,
    /** Set for "This date only": the one date whose type changes, locked. */
    val singleDate: Long? = null,
    val originalTypeId: String = "",
    val originalTypeName: String = "",
    val originalDays: List<Long> = emptyList(),
    /** A schedule started from a calendar date keeps that date: it cannot be cleared or unpicked. */
    val lockedStart: LocalDate? = null,
    val typeId: String = "",
    val title: String = "",
    val tab: DateTab = DateTab.DateRange,
    val rangeMode: RangeMode = RangeMode.ByDays,
    val start: LocalDate? = null,
    val countText: String = DEFAULT_COUNT.toString(),
    val end: LocalDate? = null,
    val picked: Set<LocalDate> = emptySet(),
    val dayWiseStart: LocalDate? = null,
    val dayWiseEnd: LocalDate? = null,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val singleAction: ConflictAction = ConflictAction.Replace,
    val newType: NewTypeDraft? = null,
    /** The block's existing dates, dotted in the pickers while editing. */
    val existingDays: Set<LocalDate> = emptySet(),
    val saving: Boolean = false,
) {
    val isSingleDay: Boolean get() = singleDate != null
    val isEdit: Boolean get() = blockId != null
    val typeChanged: Boolean get() = typeId.isNotBlank() && typeId != originalTypeId

    val count: Int get() = countText.trim().toIntOrNull()?.takeIf { it in 1..ScheduleDates.MAX_DAYS } ?: 0

    /** The dates the active tab describes, ascending. */
    fun days(): List<LocalDate> = when (tab) {
        DateTab.DateRange -> when (rangeMode) {
            RangeMode.ByDays -> ScheduleDates.byDays(start, count)
            RangeMode.ByEndDate -> ScheduleDates.range(start, end)
        }
        DateTab.Calendar -> picked.sorted()
        DateTab.DayWise -> ScheduleDates.dayWise(dayWiseStart, dayWiseEnd, weekdays)
    }

    val canSave: Boolean get() = typeId.isNotBlank() && (isSingleDay || days().isNotEmpty()) && !saving

    fun draft(zone: TimeZone): BlockDraft = BlockDraft(
        typeId = typeId,
        title = title.trim(),
        calendarDays = ScheduleDates.toWire(days(), zone),
        byDates = tab == DateTab.Calendar,
    )

    companion object {
        const val DEFAULT_COUNT = 5

        /** A new schedule, or one started from a calendar date ([date] locked). */
        fun create(date: LocalDate?): ScheduleForm = if (date == null) {
            ScheduleForm()
        } else {
            ScheduleForm(
                lockedStart = date,
                start = date,
                countText = "1",
                picked = setOf(date),
                dayWiseStart = date,
            )
        }

        /** A whole block, reopened on the tab its dates need. */
        fun edit(block: ScheduleBlock, zone: TimeZone): ScheduleForm {
            val dates = block.calendarDays.map { DiaryCalendar.dateOf(it, zone) }.distinct().sorted()
            val first = dates.firstOrNull()
            val last = dates.lastOrNull()
            return ScheduleForm(
                blockId = block.id,
                originalTypeId = block.typeId,
                originalTypeName = block.typeName,
                originalDays = block.calendarDays,
                typeId = block.typeId,
                title = block.title,
                tab = ScheduleDates.initialTab(block),
                start = first,
                countText = (dates.size.takeIf { it > 0 } ?: block.numberOfDays.takeIf { it > 0 } ?: DEFAULT_COUNT)
                    .toString(),
                end = last.takeIf { dates.size > 1 },
                picked = dates.toSet(),
                dayWiseStart = first,
                dayWiseEnd = last,
                weekdays = dates.map { it.dayOfWeek }.toSet(),
                existingDays = dates.toSet(),
            )
        }

        /** One date of a block — "This date only". */
        fun singleDay(block: ScheduleBlock, date: Long): ScheduleForm = ScheduleForm(
            blockId = block.id,
            singleDate = date,
            originalTypeId = block.typeId,
            originalTypeName = block.typeName,
            originalDays = block.calendarDays,
            typeId = block.typeId,
            title = block.title,
        )
    }
}

/** The inline "+" new type under the schedule drawer's type picker. */
data class NewTypeDraft(
    val name: String = "",
    val color: String = "#3498DB",
    val saving: Boolean = false,
)

/**
 * "Schedule Conflict" — the dates collided. The draft waits here, with the
 * form it came from, so Back reopens exactly what was typed.
 */
data class ConflictPrompt(
    val conflicts: List<DateConflict>,
    val draft: BlockDraft,
    /** The block being edited; a retry of an edit must not create a duplicate. */
    val blockId: String?,
    val form: ScheduleForm,
    val choice: ConflictAction? = null,
    val saving: Boolean = false,
)

enum class ScopeMode { Edit, Delete }

enum class ScheduleScope { Single, Complete }

/** "This schedule covers more than one day." — this date only, or the whole block. */
data class ScheduleScopePrompt(
    val blockId: String,
    val date: Long,
    val mode: ScopeMode,
    val scope: ScheduleScope = ScheduleScope.Single,
    val working: Boolean = false,
)

/** "Delete Schedule Day" — the plain confirm for a one-day block. */
data class DeleteDayPrompt(val blockId: String, val date: Long?, val working: Boolean = false)
