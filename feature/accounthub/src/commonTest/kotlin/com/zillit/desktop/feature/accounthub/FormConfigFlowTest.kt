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
import com.zillit.desktop.core.forms.FormModule
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubViewModel
import com.zillit.desktop.feature.accounthub.ui.NewFieldDraft
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SETTLE_TRIES = 200
private const val SETTLE_STEP_MILLIS = 5L

private const val DEFAULTS = """
{"status":1,"data":{"template":[
 {"key":"header","label":"Header","order":1,"system_default":true,
  "fields":[{"order":1,"name":"Vendor","type":"select","label":"vendor",
             "required":false,"system_default":true,"hide":false,"selection_type":"vendor"}]}
]}}
"""

/**
 * The form editor's guards, driven through the console's own view model.
 *
 * Over a mock engine rather than a fake repository: the repository is sixty
 * calls wide and this screen uses three of them, and the wire shape is half
 * of what these guards are about.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FormConfigFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val sent = mutableListOf<String>()

    /** Every request's path, so a test can say what was and was not called. */
    private fun engine() = MockEngine { request: HttpRequestData ->
        sent += request.url.encodedPath
        respond(
            if (request.url.encodedPath.contains("form-templates")) DEFAULTS else """{"status":1,"data":[]}""",
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    private fun viewModel(): AccountHubViewModel {
        val repository = AccountHubRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ FormMockEngineFactory(engine()) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
        )
        return AccountHubViewModel(repository = repository, viewer = { accountant() })
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

    private suspend fun TestScope.opened(): AccountHubViewModel {
        val model = viewModel()
        model.start()
        model.onEvent(AccountHubEvent.Open(HubArea.FormConfig))
        settle { model.state.value.formConfig.template.sections.isNotEmpty() }
        return model
    }

    @Test
    fun `opening the page reads the module's template`() = runTest(dispatcher) {
        val model = opened()

        val config = model.state.value.formConfig
        assertEquals(FormModule.PurchaseOrders, config.module)
        assertEquals(listOf("header"), config.template.ordered.map { it.key })
        assertFalse(config.dirty)
        assertTrue(sent.any { it.endsWith("/form-templates") })
    }

    /**
     * Cancel means cancel.
     *
     * Leaving edit mode restores what the server last answered with, so a half
     * finished reorder does not sit on screen looking saved.
     */
    @Test
    fun `leaving edit mode throws unsaved changes away`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.EditForm(true))
        model.onEvent(AccountHubEvent.FocusFormField("header", null))
        model.onEvent(AccountHubEvent.EditNewFormField(NewFieldDraft(name = "Budget Code")))
        model.onEvent(AccountHubEvent.AddFormField)
        advanceUntilIdle()
        assertTrue(model.state.value.formConfig.dirty)

        model.onEvent(AccountHubEvent.EditForm(false))
        advanceUntilIdle()

        assertFalse(model.state.value.formConfig.dirty)
        assertEquals(1, model.state.value.formConfig.template.section("header")!!.fields.size)
    }

    /**
     * A system field's type is the module's, not the production's.
     *
     * Refused in the handler as well as hidden on the screen: a guard only on
     * the screen is not a guard.
     */
    @Test
    fun `a system field's type cannot be changed`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.EditForm(true))

        model.onEvent(AccountHubEvent.SetFormFieldType("header", "vendor", "date"))
        advanceUntilIdle()

        val vendor = model.state.value.formConfig.template.section("header")!!.fields.first()
        assertEquals("select", vendor.type)
    }

    /** Removing a system field hides it; the section still holds it. */
    @Test
    fun `removing a system field takes it off the form without deleting it`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.EditForm(true))

        model.onEvent(AccountHubEvent.RemoveFormField("header", "vendor"))
        advanceUntilIdle()

        val header = model.state.value.formConfig.template.section("header")!!
        assertEquals(1, header.fields.size)
        assertTrue(header.visible.isEmpty())
        assertEquals(listOf("vendor"), header.removed.map { it.label })
    }

    /** Saving sends the whole template and clears the unsaved mark. */
    @Test
    fun `saving posts the template and stops calling it dirty`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.EditForm(true))
        model.onEvent(AccountHubEvent.RemoveFormField("header", "vendor"))
        advanceUntilIdle()

        model.onEvent(AccountHubEvent.SaveFormTemplate)
        settle { !model.state.value.formConfig.dirty }

        assertFalse(model.state.value.formConfig.dirty)
        assertFalse(model.state.value.formConfig.editing)
        assertTrue(sent.count { it.endsWith("/form-templates") } >= 2)
    }

    /** A reset is confirmed before it happens, because it applies at once. */
    @Test
    fun `a reset asks first and only then calls the server`() = runTest(dispatcher) {
        val model = opened()

        model.onEvent(AccountHubEvent.AskResetFormTemplate)
        advanceUntilIdle()
        assertTrue(model.state.value.formConfig.confirmingReset)
        assertFalse(sent.any { it.endsWith("/form-templates/reset") })

        model.onEvent(AccountHubEvent.DismissResetFormTemplate)
        advanceUntilIdle()
        assertFalse(sent.any { it.endsWith("/form-templates/reset") })

        model.onEvent(AccountHubEvent.AskResetFormTemplate)
        model.onEvent(AccountHubEvent.ConfirmResetFormTemplate)
        settle { sent.any { path -> path.endsWith("/form-templates/reset") } }

        assertTrue(sent.any { it.endsWith("/form-templates/reset") })
    }

    /**
     * The shortcut from PO Setup lands on the page and on the right module.
     *
     * One event rather than two: opening the page and then switching module
     * would each start a fetch, and the second would arrive over the first.
     */
    @Test
    fun `the PO setup shortcut opens the page on purchase orders`() = runTest(dispatcher) {
        val model = viewModel()
        model.start()
        model.onEvent(AccountHubEvent.Open(HubArea.ProductionSetup))
        settle { model.state.value.area == HubArea.ProductionSetup }

        model.onEvent(AccountHubEvent.OpenFormConfig(FormModule.PurchaseOrders))
        settle { model.state.value.formConfig.template.sections.isNotEmpty() }

        assertEquals(HubArea.FormConfig, model.state.value.area)
        assertEquals(FormModule.PurchaseOrders, model.state.value.formConfig.module)
    }

    /** Switching module re-reads rather than showing the last one's fields. */
    @Test
    fun `switching module loads that module's template`() = runTest(dispatcher) {
        val model = opened()

        model.onEvent(AccountHubEvent.OpenFormModule(FormModule.CashExpenses))
        settle { model.state.value.formConfig.template.sections.isNotEmpty() }

        assertEquals(FormModule.CashExpenses, model.state.value.formConfig.module)
    }
}

/** Its own name because the vendor test's is private to that file. */
private class FormMockEngineFactory(private val engine: MockEngine) :
    HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
