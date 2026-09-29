package com.zillit.desktop.feature.auth

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ThemeMode
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.auth.domain.Project
import com.zillit.desktop.feature.auth.ui.AuthStep
import com.zillit.desktop.feature.auth.ui.AuthUiState
import com.zillit.desktop.feature.auth.ui.ProjectListScreen
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The picker's badge for a production scheduled for deletion.
 *
 * Rendered rather than asserted on the model, because the badge's whole job is
 * to be seen: the card already carried Admin and Personal tags, and a fourth
 * that never got placed would look identical in the data.
 */
@OptIn(ExperimentalTestApi::class)
class MarkedDeleteRenderTest {

    private fun project(name: String, marked: Boolean) = Project(
        id = name.lowercase(),
        name = name,
        code = "CODE",
        type = "entertainment",
        region = null,
        isMarkedDeleted = marked,
    )

    private fun ComposeUiTest.picker(vararg projects: Project) {
        setContent {
            ZillitTheme(animateThemeChange = false) {
                ProjectListScreen(
                    state = AuthUiState(step = AuthStep.ProjectSelection, projects = projects.toList()),
                    onEvent = {},
                    themeMode = ThemeMode.Light,
                    onThemeModeChange = {},
                )
            }
        }
        // The cards fade and slide in — nothing is placed on the first frame.
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
    }

    @Test
    fun `a production marked for deletion wears the badge`() = runComposeUiTest {
        picker(project("Call Sheet Eee Vg", marked = true))

        val badge = onNodeWithText("Marked Delete", useUnmergedTree = true)
        assertTrue(badge.isDisplayed(), "the badge must be placed, not merely present in the data")
    }

    /** It is the exception, not decoration every card wears. */
    @Test
    fun `an ordinary production carries no badge`() = runComposeUiTest {
        picker(project("Blade Runner 2049", marked = false))

        onAllNodesWithText("Marked Delete", useUnmergedTree = true).assertCountEquals(0)
    }

    /** Listed alongside the rest rather than hidden, which is the whole change. */
    @Test
    fun `a marked production is still listed with the others`() = runComposeUiTest {
        picker(project("Call Sheet Eee Vg", marked = true), project("Arrival", marked = false))

        assertTrue(onNodeWithText("Call Sheet Eee Vg", useUnmergedTree = true).isDisplayed())
        assertTrue(onNodeWithText("Arrival", useUnmergedTree = true).isDisplayed())
    }
}
