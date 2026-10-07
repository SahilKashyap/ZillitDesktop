package com.zillit.desktop.feature.email

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.email.domain.EmailFolder
import com.zillit.desktop.feature.email.ui.CALENDAR_VIEW_TAG
import com.zillit.desktop.feature.email.ui.EmailScreen
import com.zillit.desktop.feature.email.ui.EmailUiState
import com.zillit.desktop.feature.email.ui.LIST_PANE_TAG
import com.zillit.desktop.feature.email.ui.MailNavigation
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The nav strip, as the web's `EmailPage` behaves: Calendar swaps the pane in
 * place, Contacts is a modal over the mailbox — neither opens a window.
 */
@OptIn(ExperimentalTestApi::class)
class MailNavViewsRenderTest {

    private val state = EmailUiState(
        folders = listOf(EmailFolder(name = EmailFolder.INBOX, isSystem = true)),
        selectedFolderName = EmailFolder.INBOX,
    )

    @Test
    fun `calendar replaces the mailbox in place and email brings it back`() =
        runSkikoComposeUiTest(size = Size(WIDTH, HEIGHT)) {
            setContent {
                ZillitTheme(darkTheme = false, animateThemeChange = false) {
                    EmailScreen(
                        state = state,
                        onEvent = {},
                        navigation = MailNavigation(calendar = { Box { Text("production calendar") } }),
                    )
                }
            }
            onNodeWithTag(LIST_PANE_TAG).assertIsDisplayed()
            assertEquals(0, onAllNodesWithTag(CALENDAR_VIEW_TAG).fetchSemanticsNodes().size)

            onNodeWithTag("email-nav-calendar").performClick()
            waitForIdle()
            onNodeWithTag(CALENDAR_VIEW_TAG).assertIsDisplayed()
            assertEquals(0, onAllNodesWithTag(LIST_PANE_TAG).fetchSemanticsNodes().size)

            onNodeWithTag("email-nav-email").performClick()
            waitForIdle()
            onNodeWithTag(LIST_PANE_TAG).assertIsDisplayed()
            assertEquals(0, onAllNodesWithTag(CALENDAR_VIEW_TAG).fetchSemanticsNodes().size)
        }

    @Test
    fun `contacts asks for its modal and leaves the mailbox where it was`() =
        runSkikoComposeUiTest(size = Size(WIDTH, HEIGHT)) {
            var opened = 0
            setContent {
                ZillitTheme(darkTheme = false, animateThemeChange = false) {
                    EmailScreen(
                        state = state,
                        onEvent = {},
                        navigation = MailNavigation(
                            calendar = { Box { Text("production calendar") } },
                            onOpenContacts = { opened++ },
                        ),
                    )
                }
            }
            onNodeWithTag("email-nav-contacts").performClick()
            waitForIdle()
            assertEquals(1, opened)
            onNodeWithTag(LIST_PANE_TAG).assertIsDisplayed()
        }

    @Test
    fun `a host with no calendar draws no calendar button`() =
        runSkikoComposeUiTest(size = Size(WIDTH, HEIGHT)) {
            setContent {
                ZillitTheme(darkTheme = false, animateThemeChange = false) {
                    EmailScreen(state = state, onEvent = {})
                }
            }
            assertEquals(0, onAllNodesWithTag("email-nav-calendar").fetchSemanticsNodes().size)
        }

    private companion object {
        const val WIDTH = 1280f
        const val HEIGHT = 800f
    }
}
