package com.zillit.desktop.feature.home

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.home.calendar.CallType
import com.zillit.desktop.feature.home.calendar.EventAudience
import com.zillit.desktop.feature.home.calendar.EventDraft
import com.zillit.desktop.feature.home.calendar.EventFieldError
import com.zillit.desktop.feature.home.calendar.EventFormDialog
import com.zillit.desktop.feature.home.calendar.EventFormState
import com.zillit.desktop.feature.home.calendar.EventInvitee
import com.zillit.desktop.feature.home.calendar.message
import kotlinx.datetime.LocalDate
import kotlin.test.Test

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
        invitees = listOf(EventInvitee("u1", "Peach Android"), EventInvitee("u2", "Sam Grip")),
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
        onNodeWithText("Peach Android").assertExists()
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
        onNodeWithText("Peach Android").assertDoesNotExist()
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
    fun `the location is marked required once people are travelling to it`() = runComposeUiTest {
        val meeting = newEvent().copy(callType = CallType.InPersonAndCall)
        setContent { ZillitTheme { EventFormDialog(form(meeting), onEvent = {}) } }

        onNodeWithText("Location — required").assertExists()
    }
}
