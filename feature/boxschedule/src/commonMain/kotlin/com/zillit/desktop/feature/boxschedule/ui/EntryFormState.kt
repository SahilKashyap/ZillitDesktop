package com.zillit.desktop.feature.boxschedule.ui

import com.zillit.desktop.feature.boxschedule.domain.AudienceMode
import com.zillit.desktop.feature.boxschedule.domain.DiaryAudience
import com.zillit.desktop.feature.boxschedule.domain.DiaryDepartment
import com.zillit.desktop.feature.boxschedule.domain.DiaryDraft
import com.zillit.desktop.feature.boxschedule.domain.DiaryKind
import com.zillit.desktop.feature.boxschedule.domain.GuestEmail
import com.zillit.desktop.feature.boxschedule.domain.RecurrenceScope
import com.zillit.desktop.feature.boxschedule.domain.UserPreset
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * The event / note drawer — `CreateEventModal`.
 *
 * Event and note keep their own title, date and colour, as the web's two tabs
 * do; [kind] says which half is live. A recurring edit carries its [scope] and
 * the occurrence it started from.
 */
data class EntryForm(
    val kind: DiaryKind,
    /** The row being edited; null for a new entry. */
    val listKey: String? = null,
    /** The document the edit PUTs to. */
    val masterId: String? = null,
    /** Null for a create or a plain edit; set when a recurring occurrence's scope was chosen. */
    val scope: RecurrenceScope? = null,
    val occurrenceDate: Long? = null,
    /** Opened from a schedule's own detail: filed on that schedule, its date locked, no link picker. */
    val pinnedDayId: String? = null,
    /** The date the drawer's header shows — a quick create's or the pinned day's. */
    val headerDate: LocalDate? = null,
    /** "Link to a schedule day": `${blockId}|${dayKey}`. */
    val linkKey: String? = null,
    // Event
    val title: String = "",
    val description: String = "",
    val startDate: LocalDate? = null,
    val startTime: LocalTime? = null,
    /** Mirrors the start date; a multi-day event is a repeat, not a span. */
    val endDate: LocalDate? = null,
    val endTime: LocalTime? = null,
    val fullDay: Boolean = false,
    val location: String = "",
    val locationLat: Double? = null,
    val locationLng: Double? = null,
    val repeat: String = "none",
    val repeatEnd: LocalDate? = null,
    val timezone: String = "",
    val reminder: String = "none",
    val callType: String = "",
    val textColor: String = "",
    val color: String = DiaryDraft.DEFAULT_EVENT_COLOR,
    val guests: List<String> = emptyList(),
    /** The guests the event had, so an unchanged address keeps its `_id`. */
    val originalGuests: List<GuestEmail> = emptyList(),
    val organizerExcluded: Boolean = false,
    // Note
    val noteType: String = "general",
    val noteDate: LocalDate? = null,
    val noteTitle: String = "",
    val noteText: String = "",
    val noteColor: String = DiaryDraft.DEFAULT_EVENT_COLOR,
    // Both
    val audience: DiaryAudience = DiaryAudience(),
    val errors: Map<EntryField, String> = emptyMap(),
    val saving: Boolean = false,
    /** "Set Reminder on Home Calendar?" — asked once, before a new event is sent. */
    val askCalendar: Boolean = false,
    val audiencePicker: AudiencePicker? = null,
    val guestsDialog: GuestsDialog? = null,
) {
    val isEdit: Boolean get() = masterId != null
    val isNote: Boolean get() = kind == DiaryKind.Note
    val isSingleScope: Boolean get() = scope == RecurrenceScope.Single
    val isThisAndFollowing: Boolean get() = scope == RecurrenceScope.ThisAndFollowing

    /** Entry point B: created from a schedule day, whose date the note may not leave. */
    val noteDateLocked: Boolean get() = pinnedDayId != null && !isEdit

    /** The link picker shows unless the entry was pinned to a day on create. */
    val showsLinkPicker: Boolean get() = pinnedDayId == null || isEdit

    val linkedDayId: String? get() = linkKey?.substringBefore('|')?.takeIf { it.isNotBlank() }

    val heading: String
        get() = when {
            scope == RecurrenceScope.Single -> "Edit this event"
            scope == RecurrenceScope.ThisAndFollowing -> "Edit this and following"
            scope == RecurrenceScope.All -> "Edit all events"
            isEdit -> if (isNote) "Edit Note" else "Edit Event"
            else -> if (isNote) "Add Note" else "Add Event"
        }

    val saveLabel: String
        get() = when {
            isNote -> if (isEdit) "Update Note" else "Save Note"
            scope == RecurrenceScope.Single -> "Save this event"
            scope == RecurrenceScope.ThisAndFollowing -> "Save this and following"
            scope == RecurrenceScope.All -> "Save all events"
            isEdit -> "Update Event"
            else -> "Save Event"
        }
}

/** The fields an entry's validation can flag, in the order the web checks them. */
enum class EntryField {
    Title, StartDate, StartTime, EndTime, EndDate, CallType, Location, RepeatEnd, Audience, NoteTitle, NoteDate,
}

/**
 * "Select Invitees" — five tabs, one choice. Picking on a tab clears every
 * other; switching tabs without picking does not, so browsing loses nothing.
 */
data class AudiencePicker(
    val tab: AudienceMode = AudienceMode.Users,
    val userIds: List<String> = emptyList(),
    val departmentIds: List<String> = emptyList(),
    val presetId: String? = null,
    val allDepartments: Boolean = false,
    val self: Boolean = false,
    val userQuery: String = "",
    val departmentQuery: String = "",
    val presetQuery: String = "",
    /** The users already on the entry when the picker opened — listed first, never re-sorted under the cursor. */
    val pinned: Set<String> = emptySet(),
    val departments: List<DiaryDepartment> = emptyList(),
    val departmentsLoading: Boolean = false,
    val presets: List<UserPreset> = emptyList(),
    val presetsLoading: Boolean = false,
    /** The preset whose members are shown. */
    val membersOf: String? = null,
) {
    /** The Done button's number, for the active tab only — `doneCount`. */
    fun doneCount(crewSize: Int): Int = when (tab) {
        AudienceMode.AllDepartments -> if (allDepartments) crewSize else 0
        AudienceMode.Self -> if (self) 1 else 0
        AudienceMode.Departments -> departmentIds.size
        AudienceMode.Users -> userIds.size
        AudienceMode.Presets -> if (presetId.isNullOrBlank()) 0 else 1
        AudienceMode.None -> 0
    }

    /** What Done hands back — the active tab's choice, every other list empty. */
    fun result(): DiaryAudience = when (tab) {
        AudienceMode.AllDepartments -> DiaryAudience(AudienceMode.AllDepartments)
        AudienceMode.Self -> DiaryAudience(AudienceMode.Self)
        AudienceMode.Departments -> DiaryAudience(AudienceMode.Departments, departmentIds = departmentIds)
        AudienceMode.Users -> DiaryAudience(AudienceMode.Users, userIds = userIds)
        AudienceMode.Presets -> DiaryAudience(AudienceMode.Presets, presetId = presetId)
        AudienceMode.None -> DiaryAudience()
    }

    /** Everything but [keep] cleared — the cross-tab rule. */
    fun keepingOnly(keep: AudienceMode): AudiencePicker = copy(
        userIds = if (keep == AudienceMode.Users) userIds else emptyList(),
        departmentIds = if (keep == AudienceMode.Departments) departmentIds else emptyList(),
        presetId = if (keep == AudienceMode.Presets) presetId else null,
        allDepartments = keep == AudienceMode.AllDepartments && allDepartments,
        self = keep == AudienceMode.Self && self,
    )

    companion object {
        /** Reopened on the tab the entry's audience is on. */
        fun from(audience: DiaryAudience): AudiencePicker = AudiencePicker(
            tab = audience.mode.takeIf { it != AudienceMode.None } ?: AudienceMode.Users,
            userIds = audience.userIds,
            departmentIds = audience.departmentIds,
            presetId = audience.presetId,
            allDepartments = audience.mode == AudienceMode.AllDepartments,
            self = audience.mode == AudienceMode.Self,
            pinned = audience.userIds.toSet(),
        )
    }
}

/** "External Guests" — addresses typed one at a time. */
data class GuestsDialog(
    val emails: List<String>,
    val draft: String = "",
    val error: String? = null,
)

/** A recurring occurrence's scope, asked before an edit or a delete. */
data class EntryScopePrompt(
    val listKey: String,
    val scope: RecurrenceScope = RecurrenceScope.Single,
    val working: Boolean = false,
)
