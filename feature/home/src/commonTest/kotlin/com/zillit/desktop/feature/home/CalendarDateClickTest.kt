package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.calendar.CalendarEvent
import com.zillit.desktop.feature.home.calendar.CalendarEvent2Event
import com.zillit.desktop.feature.home.calendar.CalendarRepository
import com.zillit.desktop.feature.home.calendar.CalendarViewModel
import com.zillit.desktop.feature.home.calendar.EventDraft
import com.zillit.desktop.feature.home.calendar.EventInviteeRow
import com.zillit.desktop.feature.home.calendar.EventTimes
import com.zillit.desktop.feature.home.calendar.InvitationPage
import com.zillit.desktop.feature.home.calendar.InvitationStatus
import com.zillit.desktop.feature.home.calendar.TimezoneOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Clicking a date in the month grid, as the web's `select` does it: any day
 * from today on opens a new event for that day, busy or not, and a day already
 * gone selects and says why it could not do more.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarDateClickTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** Noon UTC on 2026-08-06: the 6th in any zone a test machine sits in. */
    private val busy = CalendarEvent(
        id = "e1",
        title = "Camera test",
        startMillis = 1_786_017_600_000,
        endMillis = 1_786_017_600_000 + 3_600_000,
    )

    /** Noon UTC on 2026-08-02, two days before this fixture's today. */
    private val done = CalendarEvent(
        id = "e0",
        title = "Recce",
        startMillis = 1_785_672_000_000,
        endMillis = 1_785_672_000_000 + 3_600_000,
    )

    private class FakeCalendar(private val all: List<CalendarEvent>) : CalendarRepository {
        override suspend fun events(fromMillis: Long, toMillis: Long) = success(all)
        override suspend fun invitations(status: InvitationStatus, cursor: String?) =
            success(InvitationPage(emptyList()))
        override suspend fun accept(eventId: String, startMillis: Long) = success(Unit)
        override suspend fun decline(eventId: String, startMillis: Long, reason: String?) = success(Unit)
        override suspend fun delete(eventId: String) = success(Unit)
        override suspend fun save(draft: EventDraft, times: EventTimes, zone: TimeZone) = success(Unit)
        override suspend fun eventInvitees(eventId: String, occurrenceDate: String?) =
            success(emptyList<EventInviteeRow>())
        override suspend fun timezones() = success(emptyList<TimezoneOption>())
    }

    /** Loaded, as the host loads it when the calendar opens. */
    private fun viewModel() = CalendarViewModel(
        repository = FakeCalendar(listOf(busy, done)),
        today = { LocalDate(2026, 8, 4) },
    ).also { it.load() }

    @Test
    fun `a free day from today on opens a new event on that day`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        model.onEvent(CalendarEvent2Event.ClickDate(LocalDate(2026, 8, 10)))
        advanceUntilIdle()

        val form = assertNotNull(model.state.value.form)
        assertEquals("2026-08-10", form.draft.dateText)
        assertEquals(LocalDate(2026, 8, 10), model.state.value.selected)
    }

    @Test
    fun `today counts as from today on`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        model.onEvent(CalendarEvent2Event.ClickDate(LocalDate(2026, 8, 4)))
        advanceUntilIdle()

        assertNotNull(model.state.value.form)
    }

    @Test
    fun `a day that already holds something opens a new event on it too`() = runTest(dispatcher) {
        // The panel behind the form is on that day, so its events are still
        // one dismissal away.
        val model = viewModel()
        advanceUntilIdle()

        model.onEvent(CalendarEvent2Event.ClickDate(LocalDate(2026, 8, 6)))
        advanceUntilIdle()

        val form = assertNotNull(model.state.value.form)
        assertEquals("2026-08-06", form.draft.dateText)
        assertEquals(LocalDate(2026, 8, 6), model.state.value.selected)
    }

    @Test
    fun `the more link only moves the panel onto the day`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        model.onEvent(CalendarEvent2Event.Select(LocalDate(2026, 8, 6)))
        advanceUntilIdle()

        assertNull(model.state.value.form)
        assertEquals(LocalDate(2026, 8, 6), model.state.value.selected)
    }

    @Test
    fun `a past day selects and says why nothing opened`() = runTest(dispatcher) {
        // Without the notice the click looks like a dead cell: the identical
        // cell one row down opens a form.
        val model = viewModel()
        advanceUntilIdle()

        model.onEvent(CalendarEvent2Event.ClickDate(LocalDate(2026, 8, 3)))
        advanceUntilIdle()

        assertNull(model.state.value.form)
        assertEquals(LocalDate(2026, 8, 3), model.state.value.selected)
        assertTrue(model.state.value.pastDayNotice)
    }

    @Test
    fun `the notice closes on Ok`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()
        model.onEvent(CalendarEvent2Event.ClickDate(LocalDate(2026, 8, 3)))
        advanceUntilIdle()

        model.onEvent(CalendarEvent2Event.DismissPastDayNotice)
        advanceUntilIdle()

        assertFalse(model.state.value.pastDayNotice)
        assertEquals(LocalDate(2026, 8, 3), model.state.value.selected, "the day stays chosen")
    }

    @Test
    fun `a past day with something on it is refused like any other`() = runTest(dispatcher) {
        // The day cannot take a new event whatever it already holds, and the
        // web refuses the same way.
        val model = viewModel()
        advanceUntilIdle()

        model.onEvent(CalendarEvent2Event.ClickDate(LocalDate(2026, 8, 2)))
        advanceUntilIdle()

        assertTrue(model.state.value.pastDayNotice)
        assertNull(model.state.value.form)
    }
}

private fun <T> success(value: T) = com.zillit.desktop.core.common.ZillitResult.Success(value)
