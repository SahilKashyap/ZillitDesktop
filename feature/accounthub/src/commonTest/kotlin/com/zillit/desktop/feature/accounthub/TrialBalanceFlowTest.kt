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
import com.zillit.desktop.feature.accounthub.domain.ExportFormat
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.HubExportReport
import com.zillit.desktop.feature.accounthub.domain.HubExporter
import com.zillit.desktop.feature.accounthub.domain.HubFiles
import com.zillit.desktop.feature.accounthub.domain.PeriodMode
import com.zillit.desktop.feature.accounthub.domain.TrialBalancePeriod
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubViewModel
import com.zillit.desktop.feature.accounthub.ui.trialBalanceDirty
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
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
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SETTLE_TRIES = 200
private const val SETTLE_STEP_MILLIS = 5L

/**
 * The trial balance driven through the console's own view model over a mock
 * engine — what the page asks the cost-report service for, when, and what it
 * does with the answer. The web pins the first of these itself
 * (`trialBalanceSingleFetch.test.jsx`): one request on open, already carrying
 * the currency.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrialBalanceFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val zone = TimeZone.currentSystemDefault()

    /** Saturday 12 September 2026, mid-morning, in whatever zone the test runs in. */
    private val now = LocalDateTime(2026, 9, 12, 10, 30).toInstant(zone).toEpochMilliseconds()

    /** The query string of every trial-balance request, in order. */
    private val asked = mutableListOf<Parameters>()

    private var lockedThrough: String? = "2026-09-06"
    private var failNext = false
    private var refuseNext = false

    /** The envelope with `data` bare, as develop answers — not `data.value`. */
    private val ledger = """{"status":1,"data":[
        {"account_code":"1000","description":"Bank","cost_type":"asset",
         "debit":"1500.00","credit":0,"ending":"1500.00"},
        {"account_code":"2100","name":"Accruals","cost_type":"liability",
         "debit":0,"credit":1500,"ending":-1500}
    ]}"""

    private val stale = """{"status":1,"data":[
        {"account_code":"9999","name":"Stale","cost_type":"asset","debit":1,"ending":1}
    ]}"""

    private val currencies = """{"status":1,"data":{"value":{"default":"GBP","currencies":[
        {"code":"GBP","name":"Pound Sterling","symbol":"£","exr":1},
        {"code":"USD","name":"US Dollar","symbol":"$","exr":1.3}
    ]}}}"""

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        val body = when {
            path.endsWith("/trial-balance") -> {
                asked += request.url.parameters
                // A run marked "slow" is held back, so a test can overtake it
                // with a later press.
                val slow = request.url.parameters["account_start"] == "slow"
                if (slow) delay(SLOW_ANSWER_MILLIS)
                if (failNext) {
                    failNext = false
                    return@MockEngine respond(
                        """{"status":0,"message":"trial balance unavailable"}""",
                        HttpStatusCode.InternalServerError,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                when {
                    // A business refusal over a 200 — with an empty list beside it, the
                    // shape that would otherwise read as "nothing posted".
                    refuseNext -> """{"status":0,"message":"period_start must be positive","data":[]}"""
                        .also { refuseNext = false }
                    slow -> stale
                    else -> ledger
                }
            }
            path.endsWith("/lock-period") ->
                """{"status":1,"data":{"value":{"lockedDate":${lockedThrough?.let { "\"$it\"" } ?: "null"}}}}"""
            path.endsWith("/project-currencies") -> currencies
            path.endsWith("/companies") ->
                """{"status":1,"data":{"value":[{"id":"co-1","name":"Zillit Films Ltd"}]}}"""
            else -> """{"status":1,"data":[]}"""
        }
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private var exportedBody: JsonObject? = null
    private var savedFile: String? = null

    private fun viewModel(embeds: Boolean = false): AccountHubViewModel {
        val repository = AccountHubRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ TrialMockEngineFactory(engine) }),
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
            clock = { now },
            exporter = HubExporter { report, _, body ->
                assertEquals(HubExportReport.TrialBalance, report)
                exportedBody = body
                ZillitResult.Success(byteArrayOf(1, 2, 3))
            },
            files = HubFiles { name, _ ->
                savedFile = name
                ZillitResult.Success(Unit)
            },
            projectName = { "The Long Shoot" },
            embedsTools = embeds,
        )
    }

    private fun accountant() = AccountHubViewer.from(
        ProjectPermissions(listOf(access(AccountHubViewer.TOOL_IDENTIFIER), access("purchase_order_tool"))),
        "u1",
        isAccountant = true,
    )

    private fun access(tool: String) =
        ToolAccess(tool, enabled = true, canView = true, canPost = true, canDownload = true)

    private suspend fun TestScope.settle(condition: () -> Boolean) {
        repeat(SETTLE_TRIES) {
            advanceUntilIdle()
            if (condition()) return
            withContext(Dispatchers.Default) { delay(SETTLE_STEP_MILLIS) }
        }
        advanceUntilIdle()
    }

    private suspend fun TestScope.opened(model: AccountHubViewModel = viewModel()): AccountHubViewModel {
        model.start()
        model.onEvent(AccountHubEvent.Open(HubArea.TrialBalance))
        settle { model.state.value.trialBalance.let { !it.loading && it.applied != null } }
        return model
    }

    private fun startOf(day: String) = LocalDate.parse(day).atStartOfDayIn(zone).toEpochMilliseconds()

    private fun endOf(day: String) = TrialBalancePeriod.endOfDay(day, zone)!!

    // -- opening ----------------------------------------------------------------

    @Test
    fun `opening asks once, after the lock and the currencies, for the current period in the default currency`() =
        runTest(dispatcher) {
            val model = opened()

            assertEquals(1, asked.size, "one request on open, as the web pins it")
            val query = asked.single()
            assertEquals("GBP", query["currency"])
            assertEquals("false", query["zero_accounts"], "false is sent, never left to the server's default")
            assertEquals(startOf("2026-09-06").toString(), query["period_start"], "from the last closed day")
            assertEquals(endOf("2026-09-12").toString(), query["period_end"], "to the end of today")
            assertNull(query["company_id"])
            assertNull(query["account_start"])

            val trial = model.state.value.trialBalance
            assertEquals(2, trial.report.rows.size, "string figures read, no row dropped")
            assertTrue(trial.report.isBalanced)
            assertEquals("GBP", trial.currency)
            assertEquals(
                "2026-01-01" to "2026-09-12",
                trial.fromText to trial.toText,
                "Date Range opens on the year so far",
            )
            assertFalse(model.state.value.trialBalanceDirty)
        }

    /** Nothing closed yet: "Till today", from the web's floor rather than from the start of the year. */
    @Test
    fun `with nothing closed the report runs from the floor`() = runTest(dispatcher) {
        lockedThrough = null
        val model = opened()

        assertEquals(startOf(TrialBalancePeriod.FLOOR).toString(), asked.single()["period_start"])
        assertEquals("Till 12 Sep 2026", model.state.value.trialBalance.applied?.periodLabel)
    }

    // -- filters and refresh ----------------------------------------------------

    @Test
    fun `filters change nothing until refresh, which asks for all of them`() = runTest(dispatcher) {
        val model = opened()

        model.onEvent(AccountHubEvent.EditTrialBalanceAccounts(" 1000 ", "2999"))
        model.onEvent(AccountHubEvent.PickTrialBalanceCompany("co-1"))
        model.onEvent(AccountHubEvent.PickTrialBalanceCurrency("USD"))
        model.onEvent(AccountHubEvent.SetTrialBalanceZeroAccounts(true))
        settle { false }

        assertEquals(1, asked.size, "a filter is a draft")
        assertTrue(model.state.value.trialBalanceDirty, "Refresh shows")

        model.onEvent(AccountHubEvent.RefreshTrialBalance)
        settle { asked.size == 2 && !model.state.value.trialBalance.loading }

        val query = asked.last()
        assertEquals("1000", query["account_start"], "trimmed")
        assertEquals("2999", query["account_end"])
        assertEquals("co-1", query["company_id"])
        assertEquals("USD", query["currency"])
        assertEquals("true", query["zero_accounts"])
        assertFalse(model.state.value.trialBalanceDirty, "what is on screen is what was asked for")
    }

    /**
     * Picking Current Period again used to rebuild the whole query from
     * scratch, quietly dropping the account range, the company, the currency
     * and the zero-accounts choice.
     */
    @Test
    fun `switching the period back and forth keeps every other filter`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.EditTrialBalanceAccounts("1000", "1999"))
        model.onEvent(AccountHubEvent.PickTrialBalanceCompany("co-1"))
        model.onEvent(AccountHubEvent.PickTrialBalanceCurrency("USD"))
        model.onEvent(AccountHubEvent.SetTrialBalanceZeroAccounts(true))

        model.onEvent(AccountHubEvent.SetTrialBalancePeriodMode(PeriodMode.Custom))
        model.onEvent(AccountHubEvent.EditTrialBalanceDates("2026-04-01", "2026-06-30"))
        model.onEvent(AccountHubEvent.SetTrialBalancePeriodMode(PeriodMode.Current))
        settle { false }

        val trial = model.state.value.trialBalance
        assertEquals("1000" to "1999", trial.accountFromText to trial.accountToText)
        assertEquals("co-1", trial.companyId)
        assertEquals("USD", trial.currency)
        assertTrue(trial.includeZeroAccounts)
        assertEquals("2026-04-01" to "2026-06-30", trial.fromText to trial.toText, "the pickers keep what was typed")
    }

    @Test
    fun `a date range is asked for from its first day to the end of its last`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.SetTrialBalancePeriodMode(PeriodMode.Custom))
        model.onEvent(AccountHubEvent.EditTrialBalanceDates("2026-04-01", "2026-06-30"))
        model.onEvent(AccountHubEvent.RefreshTrialBalance)
        settle { asked.size == 2 && !model.state.value.trialBalance.loading }

        assertEquals(startOf("2026-04-01").toString(), asked.last()["period_start"])
        assertEquals(endOf("2026-06-30").toString(), asked.last()["period_end"])
    }

    @Test
    fun `a range that cannot be read is never asked for`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.SetTrialBalancePeriodMode(PeriodMode.Custom))
        model.onEvent(AccountHubEvent.EditTrialBalanceDates("2026-07-01", "2026-06-30"))
        model.onEvent(AccountHubEvent.RefreshTrialBalance)
        settle { false }

        assertEquals(1, asked.size)
    }

    // -- failure and races ------------------------------------------------------

    /** A failed run says so in the table instead of leaving rows that answer other filters. */
    @Test
    fun `a failure replaces the rows with the reason, and trying again recovers`() = runTest(dispatcher) {
        val model = opened()
        failNext = true
        model.onEvent(AccountHubEvent.SetTrialBalanceZeroAccounts(true))
        model.onEvent(AccountHubEvent.RefreshTrialBalance)
        settle { model.state.value.trialBalance.failed }

        val failed = model.state.value.trialBalance
        assertTrue(failed.failed)
        assertFalse(failed.showsRows, "no count, no total, no export over a failed run")
        assertFalse(model.state.value.trialBalanceDirty, "the failed filters are the applied ones, as on the web")

        model.onEvent(AccountHubEvent.RefreshTrialBalance)
        settle { !model.state.value.trialBalance.loading && !model.state.value.trialBalance.failed }

        assertTrue(model.state.value.trialBalance.showsRows)
        assertNull(model.state.value.trialBalance.errorMessage)
    }

    /**
     * A 200 that says `status: 0` is a refusal — the web's client throws on it.
     * Read as data it would be an empty ledger: "No account balances" for a
     * report the server never ran.
     */
    @Test
    fun `a refusal over a 200 is a failure, not an empty ledger`() = runTest(dispatcher) {
        val model = opened()
        refuseNext = true
        model.onEvent(AccountHubEvent.SetTrialBalanceZeroAccounts(true))
        model.onEvent(AccountHubEvent.RefreshTrialBalance)
        settle { model.state.value.trialBalance.failed }

        val refused = model.state.value.trialBalance
        assertTrue(refused.failed)
        assertFalse(refused.showsRows)
        assertNotNull(refused.errorMessage)
    }

    /** The web's `alive` flag: an answer overtaken by a later press never paints over it. */
    @Test
    fun `a slow answer to an earlier press does not overwrite a later one`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.EditTrialBalanceAccounts("slow", ""))
        model.onEvent(AccountHubEvent.SetTrialBalanceZeroAccounts(true))
        model.onEvent(AccountHubEvent.RefreshTrialBalance)
        settle { asked.size == 2 }

        model.onEvent(AccountHubEvent.EditTrialBalanceAccounts("", ""))
        model.onEvent(AccountHubEvent.RefreshTrialBalance)
        settle { asked.size == 3 && !model.state.value.trialBalance.loading }
        withContext(Dispatchers.Default) { delay(SLOW_ANSWER_MILLIS * 2) }
        settle { false }

        val codes = model.state.value.trialBalance.report.rows.map { it.accountCode }
        assertEquals(listOf("1000", "2100"), codes, "the stale answer arrived last and was dropped")
    }

    // -- coming back --------------------------------------------------------------

    /** The web remounts with fresh figures; a period closed meanwhile moves where "current" starts. */
    @Test
    fun `coming back runs the applied filters again from the lock as it now stands`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.EditTrialBalanceAccounts("1000", ""))
        model.onEvent(AccountHubEvent.RefreshTrialBalance)
        settle { asked.size == 2 && !model.state.value.trialBalance.loading }

        lockedThrough = "2026-09-10"
        model.onEvent(AccountHubEvent.Open(HubArea.Vendors))
        model.onEvent(AccountHubEvent.Open(HubArea.TrialBalance))
        settle { asked.size == 3 && !model.state.value.trialBalance.loading }

        assertEquals(startOf("2026-09-10").toString(), asked.last()["period_start"])
        assertEquals("1000", asked.last()["account_start"], "the reader's filters come back with them")
    }

    // -- export ---------------------------------------------------------------------

    /**
     * The body carries the filters the rows answer and the header's words, and
     * leaves a blank filter out rather than sending null — the web's body is
     * serialised from `undefined`, which drops the key.
     */
    @Test
    fun `the export sends what the table shows, without null filters`() = runTest(dispatcher) {
        lockedThrough = null
        val model = opened()
        model.onEvent(AccountHubEvent.PickTrialBalanceCompany("co-1"))
        model.onEvent(AccountHubEvent.RefreshTrialBalance)
        settle { asked.size == 2 && !model.state.value.trialBalance.loading }
        // A draft change after the run must not leak into the file.
        model.onEvent(AccountHubEvent.EditTrialBalanceAccounts("5000", ""))

        model.onEvent(AccountHubEvent.ExportTrialBalance(ExportFormat.Pdf))
        settle { savedFile != null && model.state.value.trialBalance.exporting == null }

        val body = assertNotNull(exportedBody)
        assertEquals(startOf(TrialBalancePeriod.FLOOR), body["period_start"]!!.jsonPrimitive.content.toLong())
        assertEquals(endOf("2026-09-12"), body["period_end"]!!.jsonPrimitive.content.toLong())
        assertEquals("co-1", body["company_id"]!!.jsonPrimitive.content)
        assertEquals("Zillit Films Ltd", body["company_name"]!!.jsonPrimitive.content)
        assertEquals("GBP", body["currency"]!!.jsonPrimitive.content)
        assertEquals("false", body["zero_accounts"]!!.jsonPrimitive.content)
        assertEquals("The Long Shoot", body["project_name"]!!.jsonPrimitive.content)
        assertEquals("Till 12 Sep 2026", body["period_label"]!!.jsonPrimitive.content)
        assertFalse("account_start" in body, "the bar's newer draft is not the table's filter")
        assertFalse("account_end" in body, "blank is left out, not sent as null")
        assertEquals("trial-balance_2026-09-12_1030.pdf", savedFile, "stamped on the local clock, as exportTs is")
    }

    // -- the way back ---------------------------------------------------------------

    @Test
    fun `the report's back arrow returns to the hub, not to the tools grid`() = runTest(dispatcher) {
        val model = opened(viewModel(embeds = true))
        assertNull(model.state.value.embedded, "the report is on screen, not a tool over it")

        model.onEvent(AccountHubEvent.BackToHub)
        settle { model.state.value.area == HubArea.ProductionSetup }

        assertEquals(HubArea.ProductionSetup, model.state.value.area)
        assertEquals(
            "/film-tools/purchase-order",
            model.state.value.embedded?.path,
            "the web's hub lands on Purchase Orders",
        )
    }

    @Test
    fun `without embedding the back arrow opens the console's own landing`() = runTest(dispatcher) {
        val model = opened()

        model.onEvent(AccountHubEvent.BackToHub)
        settle { model.state.value.area == HubArea.ProductionSetup }

        assertEquals(HubArea.ProductionSetup, model.state.value.area)
        assertNull(model.state.value.embedded)
    }

    private companion object {
        const val SLOW_ANSWER_MILLIS = 300L
    }
}

private class TrialMockEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
