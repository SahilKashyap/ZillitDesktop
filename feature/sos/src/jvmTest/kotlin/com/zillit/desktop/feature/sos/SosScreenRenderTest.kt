package com.zillit.desktop.feature.sos

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.sos.domain.SosAlert
import com.zillit.desktop.feature.sos.domain.SosContact
import com.zillit.desktop.feature.sos.domain.SosContactKind
import com.zillit.desktop.feature.sos.domain.SosCrewMember
import com.zillit.desktop.feature.sos.domain.SosViewer
import com.zillit.desktop.feature.sos.ui.SosContactTab
import com.zillit.desktop.feature.sos.ui.SosContactsState
import com.zillit.desktop.feature.sos.ui.SosConfirm
import com.zillit.desktop.feature.sos.ui.SosEvent
import com.zillit.desktop.feature.sos.ui.SosScreen
import com.zillit.desktop.feature.sos.ui.SosUiState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Composes the real screen: the alarm, an alert row with its sender, text and
 * stamp, the confirmation the alarm opens, and the receivers card with its
 * picker and list.
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
        onNodeWithText("All quiet").assertExists()
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
        onNodeWithText("Cancel").performClick()
        onNodeWithText("Send SOS now").performClick()
        assertEquals(listOf(SosEvent.CancelConfirm, SosEvent.ConfirmAction), events)
    }

    @Test
    fun `the receivers card offers the crew, lists the receivers, and says who the alarm reaches`() =
        runComposeUiTest {
            val events = mutableListOf<SosEvent>()
            val contacts = SosContactsState(
                rows = listOf(
                    SosContact(
                        id = "c1",
                        kind = SosContactKind.Internal,
                        entryType = "user",
                        userId = "u-ada",
                        userFullName = "Ada Rees",
                        userDesignation = "Grip",
                        contactName = "",
                        relation = "",
                        countryCode = "",
                        phoneNumber = "",
                        createdMillis = 0L,
                    ),
                ),
                crew = listOf(
                    SosCrewMember(userId = "u-ada", fullName = "Ada Rees", designation = "Grip"),
                    SosCrewMember(userId = "u-sam", fullName = "Sam Carter", designation = "Gaffer"),
                ),
                tab = SosContactTab.Member,
            )
            setContent {
                ZillitTheme {
                    SosScreen(state = state.copy(alerts = emptyList(), contacts = contacts), onEvent = { events += it })
                }
            }

            onNodeWithText("Reaches 1 crew member and 0 outside contacts").assertExists()
            // Sam is addable; Ada is already a receiver, so she is on the list and not in the picker.
            onNodeWithText("Add").performClick()
            onNodeWithText("Ada Rees").assertExists()
            onNodeWithContentDescription("Edit receiver").performClick()
            onNodeWithContentDescription("Remove receiver").performClick()
            onNodeWithText("Outsider").performClick()

            assertEquals(
                listOf(
                    SosEvent.SubmitMember("u-sam"),
                    SosEvent.EditContact("c1"),
                    SosEvent.AskDeleteContact("c1"),
                    SosEvent.SelectContactTab(SosContactTab.Outsider),
                ),
                events,
            )
        }

    @Test
    fun `an empty receiver list is called out on the hero`() = runComposeUiTest {
        setContent { ZillitTheme { SosScreen(state = state, onEvent = {}) } }
        onNodeWithText("No receivers yet — add some below").assertExists()
    }
}
