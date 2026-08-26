package com.zillit.desktop.feature.home

import androidx.compose.ui.geometry.Offset
import com.zillit.desktop.feature.home.calendar.CalendarEvent
import com.zillit.desktop.feature.home.calendar.CalendarEvent2Event
import com.zillit.desktop.feature.home.calendar.CalendarRepository
import com.zillit.desktop.feature.home.calendar.CalendarViewModel
import com.zillit.desktop.feature.home.calendar.DragGrid
import com.zillit.desktop.feature.home.calendar.EventDraft
import com.zillit.desktop.feature.home.calendar.EventInvitation
import com.zillit.desktop.feature.home.calendar.EventTimes
import com.zillit.desktop.feature.home.calendar.InvitationStatus
import com.zillit.desktop.feature.home.calendar.TimezoneOption
import com.zillit.desktop.feature.home.calendar.toDeltas
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Dragging an event to reschedule it.
 *
 * Two halves: the pixel→delta arithmetic (pure), and what a delta does — a
 * full-bodied edit that keeps everything but the times, refused for events
 * the dragger does not own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventMoveTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // -- pixel arithmetic --------------------------------------------------

    private val monthGrid = DragGrid(dayWidthPx = 100f, hourHeightPx = 0f, daysPerRow = 7, rowHeightPx = 80f)
    private val timeGrid = DragGrid(dayWidthPx = 200f, hourHeightPx = 60f, daysPerRow = 0)

    @Test
    fun `month drags step by cells, a week per row, never minutes`() {
        assertEquals(1 to 0, Offset(100f, 0f).toDeltas(monthGrid))
        assertEquals(-1 to 0, Offset(-95f, 0f).toDeltas(monthGrid))
        assertEquals(7 to 0, Offset(0f, 80f).toDeltas(monthGrid))
        assertEquals(8 to 0, Offset(100f, 80f).toDeltas(monthGrid))
        // A wobble smaller than half a cell is not a move.
        assertEquals(0 to 0, Offset(30f, 20f).toDeltas(monthGrid))
    }

    @Test
    fun `time-grid drags snap minutes to the quarter hour`() {
        assertEquals(0 to 60, Offset(0f, 60f).toDeltas(timeGrid))
        assertEquals(0 to 15, Offset(0f, 16f).toDeltas(timeGrid))
        assertEquals(0 to -30, Offset(0f, -30f).toDeltas(timeGrid))
        assertEquals(1 to 0, Offset(200f, 3f).toDeltas(timeGrid))
    }

    // -- the move itself ---------------------------------------------------

    private val event = CalendarEvent(
        id = "e1",
        title = "Camera test",
        // 2026-08-10 09:00 IST.
        startMillis = 1_786_332_600_000,
        endMillis = 1_786_332_600_000 + 3_600_000,
        creatorId = "me",
    )

    private class FakeCalendar : CalendarRepository {
        val saved = mutableListOf<Pair<EventDraft, EventTimes>>()

        override suspend fun events(fromMillis: Long, toMillis: Long) =
            success(emptyList<CalendarEvent>())

        override suspend fun invitations(status: InvitationStatus, cursor: String?) =
            success(com.zillit.desktop.feature.home.calendar.InvitationPage(emptyList()))

        override suspend fun accept(eventId: String, startMillis: Long) = success(Unit)

        override suspend fun decline(eventId: String, startMillis: Long, reason: String?) =
            success(Unit)

        override suspend fun delete(eventId: String) = success(Unit)

        override suspend fun save(
            draft: EventDraft,
            times: EventTimes,
            zone: TimeZone,
        ): com.zillit.desktop.core.common.ZillitResult<Unit> {
            saved += draft to times
            return success(Unit)
        }

        override suspend fun timezones() = success(emptyList<TimezoneOption>())
    }

    private fun viewModel(repository: FakeCalendar, userId: String?) = CalendarViewModel(
        repository = repository,
        today = { LocalDate(2026, 8, 4) },
        currentUserId = { userId },
    )

    @Test
    fun `a drag only proposes - nothing saves until the organiser confirms`() =
        runTest(dispatcher) {
            val repository = FakeCalendar()
            val model = viewModel(repository, userId = "me")

            model.onEvent(CalendarEvent2Event.MoveEvent(event, dayDelta = 1, minuteDelta = 30))
            advanceUntilIdle()

            // The drop is a question now, not a save.
            assertTrue(repository.saved.isEmpty())
            val pending = model.state.value.pendingReschedule
            assertEquals(event.startMillis + 86_400_000 + 1_800_000, pending?.newStart)
            assertEquals((pending?.newStart ?: 0) + 3_600_000, pending?.newEnd)
        }

    @Test
    fun `confirming the drop saves the shifted times and keeps the event's body`() =
        runTest(dispatcher) {
            val repository = FakeCalendar()
            val model = viewModel(repository, userId = "me")

            model.onEvent(CalendarEvent2Event.MoveEvent(event, dayDelta = 1, minuteDelta = 30))
            model.onEvent(CalendarEvent2Event.ConfirmReschedule)
            advanceUntilIdle()

            val (draft, times) = repository.saved.single()
            assertEquals("Camera test", draft.title)
            assertEquals("e1", draft.id)
            // One day plus thirty minutes later, duration intact.
            assertEquals(event.startMillis + 86_400_000 + 1_800_000, times.startMillis)
            assertEquals(times.startMillis + 3_600_000, times.endMillis)
            // The question is answered and gone.
            assertEquals(null, model.state.value.pendingReschedule)
        }

    @Test
    fun `cancelling the drop saves nothing and the event stays put`() =
        runTest(dispatcher) {
            val repository = FakeCalendar()
            val model = viewModel(repository, userId = "me")

            model.onEvent(CalendarEvent2Event.MoveEvent(event, dayDelta = 2, minuteDelta = 0))
            model.onEvent(CalendarEvent2Event.CancelReschedule)
            advanceUntilIdle()

            assertTrue(repository.saved.isEmpty())
            assertEquals(null, model.state.value.pendingReschedule)
        }

    @Test
    fun `someone else's event refuses to move`() = runTest(dispatcher) {
        val repository = FakeCalendar()
        val model = viewModel(repository, userId = "not-the-creator")

        model.onEvent(CalendarEvent2Event.MoveEvent(event, dayDelta = 1, minuteDelta = 0))
        advanceUntilIdle()

        assertTrue(repository.saved.isEmpty())
        assertNotNull(model.state.value.error)
    }

    @Test
    fun `a recurring event refuses to move by drag`() = runTest(dispatcher) {
        val repository = FakeCalendar()
        val model = viewModel(repository, userId = "me")

        model.onEvent(
            CalendarEvent2Event.MoveEvent(event.copy(isRecurring = true), 1, 0),
        )
        advanceUntilIdle()

        assertTrue(repository.saved.isEmpty())
        assertNotNull(model.state.value.error)
    }

    @Test
    fun `a zero drag does nothing at all`() = runTest(dispatcher) {
        val repository = FakeCalendar()
        val model = viewModel(repository, userId = "me")

        model.onEvent(CalendarEvent2Event.MoveEvent(event, 0, 0))
        advanceUntilIdle()

        assertTrue(repository.saved.isEmpty())
    }

    // -- resizing ----------------------------------------------------------

    @Test
    fun `a resize stretches the end and leaves the start alone`() = runTest(dispatcher) {
        val repository = FakeCalendar()
        val model = viewModel(repository, userId = "me")

        model.onEvent(CalendarEvent2Event.ResizeEvent(event, minuteDelta = 30))
        model.onEvent(CalendarEvent2Event.ConfirmReschedule)
        advanceUntilIdle()

        val (draft, times) = repository.saved.single()
        assertEquals("e1", draft.id)
        assertEquals(event.startMillis, times.startMillis)
        assertEquals(event.endMillis + 1_800_000, times.endMillis)
    }

    @Test
    fun `an event can never shrink below a quarter hour`() = runTest(dispatcher) {
        val repository = FakeCalendar()
        val model = viewModel(repository, userId = "me")

        // The event is an hour long; dragging up two hours pins at 15 min.
        model.onEvent(CalendarEvent2Event.ResizeEvent(event, minuteDelta = -120))
        model.onEvent(CalendarEvent2Event.ConfirmReschedule)
        advanceUntilIdle()

        val (_, times) = repository.saved.single()
        assertEquals(event.startMillis, times.startMillis)
        assertEquals(event.startMillis + 900_000, times.endMillis)
    }

    @Test
    fun `a shrink that lands where it already was saves nothing`() = runTest(dispatcher) {
        val repository = FakeCalendar()
        val model = viewModel(repository, userId = "me")
        val quarterHour = event.copy(endMillis = event.startMillis + 900_000)

        model.onEvent(CalendarEvent2Event.ResizeEvent(quarterHour, minuteDelta = -30))
        advanceUntilIdle()

        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun `someone else's event refuses to resize`() = runTest(dispatcher) {
        val repository = FakeCalendar()
        val model = viewModel(repository, userId = "not-the-creator")

        model.onEvent(CalendarEvent2Event.ResizeEvent(event, minuteDelta = 30))
        advanceUntilIdle()

        assertTrue(repository.saved.isEmpty())
        assertNotNull(model.state.value.error)
    }
}

private fun <T> success(value: T) = com.zillit.desktop.core.common.ZillitResult.Success(value)
