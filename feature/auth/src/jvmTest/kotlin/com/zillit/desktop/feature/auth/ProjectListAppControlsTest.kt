package com.zillit.desktop.feature.auth

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ThemeMode
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.UpdateCheckOutcome
import com.zillit.desktop.feature.auth.domain.Project
import com.zillit.desktop.feature.auth.ui.AuthStep
import com.zillit.desktop.feature.auth.ui.AuthUiState
import com.zillit.desktop.feature.auth.ui.ProjectListScreen
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The production list carries the shell bar's own controls, in its words:
 * it is the first screen after sign-in, and the shell is not reachable yet.
 * It used to have a bare globe and a bare sun, and no way to check for a build.
 */
@OptIn(ExperimentalTestApi::class)
class ProjectListAppControlsTest {

    private val state = AuthUiState(
        step = AuthStep.ProjectSelection,
        projects = listOf(Project(id = "p1", name = "DN6", code = "985073", type = null, region = null)),
    )

    @Test
    fun `the list wears the bar's three labelled controls`() = runComposeUiTest {
        setContent {
            ZillitTheme(darkTheme = false) {
                ProjectListScreen(
                    state = state,
                    onEvent = {},
                    themeMode = ThemeMode.Light,
                    onThemeModeChange = {},
                    onCheckForUpdates = { UpdateCheckOutcome.UpToDate },
                )
            }
        }

        onNodeWithText("Check for updates").assertIsDisplayed()
        onNodeWithText("Switch Language").assertIsDisplayed()
        onNodeWithText("Switch Theme").assertIsDisplayed()
    }

    @Test
    fun `switch theme moves to the next mode and check for updates answers on the button`() = runComposeUiTest {
        var theme = ThemeMode.Light
        var checks = 0
        setContent {
            ZillitTheme(darkTheme = false) {
                ProjectListScreen(
                    state = state,
                    onEvent = {},
                    themeMode = theme,
                    onThemeModeChange = { theme = it },
                    onCheckForUpdates = { checks++; UpdateCheckOutcome.UpToDate },
                )
            }
        }

        onNodeWithText("Switch Theme").performClick()
        assertEquals(ThemeMode.Dark, theme)

        onNodeWithText("Check for updates").performClick()
        onNode(hasText("You're on the latest version.") and hasClickAction()).assertIsDisplayed()
        assertEquals(1, checks)
    }

    @Test
    fun `no update button where the app offers no check`() = runComposeUiTest {
        setContent {
            ZillitTheme(darkTheme = false) {
                ProjectListScreen(state = state, onEvent = {}, themeMode = ThemeMode.Light, onThemeModeChange = {})
            }
        }

        onAllNodesWithText("Check for updates").assertCountEquals(0)
        onNodeWithText("Switch Theme").assertIsDisplayed()
    }
}
