package com.zillit.desktop.feature.budgetbuilder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.budgetbuilder.domain.BudgetBuilderViewer
import com.zillit.desktop.feature.budgetbuilder.ui.BudgetBuilderScreen
import com.zillit.desktop.feature.budgetbuilder.ui.BudgetBuilderUiState
import kotlin.test.Test

/**
 * Composes the real window in its four states.
 *
 * The application itself is a heavyweight browser surface the host owns, so it
 * stands in here as a tagged box — what is under test is which of the two the
 * window hands the space to, and that each notice says what the web's says.
 *
 * Cheap insurance against the two failures unit tests cannot see — a screen
 * that throws while composing, and a notice that composed but is not there.
 */
@OptIn(ExperimentalTestApi::class)
class BudgetBuilderScreenRenderTest {

    private fun viewer(canView: Boolean = true, canPost: Boolean = true) =
        BudgetBuilderViewer(canView = canView, canPost = canPost, ready = true)

    private fun ComposeUiTest.show(state: BudgetBuilderUiState) {
        setContent {
            ZillitTheme(darkTheme = false) {
                BudgetBuilderScreen(
                    state = state,
                    application = { Box(Modifier.fillMaxSize().testTag(APPLICATION)) },
                )
            }
        }
    }

    @Test
    fun `an entitled viewer gets the application, filling the window`() = runComposeUiTest {
        show(BudgetBuilderUiState(viewer = viewer()))

        onNodeWithTag(APPLICATION).assertExists()
    }

    @Test
    fun `a blocked viewer gets the refusal, not the application`() = runComposeUiTest {
        show(BudgetBuilderUiState(viewer = viewer(canView = false, canPost = false)))

        onNodeWithTag(APPLICATION).assertDoesNotExist()
        onNodeWithText(
            "You don’t have access to Budget Builder on this project. " +
                "Access is granted per tool, by the project’s admin.",
        ).assertExists()
    }

    @Test
    fun `an unconfigured environment says so instead of embedding a broken page`() = runComposeUiTest {
        show(BudgetBuilderUiState(configured = false))

        onNodeWithTag(APPLICATION).assertDoesNotExist()
        onNodeWithText("Budget Builder isn’t configured for this environment.").assertExists()
    }

    @Test
    fun `offline, the window explains rather than showing a page that cannot load`() = runComposeUiTest {
        show(BudgetBuilderUiState(viewer = viewer(), offline = true))

        onNodeWithTag(APPLICATION).assertDoesNotExist()
        onNodeWithText(
            "Budget Builder is a hosted application and needs a connection — there is no offline copy " +
                "of the budget on this computer. It opens again as soon as you’re back online.",
        ).assertExists()
    }

    private companion object {
        const val APPLICATION = "budget-builder-application"
    }
}
