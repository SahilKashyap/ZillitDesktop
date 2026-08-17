package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.home.calendar.CalendarEvent
import com.zillit.desktop.feature.home.calendar.CalendarEvent2Event
import com.zillit.desktop.feature.home.calendar.CalendarRepository
import com.zillit.desktop.feature.home.calendar.CalendarViewModel
import com.zillit.desktop.feature.home.calendar.EventDraft
import com.zillit.desktop.feature.home.calendar.EventInvitation
import com.zillit.desktop.feature.home.calendar.EventTimes
import com.zillit.desktop.feature.home.calendar.InvitationPage
import com.zillit.desktop.feature.home.calendar.InvitationStatus
import com.zillit.desktop.feature.home.calendar.invitationCursor
import com.zillit.desktop.feature.home.calendar.invitationRows
import com.zillit.desktop.feature.home.calendar.readInvitation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Received invitations: reading rows off the wire, and answering them.
 *
 * The behaviour worth pinning is what an answer causes — the open tab
 * refetches AND the board reloads, because an accepted event belongs on the
 * grid immediately, not after the next navigation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InvitationsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // -- reading -----------------------------------------------------------

    @Test
    fun `a row reads with its nested event`() {
        val invitation = readInvitation(
            Json.parseToJsonElement(
                """{"_id":"i1","event_id":"e1","status":"pending","invited_by":"u9",
                   "event":{"_id":"e1","title":"Camera test","start_datetime":1700000000000,
                            "end_datetime":1700003600000,"color":"#1677ff"}}""",
            ),
            InvitationStatus.Pending,
        )!!

        assertEquals("i1", invitation.id)
        assertEquals("e1", invitation.eventId)
        assertEquals(InvitationStatus.Pending, invitation.status)
        assertEquals("Camera test", invitation.event?.title)
        assertEquals("#1677ff", invitation.event?.colorHex)
    }

    @Test
    fun `the list body reads bare or enveloped`() {
        val bare = Json.parseToJsonElement("""[{"_id":"i1"},{"_id":"i2"}]""")
        assertEquals(2, invitationRows(bare).size)

        // The paging change wrapped the same rows — found live when every
        // invitations open started toasting "something unexpected".
        val enveloped = Json.parseToJsonElement(
            """{"items":[{"_id":"i1"}],"cursor":null,"hasMore":false}""",
        )
        assertEquals(1, invitationRows(enveloped).size)

        val empty = Json.parseToJsonElement("""{"items":[],"cursor":null,"hasMore":false}""")
        assertEquals(0, invitationRows(empty).size)
    }

    @Test
    fun `a row without its event still reads, a row without ids does not`() {
        val orphan = readInvitation(
            Json.parseToJsonElement("""{"_id":"i2","event_id":"e-gone","status":"expired"}"""),
            InvitationStatus.Expired,
        )!!
        assertNull(orphan.event)

        assertNull(
            readInvitation(Json.parseToJsonElement("""{"status":"pending"}"""), InvitationStatus.Pending),
        )
    }

    // -- answering ---------------------------------------------------------

    private val invitation = EventInvitation(
        id = "i1",
        eventId = "e1",
        status = InvitationStatus.Pending,
        event = CalendarEvent(
            id = "e1",
            title = "Camera test",
            startMillis = 1_700_000_000_000,
            endMillis = 1_700_003_600_000,
        ),
    )

    private class FakeCalendar(
        var pending: List<EventInvitation> = emptyList(),
    ) : CalendarRepository {
        val answered = mutableListOf<Pair<String, Boolean>>()
        var eventFetches = 0

        override suspend fun events(fromMillis: Long, toMillis: Long): ZillitResult<List<CalendarEvent>> {
            eventFetches++
            return ZillitResult.Success(emptyList())
        }

        var pageTwo: List<EventInvitation> = emptyList()

        override suspend fun invitations(
            status: InvitationStatus,
            cursor: String?,
        ): ZillitResult<InvitationPage> {
            if (status != InvitationStatus.Pending) return ZillitResult.Success(InvitationPage(emptyList()))
            return ZillitResult.Success(
                if (cursor == null) {
                    InvitationPage(pending, nextCursor = if (pageTwo.isEmpty()) null else "c1")
                } else {
                    InvitationPage(pageTwo, nextCursor = null)
                },
            )
        }

        override suspend fun accept(eventId: String, startMillis: Long): ZillitResult<Unit> {
            answered += eventId to true
            return ZillitResult.Success(Unit)
        }

        val declineReasons = mutableListOf<String?>()

        override suspend fun decline(
            eventId: String,
            startMillis: Long,
            reason: String?,
        ): ZillitResult<Unit> {
            answered += eventId to false
            declineReasons += reason
            return ZillitResult.Success(Unit)
        }

        override suspend fun delete(eventId: String) = ZillitResult.Success(Unit)

        override suspend fun timezones() =
            ZillitResult.Success(emptyList<com.zillit.desktop.feature.home.calendar.TimezoneOption>())

        override suspend fun save(draft: EventDraft, times: EventTimes, zone: TimeZone) =
            ZillitResult.Failure(ZillitError.NoConnection())
    }

    private fun viewModel(repository: FakeCalendar) = CalendarViewModel(
        repository = repository,
        today = { LocalDate(2026, 8, 4) },
    )

    @Test
    fun `the cursor reads only when the envelope promises more`() {
        val more = Json.parseToJsonElement("""{"items":[],"cursor":"abc","hasMore":true}""")
        assertEquals("abc", invitationCursor(more))

        val done = Json.parseToJsonElement("""{"items":[],"cursor":"abc","hasMore":false}""")
        assertNull(invitationCursor(done))

        assertNull(invitationCursor(Json.parseToJsonElement("""[{"_id":"i1"}]""")))
    }

    @Test
    fun `load more appends the next page and the cursor ends`() = runTest(dispatcher) {
        val second = invitation.copy(id = "i2", eventId = "e2")
        val repository = FakeCalendar(pending = listOf(invitation)).apply { pageTwo = listOf(second) }
        val model = viewModel(repository)

        model.onEvent(CalendarEvent2Event.ShowInvitations)
        advanceUntilIdle()
        assertEquals("c1", model.state.value.invitationsCursor, "page one promises another")

        model.onEvent(CalendarEvent2Event.LoadMoreInvitations)
        advanceUntilIdle()

        assertEquals(listOf("i1", "i2"), model.state.value.invitations.map { it.id })
        assertNull(model.state.value.invitationsCursor, "the list is complete")
    }

    @Test
    fun `opening the panel fetches the pending tab`() = runTest(dispatcher) {
        val repository = FakeCalendar(pending = listOf(invitation))
        val model = viewModel(repository)

        model.onEvent(CalendarEvent2Event.ShowInvitations)
        advanceUntilIdle()

        assertEquals(listOf("i1"), model.state.value.invitations.map { it.id })
        assertEquals(1, model.state.value.pendingInvitations)
    }

    @Test
    fun `accepting refetches the tab and reloads the board`() = runTest(dispatcher) {
        val repository = FakeCalendar(pending = listOf(invitation))
        val model = viewModel(repository)
        model.onEvent(CalendarEvent2Event.ShowInvitations)
        advanceUntilIdle()

        model.onEvent(CalendarEvent2Event.AnswerInvitation(invitation, accept = true))
        advanceUntilIdle()

        assertEquals(listOf("e1" to true), repository.answered)
        assertEquals(1, repository.eventFetches, "an accepted event belongs on the grid now")
    }

    @Test
    fun `declining asks for a reason first, and sends it along`() = runTest(dispatcher) {
        val repository = FakeCalendar(pending = listOf(invitation))
        val model = viewModel(repository)
        model.onEvent(CalendarEvent2Event.ShowInvitations)
        advanceUntilIdle()

        model.onEvent(CalendarEvent2Event.AnswerInvitation(invitation, accept = false))
        advanceUntilIdle()
        assertEquals(emptyList(), repository.answered, "no decline before the dialog answers")
        assertEquals("i1", model.state.value.declining?.id)

        model.onEvent(CalendarEvent2Event.ConfirmDecline("  On set that day.  "))
        advanceUntilIdle()

        assertEquals(listOf("e1" to false), repository.answered)
        assertEquals(listOf<String?>("On set that day."), repository.declineReasons)
        assertNull(model.state.value.declining)
    }

    @Test
    fun `an empty reason travels as no reason at all`() = runTest(dispatcher) {
        val repository = FakeCalendar(pending = listOf(invitation))
        val model = viewModel(repository)
        model.onEvent(CalendarEvent2Event.ShowInvitations)
        advanceUntilIdle()

        model.onEvent(CalendarEvent2Event.AnswerInvitation(invitation, accept = false))
        model.onEvent(CalendarEvent2Event.ConfirmDecline("   "))
        advanceUntilIdle()

        assertEquals(listOf<String?>(null), repository.declineReasons)
    }

    @Test
    fun `cancelling the dialog declines nothing`() = runTest(dispatcher) {
        val repository = FakeCalendar(pending = listOf(invitation))
        val model = viewModel(repository)
        model.onEvent(CalendarEvent2Event.ShowInvitations)
        advanceUntilIdle()

        model.onEvent(CalendarEvent2Event.AnswerInvitation(invitation, accept = false))
        model.onEvent(CalendarEvent2Event.CancelDecline)
        advanceUntilIdle()

        assertEquals(emptyList(), repository.answered)
        assertNull(model.state.value.declining)
    }
}
