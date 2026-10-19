package com.zillit.desktop.feature.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.ui.ChatUiState
import com.zillit.desktop.feature.chat.ui.ThreadPane
import kotlin.test.Test

/**
 * Where the thread list stands after a message lands.
 *
 * [ThreadFollowTest] pins the decision; this composes it, because the half
 * that a unit test cannot reach is the one that was broken on screen: whether
 * the list actually ends up showing the line the rule chose. A thread taller
 * than its pane is the only arrangement in which any of this is observable,
 * hence the fixed 500x600 and the forty lines of history.
 */
@OptIn(ExperimentalTestApi::class)
class ThreadAutoScrollRenderTest {

    private val aisha = CrewContact(userId = "u1", fullName = "Aisha Khan")

    // An hour back, so the whole thread reads as "Today" and the forty lines
    // sit in one run under one day chip.
    private val base = System.currentTimeMillis() - 3_600_000L

    private fun line(n: Int, mine: Boolean = false) = ChatMessage(
        id = "srv-$n",
        uniqueId = "w-$n",
        senderId = if (mine) "me" else "u1",
        receiverId = if (mine) "u1" else "me",
        body = "line $n",
        timestampMillis = base + n * 1_000L,
        isMine = mine,
    )

    private fun thread(lines: Int) =
        ChatUiState(peer = aisha, messages = (1..lines).map { line(it) })

    @Test
    fun `a thread opens on its newest line`() = runComposeUiTest {
        val state = thread(FULL_SCREENS)
        setContent { Pane { state } }
        waitForIdle()

        onNodeWithText("line $FULL_SCREENS").assertIsDisplayed()
    }

    @Test
    fun `a thread that fills in after opening still lands on its newest line`() = runComposeUiTest {
        // The cold-start order: the pane is on screen, and its history only
        // arrives once the request comes back. The list is already laid out
        // by then, so nothing but the follow brings it down.
        var state by mutableStateOf(ChatUiState(peer = aisha, isLoading = true))
        setContent { Pane { state } }
        waitForIdle()

        state = thread(FULL_SCREENS)
        waitForIdle()

        onNodeWithText("line $FULL_SCREENS").assertIsDisplayed()
    }

    @Test
    fun `the first message in an empty conversation shows`() = runComposeUiTest {
        // An empty thread draws a placeholder instead of the list, so the
        // first message is the one send that has no laid-out list to scroll —
        // worth a test because asking an unmeasured list to move is exactly
        // the kind of thing that throws rather than misbehaves.
        var state by mutableStateOf(ChatUiState(peer = aisha))
        setContent { Pane { state } }
        waitForIdle()

        state = state.copy(messages = listOf(line(1, mine = true)))
        waitForIdle()

        onNodeWithText("line 1").assertIsDisplayed()
    }

    @Test
    fun `sending brings the thread down to what we just sent`() = runComposeUiTest {
        var state by mutableStateOf(thread(FULL_SCREENS))
        setContent { Pane { state } }
        waitForIdle()

        state = state.copy(messages = state.messages + line(FULL_SCREENS + 1, mine = true))
        waitForIdle()

        onNodeWithText("line ${FULL_SCREENS + 1}").assertIsDisplayed()
    }

    @Test
    fun `sending from far back in the history still brings the thread down`() = runComposeUiTest {
        // The bug this file was written for: a reply typed while reading back
        // through the day landed below the fold, because a send was gated on
        // already being at the bottom like any other arrival.
        var state by mutableStateOf(thread(FULL_SCREENS))
        setContent { Pane { state } }
        waitForIdle()
        onNode(hasScrollAction()).performScrollToNode(hasText("line $WAY_BACK"))
        waitForIdle()

        state = state.copy(messages = state.messages + line(FULL_SCREENS + 1, mine = true))
        waitForIdle()

        onNodeWithText("line ${FULL_SCREENS + 1}").assertIsDisplayed()
    }

    @Test
    fun `sending several in a row ends on the last of them`() = runComposeUiTest {
        var state by mutableStateOf(thread(FULL_SCREENS))
        setContent { Pane { state } }
        waitForIdle()

        // Three before any of them is acked — the view model appends each one
        // optimistically, so the list grows three times in quick succession.
        repeat(3) { step ->
            state = state.copy(messages = state.messages + line(FULL_SCREENS + 1 + step, mine = true))
            waitForIdle()
        }

        onNodeWithText("line ${FULL_SCREENS + 3}").assertIsDisplayed()
    }

    @Test
    fun `someone else's message arrives into view for a reader at the bottom`() = runComposeUiTest {
        var state by mutableStateOf(thread(FULL_SCREENS))
        setContent { Pane { state } }
        waitForIdle()

        state = state.copy(messages = state.messages + line(FULL_SCREENS + 1))
        waitForIdle()

        onNodeWithText("line ${FULL_SCREENS + 1}").assertIsDisplayed()
    }

    @Test
    fun `someone else's message leaves a reader scrolled back where they were`() = runComposeUiTest {
        var state by mutableStateOf(thread(FULL_SCREENS))
        setContent { Pane { state } }
        waitForIdle()
        onNode(hasScrollAction()).performScrollToNode(hasText("line $WAY_BACK"))
        waitForIdle()

        state = state.copy(messages = state.messages + line(FULL_SCREENS + 1))
        waitForIdle()

        onNodeWithText("line $WAY_BACK").assertIsDisplayed()
    }

    /** The pane at a size a forty-line thread cannot fit into. */
    @androidx.compose.runtime.Composable
    private fun Pane(state: () -> ChatUiState) {
        ZillitTheme {
            Box(Modifier.size(500.dp, 600.dp)) {
                ThreadPane(state = state(), onEvent = {})
            }
        }
    }

    private companion object {
        /** Comfortably more history than the pane can show at once. */
        const val FULL_SCREENS = 40

        /** Far enough up that no slack in the "at the bottom" gate reaches it. */
        const val WAY_BACK = 8
    }
}
