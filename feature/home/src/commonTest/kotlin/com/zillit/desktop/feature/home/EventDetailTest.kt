package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.home.calendar.CalendarEvent
import com.zillit.desktop.feature.home.calendar.CalendarEvent2Event
import com.zillit.desktop.feature.home.calendar.CalendarRepository
import com.zillit.desktop.feature.home.calendar.CalendarViewModel
import com.zillit.desktop.feature.home.calendar.EventAudience
import com.zillit.desktop.feature.home.calendar.EventDraft
import com.zillit.desktop.feature.home.calendar.EventTimes
import com.zillit.desktop.feature.home.calendar.InviteStatus
import com.zillit.desktop.feature.home.calendar.durationLabel
import com.zillit.desktop.feature.home.calendar.hasFinished
import com.zillit.desktop.feature.home.calendar.inviteStatusOf
import com.zillit.desktop.feature.home.calendar.isCancelled
import com.zillit.desktop.feature.home.calendar.message
import com.zillit.desktop.feature.home.calendar.permissionsFor
import com.zillit.desktop.feature.home.calendar.reminderLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The event detail popover.
 *
 * Mostly about *who may do what*: offering Delete to someone who cannot delete
 * produces a server rejection they can do nothing about, and offering Accept on
 * an event that already happened confuses whoever reads the attendee list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventDetailTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /**
     * Defaults sit *after* the view model's notion of today, which it derives
     * from the injected date — a toy timestamp reads as long finished and every
     * action is correctly withheld.
     */
    private fun event(
        id: String = "e1",
        start: Long = FUTURE,
        end: Long = FUTURE + HOUR,
        status: String? = null,
        creator: String? = "u1",
    ) = CalendarEvent(
        id = id,
        title = "Unit call",
        startMillis = start,
        endMillis = end,
        status = status,
        creatorId = creator,
    )

    private class FakeCalendar : CalendarRepository {
        var events: List<CalendarEvent> = emptyList()
        var fails = false
        val accepted = mutableListOf<Pair<String, Long>>()
        val declined = mutableListOf<Pair<String, Long>>()
        val deleted = mutableListOf<String>()

        override suspend fun events(fromMillis: Long, toMillis: Long) = ZillitResult.Success(events)


        override suspend fun invitations(
            status: com.zillit.desktop.feature.home.calendar.InvitationStatus,
            cursor: String?,
        ) = ZillitResult.Success(com.zillit.desktop.feature.home.calendar.InvitationPage(emptyList()))
        override suspend fun accept(eventId: String, startMillis: Long): ZillitResult<Unit> {
            if (fails) return ZillitResult.Failure(ZillitError.Http(500, "nope"))
            accepted += eventId to startMillis
            return ZillitResult.Success(Unit)
        }

        override suspend fun decline(
            eventId: String,
            startMillis: Long,
            reason: String?,
        ): ZillitResult<Unit> {
            declined += eventId to startMillis
            return ZillitResult.Success(Unit)
        }

        val saved = mutableListOf<Pair<EventDraft, EventTimes>>()

        override suspend fun save(
            draft: EventDraft,
            times: EventTimes,
            zone: TimeZone,
        ): ZillitResult<Unit> {
            if (fails) return ZillitResult.Failure(ZillitError.Http(500, "nope"))
            saved += draft to times
            return ZillitResult.Success(Unit)
        }

        override suspend fun delete(eventId: String): ZillitResult<Unit> {
            if (fails) return ZillitResult.Failure(ZillitError.Http(403, "not yours"))
            deleted += eventId
            return ZillitResult.Success(Unit)
        }

        override suspend fun timezones() =
            ZillitResult.Success(emptyList<com.zillit.desktop.feature.home.calendar.TimezoneOption>())
    }

    private fun viewModel(repo: FakeCalendar, userId: String? = "u1") =
        CalendarViewModel(
            repo,
            today = { LocalDate(2026, 1, 1) },
            currentUserId = { userId },
            // Pinned to the same day as `today`: the form refuses a new event
            // in the past, and a real clock would put every draft there.
            now = { Instant.parse("2026-01-01T08:00:00Z") },
        )

    // -- reading the status ------------------------------------------------

    @Test
    fun `invitation status is read from either wording`() {
        assertEquals(InviteStatus.Pending, inviteStatusOf("pending"))
        assertEquals(InviteStatus.Pending, inviteStatusOf("invited"))
        assertEquals(InviteStatus.Accepted, inviteStatusOf("ACCEPTED"))
        assertEquals(InviteStatus.Rejected, inviteStatusOf("declined"))
    }

    @Test
    fun `an unrecognised status offers no invitation controls`() {
        // Showing Accept and Decline for a state we do not understand is worse
        // than showing neither.
        assertEquals(InviteStatus.None, inviteStatusOf("something-new"))
        assertEquals(InviteStatus.None, inviteStatusOf(null))
        assertNull(InviteStatus.None.message)
    }

    @Test
    fun `each status has its own line`() {
        assertEquals("Pending invitation", InviteStatus.Pending.message)
        assertEquals("You accepted this event", InviteStatus.Accepted.message)
        assertEquals("You declined this event", InviteStatus.Rejected.message)
    }

    // -- labels ------------------------------------------------------------

    @Test
    fun `duration reads in hours and minutes`() {
        assertEquals("30 min", durationLabel(0, 30 * MINUTE))
        assertEquals("1 hr", durationLabel(0, 60 * MINUTE))
        assertEquals("1 hr 30 min", durationLabel(0, 90 * MINUTE))
        assertEquals("3 hr 15 min", durationLabel(0, 195 * MINUTE))
    }

    @Test
    fun `an event with no length has no duration`() {
        assertEquals("", durationLabel(1_000, 1_000))
        assertEquals("", durationLabel(1_000, 0))
    }

    @Test
    fun `reminders read as the web words them`() {
        assertEquals("None", reminderLabel(0))
        assertEquals("15 minutes before", reminderLabel(15))
        assertEquals("1 hour before", reminderLabel(60))
    }

    @Test
    fun `a reminder the picker cannot set is still shown`() {
        // The server is not limited to the six options, and one set elsewhere
        // must not read as "None".
        assertEquals("45 minutes before", reminderLabel(45))
        assertEquals("2 hours before", reminderLabel(120))
    }

    // -- permissions -------------------------------------------------------

    @Test
    fun `the creator may manage an event that is still ahead`() {
        val permissions = permissionsFor(event(), isCreator = true, nowMillis = 0)

        assertTrue(permissions.canManage)
        assertFalse(permissions.canRespond, "you do not respond to your own invitation")
    }

    @Test
    fun `an invitee may respond but not manage`() {
        val permissions = permissionsFor(event(status = "pending"), isCreator = false, nowMillis = 0)

        assertFalse(permissions.canManage)
        assertTrue(permissions.canRespond)
    }

    @Test
    fun `nobody may do anything to a finished event`() {
        val past = event(start = 100, end = 200)

        assertFalse(permissionsFor(past, isCreator = true, nowMillis = 1_000).canManage)
        assertFalse(
            permissionsFor(past.copy(status = "pending"), isCreator = false, nowMillis = 1_000)
                .canRespond,
        )
    }

    @Test
    fun `a cancelled event offers nothing`() {
        val cancelled = event(status = "cancelled")

        assertTrue(cancelled.isCancelled)
        assertFalse(permissionsFor(cancelled, isCreator = true, nowMillis = 0).canManage)
    }

    @Test
    fun `someone neither invited nor owning gets no actions`() {
        // The common case for most of a production's calendar.
        val permissions = permissionsFor(event(), isCreator = false, nowMillis = 0)

        assertFalse(permissions.canManage)
        assertFalse(permissions.canRespond)
    }

    @Test
    fun `an event ending exactly now has not finished`() {
        assertFalse(event(start = 0, end = 1_000).hasFinished(1_000))
        assertTrue(event(start = 0, end = 1_000).hasFinished(1_001))
    }

    // -- through the view model --------------------------------------------

    @Test
    fun `opening an event computes its permissions once`() = runTest {
        val repo = FakeCalendar()
        val calendar = viewModel(repo)

        calendar.onEvent(CalendarEvent2Event.OpenDetail(event()))

        val detail = calendar.state.value.detail
        assertEquals("e1", detail?.event?.id)
        assertTrue(detail?.permissions?.canManage == true, "u1 created it")
    }

    @Test
    fun `accepting sends the event and its start time`() = runTest {
        // A recurring event is answered per occurrence, so the id alone would
        // not say which one.
        val repo = FakeCalendar()
        val calendar = viewModel(repo, userId = "someone-else")
        calendar.onEvent(CalendarEvent2Event.OpenDetail(event(status = "pending")))

        calendar.onEvent(CalendarEvent2Event.RespondToInvite(accept = true))
        advanceUntilIdle()

        assertEquals(listOf("e1" to FUTURE), repo.accepted)
        assertNull(calendar.state.value.detail, "answering closes the popover")
    }

    @Test
    fun `declining asks for a reason, then declines and closes the popover`() = runTest {
        val repo = FakeCalendar()
        val calendar = viewModel(repo, userId = "someone-else")
        calendar.onEvent(CalendarEvent2Event.OpenDetail(event(status = "pending")))

        calendar.onEvent(CalendarEvent2Event.RespondToInvite(accept = false))
        advanceUntilIdle()
        assertTrue(repo.declined.isEmpty(), "no decline before the reason dialog answers")
        assertEquals("e1", calendar.state.value.declining?.eventId)

        calendar.onEvent(CalendarEvent2Event.ConfirmDecline("On set."))
        advanceUntilIdle()

        assertEquals(listOf("e1" to FUTURE), repo.declined)
        assertTrue(repo.accepted.isEmpty())
        assertEquals(null, calendar.state.value.detail, "the popover closes with the answer")
        assertEquals(null, calendar.state.value.declining)
    }

    @Test
    fun `a refused response keeps the popover open and says why`() = runTest {
        val repo = FakeCalendar().apply { fails = true }
        val calendar = viewModel(repo, userId = "someone-else")
        calendar.onEvent(CalendarEvent2Event.OpenDetail(event(status = "pending")))

        calendar.onEvent(CalendarEvent2Event.RespondToInvite(accept = true))
        advanceUntilIdle()

        val detail = calendar.state.value.detail
        assertEquals("nope", detail?.error)
        assertFalse(detail?.isBusy == true)
    }

    @Test
    fun `deleting asks first`() = runTest {
        val repo = FakeCalendar()
        val calendar = viewModel(repo)
        calendar.onEvent(CalendarEvent2Event.OpenDetail(event()))

        calendar.onEvent(CalendarEvent2Event.AskDeleteEvent)
        advanceUntilIdle()

        assertTrue(calendar.state.value.detail?.isConfirmingDelete == true)
        assertTrue(repo.deleted.isEmpty())
    }

    @Test
    fun `confirming deletes and closes`() = runTest {
        val repo = FakeCalendar()
        val calendar = viewModel(repo)
        calendar.onEvent(CalendarEvent2Event.OpenDetail(event()))
        calendar.onEvent(CalendarEvent2Event.AskDeleteEvent)

        calendar.onEvent(CalendarEvent2Event.ConfirmDeleteEvent)
        advanceUntilIdle()

        assertEquals(listOf("e1"), repo.deleted)
        assertNull(calendar.state.value.detail)
    }

    @Test
    fun `cancelling deletes nothing`() = runTest {
        val repo = FakeCalendar()
        val calendar = viewModel(repo)
        calendar.onEvent(CalendarEvent2Event.OpenDetail(event()))
        calendar.onEvent(CalendarEvent2Event.AskDeleteEvent)

        calendar.onEvent(CalendarEvent2Event.DismissDeleteEvent)
        advanceUntilIdle()

        assertTrue(repo.deleted.isEmpty())
        assertFalse(calendar.state.value.detail?.isConfirmingDelete == true)
    }

    @Test
    fun `closing throws the detail away`() = runTest {
        val repo = FakeCalendar()
        val calendar = viewModel(repo)
        calendar.onEvent(CalendarEvent2Event.OpenDetail(event()))

        calendar.onEvent(CalendarEvent2Event.CloseDetail)

        assertNull(calendar.state.value.detail)
    }

    @Test
    fun `without a signed-in user nobody is treated as the creator`() = runTest {
        // The safe answer: showing Delete to someone who cannot delete produces
        // a rejection they can do nothing about.
        val repo = FakeCalendar()
        val calendar = viewModel(repo, userId = null)

        calendar.onEvent(CalendarEvent2Event.OpenDetail(event()))

        assertFalse(calendar.state.value.detail?.permissions?.canManage == true)
    }

    // -- the form ----------------------------------------------------------

    @Test
    fun `creating opens the form dated to the day in focus`() = runTest {
        // Typing today's date into a form you opened from today's cell is the
        // sort of thing that makes a calendar feel like paperwork.
        val repo = FakeCalendar()
        val calendar = viewModel(repo)
        calendar.onEvent(CalendarEvent2Event.Select(LocalDate(2026, 3, 9)))

        calendar.onEvent(CalendarEvent2Event.OpenForm(null))

        val form = calendar.state.value.form
        assertEquals("2026-03-09", form?.draft?.dateText)
        assertFalse(form?.draft?.isEdit == true)
    }

    @Test
    fun `editing opens the form filled in, and closes the popover`() = runTest {
        val repo = FakeCalendar()
        val calendar = viewModel(repo)
        calendar.onEvent(CalendarEvent2Event.OpenDetail(event()))

        calendar.onEvent(CalendarEvent2Event.OpenForm(event()))

        assertEquals("e1", calendar.state.value.form?.draft?.id)
        assertNull(calendar.state.value.detail, "two dialogs at once is one too many")
    }

    @Test
    fun `an invalid draft never reaches the server`() = runTest {
        val repo = FakeCalendar()
        val calendar = viewModel(repo)
        calendar.onEvent(CalendarEvent2Event.OpenForm(null))
        calendar.onEvent(
            CalendarEvent2Event.FormChanged(
                calendar.state.value.form!!.draft.copy(title = "", startText = "", endText = ""),
            ),
        )

        calendar.onEvent(CalendarEvent2Event.SaveForm)
        advanceUntilIdle()

        assertTrue(repo.saved.isEmpty())
        assertTrue(calendar.state.value.form!!.errors.isNotEmpty())
    }

    @Test
    fun `a valid draft is saved and the form closes`() = runTest {
        val repo = FakeCalendar()
        val calendar = viewModel(repo)
        calendar.onEvent(CalendarEvent2Event.OpenForm(null))
        calendar.onEvent(
            CalendarEvent2Event.FormChanged(
                calendar.state.value.form!!.draft.copy(
                    title = "Unit call",
                    startText = "09:00",
                    endText = "17:30",
                    // The saving path is the subject; a personal event is the
                    // shortest way to a draft that passes every rule.
                    audience = EventAudience.Personal,
                ),
            ),
        )

        calendar.onEvent(CalendarEvent2Event.SaveForm)
        advanceUntilIdle()

        assertEquals("Unit call", repo.saved.single().first.title)
        assertNull(calendar.state.value.form)
    }

    @Test
    fun `errors clear as the draft is corrected`() = runTest {
        val repo = FakeCalendar()
        val calendar = viewModel(repo)
        calendar.onEvent(CalendarEvent2Event.OpenForm(null))
        calendar.onEvent(
            CalendarEvent2Event.FormChanged(calendar.state.value.form!!.draft.copy(title = "")),
        )
        calendar.onEvent(CalendarEvent2Event.SaveForm)
        advanceUntilIdle()

        calendar.onEvent(
            CalendarEvent2Event.FormChanged(
                calendar.state.value.form!!.draft.copy(title = "Named now"),
            ),
        )

        assertTrue(calendar.state.value.form!!.errors.isEmpty())
    }

    @Test
    fun `a refused save keeps the form open with everything typed`() = runTest {
        // Losing a written event to a network blip is a small thing done twice.
        val repo = FakeCalendar().apply { fails = true }
        val calendar = viewModel(repo)
        calendar.onEvent(CalendarEvent2Event.OpenForm(null))
        calendar.onEvent(
            CalendarEvent2Event.FormChanged(
                calendar.state.value.form!!.draft.copy(
                    title = "Unit call",
                    startText = "09:00",
                    endText = "17:30",
                    // The saving path is the subject; a personal event is the
                    // shortest way to a draft that passes every rule.
                    audience = EventAudience.Personal,
                ),
            ),
        )

        calendar.onEvent(CalendarEvent2Event.SaveForm)
        advanceUntilIdle()

        val form = calendar.state.value.form
        assertEquals("Unit call", form?.draft?.title)
        assertEquals("nope", form?.error)
        assertFalse(form?.isSaving == true)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE

        /** Comfortably after 2026-01-01, the date the tests pin "today" to. */
        const val FUTURE = 1_800_000_000_000L
    }
}
