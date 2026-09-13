package com.zillit.desktop.feature.boxschedule.domain

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.ProjectPermissions

/**
 * The production diary.
 *
 * Not a stripboard: there are no scenes here. A project has a set of
 * [ScheduleType]s (Shoot Day, Prep, Travel, Day Off…) and a set of
 * [ScheduleBlock]s — one document per run of dates of one type — plus timed
 * [DiaryEvent]s and titled notes that may hang off a block or stand alone on
 * a date. Everything is keyed by epoch-millisecond dates at local midnight;
 * the wire carries no order index and no status field.
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
    val createdAt: Long = 0,
    /** Bumped by the server on every write; the list's schedule cards show it past 1. */
    val version: Int = 0,
) {
    val sortedDays: List<Long> get() = calendarDays.sorted()
    val firstDay: Long get() = calendarDays.minOrNull() ?: startDate
    val lastDay: Long get() = calendarDays.maxOrNull() ?: endDate

    /** A "Day Off" row has no running number — the web prints a dash. */
    val isDayOff: Boolean get() = typeName.equals(DAY_OFF, ignoreCase = true)

    companion object {
        const val DAY_OFF = "Day Off"
    }
}

/** One exploded date of a block, with its per-type running number. */
data class ScheduleDayRow(
    val block: ScheduleBlock,
    val date: Long,
    /** "Shoot Day 4" — counted per [ScheduleBlock.typeName], 1-based, client-side. */
    val dayNumber: Int,
    /** True when the type changed from the previous row — the only "banner". */
    val isNewBlock: Boolean,
) {
    /** The selection and expansion key — the web's `${_id}-${singleDate}`. */
    val key: String get() = "${block.id}-$date"
}

/** `event` or `note` — the two records behind `/events`, split by `eventType`. */
enum class DiaryKind(val wire: String) {
    Event("event"), Note("note");

    companion object {
        fun fromWire(value: String?): DiaryKind = if (value == "note") Note else Event
    }
}

/** Someone outside the production invited to an event — the wire's `{ _id, mail }`. */
data class GuestEmail(val id: String, val mail: String)

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
    /** `none`, `at_time`, `5min`, `15min`, `30min`, `1hr`, `1day`. */
    val reminder: String = "",
    val timezone: String = "",
    /** The title's own colour, `#RRGGBB`; blank means the theme's. */
    val textColor: String = "",
    /** Epoch ms; zero when the event does not repeat. */
    val repeatEndDate: Long = 0,
    val audience: DiaryAudience = DiaryAudience(),
    val externalEmails: List<GuestEmail> = emptyList(),
    val organizerExcluded: Boolean = false,
    val createdByName: String = "",
    val createdAt: Long = 0,
) {
    /** There is a call to join — a plain in-person meeting is not one. */
    val isCallJoinable: Boolean
        get() = callType.lowercase() in setOf("audio", "video", "meet_in_person_call")

    /** And a room to join it in. The server mints one only for events with a call. */
    val hasCallRoom: Boolean get() = cncCallGroupId.isNotBlank()

    /** Video where the event asked for it; audio otherwise. */
    val prefersVideoCall: Boolean get() = callType.equals("video", ignoreCase = true)

    /**
     * Whether Join is still offered — the web's `checkTimeInterval(start, end + 1h)`:
     * anything that has not started more than half an hour ago, or that is
     * still inside its hour of grace after the end.
     */
    fun isCallOpen(now: Long): Boolean =
        now - CALL_LEAD_MS <= startDateTime || (now > startDateTime && now < endDateTime + CALL_GRACE_MS)

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

    /** The instant the calendar buckets by — the start wins over the bare date. */
    val anchor: Long get() = startDateTime.takeIf { it > 0 } ?: date

    private companion object {
        const val CALL_LEAD_MS = 30 * 60_000L
        const val CALL_GRACE_MS = 60 * 60_000L
    }
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
enum class ConflictAction(val wire: String, val label: String) {
    Replace("replace", "Replace"),
    Extend("extend", "Extend"),
    Overlap("overlap", "Overlap"),
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
    val reminder: String = "none",
    val textColor: String = "",
    val audience: DiaryAudience = DiaryAudience(),
    val externalEmails: List<GuestEmail> = emptyList(),
    val organizerExcluded: Boolean = false,
    /**
     * Only on create, and only once asked: mirror into the Home calendar.
     * Null sends nothing — an edit never carries the key.
     */
    val createInCalendar: Boolean? = null,
    /**
     * The event's call type. The write sends the whole event, so a blank here
     * erases a call somebody set on another client.
     */
    val callType: String = "",
) {
    companion object {
        const val DEFAULT_EVENT_COLOR = "#3498DB"
        val EVENT_COLORS = listOf("#3498DB", "#E74C3C", "#27AE60", "#F39C12", "#8E44AD", "#95A5A6")
        val REPEAT_OPTIONS = listOf("none", "daily", "weekly", "monthly")
    }
}

/**
 * How far a change to a recurring occurrence reaches.
 *
 * An update in [All] scope omits every query parameter (the server's legacy
 * whole-series rewrite); a delete in [All] scope sends `delete_type=all`.
 */
enum class RecurrenceScope(val wire: String) {
    Single("single"), ThisAndFollowing("this_and_following"), All("all")
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

    /**
     * Posting rights, or a project admin — Android's `BoxScheduleRights`
     * (`postingAccess == true || isAdmin`). The web reads posting rights
     * alone; the admin grant is this tool's own, deliberately kept.
     */
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

/**
 * The stored slug of the note type labelled "Personal Note" — "Crew Start"
 * until the backend's rename. Only the label changed: the slug is what is
 * saved, sent and compared, and must never be renamed.
 */
const val PERSONAL_NOTE_TYPE = "crew_start"

/** What the web shows for [PERSONAL_NOTE_TYPE], whatever label the server still sends. */
const val PERSONAL_NOTE_LABEL = "Personal Note"

/** A note only its author sees. Untyped notes, from before the type field existed, are General. */
val DiaryEvent.isPersonalNote: Boolean
    get() = kind == DiaryKind.Note && noteType == PERSONAL_NOTE_TYPE

/**
 * The order a day's entries are read in: Personal Notes first, then General
 * notes, then the events — the web's `groupNotesByType` (ZL-21253), with
 * the order inside each group preserved.
 */
fun List<DiaryEvent>.inDiaryOrder(): List<DiaryEvent> {
    val (notes, events) = partition { it.kind == DiaryKind.Note }
    val (personal, general) = notes.partition { it.isPersonalNote }
    return personal + general + events
}

/** How the diary PDF is laid out — the server's `format`. */
enum class DiaryPdfLayout(val wire: String, val label: String) {
    Calendar("calendar", "Calendar"),
    List("list", "List"),
}

/** What the PDF is for. The server logs the verb and renders the same file either way. */
enum class DiaryPdfAction(val wire: String) {
    Print("print"),
    Share("share"),
}

/**
 * What the user chose before the PDF was asked for. The defaults reproduce
 * the file the server always sent: Calendar, Personal Notes included.
 */
data class DiaryPdfOptions(
    val layout: DiaryPdfLayout = DiaryPdfLayout.Calendar,
    val includePersonalNotes: Boolean = true,
)

/** The generated PDF, staged in the production's storage — the phones' `AttachmentModel`. */
data class DiaryPdf(
    val media: String,
    val name: String,
    val bucket: String = "",
    val region: String = "",
    val contentType: String = "",
    val contentSubtype: String = "",
    val thumbnail: String = "",
    val caption: String = "",
    val fileSizeBytes: Long = 0,
)

/** Saves the generated PDF and opens it — the host's storage reader and Downloads. */
fun interface DiaryPdfTransfer {
    suspend fun open(pdf: DiaryPdf): ZillitResult<Unit>
}

/** Registers the generated PDF in Document Distribution by its storage keys; nothing is re-uploaded. */
fun interface DiaryPdfPublisher {
    suspend fun publish(pdf: DiaryPdf): ZillitResult<Unit>
}
