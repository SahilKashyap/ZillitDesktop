package com.zillit.desktop.feature.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ThemeMode
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.workspace.InMemoryWorkspaceSessionStore
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.core.workspace.ToolRegistry
import com.zillit.desktop.core.workspace.WorkspaceViewModel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Drives the real frame.
 *
 * Screenshot verification needs macOS screen-recording permission, which CI does
 * not have; composing the actual UI checks the same things and runs identically
 * on every platform in the build matrix.
 */
@OptIn(ExperimentalTestApi::class)
class AppShellTest {

    /** Longer than the rail's expand animation, so assertions see the end state. */
    private val railSettleMillis = 400L

    /**
     * An explicit rail for these tests.
     *
     * The app's real rail is permission-driven and `DefaultRailItems` is only
     * the pre-rights fallback, so driving the frame through it would couple
     * these tests to a product decision about which tools are on the rail.
     */
    private val testRailItems = listOf(
        RailItem("home", "Home", ZillitIcons.Home, WorkspaceRoute.Home),
        RailItem("tools", "Film Tools", ZillitIcons.Tools, WorkspaceRoute.Tool("/film-tools")),
        RailItem("email", "Email", ZillitIcons.Mail, WorkspaceRoute.Tool("/email")),
        RailItem("transport", "Transport", ZillitIcons.Transport, WorkspaceRoute.Tool("/transportation")),
    )

    @Test
    fun `frame renders its regions`() = runComposeUiTest {
        setShell()

        onNodeWithText("Zillit").assertIsDisplayed()
        onNodeWithText("No production selected").assertIsDisplayed()
        onNodeWithText("0 open").assertIsDisplayed()
        onNodeWithText("Nothing open").assertIsDisplayed()
    }

    @Test
    fun `a rail item opens a window and the tab strip shows it`() {
        runComposeUiTest {
            setShell()

            onNodeWithContentDescription("Film Tools").performClick()

            onNodeWithText("1 open").assertIsDisplayed()
            // Placeholder content proves the ToolProvider resolved and rendered.
            onNodeWithText("This module arrives later in the roadmap.").assertIsDisplayed()
        }
    }

    @Test
    fun `opening two rail items yields two tabs`() = runComposeUiTest {
        setShell()

        onNodeWithContentDescription("Film Tools").performClick()
        onNodeWithContentDescription("Email").performClick()

        onNodeWithText("2 open").assertIsDisplayed()
    }

    @Test
    fun `re-opening the same rail item reuses its window`() = runComposeUiTest {
        setShell()

        onNodeWithContentDescription("Film Tools").performClick()
        onNodeWithContentDescription("Film Tools").performClick()

        onNodeWithText("1 open").assertIsDisplayed()
    }

    @Test
    fun `the theme toggle cycles light to dark to system`() = runComposeUiTest {
        setShell(initialMode = ThemeMode.Light)

        onNodeWithContentDescription("Light theme").performClick()
        onNodeWithContentDescription("Dark theme").assertIsDisplayed()

        onNodeWithContentDescription("Dark theme").performClick()
        onNodeWithContentDescription("Following system theme").assertIsDisplayed()

        onNodeWithContentDescription("Following system theme").performClick()
        onNodeWithContentDescription("Light theme").assertIsDisplayed()
    }

    @Test
    fun `window state survives switching tabs`() {
        // The state-retention contract in WorkspaceHost: each window's content is
        // keyed by WindowId in a SaveableStateHolder, so switching away and back
        // must not reset in-progress work. Regressing this is the failure mode
        // the whole workspace design exists to prevent.
        runComposeUiTest {
            setShell()

            onNodeWithContentDescription("Film Tools").performClick()
            onNodeWithText("Interact").performClick()
            onNodeWithText("Interact").performClick()
            onNodeWithText("Window state kept across tab switches: 2").assertIsDisplayed()

            onNodeWithContentDescription("Email").performClick()
            onNodeWithText("Window state kept across tab switches: 0").assertIsDisplayed()

            onNodeWithContentDescription("Film Tools").performClick()
            onNodeWithText("Window state kept across tab switches: 2").assertIsDisplayed()
        }
    }

    @Test
    fun `each window navigates independently`() = runComposeUiTest {
        setShell()

        onNodeWithContentDescription("Film Tools").performClick()
        onNodeWithText("Open a sub-page").performClick()
        onNodeWithText("/film-tools/detail").assertIsDisplayed()

        onNodeWithContentDescription("Email").performClick()
        // The email window has its own history and is still at its root.
        onNodeWithText("/email").assertIsDisplayed()
    }

    @Test
    fun `the production name is the switch control`() = runComposeUiTest {
        // Discoverability: the only thing on the bar that names where you are is
        // also the way to be somewhere else. The web buries this in the side
        // menu, several clicks from the name it changes.
        var switched = 0
        setShell(onSwitchProject = { switched++ })

        onNodeWithText("No production selected").performClick()

        assertEquals(1, switched, "clicking the production name should offer the picker")
    }

    @Test
    fun `rail labels are hidden until the rail is hovered`() = runComposeUiTest {
        // Collapsed, the rail is icons only — the label exists as an accessible
        // description but must not be rendered as text, or it would be laid out
        // inside a 60pt column.
        setShell()

        onNodeWithContentDescription("Film Tools").assertIsDisplayed()
        onAllNodesWithText("Film Tools").assertCountEquals(0)
    }

    @Test
    fun `hovering the rail reveals the titles`() = runComposeUiTest {
        setShell()

        // Collapsed: the label is an accessible description only.
        onAllNodesWithText("Film Tools").assertCountEquals(0)

        onNodeWithContentDescription("Film Tools").performMouseInput { moveTo(center) }
        // The width and the label both animate; settle before asserting.
        mainClock.advanceTimeBy(railSettleMillis)

        onNodeWithText("Film Tools").assertIsDisplayed()
        onNodeWithText("Transport").assertIsDisplayed()
    }

    private fun ComposeUiTest.setShell(
        initialMode: ThemeMode = ThemeMode.System,
        onSwitchProject: () -> Unit = {},
    ) {
        setContent {
            var mode by remember { mutableStateOf(initialMode) }
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
                    onThemeModeChange = { mode = it },
                    onSwitchProject = onSwitchProject,
                    railItems = testRailItems,
                )
            }
        }
    }
}
