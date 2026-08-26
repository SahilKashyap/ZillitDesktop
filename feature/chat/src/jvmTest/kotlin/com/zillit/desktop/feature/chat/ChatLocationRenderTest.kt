package com.zillit.desktop.feature.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.locationpicker.LocalLocationPicker
import com.zillit.desktop.core.locationpicker.LocationPicker
import com.zillit.desktop.core.locationpicker.PickedLocation
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.ChatLocation
import com.zillit.desktop.feature.chat.domain.ChatMessage
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
 * The shared-place bubble and the composer's pin, composed for real.
 *
 * The three things a location message must not do: draw as a blank bubble
 * (this client has no map raster of its own — see `LocationCard`), offer the
 * file actions the web and Android both strip for it
 * (`MyMessage.jsx:503-508`, `SenderMessage.jsx:292-295`, `DropDown.jsx:271`,
 * `ChatAndGroupPage.kt:2418`), or leave a "Share location" button in the
 * composer with no picker behind it.
 */
@OptIn(ExperimentalTestApi::class)
class ChatLocationRenderTest {

    private val aisha = CrewContact(userId = "u1", fullName = "Aisha Khan")

    private val aria = PickedLocation(
        name = "Aria Hotel",
        address = "12 Rajpath Marg, New Delhi",
        lat = 28.6129,
        lng = 77.2295,
    )

    private fun placeMessage(
        mine: Boolean = false,
        attachment: ChatAttachment? = null,
    ) = ChatMessage(
        id = "m-loc",
        uniqueId = "m-loc",
        senderId = if (mine) "me" else "u1",
        receiverId = if (mine) "u1" else "me",
        body = "Aria Hotel",
        timestampMillis = System.currentTimeMillis(),
        isMine = mine,
        attachment = attachment,
        location = ChatLocation(address = aria.address, lat = aria.lat, lng = aria.lng),
    )

    /** A picker that answers one place, counting how often it was asked. */
    private class FakePicker(private val answer: PickedLocation?) : LocationPicker {
        var opened = 0
        override suspend fun pick(initial: PickedLocation?, title: String): PickedLocation? {
            opened++
            return answer
        }
    }

    @Test
    fun `a shared place draws its label, address and coordinates, and offers the map`() =
        runComposeUiTest {
            val opened = mutableListOf<String>()
            setContent {
                ZillitTheme {
                    CompositionLocalProvider(
                        LocalChatSeams provides ChatSeams(onOpenUrl = { opened += it }),
                    ) {
                        ThreadPane(
                            state = ChatUiState(peer = aisha, messages = listOf(placeMessage())),
                            onEvent = {},
                        )
                    }
                }
            }

            onNodeWithText("Aria Hotel").assertExists()
            onNodeWithText("12 Rajpath Marg, New Delhi").assertExists()
            onNodeWithText("28.6129, 77.2295").assertExists()

            onNodeWithText("Open in Maps").performClick()
            waitForIdle()
            assertEquals(listOf("https://www.google.com/maps?q=28.6129,77.2295"), opened)
        }

    /**
     * Without a launcher there is no way out to a map, so the affordance is
     * absent rather than dead — the pane's own rule for the call buttons and
     * the group Delete. The place itself still reads.
     */
    @Test
    fun `with no URL launcher wired the card still reads but offers no way out`() =
        runComposeUiTest {
            setContent {
                ZillitTheme {
                    ThreadPane(
                        state = ChatUiState(peer = aisha, messages = listOf(placeMessage())),
                        onEvent = {},
                    )
                }
            }

            onNodeWithText("12 Rajpath Marg, New Delhi").assertExists()
            onNodeWithText("Open in Maps").assertDoesNotExist()
        }

    @Test
    fun `the composer offers Share location only when a picker is installed`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                ThreadPane(state = ChatUiState(peer = aisha), onEvent = {})
            }
        }
        onNodeWithContentDescription("Share location").assertDoesNotExist()
        // The rest of the composer is untouched.
        onNodeWithContentDescription("Attach a file").assertExists()
        onNodeWithContentDescription("Record a voice message").assertExists()
    }

    @Test
    fun `pressing the pin opens the picker once and shares what it answers`() = runComposeUiTest {
        val picker = FakePicker(aria)
        val events = mutableListOf<ChatEvent>()
        setContent {
            ZillitTheme {
                CompositionLocalProvider(LocalLocationPicker provides picker) {
                    ThreadPane(state = ChatUiState(peer = aisha), onEvent = { events += it })
                }
            }
        }

        onNodeWithContentDescription("Share location").performClick()
        waitForIdle()

        assertEquals(1, picker.opened, "one press, one picker")
        val shared = events.filterIsInstance<ChatEvent.ShareLocation>().single()
        assertEquals(aria, shared.place)
    }

    /** Cancelling the picker sends nothing at all. */
    @Test
    fun `cancelling the picker raises no event`() = runComposeUiTest {
        val picker = FakePicker(null)
        val events = mutableListOf<ChatEvent>()
        setContent {
            ZillitTheme {
                CompositionLocalProvider(LocalLocationPicker provides picker) {
                    ThreadPane(state = ChatUiState(peer = aisha), onEvent = { events += it })
                }
            }
        }

        onNodeWithContentDescription("Share location").performClick()
        waitForIdle()

        assertEquals(1, picker.opened)
        assertTrue(events.none { it is ChatEvent.ShareLocation })
    }

    /**
     * Menu parity: a phone-sent place carries a map screenshot as its
     * attachment, and both reference clients still strip Save/Download for it.
     * Reply and Delete stay.
     */
    @Test
    fun `the menu on a shared place drops Download, and keeps Reply`() = runComposeUiTest {
        val screenshot = ChatAttachment(
            media = "p1/chat/1758.png",
            name = "1758.png",
            contentType = "image",
        )
        setContent {
            ZillitTheme {
                ThreadPane(
                    state = ChatUiState(
                        peer = aisha,
                        messages = listOf(placeMessage(attachment = screenshot)),
                    ),
                    onEvent = {},
                )
            }
        }

        onNodeWithText("12 Rajpath Marg, New Delhi").performTouchInput { longClick() }
        waitForIdle()

        onNodeWithText("Reply").assertExists()
        onNodeWithText("Download").assertDoesNotExist()
        onNodeWithText("Copy image").assertDoesNotExist()
    }

    /** The same menu on an ordinary picture still offers its file actions. */
    @Test
    fun `a plain picture keeps Download - the strip is the location's alone`() = runComposeUiTest {
        val picture = ChatAttachment(media = "p1/chat/set.jpg", name = "set.jpg", contentType = "image")
        setContent {
            ZillitTheme {
                ThreadPane(
                    state = ChatUiState(
                        peer = aisha,
                        messages = listOf(
                            ChatMessage(
                                id = "m-img",
                                uniqueId = "m-img",
                                senderId = "u1",
                                receiverId = "me",
                                body = "on set",
                                timestampMillis = System.currentTimeMillis(),
                                isMine = false,
                                attachment = picture,
                            ),
                        ),
                    ),
                    onEvent = {},
                )
            }
        }

        onNodeWithText("on set").performTouchInput { longClick() }
        waitForIdle()

        onNodeWithText("Download").assertExists()
    }

    /** A reply quoting a place reads as a place, not as a screenshot's file name. */
    @Test
    fun `a quote of a shared place says so`() = runComposeUiTest {
        val reply = ChatMessage(
            id = "m-reply",
            uniqueId = "m-reply",
            senderId = "me",
            receiverId = "u1",
            body = "On my way",
            timestampMillis = System.currentTimeMillis(),
            isMine = true,
            replyTo = com.zillit.desktop.feature.chat.domain.ChatReplyRef(
                messageId = "m-loc",
                senderId = "u1",
                body = "Aria Hotel",
                kind = "location",
                attachmentName = "1758.png",
            ),
        )
        setContent {
            ZillitTheme {
                ThreadPane(
                    state = ChatUiState(peer = aisha, messages = listOf(reply)),
                    onEvent = {},
                )
            }
        }

        onNodeWithText("📍 Aria Hotel").assertExists()
        onNodeWithText("1758.png").assertDoesNotExist()
    }
}
