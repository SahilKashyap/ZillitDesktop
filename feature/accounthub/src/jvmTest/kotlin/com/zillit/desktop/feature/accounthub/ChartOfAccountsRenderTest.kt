package com.zillit.desktop.feature.accounthub

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.accounthub.domain.ChartMode
import com.zillit.desktop.feature.accounthub.domain.CoaCostType
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.ChartState
import com.zillit.desktop.feature.accounthub.ui.ChartView
import com.zillit.desktop.feature.accounthub.ui.LayerDelete
import com.zillit.desktop.feature.accounthub.ui.LayerInUse
import com.zillit.desktop.feature.accounthub.ui.TreeFold
import com.zillit.desktop.feature.accounthub.ui.pages.ChartOfAccountsPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Chart of Accounts page composed for real — the web's
 * `ChartOfAccountsModule` — in both themes, on every surface: the tree, the
 * table, the empty tab, the grid, the layers and the dialogs.
 */
@OptIn(ExperimentalTestApi::class)
class ChartOfAccountsRenderTest {

    private fun render(
        state: AccountHubUiState,
        dark: Boolean = false,
        onEvent: (AccountHubEvent) -> Unit = {},
        assertions: androidx.compose.ui.test.ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
        setContent {
            ZillitTheme(darkTheme = dark) { ChartOfAccountsPage(state, onEvent, canImportBudget = true) }
        }
        assertions()
    }

    private fun chart(transform: ChartState.() -> ChartState = { this }) =
        ChartFixtures.state(ChartState(accounts = ChartFixtures.accounts, loaded = true).transform())

    @Test
    fun `the tree draws the chart with its levels and exceptions`() = render(chart { copy(fold = TreeFold.AllOpen) }) {
        onNodeWithText("Chart of Accounts").assertIsDisplayed()
        onNodeWithText("Cost Accounts").assertIsDisplayed()
        // A top-level group's name is drawn in capitals, as the web's tree draws it.
        onNodeWithText("ABOVE THE LINE").assertIsDisplayed()
        onNodeWithText("Story & Rights").assertIsDisplayed()
        onAllNodesWithText("HEADERS").onFirst().assertIsDisplayed()
        onNodeWithText("NON-POSTING").assertIsDisplayed()
        onNodeWithText("INACTIVE").assertIsDisplayed()
        onNodeWithText("Unnamed").assertIsDisplayed()
        // The balance-sheet rows belong to the other tab.
        assertTrue(onAllNodesWithText("Current Assets").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `the stat strip counts the tab's rows`() = render(chart()) {
        onNodeWithText("TOTAL CODES").assertIsDisplayed()
        onNodeWithText("12").assertIsDisplayed()
        onNodeWithText("enabled for posting").assertIsDisplayed()
    }

    @Test
    fun `the table shows each row's level, breadcrumb and sort`() = render(chart { copy(mode = ChartMode.Table) }) {
        onAllNodesWithText("GROUP").onFirst().assertIsDisplayed()
        onAllNodesWithText("Above the Line / Story & Rights").onFirst().assertIsDisplayed()
        onNodeWithText("Sorted by ").assertIsDisplayed()
        onNodeWithText("12 rows").assertIsDisplayed()
    }

    @Test
    fun `a row's actions reach the view model`() {
        val events = mutableListOf<AccountHubEvent>()
        render(chart { copy(mode = ChartMode.Table) }, onEvent = { events += it }) {
            onAllNodesWithContentDescription("Deactivate").onFirst().performClick()
            onAllNodesWithContentDescription("Edit").onFirst().performClick()
            onAllNodesWithContentDescription("Add Header for Group").onFirst().performClick()
        }
        assertTrue(events.any { it is AccountHubEvent.AskDeactivateAccount })
        assertTrue(events.any { it is AccountHubEvent.ComposeAccount && it.editing != null })
        assertTrue(events.any { it is AccountHubEvent.OpenBulkAdd && it.parent?.id == "h1" })
    }

    @Test
    fun `an empty balance sheet offers to build from scratch`() {
        val costsOnly = ChartFixtures.accounts.filter { it.costType == CoaCostType.Expense }
        render(chart { copy(view = ChartView.BalanceSheet, accounts = costsOnly) }) {
            onNodeWithText("Your Chart of Accounts is empty").assertIsDisplayed()
            onNodeWithText("Build your codes manually from scratch.").assertIsDisplayed()
            onNodeWithText("Build from scratch").assertIsDisplayed()
        }
    }

    @Test
    fun `a search with no hit says so`() = render(chart { copy(search = "zzz") }) {
        onNodeWithText("No accounts match “zzz”.").assertIsDisplayed()
    }

    @Test
    fun `the grid draws its rows under the parent it adds to`() = render(ChartFixtures.state(ChartFixtures.bulk())) {
        onNodeWithText("Add chart-of-accounts entries").assertIsDisplayed()
        onNodeWithText("Couldn't save some rows").assertIsDisplayed()
        onNodeWithText("Story Consultant").assertIsDisplayed()
        onNodeWithText("Done").assertIsDisplayed()
    }

    @Test
    fun `the edit form names the code and says what cannot change`() =
        render(ChartFixtures.state(ChartFixtures.editing(ChartFixtures.story))) {
        onNodeWithText("Edit code · 1100").assertIsDisplayed()
        onNodeWithText("Code is immutable — clone to a new code if needed.").assertIsDisplayed()
        onNodeWithText("Save changes").assertIsDisplayed()
    }

    @Test
    fun `a budget row's form is locked`() =
        render(ChartFixtures.state(ChartFixtures.editing(ChartFixtures.cameraHire))) {
        onNodeWithText("Imported from a budget — its line type is fixed.", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the layers tab lists its sets and opens one at a time`() {
        val layered = chart { copy(view = ChartView.Layers, trackingSets = ChartFixtures.layers, openLayer = "t1") }
        render(layered) {
            onNodeWithText("Layers — analytical dimensions").assertIsDisplayed()
            onNodeWithText("Locations").assertIsDisplayed()
            onNodeWithText("London").assertIsDisplayed()
            onAllNodesWithText("DISABLED").onFirst().assertIsDisplayed()
            onNodeWithText("Add code").assertIsDisplayed()
        }
        // Closed, the set shows its count and not its codes.
        render(chart { copy(view = ChartView.Layers, trackingSets = ChartFixtures.layers) }) {
            onNodeWithText("  ·  3 codes").assertIsDisplayed()
            assertTrue(onAllNodesWithText("London").fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun `a production with no layers is invited to add one`() = render(chart { copy(view = ChartView.Layers) }) {
        onNodeWithText("No layers yet").assertIsDisplayed()
        onAllNodesWithText("New set").onFirst().assertIsDisplayed()
    }

    @Test
    fun `a refused layer delete is shown in the server's words`() {
        val refused = chart {
            copy(
                view = ChartView.Layers,
                trackingSets = ChartFixtures.layers,
                layerInUse = LayerInUse(
                    title = "Can't delete this code",
                    message = "Cannot delete LOC-LON — referenced in: Purchase Orders",
                ),
            )
        }
        render(refused) {
            onNodeWithText("Can't delete this code").assertIsDisplayed()
            onNodeWithText("Cannot delete LOC-LON — referenced in: Purchase Orders").assertIsDisplayed()
        }
    }

    @Test
    fun `every surface composes in dark mode`() {
        val states = listOf(
            chart { copy(fold = TreeFold.AllOpen) },
            chart { copy(mode = ChartMode.Table) },
            ChartFixtures.state(ChartFixtures.bulk()),
            ChartFixtures.state(ChartFixtures.editing(ChartFixtures.writing)),
            chart { copy(view = ChartView.Layers, trackingSets = ChartFixtures.layers, openLayer = "t1") },
            chart { copy(view = ChartView.Layers, layerDelete = LayerDelete.WholeSet(ChartFixtures.layers.first())) },
        )
        states.forEach { state ->
            render(state, dark = true) {
                onAllNodesWithText("chart", substring = true, ignoreCase = true).onFirst().assertExists()
            }
        }
        assertEquals(6, states.size)
    }

    private companion object {
        /** Tall enough that the lazy tree composes every row the fixture opens. */
        const val WIDTH = 1280
        const val HEIGHT = 2000
    }
}
