package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.home.calendar.CalendarEvent
import com.zillit.desktop.feature.home.calendar.CalendarEvent2Event
import com.zillit.desktop.feature.home.calendar.CalendarRepository
import com.zillit.desktop.feature.home.calendar.CalendarViewModel
import com.zillit.desktop.feature.home.calendar.EventAudience
import com.zillit.desktop.feature.home.calendar.EventDraft
import com.zillit.desktop.feature.home.calendar.EventInviteeRow
import com.zillit.desktop.feature.home.calendar.InviteeListState
import com.zillit.desktop.feature.home.calendar.EventTimes
import com.zillit.desktop.feature.home.calendar.InviteStatus
import com.zillit.desktop.feature.home.calendar.EventInvitee
import com.zillit.desktop.feature.home.calendar.InvitationStatus
import com.zillit.desktop.feature.home.calendar.durationLabel
import com.zillit.desktop.feature.home.calendar.fullDateLabel
import com.zillit.desktop.feature.home.calendar.hasFinished
import com.zillit.desktop.feature.home.calendar.inviteStatusOf
import com.zillit.desktop.feature.home.calendar.isCancelled
import com.zillit.desktop.feature.home.calendar.message
import com.zillit.desktop.feature.home.calendar.nameOf
import com.zillit.desktop.feature.home.calendar.permissionsFor
import com.zillit.desktop.feature.home.calendar.rangeLabel
import com.zillit.desktop.feature.home.calendar.reminderLabel
import com.zillit.desktop.feature.home.calendar.visibleRows
import com.zillit.desktop.feature.home.calendar.weekOfYear
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
        creatorName: String? = null,
    ) = CalendarEvent(
        id = id,
        title = "Unit call",
        startMillis = start,
        endMillis = end,
        status = status,
        creatorId = creator,
        creatorName = creatorName,
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

        /** What `invitationlist` answers, and what it was asked for. */
        var invitees: List<EventInviteeRow> = emptyList()
        val inviteeAsks = mutableListOf<Pair<String, String?>>()

        override suspend fun eventInvitees(
            eventId: String,
            occurrenceDate: String?,
        ): ZillitResult<List<EventInviteeRow>> {
            inviteeAsks += eventId to occurrenceDate
            if (fails) return ZillitResult.Failure(ZillitError.Http(500, "nope"))
            return ZillitResult.Success(invitees)
        }

        override suspend fun timezones() =
            ZillitResult.Success(emptyList<com.zillit.desktop.feature.home.calendar.TimezoneOption>())
    }

    private fun viewModel(
        repo: FakeCalendar,
        userId: String? = "u1",
        directory: List<EventInvitee> = emptyList(),
    ) =
        CalendarViewModel(
            repo,
            today = { LocalDate(2026, 1, 1) },
            currentUserId = { userId },
            invitees = { directory },
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
    fun `an organiser the row does not name is looked up in the crew`() {
        // `calendar/events` rows carry `created_by` and no `created_by_name`,
        // which read the organiser line as "Unknown" on events the user had
        // written themselves.
        val repo = FakeCalendar()
        val calendar = viewModel(repo, directory = listOf(EventInvitee("u1", "Randeep Singh")))

        calendar.onEvent(CalendarEvent2Event.OpenDetail(event()))

        assertEquals("Randeep Singh", calendar.state.value.detail?.organiserName)
    }

    @Test
    fun `a named organiser is taken from the row, not the crew`() {
        val repo = FakeCalendar()
        val calendar = viewModel(repo, directory = listOf(EventInvitee("u1", "Stale Name")))

        calendar.onEvent(CalendarEvent2Event.OpenDetail(event(creatorName = "Randeep Singh")))

        assertEquals("Randeep Singh", calendar.state.value.detail?.organiserName)
    }

    @Test
    fun `an organiser no longer on the production stays nameless`() {
        // Empty rather than their id: the popover says "Unknown", which is
        // true, instead of printing a mongo id at someone.
        val repo = FakeCalendar()
        val calendar = viewModel(repo, directory = listOf(EventInvitee("u2", "Someone Else")))

        calendar.onEvent(CalendarEvent2Event.OpenDetail(event()))

        assertEquals("", calendar.state.value.detail?.organiserName)
    }

    @Test
    fun `a new event opened from a past day starts today`() {
        // The past-date rule would refuse it on save, so the form never opens
        // with a date it can only reject.
        val repo = FakeCalendar()
        val calendar = viewModel(repo)

        calendar.onEvent(CalendarEvent2Event.Select(LocalDate(2025, 12, 20)))
        calendar.onEvent(CalendarEvent2Event.OpenForm(null))

        assertEquals("2026-01-01", calendar.state.value.form?.draft?.dateText)
    }

    @Test
    fun `a new event opened from a future day keeps that day`() {
        val repo = FakeCalendar()
        val calendar = viewModel(repo)

        calendar.onEvent(CalendarEvent2Event.Select(LocalDate(2026, 3, 9)))
        calendar.onEvent(CalendarEvent2Event.OpenForm(null))

        assertEquals("2026-03-09", calendar.state.value.form?.draft?.dateText)
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


    // -- the web's date wording ---------------------------------------------

    @Test
    fun `a one-day event reads as a date and two clock times`() {
        // The web's `formatRange`, which the phones print too — matching it
        // matters because the same event is read on three screens.
        val event = event(start = at("2026-10-14T11:30:00Z"), end = at("2026-10-14T20:00:00Z"))

        assertEquals("Wed, Oct 14 11:30 AM - 8:00 PM", event.rangeLabel(TimeZone.UTC))
        assertEquals("Wed, Oct 14, 2026", event.fullDateLabel(TimeZone.UTC))
    }

    @Test
    fun `a night shoot prints the date at both ends`() {
        // "10:00 PM - 4:00 AM" on one line is the oldest way to miss a call
        // by a day.
        val event = event(start = at("2026-10-14T22:00:00Z"), end = at("2026-10-15T04:00:00Z"))

        assertEquals("Oct 14, 10:00 PM - Oct 15, 4:00 AM", event.rangeLabel(TimeZone.UTC))
    }

    @Test
    fun `an all-day event says so instead of a clock time`() {
        val event = event(start = at("2026-10-14T00:00:00Z"), end = at("2026-10-14T23:59:00Z"))
            .copy(isAllDay = true)

        assertEquals("Wed, Oct 14 All Day", event.rangeLabel(TimeZone.UTC))
    }

    @Test
    fun `midnight and noon keep the twelve-hour clock honest`() {
        assertEquals(
            "Thu, Jan 1 12:00 AM - 12:00 PM",
            event(start = at("2026-01-01T00:00:00Z"), end = at("2026-01-01T12:00:00Z"))
                .rangeLabel(TimeZone.UTC),
        )
    }

    @Test
    fun `weeks are counted as dayjs counts them`() {
        // Sunday-start weeks, week 1 holding 1 January — the web prints this
        // number beside the date, and one off by one is worse than none.
        assertEquals(42, weekOfYear(LocalDate(2026, 10, 14)))
        assertEquals(1, weekOfYear(LocalDate(2026, 1, 1)))
        // The Sunday that opens week 2 of 2026 (1 January is a Thursday).
        assertEquals(2, weekOfYear(LocalDate(2026, 1, 4)))
        assertEquals(1, weekOfYear(LocalDate(2026, 1, 3)))
    }

    // -- the invitee list ---------------------------------------------------

    @Test
    fun `opening the invitee list asks by the event id for a one-off`() = runTest {
        val repo = FakeCalendar()
        val calendar = viewModel(repo)
        calendar.onEvent(CalendarEvent2Event.OpenDetail(event()))

        calendar.onEvent(CalendarEvent2Event.ShowInvitees)
        advanceUntilIdle()

        assertEquals(listOf<Pair<String, String?>>("e1" to null), repo.inviteeAsks)
        assertFalse(calendar.state.value.inviteeList?.isLoading == true)
    }

    @Test
    fun `a recurring event is asked for by series and occurrence date`() = runTest {
        // An invitation to a repeat is answered per occurrence, so the id
        // alone would return the series' original answers.
        val repo = FakeCalendar()
        val calendar = viewModel(repo)
        val recurring = event(start = at("2026-10-14T11:30:00Z"))
            .copy(isRecurring = true, masterEventId = "series-1")
        calendar.onEvent(CalendarEvent2Event.OpenDetail(recurring))

        calendar.onEvent(CalendarEvent2Event.ShowInvitees)
        advanceUntilIdle()

        val (eventId, occurrence) = repo.inviteeAsks.single()
        assertEquals("series-1", eventId)
        assertEquals("2026-10-14", occurrence)
    }

    @Test
    fun `a refused fetch leaves the list open and says why`() = runTest {
        val repo = FakeCalendar().apply { fails = true }
        val calendar = viewModel(repo)
        calendar.onEvent(CalendarEvent2Event.OpenDetail(event()))

        calendar.onEvent(CalendarEvent2Event.ShowInvitees)
        advanceUntilIdle()

        val list = calendar.state.value.inviteeList
        assertEquals("nope", list?.error)
        assertFalse(list?.isLoading == true)
    }

    @Test
    fun `closing the popover throws the invitee list away with it`() = runTest {
        val repo = FakeCalendar()
        val calendar = viewModel(repo)
        calendar.onEvent(CalendarEvent2Event.OpenDetail(event()))
        calendar.onEvent(CalendarEvent2Event.ShowInvitees)
        advanceUntilIdle()

        calendar.onEvent(CalendarEvent2Event.CloseDetail)

        assertNull(calendar.state.value.inviteeList)
    }

    @Test
    fun `outside guests only the event knows are not counted twice`() = runTest {
        // The invitation rows usually carry externals themselves; adding the
        // event's list on top counted every guest twice.
        val repo = FakeCalendar().apply {
            invitees = listOf(inviteeRow("ext", email = "guest@example.com", external = true))
        }
        val calendar = viewModel(repo)
        calendar.onEvent(
            CalendarEvent2Event.OpenDetail(
                event().copy(externalEmails = listOf("guest@example.com")),
            ),
        )

        calendar.onEvent(CalendarEvent2Event.ShowInvitees)
        advanceUntilIdle()

        assertEquals(1, calendar.state.value.inviteeList?.rows?.size)
    }

    @Test
    fun `an event whose rows carry no guest falls back to its own list`() = runTest {
        val repo = FakeCalendar().apply { invitees = listOf(inviteeRow("u9")) }
        val calendar = viewModel(repo)
        calendar.onEvent(
            CalendarEvent2Event.OpenDetail(
                event().copy(externalEmails = listOf("guest@example.com")),
            ),
        )

        calendar.onEvent(CalendarEvent2Event.ShowInvitees)
        advanceUntilIdle()

        val rows = calendar.state.value.inviteeList?.rows.orEmpty()
        assertEquals(2, rows.size)
        assertEquals("guest@example.com", rows.last().email)
    }

    @Test
    fun `the list answers yes first, then waiting, then no`() {
        // The question this list is opened to settle is "who is coming".
        val list = InviteeListState(
            eventTitle = "Unit call",
            rows = listOf(
                inviteeRow("a", status = InvitationStatus.Rejected),
                inviteeRow("b", status = InvitationStatus.Pending),
                inviteeRow("c", status = InvitationStatus.Accepted),
            ),
            isLoading = false,
        )

        assertEquals(listOf("c", "b", "a"), list.visibleRows().map { it.id })
    }

    @Test
    fun `searching covers the name, the address, the job and the department`() {
        val list = InviteeListState(
            eventTitle = "Unit call",
            rows = listOf(
                inviteeRow("a", userId = "u1"),
                inviteeRow("b", userId = "u2"),
                inviteeRow("c", email = "guest@example.com", external = true),
            ),
            directory = listOf(
                EventInvitee("u1", "Peach Android", designation = "Gaffer", department = "Electrical"),
                EventInvitee("u2", "Sam Grip", designation = "Key Grip", department = "Grip"),
            ),
            isLoading = false,
        )

        assertEquals(listOf("a"), list.copy(search = "peach").visibleRows().map { it.id })
        assertEquals(listOf("a"), list.copy(search = "gaffer").visibleRows().map { it.id })
        assertEquals(listOf("a"), list.copy(search = "electrical").visibleRows().map { it.id })
        assertEquals(listOf("c"), list.copy(search = "guest@").visibleRows().map { it.id })
        assertTrue(list.copy(search = "nobody").visibleRows().isEmpty())
    }

    @Test
    fun `somebody off the crew list still has a name`() {
        // Better their id than a blank row: it is the honest answer for
        // somebody since removed from the production.
        val list = InviteeListState(eventTitle = "Unit call", rows = emptyList())

        assertEquals("u404", list.nameOf(inviteeRow("row", userId = "u404")))
        assertEquals("guest@example.com", list.nameOf(inviteeRow("row", email = "guest@example.com")))
    }

    private fun inviteeRow(
        id: String,
        userId: String? = null,
        email: String? = null,
        status: InvitationStatus = InvitationStatus.Pending,
        external: Boolean = false,
    ) = EventInviteeRow(
        id = id,
        userId = userId,
        email = email,
        status = status,
        isExternal = external,
    )

    private fun at(text: String): Long = Instant.parse(text).toEpochMilliseconds()

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE

        /** Comfortably after 2026-01-01, the date the tests pin "today" to. */
        const val FUTURE = 1_800_000_000_000L
    }
}
