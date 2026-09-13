@file:Suppress("MaxLineLength") // Wire fixtures read best as one JSON line each.

package com.zillit.desktop.feature.dealmemo

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.dealmemo.data.DealFileFetcher
import com.zillit.desktop.feature.dealmemo.data.DealMemoRepositoryImpl
import com.zillit.desktop.feature.dealmemo.data.DealReferenceSource
import com.zillit.desktop.feature.dealmemo.domain.DealExport
import com.zillit.desktop.feature.dealmemo.domain.NoticeGroupKind
import com.zillit.desktop.feature.dealmemo.domain.rates.Branch
import com.zillit.desktop.feature.dealmemo.ui.DealBadgeCounts
import com.zillit.desktop.feature.dealmemo.ui.DealBadgeUnit
import com.zillit.desktop.feature.dealmemo.ui.DealFileSaver
import com.zillit.desktop.feature.dealmemo.ui.DealMemoBadgeSource
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoRoute
import com.zillit.desktop.feature.dealmemo.ui.DealMemoViewModel
import com.zillit.desktop.feature.dealmemo.ui.DealMemoViewer
import com.zillit.desktop.feature.dealmemo.ui.DealTab
import com.zillit.desktop.feature.dealmemo.ui.DealToastTone
import com.zillit.desktop.feature.dealmemo.ui.DealsEvent
import com.zillit.desktop.feature.dealmemo.ui.NoticeTemplateEvent
import com.zillit.desktop.feature.dealmemo.ui.NoticesEvent
import com.zillit.desktop.feature.dealmemo.ui.RatesEvent
import com.zillit.desktop.feature.dealmemo.ui.RatesView
import com.zillit.desktop.feature.dealmemo.ui.SetupGroup
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SETTLE_TRIES = 300
private const val SETTLE_STEP_MILLIS = 5L

/**
 * The deal memo tool driven through its own view model over a mock engine:
 * what it asks the deal-memo service for, and what it does with each answer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DealMemoFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Every request: method, path and body. */
    private val calls = mutableListOf<Triple<HttpMethod, String, JsonObject?>>()

    private var metadataFailures = 0
    private var templates = """{"status":1,"data":[]}"""
    private val template = mutableMapOf<String, String>()
    private var chaseAnswer = """{"status":1,"message":"deal_memo_chased"}"""
    private var slowTerritory: String? = null

    private val deals = """{"status":1,"data":[
        {"_id":"d1","status":"issued","deal_reference":"DRFT-1","user_id":"crew-1","created_at":2,"crew_details":{"crew_name":"Amara Okafor"},"deal":{"end_date":1786492800000}},
        {"_id":"d2","status":"active","deal_reference":"DM-2","user_id":"crew-2","created_at":1,"crew_details":{"crew_name":"Ben Hartley"},"deal":{"end_date":1786492800000,"notice_period":"2_week"}}
    ]}"""

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        val body = (request.body as? TextContent)?.text?.let { Json.parseToJsonElement(it).jsonObject }
        calls += Triple(request.method, path, body)
        val answer = when {
            path.endsWith("/metadata") -> if (metadataFailures-- > 0) {
                """{"status":1}"""
            } else {
                """{"status":1,"data":{"is_approver":true,"approval_tier_configs":[]}}"""
            }
            path.endsWith("/deal-memo/deals") && request.method == HttpMethod.Get -> deals
            path.endsWith("/chase") -> chaseAnswer
            path.contains("/deal-memo/templates/") ->
                template[path.substringAfterLast('/')] ?: """{"status":0,"message":"not_found"}"""
            path.endsWith("/deal-memo/templates") -> templates
            path.endsWith("/branches/covered-territories") -> """{"status":1,"data":["uk","us"]}"""
            path.endsWith("/unions") -> {
                val territory = request.url.parameters["territory"]
                if (territory == slowTerritory) delay(SLOW_MILLIS)
                """{"status":1,"data":[{"_identifier":"u-$territory","name":"Union of $territory"}]}"""
            }
            path.endsWith("/branches") -> {
                val territory = request.url.parameters["territory"]
                """{"status":1,"data":[{"_identifier":"b-$territory","name":"Branch of $territory","union_identifier":"u-$territory","territory":"$territory"}]}"""
            }
            path.endsWith("/agreements/import-defaults") -> """{"status":1,"message":"rates_refreshed"}"""
            path.endsWith("/agreements") ->
                """{"status":1,"data":{"union":[],"emp_statuses":[{"id":"paye","label":"PAYE"}]}}"""
            path.endsWith("/designation-rates") -> """{"status":1,"data":[]}"""
            path.endsWith("/departments") -> """{"status":1,"data":[]}"""
            path.endsWith("/notice-template") ->
                """{"status":1,"data":{"value":"Dear {{crew_name}}, bye {{last_pay_day}}."}}"""
            else -> """{"status":1,"message":"ok","data":{}}"""
        }
        respond(answer, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private val config = AppConfig(
        environment = Environment.Develop,
        services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
        realtime = emptyMap(),
    )

    private val apiClient = ApiClient(
        httpClient = HttpClientFactory.create({ DealMockEngineFactory(engine) }),
        headerProvider = { _, _, _, _ -> emptyMap() },
    )

    private val readTabs = mutableListOf<DealBadgeUnit>()
    private val readDeals = mutableListOf<Pair<DealBadgeUnit, String>>()
    private var savedFile: String? = null

    private fun viewModel(viewer: DealMemoViewer = accountant) = DealMemoViewModel(
        repository = DealMemoRepositoryImpl(
            apiClient,
            config,
            files = DealFileFetcher { _, _ ->
                ZillitResult.Success(byteArrayOf(1))
            },
        ),
        reference = DealReferenceSource(apiClient, config),
        viewer = { viewer },
        badges = object : DealMemoBadgeSource {
            override val counts = MutableStateFlow(DealBadgeCounts())
            override fun readTab(unit: DealBadgeUnit) {
                readTabs += unit
            }
            override fun readDeal(unit: DealBadgeUnit, dealId: String) {
                readDeals += unit to dealId
            }
        },
        files = DealFileSaver { name, _ ->
            savedFile = name
            ZillitResult.Success(Unit)
        },
        clock = { NOW },
        metadataRetryDelays = listOf(10L, 20L, 30L),
    )

    private val accountant = DealMemoViewer(
        "acc",
        "department_accounts",
        hasPostingAccess = false,
        hasViewAccess = false,
        rightsLoaded = true,
    )

    private suspend fun TestScope.settle(condition: () -> Boolean) {
        repeat(SETTLE_TRIES) {
            advanceUntilIdle()
            if (condition()) return
            withContext(Dispatchers.Default) { delay(SETTLE_STEP_MILLIS) }
        }
        advanceUntilIdle()
    }

    private fun count(
        method: HttpMethod,
        suffix: String,
    ) = calls.count { it.first == method && it.second.endsWith(suffix) }

    // -- entry --------------------------------------------------------------------------------

    @Test
    fun `an accountant lands on All Deals, and metadata is retried until it carries data`() = runTest(dispatcher) {
        metadataFailures = 2
        val model = viewModel()
        model.start()
        settle { model.state.value.deals.loaded && model.state.value.metadata.loaded }

        val state = model.state.value
        assertEquals(DealMemoRoute.Tab(DealTab.Deals), state.page)
        assertEquals(2, state.deals.rows.size)
        assertTrue(state.metadata.isApprover)
        assertEquals(3, count(HttpMethod.Get, "/metadata"), "two data-less answers, then the one that counts")
        assertTrue(DealTab.ApprovalQueue in state.visibleTabs)
    }

    @Test
    fun `a crew member lands on My Deal, which reads its tab badge`() = runTest(dispatcher) {
        val crew = DealMemoViewer("crew-1", "department_camera", hasViewAccess = true, rightsLoaded = true)
        val model = viewModel(crew)
        model.start()
        settle { model.state.value.myDeal.loaded }

        assertEquals(DealMemoRoute.Tab(DealTab.MyDeal), model.state.value.page)
        assertEquals(listOf(DealBadgeUnit.MyDeal), readTabs)
        assertEquals(0, count(HttpMethod.Get, "/deal-memo/deals"), "crew never fetch the production's deals")
    }

    // -- list actions ---------------------------------------------------------------------------

    @Test
    fun `chase posts once, toasts the server's message and reloads nothing`() = runTest(dispatcher) {
        val model = viewModel()
        model.start()
        settle { model.state.value.deals.loaded }
        val listReads = count(HttpMethod.Get, "/deal-memo/deals")

        model.onEvent(DealsEvent.Chase(model.state.value.deals.rows.first()))
        settle { model.state.value.toast != null }

        assertEquals(1, count(HttpMethod.Post, "/deals/d1/chase"))
        assertNull(calls.last { it.second.endsWith("/chase") }.third, "a body-less nudge")
        assertEquals(DealToastTone.Success, model.state.value.toast?.tone)
        assertEquals(listReads, count(HttpMethod.Get, "/deal-memo/deals"))
    }

    @Test
    fun `a status-0 refusal over a 200 is an error, not a success`() = runTest(dispatcher) {
        chaseAnswer = """{"status":0,"message":"deal_chase_invalid_status"}"""
        val model = viewModel()
        model.start()
        settle { model.state.value.deals.loaded }

        model.onEvent(DealsEvent.Chase(model.state.value.deals.rows.first()))
        settle { model.state.value.toast != null }

        assertEquals(DealToastTone.Error, model.state.value.toast?.tone)
        assertNull(model.state.value.deals.chasingId)
    }

    @Test
    fun `delete asks first, then deletes, reads the row's badge and reloads`() = runTest(dispatcher) {
        val model = viewModel()
        model.start()
        settle { model.state.value.deals.loaded }
        val deal = model.state.value.deals.rows.first()

        model.onEvent(DealsEvent.AskDelete(deal))
        assertEquals(deal, model.state.value.deals.pendingDelete)
        assertEquals(0, count(HttpMethod.Delete, "/deals/d1"))
        val listReads = count(HttpMethod.Get, "/deal-memo/deals")

        model.onEvent(DealsEvent.ConfirmDelete)
        settle {
            model.state.value.deals.pendingDelete == null && count(HttpMethod.Get, "/deal-memo/deals") > listReads
        }

        assertEquals(1, count(HttpMethod.Delete, "/deals/d1"))
        assertEquals(listOf(DealBadgeUnit.AllDeals to "d1"), readDeals)
    }

    @Test
    fun `an export saves the register under the web's stamped name`() = runTest(dispatcher) {
        val model = viewModel()
        model.start()
        settle { model.state.value.deals.loaded }

        model.onEvent(DealsEvent.Export(DealExport.RegisterExcel))
        settle { savedFile != null && model.state.value.deals.exporting == null }

        assertTrue(savedFile!!.matches(Regex("deal-memo-register_\\d{4}-\\d{2}-\\d{2}_\\d{4}\\.xlsx")), savedFile)
    }

    // -- the create gate --------------------------------------------------------------------------

    @Test
    fun `with no setups the create button opens the setup prompt, not the menu`() = runTest(dispatcher) {
        val model = viewModel()
        model.start()
        settle { model.state.value.templates.rows != null }

        model.onEvent(DealsEvent.RequestCreateMenu)
        settle { model.state.value.deals.setupGate != null }

        assertFalse(model.state.value.deals.createMenuOpen)
        assertNull(model.state.value.deals.setupGate?.group)
        model.onEvent(DealsEvent.OpenSetupFromGate)
        assertEquals(
            DealMemoRoute.TemplateBuilder(from = DealMemoRoute.Tab(DealTab.Deals)),
            model.state.value.route,
            "to the setup builder, never the hub — and Back returns to All Deals",
        )
    }

    @Test
    fun `a union setup opens the menu, and non-union without its own setup is gated`() = runTest(dispatcher) {
        templates = """{"status":1,"data":[{"_id":"t1","name":"Crew union"}]}"""
        template["t1"] = """{"status":1,"data":{"_id":"t1","name":"Crew union","template_data":"{\"territory_union\":{\"agreement_identifier\":\"pact_bectu_mmp\"}}"}}"""
        val model = viewModel()
        model.start()
        settle { model.state.value.templates.rows != null }

        model.onEvent(DealsEvent.RequestCreateMenu)
        settle { model.state.value.deals.createMenuOpen }
        model.onEvent(DealsEvent.CreateFrom(SetupGroup.NonUnion))
        settle { model.state.value.deals.setupGate != null }
        assertEquals(SetupGroup.NonUnion, model.state.value.deals.setupGate?.group)

        model.onEvent(DealsEvent.CloseSetupGate)
        model.onEvent(DealsEvent.CreateFrom(SetupGroup.Union))
        settle { model.state.value.route is DealMemoRoute.QuickDeal }
        assertEquals(
            DealMemoRoute.QuickDeal(SetupGroup.Union, exitTo = DealMemoRoute.Tab(DealTab.Deals)),
            model.state.value.route,
        )
    }

    // -- notices ------------------------------------------------------------------------------------

    @Test
    fun `a notice goes out at noon UTC with the letter as edited`() = runTest(dispatcher) {
        val model = viewModel()
        model.start()
        model.onEvent(DealMemoEvent.Navigate(DealMemoRoute.Tab(DealTab.Notices)))
        settle {
            model.state.value.notices.loaded &&model.state.value.notices.template.startsWith("Dear") &&
                DealBadgeUnit.Notices in readTabs
        }

        model.onEvent(NoticesEvent.OpenSend(model.state.value.notices.rows.last()))
        val draft = assertNotNull(model.state.value.notices.send)
        assertEquals("2026-08-12", draft.date, "defaults to the contract's end")
        assertEquals("Dear Ben Hartley, bye 12 Aug 2026.", draft.body)

        model.onEvent(NoticesEvent.EditSendDate("2026-08-14"))
        assertEquals(
            "Dear Ben Hartley, bye 14 Aug 2026.",
            model.state.value.notices.send?.body,
            "refills until someone types",
        )
        model.onEvent(NoticesEvent.EditSendBody("Dear Ben, thank you."))
        model.onEvent(NoticesEvent.EditSendDate("2026-08-15"))
        assertEquals(
            "Dear Ben, thank you.",
            model.state.value.notices.send?.body,
            "an edited letter is never overwritten",
        )

        model.onEvent(NoticesEvent.ConfirmSend)
        settle { model.state.value.notices.send == null }
        val sent = calls.last { it.second.endsWith("/deals/d2/send-notice") }.third!!
        assertEquals(1_786_795_200_000L, sent["last_pay_day"]!!.jsonPrimitive.long, "noon UTC on 15 Aug 2026")
        assertEquals("Dear Ben, thank you.", sent["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `deactivation sends UTC midnight and hides the button for the rest of the session`() = runTest(dispatcher) {
        val model = viewModel()
        model.start()
        model.onEvent(DealMemoEvent.Navigate(DealMemoRoute.Tab(DealTab.Notices)))
        settle { model.state.value.notices.loaded }
        val deal = model.state.value.notices.rows.last()

        model.onEvent(NoticesEvent.OpenDeactivate(deal))
        model.onEvent(NoticesEvent.EditDeactivateDate("2026-08-20"))
        model.onEvent(NoticesEvent.ConfirmDeactivate)
        settle { model.state.value.notices.deactivate == null }

        val body = calls.last { it.second.endsWith("/deals/d2/deactivate") }.third!!
        assertEquals(1_787_184_000_000L, body["last_pay_date"]!!.jsonPrimitive.long)
        assertTrue("d2" in model.state.value.notices.deactivatedHere)
        model.onEvent(NoticesEvent.OpenDeactivate(deal))
        assertNull(model.state.value.notices.deactivate, "the button does not come back")
    }

    @Test
    fun `send all posts one notice per unsent deal in the group`() = runTest(dispatcher) {
        val model = viewModel()
        model.start()
        model.onEvent(DealMemoEvent.Navigate(DealMemoRoute.Tab(DealTab.Notices)))
        settle { model.state.value.notices.loaded }

        model.onEvent(NoticesEvent.OpenSendAll(NoticeGroupKind.WithNotice))
        model.onEvent(NoticesEvent.ConfirmSendAll)
        settle { model.state.value.notices.sendAll == null && !model.state.value.notices.sendingAll }

        assertEquals(1, calls.count { it.second.endsWith("/send-notice") })
    }

    @Test
    fun `the notice template saves wrapped and tidied`() = runTest(dispatcher) {
        val model = viewModel()
        model.start()
        model.onEvent(DealMemoEvent.Navigate(DealMemoRoute.NoticeTemplate))
        settle { model.state.value.noticeTemplate.loaded }

        model.onEvent(NoticeTemplateEvent.Edit("  Dear {{crew_name}}, \n\n\n\nBye.  "))
        assertTrue(model.state.value.noticeTemplate.dirty)
        model.onEvent(NoticeTemplateEvent.Save)
        settle { !model.state.value.noticeTemplate.saving && calls.any { it.first == HttpMethod.Patch } }

        val body = calls.last { it.first == HttpMethod.Patch }.third!!
        assertEquals("Dear {{crew_name}}, \n\nBye.", body["value"]!!.jsonPrimitive.content)
    }

    // -- Global Production Rates -------------------------------------------------------------------

    @Test
    fun `a slow territory never lands under a newer one's heading`() = runTest(dispatcher) {
        val model = viewModel()
        model.start()
        model.onEvent(DealMemoEvent.Navigate(DealMemoRoute.GlobalRates))
        settle { !model.state.value.rates.coveredLoading }
        assertEquals(setOf("uk", "us"), model.state.value.rates.covered)

        slowTerritory = "uk"
        model.onEvent(RatesEvent.SelectTerritory("uk"))
        model.onEvent(RatesEvent.SelectTerritory("us"))
        settle { !model.state.value.rates.unionsLoading && model.state.value.rates.unions.isNotEmpty() }
        withContext(Dispatchers.Default) { delay(SLOW_MILLIS + 50) }
        advanceUntilIdle()

        assertEquals(listOf("u-us"), model.state.value.rates.unions.map { it.identifier })
        assertEquals("us", model.state.value.rates.territoryId)
    }

    @Test
    fun `a branch loads its agreements, its territory's statuses and a scoped rate card`() = runTest(dispatcher) {
        val model = viewModel()
        model.start()
        model.onEvent(DealMemoEvent.Navigate(DealMemoRoute.GlobalRates))
        settle { !model.state.value.rates.coveredLoading }

        model.onEvent(RatesEvent.SelectBranch(Branch("b-uk", "Camera", "u-uk", territory = "uk")))
        settle {
            !model.state.value.rates.ratesLoading &&!model.state.value.rates.agreementsLoading &&
                "uk" in model.state.value.rates.empStatuses
        }

        assertEquals(RatesView.Branch, model.state.value.rates.view)
        val rates = calls.last { it.second.endsWith("/designation-rates") }
        assertEquals("/api/v2/deal-memo/designation-rates", rates.second)
        assertEquals(listOf("PAYE"), model.state.value.rates.empStatuses.getValue("uk").map { it.label })
    }

    @Test
    fun `refresh re-seeds, then resets to the welcome pane but keeps the search`() = runTest(dispatcher) {
        val model = viewModel()
        model.start()
        model.onEvent(DealMemoEvent.Navigate(DealMemoRoute.GlobalRates))
        settle { !model.state.value.rates.coveredLoading }
        model.onEvent(RatesEvent.SidebarSearch("king"))
        model.onEvent(RatesEvent.SelectTerritory("uk"))
        settle { !model.state.value.rates.unionsLoading }

        model.onEvent(RatesEvent.Refresh)
        settle { !model.state.value.rates.refreshing && !model.state.value.rates.coveredLoading }

        assertEquals(1, count(HttpMethod.Post, "/agreements/import-defaults"))
        assertEquals(RatesView.Welcome, model.state.value.rates.view)
        assertEquals("king", model.state.value.rates.sidebarSearch)
        assertEquals(2, count(HttpMethod.Get, "/branches/covered-territories"))
    }

    private companion object {
        const val NOW = 1_786_000_000_000L
        const val SLOW_MILLIS = 150L
    }
}

private class DealMockEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
