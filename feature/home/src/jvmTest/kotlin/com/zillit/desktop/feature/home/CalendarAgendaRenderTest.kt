package com.zillit.desktop.feature.home

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.home.calendar.CalendarEvent
import com.zillit.desktop.feature.home.calendar.CalendarScreen
import com.zillit.desktop.feature.home.calendar.CalendarUiState
import com.zillit.desktop.feature.home.calendar.CallType
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The day panel's Join Call button.
 *
 * The rule itself is [com.zillit.desktop.feature.home.calendar.canJoinCall],
 * unit-tested next door; this is about whether the agenda draws the button at
 * all. It is reached through three composables that each take the handler as
 * a nullable parameter, which is exactly the shape that silently stops
 * composing when one of them forgets to pass it on — and the symptom is a
 * calendar where the only way into a call is to open the popover first.
 */
@OptIn(ExperimentalTestApi::class)
class CalendarAgendaRenderTest {

    private val today = LocalDate(2026, 10, 12)
    private val zone = TimeZone.UTC

    /** Midday on [today] — comfortably inside the joinable window. */
    private val start = LocalDateTime(2026, 10, 12, 12, 0).toInstant(zone).toEpochMilliseconds()

    private fun event(
        callType: CallType? = CallType.Video,
        room: String = "group-7",
    ) = CalendarEvent(
        id = "e1",
        title = "test agora",
        startMillis = start,
        endMillis = start + 3_600_000,
        callType = callType,
        hasCall = callType != null,
        cncGroupId = room,
    )

    private fun state(event: CalendarEvent) = CalendarUiState(
        anchor = today,
        today = today,
        events = listOf(event),
        zone = zone,
    )

    @Test
    fun `an event whose call is live offers the agenda row its own Join Call`() {
        var joined: CalendarEvent? = null
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    CalendarScreen(
                        state = state(event()),
                        onEvent = {},
                        onJoinCall = { joined = it },
                    )
                }
            }

            onNodeWithText("Join Call").assertIsDisplayed().performClick()
        }
        assertEquals("e1", joined?.id)
    }

    @Test
    fun `an event with nothing to dial draws no button`() {
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    CalendarScreen(
                        state = state(event(room = "")),
                        onEvent = {},
                        onJoinCall = {},
                    )
                }
            }

            // The row is there (the grid chip has no time range); the
            // button is what must not be.
            onNodeWithText("12:00 – 13:00").assertIsDisplayed()
            onNodeWithText("Join Call").assertDoesNotExist()
        }
    }

    @Test
    fun `a host with no calling draws no button`() {
        runComposeUiTest {
            setContent {
                ZillitTheme { CalendarScreen(state = state(event()), onEvent = {}) }
            }

            // The row is there (the grid chip has no time range); the
            // button is what must not be.
            onNodeWithText("12:00 – 13:00").assertIsDisplayed()
            onNodeWithText("Join Call").assertDoesNotExist()
        }
    }
}
