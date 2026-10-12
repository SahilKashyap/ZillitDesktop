package com.zillit.desktop.feature.chat

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.domain.lastMessageAt
import com.zillit.desktop.feature.chat.ui.ChatUiState
import com.zillit.desktop.feature.chat.ui.ThreadPane
import kotlin.test.Test

/**
 * The line under the thread header's name, as the web's
 * `UserStatusComponent` draws it: "Online", or offline the last-seen stamp —
 * the presence node's `update_time` first, the users list's last activity
 * after — and "Name - Disconnected" above it for someone who left.
 */
@OptIn(ExperimentalTestApi::class)
class ThreadHeaderPresenceTest {

    private val nodeStamp = 1_774_072_800_000L
    private val listStamp = 1_771_000_000_000L
    private val aisha = CrewContact(
        userId = "u1",
        fullName = "Aisha Khan",
        department = "Camera",
        deviceId = "d1",
        lastActiveMillis = listStamp,
    )

    private fun ComposeUiTest.header(state: ChatUiState) =
        setContent { ZillitTheme { ThreadPane(state = state, onEvent = {}) } }

    @Test
    fun `an online peer reads Online`() = runComposeUiTest {
        header(ChatUiState(peer = aisha, peerOnline = true, peerLastSeenMillis = nodeStamp))
        onNodeWithText("Online").assertExists()
        onAllNodesWithText(lastMessageAt(nodeStamp)).assertCountEquals(0)
    }

    @Test
    fun `offline, the node's update time is the stamp`() = runComposeUiTest {
        header(ChatUiState(peer = aisha, peerLastSeenMillis = nodeStamp))
        onNodeWithText(lastMessageAt(nodeStamp)).assertExists()
        onAllNodesWithText("Online").assertCountEquals(0)
    }

    @Test
    fun `before the node answers, the users list's last activity stands in`() = runComposeUiTest {
        header(ChatUiState(peer = aisha))
        onNodeWithText(lastMessageAt(listStamp)).assertExists()
    }

    @Test
    fun `with no stamp anywhere the line says who they are`() = runComposeUiTest {
        header(ChatUiState(peer = aisha.copy(lastActiveMillis = null)))
        onNodeWithText("Camera", substring = true).assertExists()
    }

    @Test
    fun `someone who left reads Name - Disconnected over their last-seen stamp`() = runComposeUiTest {
        header(ChatUiState(peer = aisha.copy(hasLeft = true), peerLastSeenMillis = nodeStamp))
        onNodeWithText("Aisha Khan").assertExists()
        onNodeWithText("Disconnected").assertExists()
        onNodeWithText(lastMessageAt(nodeStamp)).assertExists()
    }
}
