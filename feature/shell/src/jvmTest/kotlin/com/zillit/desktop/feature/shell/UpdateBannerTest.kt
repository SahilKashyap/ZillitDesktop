package com.zillit.desktop.feature.shell

import androidx.compose.runtime.remember
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ThemeMode
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.workspace.InMemoryWorkspaceSessionStore
import com.zillit.desktop.core.workspace.ToolRegistry
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.core.workspace.WorkspaceViewModel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The update strip, composed inside the real frame.
 *
 * The two cases differ in one structural way that no screenshot would catch and
 * that matters more than anything visual: the mandatory banner has **no dismiss
 * control at all**. Rendering one and hiding it, or rendering one that does
 * nothing, would both look identical in a picture and both be wrong.
 */
@OptIn(ExperimentalTestApi::class)
class UpdateBannerTest {

    private val railItems = listOf(
        RailItem("home", "Home", ZillitIcons.Home, WorkspaceRoute.Home),
    )

    @Test
    fun `no notice means no banner`() = runComposeUiTest {
        setShell(notice = null)

        onAllNodesWithTag(DISMISSIBLE_TAG).assertCountEquals(0)
        onAllNodesWithTag(BLOCKING_TAG).assertCountEquals(0)
    }

    @Test
    fun `an optional notice names the version and offers a download`() = runComposeUiTest {
        setShell(notice = UpdateNotice("1.2.0", mandatory = false, downloadUrl = DOWNLOAD_URL))

        onNodeWithTag(DISMISSIBLE_TAG).assertIsDisplayed()
        onNodeWithText("Version 1.2.0 is available.").assertIsDisplayed()
        onNodeWithText("Download").assertIsDisplayed()
    }

    /** The two numbers side by side are the whole message. */
    @Test
    fun `the strip names the installed version next to the new one`() = runComposeUiTest {
        setShell(
            notice = UpdateNotice("1.2.0", mandatory = false, downloadUrl = DOWNLOAD_URL, installedVersion = "1.1.0"),
        )

        onNodeWithText("Version 1.2.0 is available. You have 1.1.0.").assertIsDisplayed()
    }

    @Test
    fun `Download hands the URL to the launcher`() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setShell(
            notice = UpdateNotice("1.2.0", mandatory = false, downloadUrl = DOWNLOAD_URL),
            onDownload = { opened += it },
        )

        onNodeWithText("Download").performClick()

        assertEquals(listOf(DOWNLOAD_URL), opened)
    }

    @Test
    fun `the optional banner can be dismissed`() = runComposeUiTest {
        setShell(notice = UpdateNotice("1.2.0", mandatory = false, downloadUrl = DOWNLOAD_URL))

        onNodeWithContentDescription("Dismiss update notice").performClick()

        onAllNodesWithTag(DISMISSIBLE_TAG).assertCountEquals(0)
        // The rest of the frame is untouched — the strip took a band of height
        // and gave it back.
        onNodeWithText("Zillit").assertIsDisplayed()
    }

    /**
     * The one that must not regress.
     *
     * A mandatory notice has no X. Not a disabled one, not a hidden one — none,
     * because there is no "later" for a build the server will stop serving.
     */
    @Test
    fun `a mandatory notice is not dismissible`() = runComposeUiTest {
        setShell(notice = UpdateNotice("2.0.0", mandatory = true, downloadUrl = DOWNLOAD_URL))

        onNodeWithTag(BLOCKING_TAG).assertIsDisplayed()
        onAllNodesWithContentDescription("Dismiss update notice").assertCountEquals(0)
    }

    @Test
    fun `a mandatory notice says plainly that the app must be updated`() = runComposeUiTest {
        setShell(notice = UpdateNotice("2.0.0", mandatory = true, downloadUrl = DOWNLOAD_URL))

        onNodeWithText("Zillit must be updated to continue. Version 2.0.0 is required.").assertIsDisplayed()
        onNodeWithText("Download").assertIsDisplayed()
    }

    /**
     * Nothing is blocked in the optional case: the workspace behind the strip
     * still opens windows.
     */
    @Test
    fun `the optional banner does not block the app`() = runComposeUiTest {
        setShell(notice = UpdateNotice("1.2.0", mandatory = false, downloadUrl = DOWNLOAD_URL))

        onNodeWithContentDescription("Home").performClick()

        onNodeWithText("1 open").assertIsDisplayed()
    }

    /** No usable https URL anywhere: the version is still worth saying. */
    @Test
    fun `a notice with no URL renders without a button`() = runComposeUiTest {
        setShell(notice = UpdateNotice("1.2.0", mandatory = false, downloadUrl = null))

        onNodeWithText("Version 1.2.0 is available.").assertIsDisplayed()
        onAllNodesWithText("Download").assertCountEquals(0)
    }

    /** Both palettes come from theme tokens; this proves the dark one composes. */
    @Test
    fun `the banner renders in dark theme`() = runComposeUiTest {
        setShell(
            notice = UpdateNotice("1.2.0", mandatory = false, downloadUrl = DOWNLOAD_URL),
            mode = ThemeMode.Dark,
        )

        onNodeWithTag(DISMISSIBLE_TAG).assertIsDisplayed()
        onNodeWithText("Version 1.2.0 is available.").assertIsDisplayed()
    }

    // --- In-app install -----------------------------------------------------

    /** Where the app can install, it does not send the reader to a page. */
    @Test
    fun `an installable notice offers Update now and no download link`() = runComposeUiTest {
        var installs = 0
        setShell(
            notice = UpdateNotice("1.2.0", false, DOWNLOAD_URL, install = UpdateInstall.Offer),
            onInstall = { installs++ },
        )

        onAllNodesWithText("Download").assertCountEquals(0)
        onNodeWithTag(INSTALL_TAG).performClick()

        assertEquals(1, installs)
    }

    @Test
    fun `downloading shows the percentage and no buttons`() = runComposeUiTest {
        setShell(notice = UpdateNotice("1.2.0", false, DOWNLOAD_URL, install = UpdateInstall.Downloading(42)))

        onNodeWithText("Downloading version 1.2.0… 42%").assertIsDisplayed()
        onAllNodesWithTag(INSTALL_TAG).assertCountEquals(0)
        onAllNodesWithTag(RESTART_TAG).assertCountEquals(0)
    }

    @Test
    fun `a staged build offers the restart`() = runComposeUiTest {
        var restarts = 0
        setShell(
            notice = UpdateNotice("1.2.0", true, DOWNLOAD_URL, install = UpdateInstall.Ready),
            onRestart = { restarts++ },
        )

        onNodeWithTag(RESTART_TAG).performClick()

        assertEquals(1, restarts)
    }

    /** A file that failed verification would fail again: no retry, the page instead. */
    @Test
    fun `a failed verification falls back to the download page`() = runComposeUiTest {
        setShell(
            notice = UpdateNotice(
                "1.2.0", false, DOWNLOAD_URL,
                install = UpdateInstall.Failed(retryable = false, verification = true),
            ),
        )

        onNodeWithText("Download").assertIsDisplayed()
        onAllNodesWithText("Try Again").assertCountEquals(0)
    }

    @Test
    fun `a broken download can be retried`() = runComposeUiTest {
        var installs = 0
        setShell(
            notice = UpdateNotice(
                "1.2.0", false, DOWNLOAD_URL,
                install = UpdateInstall.Failed(retryable = true, verification = false),
            ),
            onInstall = { installs++ },
        )

        onNodeWithText("Try Again").performClick()

        assertEquals(1, installs)
    }

    private fun ComposeUiTest.setShell(
        notice: UpdateNotice?,
        onDownload: (String) -> Unit = {},
        mode: ThemeMode = ThemeMode.System,
        onInstall: () -> Unit = {},
        onRestart: () -> Unit = {},
    ) {
        setContent {
            val registry = remember { ToolRegistry(placeholderTools()) }
            val viewModel = remember {
                var counter = 0
                WorkspaceViewModel(
                    registry = registry,
                    sessionStore = InMemoryWorkspaceSessionStore(),
                    idGenerator = { "w${counter++}" },
                )
            }
            ZillitTheme(darkTheme = mode == ThemeMode.Dark, animateThemeChange = false) {
                AppShell(
                    viewModel = viewModel,
                    registry = registry,
                    themeMode = mode,
                    onThemeModeChange = {},
                    railItems = railItems,
                    updateNotice = notice,
                    onDownloadUpdate = onDownload,
                    onInstallUpdate = onInstall,
                    onRestartToUpdate = onRestart,
                )
            }
        }
    }

    private companion object {
        const val DOWNLOAD_URL = "https://zillit.example.com/download"
    }
}
