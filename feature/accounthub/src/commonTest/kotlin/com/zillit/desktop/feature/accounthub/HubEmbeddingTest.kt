package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.accounthub.data.AccountHubRepositoryImpl
import com.zillit.desktop.feature.accounthub.domain.AccountHubViewer
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.HubNavigation
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The other film tools inside the console — the web's nested routes.
 *
 * With a host that embeds, a tool row shows the tool in place and the console
 * lands on Purchase Orders as the web does; without one, the same row still
 * hands the tool off to its own window, which is what every earlier release did.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HubEmbeddingTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(embeds: Boolean): AccountHubViewModel {
        val engine = MockEngine {
            respond(
                """{"status":1,"data":[]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repository = AccountHubRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ EmbedMockEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
        )
        return AccountHubViewModel(repository = repository, viewer = { accountant() }, embedsTools = embeds)
    }

    private fun accountant() = AccountHubViewer.from(
        ProjectPermissions(
            listOf(
                access(AccountHubViewer.TOOL_IDENTIFIER),
                access("purchase_order_tool"),
            ),
        ),
        "u1",
        isAccountant = true,
    )

    private fun access(tool: String) =
        ToolAccess(tool, enabled = true, canView = true, canPost = true, canDownload = true)

    private val purchaseOrders get() = HubNavigation.purchaseOrdersRow(accountant())!!

    @Test
    fun `an embedding host lands on Purchase Orders, with the setup area behind it`() = runTest(dispatcher) {
        val model = viewModel(embeds = true)
        model.start()
        val state = model.state.value
        assertEquals("/film-tools/purchase-order", state.embedded?.path)
        assertEquals("Purchase Orders", state.embedded?.title)
        assertEquals(HubArea.ProductionSetup, state.area, "the console's own landing still loads underneath")
    }

    @Test
    fun `a tool row shows the tool in place and a hub row puts it away`() = runTest(dispatcher) {
        val model = viewModel(embeds = true)
        model.start()
        model.onEvent(AccountHubEvent.Open(HubArea.Vendors))
        assertNull(model.state.value.embedded)
        model.onEvent(AccountHubEvent.OpenTool(purchaseOrders))
        assertEquals("/film-tools/purchase-order", model.state.value.embedded?.path)
        model.onEvent(AccountHubEvent.EmbedRoute("/film-tools/purchase-order/queue/my"))
        assertEquals("/film-tools/purchase-order/queue/my", model.state.value.embedded?.path)
        assertEquals("Purchase Orders", model.state.value.embedded?.title, "a sub-route keeps its row's title")
        model.onEvent(AccountHubEvent.CloseEmbedded)
        assertNull(model.state.value.embedded)
    }

    @Test
    fun `without embedding the same row still opens the tool in its own window`() = runTest(dispatcher) {
        val model = viewModel(embeds = false)
        model.start()
        assertNull(model.state.value.embedded)
        model.onEvent(AccountHubEvent.OpenTool(purchaseOrders))
        val effect = model.effects.first()
        assertEquals(AccountHubEffect.OpenTool("/film-tools/purchase-order", "Purchase Orders"), effect)
        assertNull(model.state.value.embedded)
    }
}

private class EmbedMockEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
