package com.zillit.desktop.feature.home

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.home.calendar.CalendarEvent2Event
import com.zillit.desktop.feature.home.calendar.CalendarScreen
import com.zillit.desktop.feature.home.calendar.CalendarUiState
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The notice a click on a day that has gone puts up.
 *
 * The rule itself is model-tested; this is about the dialog being composed
 * into the screen at all and its one button reporting the dismissal. A notice
 * that never mounts, or an Ok that does not close it, leaves the calendar with
 * a modal nobody can get past — and both fail silently in the model.
 */
@OptIn(ExperimentalTestApi::class)
class CalendarPastDayNoticeRenderTest {

    private val today = LocalDate(2026, 10, 12)

    private fun state() = CalendarUiState(
        anchor = today,
        today = today,
        selected = LocalDate(2026, 10, 8),
        pastDayNotice = true,
        zone = TimeZone.UTC,
    )

    @Test
    fun `the notice says why, and Ok closes it`() = runComposeUiTest {
        val seen = mutableListOf<CalendarEvent2Event>()
        setContent { ZillitTheme { CalendarScreen(state = state(), onEvent = { seen += it }) } }

        onNodeWithText("You can't add an event on a previous date").assertExists()
        onNodeWithText("Ok").performClick()

        assertEquals(listOf<CalendarEvent2Event>(CalendarEvent2Event.DismissPastDayNotice), seen)
    }
}
