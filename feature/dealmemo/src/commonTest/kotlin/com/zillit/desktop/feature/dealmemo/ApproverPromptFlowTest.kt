@file:Suppress("MaxLineLength") // Wire fixtures read best as one JSON line each.

package com.zillit.desktop.feature.dealmemo

import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.dealmemo.data.DealMemoRepositoryImpl
import com.zillit.desktop.feature.dealmemo.data.DealReferenceSource
import com.zillit.desktop.feature.dealmemo.data.DealSyncEnvelope
import com.zillit.desktop.feature.dealmemo.domain.DealMemoRepository
import com.zillit.desktop.feature.dealmemo.domain.DealRefresh
import com.zillit.desktop.feature.dealmemo.domain.DealRefreshKey
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEffect
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoRoute
import com.zillit.desktop.feature.dealmemo.ui.DealMemoViewModel
import com.zillit.desktop.feature.dealmemo.ui.DealMemoViewer
import com.zillit.desktop.feature.dealmemo.ui.DealTab
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SETTLE_TRIES = 300
private const val SETTLE_STEP_MILLIS = 5L

/**
 * What Deal Memo does about approvers that are not set up: it reads the tier
 * configs fresh for All Deals' banner, re-reads `/metadata` when tiers change
 * anywhere, and hands the Approvers page to the host — for posting users only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApproverPromptFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val paths = mutableListOf<String>()
    private val queries = mutableListOf<String>()
    private var tiersFail = false
    private var chain = """[{"scope":"all","tiers":[]}]"""
    private var metadataChain = """[]"""

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        paths += path
        if (path.endsWith("/approval-tiers")) queries += request.url.encodedQuery
        val answer = when {
            path.endsWith("/approval-tiers") && tiersFail -> null
            path.endsWith("/approval-tiers") -> """{"status":1,"data":$chain}"""
            path.endsWith("/metadata") ->
                """{"status":1,"data":{"is_approver":false,"approvers_configured":false,"approval_tier_configs":$metadataChain}}"""
            path.endsWith("/deal-memo/deals") ->
                """{"status":1,"data":[{"_id":"d1","status":"awaiting_approval","deal_reference":"DM-1","department_id":"dA","crew_details":{"crew_name":"Amara Okafor"}}]}"""
            path.endsWith("/departments") -> """{"status":1,"data":[]}"""
            else -> """{"status":1,"message":"ok","data":{}}"""
        }
        if (answer == null) {
            respond("{}", HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType, "application/json"))
        } else {
            respond(answer, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }

    private val config = AppConfig(
        environment = Environment.Develop,
        services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
        realtime = emptyMap(),
    )

    private val apiClient = ApiClient(
        httpClient = HttpClientFactory.create({ ApproverMockEngineFactory(engine) }),
        headerProvider = { _, _, _, _ -> emptyMap() },
    )

    private val refreshes = MutableSharedFlow<DealRefresh>(extraBufferCapacity = 8)

    private fun viewModel(viewer: DealMemoViewer = accountant): DealMemoViewModel {
        val impl = DealMemoRepositoryImpl(apiClient, config)
        val repository = object : DealMemoRepository by impl {
            override val refreshes: Flow<DealRefresh> get() = this@ApproverPromptFlowTest.refreshes
        }
        return DealMemoViewModel(
            repository = repository,
            reference = DealReferenceSource(apiClient, config),
            viewer = { viewer },
            clock = { NOW },
            metadataRetryDelays = listOf(10L, 20L, 30L),
        )
    }

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

    private fun reads(suffix: String) = paths.count { it.endsWith(suffix) }

    @Test
    fun `All Deals reads the deal_memo tier configs after the deals load`() = runTest(dispatcher) {
        val model = viewModel()
        model.start()
        settle { model.state.value.deals.tierConfigs != null }

        val deals = model.state.value.deals
        assertEquals(1, deals.rows.size)
        assertEquals(listOf("module=deal_memo"), queries)
        assertEquals(1, deals.tierConfigs?.size)
        assertEquals(false, deals.tiersFailed)
        assertEquals("/api/v2/account-hub/approval-tiers", paths.first { it.endsWith("/approval-tiers") })
    }

    @Test
    fun `a failed tiers read is silent, and flags the metadata fallback`() = runTest(dispatcher) {
        tiersFail = true
        val model = viewModel()
        model.start()
        settle { model.state.value.deals.tiersFailed }

        val state = model.state.value
        assertTrue(state.deals.tiersFailed)
        assertNull(state.deals.tierConfigs)
        assertNull(state.toast, "the banner never toasts")
        assertEquals(false, state.metadata.approversConfigured)
    }

    @Test
    fun `tier events re-read the metadata, and the banner's configs while All Deals is open`() = runTest(dispatcher) {
        val model = viewModel()
        model.start()
        settle { model.state.value.deals.tierConfigs != null && model.state.value.metadata.loaded }
        val metadataReads = reads("/metadata")
        val tierReads = reads("/approval-tiers")

        chain = """[{"scope":"all","tiers":[{"order":1,"rules":[{"type":"default","user_ids":["u1"]}]}]}]"""
        metadataChain = chain
        refreshes.emit(DealRefresh(setOf(DealRefreshKey.ApprovalTiers)))
        settle { model.state.value.metadata.approvalTierConfigs.isNotEmpty() }

        assertEquals(metadataReads + 1, reads("/metadata"))
        assertEquals(tierReads + 1, reads("/approval-tiers"))
        assertEquals(listOf("u1"), model.state.value.metadata.approvalTierConfigs.single().tiers.single().userIds)
        assertEquals(listOf("u1"), model.state.value.deals.tierConfigs?.single()?.tiers?.single()?.userIds)
    }

    @Test
    fun `a tier event while another page is open re-reads only the metadata`() = runTest(dispatcher) {
        val model = viewModel()
        model.start()
        settle { model.state.value.metadata.loaded && model.state.value.deals.tierConfigs != null }
        model.onEvent(DealMemoEvent.Navigate(DealMemoRoute.Tab(DealTab.Notices)))
        settle { model.state.value.notices.loaded }
        val tierReads = reads("/approval-tiers")
        val metadataReads = reads("/metadata")

        refreshes.emit(DealRefresh(setOf(DealRefreshKey.ApprovalTiers)))
        settle { reads("/metadata") > metadataReads }

        assertEquals(tierReads, reads("/approval-tiers"))
    }

    @Test
    fun `starting an already started tool re-reads the metadata, as coming back from Approvers does`() =
        runTest(dispatcher) {
            val model = viewModel()
            model.start()
            settle { model.state.value.metadata.loaded }
            val metadataReads = reads("/metadata")

            model.start()
            settle { reads("/metadata") > metadataReads }

            assertEquals(metadataReads + 1, reads("/metadata"))
        }

    @Test
    fun `the approval tier socket events are announced as the tiers key`() {
        for (event in listOf("approval_tier:configured", "approval_tier:updated", "approval_tier:deleted")) {
            val refresh = DealSyncEnvelope().toRefresh(SocketEventName(event))
            assertEquals(setOf(DealRefreshKey.ApprovalTiers), refresh?.keys, event)
        }
    }

    @Test
    fun `posting users are handed the Approvers page, nobody else`() = runTest(dispatcher) {
        val seen = mutableListOf<DealMemoEffect>()

        val model = viewModel()
        model.start()
        val watching = launch(start = CoroutineStart.UNDISPATCHED) { model.effects.collect { seen += it } }
        model.onEvent(DealMemoEvent.OpenApprovers)
        settle { seen.isNotEmpty() }
        assertEquals(listOf<DealMemoEffect>(DealMemoEffect.OpenApprovers), seen)
        watching.cancel()

        val crew = DealMemoViewer("crew-1", "department_camera", hasViewAccess = true, rightsLoaded = true)
        val crewModel = viewModel(crew)
        crewModel.start()
        val crewSeen = mutableListOf<DealMemoEffect>()
        val crewWatching = launch(start = CoroutineStart.UNDISPATCHED) { crewModel.effects.collect { crewSeen += it } }
        crewModel.onEvent(DealMemoEvent.OpenApprovers)
        advanceUntilIdle()
        assertEquals(emptyList(), crewSeen)
        crewWatching.cancel()
    }

    private companion object {
        const val NOW = 1_786_000_000_000L
    }
}

private class ApproverMockEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
