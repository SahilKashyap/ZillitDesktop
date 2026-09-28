package com.zillit.desktop.feature.shell

import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasTestTag
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    // --- downloading inside the app -----------------------------------------
    //
    // Reported as "tap Download and it goes outside the app". Where the app can
    // fetch the installer itself, Download does that — with a progress bar —
    // and the only control that still leaves the app says so.

    private fun inApp(install: UpdateInstall) =
        UpdateNotice("1.2.0", mandatory = false, downloadUrl = INSTALLER_URL, install = install, manual = true)

    @Test
    fun `Download stays inside the app when it can fetch the installer`() = runComposeUiTest {
        val browser = mutableListOf<String>()
        var started = 0
        setShell(notice = inApp(UpdateInstall.Offer), onDownload = { browser += it }, onInstall = { started++ })

        onAllNodesWithText("Update now").assertCountEquals(0)
        onNodeWithText("Download").performClick()

        assertEquals(1, started)
        assertEquals(emptyList(), browser, "the report: Download must not leave the app")
    }

    @Test
    fun `a download in progress shows its progress and can be cancelled`() = runComposeUiTest {
        var cancelled = 0
        setShell(notice = inApp(UpdateInstall.Downloading(percent = 42)), onCancel = { cancelled++ })

        onNodeWithText("Downloading version 1.2.0… 42%").assertIsDisplayed()
        onNode(hasTestTag(PROGRESS_TAG) and hasProgressBarRangeInfo(ProgressBarRangeInfo(0.42f, 0f..1f)))
            .assertIsDisplayed()
        onNodeWithTag(CANCEL_TAG).performClick()

        assertEquals(1, cancelled)
    }

    /** Dismissing would hide a download that is still running, with no way back to cancel it. */
    @Test
    fun `a download in progress cannot be dismissed`() = runComposeUiTest {
        setShell(notice = inApp(UpdateInstall.Downloading(percent = 42)))

        onAllNodesWithContentDescription("Dismiss update notice").assertCountEquals(0)
    }

    @Test
    fun `a download of unknown size still shows it is working`() = runComposeUiTest {
        setShell(notice = inApp(UpdateInstall.Downloading(percent = null)))

        onNodeWithText("Downloading version 1.2.0…").assertIsDisplayed()
        // Indeterminate: an empty bar would sit at 0% and look stuck.
        onNode(hasTestTag(PROGRESS_TAG) and hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertIsDisplayed()
    }

    @Test
    fun `once downloaded the strip says where it is and can open it again`() = runComposeUiTest {
        var opened = 0
        setShell(notice = inApp(UpdateInstall.Downloaded), onOpen = { opened++ })

        onNodeWithText("saved in your Downloads folder", substring = true).assertIsDisplayed()
        onNodeWithTag(OPEN_INSTALLER_TAG).performClick()

        assertEquals(1, opened)
    }

    /** After a failure the browser is the fallback — and it must say it is the browser. */
    @Test
    fun `the fallback that leaves the app is labelled as the browser`() = runComposeUiTest {
        val browser = mutableListOf<String>()
        setShell(
            notice = inApp(UpdateInstall.Failed(retryable = true, verification = false)),
            onDownload = { browser += it },
        )

        onNodeWithText("Try Again").assertIsDisplayed()
        onAllNodesWithText("Download").assertCountEquals(0)
        onNodeWithText("Open in browser").performClick()

        assertEquals(listOf(INSTALLER_URL), browser)
    }

    /** Started from Settings after the strip was dismissed: the progress must still be seen. */
    @Test
    fun `a download the person started shows even after the strip was dismissed`() = runComposeUiTest {
        val live = androidx.compose.runtime.mutableStateOf<UpdateNotice?>(inApp(UpdateInstall.Offer))
        setLiveShell(live)
        onNodeWithContentDescription("Dismiss update notice").performClick()
        onAllNodesWithTag(DISMISSIBLE_TAG).assertCountEquals(0)

        // Settings' Download: one ask.
        live.value = inApp(UpdateInstall.Downloading(percent = 10)).copy(requests = 1)
        waitForIdle()

        onNodeWithTag(DISMISSIBLE_TAG).assertIsDisplayed()
        onNodeWithTag(PROGRESS_TAG).assertIsDisplayed()

        // And it stays to say how the download ended.
        live.value = inApp(UpdateInstall.Downloaded).copy(requests = 1)
        waitForIdle()
        onNodeWithTag(OPEN_INSTALLER_TAG).assertIsDisplayed()
    }

    /**
     * The ask, not a progress frame, is what brings the strip back. A download
     * that fails before a frame shows Downloading, or a staged build waiting
     * for its restart, is just as much the answer to it.
     */
    @Test
    fun `asking again from Settings re-shows a dismissed strip at any step`() = runComposeUiTest {
        val live = androidx.compose.runtime.mutableStateOf<UpdateNotice?>(inApp(UpdateInstall.Offer))
        setLiveShell(live)
        onNodeWithContentDescription("Dismiss update notice").performClick()
        waitForIdle()

        live.value = inApp(UpdateInstall.Failed(retryable = true, verification = false)).copy(requests = 1)
        waitForIdle()
        onNodeWithText("Try Again").assertIsDisplayed()

        onNodeWithContentDescription("Dismiss update notice").performClick()
        live.value =
            UpdateNotice("1.2.0", mandatory = false, downloadUrl = DOWNLOAD_URL, install = UpdateInstall.Ready())
            .copy(requests = 2)
        waitForIdle()
        onNodeWithTag(RESTART_TAG).assertIsDisplayed()
    }

    /** Once the download is over, the X must work like it does everywhere else. */
    @Test
    fun `a finished or failed download can be dismissed`() = runComposeUiTest {
        val live = androidx.compose.runtime.mutableStateOf<UpdateNotice?>(inApp(UpdateInstall.Downloaded))
        setLiveShell(live)

        onNodeWithContentDescription("Dismiss update notice").performClick()
        waitForIdle()
        onAllNodesWithTag(DISMISSIBLE_TAG).assertCountEquals(0)

        live.value = inApp(UpdateInstall.Failed(retryable = true, verification = false))
        waitForIdle()
        onAllNodesWithTag(DISMISSIBLE_TAG).assertCountEquals(0)
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
            notice = UpdateNotice("1.2.0", true, DOWNLOAD_URL, install = UpdateInstall.Ready()),
            onRestart = { restarts++ },
        )

        onNodeWithTag(RESTART_TAG).performClick()

        assertEquals(1, restarts)
    }

    /** The restart is coming on its own: the strip says when, and still offers it now. */
    @Test
    fun `an automatic restart counts down and cannot be dismissed`() = runComposeUiTest {
        var restarts = 0
        setShell(notice = inApp(UpdateInstall.Ready(restartIn = 12)), onRestart = { restarts++ })

        onNodeWithText("Version 1.2.0 is ready. Zillit will restart to finish updating in 12 s.").assertIsDisplayed()
        onAllNodesWithContentDescription("Dismiss update notice").assertCountEquals(0)
        onNodeWithTag(RESTART_TAG).performClick()

        assertEquals(1, restarts)
    }

    @Test
    fun `an automatic restart waits for the call to end`() = runComposeUiTest {
        setShell(notice = inApp(UpdateInstall.Ready(afterCall = true)))

        onNodeWithText("Version 1.2.0 is ready. Zillit will restart to finish updating when your call ends.")
            .assertIsDisplayed()
    }

    /** Dismissed earlier, the strip comes back for the countdown: Zillit closing unwarned would look like a crash. */
    @Test
    fun `a dismissed strip returns for an automatic restart`() {
        val dismissed = UpdateDismissal("1.2.0", requests = 0)

        assertTrue(inApp(UpdateInstall.Ready(restartIn = 5)).shownAfter(dismissed))
        assertFalse(inApp(UpdateInstall.Ready()).shownAfter(dismissed))
    }

    /** Below the floor on a packaged build: the frame is covered, not striped, and Quit is the other way out. */
    @Test
    fun `a blocking notice covers the frame and offers the update and quit`() = runComposeUiTest {
        var quits = 0
        var installs = 0
        setShell(
            notice = UpdateNotice("1.2.0", true, DOWNLOAD_URL, install = UpdateInstall.Offer, blocking = true),
            onInstall = { installs++ },
            onQuit = { quits++ },
        )

        onNodeWithTag(FORCE_UPDATE_TAG).assertIsDisplayed()
        onNodeWithText("Update required").assertIsDisplayed()
        onAllNodesWithTag(BLOCKING_TAG).assertCountEquals(0)
        onNodeWithTag(INSTALL_TAG).performClick()
        onNodeWithTag(FORCE_UPDATE_QUIT_TAG).performClick()

        assertEquals(1, installs)
        assertEquals(1, quits)
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

    @Suppress("LongParameterList") // one per callback the strip exposes
    private fun ComposeUiTest.setShell(
        notice: UpdateNotice?,
        onDownload: (String) -> Unit = {},
        mode: ThemeMode = ThemeMode.System,
        onInstall: () -> Unit = {},
        onRestart: () -> Unit = {},
        onCancel: () -> Unit = {},
        onOpen: () -> Unit = {},
        onQuit: (() -> Unit)? = null,
    ) = setLiveShell(
        androidx.compose.runtime.mutableStateOf(notice),
        onDownload, mode, onInstall, onRestart, onCancel, onOpen, onQuit,
    )

    /** [setShell] with a notice the test can change after composition. */
    @Suppress("LongParameterList") // one per callback the strip exposes
    private fun ComposeUiTest.setLiveShell(
        live: androidx.compose.runtime.State<UpdateNotice?>,
        onDownload: (String) -> Unit = {},
        mode: ThemeMode = ThemeMode.System,
        onInstall: () -> Unit = {},
        onRestart: () -> Unit = {},
        onCancel: () -> Unit = {},
        onOpen: () -> Unit = {},
        onQuit: (() -> Unit)? = null,
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
                    updateNotice = live.value,
                    onDownloadUpdate = onDownload,
                    onInstallUpdate = onInstall,
                    onRestartToUpdate = onRestart,
                    onCancelUpdate = onCancel,
                    onOpenUpdate = onOpen,
                    onQuit = onQuit,
                )
            }
        }
    }

    private companion object {
        const val DOWNLOAD_URL = "https://zillit.example.com/download"
        const val INSTALLER_URL = "https://downloads.zillit.example.com/Zillit-Desktop-Mac-Silicon.dmg"
    }
}
