package com.zillit.desktop.feature.email

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.email.domain.EmailFolder
import com.zillit.desktop.feature.email.domain.EmailSummary
import com.zillit.desktop.feature.email.ui.EmailScreen
import com.zillit.desktop.feature.email.ui.EmailUiState
import com.zillit.desktop.feature.email.ui.LIST_TAG
import kotlin.test.Test

/**
 * The listing after a workspace tab switch.
 *
 * The workspace composes only the tab in front and keeps the others'
 * `rememberSaveable` state, so the saved list position comes back with the
 * mailbox — and the list's newest-row follower, whose anchor was a plain
 * `remember`, then took the return for a first fill and scrolled to the top.
 * Composed under a [rememberSaveableStateHolder] the way `WorkspaceHost` does.
 */
@OptIn(ExperimentalTestApi::class)
class MailListReturnRenderTest {

    private val state = EmailUiState(
        folders = listOf(EmailFolder(name = EmailFolder.INBOX, isSystem = true)),
        selectedFolderName = EmailFolder.INBOX,
        messages = (1..ROWS).map { n ->
            EmailSummary(
                id = "m$n",
                threadId = "t$n",
                subject = "Mail $n",
                from = "Crew <crew@zillit.com>",
                receivedAtMillis = n.toLong(),
                uid = n,
                folderName = EmailFolder.INBOX,
            )
        },
    ).regrouped()

    @Test
    fun `coming back from another tab leaves the list where the reader left it`() =
        runSkikoComposeUiTest(size = Size(WIDTH, HEIGHT)) {
            val inFront = mutableStateOf(true)
            setContent {
                ZillitTheme(darkTheme = false, animateThemeChange = false) {
                    val windows = rememberSaveableStateHolder()
                    if (inFront.value) {
                        windows.SaveableStateProvider("email-window") { EmailScreen(state = state, onEvent = {}) }
                    }
                }
            }
            // Newest first: row 40 is m20, well below the fold of m60.
            onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag(LIST_TAG))).performScrollToIndex(40)
            waitForIdle()
            onNodeWithTag("email-row-m20").assertIsDisplayed()
            assertOffScreen("email-row-m60")

            // Over to another tab, and back.
            inFront.value = false
            waitForIdle()
            inFront.value = true
            waitForIdle()

            onNodeWithTag("email-row-m20").assertIsDisplayed()
            assertOffScreen("email-row-m60")
        }
}

/**
 * Not on screen: composed out of view, or — a lazy list's usual answer for a
 * row forty places away — not composed at all. `assertIsNotDisplayed` alone
 * throws on the second, which is the state this test most expects.
 */
private fun SemanticsNodeInteractionsProvider.assertOffScreen(tag: String) {
    if (onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) onNodeWithTag(tag).assertIsNotDisplayed()
}

private const val ROWS = 60
private const val WIDTH = 1200f
private const val HEIGHT = 800f
