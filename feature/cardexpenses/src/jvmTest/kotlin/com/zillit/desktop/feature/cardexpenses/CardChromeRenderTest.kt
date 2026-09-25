package com.zillit.desktop.feature.cardexpenses

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.cardexpenses.domain.CardMetadata
import com.zillit.desktop.feature.cardexpenses.domain.CardViewer
import com.zillit.desktop.feature.cardexpenses.ui.CardDestination
import com.zillit.desktop.feature.cardexpenses.ui.CardExpensesScreen
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.enteredThrough
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tool's own chrome, now that the Account Hub renders it full-bleed: the
 * sidebar's title card and way back, the page headings, and the crew view an
 * accountant gets from the tile.
 */
@OptIn(ExperimentalTestApi::class)
class CardChromeRenderTest {

    private val accountant = CardViewer(
        userId = "user-1",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_production_accountant",
        metadata = CardMetadata(isSenior = true),
    )

    @Test
    fun `the sidebar carries the title and a way back`() = runComposeUiTest {
        var backs = 0
        setContent {
            ZillitTheme(darkTheme = false) {
                CardExpensesScreen(
                    state = CardUiState(viewer = accountant, destination = CardDestination.Overview),
                    onEvent = {},
                    onBack = { backs++ },
                )
            }
        }

        assertTrue(onAllNodesWithText("Production Expense Cards").fetchSemanticsNodes().isNotEmpty())
        onNodeWithContentDescription("Back to dashboard").performClick()
        assertEquals(1, backs)
    }

    /** Every accountant page opens with its own heading, as every web page does. */
    @Test
    fun `an accountant page opens with its heading`() = runComposeUiTest {
        setContent {
            ZillitTheme(darkTheme = false) {
                CardExpensesScreen(
                    state = CardUiState(viewer = accountant, destination = CardDestination.TopUpQueue),
                    onEvent = {},
                )
            }
        }

        onNodeWithText("Card top-ups generated from processed expenses", substring = true).assertExists()
    }

    /** From the tile, the same accountant sees the crew's tabs and none of the console. */
    @Test
    fun `an accountant entering from the tile sees the crew view`() = runComposeUiTest {
        val fromTile = CardUiState(viewer = accountant, destination = CardDestination.Overview).enteredThrough(true)
        setContent {
            ZillitTheme(darkTheme = false) {
                CardExpensesScreen(state = fromTile, onEvent = {}, onBack = {})
            }
        }

        onNodeWithText("View your receipts, card details, and approval status", substring = true).assertExists()
        assertTrue(onAllNodesWithText("Card Register").fetchSemanticsNodes().isEmpty(), "no console pages")
    }
}
