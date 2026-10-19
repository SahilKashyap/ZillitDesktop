package com.zillit.desktop.feature.home

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.home.calendar.CalendarScreen
import com.zillit.desktop.feature.home.calendar.CalendarUiState
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The day panel's empty state.
 *
 * A day that has been and gone cannot take a new event — `validate` refuses
 * one — so the panel does not offer to make one there. The toolbar's own New
 * Event stays either way: it creates on the focused day, which the view model
 * clamps forward to today, so it always has somewhere to put the event. That
 * second button is why these assertions count nodes rather than look for one.
 */
@OptIn(ExperimentalTestApi::class)
class CalendarEmptyDayRenderTest {

    private val today = LocalDate(2026, 10, 12)

    private fun state(selected: LocalDate) = CalendarUiState(
        anchor = selected,
        today = today,
        selected = selected,
        events = emptyList(),
        zone = TimeZone.UTC,
    )

    private fun newEventButtons(selected: LocalDate): Int {
        var count = 0
        runComposeUiTest {
            setContent {
                ZillitTheme { CalendarScreen(state = state(selected), onEvent = {}) }
            }

            onNodeWithText("Nothing scheduled.").assertIsDisplayed()
            count = onAllNodesWithText("New Event").fetchSemanticsNodes().size
        }
        return count
    }

    @Test
    fun `a free day still ahead offers to fill it`() {
        assertEquals(2, newEventButtons(LocalDate(2026, 10, 15)), "the toolbar's and the panel's")
    }

    @Test
    fun `today still counts as ahead`() {
        // The day is not over until it is over: an event at four this
        // afternoon is an ordinary thing to add at two.
        assertEquals(2, newEventButtons(today), "the toolbar's and the panel's")
    }

    @Test
    fun `a day that has passed is left alone`() {
        assertEquals(1, newEventButtons(LocalDate(2026, 10, 8)), "the toolbar's only")
    }
}
