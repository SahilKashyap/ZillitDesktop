package com.zillit.desktop.feature.bankrec

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.bankrec.domain.AuditFilters
import com.zillit.desktop.feature.bankrec.domain.ImportResult
import com.zillit.desktop.feature.bankrec.domain.PickedStatement
import com.zillit.desktop.feature.bankrec.domain.PortalLinkDraft
import com.zillit.desktop.feature.bankrec.domain.QuickAddForm
import com.zillit.desktop.feature.bankrec.ui.AuditLogState
import com.zillit.desktop.feature.bankrec.ui.BankRecScreen
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.BankTab
import com.zillit.desktop.feature.bankrec.ui.DeleteRequest
import com.zillit.desktop.feature.bankrec.ui.ExceptionQuickAddState
import com.zillit.desktop.feature.bankrec.ui.ExportPdfState
import com.zillit.desktop.feature.bankrec.ui.FxPostState
import com.zillit.desktop.feature.bankrec.ui.ImportState
import com.zillit.desktop.feature.bankrec.ui.ImportStep
import com.zillit.desktop.feature.bankrec.ui.LocalBankRecPeople
import com.zillit.desktop.feature.bankrec.ui.MatchProposal
import com.zillit.desktop.feature.bankrec.ui.PeriodDetailState
import com.zillit.desktop.feature.bankrec.ui.SignOffState
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Composes every tab and every dialog, light and dark.
 *
 * Both themes because a colour defined in one and not the other is invisible
 * until somebody switches; everything because a layout that throws — a
 * full-width child inside a row, a list inside a scrolling column, an intrinsic
 * measurement of a table — takes the window down rather than degrading.
 */
@OptIn(ExperimentalTestApi::class)
class BankRecScreenRenderTest {

    private fun render(state: BankRecUiState, dark: Boolean = false, body: ComposeUiTest.() -> Unit) {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = dark, animateThemeChange = false) {
                    CompositionLocalProvider(LocalBankRecPeople provides RenderFixtures.people) {
                        BankRecScreen(state) {}
                    }
                }
            }
            waitForIdle()
            body()
        }
    }

    private fun ComposeUiTest.seen(text: String) {
        val found = onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes()
        assertTrue(found.isNotEmpty(), "expected to find \"$text\" on screen")
    }

    @Test
    fun `every tab composes in both themes`() {
        listOf(false, true).forEach { dark ->
            BankTab.entries.forEach { tab ->
                render(RenderFixtures.state(tab), dark) { seen("Bank Reconciliation") }
            }
        }
    }

    @Test
    fun `the overview shows the current period and its history`() {
        render(RenderFixtures.state(BankTab.Overview)) {
            seen("Bank Balance")
            seen("Zillit Balance")
            seen("Reconciliation History")
            seen("Apr 2025")
        }
    }

    @Test
    fun `the workspace shows both panels and the fraud line`() {
        render(RenderFixtures.state(BankTab.Workspace)) {
            seen("Bank Statement")
            seen("Zillit Ledger")
            seen("Thames Valley Catering")
            seen("Framestore VFX Ltd")
            seen("Quick Entry")
        }
    }

    @Test
    fun `the exceptions tab groups what is open, foreign and actioned`() {
        render(RenderFixtures.state(BankTab.Exceptions)) {
            seen("Not In Zillit")
            seen("FX Variations")
            seen("Previously actioned")
            seen("Quick Add")
            seen("Investigate")
            seen("View FX")
        }
    }

    @Test
    fun `the fraud tab shows the alert, its signals and both actions`() {
        render(RenderFixtures.state(BankTab.FraudAlerts)) {
            seen("Thames Valley Catering")
            seen("Investigated — No Issue")
            seen("Escalate to Finance")
            seen("Audit Log")
        }
    }

    @Test
    fun `the FX tab shows the chart, the table and the journal`() {
        render(RenderFixtures.state(BankTab.FxVariances)) {
            seen("Bank Rates vs Budget")
            seen("Journal Preview")
            seen("Kodak Motion Picture")
            // USD has no rate in Production Setup, so Post All says why it is unavailable.
            seen("Rates missing")
        }
    }

    @Test
    fun `history, open banking and settings compose their content`() {
        render(RenderFixtures.state(BankTab.History)) { seen("Mar 2025") }
        render(RenderFixtures.state(BankTab.OpenBanking)) { seen("Coming soon") }
        render(RenderFixtures.state(BankTab.Settings)) {
            seen("Connected Bank Accounts")
            seen("Auto-Match Rules")
            seen("Fraud Detection Thresholds")
            seen("Split payment threshold")
        }
    }

    @Test
    fun `the portal previews the summary and lists the links`() {
        render(RenderFixtures.state(BankTab.GuarantorPortal)) {
            seen("Bank Reconciliation Summary")
            seen("Signed Off")
            seen("Foreign Currency Payments")
            seen("Sign-off Note")
            seen("James Whitford")
            seen("Re-share")
        }
    }

    @Test
    fun `every dialog composes in both themes`() {
        listOf(false, true).forEach { dark ->
            dialogStates().forEach { (title, state) ->
                render(state, dark) { seen(title) }
            }
        }
    }

    companion object {
        /** Each dialog open over a tab it opens from, with the title it must show. */
        @Suppress("LongMethod") // Test data: one state per dialog.
        fun dialogStates(): List<Pair<String, BankRecUiState>> {
            val f = RenderFixtures
            return listOf(
                "Delete this period?" to f.state(BankTab.History).copy(deleting = DeleteRequest(
                    listOf("p1"),
                    "Apr 2025",
                )),
                "Export Reconciliation PDF" to f.state(BankTab.History).copy(exportPdf = ExportPdfState(setOf("p0"))),
                "Period Details" to f.state(BankTab.History).copy(
                    periodDetail = PeriodDetailState(
                        periodId = "p0",
                        loading = false,
                        preview = f.preview,
                        transactions = f.transactions,
                        ledger = f.ledger,
                    ),
                ),
                "Import Bank Statement" to f.state(BankTab.Overview).copy(
                    import = ImportState(
                        open = true,
                        bankAccountId = "b1",
                        file = PickedStatement("april.csv", ByteArray(2048)),
                    ),
                ),
                "Importing Statement" to f.state(BankTab.Overview).copy(
                    import = ImportState(
                        open = true,
                        bankAccountId = "b1",
                        file = PickedStatement("april.csv", ByteArray(2048)),
                        processing = true,
                        step = ImportStep.Done.ordinal,
                        result = ImportResult(imported = 47, matched = 39, suggested = 3, unmatched = 3, fraud = 2),
                    ),
                ),
                "Fraud Alert — Confirm Match" to f.state(BankTab.Workspace).let {
                    it.copy(workspace = it.workspace.copy(proposal = MatchProposal("t4", "inv-3")))
                },
                "Manual Match" to f.state(BankTab.Workspace).let {
                    it.copy(workspace = it.workspace.copy(manualMatchId = "t6"))
                },
                "Confirm Manual Match" to f.state(BankTab.Workspace).let {
                    it.copy(workspace = it.workspace.copy(manualMatchId = "t6", manualMatchEntryId = "led-3"))
                },
                "Sign Off Reconciliation" to f.state(BankTab.Workspace).let {
                    it.copy(
                        workspace = it.workspace.copy(
                            signOff = SignOffState(note = "Chasing Barclays for the charge."),
                        ),
                    )
                },
                "Quick Add to Zillit Ledger" to f.state(BankTab.Exceptions).copy(
                    exceptionsPage = f.state(BankTab.Exceptions).exceptionsPage.copy(
                        quickAdd = ExceptionQuickAddState(
                            "e1",
                            QuickAddForm(date = "2025-04-07", amount = "35.00", effectiveDate = "2025-03-30"),
                        ),
                    ),
                ),
                "Audit Log" to f.state(BankTab.FraudAlerts).copy(
                    fraudPage = f.state(BankTab.FraudAlerts).fraudPage.copy(
                        audit = AuditLogState(loading = false, entries = f.audit, filters = AuditFilters()),
                    ),
                ),
                "Post FX Variance" to f.state(BankTab.FxVariances).copy(
                    fxPage = f.state(BankTab.FxVariances).fxPage.copy(
                        post = FxPostState(
                            "v2",
                            nominalCode = "7850",
                            costCentre = "",
                            budgetRate = "",
                            bankRate = "1.28",
                        ),
                    ),
                ),
                "Generate Guarantor / Broadcaster Portal Link" to f.state(BankTab.GuarantorPortal).let {
                    it.copy(portal = it.portal.copy(draft = PortalLinkDraft(recipientName = "James", periodId = "p0")))
                },
            )
        }
    }
}
