package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.home.calendar.CALENDAR_SYNC_EVENTS
import com.zillit.desktop.feature.home.calendar.CalendarEvent
import com.zillit.desktop.feature.home.calendar.CalendarEvent2Event
import com.zillit.desktop.feature.home.calendar.CalendarRealtimeKind
import com.zillit.desktop.feature.home.calendar.CalendarRepository
import com.zillit.desktop.feature.home.calendar.CalendarViewModel
import com.zillit.desktop.feature.home.calendar.EventDraft
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
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The calendar, kept live.
 *
 * The regression this pins: the five calendar events were on the wire and the
 * desktop even subscribed to them — but only the box-schedule diary consumed
 * them, so an event created on a phone never reached the open Calendar
 * (found 2026-09-07 auditing realtime against Android, iOS and the web).
 *
 * The self-action window is the other half. Every local mutation already
 * reloads on success, and the server echoes it straight back; without
 * suppression the same three-month window is fetched twice a second apart.
 * Both phones and the web use two seconds, so this does too.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarRealtimeTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeCalendar : CalendarRepository {
        var fetches = 0
        var invitationFetches = 0

        override suspend fun events(fromMillis: Long, toMillis: Long): ZillitResult<List<CalendarEvent>> {
            fetches++
            return ZillitResult.Success(emptyList())
        }

        override suspend fun invitations(
            status: InvitationStatus,
            cursor: String?,
        ): ZillitResult<InvitationPage> {
            invitationFetches++
            return ZillitResult.Success(InvitationPage(rows = emptyList()))
        }

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
        override suspend fun timezones() = ZillitResult.Success(emptyList<TimezoneOption>())
    }

    /**
     * The clock the view model reads, moved by hand so the window is testable.
     * Pinned to the same day as `today`: the form refuses a date in the past,
     * and a clock a year ahead would fail every save before it saved anything.
     */
    private var nowMillis = Instant.parse("2026-01-01T08:00:00Z").toEpochMilliseconds()

    private fun viewModel(repo: FakeCalendar) = CalendarViewModel(
        repo,
        today = { LocalDate(2026, 1, 1) },
        currentUserId = { "u1" },
        now = { Instant.fromEpochMilliseconds(nowMillis) },
    )

    // -- the wire ----------------------------------------------------------

    @Test
    fun `the five calendar events are the ones the other clients carry`() {
        // Un-namespaced, unlike almost everything else on this wire — that is
        // the server's spelling. A typo here is silence, not an error.
        assertEquals(
            listOf("create:event", "edit:event", "delete:event", "accept:event", "reject:event"),
            CALENDAR_SYNC_EVENTS.map(SocketEventName::value),
        )
    }

    // -- reloading ---------------------------------------------------------

    @Test
    fun `somebody else's change reloads the board`() = runTest(dispatcher) {
        val repo = FakeCalendar()
        val model = viewModel(repo)
        model.load()
        advanceUntilIdle()
        val before = repo.fetches

        model.onEvent(CalendarEvent2Event.Realtime(CalendarRealtimeKind.Events))
        advanceUntilIdle()

        assertEquals(before + 1, repo.fetches)
    }

    @Test
    fun `an answered invitation refreshes the panel when it is open`() = runTest(dispatcher) {
        val repo = FakeCalendar()
        val model = viewModel(repo)
        model.onEvent(CalendarEvent2Event.ShowInvitations)
        advanceUntilIdle()
        val before = repo.invitationFetches

        model.onEvent(CalendarEvent2Event.Realtime(CalendarRealtimeKind.Invitations))
        advanceUntilIdle()

        assertTrue(repo.invitationFetches > before, "the open panel should refetch")
    }

    @Test
    fun `a closed invitations panel is not fetched behind the user's back`() = runTest(dispatcher) {
        val repo = FakeCalendar()
        val model = viewModel(repo)
        model.load()
        advanceUntilIdle()
        // `load` reads the pending badge; the paged list is a separate fetch.
        val badgeReads = repo.invitationFetches

        model.onEvent(CalendarEvent2Event.Realtime(CalendarRealtimeKind.Invitations))
        advanceUntilIdle()

        // One more for the badge inside `load`, and no paged list fetch.
        assertEquals(badgeReads + 1, repo.invitationFetches)
    }

    // -- the echo of our own change ----------------------------------------

    @Test
    fun `the echo of a change made here is ignored`() = runTest(dispatcher) {
        val repo = FakeCalendar()
        val model = viewModel(repo)
        // Saving marks the self-action window and reloads on its own.
        model.onEvent(CalendarEvent2Event.OpenForm(null))
        model.onEvent(
            CalendarEvent2Event.FormChanged(
                model.state.value.form!!.draft.copy(
                    title = "Unit call",
                    dateText = "2026-01-02",
                    startText = "09:00",
                    endText = "10:00",
                    audience = com.zillit.desktop.feature.home.calendar.EventAudience.Personal,
                ),
            ),
        )
        model.onEvent(CalendarEvent2Event.SaveForm)
        advanceUntilIdle()
        val afterSave = repo.fetches

        model.onEvent(CalendarEvent2Event.Realtime(CalendarRealtimeKind.Events))
        advanceUntilIdle()

        assertEquals(afterSave, repo.fetches, "the server's echo must not refetch what we just fetched")
    }

    @Test
    fun `a change two seconds later is somebody else's and does reload`() = runTest(dispatcher) {
        val repo = FakeCalendar()
        val model = viewModel(repo)
        model.onEvent(CalendarEvent2Event.OpenForm(null))
        model.onEvent(
            CalendarEvent2Event.FormChanged(
                model.state.value.form!!.draft.copy(
                    title = "Unit call",
                    dateText = "2026-01-02",
                    startText = "09:00",
                    endText = "10:00",
                    audience = com.zillit.desktop.feature.home.calendar.EventAudience.Personal,
                ),
            ),
        )
        model.onEvent(CalendarEvent2Event.SaveForm)
        advanceUntilIdle()
        val afterSave = repo.fetches

        // Past the window: this is a real change from another device.
        nowMillis += 2_000L
        model.onEvent(CalendarEvent2Event.Realtime(CalendarRealtimeKind.Events))
        advanceUntilIdle()

        assertEquals(afterSave + 1, repo.fetches)
    }
}
