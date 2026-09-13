package com.zillit.desktop.feature.boxschedule.ui

import com.zillit.desktop.feature.boxschedule.domain.BoxScheduleViewer
import com.zillit.desktop.feature.boxschedule.domain.CalendarMode
import com.zillit.desktop.feature.boxschedule.domain.ContentFilter
import com.zillit.desktop.feature.boxschedule.domain.DiaryCalendar
import com.zillit.desktop.feature.boxschedule.domain.DiaryEvent
import com.zillit.desktop.feature.boxschedule.domain.DiaryFilter
import com.zillit.desktop.feature.boxschedule.domain.DiaryPerson
import com.zillit.desktop.feature.boxschedule.domain.DiaryView
import com.zillit.desktop.feature.boxschedule.domain.ListMode
import com.zillit.desktop.feature.boxschedule.domain.NoteType
import com.zillit.desktop.feature.boxschedule.domain.ScheduleBlock
import com.zillit.desktop.feature.boxschedule.domain.ScheduleDayRow
import com.zillit.desktop.feature.boxschedule.domain.ScheduleType
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus

/**
 * The production diary page — the web's `BoxSchedulePage`.
 *
 * Data, the page chrome ([PageState]) and whatever is open over it
 * ([DiaryOverlays]) are separate values, so a reload never closes a form and
 * a form never has to know which view is behind it.
 */
data class BoxScheduleUiState(
    val viewer: BoxScheduleViewer = BoxScheduleViewer(),
    val loading: Boolean = false,
    /** The first load has answered; a later load is a refresh, drawn as a pill, not a spinner. */
    val loadedOnce: Boolean = false,
    /** A load that failed — a banner, dismissible. Writes report through notices. */
    val error: String? = null,
    val types: List<ScheduleType> = emptyList(),
    val blocks: List<ScheduleBlock> = emptyList(),
    /** Every block exploded into dated, numbered rows — the web's `flatSchedule`. */
    val rows: List<ScheduleDayRow> = emptyList(),
    /** Box-schedule events and notes, with the Main Calendar's merged in read-only. */
    val events: List<DiaryEvent> = emptyList(),
    val noteTypes: List<NoteType> = NoteType.DEFAULTS,
    val people: List<DiaryPerson> = emptyList(),
    val today: LocalDate = EPOCH,
    val nowMillis: Long = 0,
    /** The zone every date on the page is read in. */
    val zone: TimeZone = TimeZone.UTC,
    /** Document Distribution is wired and the viewer may post into it. */
    val canPublish: Boolean = false,
    /** The system printer is wired — Print Selected exists. */
    val canPrint: Boolean = false,
    val historyBadge: Int = 0,
    val page: PageState = PageState(),
    val overlays: DiaryOverlays = DiaryOverlays(),
) {
    val mayEdit: Boolean get() = viewer.mayEdit
    val todayKey: Long get() = DiaryCalendar.startOf(today, zone)

    fun isPast(dayKey: Long): Boolean = dayKey < todayKey

    fun block(id: String): ScheduleBlock? = blocks.firstOrNull { it.id == id }
    fun entry(listKey: String): DiaryEvent? = events.firstOrNull { it.listKey == listKey }
    fun person(id: String): DiaryPerson? = people.firstOrNull { it.id == id }

    /** The list's rows after the search box and the type filter — what Select All selects. */
    val filteredRows: List<ScheduleDayRow> get() = page.filter.listRows(rows, zone)

    /** The rows ticked in select mode, in date order. */
    val selectedRows: List<ScheduleDayRow> get() = rows.filter { it.key in page.selected }

    companion object {
        val EPOCH = LocalDate(1970, 1, 1)
    }
}

/** The page's own controls: which view, which dates, which filter, which rows. */
data class PageState(
    val view: DiaryView = DiaryView.Calendar,
    val defaultView: DiaryView = DiaryView.Calendar,
    val calendarMode: CalendarMode = CalendarMode.Month,
    val defaultCalendarMode: CalendarMode = CalendarMode.Month,
    val listMode: ListMode = ListMode.ByDate,
    val defaultListMode: ListMode = ListMode.ByDate,
    /** The first of the month the Month view shows; null until the page knows today. */
    val month: LocalDate? = null,
    /** The Monday the Week view starts on. */
    val weekStart: LocalDate? = null,
    /** The Day view's day. */
    val day: LocalDate? = null,
    val filter: DiaryFilter = DiaryFilter(),
    /** The list row opened to its day detail — `${blockId}-${date}`. */
    val expandedRow: String? = null,
    val selecting: Boolean = false,
    val selected: Set<String> = emptySet(),
) {
    fun weekDays(): List<LocalDate> {
        val start = weekStart ?: return emptyList()
        return (0 until DiaryCalendar.DAYS_IN_WEEK).map { start.plus(it, DateTimeUnit.DAY) }
    }

    /** Snaps every mode to [date] — the web's `focusRequest`. */
    fun focusedOn(date: LocalDate): PageState = copy(
        month = LocalDate(date.year, date.month, 1),
        weekStart = DiaryCalendar.weekStart(date),
        day = date,
    )
}

/** Everything that can be open over the page. Several stack: a prompt over a drawer over a view. */
data class DiaryOverlays(
    val day: DayDrawer? = null,
    /** An empty future date was clicked: "What would you like to create?" */
    val quickAction: Long? = null,
    /** The entry whose complete details are open — its list key. */
    val viewing: String? = null,
    val scheduleForm: ScheduleForm? = null,
    val conflict: ConflictPrompt? = null,
    val scheduleScope: ScheduleScopePrompt? = null,
    val deleteDay: DeleteDayPrompt? = null,
    /** "Delete Script" on a By Schedule card — the block's id. */
    val deleteBlock: String? = null,
    /** "Delete all schedules on this day?" — the day's key. */
    val deleteAllOn: Long? = null,
    val bulkDelete: Boolean = false,
    val entryForm: EntryForm? = null,
    val updateScope: EntryScopePrompt? = null,
    val deleteEntry: EntryScopePrompt? = null,
    /** "This event was created in the Calendar module…" — true when it was a delete. */
    val calendarInfo: CalendarInfo? = null,
    val types: TypesManager? = null,
    val history: HistoryPanel? = null,
    val presets: PresetsPanel? = null,
    val pdf: PdfSheet? = null,
    val printSelected: PrintPrompt? = null,
    val share: SharePanel? = null,
    val filters: FilterDraft? = null,
    val palette: PalettePanel? = null,
)

/** The calendar's day drawer, scoped to what was clicked. */
data class DayDrawer(val dayKey: Long, val focus: DayFocus = DayFocus.Full)

sealed interface DayFocus {
    /** A plain date click: everything on the day. */
    data object Full : DayFocus

    /** A schedule pill: that schedule alone. */
    data class Schedule(val blockId: String) : DayFocus

    /** The notes group: only notes. */
    data object Notes : DayFocus
}

data class CalendarInfo(val forDelete: Boolean)

/** The Filter dialog's choices before Apply. */
data class FilterDraft(val typeName: String = "", val content: ContentFilter = ContentFilter.All)

data class PalettePanel(val query: String = "")
