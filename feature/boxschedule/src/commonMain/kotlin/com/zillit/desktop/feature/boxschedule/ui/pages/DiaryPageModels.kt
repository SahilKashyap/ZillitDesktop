package com.zillit.desktop.feature.boxschedule.ui.pages

import androidx.compose.runtime.Immutable
import com.zillit.desktop.feature.boxschedule.domain.DiaryEvent
import com.zillit.desktop.feature.boxschedule.domain.ScheduleBlock

/** What the calendar draws, bucketed by day once per change of data or filter. */
@Immutable
internal class CalendarData(
    val schedules: Map<Long, List<ScheduleBlock>>,
    val events: Map<Long, List<DiaryEvent>>,
    val notes: Map<Long, List<DiaryEvent>>,
) {
    fun hasContent(dayKey: Long): Boolean =
        !schedules[dayKey].isNullOrEmpty() || !events[dayKey].isNullOrEmpty() || !notes[dayKey].isNullOrEmpty()
}

/** How a schedule day's detail is drawn where it is shown. */
internal data class DetailMode(
    /** A past day, or a viewer without rights: nothing to change. */
    val readOnly: Boolean,
    /** Inside the day drawer, whose own bar already creates — ZL-19856. */
    val hideInlineCreate: Boolean = false,
    /** The drawer's notes focus: the notes alone. */
    val hideEvents: Boolean = false,
)
