package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.accounthub.data.AccountHubRepositoryImpl
import com.zillit.desktop.feature.accounthub.domain.AccountHubViewer
import com.zillit.desktop.feature.accounthub.domain.BiblePeriod
import com.zillit.desktop.feature.accounthub.domain.ExportFormat
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.HubExportReport
import com.zillit.desktop.feature.accounthub.domain.HubExporter
import com.zillit.desktop.feature.accounthub.domain.HubFiles
import com.zillit.desktop.feature.accounthub.domain.PeriodMode
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubViewModel
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SETTLE_TRIES = 200
private const val SETTLE_STEP_MILLIS = 5L

/**
 * The bible, driven through the console's own view model over a mock engine —
 * the web's `BibleReportModule` from open to export.
 *
 * What matters is what is *asked for*: the period Current means, the filters
 * the query carries, and the filters the export carries — because the file an
 * accountant sends has to be the table they were reading.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BibleReportFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val zone = TimeZone.currentSystemDefault()
    private val today = LocalDate(2026, 9, 12)
    private val noonToday = today.atStartOfDayIn(zone).toEpochMilliseconds() + 12 * 3_600_000L

    /** Every `/bible` request's query string, in order. */
    private val bibleCalls = mutableListOf<Url>()
    private val exported = mutableListOf<Pair<ExportFormat, JsonObject>>()
    private val saved = mutableListOf<String>()

    private val report = """{"status":1,"data":{
        "currency":"GBP","generatedAt":1757700000000,
        "grand_total":-150.5,
        "accounts":[
          {"code":"7100","name":"Camera hire","total":-200.5,"transactions":[
            {"src":"INV","eff_date":1757000000000,"invoice_number":"INV-9","vendor":"Panavision","amount":-250.5},
            {"src":"CRED","eff_date":1757000000000,"vendor":"Panavision","amount":50}]},
          {"code":"__uncoded__","name":"__uncoded__","total":50,"transactions":[
            {"src":"CASH","vendor":"Runner","amount":50}]}
        ],
        "errors":{"payroll":"timed out"}}}"""

    private fun engine(
        lock: String = """{"status":1,"data":{"lockedDate":"2026-09-06","tz":"Europe/London"}}""",
        lockStatus: HttpStatusCode = HttpStatusCode.OK,
        settings: String = """{"status":1,"data":{"settings":{}}}""",
        bible: () -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to report },
    ) = MockEngine { request: HttpRequestData ->
        val path = request.url.encodedPath
        val (status, body) = when {
            path.endsWith("/cost-reports/lock-period") -> lockStatus to lock
            path.endsWith("/account-hub/project-settings") -> HttpStatusCode.OK to settings
            path.endsWith("/project-currencies") ->
                HttpStatusCode.OK to """{"status":1,"data":{"value":{"currencies":[{"code":"GBP","symbol":"£"},
                    {"code":"USD","symbol":"$"}],"default":"GBP"}}}"""
            path.endsWith("/cost-reports/bible") -> {
                bibleCalls += request.url
                bible()
            }
            else -> HttpStatusCode.OK to """{"status":1,"data":[]}"""
        }
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private fun viewModel(engine: MockEngine): AccountHubViewModel {
        val repository = AccountHubRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ BibleMockEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
        )
        return AccountHubViewModel(
            repository = repository,
            viewer = { accountant() },
            clock = { noonToday },
            exporter = HubExporter { report, format, body ->
                assertEquals(HubExportReport.Bible, report)
                exported += format to body
                ZillitResult.Success(byteArrayOf(1, 2, 3))
            },
            files = HubFiles { name, _ ->
                saved += name
                ZillitResult.Success(Unit)
            },
            projectName = { "Zillit Films" },
        )
    }

    private fun accountant() = AccountHubViewer.from(
        ProjectPermissions(
            listOf(
                ToolAccess(
                    identifier = AccountHubViewer.TOOL_IDENTIFIER,
                    enabled = true,
                    canView = true,
                    canPost = true,
                    canDownload = true,
                ),
            ),
        ),
        "u1",
        isAccountant = true,
    )

    private suspend fun TestScope.settle(condition: () -> Boolean) {
        repeat(SETTLE_TRIES) {
            advanceUntilIdle()
            if (condition()) return
            withContext(Dispatchers.Default) { delay(SETTLE_STEP_MILLIS) }
        }
        advanceUntilIdle()
    }

    private suspend fun TestScope.opened(model: AccountHubViewModel): AccountHubViewModel {
        model.start()
        model.onEvent(AccountHubEvent.Open(HubArea.BibleReport))
        settle {
            val bible = model.state.value.bible
            !bible.lockLoading && bible.filters.currency.isNotBlank()
        }
        return model
    }

    private suspend fun TestScope.run(model: AccountHubViewModel) {
        model.onEvent(AccountHubEvent.RunBibleReport)
        settle { !model.state.value.bible.loading }
    }

    /** The web opens on "Set filters and run the report": the heaviest read on the service is never made unasked. */
    @Test
    fun `opening the bible reads the lock and seeds the filters, and runs nothing`() = runTest(dispatcher) {
        val model = opened(viewModel(engine()))
        val bible = model.state.value.bible

        assertTrue(bibleCalls.isEmpty(), "opening must not run the report")
        assertNull(bible.report)
        val lock = model.state.value.periodClose.lock
        assertEquals("2026-09-06", lock.lockedThrough, "read bare, as the service sends it")
        assertEquals(PeriodMode.Current, bible.filters.periodMode)
        assertEquals("2026-01-01", bible.filters.fromDate, "the web's Date Range default: the first of January")
        assertEquals("2026-09-12", bible.filters.toDate)
        assertEquals("GBP", bible.filters.currency, "the production's default, preselected")
        assertTrue(bible.filters.includeOpenPurchaseOrders)
    }

    /** Current Period starts on the lock and ends at the close of today, in the reader's zone. */
    @Test
    fun `a current-period run asks for the lock date through the end of today`() = runTest(dispatcher) {
        val model = opened(viewModel(engine()))
        run(model)

        val call = bibleCalls.single()
        val period = BiblePeriod.current("2026-09-06", today, zone)
        assertEquals(period.startMillis.toString(), call.parameters["period_start"])
        assertEquals(period.endMillis.toString(), call.parameters["period_end"])
        assertEquals("GBP", call.parameters["currency"])
        assertNull(call.parameters["include_open_pos"], "on is the server's default, so it is not restated")

        val bible = model.state.value.bible
        val shown = assertNotNull(bible.report)
        assertEquals(listOf("7100", "__uncoded__"), shown.accounts.map { it.code })
        assertEquals(-150.5, shown.grandTotal)
        assertEquals(mapOf("payroll" to "timed out"), shown.errors)
        assertEquals("6 Sep 2026 – 12 Sep 2026", bible.run?.periodLabel)
        assertNull(bible.error)
    }

    /**
     * The web's `useCrLock` falls back to the settings document, whose date wins
     * when later — the live lock route has failed outright on a string date.
     */
    @Test
    fun `a failing lock route falls back to the settings document's date`() = runTest(dispatcher) {
        val model = opened(
            viewModel(
                engine(
                    lock = """{"status":0,"message":"Invalid time value"}""",
                    lockStatus = HttpStatusCode.InternalServerError,
                    settings = """{"status":1,"data":{"settings":{"last_cr_locked_date":"2026-08-30"}}}""",
                ),
            ),
        )
        assertEquals("2026-08-30", model.state.value.periodClose.lock.lockedThrough)

        run(model)
        val expected = BiblePeriod.current("2026-08-30", today, zone).startMillis
        assertEquals(expected.toString(), bibleCalls.single().parameters["period_start"])
    }

    /** A half-typed Date Range runs nothing — the web disables Run until both dates read. */
    @Test
    fun `a half-typed date range does not run`() = runTest(dispatcher) {
        val model = opened(viewModel(engine()))
        val filters = model.state.value.bible.filters
        val halfTyped = filters.copy(periodMode = PeriodMode.Custom, toDate = "2026-09-1")
        model.onEvent(AccountHubEvent.EditBibleFilters(halfTyped))
        run(model)
        assertTrue(bibleCalls.isEmpty())

        model.onEvent(
            AccountHubEvent.EditBibleFilters(
                filters.copy(periodMode = PeriodMode.Custom, fromDate = "2026-03-01", toDate = "2026-03-31"),
            ),
        )
        run(model)
        val range = BiblePeriod.custom("2026-03-01", "2026-03-31", zone)!!
        assertEquals(range.startMillis.toString(), bibleCalls.single().parameters["period_start"])
        assertEquals(range.endMillis.toString(), bibleCalls.single().parameters["period_end"])
    }

    /** A failed refresh says why above the figures it could not replace, as the web does. */
    @Test
    fun `a failed run keeps the last report and says why`() = runTest(dispatcher) {
        var fail = false
        val model = opened(
            viewModel(
                engine(
                    bible = {
                        if (fail) HttpStatusCode.OK to """{"status":0,"message":"Cost report service unavailable"}"""
                        else HttpStatusCode.OK to report
                    },
                ),
            ),
        )
        run(model)
        fail = true
        run(model)

        val bible = model.state.value.bible
        val error = assertNotNull(bible.error, "a 200 carrying status 0 is a refusal, as the web's client treats it")
        assertTrue(error.contains("unavailable", ignoreCase = true), error)
        assertEquals(2, assertNotNull(bible.report).accounts.size, "the previous run stays on screen")
        assertEquals(2, bibleCalls.size)
    }

    /** The export sends what the table was run with — not a filter touched since — plus the header's words. */
    @Test
    fun `the export sends the run on screen, not the filter bar`() = runTest(dispatcher) {
        val model = opened(viewModel(engine()))
        val filters = model.state.value.bible.filters
        val asked = filters.copy(vendorId = "v-42", includeOpenPurchaseOrders = false)
        model.onEvent(AccountHubEvent.EditBibleFilters(asked))
        run(model)
        // Touched after the run, and never run.
        model.onEvent(AccountHubEvent.EditBibleFilters(model.state.value.bible.filters.copy(vendorId = "v-99")))

        model.onEvent(AccountHubEvent.ExportBible(ExportFormat.Pdf))
        settle { model.state.value.bible.exporting == null && saved.isNotEmpty() }

        val (format, body) = exported.single()
        assertEquals(ExportFormat.Pdf, format)
        assertEquals(JsonPrimitive("v-42"), body["vendor_id"])
        assertEquals(JsonPrimitive(false), body["include_open_pos"])
        assertEquals(JsonPrimitive("Zillit Films"), body["project_name"])
        assertEquals(JsonPrimitive("6 Sep 2026 – 12 Sep 2026"), body["period_label"])
        assertEquals("false", bibleCalls.single().parameters["include_open_pos"])

        val name = saved.single()
        assertTrue(name.startsWith("bible-report_2026-09-12_") && name.endsWith(".pdf"), name)
        assertEquals("Exported $name.", model.state.value.notice)
    }

    /** Folding every account, then opening them all again. */
    @Test
    fun `every account folds and opens together`() = runTest(dispatcher) {
        val model = opened(viewModel(engine()))
        run(model)
        model.onEvent(AccountHubEvent.SetAllBibleAccounts(collapsed = true))
        advanceUntilIdle()
        assertEquals(setOf("7100", "__uncoded__"), model.state.value.bible.collapsed)

        model.onEvent(AccountHubEvent.ToggleBibleAccount("7100"))
        advanceUntilIdle()
        assertEquals(setOf("__uncoded__"), model.state.value.bible.collapsed)

        model.onEvent(AccountHubEvent.SetAllBibleAccounts(collapsed = false))
        advanceUntilIdle()
        assertTrue(model.state.value.bible.collapsed.isEmpty())
    }
}

private class BibleMockEngineFactory(private val engine: MockEngine) :
    HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
