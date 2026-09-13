package com.zillit.desktop.feature.costreport

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.costreport.data.parseAnalyticsModules
import com.zillit.desktop.feature.costreport.data.parseAnalyticsPage
import com.zillit.desktop.feature.costreport.domain.CrCompany
import com.zillit.desktop.feature.costreport.domain.CrCurrency
import com.zillit.desktop.feature.costreport.domain.CurrencyOptions
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsBlock
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsFilterSource
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsModuleMeta
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsOption
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsPage
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsQuery
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsRepository
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsTab
import com.zillit.desktop.feature.costreport.domain.analytics.BlockHeading
import com.zillit.desktop.feature.costreport.ui.analytics.AnalyticsEffect
import com.zillit.desktop.feature.costreport.ui.analytics.AnalyticsEvent
import com.zillit.desktop.feature.costreport.ui.analytics.AnalyticsPeriod
import com.zillit.desktop.feature.costreport.ui.analytics.AnalyticsStatus
import com.zillit.desktop.feature.costreport.ui.analytics.AnalyticsUiState
import com.zillit.desktop.feature.costreport.ui.analytics.AnalyticsViewModel
import com.zillit.desktop.feature.costreport.ui.analytics.TabStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeAnalytics : AnalyticsRepository {
    val moduleCalls = mutableListOf<AnalyticsQuery>()
    val pageCalls = mutableListOf<Triple<String, AnalyticsQuery, String?>>()
    var overviewResult: ZillitResult<AnalyticsPage?> = ZillitResult.Success(
        AnalyticsPage(currency = "GBP", blocks = listOf(AnalyticsBlock.Alerts(BlockHeading(), emptyList()))),
    )
    var modulePages: MutableMap<String, ZillitResult<AnalyticsPage?>> = mutableMapOf()

    override suspend fun modules(query: AnalyticsQuery): ZillitResult<List<AnalyticsModuleMeta>> {
        moduleCalls += query
        return ZillitResult.Success(listOf(AnalyticsModuleMeta("payroll", "Payroll", total = 120_000.0)))
    }

    override suspend fun overview(query: AnalyticsQuery): ZillitResult<AnalyticsPage?> {
        pageCalls += Triple("overview", query, null)
        return overviewResult
    }

    override suspend fun module(id: String, query: AnalyticsQuery, sub: String?): ZillitResult<AnalyticsPage?> {
        pageCalls += Triple(id, query, sub)
        val key = if (sub == null) id else "$id?$sub"
        return modulePages[key] ?: ZillitResult.Success(null)
    }
}

private class FakeChoices : AnalyticsFilterSource {
    override suspend fun currencies() =
        CurrencyOptions(listOf(CrCurrency("USD", symbol = "$"), CrCurrency("GBP", symbol = "£")), "USD")
    override suspend fun companies() = listOf(CrCompany("c1", "Gilded Hour Films", "UK"))
    override suspend fun departments() = listOf(AnalyticsOption("d1", "Camera"), AnalyticsOption("d2", "Grip"))
    override suspend fun units() = listOf(AnalyticsOption("u1", "Main Unit"))
}

@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun tabbed(): AnalyticsPage = AnalyticsPage(
        id = "payroll",
        title = "Payroll",
        currency = "USD",
        tabs = listOf(AnalyticsTab("ots", "OTs"), AnalyticsTab("pen", "Penalties")),
    )

    @Test
    fun firstLoadSendsTheProjectDefaultCurrencyAndFillsTheChoices() = runTest(dispatcher) {
        val repo = FakeAnalytics()
        val vm = AnalyticsViewModel(repo, FakeChoices())
        vm.start()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(AnalyticsStatus.Ready, state.status)
        assertEquals("USD", state.applied?.currency)
        assertEquals(AnalyticsQuery(currency = "USD"), repo.moduleCalls.single())
        assertEquals("overview", repo.pageCalls.single().first)
        assertEquals("Gilded Hour Films (UK)", state.options.entities.single().label)
        assertEquals(listOf("USD · $", "GBP · £"), state.options.currencies.map { it.chipLabel })
        assertEquals(1, state.modules.size)
        assertEquals("Overview", state.titleWord)
    }

    @Test
    fun filterPicksFetchNothingUntilDoneAndAnUnchangedSelectionNeverRefetches() = runTest(dispatcher) {
        val repo = FakeAnalytics()
        val vm = AnalyticsViewModel(repo, FakeChoices())
        vm.start()
        advanceUntilIdle()

        vm.onEvent(AnalyticsEvent.ToggleFilters)
        vm.onEvent(AnalyticsEvent.SetDepartments(listOf("d2", "d1")))
        vm.onEvent(AnalyticsEvent.ToggleUnit("u1"))
        vm.onEvent(AnalyticsEvent.SetEntity("c1"))
        vm.onEvent(AnalyticsEvent.SetCurrency("GBP"))
        vm.onEvent(AnalyticsEvent.SetPeriod(AnalyticsPeriod.Range))
        vm.onEvent(AnalyticsEvent.SetDateFrom("2026-05-01"))
        vm.onEvent(AnalyticsEvent.SetDateTo("2026-05-31"))
        advanceUntilIdle()
        assertEquals(1, repo.moduleCalls.size)
        assertEquals(6, vm.state.value.draft.activeCount(vm.state.value.options))

        vm.onEvent(AnalyticsEvent.ApplyFilters)
        advanceUntilIdle()
        val query = repo.moduleCalls.last()
        assertEquals(
            mapOf(
                "currency" to "GBP",
                "depts" to "d2,d1",
                "units" to "u1",
                "entity" to "c1",
                "from" to "1777593600000",
                "to" to "1780271999000",
            ),
            query.parameters(),
        )
        assertEquals(false, vm.state.value.filtersOpen)

        vm.onEvent(AnalyticsEvent.ToggleFilters)
        vm.onEvent(AnalyticsEvent.ApplyFilters)
        advanceUntilIdle()
        assertEquals(2, repo.moduleCalls.size)

        // The backdrop closes without applying; the picks stay.
        vm.onEvent(AnalyticsEvent.ToggleFilters)
        vm.onEvent(AnalyticsEvent.ResetFilters)
        vm.onEvent(AnalyticsEvent.CloseFilters)
        advanceUntilIdle()
        assertEquals(2, repo.moduleCalls.size)
        assertEquals(0, vm.state.value.draft.activeCount(vm.state.value.options))
    }

    @Test
    fun aModuleLoadsItsFirstTabLazilyAndCachesEachTab() = runTest(dispatcher) {
        val repo = FakeAnalytics()
        repo.modulePages["payroll"] = ZillitResult.Success(tabbed())
        repo.modulePages["payroll?ots"] =
            ZillitResult.Success(AnalyticsPage(blocks = listOf(AnalyticsBlock.KpiRow(emptyList()))))
        repo.modulePages["payroll?pen"] = ZillitResult.Failure(ZillitError.NoConnection("offline"))
        val vm = AnalyticsViewModel(repo, FakeChoices())
        vm.start()
        advanceUntilIdle()

        vm.onEvent(AnalyticsEvent.Select("payroll"))
        advanceUntilIdle()
        var state = vm.state.value
        assertEquals(AnalyticsStatus.Ready, state.status)
        assertEquals("ots", state.activeTab)
        assertEquals(TabStatus.Idle, state.tabStatus)
        assertEquals(1, state.activeTabBlocks?.size)
        assertEquals(listOf(null, "ots"), repo.pageCalls.filter { it.first == "payroll" }.map { it.third })

        vm.onEvent(AnalyticsEvent.SelectTab("pen"))
        advanceUntilIdle()
        assertEquals(TabStatus.Error, vm.state.value.tabStatus)

        vm.onEvent(AnalyticsEvent.SelectTab("ots"))
        advanceUntilIdle()
        state = vm.state.value
        assertEquals(TabStatus.Idle, state.tabStatus)
        // Cached: no third call for the first tab.
        assertEquals(1, repo.pageCalls.count { it.third == "ots" })
    }

    @Test
    fun emptyAndFailedPagesSayWhyAndRetryRereads() = runTest(dispatcher) {
        val repo = FakeAnalytics()
        repo.modulePages["po"] = ZillitResult.Success(AnalyticsPage(id = "po"))
        val vm = AnalyticsViewModel(repo, FakeChoices())
        vm.start()
        advanceUntilIdle()

        vm.onEvent(AnalyticsEvent.Select("po"))
        advanceUntilIdle()
        assertEquals(AnalyticsStatus.Empty, vm.state.value.status)
        assertEquals("Purchase Orders", vm.state.value.titleWord)

        repo.overviewResult =
            ZillitResult.Failure(ZillitError.Http(status = 500, serverMessage = "Analytics service unavailable"))
        vm.onEvent(AnalyticsEvent.Select(AnalyticsUiState.OVERVIEW))
        advanceUntilIdle()
        assertEquals(AnalyticsStatus.Error, vm.state.value.status)
        assertTrue(vm.state.value.error.orEmpty().isNotBlank())

        repo.overviewResult = ZillitResult.Success(null)
        vm.onEvent(AnalyticsEvent.Retry)
        advanceUntilIdle()
        assertEquals(AnalyticsStatus.Empty, vm.state.value.status)
        assertNull(vm.state.value.page)
    }

    @Test
    fun backAndLinksAreHandedToTheHost() = runTest(dispatcher) {
        val vm = AnalyticsViewModel(FakeAnalytics(), FakeChoices())
        val effects = mutableListOf<AnalyticsEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        runCurrent()
        vm.onEvent(AnalyticsEvent.Back)
        vm.onEvent(AnalyticsEvent.OpenLink("/film-tools/purchase-order"))
        advanceUntilIdle()
        runCurrent()
        assertEquals(AnalyticsEffect.Back, effects.first())
        assertIs<AnalyticsEffect.OpenLink>(effects.last())
    }

    @Test
    fun wireReadsTheBlockModelAndTurnsALegacyOverviewIntoPairedRows() {
        val modules = parseAnalyticsModules(
            Json.parseToJsonElement(
                """[{"id":"payroll","label":"Payroll","tone":"amber","total":"1905000","currency":"GBP","delta":4.2,
                   "deltaFmt":"pct","deltaTone":"red","spark":[1,2,"3"]},{"label":"no id"}]""",
            ),
        )
        assertEquals(1, modules.size)
        assertEquals(1_905_000.0, modules.single().total)
        assertEquals(listOf(1.0, 2.0, 3.0), modules.single().spark)

        val legacy = parseAnalyticsPage(
            Json.parseToJsonElement(
                """{"currency":"GBP","kpis":[{"label":"EFC","value":1090000}],
                   "projection":{"cum":[1,2],"proj":[2,3],"budget":4},
                   "groups":[{"label":"Crew","value":300,"color":"#e8861a"},{"label":"Post","value":200}],
                   "alerts":[{"title":"Camera over","module":"payroll","tone":"red"}],
                   "snapshots":["payroll","po"]}""",
            ),
            overview = true,
        )!!
        assertEquals(4, legacy.blocks.size)
        assertIs<AnalyticsBlock.KpiRow>(legacy.blocks[0])
        val pair = assertIs<AnalyticsBlock.Row>(legacy.blocks[1])
        assertEquals("1.6fr 1fr", pair.cols)
        val donut = assertIs<AnalyticsBlock.Donut>(pair.blocks[1])
        assertEquals(500.0, donut.centerValue)
        assertEquals("COST", donut.centerSub)
        assertIs<AnalyticsBlock.Alerts>(legacy.blocks[2])
        assertEquals(listOf("payroll", "po"), assertIs<AnalyticsBlock.SnapshotCards>(legacy.blocks[3]).modules)

        val module = parseAnalyticsPage(
            Json.parseToJsonElement(
                """{"value":{"id":"po","title":"Purchase Orders","currency":"USD","blocks":[
                   {"type":"table","columns":[{"key":"vendor","label":"Vendor","cell":"avatar"},{"key":"spend","label":"Spend","fmt":"money","align":"right"}],
                    "rows":[{"vendor":"Arri","spend":12000.50,"role":"Camera","flag":true}],"link":{"label":"All vendors"}},
                   {"type":"future-widget","anything":1},
                   {"type":"row","cols":"repeat(auto-fit, minmax(220px, 1fr))","align":"stretch","blocks":[{"type":"hbars","rows":[{"label":"A","value":"5"}]}]},
                   {"type":"gauge-cards","methods":[{"label":"BACS","value":10,"pct":33.6},{"label":"Payroll","value":5,"pct":"12%"}]}
                 ],"tabs":[{"id":"open","label":"Open","blocks":[]},{"id":"closed","label":"Closed"}]}}""",
            ),
        )!!
        assertEquals("USD", module.currency)
        assertEquals(3, module.blocks.size)
        val table = assertIs<AnalyticsBlock.Table>(module.blocks[0])
        assertEquals("12000.5", table.rows.single()["spend"])
        assertNull(table.rows.single()["flag"])
        assertEquals(null, table.link?.href)
        val row = assertIs<AnalyticsBlock.Row>(module.blocks[1])
        assertTrue(row.stretch)
        val gauges = assertIs<AnalyticsBlock.GaugeCards>(module.blocks[2])
        assertEquals(listOf("34%", "12%"), gauges.cards.map { it.pct })
        assertEquals(emptyList(), module.tabs[0].blocks)
        assertNull(module.tabs[1].blocks)
    }
}
