package com.zillit.desktop.feature.email

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.email.domain.ComposeMode
import com.zillit.desktop.feature.email.ui.Composing
import com.zillit.desktop.feature.email.ui.ComposerDock
import com.zillit.desktop.feature.email.ui.ComposerWindow
import com.zillit.desktop.feature.email.ui.OpenComposer
import kotlin.test.Test

/**
 * The composers as they actually stand on the mailbox.
 *
 * Composes the real dock over the real form, because the thing worth checking
 * is the chrome around it: a minimised composer that renders its whole form is
 * not minimised, and a card with no close button is a trap.
 */
@OptIn(ExperimentalTestApi::class)
class ComposerDockRenderTest {

    /** The same in-memory server the composer's other tests use. */
    private val server = FakeMailServer()

    private val deps = Composing(
        repository = server,
        drafts = server,
        contacts = server,
        signatures = server,
    )

    private fun composer(
        id: String = "composer-0",
        window: ComposerWindow = ComposerWindow.Docked,
    ) = OpenComposer(id = id, mode = ComposeMode.New, window = window)

    @Composable
    private fun Dock(composers: List<OpenComposer>) {
        Box(Modifier.fillMaxSize()) {
            ComposerDock(
                composers = composers,
                deps = deps,
                messageById = { null },
                draftById = { null },
                onWindow = { _, _ -> },
                onClose = {},
                onOpenSignatures = {},
            )
        }
    }

    @Test
    fun `a docked composer shows its title bar and its form`() {
        runComposeUiTest {
            setContent { ZillitTheme(darkTheme = false) { Dock(listOf(composer())) } }

            onNodeWithText("New message").assertIsDisplayed()
            onNodeWithText("To").assertIsDisplayed()
            onNodeWithText("Subject").assertIsDisplayed()
            onNodeWithText("Send").assertIsDisplayed()
        }
    }

    @Test
    fun `every window control is reachable`() {
        // A composer you cannot close is a trap, and one you cannot minimise
        // is a window by another name.
        runComposeUiTest {
            setContent { ZillitTheme(darkTheme = false) { Dock(listOf(composer())) } }

            onNodeWithContentDescription("Minimise New message").assertIsDisplayed()
            onNodeWithContentDescription("Fill the window with New message").assertIsDisplayed()
            onNodeWithContentDescription("Close New message").assertIsDisplayed()
        }
    }

    @Test
    fun `a minimised composer keeps its bar and drops its form`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    Dock(listOf(composer(window = ComposerWindow.Minimised)))
                }
            }

            onNodeWithText("New message").assertIsDisplayed()
            // The form is gone — that is what minimised means.
            onAllNodesWithText("Subject").assertCountEquals(0)
            onAllNodesWithText("Send").assertCountEquals(0)
            // Still closeable without restoring it first.
            onNodeWithContentDescription("Close New message").assertIsDisplayed()
        }
    }

    @Test
    fun `an expanded composer offers its way back to the corner`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    Dock(listOf(composer(window = ComposerWindow.Expanded)))
                }
            }

            onNodeWithText("Subject").assertIsDisplayed()
            onNodeWithContentDescription("Return New message to the corner").assertIsDisplayed()
        }
    }

    @Test
    fun `several composers stand side by side`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    Dock(listOf(composer("composer-0"), composer("composer-1")))
                }
            }

            // Two of everything: two cards, each with its own title and form.
            onAllNodesWithText("New message").assertCountEquals(2)
            onAllNodesWithText("Send").assertCountEquals(2)
        }
    }

    @Test
    fun `an empty deck draws nothing`() {
        runComposeUiTest {
            setContent { ZillitTheme(darkTheme = false) { Dock(emptyList()) } }
            onAllNodesWithText("New message").assertCountEquals(0)
        }
    }

    @Test
    fun `the dock composes in both themes`() {
        listOf(false, true).forEach { dark ->
            runComposeUiTest {
                setContent { ZillitTheme(darkTheme = dark) { Dock(listOf(composer())) } }
                onNodeWithText("New message").assertIsDisplayed()
            }
        }
    }

    @Test
    fun `the paperclip opens the phones' attach sheet`() = runComposeUiTest {
        // Android's ComposeActivity offers gallery, video, document and audio
        // behind its attach button; the desktop paperclip offers the same.
        val composers = listOf(composer())
        setContent {
            ZillitTheme {
                Box(Modifier.fillMaxSize()) {
                    ComposerDock(
                composers = composers,
                deps = deps,
                messageById = { null },
                draftById = { null },
                onWindow = { _, _ -> },
                onClose = {},
                onOpenSignatures = {},
            )
                }
            }
        }

        onNodeWithContentDescription("Attach a file").performClick()
        waitForIdle()
        listOf("Photo", "Video", "Document", "Audio").forEach { onNodeWithText(it).assertExists() }
    }
}
