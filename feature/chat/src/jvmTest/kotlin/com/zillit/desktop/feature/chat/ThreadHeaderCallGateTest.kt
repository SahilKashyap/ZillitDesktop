package com.zillit.desktop.feature.chat

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.ui.ChatUiState
import com.zillit.desktop.feature.chat.ui.ThreadPane
import kotlin.test.Test

/**
 * Who the thread header offers a call to.
 *
 * A person who left the production keeps their device id on the crew row —
 * the phone is still registered — so a gate on the device alone kept showing
 * phone and video for someone the other end would refuse. The buttons follow
 * membership, not hardware; the "Disconnected" caption beside the name says
 * why they are gone.
 */
@OptIn(ExperimentalTestApi::class)
class ThreadHeaderCallGateTest {

    private val active = CrewContact(userId = "u1", fullName = "Aisha Khan", deviceId = "d1")
    private val departed = active.copy(userId = "u2", fullName = "Vivek Mishra", hasLeft = true)

    @Test
    fun `an active peer with a device gets both call buttons`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                ThreadPane(
                    state = ChatUiState(peer = active),
                    onEvent = {},
                    onCall = { _, _ -> },
                )
            }
        }

        onAllNodesWithContentDescription("Start call").assertCountEquals(1)
        onAllNodesWithContentDescription("Start video call").assertCountEquals(1)
    }

    @Test
    fun `a peer who left keeps the thread and loses the call buttons`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                ThreadPane(
                    state = ChatUiState(peer = departed),
                    onEvent = {},
                    onCall = { _, _ -> },
                )
            }
        }

        // The conversation is still readable, and the header says why the
        // buttons are gone.
        onNodeWithText("Vivek Mishra").assertExists()
        onNodeWithText("Disconnected").assertExists()
        onAllNodesWithContentDescription("Start call").assertCountEquals(0)
        onAllNodesWithContentDescription("Start video call").assertCountEquals(0)
    }

    @Test
    fun `a group is always callable, whoever has left it`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                ThreadPane(
                    state = ChatUiState(
                        peer = CrewContact(userId = "room-1", fullName = "Camera unit"),
                        peerIsGroup = true,
                    ),
                    onEvent = {},
                    onCall = { _, _ -> },
                )
            }
        }

        // The room is the address; no device id is needed to ring it.
        onAllNodesWithContentDescription("Start call").assertCountEquals(1)
    }
}
