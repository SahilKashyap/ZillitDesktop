package com.zillit.desktop.feature.budgetbuilder

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.budgetbuilder.domain.BudgetBuilderViewer
import com.zillit.desktop.feature.budgetbuilder.ui.BudgetBuilderEvent
import com.zillit.desktop.feature.budgetbuilder.ui.BudgetBuilderScreen
import com.zillit.desktop.feature.budgetbuilder.ui.BudgetBuilderUiState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Composes the real launch page in its three states.
 *
 * Cheap insurance against the two failures unit tests cannot see — a screen
 * that throws while composing, and a control that composed but is not there.
 */
@OptIn(ExperimentalTestApi::class)
class BudgetBuilderScreenRenderTest {

    private fun viewer(canView: Boolean = true, canPost: Boolean = true) =
        BudgetBuilderViewer(canView = canView, canPost = canPost, ready = true)

    @Test
    fun `an entitled viewer gets the door, and it opens`() {
        val events = mutableListOf<BudgetBuilderEvent>()
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    BudgetBuilderScreen(
                        state = BudgetBuilderUiState(viewer = viewer()),
                        onEvent = events::add,
                    )
                }
            }
            onNodeWithText("Open Budget Builder").assertExists()
            onNodeWithText("Open Budget Builder").performClick()
        }
        assertEquals(listOf<BudgetBuilderEvent>(BudgetBuilderEvent.Open), events)
    }

    @Test
    fun `view-only rights are said out loud`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    BudgetBuilderScreen(
                        state = BudgetBuilderUiState(viewer = viewer(canPost = false)),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText(
                "You hold view access only — the budget opens read-only.",
            ).assertExists()
        }
    }

    @Test
    fun `a blocked viewer gets the refusal, not the button`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    BudgetBuilderScreen(
                        state = BudgetBuilderUiState(
                            viewer = viewer(canView = false, canPost = false),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Open Budget Builder").assertDoesNotExist()
            onNodeWithText(
                "You don’t have access to Budget Builder on this project. " +
                    "Access is granted per tool, by the project’s admin.",
            ).assertExists()
        }
    }

    @Test
    fun `an unconfigured environment says so instead of offering a broken window`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    BudgetBuilderScreen(
                        state = BudgetBuilderUiState(configured = false),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText("Open Budget Builder").assertDoesNotExist()
            onNodeWithText("Budget Builder isn’t configured for this environment.").assertExists()
        }
    }
}
