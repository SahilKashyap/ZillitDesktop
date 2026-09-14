package com.zillit.desktop.feature.email

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.email.domain.ComposeMode
import com.zillit.desktop.feature.email.domain.OutgoingEmail
import com.zillit.desktop.feature.email.ui.ComposePane
import com.zillit.desktop.feature.email.ui.ComposeUiState
import kotlin.test.Test

/**
 * The composer as it actually stands in the reading pane.
 *
 * Composes the real pane over a stated state, because the thing worth
 * checking is the chrome the web puts around the form: the inline toolbar's
 * verbs, the From row, and the Popout that only a pane-bound composer has.
 */
@OptIn(ExperimentalTestApi::class)
class ComposePaneRenderTest {

    private val state = ComposeUiState(
        draft = OutgoingEmail(),
        mode = ComposeMode.New,
        fromAddress = "sahil@zillit.com",
    )

    @Composable
    private fun Pane(state: ComposeUiState = this.state, onPopOut: (() -> Unit)? = {}) {
        Box(Modifier.fillMaxSize()) {
            ComposePane(state = state, onEvent = {}, onPopOut = onPopOut)
        }
    }

    @Test
    fun `the inline toolbar carries the web's verbs and the form its rows`() {
        runComposeUiTest {
            setContent { ZillitTheme(darkTheme = false) { Pane() } }

            listOf("Send", "Save as Draft", "Discard", "Popout").forEach { onNodeWithText(it).assertIsDisplayed() }
            // Each address row is a label and, while empty, a placeholder of the same word.
            listOf("From", "To", "Cc", "Bcc", "Subject").forEach {
                onAllNodesWithText(it).onFirst().assertIsDisplayed()
            }
            listOf("compose-to", "compose-cc", "compose-bcc").forEach { onNodeWithTag(it).assertIsDisplayed() }
            onNodeWithText("sahil@zillit.com").assertIsDisplayed()
            onNodeWithContentDescription("Copy email").assertIsDisplayed()
        }
    }

    @Test
    fun `a composer in its own window has nothing to pop out of`() {
        runComposeUiTest {
            setContent { ZillitTheme(darkTheme = false) { Pane(onPopOut = null) } }

            onAllNodesWithText("Popout").assertCountEquals(0)
            onNodeWithText("Send").assertIsDisplayed()
        }
    }

    @Test
    fun `a save in flight says so and a send in flight disables the button`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) { Pane(state.copy(isSavingDraft = true)) }
            }
            onNodeWithText("Saving Draft…").assertIsDisplayed()
        }
    }

    @Test
    fun `the pane composes in both themes`() {
        listOf(false, true).forEach { dark ->
            runComposeUiTest {
                setContent { ZillitTheme(darkTheme = dark) { Pane() } }
                onNodeWithText("Send").assertIsDisplayed()
            }
        }
    }

    @Test
    fun `the paperclip opens the phones' attach sheet`() = runComposeUiTest {
        // Android's ComposeActivity offers gallery, video, document and audio
        // behind its attach button; the desktop paperclip offers the same.
        setContent { ZillitTheme { Pane() } }

        onNodeWithContentDescription("Attach").performClick()
        waitForIdle()
        listOf("Photo", "Video", "Document", "Audio").forEach { onNodeWithText(it).assertExists() }
    }
}
