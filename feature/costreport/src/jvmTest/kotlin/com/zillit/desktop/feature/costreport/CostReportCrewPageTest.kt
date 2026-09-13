package com.zillit.desktop.feature.costreport

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.costreport.domain.BudgetVersion
import com.zillit.desktop.feature.costreport.domain.CostReportTab
import com.zillit.desktop.feature.costreport.domain.CostReportViewer
import com.zillit.desktop.feature.costreport.domain.CrCurrency
import com.zillit.desktop.feature.costreport.domain.LedgerItem
import com.zillit.desktop.feature.costreport.domain.LedgerResult
import com.zillit.desktop.feature.costreport.domain.LedgerType
import com.zillit.desktop.feature.costreport.domain.LiveReport
import com.zillit.desktop.feature.costreport.domain.SnapshotCadence
import com.zillit.desktop.feature.costreport.domain.SnapshotDetail
import com.zillit.desktop.feature.costreport.domain.SnapshotHeader
import com.zillit.desktop.feature.costreport.domain.SnapshotTotals
import com.zillit.desktop.feature.costreport.domain.buildSections
import com.zillit.desktop.feature.costreport.domain.currentWeek
import com.zillit.desktop.feature.costreport.ui.CostReportEvent
import com.zillit.desktop.feature.costreport.ui.CostReportScreen
import com.zillit.desktop.feature.costreport.ui.CostReportUiState
import com.zillit.desktop.feature.costreport.ui.CurrentCr
import com.zillit.desktop.feature.costreport.ui.LedgerView
import com.zillit.desktop.feature.costreport.ui.PostedCrs
import com.zillit.desktop.feature.costreport.ui.SnapshotView
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The crew tool (`/film-tools/cost-report`) with a real-shaped week in it:
 * every surface composes in both themes with large figures, and the clicks
 * that leave the page reach the view model as events. No dev production has
 * the tool switched on for a live check, so this is its verification.
 */
@OptIn(ExperimentalTestApi::class)
class CostReportCrewPageTest {

    private val now = 1_779_289_200_000L

    /** The shared fixture scaled into nine figures, where the grid's column fitting matters. */
    private val lines = LINES.map {
        it.copy(budget = it.budget * 17043.27, atd = it.atd * 4127.33, po = it.po * 1983.1, card = it.card * 811.9)
    }
    private val sections = buildSections(COA, lines)
    private val posted = listOf(
        SnapshotHeader(
            "s1",
            cadence = SnapshotCadence.Weekly,
            name = "Wk 20 · w/e 16 May 2026",
            publishedAtMs = now - 5 * 86_400_000L,
            totalVariance = -4_200_000.0,
            currency = "GBP",
            reference = "CR-W-2026-W20",
        ),
        SnapshotHeader(
            "s2",
            cadence = SnapshotCadence.Daily,
            name = "Daily CR 12 May",
            publishedAtMs = now,
            currency = "GBP",
        ),
    )

    private val current = CostReportUiState(
        viewer = CostReportViewer(canView = true, ready = true),
        projectName = "Sunset Boulevard",
        current = CurrentCr(
            budgets = listOf(BudgetVersion("bv-live", "v1", "Main Budget v1", "LIVE")),
            budgetKey = "v1",
            currencies = listOf(CrCurrency("GBP", "Pound", "£", 1.0)),
            currencyCode = "GBP",
            report = LiveReport(lines, currency = "GBP"),
            sections = sections,
            week = currentWeek(now),
            todayMs = now,
            symbol = "£",
        ),
        posted = PostedCrs(loadedOnce = true, rows = posted),
    )

    private val writers = sections.flatMap { it.headers }.flatMap { it.nominals }.first { it.code == "1110" }

    @Test
    fun everySurfaceComposesInBothThemes() {
        val states = listOf(
            current,
            current.copy(tab = CostReportTab.Posted),
            current.copy(
                snapshot = SnapshotView(
                    header = posted.first(),
                    detail = SnapshotDetail(
                        posted.first(),
                        SnapshotTotals(budget = 122_324_183.31, atd = 467_754.64),
                        lines,
                    ),
                    loading = false,
                    sections = sections,
                    symbol = "£",
                ),
            ),
            current.copy(
                ledger = LedgerView(
                    nominal = writers,
                    type = LedgerType.Actuals,
                    symbol = "£",
                    loading = false,
                    result = LedgerResult(
                        code = "1110",
                        name = "Writers",
                        total = 1_238_199.0,
                        count = 1,
                        items = listOf(
                            LedgerItem("INV", LedgerType.Actuals, "1110", vendor = "Arri Rental", amount = 1_238_199.0),
                        ),
                    ),
                ),
            ),
        )
        states.forEach { shown ->
            listOf(false, true).forEach { dark ->
                runComposeUiTest {
                    setContent {
                        ZillitTheme(darkTheme = dark) {
                            CostReportScreen(state = shown, onEvent = {}, resolveUser = { "Ada Lovelace · Accountant" })
                        }
                    }
                    waitForIdle()
                }
            }
        }
    }

    @Test
    fun clicksReachTheViewModel() = runComposeUiTest {
        val events = mutableListOf<CostReportEvent>()
        setContent {
            ZillitTheme { CostReportScreen(state = current, onEvent = { events += it }, resolveUser = { null }) }
        }

        onNodeWithText("Analytics").performClick()
        onNodeWithText("1110").performClick()
        onAllNodesWithText("STORY & RIGHTS")[0].performClick()
        onNodeWithText("Posted CRs").performClick()

        assertTrue(CostReportEvent.OpenAnalytics in events, "analytics: $events")
        assertTrue(
            events.any { it is CostReportEvent.OpenLedger && it.nominal.code == "1110" && it.column == null },
            "ledger: $events",
        )
        assertTrue(CostReportEvent.ToggleHeader("1100") in events, "header: $events")
        assertTrue(CostReportEvent.SelectTab(CostReportTab.Posted) in events, "tab: $events")
    }
}
