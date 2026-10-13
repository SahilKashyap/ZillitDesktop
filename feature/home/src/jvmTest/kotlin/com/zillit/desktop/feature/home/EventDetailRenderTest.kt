package com.zillit.desktop.feature.home

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.home.calendar.CalendarEvent
import com.zillit.desktop.feature.home.calendar.CalendarEvent2Event
import com.zillit.desktop.feature.home.calendar.CallType
import com.zillit.desktop.feature.home.calendar.EventAudience
import com.zillit.desktop.feature.home.calendar.EventDetailPopover
import com.zillit.desktop.feature.home.calendar.EventDetailState
import com.zillit.desktop.feature.home.calendar.EventInvitee
import com.zillit.desktop.feature.home.calendar.EventInviteeRow
import com.zillit.desktop.feature.home.calendar.EventPermissions
import com.zillit.desktop.feature.home.calendar.InvitationStatus
import com.zillit.desktop.feature.home.calendar.InviteeListDialog
import com.zillit.desktop.feature.home.calendar.InviteeListState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Composes the real detail popover.
 *
 * Its facts are unit-tested; this is about whether they are on the screen in
 * the arrangement the web and the phones use. Every band of it — the eyebrow,
 * the audience chip, the card, the invitee card, the footer — appears and
 * disappears with the kind of event and the viewer's standing on it, which is
 * exactly the sort of conditional that stops composing silently.
 */
@OptIn(ExperimentalTestApi::class)
class EventDetailRenderTest {

    private fun event(
        audience: EventAudience = EventAudience.Members,
        callType: CallType? = CallType.Audio,
        status: String? = "pending",
    ) = CalendarEvent(
        id = "e1",
        title = "Daily Meet",
        startMillis = START,
        endMillis = START + DAY_PART,
        status = status,
        creatorName = "Randeep Iphone Test",
        creatorId = "u-creator",
        reminderMinutes = 5,
        hasCall = callType != null,
        isRecurring = true,
        audience = audience,
        callType = callType,
        cncGroupId = "room-1",
        invitedCount = 21,
        description = "Stand-up for the whole unit.",
    )

    private fun detail(
        event: CalendarEvent = event(),
        permissions: EventPermissions = EventPermissions(canRespond = true),
        hasFinished: Boolean = false,
    ) = EventDetailState(
        event = event,
        permissions = permissions,
        hasFinished = hasFinished,
        canJoinCall = !hasFinished,
        rangeLabel = "Wed, Oct 14 11:30 AM - 8:00 PM",
        fullDateLabel = "Wed, Oct 14, 2026",
        weekLabel = "Week 42 of the Year",
        durationText = "8 hr 30 min",
        createdLabel = "Created Wed, Oct 14, 2026 9:00 AM",
        inviteeCount = event.invitedCount,
        inviteeFaces = List(FACES) { EventInvitee("u$it", "Crew $it") },
    )

    @Test
    fun `an invitee sees every band the web draws`() = runComposeUiTest {
        // onJoinCall supplied: a host with no calling draws no Join button at
        // all, which is a separate case from an event with no call.
        setContent {
            ZillitTheme { EventDetailPopover(detail(), onEvent = {}, onJoinCall = {}) }
        }

        // The eyebrow, uppercased, and the repeat beside it.
        onNodeWithText("EVENT DETAILS").assertExists()
        onNodeWithText("RECURRING").assertExists()
        onNodeWithText("Daily Meet").assertExists()
        // The audience chip above the card.
        onNodeWithText("Members").assertExists()
        // The card: when, what day, how they are meeting, the reminder, who.
        onNodeWithText("Wed, Oct 14 11:30 AM - 8:00 PM").assertExists()
        onNodeWithText("Duration: 8 hr 30 min").assertExists()
        onNodeWithText("Wed, Oct 14, 2026").assertExists()
        onNodeWithText("Week 42 of the Year").assertExists()
        onNodeWithText("Audio Call").assertExists()
        onNodeWithText("5 minutes before").assertExists()
        onNodeWithText("Randeep Iphone Test").assertExists()
        // The invitee card.
        onNodeWithText("21 Invitees").assertExists()
        onNodeWithText("SEE INVITEES").assertExists()
        // The footer: where this user stands, and the three things they can do.
        onNodeWithText("Pending invitation").assertExists()
        onNodeWithText("Join Call").assertExists()
        onNodeWithText("Accept").assertExists()
        onNodeWithText("Decline").assertExists()
    }

    @Test
    fun `the organiser gets Edit and Delete instead of an answer`() = runComposeUiTest {
        val mine = detail(
            event = event(status = null),
            permissions = EventPermissions(canManage = true),
        )
        setContent { ZillitTheme { EventDetailPopover(mine, onEvent = {}) } }

        onNodeWithText("Edit Event").assertExists()
        onNodeWithText("Delete").assertExists()
        // You do not answer your own invitation.
        onNodeWithText("Accept").assertDoesNotExist()
        onNodeWithText("Pending invitation").assertDoesNotExist()
    }

    @Test
    fun `a cancelled event says so and offers nothing to do about it`() = runComposeUiTest {
        val cancelled = detail(
            event = event(status = "cancelled"),
            permissions = EventPermissions(),
        )
        setContent { ZillitTheme { EventDetailPopover(cancelled, onEvent = {}) } }

        onNodeWithText("Cancelled: Daily Meet").assertExists()
        onNodeWithText("This event has been cancelled.").assertExists()
        onNodeWithText("Accept").assertDoesNotExist()
        onNodeWithText("Edit Event").assertDoesNotExist()
    }

    @Test
    fun `a finished event reads as expired`() = runComposeUiTest {
        val over = detail(permissions = EventPermissions(), hasFinished = true)
        setContent { ZillitTheme { EventDetailPopover(over, onEvent = {}) } }

        onNodeWithText("This event has expired.").assertExists()
        onNodeWithText("Join Call").assertDoesNotExist()
    }

    @Test
    fun `a personal event has nobody to meet, so it shows no call`() = runComposeUiTest {
        val personal = detail(
            event = event(audience = EventAudience.Personal, callType = null, status = null)
                .copy(invitedCount = 0),
            permissions = EventPermissions(canManage = true),
        )
        setContent { ZillitTheme { EventDetailPopover(personal, onEvent = {}) } }

        onNodeWithText("Personal").assertExists()
        onNodeWithText("Audio Call").assertDoesNotExist()
        // Nobody is invited to a personal event, so there is no card for them.
        onNodeWithText("SEE INVITEES").assertDoesNotExist()
    }

    @Test
    fun `the invitee card is the control, not just its words`() = runComposeUiTest {
        val raised = mutableListOf<CalendarEvent2Event>()
        setContent { ZillitTheme { EventDetailPopover(detail(), onEvent = { raised += it }) } }

        onNodeWithText("21 Invitees").performClick()

        assertEquals(listOf<CalendarEvent2Event>(CalendarEvent2Event.ShowInvitees), raised)
    }

    @Test
    fun `the invitee list splits crew from outside guests`() = runComposeUiTest {
        val list = InviteeListState(
            eventTitle = "Daily Meet",
            rows = listOf(
                EventInviteeRow("a", userId = "u1", status = InvitationStatus.Accepted),
                EventInviteeRow("b", userId = "u2", status = InvitationStatus.Rejected),
                EventInviteeRow("c", email = "guest@example.com", isExternal = true),
            ),
            directory = listOf(
                EventInvitee("u1", "Peach Android", designation = "Gaffer", department = "Electrical"),
                EventInvitee("u2", "Sam Grip", designation = "Key Grip"),
            ),
            isLoading = false,
        )
        setContent { ZillitTheme { InviteeListDialog(list, onEvent = {}) } }

        onNodeWithText("Members").assertExists()
        onNodeWithText("External Guests").assertExists()
        onNodeWithText("Peach Android").assertExists()
        onNodeWithText("Gaffer · Electrical").assertExists()
        onNodeWithText("guest@example.com").assertExists()
        // The summary counts only what is there — no "0 Pending" chip.
        onNodeWithText("1 Accepted").assertExists()
        onNodeWithText("1 Declined").assertExists()
    }

    @Test
    fun `a search with no match says so rather than showing nothing`() = runComposeUiTest {
        val list = InviteeListState(
            eventTitle = "Daily Meet",
            rows = listOf(EventInviteeRow("a", userId = "u1")),
            directory = listOf(EventInvitee("u1", "Peach Android")),
            search = "nobody",
            isLoading = false,
        )
        setContent { ZillitTheme { InviteeListDialog(list, onEvent = {}) } }

        onNodeWithText("No invitees match your search").assertExists()
    }

    private companion object {
        /** 2026-10-14T11:30Z, the event the mock-ups were drawn from. */
        const val START = 1_792_013_400_000L
        const val DAY_PART = 30_600_000L
        const val FACES = 21
    }
}
