package com.zillit.desktop.feature.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The update strip and the blocking screen on the pages outside the shell —
 * the QR sign-in page and the production picker.
 *
 * They had neither: the strip lived inside [AppShell], so somebody who never
 * got past sign-in was told nothing, which is precisely the person whose build
 * is too old to authenticate.
 */
@OptIn(ExperimentalTestApi::class)
class UpdateSurfaceTest {

    private val pageText = "the page underneath"

    private fun ComposeUiTest.surface(notice: UpdateNotice?, onQuit: (() -> Unit)? = {}) {
        setContent {
            ZillitTheme(animateThemeChange = false) {
                UpdateSurface(notice = notice, onQuit = onQuit) {
                    Box(Modifier.fillMaxSize()) { ZillitText(pageText) }
                }
            }
        }
    }

    private fun notice(blocking: Boolean = false, mandatory: Boolean = blocking) = UpdateNotice(
        latestVersion = "1.2.0",
        mandatory = mandatory,
        downloadUrl = "https://zillit.example.com/download",
        installedVersion = "1.1.0",
        install = UpdateInstall.Offer,
        blocking = blocking,
    )

    @Test
    fun `with no update the page has the surface to itself`() = runComposeUiTest {
        surface(notice = null)

        onNodeWithText(pageText).assertIsDisplayed()
        onAllNodesWithTag(DISMISSIBLE_TAG).assertCountEquals(0)
        onAllNodesWithTag(FORCE_UPDATE_TAG).assertCountEquals(0)
    }

    /** The strip takes a band off the top; the page keeps the rest. */
    @Test
    fun `an optional update strips the page without hiding it`() = runComposeUiTest {
        surface(notice = notice())

        onNodeWithTag(DISMISSIBLE_TAG).assertIsDisplayed()
        onNodeWithText(pageText).assertIsDisplayed()
    }

    @Test
    fun `the strip can be dismissed, leaving the page alone`() = runComposeUiTest {
        surface(notice = notice())

        onNodeWithContentDescriptionOnce("Dismiss update notice").performClick()

        onAllNodesWithTag(DISMISSIBLE_TAG).assertCountEquals(0)
        onNodeWithText(pageText).assertIsDisplayed()
    }

    /**
     * Below the floor the server refuses the calls sign-in is made of, so the
     * blocking screen has to reach these pages too — otherwise the person just
     * watches sign-in fail with nothing explaining why.
     */
    @Test
    fun `a mandatory update covers the page and offers the way out`() = runComposeUiTest {
        var quits = 0
        surface(notice = notice(blocking = true), onQuit = { quits++ })

        onNodeWithTag(FORCE_UPDATE_TAG).assertIsDisplayed()
        onNodeWithText("Update required").assertIsDisplayed()
        // The strip is not drawn as well — the screen replaces it.
        onAllNodesWithTag(DISMISSIBLE_TAG).assertCountEquals(0)

        onNodeWithTag(FORCE_UPDATE_QUIT_TAG).performClick()
        assertEquals(1, quits)
    }

    /** A mandatory update is not dismissible here either. */
    @Test
    fun `the blocking screen has no dismiss control`() = runComposeUiTest {
        surface(notice = notice(blocking = true))

        onAllNodesWithContentDescription("Dismiss update notice").assertCountEquals(0)
    }

    private fun ComposeUiTest.onNodeWithContentDescriptionOnce(label: String) =
        onAllNodesWithContentDescription(label)[0]
}
