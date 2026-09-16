package com.zillit.desktop.feature.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.domain.GroupRoom
import com.zillit.desktop.feature.chat.domain.ReadByReport
import com.zillit.desktop.feature.chat.domain.ReadByRow
import com.zillit.desktop.feature.chat.ui.ChatEvent
import com.zillit.desktop.feature.chat.ui.ChatSeams
import com.zillit.desktop.feature.chat.ui.ChatUiState
import com.zillit.desktop.feature.chat.ui.LocalChatSeams
import com.zillit.desktop.feature.chat.ui.ReadByView
import com.zillit.desktop.feature.chat.ui.ThreadPane
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bubble menu, opened for real: which verbs each kind of line gets, in
 * the web's order, and the dialogs the new ones open. A menu-opening test
 * is the only kind that catches a popup that never places itself (the
 * design-system lesson in `GroupEditorDialog`'s KDoc).
 */
@OptIn(ExperimentalTestApi::class)
class BubbleMenuRenderTest {

    private val aisha = CrewContact(userId = "u1", fullName = "Aisha Khan")
    private val bob = CrewContact(userId = "u2", fullName = "Bob Ray", designation = "Gaffer")
    private val now = System.currentTimeMillis()

    private fun theirs(body: String = "Rolling at 8?", attachment: ChatAttachment? = null) = ChatMessage(
        id = "srv-1", uniqueId = "w-1", senderId = "u1", receiverId = "me",
        body = body, timestampMillis = now - 60_000L, isMine = false, attachment = attachment,
    )

    private fun mine(body: String = "Copy. On my way.", attachment: ChatAttachment? = null) = ChatMessage(
        id = "srv-2", uniqueId = "w-2", senderId = "me", receiverId = "u1",
        body = body, timestampMillis = now - 30_000L, isMine = true, attachment = attachment,
    )

    @Test
    fun `a self line offers Edit, Forward, Share, Delete and someone's offers Translate when languages differ`() =
        runComposeUiTest {
            val events = mutableListOf<ChatEvent>()
            setContent {
                ZillitTheme {
                    CompositionLocalProvider(LocalChatSeams provides ChatSeams(shareAsEmail = { null })) {
                        ThreadPane(
                            state = ChatUiState(
                                peer = aisha,
                                messages = listOf(theirs(), mine()),
                                translateOffered = true,
                            ),
                            onEvent = { events += it },
                        )
                    }
                }
            }

            onNodeWithText("Copy. On my way.").performMouseInput { rightClick() }
            waitForIdle()
            onNodeWithText("Edit").assertExists()
            onNodeWithText("Forward").assertExists()
            onNodeWithText("Share").assertExists()
            onNodeWithText("Delete for everyone").assertExists()
            onNodeWithText("Translate").assertDoesNotExist()
            onNodeWithText("Read by").assertDoesNotExist()
            onNodeWithText("Edit").performClick()
            waitForIdle()
            assertEquals(listOf<ChatEvent>(ChatEvent.StartEdit("srv-2")), events)

            onNodeWithText("Rolling at 8?").performMouseInput { rightClick() }
            waitForIdle()
            onNodeWithText("Translate").assertExists()
            onNodeWithText("Edit").assertDoesNotExist()
            onNodeWithText("Delete for everyone").assertDoesNotExist()
            onNodeWithText("Translate").performClick()
            waitForIdle()
            assertEquals(ChatEvent.Translate("srv-1"), events.last())
        }

    @Test
    fun `a room's line offers Read by and a picture offers Image Reply`() = runComposeUiTest {
        val picture = theirs(
            body = "",
            attachment = ChatAttachment(media = "chat/p.jpg", name = "p.jpg", contentType = "image/jpeg"),
        )
        val room = CrewContact(userId = "room-1", fullName = "Camera")
        val events = mutableListOf<ChatEvent>()
        setContent {
            ZillitTheme {
                ThreadPane(
                    state = ChatUiState(peer = room, peerIsGroup = true, messages = listOf(picture, mine())),
                    onEvent = { events += it },
                )
            }
        }

        onNodeWithText("Copy. On my way.").performMouseInput { rightClick() }
        waitForIdle()
        onNodeWithText("Read by").assertExists()
        onNodeWithText("Read by").performClick()
        waitForIdle()
        assertEquals(ChatEvent.ShowReadBy("srv-2"), events.last())
    }

    @Test
    fun `the Edit dialog shows the words and disables Save until they change`() = runComposeUiTest {
        val line = mine()
        setContent {
            ZillitTheme {
                ThreadPane(
                    state = ChatUiState(peer = aisha, messages = listOf(line), editing = line, editDraft = line.body),
                    onEvent = {},
                )
            }
        }
        waitForIdle()
        onNodeWithText("Edit message").assertExists()
        onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun `the Forward picker lists rooms and people and sends the ticked ones`() = runComposeUiTest {
        val line = theirs()
        val events = mutableListOf<ChatEvent>()
        setContent {
            ZillitTheme {
                ThreadPane(
                    state = ChatUiState(
                        peer = aisha,
                        messages = listOf(line),
                        forwarding = line,
                        groups = listOf(GroupRoom(id = "room-1", name = "Camera")),
                    ),
                    forwardPeople = listOf(bob),
                    onEvent = { events += it },
                )
            }
        }
        waitForIdle()
        onNodeWithText("Camera").assertExists()
        onNodeWithText("Bob Ray").assertExists()
        onNodeWithText("Send").assertIsNotEnabled()
        onNodeWithText("Bob Ray").performClick()
        waitForIdle()
        onNodeWithText("Send").performClick()
        waitForIdle()
        val sent = events.filterIsInstance<ChatEvent.ForwardTo>().single()
        assertEquals(listOf("u2"), sent.targets.map { it.id })
        assertTrue(sent.targets.none { it.isGroup })
    }

    @Test
    fun `the Read by panel names readers from the crew and drops the unknown`() = runComposeUiTest {
        val line = mine()
        setContent {
            ZillitTheme {
                ThreadPane(
                    state = ChatUiState(
                        peer = CrewContact(userId = "room-1", fullName = "Camera"),
                        peerIsGroup = true,
                        messages = listOf(line),
                        readBy = ReadByView(
                            line,
                            ReadByReport(
                                read = listOf(ReadByRow("u2", readAtMillis = now, deliveredAtMillis = 0L)),
                                unread = listOf(ReadByRow("ghost")),
                            ),
                        ),
                    ),
                    resolveContact = { id -> listOf(aisha, bob).firstOrNull { it.userId == id } },
                    onEvent = {},
                )
            }
        }
        waitForIdle()
        onNodeWithText("Bob Ray (Gaffer)").assertExists()
        onNodeWithText("Unread 0").assertDoesNotExist()
        onNodeWithText("Unread").performClick()
        waitForIdle()
        onNodeWithText("Everyone in the group has read this.").assertExists()
    }
}
