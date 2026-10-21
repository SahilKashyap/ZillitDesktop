package com.zillit.desktop.feature.email

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.email.domain.EmailDraft
import com.zillit.desktop.feature.email.domain.EmailFolder
import com.zillit.desktop.feature.email.ui.DRAFT_COUNT_TAG
import com.zillit.desktop.feature.email.ui.EmailScreen
import com.zillit.desktop.feature.email.ui.EmailUiState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How many drafts are waiting, on the sidebar's Drafts row.
 *
 * Asserted against a real render because the number shares the row with the
 * unread pill and the folder's hover menu: it has to be there, and it has to
 * be the drafts rather than the mail server's unread count for a folder this
 * client does not keep its drafts in.
 */
@OptIn(ExperimentalTestApi::class)
class DraftCountRenderTest {

    private val folders = listOf(
        EmailFolder(name = EmailFolder.INBOX, isSystem = true, unreadCount = 5),
        EmailFolder(name = EmailFolder.DRAFTS, isSystem = true, unreadCount = 9),
    )

    @Test
    fun `the Drafts row carries how many there are`() = runSkikoComposeUiTest(size = Size(WIDTH, HEIGHT)) {
        val state = EmailUiState(
            folders = folders,
            selectedFolderName = EmailFolder.INBOX,
            drafts = listOf(EmailDraft(id = "d1"), EmailDraft(id = "d2"), EmailDraft(id = "d3")),
        )
        setContent {
            ZillitTheme(darkTheme = false, animateThemeChange = false) {
                EmailScreen(state = state, onEvent = {})
            }
        }

        // Unmerged: the folder row is clickable, so it merges its children's
        // semantics into one node.
        onNodeWithTag(DRAFT_COUNT_TAG, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag(DRAFT_COUNT_TAG, useUnmergedTree = true).assertTextEquals("3")
    }

    @Test
    fun `an empty Drafts folder shows no number, and never the server's unread`() =
        runSkikoComposeUiTest(size = Size(WIDTH, HEIGHT)) {
            // The IMAP Drafts folder reports 9 unread here. It is not where this
            // client's drafts live, so that number means nothing to the user.
            val state = EmailUiState(folders = folders, selectedFolderName = EmailFolder.INBOX)
            setContent {
                ZillitTheme(darkTheme = false, animateThemeChange = false) {
                    EmailScreen(state = state, onEvent = {})
                }
            }

            assertEquals(
                0,
                onAllNodesWithTag(DRAFT_COUNT_TAG, useUnmergedTree = true).fetchSemanticsNodes().size,
            )
        }

    private companion object {
        const val WIDTH = 1280f
        const val HEIGHT = 800f
    }
}
