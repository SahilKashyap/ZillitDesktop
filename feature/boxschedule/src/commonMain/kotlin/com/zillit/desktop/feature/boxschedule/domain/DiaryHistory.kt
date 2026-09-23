package com.zillit.desktop.feature.boxschedule.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.math.abs

/**
 * The diary's history — `GET /box-schedule/activity-log` and `/revisions`,
 * read the way the web's `ActivityLogDrawer` reads them.
 */
enum class HistoryAction(
    val wire: String,
    private val labelKey: String,
    val color: String,
    private val filterLabelKey: String,
) {
    Created("created", S.history_label_added, "#27AE60", S.history_added),
    Updated("updated", S.history_label_changed, "#3498DB", S.history_changed),
    Deleted("deleted", S.history_label_removed, "#E74C3C", S.history_removed),
    Printed("printed", S.history_label_printed, "#8E44AD", S.history_printed),
    Shared("shared", S.history_label_shared, "#F39C12", S.history_shared),
    ;

    val label: String get() = str(labelKey)
    val filterLabel: String get() = str(filterLabelKey)

    companion object {
        fun fromWire(value: String): HistoryAction? = entries.firstOrNull { it.wire == value }
    }
}

/** One activity-log row. */
data class HistoryEntry(
    val id: String,
    /** `created`, `updated`, `deleted`, `printed`, `shared` — or whatever else the server adds. */
    val action: String,
    /** `schedule_day`, `schedule_type`, `event`, `note`. */
    val targetType: String,
    val targetId: String,
    val targetTitle: String,
    /** The server's own context line, when it wrote one. */
    val details: String,
    val performedById: String,
    /** A name populated onto the row itself; blank when the row carries only an id. */
    val performedByName: String,
    val createdAt: Long,
) {
    val known: HistoryAction? get() = HistoryAction.fromWire(action)

    /** "ADDED", or the raw verb upper-cased for one this client has no word for. */
    val actionLabel: String get() = known?.label ?: action.uppercase()

    val targetLabel: String
        get() = when (targetType) {
            "schedule_day" -> str(S.history_target_schedule)
            "schedule_type" -> str(S.history_target_schedule_type)
            "event" -> str(S.history_target_event)
            "note" -> str(S.history_target_note)
            else -> targetType
        }
}

/** A stored version of one record, paired to a log row by target and time. */
data class DiaryRevision(
    val targetId: String,
    val createdAt: Long,
    val number: Int,
    val snapshot: HistorySnapshot,
)

/**
 * What the details dialog can say about a record. Every field is optional:
 * the shape varies by target type, and a deleted record may survive only as
 * the log's title.
 */
data class HistorySnapshot(
    val title: String = "",
    val eventType: String = "",
    val description: String = "",
    val start: Long = 0,
    val end: Long = 0,
    val fullDay: Boolean = false,
    val timezone: String = "",
    val location: String = "",
    val reminder: String = "",
    val repeatStatus: String = "",
    val repeatEndDate: Long = 0,
    val callType: String = "",
    val color: String = "",
    val audience: DiaryAudience = DiaryAudience(),
    val externalEmails: List<String> = emptyList(),
    val calendarDays: List<Long> = emptyList(),
    val organizerExcluded: Boolean = false,
    val scheduleDayId: String = "",
    val typeId: String = "",
) {
    /**
     * [newer] laid over this one, a field at a time — the web's
     * `{ ...revision, ...snapshot, ...log, ...liveRecord }`, except that an
     * empty field never blanks a filled one.
     */
    fun overlaidWith(newer: HistorySnapshot?): HistorySnapshot {
        if (newer == null) return this
        return HistorySnapshot(
            title = newer.title.ifBlank { title },
            eventType = newer.eventType.ifBlank { eventType },
            description = newer.description.ifBlank { description },
            start = newer.start.takeIf { it > 0 } ?: start,
            end = newer.end.takeIf { it > 0 } ?: end,
            fullDay = newer.fullDay || fullDay,
            timezone = newer.timezone.ifBlank { timezone },
            location = newer.location.ifBlank { location },
            reminder = newer.reminder.ifBlank { reminder },
            repeatStatus = newer.repeatStatus.ifBlank { repeatStatus },
            repeatEndDate = newer.repeatEndDate.takeIf { it > 0 } ?: repeatEndDate,
            callType = newer.callType.ifBlank { callType },
            color = newer.color.ifBlank { color },
            audience = if (newer.audience.isSet) newer.audience else audience,
            externalEmails = newer.externalEmails.ifEmpty { externalEmails },
            calendarDays = newer.calendarDays.ifEmpty { calendarDays },
            organizerExcluded = newer.organizerExcluded || organizerExcluded,
            scheduleDayId = newer.scheduleDayId.ifBlank { scheduleDayId },
            typeId = newer.typeId.ifBlank { typeId },
        )
    }

    companion object {
        fun of(event: DiaryEvent) = HistorySnapshot(
            title = event.title,
            eventType = event.kind.wire,
            description = event.body,
            start = event.startDateTime.takeIf { it > 0 } ?: event.date,
            end = event.endDateTime,
            fullDay = event.fullDay,
            timezone = event.timezone,
            location = event.location,
            reminder = event.reminder,
            repeatStatus = event.repeatStatus,
            repeatEndDate = event.repeatEndDate,
            callType = event.callType,
            color = event.color,
            audience = event.audience,
            externalEmails = event.externalEmails.map { it.mail },
            organizerExcluded = event.organizerExcluded,
            scheduleDayId = event.scheduleDayId,
        )

        fun of(block: ScheduleBlock) = HistorySnapshot(
            title = block.title,
            color = block.color,
            calendarDays = block.calendarDays,
            typeId = block.typeId,
        )

        fun of(type: ScheduleType) = HistorySnapshot(title = type.title, color = type.color)
    }
}

object DiaryHistory {

    /** The log's filters, newest first. */
    fun visible(
        entries: List<HistoryEntry>,
        action: HistoryAction?,
        day: LocalDate?,
        zone: TimeZone,
    ): List<HistoryEntry> = entries
        .filter { action == null || it.action == action.wire }
        .filter { day == null || DiaryCalendar.dateOf(it.createdAt, zone) == day }
        .sortedByDescending { it.createdAt }

    /** The revision written with [entry]: same record, within ten seconds, the nearest. */
    fun revisionFor(entry: HistoryEntry, revisions: List<DiaryRevision>): DiaryRevision? {
        if (entry.targetId.isBlank()) return null
        return revisions
            .filter { it.targetId == entry.targetId && abs(it.createdAt - entry.createdAt) < PAIRING_WINDOW_MS }
            .minByOrNull { abs(it.createdAt - entry.createdAt) }
    }

    /** The record as it is now, when it still exists. */
    fun live(
        entry: HistoryEntry,
        events: List<DiaryEvent>,
        blocks: List<ScheduleBlock>,
        types: List<ScheduleType>,
    ): HistorySnapshot? = when (entry.targetType) {
        "event", "note" -> events.firstOrNull { it.masterId == entry.targetId || it.id == entry.targetId }
            ?.let(HistorySnapshot::of)
        "schedule_day" -> blocks.firstOrNull { it.id == entry.targetId }?.let(HistorySnapshot::of)
        "schedule_type" -> types.firstOrNull { it.id == entry.targetId }?.let(HistorySnapshot::of)
        else -> null
    }

    private const val PAIRING_WINDOW_MS = 10_000L
}
