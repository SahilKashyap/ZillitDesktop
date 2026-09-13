package com.zillit.desktop.feature.accounthub

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.accounthub.domain.AccountHubViewer
import com.zillit.desktop.feature.accounthub.domain.BudgetImportMeta
import com.zillit.desktop.feature.accounthub.domain.BudgetLine
import com.zillit.desktop.feature.accounthub.domain.BudgetStatus
import com.zillit.desktop.feature.accounthub.domain.BudgetUpload
import com.zillit.desktop.feature.accounthub.domain.BudgetVersion
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.HubNavigation
import com.zillit.desktop.feature.accounthub.domain.ParsedBudget
import com.zillit.desktop.feature.accounthub.domain.ParsedCode
import com.zillit.desktop.feature.accounthub.domain.ParsedSection
import com.zillit.desktop.feature.accounthub.domain.ParsedUncoded
import com.zillit.desktop.feature.accounthub.domain.PickedAgreementFile
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubScreen
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.BudgetImportState
import com.zillit.desktop.feature.accounthub.ui.BudgetState
import com.zillit.desktop.feature.accounthub.ui.ImportStep
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Budget page and every step of its import wizard, composed for real.
 *
 * The tree sits in a lazy list inside a card inside the page, and the preview
 * is a long scrolling dialog — the arrangements that fail at composition
 * rather than in review. The tree's toggle and the wizard's buttons are
 * clicked, so a row wired to the wrong event fails here.
 */
@OptIn(ExperimentalTestApi::class)
class BudgetRenderTest {

    private val viewer = AccountHubViewer(
        userId = "u1",
        isAccountant = true,
        canView = true,
        canPost = true,
        canDownload = true,
        ready = true,
        viewableTools = setOf(AccountHubViewer.TOOL_IDENTIFIER),
    )

    private val versions = listOf(
        BudgetVersion(
            id = "b2",
            version = "v2",
            name = "Revised budget",
            status = BudgetStatus.Draft,
            total = 1_000.0,
            currencyCode = "GBP",
            createdAtMillis = 1_750_000_000_000L,
            sourceFileName = "revised.xlsx",
        ),
        BudgetVersion("b1", "v1", "Sound budget", BudgetStatus.Live, 900.0, "GBP"),
    )

    private val lines = listOf(
        BudgetLine(
            id = "h1",
            account = "1000",
            name = "Above the line",
            rollupTotal = 1_000.0,
            lineType = CoaLineType.Header,
        ),
        BudgetLine(
            id = "s1",
            account = "1100",
            name = "Story",
            rollupTotal = 1_000.0,
            lineType = CoaLineType.Section,
            headId = "h1",
        ),
        BudgetLine(
            id = "c1",
            account = "1110",
            name = "Writers",
            amount = 1_000.0,
            lineType = CoaLineType.Category,
            sectionId = "s1",
        ),
    )

    private fun state(import: BudgetImportState = BudgetImportState()) = AccountHubUiState(
        viewer = viewer,
        sections = HubNavigation.visibleTo(viewer),
        area = HubArea.Budget,
        budget = BudgetState(
            versions = versions,
            selectedId = "b2",
            lines = lines,
            openGroups = setOf("h1"),
            import = import,
        ),
    )

    private val parsed = ParsedBudget(
        currency = "GBP",
        sections = listOf(ParsedSection("A", "Above the line")),
        headers = listOf(ParsedCode("1000", "Story", 600.0, sectionId = "A")),
        nominals = listOf(ParsedCode("1110", "Writers", 600.0, parentCode = "1000")),
        uncoded = listOf(ParsedUncoded("Contingency", 50.0)),
        warnings = listOf("Duplicate code 1110"),
    )

    @Test
    fun `the page shows the versions, the open top level and its subtotal`() {
        val events = mutableListOf<AccountHubEvent>()
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    AccountHubScreen(state = state(), onEvent = { events += it }, canImportBudget = true)
                }
            }
            onNodeWithText("Budget Versions").assertIsDisplayed()
            onNodeWithText("2 versions · 1 LIVE").assertIsDisplayed()
            onNodeWithText("ABOVE THE LINE").assertIsDisplayed()
            onNodeWithText("Story").assertIsDisplayed()
            // Writers sits in a group that has not been opened.
            onAllNodesWithText("Writers").assertCountEquals(0)
            onNodeWithText("TOTAL · ABOVE THE LINE").assertIsDisplayed()

            onNodeWithText("Story").performClick()
            onNodeWithText("Sound budget").performClick()
            onNodeWithText("Import Budget").performClick()
        }
        assertTrue(AccountHubEvent.ToggleBudgetGroup("s1") in events, "a group row opens on click")
        assertTrue(AccountHubEvent.SelectBudgetVersion("b1") in events)
        assertTrue(AccountHubEvent.OpenBudgetImport in events)
    }

    @Test
    fun `the upload step waits for a file, then offers to parse it`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    AccountHubScreen(
                        state = state(BudgetImportState(open = true, acceptsDrops = true)),
                        onEvent = {},
                        canImportBudget = true,
                    )
                }
            }
            onNodeWithText("Drop file here, or click to browse").assertIsDisplayed()
            onNodeWithText("Parse file").assertIsNotEnabled()
        }
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    AccountHubScreen(
                        state = state(
                            BudgetImportState(
                                open = true,
                                picked = PickedAgreementFile("Sound_budget.xlsx", 2_048, "h"),
                                parseError = "The parser timed out.",
                            ),
                        ),
                        onEvent = {},
                        canImportBudget = true,
                    )
                }
            }
            onNodeWithText("Sound_budget.xlsx").assertIsDisplayed()
            onNodeWithText("2.0 KB · Excel workbook").assertIsDisplayed()
            onNodeWithText("The parser timed out.").assertIsDisplayed()
            onNodeWithText("Parse file").assertIsEnabled()
        }
    }

    @Test
    fun `the preview shows the counts, the guidance and the extracted structure`() {
        val events = mutableListOf<AccountHubEvent>()
        val preview = BudgetImportState(
            open = true,
            step = ImportStep.Preview,
            parsed = parsed,
            upload = BudgetUpload(fileName = "Sound_budget.xlsx", detectedFormat = "xlsx"),
            meta = BudgetImportMeta("v3", "Sound budget", "Imported from Sound_budget.xlsx"),
            existing = versions,
            commitError = "Version v3 already exists",
        )
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    AccountHubScreen(state = state(preview), onEvent = { events += it }, canImportBudget = true)
                }
            }
            onNodeWithText("NOMINALS").assertIsDisplayed()
            onAllNodesWithText("1 warning — click to review").onFirst().performClick()
            onNodeWithText("· Duplicate code 1110").assertExists()
            onNodeWithText(
                "Next free version in this project (latest: v2). Continues “Sound budget” — its latest is v1.",
            ).assertExists()
            onNodeWithText("Override existing").performClick()
            onNodeWithText("AWAITING CODE").assertExists()
            onNodeWithText("Import failed").assertExists()
            onNodeWithText("Back").performClick()
        }
        assertTrue(events.any { it is AccountHubEvent.SetCoaImportMode })
        assertTrue(AccountHubEvent.BackToBudgetUpload in events)
    }

    @Test
    fun `the last step names what was imported`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    AccountHubScreen(
                        state = state(
                            BudgetImportState(
                                open = true,
                                step = ImportStep.Done,
                                meta = BudgetImportMeta("v3", "Sound budget", ""),
                            ),
                        ),
                        onEvent = {},
                        canImportBudget = true,
                    )
                }
            }
            onNodeWithText("Budget imported").assertIsDisplayed()
            onNodeWithText("Done").assertIsEnabled()
        }
    }
}
