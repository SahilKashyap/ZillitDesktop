package com.zillit.desktop.feature.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.database.UserLocation
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.locationpicker.LocalLocationPicker
import com.zillit.desktop.core.locationpicker.LocationPicker
import com.zillit.desktop.core.locationpicker.PickedLocation
import com.zillit.desktop.core.locationpicker.PickerStart
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.ui.ChatEvent
import com.zillit.desktop.feature.chat.ui.ChatSeams
import com.zillit.desktop.feature.chat.ui.ChatUiState
import com.zillit.desktop.feature.chat.ui.LocalChatSeams
import com.zillit.desktop.feature.chat.ui.ThreadPane
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "View user's location" in the contact panel — the web's
 * `cnc_latest/components/personal/InfoSider.jsx` tile, composed for real.
 */
@OptIn(ExperimentalTestApi::class)
class ContactLocationRenderTest {

    private val me = CrewContact(userId = "me", fullName = "Sahil Kashyap")

    private fun aisha(location: UserLocation?) =
        CrewContact(userId = "u1", fullName = "Aisha Khan", location = location)

    private val sharing = UserLocation(latitude = 19.076, longitude = 72.8777, shared = true)
    private val silent = UserLocation(shared = false)

    /** A map that records what it was asked to show, and nothing else. */
    private class FakeMap : LocationPicker {
        val viewed = mutableListOf<PickedLocation>()
        override suspend fun pick(initial: PickedLocation?, title: String, near: PickerStart?) = null
        override suspend fun view(place: PickedLocation, title: String) {
            viewed += place
        }
    }

    /** The pane with its info panel already open on [peer]. */
    private fun androidx.compose.ui.test.ComposeUiTest.openPanel(
        peer: CrewContact,
        seams: ChatSeams,
        onEvent: (ChatEvent) -> Unit = {},
        map: LocationPicker? = null,
    ) {
        setContent {
            ZillitTheme {
                CompositionLocalProvider(LocalChatSeams provides seams, LocalLocationPicker provides map) {
                    ThreadPane(
                        state = ChatUiState(peer = peer),
                        onEvent = onEvent,
                        selfId = "me",
                        resolveContact = { id -> listOf(me, peer).firstOrNull { it.userId == id } },
                    )
                }
            }
        }
        // The header's name opens contact info, as it does in WhatsApp.
        onAllNodesWithText(peer.fullName)[0].performClick()
    }

    @Test
    fun `a peer who shares opens on the app's own map, not the browser`() = runComposeUiTest {
        val opened = mutableListOf<String>()
        val map = FakeMap()
        openPanel(aisha(sharing), ChatSeams(onOpenUrl = { opened += it }), map = map)

        onNodeWithText("Location").performClick()
        waitForIdle()
        val shown = map.viewed.single()
        assertEquals(19.076, shown.lat)
        assertEquals(72.8777, shown.lng)
        assertEquals("Aisha Khan", shown.name, "the dialog is titled by whose position it is")
        assertTrue(opened.isEmpty(), "the browser is not opened when there is a map in the app")
    }

    @Test
    fun `with no map in the build it still falls back to the browser`() = runComposeUiTest {
        val opened = mutableListOf<String>()
        openPanel(aisha(sharing), ChatSeams(onOpenUrl = { opened += it }), map = null)

        onNodeWithText("Location").performClick()
        assertEquals(listOf("https://www.google.com/maps?q=19.076,72.8777"), opened)
    }

    @Test
    fun `a map alone is enough to show the tile`() = runComposeUiTest {
        // No browser seam wired: the tile used to need one, and now does not.
        openPanel(aisha(sharing), ChatSeams(), map = FakeMap())
        onAllNodesWithText("Location").assertCountEquals(1)
    }

    @Test
    fun `a peer who does not share is asked to turn it on, in this thread`() = runComposeUiTest {
        val events = mutableListOf<ChatEvent>()
        openPanel(aisha(silent), ChatSeams(onOpenUrl = {}), onEvent = { events += it })

        onNodeWithText("Location").performClick()
        onNodeWithText(
            "Individual tracking is disabled by the user. " +
                "Do you want to send the user a message to enable it?",
        ).assertExists()

        onNodeWithText("Yes").performClick()
        val ask = events.filterIsInstance<ChatEvent.AskToShareLocation>().single()
        assertTrue(ask.body.startsWith("Sahil Kashyap "), ask.body)
        assertTrue(ask.body.contains("requested to track you"), ask.body)
    }

    @Test
    fun `No sends nothing`() = runComposeUiTest {
        val events = mutableListOf<ChatEvent>()
        openPanel(aisha(silent), ChatSeams(onOpenUrl = {}), onEvent = { events += it })

        onNodeWithText("Location").performClick()
        onNodeWithText("No").performClick()
        assertTrue(events.none { it is ChatEvent.AskToShareLocation }, events.toString())
    }

    @Test
    fun `a viewer whose own join is pending gets no tile`() = runComposeUiTest {
        openPanel(aisha(sharing), ChatSeams(onOpenUrl = {}, viewerIsPending = { true }))
        onAllNodesWithText("Location").assertCountEquals(0)
    }

    @Test
    fun `neither a map nor a browser, no tile`() = runComposeUiTest {
        openPanel(aisha(sharing), ChatSeams(), map = null)
        onAllNodesWithText("Location").assertCountEquals(0)
    }
}
