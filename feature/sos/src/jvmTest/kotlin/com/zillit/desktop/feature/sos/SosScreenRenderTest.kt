package com.zillit.desktop.feature.sos

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.sos.domain.SosAlert
import com.zillit.desktop.feature.sos.domain.SosViewer
import com.zillit.desktop.feature.sos.ui.SosConfirm
import com.zillit.desktop.feature.sos.ui.SosEvent
import com.zillit.desktop.feature.sos.ui.SosScreen
import com.zillit.desktop.feature.sos.ui.SosUiState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Composes the real screen: the alarm, an alert row with its sender, text and
 * stamp, and the confirmation the alarm opens.
 */
@OptIn(ExperimentalTestApi::class)
class SosScreenRenderTest {

    private val row = SosAlert(
        id = "a1",
        uuid = "u1",
        senderId = "u-sam",
        senderNameHint = "Sam Carter",
        text = "Sam Carter needs help",
        mapsUrl = "https://maps.google.com/?q=1,2",
        action = "sos_alert",
        contactInfo = "",
        // 2026-08-12T14:32:00Z — the stamp is drawn in the machine's zone, so only its presence is asserted.
        createdMillis = 1_786_545_120_000L,
        updatedMillis = 1_786_545_120_000L,
        deleted = false,
    )

    private val state = SosUiState(
        viewer = SosViewer(userId = "u-me", phone = "7700900000"),
        loaded = true,
        alerts = listOf(row),
    )

    @Test
    fun `the alarm and an alert row are on the page, and both raise their events`() = runComposeUiTest {
        val events = mutableListOf<SosEvent>()
        setContent { ZillitTheme { SosScreen(state = state, onEvent = { events += it }) } }

        onNodeWithText("Send SOS").assertIsEnabled().performClick()
        onNodeWithText("Sam Carter").assertExists()
        onNodeWithText("Sam Carter needs help").assertExists()
        onNodeWithText(row.timeLabel).assertExists()
        onNodeWithContentDescription("Open location").performClick()
        onNodeWithContentDescription("Delete alert").performClick()

        assertEquals(
            listOf(SosEvent.AskSendAlert, SosEvent.OpenMap("a1"), SosEvent.AskDeleteAlert("a1")),
            events,
        )
    }

    @Test
    fun `an empty feed says so`() = runComposeUiTest {
        setContent {
            ZillitTheme { SosScreen(state = SosUiState(loaded = true), onEvent = {}) }
        }
        onNodeWithText("No SOS alerts").assertExists()
    }

    @Test
    fun `the alarm asks before it fires`() = runComposeUiTest {
        val events = mutableListOf<SosEvent>()
        setContent {
            ZillitTheme {
                SosScreen(state = state.copy(confirm = SosConfirm.SendAlert), onEvent = { events += it })
            }
        }
        onNodeWithText("Send SOS?").assertExists()
        onNodeWithText("No").performClick()
        onNodeWithText("Yes").performClick()
        assertEquals(listOf(SosEvent.CancelConfirm, SosEvent.ConfirmAction), events)
    }
}
