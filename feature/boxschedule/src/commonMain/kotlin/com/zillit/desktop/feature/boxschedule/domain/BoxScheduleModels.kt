package com.zillit.desktop.feature.boxschedule.domain

import com.zillit.desktop.core.permissions.ProjectPermissions

/**
 * The production diary.
 *
 * Not a stripboard: there are no scenes here. A project has a set of
 * [ScheduleType]s (Shoot Day, Prep, Travel, Day Off…) and a set of
 * [ScheduleBlock]s — one document per run of dates of one type — plus timed
 * [DiaryEvent]s and titled [DiaryNote]s that may hang off a block or stand
 * alone on a date. Everything is keyed by epoch-millisecond dates at local
 * midnight; the wire carries no order index and no status field.
 */
data class ScheduleType(
    val id: String,
    val title: String,
    /** `#RRGGBB`. */
    val color: String,
    /** System types can be recoloured but never renamed or deleted. */
    val systemDefined: Boolean,
)

/**
 * A run of dates of one type — what the UI calls a schedule block.
 *
 * The type's name and colour are DENORMALISED onto each block by the server,
 * so a type rename forces a full block refetch.
 */
data class ScheduleBlock(
    val id: String,
    val title: String,
    val typeId: String,
    val typeName: String,
    val color: String,
    /** Epoch ms at local midnight; not guaranteed sorted on the wire. */
    val calendarDays: List<Long>,
    val startDate: Long,
    val endDate: Long,
    val numberOfDays: Int,
    /** `by_days` or `by_dates`. */
    val dateRangeType: String,
)

/** One exploded date of a block, with its per-type running number. */
data class ScheduleDayRow(
    val block: ScheduleBlock,
    val date: Long,
    /** "Shoot Day 4" — counted per [ScheduleBlock.typeName], 1-based, client-side. */
    val dayNumber: Int,
    /** True when the type changed from the previous row — the only "banner". */
    val isNewBlock: Boolean,
)

/** `event` or `note` — the two records behind `/events`, split by `eventType`. */
enum class DiaryKind(val wire: String) {
    Event("event"), Note("note");

    companion object {
        fun fromWire(value: String?): DiaryKind = if (value == "note") Note else Event
    }
}

data class DiaryEvent(
    val id: String,
    val kind: DiaryKind,
    val title: String,
    /** Body text: `description` for events, `notes` for notes. */
    val body: String,
    /** Start-of-day epoch ms. */
    val date: Long,
    val startDateTime: Long,
    val endDateTime: Long,
    val fullDay: Boolean,
    val location: String,
    /** The picked place's coordinates; null when the location is typed text. */
    val locationLat: Double? = null,
    val locationLng: Double? = null,
    val color: String,
    val scheduleDayId: String,
    val noteType: String,
    val repeatStatus: String,
    /** `"<masterId>_<startMs>"` for server-expanded occurrences; blank otherwise. */
    val occurrenceId: String,
    val masterEventId: String,
    val isRecurringInstance: Boolean,
    /** Set when mirrored into the Main Calendar; drives dedupe. */
    val calendarEventId: String,
    /** True for read-only rows merged in from the Main Calendar. */
    val calendarSourced: Boolean = false,
    /** `audio`, `video`, `meet_in_person`, `meet_in_person_call`, or blank. */
    val callType: String = "",
    /**
     * The chat room the event's call is held in, inherited from the calendar
     * event it was created with. It IS the call: everyone dials this same room.
     */
    val cncCallGroupId: String = "",
) {
    /** There is a call to join — a plain in-person meeting is not one. */
    val isCallJoinable: Boolean
        get() = callType.lowercase() in setOf("audio", "video", "meet_in_person_call")

    /** And a room to join it in. The server mints one only for events with a call. */
    val hasCallRoom: Boolean get() = cncCallGroupId.isNotBlank()

    /** Video where the event asked for it; audio otherwise. */
    val prefersVideoCall: Boolean get() = callType.equals("video", ignoreCase = true)

    /** List key: never `id` alone, or a whole recurring series collapses. */
    val listKey: String get() = occurrenceId.ifBlank { id }

    /** The document id to send on edit or delete. */
    val masterId: String get() = masterEventId.ifBlank { id }

    val isRecurring: Boolean
        get() = isRecurringInstance || (repeatStatus.isNotBlank() && repeatStatus != "none")

    /**
     * The occurrence's own instant: the number after the LAST `_` in
     * [occurrenceId] when it parses, else the start, else the date.
     */
    val occurrenceDate: Long
        get() = occurrenceId.substringAfterLast('_', "").toLongOrNull()?.takeIf { it > 0 }
            ?: startDateTime.takeIf { it > 0 }
            ?: date
}

/** What a new or edited block says: type, dates, optional title. */
data class BlockDraft(
    val typeId: String,
    val title: String,
    val calendarDays: List<Long>,
    /** `by_dates` for an ad-hoc pick, `by_days` otherwise. */
    val byDates: Boolean,
) {
    val startDate: Long get() = calendarDays.minOrNull() ?: 0L
    val endDate: Long get() = calendarDays.maxOrNull() ?: 0L
    val numberOfDays: Int get() = calendarDays.size
}

/** How a colliding date is resolved on create/edit. */
enum class ConflictAction(val wire: String) {
    Replace("replace"), Extend("extend"), Overlap("overlap")
}

/** A colliding date the server reported instead of writing. */
data class DateConflict(
    val date: Long,
    val existingType: String,
    val existingColor: String,
    val existingTitle: String,
)

/** What a new or edited event/note says. */
data class DiaryDraft(
    val kind: DiaryKind,
    val title: String,
    val body: String,
    val date: Long,
    val startDateTime: Long,
    val endDateTime: Long,
    val fullDay: Boolean,
    val location: String = "",
    /** Set when the location came from the map picker (web: `CreateEventModal.jsx:202-203`). */
    val locationLat: Double? = null,
    val locationLng: Double? = null,
    val color: String = DEFAULT_EVENT_COLOR,
    /** Blank means standalone — the key is OMITTED on the wire, never null. */
    val scheduleDayId: String = "",
    val noteType: String = "general",
    val repeatStatus: String = "none",
    /** Zero when no repeat — the wire wants `0`, not null. */
    val repeatEndDate: Long = 0,
    val timezone: String = "",
    /** Only on create: mirror into the Home calendar. */
    val createInCalendar: Boolean = false,
    /**
     * The event's call type, carried through an edit unchanged.
     *
     * The desktop offers no way to attach a call, so this is only ever what
     * the event already had. It has to ride along regardless: the write sends
     * the whole event, and a blank here erases a call somebody set on another
     * client — the event keeps its room id and loses the type that makes it
     * joinable.
     */
    val callType: String = "",
) {
    companion object {
        const val DEFAULT_EVENT_COLOR = "#3498DB"
        val EVENT_COLORS = listOf("#3498DB", "#E74C3C", "#27AE60", "#F39C12", "#8E44AD", "#95A5A6")
        val REPEAT_OPTIONS = listOf("none", "daily", "weekly", "monthly")
    }
}

/** Editing scope for a recurring event. `All` omits the query params. */
enum class RecurrenceScope(val wire: String?) {
    Single("single"), ThisAndFollowing("this_and_following"), All(null)
}

data class BoxScheduleViewer(
    val userId: String = "",
    val displayName: String = "",
    val canView: Boolean = true,
    val canEdit: Boolean = false,
    val isAdmin: Boolean = false,
    val ready: Boolean = false,
) {
    val isBlocked: Boolean get() = ready && !canView && !isAdmin
    val mayEdit: Boolean get() = isAdmin || canEdit

    companion object {
        /** The module checks ONLY this identifier, though a legacy tile shares its route. */
        const val TOOL_IDENTIFIER = "box_schedule_tool"

        fun from(permissions: ProjectPermissions, userId: String, displayName: String): BoxScheduleViewer {
            val access = permissions.access(TOOL_IDENTIFIER)
            if (!access.enabled && permissions.visibleTools.isEmpty()) {
                return BoxScheduleViewer(userId = userId, displayName = displayName, ready = false)
            }
            val post = permissions.canPost(TOOL_IDENTIFIER)
            return BoxScheduleViewer(
                userId = userId,
                displayName = displayName,
                // The web's rule: view is view OR post.
                canView = permissions.canView(TOOL_IDENTIFIER) || post,
                canEdit = post,
                isAdmin = permissions.isAdmin,
                ready = true,
            )
        }
    }
}
