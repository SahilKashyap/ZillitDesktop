package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.forms.FormModule
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.accounthub.data.AccountHubRepositoryImpl
import com.zillit.desktop.feature.accounthub.domain.AccountHubViewer
import com.zillit.desktop.feature.accounthub.domain.ApprovalModule
import com.zillit.desktop.feature.accounthub.domain.ApprovalScope
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubViewModel
import com.zillit.desktop.feature.accounthub.ui.BuilderOrigin
import com.zillit.desktop.feature.accounthub.ui.DiscardIntent
import com.zillit.desktop.feature.accounthub.ui.NewFieldDraft
import com.zillit.desktop.feature.accounthub.ui.SectionComposer
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
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
import kotlin.concurrent.Volatile
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

private const val DEFAULTS = """
{"status":1,"data":{"template":[
 {"key":"header","label":"Header","order":1,"system_default":true,
  "fields":[{"order":1,"name":"Vendor","type":"select","label":"vendor",
             "required":false,"system_default":true,"hide":false,"selection_type":"vendor"}]},
 {"key":"extras","label":"Extras","order":2,"system_default":false,
  "fields":[{"order":1,"name":"Budget Code","type":"text","label":"budget_code",
             "required":false,"system_default":false,"hide":false}]}
]}}
"""

/** The production-wide chain only: one level, one Default rule, one approver. */
private const val CHAINS = """
{"status":1,"data":[
 {"id":"cfg-all","module":"purchase_orders","scope":"all",
  "tiers":[{"order":1,"rules":[{"type":"default","user_ids":["u-lead"]}]}]}
]}
"""

/**
 * The form editor's rules, driven through the console's own view model.
 *
 * Over a mock engine rather than a fake repository: the repository is sixty
 * calls wide and this screen uses a handful, and the wire shape is half of
 * what these rules are about.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FormConfigFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /**
     * Every request as "METHOD path", so a test can say what was and was not called.
     *
     * An immutable list swapped on each request, not a mutable one: the engine
     * appends from the client's thread while `settle` reads on the test's, and
     * iterating an `ArrayList` mid-append threw ConcurrentModificationException
     * — a flake that failed the gate one run in three.
     */
    @Volatile
    private var sent: List<String> = emptyList()

    private fun engine() = MockEngine { request: HttpRequestData ->
        val path = request.url.encodedPath
        sent += "${request.method.value} $path"
        respond(
            when {
                path.contains("form-templates") -> DEFAULTS
                path.endsWith("/approval-tiers") && request.method == HttpMethod.Get -> CHAINS
                else -> """{"status":1,"data":[]}"""
            },
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

    private fun AccountHubViewModel.config() = state.value.formConfig

    /** Adds a custom field to the header, which leaves the form with an unsaved edit. */
    private fun AccountHubViewModel.addBudgetHolder() {
        onEvent(AccountHubEvent.EditForm(true))
        onEvent(AccountHubEvent.FocusFormField("header", null))
        onEvent(AccountHubEvent.EditNewFormField(NewFieldDraft(name = "Budget Holder")))
        onEvent(AccountHubEvent.AddFormField)
    }

    @Test
    fun `opening the page reads the module's template`() = runTest(dispatcher) {
        val model = opened()

        val config = model.config()
        assertEquals(FormModule.PurchaseOrders, config.module)
        assertEquals(listOf("header", "extras"), config.template.ordered.map { it.key })
        assertFalse(config.dirty)
        assertTrue(sent.any { it.endsWith("/form-templates") })
    }

    /**
     * Back keeps the edits, as the web's does.
     *
     * The preview then shows them with a banner offering to save or discard,
     * rather than an hour of rearranging vanishing on a Back arrow.
     */
    @Test
    fun `leaving edit mode keeps the unsaved edits`() = runTest(dispatcher) {
        val model = opened()
        model.addBudgetHolder()
        advanceUntilIdle()

        model.onEvent(AccountHubEvent.EditForm(false))
        advanceUntilIdle()

        assertFalse(model.config().editing)
        assertTrue(model.config().dirty)
        assertEquals(2, model.config().template.section("header")!!.fields.size)
    }

    @Test
    fun `discarding asks first, then puts back the saved form`() = runTest(dispatcher) {
        val model = opened()
        model.addBudgetHolder()
        model.onEvent(AccountHubEvent.EditForm(false))

        model.onEvent(AccountHubEvent.AskDiscardFormChanges)
        advanceUntilIdle()
        assertEquals(DiscardIntent.Revert, model.config().discard)
        assertTrue(model.config().dirty)

        model.onEvent(AccountHubEvent.ConfirmDiscardFormChanges)
        advanceUntilIdle()

        assertNull(model.config().discard)
        assertFalse(model.config().dirty)
        assertEquals(1, model.config().template.section("header")!!.fields.size)
    }

    /** A click on the rail cannot lose unsaved work; the web's silently does. */
    @Test
    fun `switching module with unsaved edits asks before leaving`() = runTest(dispatcher) {
        val model = opened()
        model.addBudgetHolder()
        advanceUntilIdle()

        model.onEvent(AccountHubEvent.OpenFormModule(FormModule.CashExpenses))
        advanceUntilIdle()
        assertEquals(FormModule.PurchaseOrders, model.config().module)
        assertEquals(DiscardIntent.Switch(FormModule.CashExpenses), model.config().discard)

        model.onEvent(AccountHubEvent.ConfirmDiscardFormChanges)
        settle { model.config().template.sections.isNotEmpty() && !model.config().loading }

        assertEquals(FormModule.CashExpenses, model.config().module)
        assertFalse(model.config().dirty)
    }

    @Test
    fun `switching module with nothing unsaved loads that module's template`() = runTest(dispatcher) {
        val model = opened()

        model.onEvent(AccountHubEvent.OpenFormModule(FormModule.CashExpenses))
        settle { model.config().module == FormModule.CashExpenses && model.config().template.sections.isNotEmpty() }

        assertEquals(FormModule.CashExpenses, model.config().module)
        assertNull(model.config().discard)
    }

    /**
     * A system field's type is the module's, not the production's.
     *
     * Refused in the handler as well as disabled on the screen: a guard only on
     * the screen is not a guard.
     */
    @Test
    fun `a system field's type cannot be changed`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.EditForm(true))

        model.onEvent(AccountHubEvent.SetFormFieldType("header", "vendor", "date"))
        advanceUntilIdle()

        assertEquals("select", model.config().template.section("header")!!.fields.first().type)
    }

    /** Removing a system field hides it; the section still holds it. */
    @Test
    fun `removing a system field takes it off the form without deleting it`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.EditForm(true))

        model.onEvent(AccountHubEvent.RemoveFormField("header", "vendor"))
        advanceUntilIdle()

        val header = model.config().template.section("header")!!
        assertEquals(1, header.fields.size)
        assertTrue(header.visible.isEmpty())
        assertEquals(listOf("vendor"), header.removed.map { it.label })
    }

    /**
     * The module's forms find their own fields in the section the module put
     * them in, so only a custom field moves between sections.
     */
    @Test
    fun `only a custom field moves to another section`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.EditForm(true))

        model.onEvent(AccountHubEvent.MoveFormFieldToSection("header", "vendor", "extras"))
        advanceUntilIdle()
        assertEquals(listOf("vendor"), model.config().template.section("header")!!.fields.map { it.label })

        model.onEvent(AccountHubEvent.MoveFormFieldToSection("extras", "budget_code", "header"))
        advanceUntilIdle()
        assertEquals(
            listOf("vendor", "budget_code"),
            model.config().template.section("header")!!.ordered.map { it.label },
        )
        // The panel follows the field to where it landed.
        assertEquals("header", model.config().focus?.sectionKey)
    }

    /** A second click on the field that is open closes its panel, as on the web. */
    @Test
    fun `clicking the open field again closes the property panel`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.EditForm(true))
        model.onEvent(AccountHubEvent.ToggleRearrange(true))

        model.onEvent(AccountHubEvent.FocusFormField("header", "vendor"))
        advanceUntilIdle()
        assertNotNull(model.config().focus)
        // The panel and Rearrange share the right-hand side.
        assertFalse(model.config().rearrange)

        model.onEvent(AccountHubEvent.FocusFormField("header", "vendor"))
        advanceUntilIdle()
        assertNull(model.config().focus)
    }

    /** The insert rail above the first section once opened nothing: "at the top" and "not adding" were both null. */
    @Test
    fun `a section added from the top rail lands first`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.EditForm(true))

        model.onEvent(AccountHubEvent.ComposeFormSection(null))
        advanceUntilIdle()
        assertEquals(SectionComposer(afterKey = null), model.config().composer)

        model.onEvent(AccountHubEvent.EditFormSectionName("Approvals"))
        model.onEvent(AccountHubEvent.AddFormSection)
        advanceUntilIdle()

        assertNull(model.config().composer)
        assertEquals("Approvals", model.config().template.ordered.first().label)
        assertEquals(listOf(1, 2, 3), model.config().template.ordered.map { it.order })
    }

    /** A section this production added is renamed in place; a blank name keeps the old one. */
    @Test
    fun `a custom section is renamed in place`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.EditForm(true))

        model.onEvent(AccountHubEvent.RenameFormSection("extras", "Extras"))
        model.onEvent(AccountHubEvent.EditFormSectionRename("   "))
        model.onEvent(AccountHubEvent.SaveFormSectionRename)
        advanceUntilIdle()
        assertEquals("Extras", model.config().template.section("extras")!!.label)
        assertNull(model.config().rename)

        model.onEvent(AccountHubEvent.RenameFormSection("extras", "Extras"))
        model.onEvent(AccountHubEvent.EditFormSectionRename("Production Extras"))
        model.onEvent(AccountHubEvent.SaveFormSectionRename)
        advanceUntilIdle()
        assertEquals("Production Extras", model.config().template.section("extras")!!.label)
    }

    /** The module's own sections keep their names and cannot be removed — the web offers neither. */
    @Test
    fun `a system section is neither renamed nor removed`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.EditForm(true))
        val header = model.config().template.section("header")!!

        model.onEvent(AccountHubEvent.RenameFormSection("header", "Header"))
        model.onEvent(AccountHubEvent.AskRemoveFormSection(header))
        advanceUntilIdle()

        assertNull(model.config().rename)
        assertNull(model.config().removingSection)
    }

    @Test
    fun `dropping a section moves it by key`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.EditForm(true))

        model.onEvent(AccountHubEvent.MoveFormSection("extras", "header"))
        advanceUntilIdle()

        assertEquals(listOf("extras", "header"), model.config().template.ordered.map { it.key })
        assertTrue(model.config().dirty)
    }

    /** Saving sends the whole template and stays in the editor, as the web does. */
    @Test
    fun `saving posts the template and stays in edit mode`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.EditForm(true))
        model.onEvent(AccountHubEvent.RemoveFormField("header", "vendor"))
        advanceUntilIdle()

        model.onEvent(AccountHubEvent.SaveFormTemplate)
        settle { !model.config().dirty && !model.config().saving }

        assertFalse(model.config().dirty)
        assertTrue(model.config().editing)
        assertTrue(sent.any { it == "POST /api/v2/account-hub/form-templates" })
        assertEquals("Form template saved successfully.", model.state.value.notice)
    }

    /** A reset is confirmed before it happens, because it applies at once, for everybody. */
    @Test
    fun `a reset asks first and only then calls the server`() = runTest(dispatcher) {
        val model = opened()

        model.onEvent(AccountHubEvent.AskResetFormTemplate)
        advanceUntilIdle()
        assertTrue(model.config().confirmingReset)
        assertFalse(sent.any { it.endsWith("/form-templates/reset") })

        model.onEvent(AccountHubEvent.DismissResetFormTemplate)
        advanceUntilIdle()
        assertFalse(sent.any { it.endsWith("/form-templates/reset") })

        model.onEvent(AccountHubEvent.AskResetFormTemplate)
        model.onEvent(AccountHubEvent.ConfirmResetFormTemplate)
        settle { sent.any { path -> path.endsWith("/form-templates/reset") } && !model.config().resetting }

        assertTrue(sent.any { it.endsWith("/form-templates/reset") })
        assertFalse(model.config().resetting)
    }

    /**
     * The shortcut from another screen lands on the page and on the right module.
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
        settle { model.config().template.sections.isNotEmpty() }

        assertEquals(HubArea.FormConfig, model.state.value.area)
        assertEquals(FormModule.PurchaseOrders, model.config().module)
    }

    /**
     * "Set Approver Level" opens the Approvers page's own builder, from this
     * page, on the chosen scope — and a department with no chain of its own
     * starts from the production's, as the web's builder does.
     */
    @Test
    fun `set approver level opens the builder on the department, seeded from the default chain`() =
        runTest(dispatcher) {
            val model = opened()

            model.onEvent(AccountHubEvent.OpenApproverScope(true))
            model.onEvent(AccountHubEvent.PickApproverScope(ApprovalScope.Department, "dept-camera"))
            advanceUntilIdle()
            assertTrue(model.config().scopeModal?.canContinue == true)

            model.onEvent(AccountHubEvent.ContinueApproverScope)
            advanceUntilIdle()
            assertNull(model.config().scopeModal)
            settle { model.state.value.approvals.builder != null }

            val builder = model.state.value.approvals.builder
            assertNotNull(builder)
            assertEquals(BuilderOrigin.Forms, builder.origin)
            assertEquals(ApprovalModule.PurchaseOrders, builder.config.module)
            assertEquals(ApprovalScope.Department, builder.config.scope)
            assertEquals("dept-camera", builder.config.departmentId)
            // Not the default chain's id: saving writes the department's own.
            assertEquals("", builder.config.id)
            assertEquals(listOf("u-lead"), builder.config.tiers.single().rules.single().userIds)
            assertNull(model.config().approverLoad)
        }

    /** Backing out while the chain is read leaves no builder behind when the answer lands. */
    @Test
    fun `cancelling while the chain is read opens nothing`() = runTest(dispatcher) {
        val model = opened()

        model.onEvent(AccountHubEvent.OpenApproverScope(true))
        model.onEvent(AccountHubEvent.PickApproverScope(ApprovalScope.All))
        model.onEvent(AccountHubEvent.ContinueApproverScope)
        assertNotNull(model.config().approverLoad)

        model.onEvent(AccountHubEvent.OpenApproverScope(false))
        settle { sent.any { it == "GET /api/v2/account-hub/approval-tiers" } }
        advanceUntilIdle()

        assertNull(model.config().approverLoad)
        assertNull(model.state.value.approvals.builder)
    }

    /** The Approvers page does not show a chain opened from Forms Configuration, nor the reverse. */
    @Test
    fun `a builder opened from forms belongs to the forms page`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.OpenApproverScope(true))
        model.onEvent(AccountHubEvent.PickApproverScope(ApprovalScope.All))
        model.onEvent(AccountHubEvent.ContinueApproverScope)
        settle { model.state.value.approvals.builder != null }

        val builder = model.state.value.approvals.builder!!
        assertEquals(BuilderOrigin.Forms, builder.origin)
        assertEquals("cfg-all", builder.config.id)

        model.onEvent(AccountHubEvent.DismissApprovalConfig)
        advanceUntilIdle()
        assertNull(model.state.value.approvals.builder)
    }
}

/** Its own name because the vendor test's is private to that file. */
private class FormMockEngineFactory(private val engine: MockEngine) :
    HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
