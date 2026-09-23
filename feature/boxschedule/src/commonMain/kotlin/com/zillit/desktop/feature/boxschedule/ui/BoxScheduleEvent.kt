package com.zillit.desktop.feature.boxschedule.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.boxschedule.domain.AudienceMode
import com.zillit.desktop.feature.boxschedule.domain.CalendarMode
import com.zillit.desktop.feature.boxschedule.domain.ConflictAction
import com.zillit.desktop.feature.boxschedule.domain.ContentFilter
import com.zillit.desktop.feature.boxschedule.domain.DateTab
import com.zillit.desktop.feature.boxschedule.domain.DiaryKind
import com.zillit.desktop.feature.boxschedule.domain.DiaryPdfLayout
import com.zillit.desktop.feature.boxschedule.domain.DiaryView
import com.zillit.desktop.feature.boxschedule.domain.HistoryAction
import com.zillit.desktop.feature.boxschedule.domain.ListMode
import com.zillit.desktop.feature.boxschedule.domain.RangeMode
import com.zillit.desktop.feature.boxschedule.domain.RecurrenceScope
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/** Everything the diary page can be asked to do, grouped by the surface that asks. */
sealed interface BoxScheduleEvent

/** The page's chrome: views, dates, search and filters, list rows, the command palette. */
sealed interface PageEvent : BoxScheduleEvent {
    data object Refresh : PageEvent
    data object DismissError : PageEvent
    data class SetView(val view: DiaryView) : PageEvent
    data class SaveDefaultView(val view: DiaryView) : PageEvent
    data class SetCalendarMode(val mode: CalendarMode) : PageEvent
    data class SaveDefaultCalendarMode(val mode: CalendarMode) : PageEvent
    data class SetListMode(val mode: ListMode) : PageEvent
    data class SaveDefaultListMode(val mode: ListMode) : PageEvent
    data class Step(val forward: Boolean) : PageEvent
    data object Today : PageEvent
    data class Search(val text: String) : PageEvent
    data object OpenFilters : PageEvent
    data class DraftContent(val content: ContentFilter) : PageEvent
    data class DraftType(val typeName: String) : PageEvent
    data object ClearFilters : PageEvent
    data object ApplyFilters : PageEvent
    data object CloseFilters : PageEvent
    data class ToggleRow(val key: String) : PageEvent
    data object EnterSelect : PageEvent
    data object ExitSelect : PageEvent
    data class ToggleSelect(val key: String) : PageEvent
    data object SelectAll : PageEvent
    data object DeselectAll : PageEvent
    data object TogglePalette : PageEvent
    data class PaletteQuery(val text: String) : PageEvent
    data class RunCommand(val command: DiaryCommand) : PageEvent
    data object ClosePalette : PageEvent
    data class JoinCall(val listKey: String) : PageEvent
}

/** The calendar's day drawer, its empty-day prompt, and an entry's details. */
sealed interface DayEvent : BoxScheduleEvent {
    data class OpenDay(val dayKey: Long, val focus: DayFocus = DayFocus.Full) : DayEvent
    data object ShowFullDay : DayEvent
    data object CloseDay : DayEvent
    data class OpenQuickAction(val dayKey: Long) : DayEvent
    data object CloseQuickAction : DayEvent
    data class ViewEntry(val listKey: String) : DayEvent
    data object CloseViewEntry : DayEvent
}

/** Schedules: the drawer, conflicts, scope prompts, and every way a schedule is deleted. */
sealed interface ScheduleEvent : BoxScheduleEvent {
    /** A new schedule; a [date] from the calendar is locked in as its start. */
    data class NewSchedule(val date: Long? = null) : ScheduleEvent

    /** A block's Edit; with a [date], a multi-day block first asks this date only or the whole. */
    data class EditSchedule(val blockId: String, val date: Long? = null) : ScheduleEvent
    data class ChooseScope(val scope: ScheduleScope) : ScheduleEvent
    data object ConfirmScope : ScheduleEvent
    data object CancelScope : ScheduleEvent
    data class SetType(val typeId: String) : ScheduleEvent
    data class SetTitle(val title: String) : ScheduleEvent
    data class SetTab(val tab: DateTab) : ScheduleEvent
    data class SetRangeMode(val mode: RangeMode) : ScheduleEvent
    data class SetStart(val date: LocalDate?) : ScheduleEvent
    data class SetCount(val text: String) : ScheduleEvent
    data class SetEnd(val date: LocalDate?) : ScheduleEvent
    data class TogglePick(val date: LocalDate) : ScheduleEvent
    data class SetDayWiseStart(val date: LocalDate?) : ScheduleEvent
    data class SetDayWiseEnd(val date: LocalDate?) : ScheduleEvent
    data class ToggleWeekday(val day: DayOfWeek) : ScheduleEvent
    data class SetSingleAction(val action: ConflictAction) : ScheduleEvent
    data object OpenNewType : ScheduleEvent
    data class SetNewTypeName(val name: String) : ScheduleEvent
    data class SetNewTypeColor(val color: String) : ScheduleEvent
    data object CancelNewType : ScheduleEvent
    data object CreateNewType : ScheduleEvent
    data object Save : ScheduleEvent
    data object CloseForm : ScheduleEvent
    data class PickConflict(val action: ConflictAction) : ScheduleEvent
    data object ResolveConflict : ScheduleEvent
    data object ConflictBack : ScheduleEvent
    data object CancelConflict : ScheduleEvent
    data class AskDeleteDay(val blockId: String, val date: Long?) : ScheduleEvent
    data object ConfirmDeleteDay : ScheduleEvent
    data object CancelDeleteDay : ScheduleEvent
    data class AskDeleteBlock(val blockId: String) : ScheduleEvent
    data object ConfirmDeleteBlock : ScheduleEvent
    data object CancelDeleteBlock : ScheduleEvent
    data class AskDeleteAllOn(val dayKey: Long) : ScheduleEvent
    data object ConfirmDeleteAllOn : ScheduleEvent
    data object CancelDeleteAllOn : ScheduleEvent
    data object AskBulkDelete : ScheduleEvent
    data object ConfirmBulkDelete : ScheduleEvent
    data object CancelBulkDelete : ScheduleEvent
}

/** Events and notes: the drawer, its audience and guests, recurring scopes, and deletes. */
sealed interface EntryEvent : BoxScheduleEvent {
    /** A new entry; a [date] seeds it, a [scheduleDayId] files and locks it on that schedule day. */
    data class NewEntry(val kind: DiaryKind, val date: Long? = null, val scheduleDayId: String? = null) : EntryEvent
    data class EditEntry(val listKey: String) : EntryEvent
    data class ChooseUpdateScope(val scope: RecurrenceScope) : EntryEvent
    data object ConfirmUpdateScope : EntryEvent
    data object CancelUpdateScope : EntryEvent
    data class SetLink(val key: String?) : EntryEvent
    data class SetTitle(val text: String) : EntryEvent
    data class SetDescription(val text: String) : EntryEvent
    data class SetFullDay(val on: Boolean) : EntryEvent
    data class SetStartDate(val date: LocalDate?) : EntryEvent
    data class SetStartTime(val time: LocalTime?) : EntryEvent
    data class SetEndTime(val time: LocalTime?) : EntryEvent
    data class SetRepeat(val value: String) : EntryEvent
    data class SetRepeatEnd(val date: LocalDate?) : EntryEvent
    data class SetTimezone(val zoneId: String) : EntryEvent
    data class SetReminder(val value: String) : EntryEvent
    data class SetCallType(val value: String) : EntryEvent
    data class SetTextColor(val color: String) : EntryEvent
    data class SetLocation(val text: String, val lat: Double? = null, val lng: Double? = null) : EntryEvent
    data class SetColor(val color: String) : EntryEvent
    data class SetOrganizerExcluded(val on: Boolean) : EntryEvent
    data class SetNoteType(val value: String) : EntryEvent
    data class SetNoteDate(val date: LocalDate?) : EntryEvent
    data class SetNoteTitle(val text: String) : EntryEvent
    data class SetNoteText(val text: String) : EntryEvent
    data class SetNoteColor(val color: String) : EntryEvent
    data object Save : EntryEvent
    data class AnswerCalendar(val mirror: Boolean) : EntryEvent
    data object Close : EntryEvent
    data object OpenAudience : EntryEvent
    data class AudienceTab(val mode: AudienceMode) : EntryEvent
    data class AudienceToggleUser(val id: String) : EntryEvent
    data class AudienceToggleDepartment(val id: String) : EntryEvent
    data class AudienceSetUsers(val ids: List<String>) : EntryEvent
    data class AudienceSetDepartments(val ids: List<String>) : EntryEvent
    data class AudiencePreset(val id: String) : EntryEvent
    data object AudienceToggleAllDepartments : EntryEvent
    data object AudienceToggleSelf : EntryEvent
    data class AudienceQuery(val text: String) : EntryEvent
    data class AudienceMembers(val presetId: String?) : EntryEvent
    data object AudienceDone : EntryEvent
    data object CloseAudience : EntryEvent
    data object OpenGuests : EntryEvent
    data class GuestDraft(val text: String) : EntryEvent
    data object AddGuest : EntryEvent
    data class RemoveGuest(val mail: String) : EntryEvent
    data object GuestsDone : EntryEvent
    data object CloseGuests : EntryEvent
    data class AskDelete(val listKey: String) : EntryEvent
    data class ChooseDeleteScope(val scope: RecurrenceScope) : EntryEvent
    data object ConfirmDelete : EntryEvent
    data object CancelDelete : EntryEvent
    data object CloseCalendarInfo : EntryEvent
}

/** The toolbar's panels: types, history, presets, the PDF, printing, sharing. */
sealed interface PanelEvent : BoxScheduleEvent {
    data object OpenTypes : PanelEvent
    data object CloseTypes : PanelEvent
    data class RecolorType(val typeId: String, val color: String) : PanelEvent
    data class StartTypeEdit(val typeId: String) : PanelEvent
    data class SetEditTitle(val text: String) : PanelEvent
    data class SetEditColor(val color: String) : PanelEvent
    data object SaveTypeEdit : PanelEvent
    data object CancelTypeEdit : PanelEvent
    data class DeleteType(val typeId: String) : PanelEvent
    data class SetNewTitle(val text: String) : PanelEvent
    data class SetNewColor(val color: String) : PanelEvent
    data object AddType : PanelEvent

    data object OpenHistory : PanelEvent
    data object CloseHistory : PanelEvent
    data class FilterHistory(val action: HistoryAction?) : PanelEvent
    data class HistoryDay(val date: LocalDate?) : PanelEvent
    data class OpenHistoryDetail(val entryId: String) : PanelEvent
    data object CloseHistoryDetail : PanelEvent

    data object OpenPresets : PanelEvent
    data object ClosePresets : PanelEvent
    data class SearchPresets(val text: String) : PanelEvent
    data object NewPreset : PanelEvent
    data class EditPreset(val presetId: String) : PanelEvent
    data class SetPresetName(val text: String) : PanelEvent
    data class SetPresetQuery(val text: String) : PanelEvent
    data class TogglePresetUser(val userId: String) : PanelEvent
    data class SetPresetUsers(val userIds: List<String>) : PanelEvent
    data object SavePreset : PanelEvent
    data object BackToPresets : PanelEvent
    data class AskDeletePreset(val presetId: String) : PanelEvent
    data object ConfirmDeletePreset : PanelEvent
    data object CancelDeletePreset : PanelEvent
    data class ShowPresetMembers(val presetId: String?) : PanelEvent

    data class OpenPdf(val destination: PdfDestination) : PanelEvent
    data class SetPdfLayout(val layout: DiaryPdfLayout) : PanelEvent
    data class SetPdfPersonalNotes(val include: Boolean) : PanelEvent
    data object SubmitPdf : PanelEvent
    data object ClosePdf : PanelEvent

    data object OpenPrintSelected : PanelEvent
    data class SetPrintPersonalNotes(val include: Boolean) : PanelEvent
    data object ConfirmPrintSelected : PanelEvent
    data object ClosePrintSelected : PanelEvent

    data object OpenShare : PanelEvent
    data object CloseShare : PanelEvent
    data object GenerateShareLink : PanelEvent
    data object CopyShareLink : PanelEvent
    data object CopyScheduleText : PanelEvent
}

/** The command palette's actions — `commandActions`. Writes are offered only to someone who may post. */
enum class DiaryCommand(private val labelKey: String, val hint: String, val writes: Boolean) {
    NewSchedule(S.desktop_bs_cmd_new_schedule, "N", writes = true),
    NewEvent(S.new_event, "E", writes = true),
    NewNote(S.desktop_bs_cmd_new_note, "", writes = true),
    EditTypes(S.desktop_bs_cmd_edit_schedule_types, "", writes = true),
    CalendarView(S.desktop_bs_cmd_switch_calendar_view, "", writes = false),
    ListView(S.desktop_bs_cmd_switch_list_view, "", writes = false),
    Today(S.desktop_bs_cmd_jump_to_today, "T", writes = false),
    Previous(S.txt_prev, "←", writes = false),
    Next(S.next, "→", writes = false),
    History(S.desktop_bs_cmd_open_activity_log, "", writes = false),
    ShareLink(S.share_via_link, "", writes = false),
    Print(S.print, "", writes = true),
    ;

    val label: String get() = str(labelKey)

    companion object {
        /** The palette's list: writes first when the viewer may post, as the web orders it. */
        fun available(mayEdit: Boolean, query: String): List<DiaryCommand> {
            val q = query.trim().lowercase()
            return (if (mayEdit) entries.sortedByDescending { it.writes } else entries.filterNot { it.writes })
                .filter { q.isEmpty() || it.label.lowercase().contains(q) }
        }
    }
}

sealed interface BoxScheduleEffect {
    /** A toast: green for something that happened, red for something that did not. */
    data class Notice(val message: String, val success: Boolean = false) : BoxScheduleEffect

    /**
     * Joins an event's call, for the host to hand to the calling stack.
     *
     * The same room a calendar event's Join opens — this module keeps no
     * dependency on calling, only on the id.
     */
    data class JoinCall(val roomId: String, val title: String, val video: Boolean) : BoxScheduleEffect

    /** Puts text on the clipboard — a share link, or the schedule as text. */
    data class CopyText(val text: String) : BoxScheduleEffect
}
