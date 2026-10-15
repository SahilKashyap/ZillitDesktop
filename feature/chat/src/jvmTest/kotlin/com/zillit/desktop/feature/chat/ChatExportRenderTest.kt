package com.zillit.desktop.feature.chat

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.chat.domain.ChatExport
import com.zillit.desktop.feature.chat.domain.ChatExportStatus
import com.zillit.desktop.feature.chat.ui.export.ChatExportDialog
import com.zillit.desktop.feature.chat.ui.export.ChatExportEvent
import com.zillit.desktop.feature.chat.ui.export.ChatExportUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Export chats sheet on screen, in the states that matter: nothing
 * exported yet, one being built, one ready, and one that failed.
 *
 * Rendered rather than only reasoned about because the row each export is
 * drawn as — a weighted column beside its buttons — is the shape that has
 * silently crushed text elsewhere in this app.
 */
@OptIn(ExperimentalTestApi::class)
class ChatExportRenderTest {

    @Test
    fun `with nothing exported the sheet still says what an export is`() = render(ChatExportUiState()) { events ->
        onNodeWithText("No exports yet.").assertIsDisplayed()
        onNodeWithText("Include photos, videos and files").assertIsDisplayed()
        assertTrue(
            onAllNodesWithText("Covers this project only", substring = true).fetchSemanticsNodes().isNotEmpty(),
            "an export is one production's chats, and the card says so before it starts",
        )

        // The header carries the same words, so the button is the later node.
        onAllNodesWithText("Export chats").onLast().performClick()
        waitForIdle()
        assertEquals(listOf<ChatExportEvent>(ChatExportEvent.Start), events.toList())
    }

    @Test
    fun `a running export shows its count and offers Cancel`() = render(
        ChatExportUiState(
            exports = listOf(
                export("e1", ChatExportStatus.Processing)
                    .copy(exportedConversations = 17, totalConversations = 42),
            ),
        ),
    ) { events ->
        assertTrue(
            onAllNodesWithText("17 of 42 conversations", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
        // The choice card is replaced by the running one, so the box that
        // belongs to the *next* export is not on screen at all.
        assertTrue(
            onAllNodesWithText("Include photos, videos and files").fetchSemanticsNodes().isEmpty(),
        )

        onNodeWithText("Cancel").assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals(listOf<ChatExportEvent>(ChatExportEvent.Cancel("e1")), events.toList())
    }

    @Test
    fun `an export the server has not counted yet reads as working, not stuck`() = render(
        ChatExportUiState(exports = listOf(export("e1", ChatExportStatus.Queued))),
    ) { _ ->
        onNodeWithText("Preparing your export…").assertIsDisplayed()
        assertTrue(
            onAllNodesWithText("come back to it", substring = true).fetchSemanticsNodes().isNotEmpty(),
            "a bar pinned at zero would read as a stall",
        )
    }

    @Test
    fun `a finished export shows its size and expiry, and offers Download`() = render(
        ChatExportUiState(
            exports = listOf(
                export("e1", ChatExportStatus.Completed)
                    .copy(sizeBytes = 48_213_377, expiresOnMillis = 1_761_000_000_000),
            ),
        ),
    ) { events ->
        assertTrue(
            onAllNodesWithText("45 MB", substring = true).fetchSemanticsNodes().isNotEmpty(),
            "the size decides whether the click is a good idea",
        )
        assertTrue(onAllNodesWithText("Available until", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(
            onAllNodesWithText("Kept for 7 days", substring = true).fetchSemanticsNodes().isNotEmpty(),
            "how long it lasts is said once, over the list",
        )
        assertTrue(
            onAllNodesWithText("index.html", substring = true).fetchSemanticsNodes().isNotEmpty(),
            "the ZIP is useless without that one instruction",
        )

        onNodeWithText("Download").assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals(listOf<ChatExportEvent>(ChatExportEvent.Download("e1")), events.toList())
    }

    @Test
    fun `a failed export says why and offers another go`() = render(
        ChatExportUiState(
            exports = listOf(
                export("e1", ChatExportStatus.Failed).copy(error = "cnc_chat_export_no_chats"),
            ),
        ),
    ) { events ->
        onNodeWithText("Export failed").assertIsDisplayed()
        // The key humanises when the server dictionary is not loaded, which
        // is what a test and a cold start both see.
        assertTrue(onAllNodesWithText("Chats", substring = true).fetchSemanticsNodes().isNotEmpty())

        onNodeWithText("Export again").performClick()
        waitForIdle()
        assertEquals(listOf<ChatExportEvent>(ChatExportEvent.Start), events.toList())
    }

    @Test
    fun `a text-only export says so on its own row`() = render(
        ChatExportUiState(
            exports = listOf(
                export("e1", ChatExportStatus.Completed).copy(includeMedia = false, sizeBytes = 48_213_377),
                export("e0", ChatExportStatus.Completed).copy(sizeBytes = 48_213_377),
            ),
        ),
    ) { _ ->
        // Both rows carry the same size, so the words are what tell them apart.
        assertTrue(
            onAllNodesWithText("text only", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
        assertEquals(2, onAllNodesWithText("Download").fetchSemanticsNodes().size, "both are downloadable")
    }

    @Test
    fun `removing a finished export asks before it deletes anything`() = render(
        ChatExportUiState(
            exports = listOf(export("e1", ChatExportStatus.Completed)),
            pendingRemoval = export("e1", ChatExportStatus.Completed),
        ),
    ) { events ->
        // Inline in the row: a dialog composed inside this sheet's scroller
        // would be clipped by it.
        onNodeWithText("Remove this export?").assertIsDisplayed()
        onNodeWithText("Keep").assertIsDisplayed()
        onNodeWithText("Delete").assertIsDisplayed().performClick()
        waitForIdle()
        assertTrue(events.contains(ChatExportEvent.ConfirmRemove))
    }

    // -- plumbing ------------------------------------------------------------

    private fun render(
        state: ChatExportUiState,
        assertions: ComposeUiTest.(MutableList<ChatExportEvent>) -> Unit,
    ) = runSkikoComposeUiTest(size = Size(WIDTH, HEIGHT)) {
        val events = mutableListOf<ChatExportEvent>()
        setContent {
            ZillitTheme(darkTheme = false, animateThemeChange = false) {
                ChatExportDialog(state = state, onEvent = { events += it }, visible = true, onDismiss = {})
            }
        }
        waitForIdle()
        assertions(events)
    }

    /** The two fields every row needs; the rest are `copy`ed in per case. */
    private fun export(id: String, status: ChatExportStatus) = ChatExport(
        exportId = id,
        status = status,
        requestedOnMillis = 1_791_975_262_243,
    )

    private companion object {
        const val WIDTH = 760f
        const val HEIGHT = 1000f
    }
}
