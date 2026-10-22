package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.home.calendar.CalendarEvent
import com.zillit.desktop.feature.home.calendar.CalendarEvent2Event
import com.zillit.desktop.feature.home.calendar.CalendarRepository
import com.zillit.desktop.feature.home.calendar.CalendarViewMode
import com.zillit.desktop.feature.home.calendar.CalendarViewModel
import com.zillit.desktop.feature.home.calendar.EventDraft
import com.zillit.desktop.feature.home.calendar.EventInviteeRow
import com.zillit.desktop.feature.home.calendar.EventTimes
import com.zillit.desktop.feature.home.calendar.InvitationPage
import com.zillit.desktop.feature.home.calendar.InvitationStatus
import com.zillit.desktop.feature.home.calendar.TimezoneOption
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The calendar across a production switch.
 *
 * The pane sits beside the board `/home` opens on, so its events are the first
 * thing a new production shows — and they were the production just left. The
 * clearing used to be bundled with the refetch and sent *behind* the new
 * production's first frame, which is the flash: 17 to 92ms of the previous
 * shoot's diary, by the app's own switch log, and the fetch's own wait on top.
 * So the clearing is its own call now, sent as the production is left, and the
 * read follows behind the frame with every other tool's.
 *
 * The second half is the read that was already out. `load` refuses to start
 * while one is in flight, so an unfinished read of the production being left
 * swallowed the next production's reload and then answered into its board.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarProjectSwitchTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeCalendar : CalendarRepository {
        var fetches = 0
        var rows: List<CalendarEvent> = emptyList()

        /** Set to hold the events read open, as a slow one is held by the network. */
        var held: CompletableDeferred<Unit>? = null

        override suspend fun events(fromMillis: Long, toMillis: Long): ZillitResult<List<CalendarEvent>> {
            fetches++
            held?.await()
            return ZillitResult.Success(rows)
        }

        override suspend fun invitations(
            status: InvitationStatus,
            cursor: String?,
        ): ZillitResult<InvitationPage> = ZillitResult.Success(InvitationPage(rows = emptyList()))

        override suspend fun accept(eventId: String, startMillis: Long) = ZillitResult.Success(Unit)
        override suspend fun decline(
            eventId: String,
            startMillis: Long,
            reason: String?,
        ) = ZillitResult.Success(Unit)

        override suspend fun save(
            draft: EventDraft,
            times: EventTimes,
            zone: TimeZone,
        ) = ZillitResult.Success(Unit)

        override suspend fun delete(eventId: String) = ZillitResult.Success(Unit)
        override suspend fun eventInvitees(eventId: String, occurrenceDate: String?) =
            ZillitResult.Success(emptyList<EventInviteeRow>())

        override suspend fun timezones() = ZillitResult.Success(emptyList<TimezoneOption>())
    }

    private fun viewModel(repo: FakeCalendar) = CalendarViewModel(
        repo,
        today = { LocalDate(2026, 1, 1) },
        currentUserId = { "u1" },
        now = { Instant.parse("2026-01-01T08:00:00Z") },
    )

    private fun event(id: String) = CalendarEvent(
        id = id,
        title = "Unit call",
        startMillis = Instant.parse("2026-01-02T09:00:00Z").toEpochMilliseconds(),
        endMillis = Instant.parse("2026-01-02T10:00:00Z").toEpochMilliseconds(),
    )

    @Test
    fun `leaving a production empties the pane and reads nothing`() = runTest(dispatcher) {
        val repo = FakeCalendar().apply { rows = listOf(event("e1")) }
        val model = viewModel(repo)
        model.load()
        advanceUntilIdle()
        assertEquals(1, model.state.value.events.size, "the production being left had an event")
        val read = repo.fetches

        model.onEvent(CalendarEvent2Event.ProjectChanged)
        advanceUntilIdle()

        assertTrue(model.state.value.events.isEmpty(), "the previous production's events must be gone")
        assertEquals(read, repo.fetches, "the clearing must not fetch — the shell asks behind the frame")
    }

    /** The reload the shell sends behind the first frame must actually go out. */
    @Test
    fun `a read still out does not swallow the next production's`() = runTest(dispatcher) {
        val repo = FakeCalendar().apply { held = CompletableDeferred() }
        val model = viewModel(repo)
        model.load()
        advanceUntilIdle()
        assertEquals(1, repo.fetches, "the first read is out and unanswered")

        model.onEvent(CalendarEvent2Event.ProjectChanged)
        model.onEvent(CalendarEvent2Event.Reload)
        advanceUntilIdle()

        assertEquals(2, repo.fetches, "the new production's read must start even so")
    }

    @Test
    fun `an unfinished read of the production left never lands`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeCalendar().apply {
            rows = listOf(event("left"))
            held = gate
        }
        val model = viewModel(repo)
        model.load()
        advanceUntilIdle()

        model.onEvent(CalendarEvent2Event.ProjectChanged)
        advanceUntilIdle()
        // The production being left finally answers, after it was left.
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(
            model.state.value.events.isEmpty(),
            "a read of the production left must not answer into the next one's board",
        )
    }

    /** A month and a view mode are the viewer's, not the production's. */
    @Test
    fun `what the viewer chose survives the switch`() = runTest(dispatcher) {
        val repo = FakeCalendar()
        val model = viewModel(repo)
        model.onEvent(CalendarEvent2Event.SetMode(CalendarViewMode.Week))
        model.onEvent(CalendarEvent2Event.Next)
        advanceUntilIdle()
        val anchor = model.state.value.anchor

        model.onEvent(CalendarEvent2Event.ProjectChanged)
        advanceUntilIdle()

        assertEquals(CalendarViewMode.Week, model.state.value.mode)
        assertEquals(anchor, model.state.value.anchor)
    }
}
