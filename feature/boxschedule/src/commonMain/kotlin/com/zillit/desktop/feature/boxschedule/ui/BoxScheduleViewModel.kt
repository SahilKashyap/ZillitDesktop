package com.zillit.desktop.feature.boxschedule.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.boxschedule.domain.BoxScheduleHost
import com.zillit.desktop.feature.boxschedule.domain.BoxScheduleRepository
import com.zillit.desktop.feature.boxschedule.domain.BoxScheduleViewer
import com.zillit.desktop.feature.boxschedule.domain.CalendarMode
import com.zillit.desktop.feature.boxschedule.domain.DiaryCalendar
import com.zillit.desktop.feature.boxschedule.domain.DiaryKind
import com.zillit.desktop.feature.boxschedule.domain.DiaryMath
import com.zillit.desktop.feature.boxschedule.domain.DiaryPreferences
import com.zillit.desktop.feature.boxschedule.domain.DiaryView
import com.zillit.desktop.feature.boxschedule.domain.ListMode
import com.zillit.desktop.feature.boxschedule.domain.MainCalendarLookup
import com.zillit.desktop.feature.boxschedule.domain.NoteType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.conflate
import kotlinx.datetime.LocalDate

/**
 * The production diary — the web's `BoxSchedulePage`.
 *
 * Every mutation is fire-then-refetch, as the web's `refreshAll` after each
 * write. Types are refetched with the blocks because their names and colours
 * are denormalised onto every block. The page's surfaces each have their own
 * handler — [ScheduleActions], [EntryActions], [PanelActions] — and this class
 * keeps the load, the chrome and the dispatch.
 *
 * Every write is re-checked against [BoxScheduleViewer.mayEdit] in its
 * handler, not only hidden on screen: a button can outlive the rights that
 * drew it.
 */
@Suppress("TooManyFunctions") // The load, the page's chrome, and the helpers its three action classes share.
class BoxScheduleViewModel(
    repository: BoxScheduleRepository,
    private val calendar: MainCalendarLookup,
    private val resolveViewer: () -> BoxScheduleViewer,
    private val nowMillis: () -> Long,
    internal val host: BoxScheduleHost = BoxScheduleHost(),
) : ZillitViewModel<BoxScheduleUiState, BoxScheduleEvent, BoxScheduleEffect>(BoxScheduleUiState()) {

    internal val repo: BoxScheduleRepository = repository

    private val schedules = ScheduleActions(this)
    private val entries = EntryActions(this)
    private val panels = PanelActions(this)

    private var listening = false

    fun start() {
        val zone = host.zone()
        val now = nowMillis()
        val today = DiaryCalendar.dateOf(now, zone)
        setState {
            copy(
                viewer = resolveViewer(),
                zone = zone,
                nowMillis = now,
                today = today,
                people = host.directory.people(),
                canPublish = host.publisher != null && host.canPublish(),
                canPrint = host.printer != null,
                page = if (page.month == null) page.focusedOn(today) else page,
            )
        }
        refresh()
        listenOnce()
        launch { restoreDefaults() }
    }

    /**
     * Re-runs the one big load when the socket says another client changed
     * the diary — the web's three `BoxScheduleSocketRefresh` mounts
     * (`boxScheduleV2/index.jsx:1538-1554`) collapse into this refresh.
     * Guarded so a second start (the window reopening) does not stack
     * collectors; `conflate()` folds a burst into one reload — the web
     * debounces ~300ms for the same reason.
     */
    private fun listenOnce() {
        if (listening) return
        listening = true
        launch { repo.refreshes.conflate().collect { refresh() } }
        launch { host.historyBadge.collect { count -> setState { copy(historyBadge = count) } } }
    }

    /** The views the viewer chose as defaults — `localStorage` on the web, preferences here. */
    private suspend fun restoreDefaults() {
        val prefs = host.preferences
        val view = prefs.read(DiaryPreferences.DEFAULT_VIEW)?.let { saved ->
            DiaryView.entries.firstOrNull { it.name == saved }
        }
        val mode = prefs.read(DiaryPreferences.CALENDAR_MODE)?.let { saved ->
            CalendarMode.entries.firstOrNull { it.name == saved }
        }
        val list = prefs.read(DiaryPreferences.LIST_MODE)?.let { saved ->
            ListMode.entries.firstOrNull { it.name == saved }
        }
        setState {
            copy(
                page = page.copy(
                    view = view ?: page.view,
                    defaultView = view ?: page.defaultView,
                    calendarMode = mode ?: page.calendarMode,
                    defaultCalendarMode = mode ?: page.defaultCalendarMode,
                    listMode = list ?: page.listMode,
                    defaultListMode = list ?: page.defaultListMode,
                ),
            )
        }
    }

    override fun onEvent(event: BoxScheduleEvent) {
        when (event) {
            is PageEvent -> onPage(event)
            is DayEvent -> onDay(event)
            is ScheduleEvent -> schedules.onEvent(event)
            is EntryEvent -> entries.onEvent(event)
            is PanelEvent -> panels.onEvent(event)
        }
    }

    // Load ------------------------------------------------------------------

    internal fun refresh() {
        setState { copy(loading = true) }
        launch {
            coroutineScope {
                val types = async { repo.types() }
                val blocks = async { repo.blocks() }
                val events = async { repo.events() }
                val noteTypes = async { repo.noteTypes() }
                // The web's window: a year either side of now.
                val now = nowMillis()
                val mirrored = async { calendar.events(now - YEAR_MS, now + YEAR_MS) }

                val typeList = types.await()
                val blockList = blocks.await()
                val eventList = events.await()
                val kinds = (noteTypes.await() as? ZillitResult.Success)?.data.orEmpty()
                val failure = listOf(
                    typeList,
                    blockList,
                    eventList,
                ).filterIsInstance<ZillitResult.Failure>().firstOrNull()
                val merged = (eventList as? ZillitResult.Success)?.data?.let {
                    DiaryMath.mergeWithCalendar(it, mirrored.await())
                }
                val zone = host.zone()

                setState {
                    val nextBlocks = (blockList as? ZillitResult.Success)?.data ?: this.blocks
                    copy(
                        loading = false,
                        loadedOnce = true,
                        error = failure?.error?.localised() ?: error,
                        types = (typeList as? ZillitResult.Success)?.data ?: this.types,
                        blocks = nextBlocks,
                        rows = DiaryMath.explode(nextBlocks, zone),
                        events = merged ?: this.events,
                        noteTypes = kinds.ifEmpty { this.noteTypes.ifEmpty { NoteType.DEFAULTS } },
                        people = host.directory.people(),
                        nowMillis = now,
                        today = DiaryCalendar.dateOf(now, zone),
                        canPublish = host.publisher != null && host.canPublish(),
                    )
                }
            }
        }
    }

    // Page ------------------------------------------------------------------

    @Suppress("CyclomaticComplexMethod", "LongMethod") // Event fan-out: one line per control.
    private fun onPage(event: PageEvent) {
        when (event) {
            PageEvent.Refresh -> refresh()
            PageEvent.DismissError -> setState { copy(error = null) }
            is PageEvent.SetView -> updatePage { copy(view = event.view, selecting = false, selected = emptySet()) }
            is PageEvent.SaveDefaultView -> saveDefault(
                DiaryPreferences.DEFAULT_VIEW,
                event.view.name,
                event.view.label,
            ) {
                copy(view = event.view, defaultView = event.view)
            }
            is PageEvent.SetCalendarMode -> updatePage { copy(calendarMode = event.mode) }
            is PageEvent.SaveDefaultCalendarMode ->
                saveDefault(DiaryPreferences.CALENDAR_MODE, event.mode.name, "${event.mode.label} View") {
                    copy(calendarMode = event.mode, defaultCalendarMode = event.mode)
                }
            is PageEvent.SetListMode -> updatePage { copy(listMode = event.mode) }
            is PageEvent.SaveDefaultListMode ->
                saveDefault(DiaryPreferences.LIST_MODE, event.mode.name, event.mode.label, list = true) {
                    copy(listMode = event.mode, defaultListMode = event.mode)
                }
            is PageEvent.Step -> step(event.forward)
            PageEvent.Today -> setState { copy(page = page.focusedOn(today)) }
            is PageEvent.Search -> updatePage { copy(filter = filter.copy(search = event.text)) }
            PageEvent.OpenFilters -> updateOverlays {
                copy(filters = FilterDraft(currentState.page.filter.typeName, currentState.page.filter.content))
            }
            is PageEvent.DraftContent -> updateOverlays {
                // Events and notes have no type: choosing one of them clears a type pick.
                val clearType = !event.content.showsSchedules
                val typeName = if (clearType) "" else filters?.typeName.orEmpty()
                copy(filters = filters?.copy(content = event.content, typeName = typeName))
            }
            is PageEvent.DraftType -> updateOverlays { copy(filters = filters?.copy(typeName = event.typeName)) }
            PageEvent.ClearFilters -> updateOverlays { copy(filters = filters?.let { FilterDraft() }) }
            PageEvent.ApplyFilters -> applyFilters()
            PageEvent.CloseFilters -> updateOverlays { copy(filters = null) }
            is PageEvent.ToggleRow -> updatePage {
                if (selecting) this else copy(expandedRow = if (expandedRow == event.key) null else event.key)
            }
            PageEvent.EnterSelect -> if (mayEdit()) updatePage {
                copy(selecting = true, selected = emptySet(), expandedRow = null)
            }
            PageEvent.ExitSelect -> updatePage { copy(selecting = false, selected = emptySet()) }
            is PageEvent.ToggleSelect -> updatePage {
                copy(selected = if (event.key in selected) selected - event.key else selected + event.key)
            }
            PageEvent.SelectAll -> setState { copy(page = page.copy(selected = filteredRows.map { it.key }.toSet())) }
            PageEvent.DeselectAll -> updatePage { copy(selected = emptySet()) }
            PageEvent.TogglePalette -> updateOverlays { copy(palette = if (palette == null) PalettePanel() else null) }
            is PageEvent.PaletteQuery -> updateOverlays { copy(palette = palette?.copy(query = event.text)) }
            is PageEvent.RunCommand -> runCommand(event.command)
            PageEvent.ClosePalette -> updateOverlays { copy(palette = null) }
            is PageEvent.JoinCall -> joinCall(event.listKey)
        }
    }

    private fun onDay(event: DayEvent) {
        when (event) {
            is DayEvent.OpenDay -> updateOverlays {
                copy(day = DayDrawer(event.dayKey, event.focus), quickAction = null)
            }
            DayEvent.ShowFullDay -> updateOverlays { copy(day = day?.copy(focus = DayFocus.Full)) }
            DayEvent.CloseDay -> updateOverlays { copy(day = null) }
            is DayEvent.OpenQuickAction -> {
                // An empty past date offers nothing to create, and a viewer without rights has nothing to create with.
                if (mayEdit() && !currentState.isPast(event.dayKey)) {
                    updateOverlays { copy(quickAction = event.dayKey, day = null) }
                }
            }
            DayEvent.CloseQuickAction -> updateOverlays { copy(quickAction = null) }
            is DayEvent.ViewEntry -> updateOverlays { copy(viewing = event.listKey) }
            DayEvent.CloseViewEntry -> updateOverlays { copy(viewing = null) }
        }
    }

    private fun step(forward: Boolean) {
        setState {
            val next = when (page.calendarMode) {
                CalendarMode.Month -> page.copy(month = DiaryCalendar.step(
                    CalendarMode.Month,
                    page.month ?: today,
                    forward,
                ))
                CalendarMode.Week -> {
                    val start = page.weekStart ?: DiaryCalendar.weekStart(today)
                    page.copy(weekStart = DiaryCalendar.step(CalendarMode.Week, start, forward))
                }
                CalendarMode.Day -> page.copy(day = DiaryCalendar.step(CalendarMode.Day, page.day ?: today, forward))
            }
            copy(page = next)
        }
    }

    private fun applyFilters() {
        val draft = currentState.overlays.filters ?: return
        setState {
            copy(
                page = page.copy(filter = page.filter.copy(typeName = draft.typeName, content = draft.content)),
                overlays = overlays.copy(filters = null),
            )
        }
    }

    private fun saveDefault(
        key: String,
        value: String,
        label: String,
        list: Boolean = false,
        apply: PageState.() -> PageState,
    ) {
        updatePage(apply)
        launch {
            host.preferences.write(key, value)
            val where = when {
                list -> "list view"
                key == DiaryPreferences.CALENDAR_MODE -> "calendar view"
                else -> "view"
            }
            notice("$label is now your default $where.", success = true)
        }
    }

    @Suppress("CyclomaticComplexMethod") // Command fan-out: one line per palette entry.
    private fun runCommand(command: DiaryCommand) {
        updateOverlays { copy(palette = null) }
        if (command.writes && !mayEdit()) return
        when (command) {
            DiaryCommand.NewSchedule -> onEvent(ScheduleEvent.NewSchedule())
            DiaryCommand.NewEvent -> onEvent(EntryEvent.NewEntry(DiaryKind.Event))
            DiaryCommand.NewNote -> onEvent(EntryEvent.NewEntry(DiaryKind.Note))
            DiaryCommand.EditTypes -> onEvent(PanelEvent.OpenTypes)
            DiaryCommand.CalendarView -> onEvent(PageEvent.SetView(DiaryView.Calendar))
            DiaryCommand.ListView -> onEvent(PageEvent.SetView(DiaryView.List))
            DiaryCommand.Today -> onEvent(PageEvent.Today)
            DiaryCommand.Previous -> onEvent(PageEvent.Step(forward = false))
            DiaryCommand.Next -> onEvent(PageEvent.Step(forward = true))
            DiaryCommand.History -> onEvent(PanelEvent.OpenHistory)
            DiaryCommand.ShareLink -> onEvent(PanelEvent.OpenShare)
            DiaryCommand.Print -> panels.printNow()
        }
    }

    /**
     * Joins the call on one event.
     *
     * The guards are re-checked here rather than trusted from the button: the
     * list can move under a click, and dialling a blank room places a call
     * into nothing.
     */
    private fun joinCall(listKey: String) {
        val event = currentState.entry(listKey) ?: return
        if (!event.isCallJoinable || !event.hasCallRoom) {
            notice("This event has no call to join.")
            return
        }
        sendEffect(
            BoxScheduleEffect.JoinCall(
                roomId = event.cncCallGroupId,
                title = event.title,
                video = event.prefersVideoCall,
            ),
        )
    }

    // For the action classes -------------------------------------------------

    internal fun mayEdit(): Boolean = currentState.viewer.mayEdit

    internal fun update(reducer: BoxScheduleUiState.() -> BoxScheduleUiState) = setState(reducer)

    internal fun updatePage(reducer: PageState.() -> PageState) = setState { copy(page = page.reducer()) }

    internal fun updateOverlays(reducer: DiaryOverlays.() -> DiaryOverlays) =
        setState { copy(overlays = overlays.reducer()) }

    internal fun work(block: suspend () -> Unit) {
        launch { block() }
    }

    internal fun notice(message: String, success: Boolean = false) =
        sendEffect(BoxScheduleEffect.Notice(message, success))

    internal fun copyText(text: String) = sendEffect(BoxScheduleEffect.CopyText(text))

    internal fun now(): Long = nowMillis()

    /** Snaps the calendar to a saved item's date, so the viewer sees their work. */
    internal fun focusOn(date: LocalDate) = updatePage { focusedOn(date) }

    private companion object {
        const val YEAR_MS = 365L * DiaryMath.DAY_MS
    }
}
