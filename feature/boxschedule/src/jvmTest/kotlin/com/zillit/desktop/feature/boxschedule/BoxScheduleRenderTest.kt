package com.zillit.desktop.feature.boxschedule

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.boxschedule.DiarySamples.day
import com.zillit.desktop.feature.boxschedule.domain.AudienceMode
import com.zillit.desktop.feature.boxschedule.domain.CalendarMode
import com.zillit.desktop.feature.boxschedule.domain.ConflictAction
import com.zillit.desktop.feature.boxschedule.domain.DateConflict
import com.zillit.desktop.feature.boxschedule.domain.DateTab
import com.zillit.desktop.feature.boxschedule.domain.DiaryAudience
import com.zillit.desktop.feature.boxschedule.domain.DiaryKind
import com.zillit.desktop.feature.boxschedule.domain.DiaryView
import com.zillit.desktop.feature.boxschedule.domain.ListMode
import com.zillit.desktop.feature.boxschedule.domain.RecurrenceScope
import com.zillit.desktop.feature.boxschedule.ui.AudiencePicker
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleEvent
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleScreen
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleUiState
import com.zillit.desktop.feature.boxschedule.ui.CalendarInfo
import com.zillit.desktop.feature.boxschedule.ui.ConflictPrompt
import com.zillit.desktop.feature.boxschedule.ui.DayDrawer
import com.zillit.desktop.feature.boxschedule.ui.DayEvent
import com.zillit.desktop.feature.boxschedule.ui.DayFocus
import com.zillit.desktop.feature.boxschedule.ui.DeleteDayPrompt
import com.zillit.desktop.feature.boxschedule.ui.DiaryOverlays
import com.zillit.desktop.feature.boxschedule.ui.EntryEvent
import com.zillit.desktop.feature.boxschedule.ui.EntryField
import com.zillit.desktop.feature.boxschedule.ui.EntryForm
import com.zillit.desktop.feature.boxschedule.ui.EntryScopePrompt
import com.zillit.desktop.feature.boxschedule.ui.FilterDraft
import com.zillit.desktop.feature.boxschedule.ui.GuestsDialog
import com.zillit.desktop.feature.boxschedule.ui.HistoryPanel
import com.zillit.desktop.feature.boxschedule.ui.PalettePanel
import com.zillit.desktop.feature.boxschedule.ui.PdfDestination
import com.zillit.desktop.feature.boxschedule.ui.PdfSheet
import com.zillit.desktop.feature.boxschedule.ui.PresetsPanel
import com.zillit.desktop.feature.boxschedule.ui.PrintPrompt
import com.zillit.desktop.feature.boxschedule.ui.ScheduleEvent
import com.zillit.desktop.feature.boxschedule.ui.ScheduleForm
import com.zillit.desktop.feature.boxschedule.ui.ScheduleScopePrompt
import com.zillit.desktop.feature.boxschedule.ui.ScopeMode
import com.zillit.desktop.feature.boxschedule.ui.SharePanel
import com.zillit.desktop.feature.boxschedule.ui.TypesManager
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole page drawn from a real-shaped September: every view, and every
 * surface the page can open over it. Most of what these catch is a surface
 * that throws on first composition — the kind no unit test reaches.
 */
@OptIn(ExperimentalTestApi::class)
class BoxScheduleRenderTest {

    private fun ComposeUiTest.shows(text: String) = assertTrue(
        onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty(),
        "\"$text\" should be on screen",
    )

    private fun ComposeUiTest.page(
        initial: BoxScheduleUiState,
        events: MutableList<BoxScheduleEvent>,
    ): (BoxScheduleUiState) -> Unit {
        var state by mutableStateOf(initial)
        setContent { ZillitTheme { BoxScheduleScreen(state, events::add) } }
        waitForIdle()
        return { next ->
            state = next
            waitForIdle()
        }
    }

    @Test
    fun `month, week, day and both lists draw the diary`() = runComposeUiTest {
        val events = mutableListOf<BoxScheduleEvent>()
        val show = page(DiarySamples.state(), events)
        shows("September 2026")
        shows("Production meeting")
        shows("Shoot Day")

        show(DiarySamples.state(calendarMode = CalendarMode.Week, focus = LocalDate(2026, 9, 15)))
        shows("Costume fitting")

        show(DiarySamples.state(calendarMode = CalendarMode.Day, focus = LocalDate(2026, 9, 15)))
        shows("Rain cover")

        show(DiarySamples.state(view = DiaryView.List))
        shows("Stage 4 — Interior")

        show(DiarySamples.state(view = DiaryView.List, listMode = ListMode.BySchedule))
        shows("Stage build") // The first card; the lazy list composes only what fits.
        onAllNodesWithText("Delete Script").onFirst().performClick()
        assertTrue(events.any { it is ScheduleEvent.AskDeleteBlock }, "a schedule card deletes its script")
    }

    @Test
    fun `a viewer without rights is offered nothing that writes`() = runComposeUiTest {
        page(
            DiarySamples.state(view = DiaryView.List, listMode = ListMode.BySchedule, canEdit = false),
            mutableListOf(),
        )
        assertEquals(0, onAllNodesWithText("Delete Script").fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText("Create Event").fetchSemanticsNodes().size)
    }

    @Test
    fun `the day drawer lists the day's schedule, events and notes`() = runComposeUiTest {
        val events = mutableListOf<BoxScheduleEvent>()
        val show = page(DiarySamples.state(overlays = DiaryOverlays(day = DayDrawer(day(15)))), events)
        shows("Tuesday, September 15, 2026")
        shows("Costume fitting")
        shows("Rain cover")
        shows("Daily rushes")

        show(DiarySamples.state(overlays = DiaryOverlays(day = DayDrawer(day(15), DayFocus.Schedule("b-shoot")))))
        onNodeWithText("Show full day").performClick()
        assertTrue(DayEvent.ShowFullDay in events)
    }

    @Test
    fun `the schedule form draws each way to set dates, and the type menu opens and picks`() = runComposeUiTest {
        val events = mutableListOf<BoxScheduleEvent>()
        val create = ScheduleForm.create(null)
        val show = page(DiarySamples.state(overlays = DiaryOverlays(scheduleForm = create)), events)
        onNodeWithText("Select schedule type").performClick()
        waitForIdle()
        // The legend and the calendar behind the drawer say Travel too; the menu's row is drawn last.
        onAllNodesWithText("Travel").onLast().performClick()
        assertTrue(ScheduleEvent.SetType("t-travel") in events)

        DateTab.entries.forEach { tab ->
            show(DiarySamples.state(overlays = DiaryOverlays(scheduleForm = create.copy(typeId = "t-prep", tab = tab))))
            shows(tab.description)
        }

        val single = ScheduleForm.singleDay(DiarySamples.blocks[1], day(15))
        show(DiarySamples.state(overlays = DiaryOverlays(scheduleForm = single.copy(typeId = "t-travel"))))
        shows("Date cannot be changed when editing a single day")
        onNodeWithText("Extend").performClick()
        assertTrue(ScheduleEvent.SetSingleAction(ConflictAction.Extend) in events)
    }

    @Test
    fun `an entry form shows its field errors, and a new event asks about the Home calendar`() = runComposeUiTest {
        val events = mutableListOf<BoxScheduleEvent>()
        val form = EntryForm(
            kind = DiaryKind.Event,
            startDate = DiarySamples.today,
            timezone = DiarySamples.zone.id,
            errors = mapOf(EntryField.Title to "Title is required", EntryField.CallType to "Call type is required"),
        )
        val show = page(DiarySamples.state(overlays = DiaryOverlays(entryForm = form)), events)
        shows("ADD EVENT")
        shows("Title is required")
        shows("Call type is required")

        val asking = form.copy(errors = emptyMap(), askCalendar = true)
        show(DiarySamples.state(overlays = DiaryOverlays(entryForm = asking)))
        shows("Set Reminder on Home Calendar?")
        onNodeWithText("Yes").performClick()
        assertTrue(EntryEvent.AnswerCalendar(mirror = true) in events)

        val note = EntryForm(
            kind = DiaryKind.Note,
            noteDate = DiarySamples.today,
            audience = DiaryAudience(AudienceMode.Self),
        )
        show(DiarySamples.state(overlays = DiaryOverlays(entryForm = note)))
        shows("ADD NOTE")
    }

    @Test
    fun `the invitee picker draws every tab and counts the choice on Done`() = runComposeUiTest {
        val events = mutableListOf<BoxScheduleEvent>()
        val form = EntryForm(kind = DiaryKind.Event, startDate = DiarySamples.today)
        val show = page(DiarySamples.state(), events)
        AudienceMode.entries.filter { it != AudienceMode.None }.forEach { tab ->
            val picker = AudiencePicker(tab = tab, userIds = listOf("u1"), presets = DiarySamples.presets)
            show(DiarySamples.state(overlays = DiaryOverlays(entryForm = form.copy(audiencePicker = picker))))
            shows("Select Invitees")
        }
        val users = AudiencePicker(tab = AudienceMode.Users, userIds = listOf("u1"))
        show(DiarySamples.state(overlays = DiaryOverlays(entryForm = form.copy(audiencePicker = users))))
        shows("Done (1)")
        onNodeWithText("Dev Patel").performClick()
        assertTrue(EntryEvent.AudienceToggleUser("u2") in events)

        val guests = form.copy(guestsDialog = GuestsDialog(listOf("a@b.co")))
        show(DiarySamples.state(overlays = DiaryOverlays(entryForm = guests)))
        shows("External Guests")
        shows("a@b.co")
    }

    @Test
    fun `a clash lists its dates and waits for a choice before Done`() = runComposeUiTest {
        val events = mutableListOf<BoxScheduleEvent>()
        val draftForm = ScheduleForm.create(LocalDate(2026, 9, 15)).copy(typeId = "t-prep", countText = "2")
        val prompt = ConflictPrompt(
            conflicts = listOf(day(15), day(16)).map { DateConflict(it, "Shoot Day", "#E74C3C", "Stage 4 — Interior") },
            draft = draftForm.draft(DiarySamples.zone),
            blockId = null,
            form = draftForm,
        )
        page(DiarySamples.state(overlays = DiaryOverlays(conflict = prompt)), events)
        shows("2 date(s) overlap with existing schedules:")
        onNodeWithText("Done").performClick()
        assertTrue(events.none { it == ScheduleEvent.ResolveConflict }, "Done is disabled without a choice")
        onNodeWithText("Replace").performClick()
        assertTrue(ScheduleEvent.PickConflict(ConflictAction.Replace) in events)
    }

    @Test
    fun `history lists who did what, and a row opens its details`() = runComposeUiTest {
        val events = mutableListOf<BoxScheduleEvent>()
        val panel = HistoryPanel(loading = false, entries = DiarySamples.history)
        val show = page(DiarySamples.state(overlays = DiaryOverlays(history = panel)), events)
        shows("HISTORY")
        shows("Rain cover")
        shows("Asha Rao")

        show(DiarySamples.state(overlays = DiaryOverlays(history = panel.copy(detailId = "l2"))))
        shows("Stage 4 — Interior")
    }

    @Test
    fun `every other surface the page opens draws`() = runComposeUiTest {
        val show = page(DiarySamples.state(), mutableListOf())
        val standup = DiarySamples.events.first { it.isRecurringInstance }.listKey
        val scope = ScheduleScopePrompt("b-shoot", day(15), ScopeMode.Delete)
        val following = EntryScopePrompt(standup, RecurrenceScope.ThisAndFollowing)
        val presets = PresetsPanel(loading = false, presets = DiarySamples.presets)
        val surfaces = listOf(
            DiaryOverlays(quickAction = day(26)) to "What would you like to create?",
            DiaryOverlays(viewing = "e1") to "EVENT DETAILS",
            DiaryOverlays(viewing = "n1") to "NOTE DETAILS",
            DiaryOverlays(scheduleScope = scope) to "Complete schedule",
            DiaryOverlays(deleteDay = DeleteDayPrompt("b-travel", day(21))) to "Delete Schedule Day",
            DiaryOverlays(deleteBlock = "b-prep") to "This action cannot be undone.",
            DiaryOverlays(deleteAllOn = day(15)) to "Delete all schedules on this day?",
            DiaryOverlays(bulkDelete = true) to "Delete selected days",
            DiaryOverlays(updateScope = EntryScopePrompt(standup)) to "All events in the series",
            DiaryOverlays(deleteEntry = following) to "This and following events",
            DiaryOverlays(deleteEntry = EntryScopePrompt("n3")) to "Delete note?",
            DiaryOverlays(calendarInfo = CalendarInfo(forDelete = true)) to "Calendar Event",
            DiaryOverlays(types = TypesManager()) to "Schedule Types",
            DiaryOverlays(presets = presets) to "Heads of department",
            DiaryOverlays(filters = FilterDraft()) to "Filters",
            DiaryOverlays(pdf = PdfSheet(PdfDestination.Print)) to "PDF options",
            DiaryOverlays(printSelected = PrintPrompt()) to "Print selected days",
            DiaryOverlays(share = SharePanel(link = "https://web.test/box-schedule/share/abc")) to "Share Schedule",
            DiaryOverlays(palette = PalettePanel()) to "Commands",
        )
        surfaces.forEach { (overlays, text) ->
            show(DiarySamples.state(overlays = overlays))
            shows(text)
        }
    }
}
