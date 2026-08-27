package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.boxschedule.domain.BoxScheduleViewer
import com.zillit.desktop.feature.boxschedule.domain.DiaryEvent
import com.zillit.desktop.feature.boxschedule.domain.DiaryKind
import com.zillit.desktop.feature.boxschedule.domain.DiaryMath
import com.zillit.desktop.feature.boxschedule.domain.MainCalendarLookup
import com.zillit.desktop.feature.maps.domain.MapViewer
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/**
 * The box schedule's Main Calendar merge — the Home calendar's events, folded
 * into read-only diary rows so the diary shows one timeline. A failed fetch
 * yields nothing; the web swallows it the same way.
 */
internal fun AppGraph.Ready.diaryCalendarLookup(): MainCalendarLookup = MainCalendarLookup { from, to ->
    when (val events = calendarRepository.events(from, to)) {
        is ZillitResult.Failure -> emptyList()
        is ZillitResult.Success -> events.data
            .filter { it.status?.equals("rejected", ignoreCase = true) != true }
            .map { event ->
                val zone = TimeZone.currentSystemDefault()
                val date = Instant.fromEpochMilliseconds(event.startMillis)
                    .toLocalDateTime(zone).date
                    .atStartOfDayIn(zone).toEpochMilliseconds()
                DiaryEvent(
                    id = "${DiaryMath.CALENDAR_PREFIX}${event.id}",
                    kind = DiaryKind.Event,
                    title = event.title,
                    body = event.description.orEmpty(),
                    date = date,
                    startDateTime = event.startMillis,
                    endDateTime = event.endMillis,
                    fullDay = event.isAllDay,
                    location = event.location.orEmpty(),
                    color = event.colorHex ?: "#3498DB",
                    scheduleDayId = "",
                    noteType = "",
                    repeatStatus = if (event.isRecurring) "recurring" else "",
                    occurrenceId = "",
                    masterEventId = "",
                    isRecurringInstance = false,
                    calendarEventId = event.id,
                    calendarSourced = true,
                    // Carried through so a calendar event mirrored into the
                    // diary keeps its Join button here too.
                    callType = event.callType?.wireValue.orEmpty(),
                    cncCallGroupId = event.cncGroupId,
                )
            }
    }
}

internal fun AppGraph.Ready.boxScheduleViewer(permissions: ProjectPermissions): BoxScheduleViewer {
    val context = projectContext?.context?.value
    return BoxScheduleViewer.from(
        permissions = permissions,
        userId = context?.profile?.userId.orEmpty(),
        displayName = context?.profile?.fullName.orEmpty(),
    )
}

internal fun AppGraph.Ready.mapViewer(permissions: ProjectPermissions): MapViewer {
    val context = projectContext?.context?.value
    return MapViewer.from(
        permissions = permissions,
        userId = context?.profile?.userId.orEmpty(),
        displayName = context?.profile?.fullName.orEmpty(),
    )
}
