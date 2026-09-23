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
import com.zillit.desktop.feature.accounthub.domain.CoaAccount
import com.zillit.desktop.feature.accounthub.domain.CoaCostType
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubViewModel
import com.zillit.desktop.feature.accounthub.ui.ChartState
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.concurrent.Volatile
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
 * The Chart of Accounts driven through the console's own view model against a
 * small in-memory chart service — what each action sends, and what the screen
 * holds afterwards. The web's `AccountsTab`, `AccountFormModal` and
 * `CoaBulkAddPage` are the reference for every body asserted here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChartOfAccountsFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** One call the service received. */
    private data class Call(
        val method: HttpMethod,
        val path: String,
        val query: Map<String, String>,
        val body: JsonObject?,
    )

    /** A chart service: rows by id, breadcrumbs filled from the parent, every call kept. */
    private class ChartService {
        val rows = mutableListOf<JsonObject>()

        /**
         * Swapped, never mutated: the engine records from the client's thread
         * while `settle` filters on the test's, and filtering an `ArrayList`
         * mid-append threw ConcurrentModificationException now and then.
         */
        @Volatile
        var calls: List<Call> = emptyList()
        var cascaded = 0
        var refuseDeletes = false
        private var nextId = 1

        fun seed(vararg accounts: CoaAccount) {
            accounts.forEach { rows += it.toJson() }
        }

        fun handle(request: HttpRequestData): String {
            val path = request.url.encodedPath
            val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
                ?.takeIf { it.isNotBlank() }
                ?.let { Json.parseToJsonElement(it).jsonObject }
            val query = request.url.parameters.entries().associate { it.key to it.value.first() }
            calls += Call(request.method, path, query, body)
            if (!path.contains("/chart-of-accounts")) return """{"status":1,"data":[]}"""
            val id = path.substringAfter("/chart-of-accounts", "").trim('/')
            return when (request.method) {
                HttpMethod.Get -> envelope(buildJsonArray { rows.forEach { add(it) } })
                HttpMethod.Post -> envelope(create(requireNotNull(body)))
                HttpMethod.Patch -> envelope(update(id, requireNotNull(body)))
                HttpMethod.Delete -> if (refuseDeletes) {
                    """{"status":0,"message":"chart_of_account_in_use"}"""
                } else {
                    replace(id) { put("is_active", false) }
                    """{"status":1,"message":"deactivated"}"""
                }
                else -> """{"status":1,"data":null}"""
            }
        }

        private fun envelope(data: kotlinx.serialization.json.JsonElement) = buildJsonObject {
            put("status", 1)
            put("data", data)
        }.toString()

        private fun create(body: JsonObject): JsonObject {
            val id = "new-${nextId++}"
            val parentId = body["parent_id"]?.jsonPrimitive?.contentOrNull
            val parent = parentId?.let { wanted -> rows.firstOrNull { it.str("id") == wanted } }
            // The breadcrumb the server writes: every ancestor's id, and this row's own at its level.
            val lineType = body["line_type"]?.jsonPrimitive?.contentOrNull
            val row = buildJsonObject {
                body.forEach { (key, value) -> if (key != "parent_id") put(key, value) }
                put("id", id)
                put("head_id", if (lineType == "header") id else parent?.str("head_id") ?: id)
                when (lineType) {
                    "section" -> put("sec_id", id)
                    "category" -> {
                        parent?.str("id")?.let { put("sec_id", it) }
                        put("cat_id", id)
                    }
                    "sub_category" -> {
                        parent?.str("sec_id")?.let { put("sec_id", it) }
                        parent?.str("id")?.let { put("cat_id", it) }
                    }
                }
            }
            rows += row
            return row
        }

        private fun update(id: String, body: JsonObject): JsonObject {
            val updated = replace(id) { body.forEach { (key, value) -> put(key, value) } }
            return buildJsonObject {
                updated.forEach { (key, value) -> put(key, value) }
                put("_cascaded_descendants", cascaded)
            }
        }

        private fun replace(id: String, edit: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject {
            val index = rows.indexOfFirst { it.str("id") == id }
            val next = buildJsonObject {
                rows[index].forEach { (key, value) -> put(key, value) }
                edit()
            }
            rows[index] = next
            return next
        }

        private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull

        private fun CoaAccount.toJson() = buildJsonObject {
            put("id", id)
            put("code", code)
            put("name", name)
            put("line_type", lineType.wire)
            put("cost_type", costType.wire)
            headId?.let { put("head_id", it) }
            sectionId?.let { put("sec_id", it) }
            categoryId?.let { put("cat_id", it) }
            put("is_active", isActive)
            put("posting_box", isPosting)
            put("source", source)
        }
    }

    private val service = ChartService()

    private val header = CoaAccount(
        id = "h1",
        code = "1000",
        name = "Production",
        lineType = CoaLineType.Header,
        headId = "h1",
    )
    private val section = CoaAccount(
        id = "s1",
        code = "1100",
        name = "Crew",
        lineType = CoaLineType.Section,
        headId = "h1",
        sectionId = "s1",
    )
    private val nominal = CoaAccount(
        id = "c1",
        code = "1110",
        name = "Grips",
        lineType = CoaLineType.Category,
        headId = "h1",
        sectionId = "s1",
        categoryId = "c1",
    )

    private fun viewModel(): AccountHubViewModel {
        val engine = MockEngine { request ->
            respond(service.handle(request), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val repository = AccountHubRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ ChartMockEngineFactory(engine) }),
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

    private suspend fun TestScope.opened(vararg extra: CoaAccount): AccountHubViewModel {
        service.seed(header, section, nominal, *extra)
        val model = viewModel()
        model.start()
        model.onEvent(AccountHubEvent.Open(HubArea.ChartOfAccounts))
        settle { model.chartState.loaded && model.chartState.accounts.size == service.rows.size }
        return model
    }

    private val AccountHubViewModel.chartState: ChartState get() = state.value.chart

    private fun chartCalls(method: HttpMethod) =
        service.calls.filter { it.method == method && it.path.contains("/chart-of-accounts") }

    // -- reading ---------------------------------------------------------------------

    /** Stated, as every web caller states it — the server's default is not ours to rely on. */
    @Test
    fun `the chart is read with active_only stated`() = runTest(dispatcher) {
        val model = opened()

        assertEquals("false", chartCalls(HttpMethod.Get).first().query["active_only"])
        assertEquals(3, model.chartState.accounts.size)
    }

    // -- the table's class select ------------------------------------------------------

    /**
     * The class alone goes, as the web's select sends it; the answer's
     * descendant count is said; and the chart is read again, because the class
     * moved the rows beneath too.
     */
    @Test
    fun `the inline class select sends the class alone and reads the chart again`() = runTest(dispatcher) {
        val model = opened()
        service.cascaded = 2
        val readsBefore = chartCalls(HttpMethod.Get).size

        model.onEvent(AccountHubEvent.SetAccountCostTypeInline("h1", CoaCostType.Asset))
        settle { chartCalls(HttpMethod.Get).size > readsBefore && !model.chartState.loading }

        val patch = chartCalls(HttpMethod.Patch).single()
        assertTrue(patch.path.endsWith("/chart-of-accounts/h1"))
        assertEquals(setOf("cost_type"), patch.body?.keys)
        assertEquals("Cost type updated — 2 descendants also updated", model.state.value.notice)
    }

    /** The server refuses a budget row's change of class, so the select never asks. */
    @Test
    fun `a budget row's class is never sent`() = runTest(dispatcher) {
        val model = opened(nominal.copy(id = "b1", code = "1120", source = CoaAccount.BUDGET_SOURCE))

        model.onEvent(AccountHubEvent.SetAccountCostTypeInline("b1", CoaCostType.Asset))
        settle { false }

        assertTrue(chartCalls(HttpMethod.Patch).isEmpty())
        assertEquals(CoaCostType.Expense, model.chartState.accounts.first { it.id == "b1" }.costType)
    }

    // -- the edit form -----------------------------------------------------------------

    /** Level and parent ride along only when they moved: re-threading the breadcrumb is the expensive half. */
    @Test
    fun `an edit sends level and parent only when they moved`() = runTest(dispatcher) {
        val model = opened()

        model.onEvent(AccountHubEvent.ComposeAccount(editing = section))
        model.onEvent(AccountHubEvent.SetAccountName("Crew & Cast"))
        model.onEvent(AccountHubEvent.SaveAccount)
        settle { model.chartState.form == null }

        val rename = chartCalls(HttpMethod.Patch).last().body!!
        assertEquals("Crew & Cast", rename["name"]?.jsonPrimitive?.contentOrNull)
        assertFalse("line_type" in rename)
        assertFalse("parent_id" in rename)

        model.onEvent(AccountHubEvent.ComposeAccount(editing = nominal))
        model.onEvent(AccountHubEvent.SetAccountLineType(CoaLineType.Section))
        // Its old parent is a section, which cannot hold a section: cleared rather than kept.
        assertNull(model.chartState.form?.parentId)
        model.onEvent(AccountHubEvent.SetAccountParent("h1"))
        model.onEvent(AccountHubEvent.SaveAccount)
        settle { model.chartState.form == null }

        val retype = chartCalls(HttpMethod.Patch).last().body!!
        assertEquals("section", retype["line_type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("h1", retype["parent_id"]?.jsonPrimitive?.contentOrNull)
    }

    // -- deactivating ------------------------------------------------------------------

    /** A `status: 0` is the server refusing: the confirmation stays, and nothing reads as retired. */
    @Test
    fun `a refused deactivate keeps the confirmation open`() = runTest(dispatcher) {
        val model = opened()
        service.refuseDeletes = true

        model.onEvent(AccountHubEvent.AskDeactivateAccount(section))
        model.onEvent(AccountHubEvent.ConfirmDeactivateAccount)
        settle { chartCalls(HttpMethod.Delete).isNotEmpty() && !model.chartState.deactivating }

        assertNotNull(model.chartState.confirmDeactivate)
        assertTrue(model.chartState.accounts.first { it.id == "s1" }.isActive)
    }

    @Test
    fun `an accepted deactivate closes the confirmation and shows the code retired`() = runTest(dispatcher) {
        val model = opened()

        model.onEvent(AccountHubEvent.AskDeactivateAccount(section))
        model.onEvent(AccountHubEvent.ConfirmDeactivateAccount)
        settle {
            val chart = model.chartState
            chart.confirmDeactivate == null && chart.accounts.any { it.id == "s1" && !it.isActive }
        }

        assertFalse(model.chartState.accounts.first { it.id == "s1" }.isActive)
    }

    // -- the bulk grid -------------------------------------------------------------------

    /**
     * One row's life on the grid: created once it has a code, updated narrowly,
     * re-created under a new code with the old one retired, re-typed with its
     * level and parent — and a colliding row never sent.
     */
    @Test
    fun `a grid row creates, updates, renames and re-types itself`() = runTest(dispatcher) {
        val model = opened()

        model.onEvent(AccountHubEvent.OpenBulkAdd(section))
        val row = model.chartState.bulk!!.rows.single()
        assertEquals(CoaLineType.Category, row.lineType)

        model.onEvent(AccountHubEvent.EditBulkRow(row.copy(code = "1150")))
        settle { model.chartState.bulk?.rows?.single()?.serverId != null }
        val created = chartCalls(HttpMethod.Post).single().body!!
        assertEquals("1150", created.string("code"))
        assertEquals("", created.string("name"))
        assertEquals("category", created.string("line_type"))
        assertEquals("s1", created.string("parent_id"))
        assertEquals(true, created["is_active"]?.jsonPrimitive?.booleanOrNull)

        val saved = model.chartState.bulk!!.rows.single()
        model.onEvent(AccountHubEvent.EditBulkRow(saved.copy(name = "Riggers")))
        settle { chartCalls(HttpMethod.Patch).isNotEmpty() }
        val narrow = chartCalls(HttpMethod.Patch).single().body!!
        assertEquals(setOf("name", "cost_type", "is_active", "posting_box"), narrow.keys)

        val oldId = saved.serverId
        model.onEvent(AccountHubEvent.EditBulkRow(model.chartState.bulk!!.rows.single().copy(code = "1160")))
        settle {
            chartCalls(HttpMethod.Delete).isNotEmpty() && model.chartState.bulk?.rows?.single()?.serverId != oldId
        }
        assertEquals("1160", chartCalls(HttpMethod.Post).last().body?.string("code"))
        assertTrue(chartCalls(HttpMethod.Delete).single().path.endsWith("/$oldId"))

        val renamed = model.chartState.bulk!!.rows.single()
        model.onEvent(AccountHubEvent.EditBulkRow(renamed.copy(lineType = CoaLineType.SubCategory)))
        settle { chartCalls(HttpMethod.Patch).size == 2 }
        val retype = chartCalls(HttpMethod.Patch).last().body!!
        assertEquals("sub_category", retype.string("line_type"))
        assertEquals("s1", retype.string("parent_id"))

        val posts = chartCalls(HttpMethod.Post).size
        model.onEvent(AccountHubEvent.AddBulkRows(1))
        val clash = model.chartState.bulk!!.rows.last()
        model.onEvent(AccountHubEvent.EditBulkRow(clash.copy(code = "1100")))
        settle { false }
        assertEquals(posts, chartCalls(HttpMethod.Post).size, "a code the chart holds is never created")
    }

    /** Done sends what is still waiting at once, then closes the grid and reads the chart again. */
    @Test
    fun `done flushes a waiting row and reads the chart again`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.OpenBulkAdd(null))
        val row = model.chartState.bulk!!.rows.single()
        model.onEvent(AccountHubEvent.EditBulkRow(row.copy(code = "3000", name = "Post")))
        val reads = chartCalls(HttpMethod.Get).size

        model.onEvent(AccountHubEvent.FinishBulkAdd)
        settle { model.chartState.bulk == null && chartCalls(HttpMethod.Get).size > reads && !model.chartState.loading }

        assertEquals("3000", chartCalls(HttpMethod.Post).single().body?.string("code"))
        assertTrue(model.chartState.accounts.any { it.code == "3000" })
    }

    /** Removing a saved row retires it: the API has no hard delete. */
    @Test
    fun `removing a saved row retires it`() = runTest(dispatcher) {
        val model = opened()
        model.onEvent(AccountHubEvent.OpenBulkAdd(null))
        val row = model.chartState.bulk!!.rows.single()
        model.onEvent(AccountHubEvent.EditBulkRow(row.copy(code = "4000")))
        settle { model.chartState.bulk?.rows?.single()?.serverId != null }
        val serverId = model.chartState.bulk!!.rows.single().serverId

        model.onEvent(AccountHubEvent.RemoveBulkRow(row.localId))
        settle { chartCalls(HttpMethod.Delete).isNotEmpty() }

        assertTrue(chartCalls(HttpMethod.Delete).single().path.endsWith("/$serverId"))
        assertTrue(model.chartState.bulk!!.rows.isEmpty())
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

private class ChartMockEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
