package com.zillit.desktop.feature.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.zillit.desktop.core.designsystem.ThemeMode
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.workspace.InMemoryWorkspaceSessionStore
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.core.workspace.ToolRegistry
import com.zillit.desktop.core.workspace.WorkspaceViewModel
import com.zillit.desktop.core.strings.AppLanguage
import com.zillit.desktop.core.strings.BundledCatalogSource
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.Strings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        RailItem("home", S.home, ZillitIcons.Home, WorkspaceRoute.Home),
        RailItem("tools", S.desktop_film_tools, ZillitIcons.Tools, WorkspaceRoute.Tool("/film-tools")),
        RailItem("email", S.email, ZillitIcons.Mail, WorkspaceRoute.Tool("/email")),
        RailItem("transport", S.txt_transportation, ZillitIcons.Transport, WorkspaceRoute.Tool("/transportation")),
    )

    @AfterTest
    fun restoreEnglish() = Strings.reset()

    @Test
    fun `frame renders its regions`() = runComposeUiTest {
        setShell()

        onNodeWithText("Zillit").assertIsDisplayed()
        onNodeWithText("No project selected").assertIsDisplayed()
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
    fun `the language menu lists every shipped language and writes the choice`() = runComposeUiTest {
        var chosen: String? = null
        setShell(onLanguageChange = { chosen = it })

        onNodeWithContentDescription("Language").performClick()

        onNodeWithText("Follow the system language").assertIsDisplayed()
        onNodeWithText("Français").assertIsDisplayed()
        onNodeWithText("日本語").assertIsDisplayed()
        onNodeWithText("Français").performClick()

        assertEquals("fr", chosen)
    }

    @Test
    fun `system is a real choice in the language menu`() = runComposeUiTest {
        var chosen: String? = "fr"
        setShell(language = "fr", onLanguageChange = { chosen = it })

        onNodeWithContentDescription("Language").performClick()
        onNodeWithText("Follow the system language").performClick()

        assertEquals("", chosen)
    }

    @Test
    fun `the frame redraws in the installed language`() = runComposeUiTest {
        setShell(onSignOut = {})
        onNodeWithContentDescription("Home").assertIsDisplayed()

        val source = BundledCatalogSource()
        val french = source.load(AppLanguage.byCode("fr")!!)!!.over(source.load(AppLanguage.English)!!)
        runOnUiThread { Strings.install(french) }
        waitForIdle()

        // The rail's labels are keys resolved at draw, so nothing was rebuilt.
        onNodeWithContentDescription("Accueil").assertIsDisplayed()
        onNodeWithContentDescription("Se déconnecter").assertIsDisplayed()
        // Desktop-only text comes from desktop-fr.xml.
        onNodeWithText("Aucun projet sélectionné").assertIsDisplayed()
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

        onNodeWithText("No project selected").performClick()

        assertEquals(1, switched, "clicking the project name should offer the picker")
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
        onNodeWithText("Transportation").assertIsDisplayed()
    }

    /** Logout is all the rail's foot holds, and it asks before it fires. */
    @Test
    fun `Logout sits alone at the foot and asks first`() = runComposeUiTest {
        var signedOut = 0
        setShell(onSignOut = { signedOut++ })

        onNodeWithContentDescription("Logout").assertIsDisplayed()
        val railBefore = onNodeWithContentDescription("Film Tools").getUnclippedBoundsInRoot()

        // A stray click here would sign the person out of every production at
        // once, so it confirms before it does anything.
        onNodeWithContentDescription("Logout").performClick()
        assertEquals(0, signedOut, "Logout must ask before signing anyone out")
        onNodeWithText("Sign out?").assertIsDisplayed()

        // The question belongs to the frame. Composed inside the rail it was
        // laid out in a 60pt column — the buttons came out five points high
        // and the rail's own entries were pushed around to make room.
        assertEquals(railBefore, onNodeWithContentDescription("Film Tools").getUnclippedBoundsInRoot())
        val confirm = onNodeWithText("Sign out").getUnclippedBoundsInRoot()
        assertTrue(
            confirm.height > MIN_DIALOG_BUTTON_HEIGHT,
            "the confirm button is $confirm — the dialog is being squeezed",
        )

        onNodeWithText("Sign out").performClick()
        assertEquals(1, signedOut)
    }

    /**
     * The mark is the notification list, as the phones' toolbar logo is
     * (Android `BottomNavigationActivity:544`). There is no bell any more, so
     * this is the only way in and it has to work.
     */
    @Test
    fun `the Zillit mark opens notifications and wears the unread count`() = runComposeUiTest {
        setShell(notificationsRoute = WorkspaceRoute.Tool(TEST_NOTIFICATIONS_PATH), notificationBadge = 3)

        onNodeWithText("3").assertIsDisplayed()

        onNodeWithText("Zillit").performClick()

        onNodeWithText("1 open").assertIsDisplayed()
        // The window is the notification list: its tab and its content both
        // name it, which is why this counts rather than asserting one node.
        onAllNodesWithText("Notifications").assertCountEquals(2)
    }

    /** Without a destination the mark is just the app's name — and inert. */
    @Test
    fun `the mark opens nothing when there is no notification list`() = runComposeUiTest {
        setShell()

        onNodeWithText("Zillit").performClick()

        onNodeWithText("0 open").assertIsDisplayed()
    }

    private fun ComposeUiTest.setShell(
        initialMode: ThemeMode = ThemeMode.System,
        language: String = "",
        onLanguageChange: (String) -> Unit = {},
        onSwitchProject: () -> Unit = {},
        onSignOut: (() -> Unit)? = null,
        notificationsRoute: WorkspaceRoute? = null,
        notificationBadge: Int = 0,
    ) {
        setContent {
            var mode by remember { mutableStateOf(initialMode) }
            val registry = remember {
                ToolRegistry(
                    placeholderTools() +
                        PlaceholderTool(TEST_NOTIFICATIONS_PATH, "Notifications", ZillitIcons.Bell),
                )
            }
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
                    language = language,
                    onLanguageChange = onLanguageChange,
                    onSwitchProject = onSwitchProject,
                    railItems = testRailItems,
                    onSignOut = onSignOut,
                    notificationsRoute = notificationsRoute,
                    notificationBadge = notificationBadge,
                )
            }
        }
    }
}

/** Anything shorter than this is a crushed control, not a button. */
private val MIN_DIALOG_BUTTON_HEIGHT = 24.dp

/** The notification list, for a frame whose real one lives in another module. */
private const val TEST_NOTIFICATIONS_PATH = "/notifications"
