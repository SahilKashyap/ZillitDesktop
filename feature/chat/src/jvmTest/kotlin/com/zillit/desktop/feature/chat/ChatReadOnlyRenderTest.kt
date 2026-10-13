package com.zillit.desktop.feature.chat

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.ui.ChatConversation
import com.zillit.desktop.feature.chat.ui.ChatUiState
import kotlin.test.Test
import kotlin.test.assertEquals

/** The live conversation run read-only: the lines, and none of what would act on them. */
@OptIn(ExperimentalTestApi::class)
class ChatReadOnlyRenderTest {

    private val aisha = CrewContact(userId = "u1", fullName = "Aisha Khan")
    private val now = System.currentTimeMillis()
    private val state = ChatUiState(
        peer = aisha,
        messages = listOf(
            ChatMessage("s1", "w1", "u1", "me", "Rolling at 8?", now - 60_000L, isMine = false),
            ChatMessage("s2", "w2", "me", "u1", "Copy.", now - 30_000L, isMine = true),
        ),
    )

    private fun count(test: androidx.compose.ui.test.ComposeUiTest, description: String) =
        test.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().size

    @Test
    fun `read-only shows the lines with no composer, menu chevron or call buttons`() = runComposeUiTest {
        setContent { ZillitTheme { ChatConversation(state = state, onEvent = {}, readOnly = true) } }
        waitForIdle()
        onNodeWithText("Rolling at 8?").assertExists()
        onNodeWithText("Copy.").assertExists()
        listOf("Options", "Attach a file").forEach { assertEquals(0, count(this, it), it) }
    }

    @Test
    fun `the same state live still draws the composer and the menu`() = runComposeUiTest {
        setContent { ZillitTheme { ChatConversation(state = state, onEvent = {}) } }
        waitForIdle()
        onNodeWithText("Rolling at 8?").assertExists()
        assertEquals(2, count(this, "Options"), "a chevron per bubble")
        assertEquals(1, count(this, "Attach a file"))
    }
}
