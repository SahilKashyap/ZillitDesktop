package com.zillit.desktop.feature.addashboard

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.addashboard.domain.AdCurrencies
import com.zillit.desktop.feature.addashboard.domain.AdDayStatus
import com.zillit.desktop.feature.addashboard.domain.AdReport
import com.zillit.desktop.feature.addashboard.domain.AdShootDay
import com.zillit.desktop.feature.addashboard.domain.AdViewer
import com.zillit.desktop.feature.addashboard.domain.Agency
import com.zillit.desktop.feature.addashboard.domain.AgencyPurchaseOrder
import com.zillit.desktop.feature.addashboard.domain.Artiste
import com.zillit.desktop.feature.addashboard.domain.ArtisteQuery
import com.zillit.desktop.feature.addashboard.domain.ArtisteStatus
import com.zillit.desktop.feature.addashboard.domain.AttendanceStatus
import com.zillit.desktop.feature.addashboard.domain.DayTypeOption
import com.zillit.desktop.feature.addashboard.domain.EngagementType
import com.zillit.desktop.feature.addashboard.domain.NonUnionRates
import com.zillit.desktop.feature.addashboard.domain.PoQueue
import com.zillit.desktop.feature.addashboard.domain.RateConfig
import com.zillit.desktop.feature.addashboard.domain.RateLine
import com.zillit.desktop.feature.addashboard.domain.ReportBlockType
import com.zillit.desktop.feature.addashboard.domain.ReportCell
import com.zillit.desktop.feature.addashboard.domain.ReportColumn
import com.zillit.desktop.feature.addashboard.domain.ReportKind
import com.zillit.desktop.feature.addashboard.domain.ReportPageRow
import com.zillit.desktop.feature.addashboard.domain.ReportRow
import com.zillit.desktop.feature.addashboard.domain.ReportStatus
import com.zillit.desktop.feature.addashboard.domain.ReportSummary
import com.zillit.desktop.feature.addashboard.domain.ReportTemplate
import com.zillit.desktop.feature.addashboard.domain.SupportingArtistDay
import com.zillit.desktop.feature.addashboard.ui.AdDestination
import com.zillit.desktop.feature.addashboard.ui.AdScreen
import com.zillit.desktop.feature.addashboard.ui.AdUiState
import com.zillit.desktop.feature.addashboard.ui.AgencyState
import com.zillit.desktop.feature.addashboard.ui.QueriesState
import com.zillit.desktop.feature.addashboard.ui.RatesState
import com.zillit.desktop.feature.addashboard.ui.ReportEditorState
import com.zillit.desktop.feature.addashboard.ui.ReportsState
import kotlin.test.Test

/**
 * Composes the real dashboard on every page, light and dark.
 *
 * Asserts with `assertExists`, not `assertIsDisplayed`: the test window is
 * small and anything below the first screenful is composed but not displayed.
 */
@OptIn(ExperimentalTestApi::class)
class AdScreenRenderTest {

    private val ad = AdViewer(userId = "u1", canPost = true, ready = true)
    private val shootDate = 1_772_755_200_000L

    private val deal = listOf(
        RateLine(identifier = "costume", label = "Costume", rateAmount = 10.0),
        RateLine(identifier = "travel", label = "Travel", rateAmount = 15.0),
    )

    @Suppress("LongMethod") // One fixture covering every page; split, it would drift per page.
    private fun state(
        destination: AdDestination = AdDestination.TodaysList,
        dayStatus: AdDayStatus = AdDayStatus.InProgress,
        viewer: AdViewer = ad,
        reports: ReportsState = reportsState(),
    ) = AdUiState(
        viewer = viewer,
        destination = destination,
        shootDate = shootDate,
        today = shootDate,
        dayNumber = 12,
        currencies = AdCurrencies(defaultCode = "GBP", symbols = mapOf("GBP" to "£")),
        day = AdShootDay(
            id = "d1",
            shootDate = shootDate,
            dayNumber = 12,
            status = dayStatus,
            unitName = "Main unit",
            callTime = "07:00",
            wrapTime = "19:30",
            poReference = "PO-D12-20260306",
        ),
        dayList = listOf(
            SupportingArtistDay(
                id = "s1",
                artisteId = "a1",
                artisteName = "Ada Lovelace",
                callTime = "07:00",
                wrapTime = "19:30",
                attendance = AttendanceStatus.Present,
                engagement = EngagementType.DirectPaye,
                isExternal = true,
                signStatus = "typed",
                gross = 142.0,
                currency = "GBP",
                dayTypes = listOf(DayTypeOption("SWD")),
                dealAllowances = deal,
                allowances = listOf(deal.first()),
            ),
            SupportingArtistDay(
                id = "s2",
                artisteId = "a2",
                artisteName = "Ravi Menon",
                callTime = "07:05",
                attendance = AttendanceStatus.Present,
                engagement = EngagementType.Agency,
                agencyName = "Scene One Extras",
                agencyId = "v1",
                currency = "GBP",
                gross = 198.0,
            ),
            // Absent, so it lands in Submit Day's exception list rather than
            // on an order — which is what that page is asserted on.
            SupportingArtistDay(
                id = "s3",
                artisteId = "a3",
                artisteName = "Sam Reid",
                attendance = AttendanceStatus.Absent,
                currency = "GBP",
            ),
        ),
        artistes = listOf(
            Artiste(id = "a1", name = "Ada Lovelace", refNumber = "SA-0042", isExternal = true),
            Artiste(id = "a2", name = "Ravi Menon", status = ArtisteStatus.Verified),
        ),
        shootDays = listOf(
            AdShootDay(id = "d1", shootDate = shootDate, dayNumber = 12, status = AdDayStatus.Submitted),
        ),
        agency = AgencyState(rows = listOf(Agency(id = "v1", name = "Scene One Extras", verified = true))),
        queries = QueriesState(rows = listOf(ArtisteQuery(id = "q1", artisteName = "Ada Lovelace"))),
        rates = RatesState(
            config = RateConfig(vendors = listOf(Agency(id = "v1", name = "Scene One Extras"))),
            nonUnion = NonUnionRates(
                dayRates = mapOf("General SA" to "142"),
                dayTypes = listOf(DayTypeOption("SWD", workMinutes = 600)),
                allowances = deal,
            ),
        ),
        poQueue = PoQueue(
            agencyPos = listOf(
                AgencyPurchaseOrder(
                    poId = "po1",
                    poReference = "PO-D12-20260306-1",
                    agency = "Scene One Extras",
                    currency = "GBP",
                    amount = 198.0,
                    status = "PENDING",
                    dayNumber = 12,
                ),
            ),
        ),
        reports = reports,
    )

    private fun reportsState() = ReportsState(
        wrapRows = listOf(
            ReportSummary(
                id = "r1",
                kind = ReportKind.WrapReport,
                name = "Wrap report",
                status = ReportStatus.Draft,
                shootDate = shootDate,
                dayNumber = 12,
            ),
        ),
        exhibitRows = listOf(
            ReportSummary(
                id = "r2",
                kind = ReportKind.ExhibitG,
                name = "Exhibit G",
                status = ReportStatus.Posted,
                shootDate = shootDate,
            ),
        ),
        templates = listOf(
            ReportTemplate(id = "t1", name = "Standard wrap", kind = ReportKind.WrapReport, origin = "zillit"),
        ),
    )

    /** A report open in the editor, with one section and one signing table. */
    private fun editorState(kind: ReportKind = ReportKind.WrapReport) = reportsState().copy(
        editor = ReportEditorState(
            kind = kind,
            report = AdReport(
                id = "r1",
                kind = kind,
                name = "Wrap report",
                shootDate = shootDate,
                pageRows = listOf(
                    ReportPageRow(
                        id = "pr1",
                        cells = listOf(
                            ReportCell(
                                id = "cell1",
                                type = ReportBlockType.Section,
                                title = "Day",
                                columns = listOf(
                                    ReportColumn(id = "c1", label = "Field"),
                                    ReportColumn(id = "c2", label = "Value"),
                                ),
                                rows = listOf(
                                    ReportRow(id = "row1", cells = listOf("Crew Call", "07:00"), field = "call_time"),
                                    ReportRow(id = "row2", cells = listOf("Unit Wrap", "19:30"), field = "wrap_time"),
                                ),
                            ),
                            ReportCell(
                                id = "cell2",
                                type = ReportBlockType.Table,
                                title = "Cast",
                                columns = listOf(
                                    ReportColumn(id = "c3", label = "Cast"),
                                    ReportColumn(id = "c4", label = "Performer signature"),
                                ),
                                rows = listOf(ReportRow(id = "row3", cells = listOf("Ada Lovelace", ""))),
                            ),
                        ),
                    ),
                ),
            ),
            name = "Wrap report",
        ),
    )

    @Test
    fun `every page composes in both themes`() {
        listOf(false, true).forEach { dark ->
            AdDestination.entries.forEach { destination ->
                runComposeUiTest {
                    setContent {
                        ZillitTheme(darkTheme = dark) {
                            AdScreen(state = state(destination), onEvent = {})
                        }
                    }
                    // The page's own heading is in the header; the same words
                    // are in the sidebar row, so the first match is enough.
                    onAllNodesWithText(destination.label, substring = true).onFirst().assertExists()
                }
            }
        }
    }

    @Test
    fun `the day's list leads with who is on it and what they are owed`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) { AdScreen(state = state(), onEvent = {}) }
            }
            onNodeWithText("Ada Lovelace").assertExists()
            onNodeWithText("Ravi Menon").assertExists()
            onNodeWithText("£152.00").assertExists()
        }
    }

    /** A locked day says so once, rather than quietly losing every control. */
    @Test
    fun `a submitted day is announced`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    AdScreen(state = state(dayStatus = AdDayStatus.Submitted), onEvent = {})
                }
            }
            onAllNodesWithText("Submitted", substring = true).onFirst().assertExists()
        }
    }

    /** A viewer who may look but not change is told once, not button by button. */
    @Test
    fun `a reader is told the dashboard is read-only`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    AdScreen(
                        state = state(viewer = AdViewer(userId = "u1", canPost = false, ready = true)),
                        onEvent = {},
                    )
                }
            }
            onAllNodesWithText("not change them", substring = true).onFirst().assertExists()
        }
    }

    @Test
    fun `a viewer with no access gets the shield rather than an empty tool`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    AdScreen(
                        state = state(viewer = AdViewer(userId = "u1", canView = false, ready = true)),
                        onEvent = {},
                    )
                }
            }
            onAllNodesWithText("access", substring = true).onFirst().assertExists()
        }
    }

    @Test
    fun `the submit page shows the agency order and the exceptions`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    AdScreen(state = state(AdDestination.SubmitDay), onEvent = {})
                }
            }
            onAllNodesWithText("Scene One Extras", substring = true).onFirst().assertExists()
            onNodeWithText("PO-D12-20260306-1").assertExists()
        }
    }

    @Test
    fun `the report editor draws its blocks and its signature column`() {
        listOf(ReportKind.WrapReport, ReportKind.ExhibitG).forEach { kind ->
            runComposeUiTest {
                setContent {
                    ZillitTheme(darkTheme = false) {
                        AdScreen(
                            state = state(AdDestination.WrapReport, reports = editorState(kind)),
                            onEvent = {},
                        )
                    }
                }
                onNodeWithText("Crew Call").assertExists()
                onNodeWithText("Unit Wrap").assertExists()
                onAllNodesWithText("Sign here", substring = true).onFirst().assertExists()
            }
        }
    }

    @Test
    fun `rates and config draws every section`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    AdScreen(state = state(AdDestination.Config), onEvent = {})
                }
            }
            onAllNodesWithText("Non-union rates", substring = true).onFirst().assertExists()
            onAllNodesWithText("Registered agencies", substring = true).onFirst().assertExists()
            onAllNodesWithText("Union pay breakdown", substring = true).onFirst().assertExists()
        }
    }

    /** Budget is a placeholder on every client; it must say so rather than look broken. */
    @Test
    fun `budget says it is coming`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    AdScreen(state = state(AdDestination.Budget), onEvent = {})
                }
            }
            onAllNodesWithText("coming", substring = true).onFirst().assertExists()
        }
    }
}
