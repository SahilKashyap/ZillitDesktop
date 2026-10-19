package com.zillit.desktop.feature.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.ui.ChatEvent
import com.zillit.desktop.feature.chat.ui.ChatUiState
import com.zillit.desktop.feature.chat.ui.ThreadPane
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tagging a crew member, composed for real.
 *
 * [ChatTaggingTest] pins the ranking and the name-to-id step; this drives the
 * gesture end to end, because the half a unit test cannot reach is the half
 * that was missing: an `@` in the field has to actually raise a list, a key
 * press has to reach it rather than the thread, and the body that leaves on
 * Enter has to be the one with the id in it.
 *
 * The room is named so that nothing in the header collides with the name
 * being tagged — otherwise an assertion on "Aisha Khan" could pass on the
 * header alone and prove nothing.
 */
@OptIn(ExperimentalTestApi::class)
class ChatTaggingRenderTest {

    private val room = CrewContact(userId = "g1", fullName = "Unit 2 Camera")
    private val aisha = CrewContact(userId = "u1", fullName = "Aisha Khan")
    private val sunil = CrewContact(userId = "u3", fullName = "Sunil k Gautam")
    private val crew = listOf(aisha, sunil)

    /** The editable field itself: the placeholder renders as its own label node. */
    private fun androidx.compose.ui.test.ComposeUiTest.composerField() = onNode(hasSetTextAction())

    /**
     * The pane with a live draft: the field is state-driven, so a test that
     * does not feed DraftChanged back sees every keystroke discarded.
     */
    private class Harness {
        var state by mutableStateOf(ChatUiState(peer = null, peerIsGroup = true))
        val sent = mutableListOf<String?>()

        fun onEvent(event: ChatEvent) {
            when (event) {
                is ChatEvent.DraftChanged -> state = state.copy(draft = event.text)
                is ChatEvent.Send -> {
                    sent += event.body
                    state = state.copy(draft = "")
                }
                else -> Unit
            }
        }
    }

    private fun harness() = Harness().also {
        it.state = ChatUiState(peer = room, peerIsGroup = true)
    }

    @Test
    fun `typing an at raises the crew it could mean`() = runComposeUiTest {
        val app = harness()
        setContent { Pane(app, crew) }
        composerField().performTextInput("ask @ais")
        waitForIdle()

        onNodeWithText("Aisha Khan").assertIsDisplayed()
    }

    @Test
    fun `the list only offers who the token could mean`() = runComposeUiTest {
        val app = harness()
        setContent { Pane(app, crew) }
        composerField().performTextInput("ask @sun")
        waitForIdle()

        onNodeWithText("Sunil k Gautam").assertIsDisplayed()
        onNodeWithText("Aisha Khan").assertDoesNotExist()
    }

    @Test
    fun `enter completes the lit name into the draft instead of sending`() = runComposeUiTest {
        val app = harness()
        setContent { Pane(app, crew) }
        composerField().performTextInput("ask @ais")
        waitForIdle()
        composerField().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals("ask @Aisha Khan ", app.state.draft)
        assertTrue(app.sent.isEmpty(), "Enter belonged to the open list, not to sending")
    }

    @Test
    fun `clicking a row completes it too`() = runComposeUiTest {
        val app = harness()
        setContent { Pane(app, crew) }
        composerField().performTextInput("ask @ais")
        waitForIdle()
        onNodeWithText("Aisha Khan").performClick()
        waitForIdle()

        assertEquals("ask @Aisha Khan ", app.state.draft)
    }

    @Test
    fun `the body that leaves carries the id, not the name`() = runComposeUiTest {
        val app = harness()
        setContent { Pane(app, crew) }
        composerField().performTextInput("ask @ais")
        waitForIdle()
        // Completes the tag...
        composerField().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        // ...and with the list shut, Enter is sending again.
        composerField().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals(listOf<String?>("ask @{{u1}} "), app.sent)
    }

    @Test
    fun `escape gives enter back to sending, with the name left as typed`() = runComposeUiTest {
        val app = harness()
        setContent { Pane(app, crew) }
        composerField().performTextInput("ask @ais")
        waitForIdle()
        composerField().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        onNodeWithText("Aisha Khan").assertDoesNotExist()

        composerField().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        // `@ais` names nobody, so it travels as the words it is.
        assertEquals(listOf<String?>("ask @ais"), app.sent)
    }

    @Test
    fun `a line with no tag sends exactly what was typed`() = runComposeUiTest {
        val app = harness()
        setContent { Pane(app, crew) }
        composerField().performTextInput("rolling at 8")
        waitForIdle()
        composerField().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals(listOf<String?>("rolling at 8"), app.sent)
    }

    @androidx.compose.runtime.Composable
    private fun Pane(app: Harness, people: List<CrewContact>) {
        ZillitTheme {
            Box(Modifier.size(600.dp, 700.dp)) {
                ThreadPane(
                    state = app.state,
                    onEvent = app::onEvent,
                    forwardPeople = people,
                    resolveName = { id -> people.firstOrNull { it.userId == id }?.fullName },
                )
            }
        }
    }
}
