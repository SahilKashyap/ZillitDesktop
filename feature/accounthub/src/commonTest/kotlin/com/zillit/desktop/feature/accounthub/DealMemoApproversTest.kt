package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.accounthub.data.AccountHubRepositoryImpl
import com.zillit.desktop.feature.accounthub.domain.AccountHubViewer
import com.zillit.desktop.feature.accounthub.domain.ApprovalModule
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.HubDepartment
import com.zillit.desktop.feature.accounthub.domain.HubUser
import com.zillit.desktop.feature.accounthub.ui.AccountHubEffect
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubViewModel
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SETTLE_TRIES = 200
private const val SETTLE_STEP_MILLIS = 5L

/**
 * Deal Memo's own Approvers page: the console's screen locked to the Deal Memo
 * chain, editable by the people the host vouches for (`ApproversModule`'s
 * `canEdit`) and by nobody else outside accounts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DealMemoApproversTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val modules = mutableListOf<String>()

    private val chain =
        """{"id":"cfg-all","module":"deal_memo","scope":"all",""" +
            """"tiers":[{"order":1,"rules":[{"type":"default","user_ids":["u1"]}]}]}"""

    private fun viewModel(viewer: AccountHubViewer): AccountHubViewModel {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            if (path.endsWith("/approval-tiers")) modules += request.url.parameters["module"].orEmpty()
            val body = when {
                path.endsWith("/approval-tiers") -> """{"status":1,"data":[$chain]}"""
                path.endsWith("/access/users") -> """{"status":1,"data":["u2"]}"""
                else -> """{"status":1,"data":[]}"""
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val repository = AccountHubRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ DealMemoApproversEngineFactory(engine) }),
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
            viewer = { viewer },
            users = { listOf(HubUser("u1", "Asha Rao"), HubUser("u2", "Ben Cole")) },
            departmentList = { listOf(HubDepartment("d1", "Camera")) },
        )
    }

    private suspend fun TestScope.settle(condition: () -> Boolean) {
        repeat(SETTLE_TRIES) {
            advanceUntilIdle()
            if (condition()) return
            withContext(Dispatchers.Default) { delay(SETTLE_STEP_MILLIS) }
        }
        advanceUntilIdle()
    }

    /** A posting user outside accounts: the one Deal Memo vouches for. */
    private fun poster(hostEdit: Boolean) = AccountHubViewer(
        userId = "u2",
        isAccountant = false,
        canView = false,
        canPost = true,
        ready = true,
        approvalsHostEdit = hostEdit,
    )

    @Test
    fun `the right to edit approvals is the accountant's or the host's, nobody else's`() {
        assertFalse(poster(hostEdit = false).canEditApprovals)
        assertTrue(poster(hostEdit = true).canEditApprovals)
        assertTrue(AccountHubViewer(isAccountant = true, canPost = true).canEditApprovals)
        // Vouching widens editing of approvals alone: everything else stays accountant-only.
        assertFalse(poster(hostEdit = true).canActAsAccountant)
        assertFalse(poster(hostEdit = true).canEdit)
    }

    @Test
    fun `the page opens on the Deal Memo chain whoever the console was last showing`() = runTest(dispatcher) {
        val model = viewModel(poster(hostEdit = true))
        model.openApprovers(ApprovalModule.DealMemo)
        settle { model.state.value.approvals.loadedModule == ApprovalModule.DealMemo }

        val approvals = model.state.value.approvals
        assertEquals(ApprovalModule.DealMemo, approvals.module)
        assertEquals(ApprovalModule.DealMemo, approvals.loadedModule)
        assertEquals(HubArea.Approvers, model.state.value.area)
        assertEquals("cfg-all", approvals.defaultConfig?.id)
        assertEquals("deal_memo", modules.first())
    }

    @Test
    fun `a vouched-for posting user can open the default chain's editor`() = runTest(dispatcher) {
        val model = viewModel(poster(hostEdit = true))
        model.openApprovers(ApprovalModule.DealMemo)
        settle { model.state.value.approvals.loadedModule == ApprovalModule.DealMemo }

        model.onEvent(AccountHubEvent.EditDefaultApprovals)
        advanceUntilIdle()
        assertNotNull(model.state.value.approvals.builder)
        assertEquals(ApprovalModule.DealMemo, model.state.value.approvals.builder?.config?.module)
    }

    @Test
    fun `without the host's word a posting user outside accounts is refused, with the reason`() = runTest(dispatcher) {
        val model = viewModel(poster(hostEdit = false))
        val effects = mutableListOf<AccountHubEffect>()
        val watching = launch(start = CoroutineStart.UNDISPATCHED) { model.effects.collect { effects += it } }
        model.openApprovers(ApprovalModule.DealMemo)
        settle { model.state.value.approvals.loadedModule == ApprovalModule.DealMemo }

        model.onEvent(AccountHubEvent.EditDefaultApprovals)
        advanceUntilIdle()

        assertNull(model.state.value.approvals.builder)
        assertTrue(effects.any { it is AccountHubEffect.Failed }, "the refusal names itself")
        watching.cancel()
    }
}

private class DealMemoApproversEngineFactory(private val engine: MockEngine) :
    HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
