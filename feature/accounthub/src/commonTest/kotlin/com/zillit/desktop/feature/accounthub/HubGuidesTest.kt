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
import com.zillit.desktop.feature.accounthub.domain.HubGuides
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Setup → Guide: the web's `GuideModule`, its links and its place in the sidebar. */
@OptIn(ExperimentalCoroutinesApi::class)
class HubGuidesTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun accountant() = AccountHubViewer.from(
        ProjectPermissions(
            listOf(
                ToolAccess(
                    AccountHubViewer.TOOL_IDENTIFIER,
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

    private fun viewModel(projectType: String?): AccountHubViewModel {
        val engine = MockEngine {
            respond(
                """{"status":1,"data":[]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repository = AccountHubRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ GuideMockEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
        )
        return AccountHubViewModel(repository = repository, viewer = { accountant() }, projectType = { projectType })
    }

    @Test
    fun `the Guide row leads the Setup group and Production Setup is still the landing`() {
        val setup = HubNavigation.visibleTo(accountant()).first()
        assertEquals(listOf("guide", "production-setup"), setup.items.map { it.id })
        assertEquals(HubArea.ProductionSetup, HubNavigation.landing(accountant()))
    }

    @Test
    fun `a department user is not offered the Guide`() {
        val department = AccountHubViewer.from(ProjectPermissions(emptyList()), "u2", isAccountant = false)
        assertFalse(HubNavigation.areasFor(department).contains(HubArea.Guide))
    }

    @Test
    fun `the page lists the web's ten modules, grouped as the sidebar groups them, and not itself`() {
        val ids = HubGuides.sections.flatMap { it.items }.map { it.id }
        assertEquals(
            listOf(
                "production-setup", "purchase-orders", "invoices", "card-expenses", "cash-expenses", "payroll",
                "cost-report", "trial-balance", "bible-report", "bank-reconciliation",
            ),
            ids,
        )
        assertEquals(5, HubGuides.sections.size, "Setup, Transactions, Payroll, Reports, Management")
    }

    @Test
    fun `a link carries project_type ahead of the anchor`() {
        assertEquals(
            "https://documentation.zillit.com/?project_type=film#account-hub-purchase-order",
            HubGuides.url("purchase-orders", "film"),
        )
    }

    @Test
    fun `an unknown project type reads default, an odd one is escaped, and an unlisted module has no link`() {
        assertEquals("https://documentation.zillit.com/?project_type=default#invoices", HubGuides.url("invoices", null))
        assertEquals("https://documentation.zillit.com/?project_type=default#invoices", HubGuides.url("invoices", " "))
        assertEquals(
            "https://documentation.zillit.com/?project_type=tv%20%26%20web#trial-balance",
            HubGuides.url("trial-balance", "tv & web"),
        )
        assertNull(HubGuides.url("vendors", "film"))
        assertNull(HubGuides.url("guide", "film"))
    }

    @Test
    fun `a Guide card raises the documentation address for the open production`() = runTest(dispatcher) {
        val model = viewModel("film")
        model.start()
        model.onEvent(AccountHubEvent.Open(HubArea.Guide))
        assertEquals(HubArea.Guide, model.state.value.area)
        model.onEvent(AccountHubEvent.OpenGuide("payroll"))
        assertEquals(
            AccountHubEffect.OpenUrl("https://documentation.zillit.com/?project_type=film#account-hub-payroll"),
            model.effects.first(),
        )
        assertTrue(model.state.value.area == HubArea.Guide, "the console stays where it was")
    }
}

private class GuideMockEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
