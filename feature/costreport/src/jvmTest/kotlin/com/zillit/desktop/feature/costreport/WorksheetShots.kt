package com.zillit.desktop.feature.costreport

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.costreport.domain.BudgetVersion
import com.zillit.desktop.feature.costreport.domain.CostLine
import com.zillit.desktop.feature.costreport.domain.CostReportTab
import com.zillit.desktop.feature.costreport.domain.CostReportViewer
import com.zillit.desktop.feature.costreport.domain.CrColumn
import com.zillit.desktop.feature.costreport.domain.CrCompany
import com.zillit.desktop.feature.costreport.domain.CrCurrency
import com.zillit.desktop.feature.costreport.domain.CrLockState
import com.zillit.desktop.feature.costreport.domain.CrOverrides
import com.zillit.desktop.feature.costreport.domain.CrSort
import com.zillit.desktop.feature.costreport.domain.CurrencyOptions
import com.zillit.desktop.feature.costreport.domain.EtcVersion
import com.zillit.desktop.feature.costreport.domain.LiveReport
import com.zillit.desktop.feature.costreport.domain.PostedFilter
import com.zillit.desktop.feature.costreport.domain.SnapshotCadence
import com.zillit.desktop.feature.costreport.domain.SnapshotDetail
import com.zillit.desktop.feature.costreport.domain.SnapshotHeader
import com.zillit.desktop.feature.costreport.domain.SnapshotTotals
import com.zillit.desktop.feature.costreport.domain.VtpBaseline
import com.zillit.desktop.feature.costreport.domain.buildSections
import com.zillit.desktop.feature.costreport.domain.currentWeek
import com.zillit.desktop.feature.costreport.ui.CostReportScreen
import com.zillit.desktop.feature.costreport.ui.CostReportUiState
import com.zillit.desktop.feature.costreport.ui.CurrentCr
import com.zillit.desktop.feature.costreport.ui.PostedCrs
import com.zillit.desktop.feature.costreport.ui.SnapshotView
import com.zillit.desktop.feature.costreport.ui.worksheet.CrFilters
import com.zillit.desktop.feature.costreport.ui.worksheet.CrPane
import com.zillit.desktop.feature.costreport.ui.worksheet.CrReference
import com.zillit.desktop.feature.costreport.ui.worksheet.CrTableView
import com.zillit.desktop.feature.costreport.ui.worksheet.LiveTab
import com.zillit.desktop.feature.costreport.ui.worksheet.PublishForm
import com.zillit.desktop.feature.costreport.ui.worksheet.SnapshotFeed
import com.zillit.desktop.feature.costreport.ui.worksheet.WorksheetModal
import com.zillit.desktop.feature.costreport.ui.worksheet.WorksheetPane
import com.zillit.desktop.feature.costreport.ui.worksheet.WorksheetScreen
import com.zillit.desktop.feature.costreport.ui.worksheet.WorksheetUiState
import java.io.File
import kotlin.test.Test

private const val SHOT_NOW = 1_779_289_200_000L
private const val OUT = "/private/tmp/claude-501/-Users-sahilkashyap-AndroidStudioProjects-Zillit/a808aef0-4627-475a-864e-38862eb3bd58/scratchpad/shots"

class WorksheetShots {

    private val lines = LINES + listOf(
        CostLine(null, name = "Completion Bond", budget = 900.0, id = "b-1", sectionId = "__contractual__"),
        CostLine(null, name = "Production Insurance", budget = 450.0, id = "b-2", sectionId = "__contractual__"),
        CostLine("2120", atd = 950.0, atp = 120.0),
    )
    private val sections = buildSections(COA, lines)
    private val week = currentWeek(SHOT_NOW)
    private val posted = listOf(
        SnapshotHeader("s1", cadence = SnapshotCadence.Weekly, name = "Wk 20 · w/e 16 May 2026", postNote = "Camera overage flagged",
            publishedAtMs = SHOT_NOW - 5 * 86_400_000L, totalVariance = -4200.0, currency = "GBP", reference = "CR-W-2026-W20"),
        SnapshotHeader("s2", cadence = SnapshotCadence.Daily, name = "Daily CR 12 May", publishedAtMs = SHOT_NOW - 8 * 86_400_000L,
            totalVariance = -2100.0, currency = "GBP"),
        SnapshotHeader("s3", cadence = SnapshotCadence.Adhoc, name = "Period Lock — Wk 19 · w/e 09 May 2026", publishedAtMs = SHOT_NOW - 12 * 86_400_000L,
            totalVariance = 150.0, currency = "GBP"),
    )
    private val reference = CrReference(
        loaded = true,
        coa = COA,
        budgets = listOf(BudgetVersion("bv-live", "v1", "Main Budget v1", "LIVE"), BudgetVersion("bv-2", "v2", "Revised v2", "DRAFT")),
        companies = listOf(CrCompany("co-1", "Sunset Films Ltd")),
        currencies = CurrencyOptions(listOf(CrCurrency("GBP", "Pound", "£", 1.0), CrCurrency("USD", "Dollar", "$", 1.25)), "GBP"),
    )
    private val filters = CrFilters(budgetKey = "v1", currency = "GBP")
    private val pane = CrPane(
        pending = filters,
        applied = filters,
        week = week,
        versions = listOf(EtcVersion("ver-1", "End of week 20", 12, SHOT_NOW - 86_400_000L)),
        loadedOnce = true,
        sections = sections,
        serverCurrency = "GBP",
        overrides = CrOverrides(etc = mapOf("1110" to 500.0), vtp = mapOf("2120" to -75.0)),
        baseline = VtpBaseline(mapOf("1110|" to 30.0), hasPrior = true),
    )
    private val base = WorksheetUiState(
        projectName = "Sunset Boulevard",
        generatedBy = "Ada Lovelace · Accountant",
        reference = reference,
        ws = pane,
        live = pane.copy(overrides = CrOverrides.NONE),
        snapshots = SnapshotFeed(rows = posted, loadedOnce = true, filter = PostedFilter.All),
        lock = CrLockState(loaded = true),
    )

    private fun shot(name: String, dark: Boolean = false, content: @androidx.compose.runtime.Composable () -> Unit) {
        val scene = ImageComposeScene(width = 2880, height = 1800, density = Density(2f)) {
            ZillitTheme(darkTheme = dark, animateThemeChange = false) { content() }
        }
        scene.render(0L)
        val image = scene.render(2_000_000_000L)
        File("$OUT/$name.png").writeBytes(image.encodeToData()!!.bytes)
        scene.close()
    }

    private fun worksheet(state: WorksheetUiState, name: String, dark: Boolean = false) = shot(name, dark) {
        WorksheetScreen(state = state, onEvent = {}, nowMillis = { SHOT_NOW }, resolveUser = { "Ada Lovelace · Accountant" })
    }

    @Test
    fun shots() {
        worksheet(base, "01-worksheet")
        worksheet(base, "02-worksheet-dark", dark = true)
        worksheet(base.copy(wsView = CrTableView(sort = CrSort(CrColumn.Atd), decimals = 2), ws = pane.copy(pending = filters.copy(companyId = "co-1"))), "03-worksheet-sorted-dirty")
        worksheet(base.copy(pane = WorksheetPane.Live), "04-live-current")
        worksheet(base.copy(pane = WorksheetPane.Live, liveTab = LiveTab.History), "05-live-history")
        worksheet(base.copy(modal = WorksheetModal.Lock("Week closed after audit")), "06-lock")
        worksheet(base.copy(modal = WorksheetModal.Export()), "07-export")
        worksheet(base.copy(modal = WorksheetModal.Publish(PublishForm(budgetKey = "v1", currency = "GBP", startDate = "2026-05-14", endDate = "2026-05-20", versions = pane.versions))), "08-publish")
        worksheet(base.copy(modal = WorksheetModal.Overages), "09-overages")
        worksheet(base.copy(modal = WorksheetModal.History), "10-history")
        worksheet(base.copy(modal = WorksheetModal.SaveVersion("End of week 21")), "11-save-version")
        worksheet(
            base.copy(
                snapshot = SnapshotView(
                    header = posted.first(),
                    detail = SnapshotDetail(posted.first(), SnapshotTotals(budget = 7177.0, atd = 2715.0, po = 300.0), lines),
                    loading = false,
                    sections = sections,
                    symbol = "£",
                ),
            ),
            "12-snapshot",
        )
        shot("13-crew-current") {
            CostReportScreen(
                state = CostReportUiState(
                    viewer = CostReportViewer(canView = true, ready = true),
                    projectName = "Sunset Boulevard",
                    current = CurrentCr(
                        budgets = reference.budgets,
                        budgetKey = "v1",
                        currencies = reference.currencies.currencies,
                        currencyCode = "GBP",
                        companies = reference.companies,
                        report = LiveReport(lines, currency = "GBP"),
                        sections = sections,
                        week = week,
                        todayMs = SHOT_NOW,
                        symbol = "£",
                    ),
                ),
                onEvent = {},
                resolveUser = { "Ada Lovelace" },
            )
        }
        shot("14-crew-posted") {
            CostReportScreen(
                state = CostReportUiState(
                    viewer = CostReportViewer(canView = true, ready = true),
                    tab = CostReportTab.Posted,
                    projectName = "Sunset Boulevard",
                    posted = PostedCrs(loadedOnce = true, rows = posted),
                ),
                onEvent = {},
                resolveUser = { "Ada Lovelace" },
            )
        }
    }
}
