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
import com.zillit.desktop.feature.accounthub.domain.ApprovalModule
import com.zillit.desktop.feature.accounthub.domain.ApprovalRule
import com.zillit.desktop.feature.accounthub.domain.ApprovalScope
import com.zillit.desktop.feature.accounthub.domain.ApprovalSequence
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.HubDepartment
import com.zillit.desktop.feature.accounthub.domain.HubUser
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubViewModel
import com.zillit.desktop.feature.accounthub.ui.BuilderConfirm
import com.zillit.desktop.feature.accounthub.ui.DepartmentFilter
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SETTLE_TRIES = 200
private const val SETTLE_STEP_MILLIS = 5L

/**
 * The approval-chain editor, driven through the console's view model over a
 * mock engine — the web's `ApproversModule` builder and its `handleSave`.
 *
 * What matters is what reaches the server: which levels, which rules, which
 * people, and whether a department's chain is saved, left alone or removed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApproverBuilderFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Every write, as `METHOD path` plus its body. */
    private val writes = mutableListOf<Pair<String, String>>()

    /** Flipped by a test to make the chain read fail. */
    private var readFails = false

    /** What the save answers; null is a plain success. */
    private var saveRefusal: String? = null

    private val defaultChain =
        """{"id":"cfg-all","module":"purchase_orders","scope":"all",""" +
            """"tiers":[{"order":1,"rules":[{"type":"default","user_ids":["u1"]}]}]}"""

    private val artChain =
        """{"id":"cfg-d2","module":"purchase_orders","scope":"department","department_id":"d2",""" +
            """"tiers":[{"order":1,"rules":[{"type":"default","user_ids":["u4"]}]}]}"""

    private val roster = listOf(
        HubUser("u1", "Asha Rao", departmentIdentifier = "department_accounts", department = "Accounts"),
        HubUser("u2", "Ben Cole", departmentIdentifier = "department_camera", department = "Camera"),
        HubUser("u3", "Cara Diaz", departmentIdentifier = "department_camera", department = "Camera"),
        HubUser("u4", "Dev Patel", departmentIdentifier = "department_art", department = "Art"),
    )

    private fun engine() = MockEngine { request: HttpRequestData ->
        val path = request.url.encodedPath
        val ok = HttpStatusCode.OK
        val (status, body) = when {
            request.method == HttpMethod.Post && path.endsWith("/approval-tiers") -> {
                writes += "POST $path" to request.text()
                saveRefusal?.let { HttpStatusCode.UnprocessableEntity to """{"status":0,"message":"$it"}""" }
                    ?: (ok to """{"status":1,"data":$defaultChain}""")
            }
            request.method == HttpMethod.Delete -> {
                writes += "DELETE $path" to ""
                ok to """{"status":1,"message":"deleted"}"""
            }
            path.endsWith("/approval-tiers/summary") -> ok to """{"status":1,"data":[]}"""
            path.endsWith("/approval-tiers") ->
                if (readFails) {
                    HttpStatusCode.InternalServerError to """{"status":0,"message":"approval tiers unavailable"}"""
                } else {
                    ok to """{"status":1,"data":[$defaultChain,$artChain]}"""
                }
            path.endsWith("/access/users") -> ok to """{"status":1,"data":["u2","u3"]}"""
            else -> ok to """{"status":1,"data":[]}"""
        }
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private fun HttpRequestData.text(): String =
        (body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()

    private fun viewModel(): AccountHubViewModel {
        val engine = engine()
        val repository = AccountHubRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ ApproverMockEngineFactory(engine) }),
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
            users = { roster },
            departmentList = { listOf(HubDepartment("d1", "Camera"), HubDepartment("d2", "Art")) },
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

    private suspend fun TestScope.opened(): AccountHubViewModel {
        val model = viewModel()
        model.start()
        model.onEvent(AccountHubEvent.Open(HubArea.Approvers))
        settle {
            val approvals = model.state.value.approvals
            approvals.loadedModule == ApprovalModule.PurchaseOrders && approvals.candidateIds != null
        }
        return model
    }

    private val AccountHubViewModel.builder get() = state.value.approvals.builder

    private suspend fun TestScope.saved(model: AccountHubViewModel, send: AccountHubEvent) {
        model.onEvent(send)
        settle { model.builder == null && !model.state.value.approvals.saving }
    }

    private fun tiersOf(body: String): JsonArray = Json.parseToJsonElement(body).jsonObject.getValue("tiers").jsonArray

    private fun JsonObject.users(): List<String> = getValue("user_ids").jsonArray.map { it.jsonPrimitive.content }

    /**
     * The web's `openBuilder`: a department with no chain of its own starts
     * from the default's levels. Nothing is written until Save, and the save
     * is the department's own — keyed by scope and department, with no `id`,
     * exactly the body the web sends.
     */
    @Test
    fun `a department without a chain starts from the default and saves as its own`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.EditDepartmentConfig("d1"))
        advanceUntilIdle()

        val config = assertNotNull(model.builder).config
        assertEquals(ApprovalScope.Department, config.scope)
        assertEquals("d1", config.departmentId)
        assertEquals("", config.id, "the default's id must not ride along")
        assertEquals(listOf("u1"), config.tiers.single().userIds)
        assertTrue(writes.isEmpty(), "opening the editor writes nothing")

        saved(model, AccountHubEvent.SaveApprovalConfig)

        val (call, body) = writes.single()
        assertTrue(call.endsWith("/approval-tiers"), call)
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("department", json.getValue("scope").jsonPrimitive.content)
        assertEquals("d1", json.getValue("department_id").jsonPrimitive.content)
        assertFalse("id" in json, body)
        val rule = tiersOf(body).single().jsonObject.getValue("rules").jsonArray.single().jsonObject
        assertEquals(listOf("u1"), rule.users())
        assertEquals("Approval levels saved successfully.", model.state.value.notice)
    }

    /**
     * A level holding a Default can only gain "Amount greater than" rules, and
     * those cannot change kind. A person sits on a level once, whichever rule
     * they came in under; a rule with no kind offers no picker.
     */
    @Test
    fun `rules on a level follow the web's lock and one-person-per-level rule`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.EditDefaultApprovals)
        model.onEvent(AccountHubEvent.AddApprovalRule(tier = 1))
        advanceUntilIdle()
        assertEquals(ApprovalRule.AMOUNT, assertNotNull(model.builder).config.tiers.single().rules[1].type)

        model.onEvent(AccountHubEvent.SetApprovalRuleType(tier = 1, rule = 1, type = ApprovalRule.DEFAULT))
        model.onEvent(AccountHubEvent.SetApprovalRuleAmount(tier = 1, rule = 1, amount = 5000.0))
        model.onEvent(AccountHubEvent.OpenApproverPicker(tier = 1, rule = 1))
        model.onEvent(AccountHubEvent.ToggleApproverPick("u1"))
        model.onEvent(AccountHubEvent.ToggleApproverPick("u2"))
        advanceUntilIdle()
        val picking = assertNotNull(model.builder)
        assertEquals(ApprovalRule.AMOUNT, picking.config.tiers.single().rules[1].type, "a locked rule keeps its kind")
        assertEquals(listOf("u2"), picking.picked, "the level's Default approver cannot be ticked again")

        model.onEvent(AccountHubEvent.AddPickedApprovers)
        model.onEvent(AccountHubEvent.InsertApprovalLevel(position = 1))
        model.onEvent(AccountHubEvent.OpenApproverPicker(tier = 2, rule = 0))
        advanceUntilIdle()
        val built = assertNotNull(model.builder)
        assertNull(built.pickerTier, "an untyped rule has no Add Users")
        assertEquals(listOf("u2"), built.config.tiers.first().rules[1].userIds)
        assertEquals("", built.config.tiers[1].rules.single().type)

        // The empty level is the last one, so the save goes straight through.
        saved(model, AccountHubEvent.SaveApprovalConfig)
        val rules = tiersOf(writes.single().second).single().jsonObject.getValue("rules").jsonArray
        assertEquals(2, rules.size)
        val amount = rules[1].jsonObject
        assertEquals("amount", amount.getValue("type").jsonPrimitive.content)
        assertEquals(5000.0, amount.getValue("amount_threshold").jsonPrimitive.double)
        assertEquals(listOf("u2"), amount.users())
    }

    /** A filled level below an empty one moves up — after asking, in the web's words. */
    @Test
    fun `a gap before a filled level asks before the levels move up`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.EditDefaultApprovals)
        model.onEvent(AccountHubEvent.InsertApprovalLevel(position = 0))
        model.onEvent(AccountHubEvent.SaveApprovalConfig)
        advanceUntilIdle()

        val confirm = assertIs<BuilderConfirm.EmptyLevels>(assertNotNull(model.builder).confirm)
        assertEquals(listOf(1), confirm.levels)
        assertEquals(
            "Level 1 has no approvers. It will be removed and the levels below will move up. " +
                "Save the updated approval levels?",
            ApprovalSequence.compactionMessage(confirm.levels),
        )
        assertTrue(writes.isEmpty(), "nothing is sent before the answer")

        saved(model, AccountHubEvent.ConfirmApprovalSave)
        val tier = tiersOf(writes.single().second).single().jsonObject
        assertEquals(1, tier.getValue("order").jsonPrimitive.content.toInt())
    }

    @Test
    fun `several empty levels are named the web's way`() {
        assertEquals("Level 2 and Level 4", ApprovalSequence.levelsText(listOf(2, 4)))
        assertEquals("Level 1, Level 2 and Level 3", ApprovalSequence.levelsText(listOf(1, 2, 3)))
        val message = ApprovalSequence.compactionMessage(listOf(2, 4))
        assertTrue(message.startsWith("Level 2 and Level 4 have no approvers. They"), message)
    }

    /**
     * The amount check runs on the levels that survive: a half-built level
     * about to be dropped cannot block a save, but an amount rule on a level
     * that stays must have its amount.
     */
    @Test
    fun `only a surviving amount rule needs its amount`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.EditDefaultApprovals)
        model.onEvent(AccountHubEvent.AddApprovalRule(tier = 1))
        model.onEvent(AccountHubEvent.SaveApprovalConfig)
        advanceUntilIdle()
        assertEquals(
            "Enter an amount greater than 0 for each \"Amount greater than\" rule.",
            assertNotNull(model.builder).error,
        )
        assertTrue(writes.isEmpty())

        model.onEvent(AccountHubEvent.RemoveApprovalRule(tier = 1, rule = 1))
        model.onEvent(AccountHubEvent.InsertApprovalLevel(position = 1))
        model.onEvent(AccountHubEvent.SetApprovalRuleType(tier = 2, rule = 0, type = ApprovalRule.AMOUNT))
        saved(model, AccountHubEvent.SaveApprovalConfig)
        assertEquals(1, tiersOf(writes.single().second).size, "the empty amount level was dropped, not refused")
    }

    /** The default chain cannot be emptied: there is nothing behind it to fall back to. */
    @Test
    fun `an emptied default chain is refused`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.EditDefaultApprovals)
        model.onEvent(AccountHubEvent.RemoveApprover(tier = 1, rule = 0, userId = "u1"))
        model.onEvent(AccountHubEvent.SaveApprovalConfig)
        advanceUntilIdle()
        assertEquals("Please add at least one level.", assertNotNull(model.builder).error)
        assertTrue(writes.isEmpty())
    }

    /** A department never saved has nothing to remove, so emptying it just closes the editor. */
    @Test
    fun `an emptied department that was never saved just closes`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.EditDepartmentConfig("d1"))
        model.onEvent(AccountHubEvent.RemoveApprover(tier = 1, rule = 0, userId = "u1"))
        model.onEvent(AccountHubEvent.SaveApprovalConfig)
        advanceUntilIdle()
        assertNull(model.builder)
        assertTrue(writes.isEmpty())
    }

    /**
     * An emptied department with a saved chain asks, then removes the row —
     * the absence of a row is what "inherits" means to the server.
     */
    @Test
    fun `an emptied department with a saved chain reverts to the global approvers`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.EditDepartmentConfig("d2"))
        model.onEvent(AccountHubEvent.RemoveApprover(tier = 1, rule = 0, userId = "u4"))
        model.onEvent(AccountHubEvent.SaveApprovalConfig)
        advanceUntilIdle()
        assertEquals(BuilderConfirm.RevertToGlobal("cfg-d2"), assertNotNull(model.builder).confirm)
        assertTrue(writes.isEmpty())

        saved(model, AccountHubEvent.ConfirmApprovalSave)
        val call = writes.single().first
        assertTrue(call.startsWith("DELETE") && call.endsWith("/approval-tiers/cfg-d2"), call)
        assertEquals("This department will now use the global approvers.", model.state.value.notice)
    }

    /** A refusal keeps the editor open, confirmation closed, with the server's reason in it. */
    @Test
    fun `a refused save stays in the editor with the reason`() = runTest(dispatcher) {
        saveRefusal = "tiers[0].rules[0].type must be one of [default, amount]"
        val model = opened()
        model.onEvent(AccountHubEvent.EditDefaultApprovals)
        model.onEvent(AccountHubEvent.SaveApprovalConfig)
        settle { model.builder?.error != null }

        val builder = assertNotNull(model.builder)
        assertTrue(assertNotNull(builder.error).contains("must be one of"), builder.error)
        assertNull(builder.confirm)
        assertFalse(model.state.value.approvals.saving)
    }

    /**
     * A failed read is reported as a failure. An empty list would render every
     * department "Not configured", which is not what is known.
     */
    @Test
    fun `a failed read shows its reason and a retry recovers`() = runTest(dispatcher) {
        readFails = true
        val model = viewModel()
        model.start()
        model.onEvent(AccountHubEvent.Open(HubArea.Approvers))
        settle { model.state.value.approvals.loadError != null }
        assertNull(model.state.value.approvals.loadedModule)

        readFails = false
        model.onEvent(AccountHubEvent.ReloadApprovalConfigs)
        settle { model.state.value.approvals.loadedModule != null }
        assertNull(model.state.value.approvals.loadError)
        assertEquals(2, model.state.value.approvals.configs.size)
    }

    /** A new module starts with a clean toolbar, as the web's module switch does. */
    @Test
    fun `switching module clears the department search and filter`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.SearchDepartments("cam"))
        model.onEvent(AccountHubEvent.FilterDepartments(DepartmentFilter.Custom))
        model.onEvent(AccountHubEvent.SwitchApprovalModule(ApprovalModule.Invoices))
        advanceUntilIdle()
        val approvals = model.state.value.approvals
        assertEquals("", approvals.departmentSearch)
        assertEquals(DepartmentFilter.All, approvals.departmentFilter)
    }
}

private class ApproverMockEngineFactory(private val engine: MockEngine) :
    HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
