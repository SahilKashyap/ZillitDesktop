package com.zillit.desktop.feature.email

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.email.domain.MailboxExport
import com.zillit.desktop.feature.email.domain.MailboxExportPart
import com.zillit.desktop.feature.email.domain.MailboxExportStatus
import com.zillit.desktop.feature.email.ui.settings.MailboxExportEvent
import com.zillit.desktop.feature.email.ui.settings.MailboxExportPage
import com.zillit.desktop.feature.email.ui.settings.MailboxExportUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Export mailbox page on screen, in the three states that matter: being
 * built, ready with several ZIPs, and failed.
 *
 * Rendered rather than only reasoned about because the row this page is built
 * from — a weighted column beside a button — is the shape that has silently
 * crushed text elsewhere in this app.
 */
@OptIn(ExperimentalTestApi::class)
class MailboxExportRenderTest {

    @Test
    fun `a running export shows its count and asks before stopping`() = render(
        MailboxExportUiState(
            exports = listOf(
                export("e1", MailboxExportStatus.Processing, exported = 1200, total = 6087),
            ),
        ),
    ) { events ->
        onNodeWithText("1200 of 6087 emails").assertIsDisplayed()
        // Cancel asks first, inline — the stop itself is the answer to it.
        onNodeWithText("Cancel").assertIsDisplayed().performClick()
        waitForIdle()
        assertTrue(events.any { it is MailboxExportEvent.AskRemove }, "cancelling asks first")
    }

    @Test
    fun `the stop question stands in the running card`() = render(
        MailboxExportUiState(
            exports = listOf(export("e1", MailboxExportStatus.Processing, exported = 1200, total = 6087)),
            pendingRemoval = export("e1", MailboxExportStatus.Processing),
        ),
    ) { events ->
        onNodeWithText("Stop this export?").assertIsDisplayed()
        onNodeWithText("Keep running").assertIsDisplayed()
        onNodeWithText("Stop").assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals(listOf<MailboxExportEvent>(MailboxExportEvent.Cancel("e1")), events.toList())
    }

    @Test
    fun `a finished export offers one Download per part and a Remove`() = render(
        MailboxExportUiState(
            exports = listOf(
                export(
                    "e1",
                    MailboxExportStatus.Completed,
                    parts = listOf(
                        MailboxExportPart(1, "a.zip", sizeBytes = 1_012_334_592, messageCount = 4210),
                        MailboxExportPart(2, "b.zip", sizeBytes = 398_120_448, messageCount = 1877),
                    ),
                    expiresOn = 1_760_600_000_000,
                ),
            ),
        ),
    ) { events ->
        onNodeWithText("Part 1").assertIsDisplayed()
        onNodeWithText("Part 2").assertIsDisplayed()
        assertTrue(
            onAllNodesWithText("2 ZIP files", substring = true).fetchSemanticsNodes().isNotEmpty(),
            "the row says how many ZIPs there are",
        )
        assertTrue(
            onAllNodesWithText("Expires", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )

        onNodeWithContentDescription("Remove this export?").performClick()
        waitForIdle()
        assertTrue(events.any { it is MailboxExportEvent.AskRemove }, "removing a finished export asks first")
    }

    @Test
    fun `a failed export says why and offers Try Again`() = render(
        MailboxExportUiState(
            exports = listOf(export("e1", MailboxExportStatus.Failed, error = "email_export_mailbox_empty")),
        ),
    ) { events ->
        // The key has no dictionary entry in a test, so the humanised form of
        // it is what shows — the point is that the reason is on screen.
        assertTrue(
            onAllNodesWithText("mailbox empty", substring = true, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty(),
            "a failed export says what went wrong",
        )
        onNodeWithText("Try Again").assertIsDisplayed().performClick()
        waitForIdle()
        assertTrue(events.contains(MailboxExportEvent.Start))
    }

    @Test
    fun `the removal question names what it destroys`() = render(
        MailboxExportUiState(
            exports = listOf(export("e1", MailboxExportStatus.Completed)),
            pendingRemoval = export("e1", MailboxExportStatus.Completed),
        ),
    ) { events ->
        // Inline in the row, not a dialog over the sheet: a dialog composed
        // inside this scroller is clipped by it.
        onNodeWithText("Remove this export?").assertIsDisplayed()
        onNodeWithText("Keep").assertIsDisplayed()
        onNodeWithText("Remove").assertIsDisplayed().performClick()
        waitForIdle()
        assertTrue(events.contains(MailboxExportEvent.ConfirmRemove))
    }

    @Test
    fun `an empty mailbox says so under the heading`() = render(MailboxExportUiState()) { _ ->
        onNodeWithText("Exports".uppercase()).assertIsDisplayed()
        onNodeWithText("Kept for 7 days").assertIsDisplayed()
        onNodeWithText("No exports yet.").assertIsDisplayed()
    }

    // -- plumbing ------------------------------------------------------------

    private fun render(
        state: MailboxExportUiState,
        assertions: ComposeUiTest.(MutableList<MailboxExportEvent>) -> Unit,
    ) = runSkikoComposeUiTest(size = Size(WIDTH, HEIGHT)) {
        val events = mutableListOf<MailboxExportEvent>()
        setContent {
            ZillitTheme(darkTheme = false, animateThemeChange = false) {
                MailboxExportPage(state = state, onEvent = { events += it }, onBack = {})
            }
        }
        waitForIdle()
        assertions(events)
    }

    private fun export(
        id: String,
        status: MailboxExportStatus,
        exported: Int = 0,
        total: Int = 0,
        parts: List<MailboxExportPart> = emptyList(),
        error: String = "",
        expiresOn: Long = 0,
    ) = MailboxExport(
        exportId = id,
        status = status,
        mailboxEmail = "jane@prod.com",
        totalMessages = total,
        exportedMessages = exported,
        parts = parts,
        error = error,
        requestedOnMillis = 1_760_000_000_000,
        expiresOnMillis = expiresOn,
    )

    private companion object {
        const val WIDTH = 720f
        const val HEIGHT = 900f
    }
}
