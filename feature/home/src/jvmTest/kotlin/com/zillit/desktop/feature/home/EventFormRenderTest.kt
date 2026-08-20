package com.zillit.desktop.feature.home

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.home.calendar.CalendarEvent2Event
import com.zillit.desktop.feature.home.calendar.CallType
import com.zillit.desktop.feature.home.calendar.EventAudience
import com.zillit.desktop.feature.home.calendar.EventDraft
import com.zillit.desktop.feature.home.calendar.EventFieldError
import com.zillit.desktop.feature.home.calendar.EventFormDialog
import com.zillit.desktop.feature.home.calendar.EventFormState
import com.zillit.desktop.feature.home.calendar.EventInvitee
import com.zillit.desktop.feature.home.calendar.TimezoneOption
import com.zillit.desktop.feature.home.calendar.message
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Composes the real form.
 *
 * The rules are unit-tested; this is about whether the controls that carry them
 * are on the screen at all. A validation nobody can satisfy because its field
 * was never drawn passes every model test and blocks every save — and the
 * sections here appear and disappear with the kind of event being written,
 * which is exactly the sort of conditional that stops composing silently.
 */
@OptIn(ExperimentalTestApi::class)
class EventFormRenderTest {

    private fun form(draft: EventDraft, errors: Set<EventFieldError> = emptySet()) = EventFormState(
        draft = draft,
        today = LocalDate(2026, 8, 4),
        invitees = listOf(
            EventInvitee("u1", "Peach Android", designation = "Camera Operator"),
            EventInvitee("u2", "Sam Grip", designation = "Key Grip"),
        ),
        errors = errors,
    )

    private fun newEvent() = EventDraft(
        title = "Unit call",
        dateText = "2026-08-04",
        startText = "09:00",
        endText = "17:30",
    )

    @Test
    fun `a new members event offers every control its rules need`() = runComposeUiTest {
        setContent { ZillitTheme { EventFormDialog(form(newEvent()), onEvent = {}) } }

        // Who it is for, which the web asks first.
        onNodeWithText("Members").assertExists()
        onNodeWithText("Personal").assertExists()
        // How they are meeting — required, so it has to be reachable.
        onNodeWithText("Call type").assertExists()
        onNodeWithText("Video call").assertExists()
        onNodeWithText("Meet in person & call").assertExists()
        // Who is coming, by crew record or by address.
        onNodeWithText("Invite crew").assertExists()
        onNodeWithText("Add crew").assertExists()
        onNodeWithText("External guests").assertExists()
        onNodeWithText("I will not be part of this event").assertExists()
        // And the rest of the form.
        onNodeWithText("Repeat").assertExists()
        onNodeWithText("Reminder").assertExists()
        onNodeWithText("Create").assertExists()
    }

    @Test
    fun `a personal event drops the parts that only apply to members`() = runComposeUiTest {
        val personal = newEvent().copy(audience = EventAudience.Personal)
        setContent { ZillitTheme { EventFormDialog(form(personal), onEvent = {}) } }

        onNodeWithText("Call type").assertDoesNotExist()
        onNodeWithText("External guests").assertDoesNotExist()
        onNodeWithText("Add crew").assertDoesNotExist()
        // The date and times are still everybody's business.
        onNodeWithText("Start time").assertExists()
    }

    @Test
    fun `the audience choice is not offered when editing`() = runComposeUiTest {
        // Switching a personal event into a members one part-way through its
        // life leaves the people newly on it with no idea where it came from.
        setContent { ZillitTheme { EventFormDialog(form(newEvent().copy(id = "e1")), onEvent = {}) } }

        onNodeWithText("Personal").assertDoesNotExist()
        onNodeWithText("Save").assertExists()
    }

    @Test
    fun `a night shoot says which day it ends on`() = runComposeUiTest {
        val night = newEvent().copy(startText = "22:00", endText = "04:00")
        setContent { ZillitTheme { EventFormDialog(form(night), onEvent = {}) } }

        onNodeWithText("Ends the next day, 2026-08-05").assertExists()
    }

    @Test
    fun `a same-day event says so plainly`() = runComposeUiTest {
        setContent { ZillitTheme { EventFormDialog(form(newEvent()), onEvent = {}) } }

        onNodeWithText("Ends 2026-08-04").assertExists()
    }

    @Test
    fun `every refusal reaches the screen`() = runComposeUiTest {
        val errors = setOf(
            EventFieldError.TitleTooShort,
            EventFieldError.DateInPast,
            EventFieldError.TooShort,
            EventFieldError.ReminderPassed,
            EventFieldError.CallTypeMissing,
            EventFieldError.NoInvitees,
        )
        setContent { ZillitTheme { EventFormDialog(form(newEvent(), errors), onEvent = {}) } }

        errors.forEach { error ->
            onNodeWithText(error.message).assertExists()
        }
    }

    @Test
    fun `the timezone menu opens without dying on intrinsic measurement`() = runComposeUiTest {
        // The same lazy-list-in-a-menu shape that killed the time picker's
        // first draft: the menu asks its content for intrinsic sizes, which a
        // lazy list cannot answer.
        val zones = form(newEvent()).copy(
            timezones = listOf(
                TimezoneOption("Asia/Kolkata", "(GMT+05:30) Kolkata"),
                TimezoneOption("Europe/London", "(GMT+01:00) London"),
            ),
        )
        setContent { ZillitTheme { EventFormDialog(zones, onEvent = {}) } }

        onNodeWithText("Device timezone").performClick()
        onNodeWithText("(GMT+05:30) Kolkata").assertExists()
    }

    @Test
    fun `the crew dropdown searches and shows designations, and picking invites`() = runComposeUiTest {
        val events = mutableListOf<CalendarEvent2Event>()
        setContent { ZillitTheme { EventFormDialog(form(newEvent()), onEvent = events::add) } }

        // The People card sits below the dialog's fold. A fully clipped node
        // reports zero bounds, so performScrollTo computes a zero delta and
        // the tap lands on the scrim — drive the body's scroll action instead.
        scrollFormToBottom()
        onNodeWithText("Add crew").performClick()
        // Faces come with names and designations, so the two Sams read apart.
        onNodeWithText("Peach Android").assertExists()
        onNodeWithText("Camera Operator").assertExists()
        onNodeWithText("Key Grip").assertExists()

        onNodeWithText("Sam Grip").performClick()
        val changed = events.filterIsInstance<CalendarEvent2Event.FormChanged>().last()
        assertEquals(setOf("u2"), changed.draft.inviteeIds)
        // The menu stays up: events go to groups, not to one person at a time.
        onNodeWithText("Peach Android").assertExists()
    }

    @Test
    fun `a chosen crew member becomes a chip whose cross removes them`() = runComposeUiTest {
        val events = mutableListOf<CalendarEvent2Event>()
        val chosen = newEvent().copy(inviteeIds = setOf("u1"))
        setContent { ZillitTheme { EventFormDialog(form(chosen), onEvent = events::add) } }

        onNodeWithText("Peach Android").assertExists()
        onNodeWithText("Invite crew — 1 selected").assertExists()

        scrollFormToBottom()
        onNodeWithContentDescription("Remove Peach Android").performClick()
        val changed = events.filterIsInstance<CalendarEvent2Event.FormChanged>().last()
        assertEquals(emptySet(), changed.draft.inviteeIds)
    }

    @Test
    fun `both time fields carry a picker`() = runComposeUiTest {
        setContent { ZillitTheme { EventFormDialog(form(newEvent()), onEvent = {}) } }

        onAllNodesWithContentDescription("Choose a time").assertCountEquals(2)
    }

    @Test
    fun `picking a time from the list writes it into the draft`() = runComposeUiTest {
        val events = mutableListOf<CalendarEvent2Event>()
        setContent { ZillitTheme { EventFormDialog(form(newEvent()), onEvent = events::add) } }

        onAllNodesWithContentDescription("Choose a time")[0].performClick()
        onNodeWithText("10:15").performClick()

        val changed = events.filterIsInstance<CalendarEvent2Event.FormChanged>().last()
        assertEquals("10:15", changed.draft.startText)
    }

    @Test
    fun `the location is marked required once people are travelling to it`() = runComposeUiTest {
        val meeting = newEvent().copy(callType = CallType.InPersonAndCall)
        setContent { ZillitTheme { EventFormDialog(form(meeting), onEvent = {}) } }

        onNodeWithText("Location — required").assertExists()
    }

    /**
     * Scrolls the dialog body all the way down. A node below the fold is
     * fully clipped and reports zero bounds, which defeats `performScrollTo`
     * (its scroll delta comes out zero) and lands taps on the scrim.
     */
    private fun ComposeUiTest.scrollFormToBottom() {
        onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) {
            it(0f, 10_000f)
        }
    }
}
