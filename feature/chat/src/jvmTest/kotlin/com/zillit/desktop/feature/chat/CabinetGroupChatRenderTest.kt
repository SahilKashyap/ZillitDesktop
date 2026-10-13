package com.zillit.desktop.feature.chat

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.cabinet.CabinetEntry
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.chat.cabinet.GroupChatCabinetViewer
import com.zillit.desktop.feature.chat.data.ChatRepository
import com.zillit.desktop.feature.chat.domain.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Group Chat cabinet viewer composed for real: read-only bubbles, paging, and the empty and failed states. */
@OptIn(ExperimentalTestApi::class)
class CabinetGroupChatRenderTest {

    private val now = System.currentTimeMillis()
    private val room = CabinetEntry("room1", "Camera")

    private fun line(n: Int, mine: Boolean, body: String) = ChatMessage(
        id = "m$n", uniqueId = "m$n", senderId = if (mine) "me" else "u1", receiverId = "room1",
        body = body, timestampMillis = now - (10 - n) * 60_000L, isMine = mine, isGroup = true,
    )

    private class Archive(
        var answer: (Long) -> ZillitResult<List<ChatMessage>>,
    ) : ChatRepository by FakeChatRepository() {
        val asked = mutableListOf<Long>()
        override suspend fun cabinetHistory(roomId: String, beforeMillis: Long): ZillitResult<List<ChatMessage>> {
            asked += beforeMillis
            return answer(beforeMillis)
        }
    }

    private fun render(
        archive: Archive,
        entry: CabinetEntry? = room,
        assert: androidx.compose.ui.test.ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        val viewer = GroupChatCabinetViewer(archive, clock = { now })
        setContent { ZillitTheme { viewer.Content(entry, Modifier) } }
        waitForIdle()
        assert()
    }

    @Test
    fun `archived lines read with their sender and no way to act on them`() {
        val archive = Archive {
            ZillitResult.Success(
                listOf(line(1, mine = false, body = "Rolling at 8?"), line(2, mine = true, body = "Copy.")),
            )
        }
        render(archive) {
            onNodeWithText("Rolling at 8?").assertExists()
            onNodeWithText("Copy.").assertExists()
            // No chevron, reaction picker, composer or tick: the bubble alone.
            assertEquals(0, onAllNodesWithContentDescriptionCount("Options"))
            assertEquals(0, onAllNodesWithContentDescriptionCount("Attach a file"))
            assertEquals(listOf(now), archive.asked)
        }
    }

    @Test
    fun `an older page is one click away and joins the list`() {
        val archive = Archive { before ->
            ZillitResult.Success(
                if (before == now) {
                    listOf(line(5, false, "Newest"))
                } else {
                    listOf(line(1, false, "Much earlier"))
                },
            )
        }
        render(archive) {
            onNodeWithText("Show older").performClick()
            waitForIdle()
            onNodeWithText("Much earlier").assertExists()
            onNodeWithText("Newest").assertExists()
        }
    }

    @Test
    fun `an empty archive says so`() {
        render(Archive { ZillitResult.Success(emptyList()) }) {
            onNodeWithText("No messages to show.").assertExists()
        }
    }

    @Test
    fun `a refused archive offers a retry that asks again`() {
        var failing = true
        val archive = Archive {
            if (failing) {
                ZillitResult.Failure(ZillitError.Unknown("down"))
            } else {
                ZillitResult.Success(listOf(line(1, false, "Back")))
            }
        }
        render(archive) {
            onNodeWithText("Retry").assertExists()
            failing = false
            onNodeWithText("Retry").performClick()
            waitForIdle()
            onNodeWithText("Back").assertExists()
            assertTrue(archive.asked.size == 2)
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithContentDescriptionCount(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasContentDescription(text)).fetchSemanticsNodes().size
}
