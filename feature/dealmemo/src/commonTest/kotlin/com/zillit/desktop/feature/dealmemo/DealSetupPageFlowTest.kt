@file:Suppress("MaxLineLength") // Wire fixtures read best as one JSON line each.

package com.zillit.desktop.feature.dealmemo

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.dealmemo.data.DealMemoRepositoryImpl
import com.zillit.desktop.feature.dealmemo.data.DealReferenceSource
import com.zillit.desktop.feature.dealmemo.domain.preview.DealAttachment
import com.zillit.desktop.feature.dealmemo.domain.preview.DealCompany
import com.zillit.desktop.feature.dealmemo.domain.preview.DealCountry
import com.zillit.desktop.feature.dealmemo.domain.preview.DealUnit
import com.zillit.desktop.feature.dealmemo.ui.BuilderEvent
import com.zillit.desktop.feature.dealmemo.ui.DealCoaAccount
import com.zillit.desktop.feature.dealmemo.ui.DealDocumentStore
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoRoute
import com.zillit.desktop.feature.dealmemo.ui.DealMemoViewModel
import com.zillit.desktop.feature.dealmemo.ui.DealMemoViewer
import com.zillit.desktop.feature.dealmemo.ui.DealProductionData
import com.zillit.desktop.feature.dealmemo.ui.DealProjectInfo
import com.zillit.desktop.feature.dealmemo.ui.DealSavedSignature
import com.zillit.desktop.feature.dealmemo.ui.PickedDealFile
import com.zillit.desktop.feature.dealmemo.ui.RulesEvent
import com.zillit.desktop.feature.dealmemo.ui.SetupGroup
import com.zillit.desktop.feature.dealmemo.ui.SetupHubEvent
import com.zillit.desktop.feature.dealmemo.ui.TemplatePatch
import com.zillit.desktop.feature.dealmemo.ui.builder.RulesTarget
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a setup page writes straight to Production Setup — a company, the
 * non-union pay rules and their import, Day Types, agreement documents — and
 * the Setup Hub's inline create, delete and Use, over a mock server that
 * keeps what it is sent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DealSetupPageFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private data class Call(val method: HttpMethod, val path: String, val body: JsonElement?)

    private val calls = MutableStateFlow<List<Call>>(emptyList())

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    // -- what the server holds ----------------------------------------------------------------------

    private var companies: JsonElement = Json.parseToJsonElement("""[{"id":"co-1","name":"Acme Productions","country":"United Kingdom","country_code":"GB"}]""")
    private var dayTypes: JsonElement = Json.parseToJsonElement(
        """[{"day_type":"SWD","work_min":660,"meal_break_min":60,"label":"Standard Working Day"},{"day_type":"NIGHT","work_min":600,"meal_break_min":30,"label":"Night"}]""",
    )
    private var payBreakdown: JsonElement = json(
        """{"overtimes":[{"id":"ot-1","label":"OT","rate_type":"multiplier","rate_amount":1.5,"basis":"hour","triggers":[{"after":600}]}],"premiums":[],"penalties":[],"apply_mode":"departments","department_ids":["dep-1"]}""",
    )
    private var documents: List<JsonObject> = listOf(
        json("""{"_id":"doc-1","title":"NDA","description":"","document":{"name":"nda.pdf","media":"m-nda","bucket":"b","region":"r","content_type":"document","content_subtype":"pdf","file_size":100}}"""),
    )
    private val templates = mutableMapOf<String, JsonObject>()

    private fun settings(): String = """{"settings":{
        "companies":$companies,
        "day_types":$dayTypes,
        "project_currencies":{"default":"GBP","currencies":[{"code":"GBP","exr":1}]},
        "production":{"non_union_paybreakdown":$payBreakdown,"agreements_documents":${JsonArray(documents)}}
    }}"""

    private val agreementDoc = """{"_identifier":"pact-bectu-tvda","name":"PACT/BECTU TV Drama","union_identifier":"bectu",
        "overtimes":{"rows":[{"id":"overtime","label":"Overtime","rate_type":"multiplier","rate_amount":1.5,"basis":"hour","triggers":[{"after":660,"camera":false}]},
                             {"id":"meal_penalty","label":"Meal Penalty","rate_type":"flat","rate_amount":15,"basis":"event","triggers":[{"after":360,"meal":true}]}]},
        "premiums":{"rows":[{"id":"6th_day","label":"6th Day","rate_type":"multiplier","rate_amount":1.5,"basis":"day","triggers":[{"day_number":6,"consecutive":true}]}]}}"""

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        val method = request.method
        val body = (request.body as? TextContent)?.text?.let { Json.parseToJsonElement(it) }
        calls.update { it + Call(method, path, body) }
        val answer = when {
            path.endsWith("/metadata") -> """{"status":1,"data":{"is_approver":false,"approval_tier_configs":[]}}"""
            path.endsWith("/project-settings") -> """{"status":1,"data":${settings()}}"""
            path.endsWith("/project-settings/companies") -> {
                companies = body ?: companies
                """{"status":1,"message":"company_saved"}"""
            }
            path.endsWith("/project-settings/day-types") -> {
                dayTypes = body ?: dayTypes
                """{"status":1,"message":"day_types_saved"}"""
            }
            path.endsWith("/project-settings/non-union-paybreakdown") -> {
                payBreakdown = body ?: payBreakdown
                """{"status":1,"message":"rules_saved"}"""
            }
            path.endsWith("/project-settings/agreements-documents") -> {
                documents = documents + body?.jsonArray.orEmpty().mapIndexed { index, row ->
                    JsonObject(row.jsonObject + ("_id" to JsonPrimitive("doc-new-${documents.size + index}")))
                }
                """{"status":1,"message":"documents_added"}"""
            }
            path.contains("/project-settings/agreements-documents/") -> {
                val id = path.substringAfterLast('/')
                documents = documents.filterNot { it["_id"]?.jsonPrimitive?.content == id }
                """{"status":1,"message":"document_deleted"}"""
            }
            path.endsWith("/deal-memo/templates") && method == HttpMethod.Get ->
                """{"status":1,"data":[${templates.values.joinToString(",") { """{"_id":${it["_id"]},"name":${it["name"]},"created_at":1}""" }}]}"""
            path.endsWith("/deal-memo/templates") && method == HttpMethod.Post -> {
                val sent = body!!.jsonObject
                val id = sent["_id"]!!.jsonPrimitive.content
                templates[id] = JsonObject(mapOf("_id" to JsonPrimitive(id), "name" to sent["name"]!!, "template_data" to sent))
                """{"status":1,"message":"template_saved","data":{"_id":"$id"}}"""
            }
            path.contains("/deal-memo/templates/") && method == HttpMethod.Get ->
                """{"status":1,"data":${templates[path.substringAfterLast('/')]}}"""
            path.contains("/deal-memo/templates/") && method == HttpMethod.Delete -> {
                templates.remove(path.substringAfterLast('/'))
                """{"status":1,"message":"template_deleted"}"""
            }
            path.endsWith("/agreements") ->
                """{"status":1,"data":{"union":[{"_identifier":"pact-bectu-tvda","name":"PACT/BECTU TV Drama","short_label":"TVDA"}],"emp_statuses":[]}}"""
            path.endsWith("/agreements/pact-bectu-tvda") -> """{"status":1,"data":$agreementDoc}"""
            path.endsWith("/covered-territories") -> """{"status":1,"data":["uk"]}"""
            else -> """{"status":1,"message":"ok","data":[]}"""
        }
        respond(answer, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private val config = AppConfig(
        environment = Environment.Develop,
        services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
        realtime = emptyMap(),
    )

    private val apiClient = ApiClient(
        httpClient = HttpClientFactory.create({ SetupMockEngineFactory(engine) }),
        headerProvider = { _, _, _, _ -> emptyMap() },
    )

    private val production = object : DealProductionData {
        override fun project() = DealProjectInfo(projectName = "Nightfall", productionType = "scripted-tv")
        override fun webOrigin(): String? = null
        override suspend fun companies() = listOf(DealCompany("co-1", "Acme Productions"))
        override suspend fun units() = listOf(DealUnit("unit-1", "main_unit"))
        override suspend fun countries() =
            listOf(DealCountry("GB", "+44", "United Kingdom"), DealCountry("FR", "+33", "France"))
        override suspend fun chartOfAccounts() = ZillitResult.Success(listOf(DealCoaAccount("4400", "Per diems")))
    }

    /** Files the picker hands back, and what was uploaded. */
    private var picked: List<PickedDealFile> = emptyList()
    private val uploads = mutableListOf<Pair<String, String>>()

    private val store = object : DealDocumentStore {
        override suspend fun fetch(attachment: DealAttachment): ZillitResult<ByteArray> =
            ZillitResult.Failure(ZillitError.Validation("Not stored in this test."))
        override suspend fun upload(fileName: String, contentType: String, bytes: ByteArray): ZillitResult<DealAttachment> {
            uploads += fileName to contentType
            return ZillitResult.Success(DealAttachment(json("""{"media":"m-$fileName","bucket":"b","region":"r","name":"$fileName"}""")))
        }
        override suspend fun savedSignatures(): ZillitResult<List<DealSavedSignature>> = ZillitResult.Success(emptyList())
        override suspend fun saveSignature(png: ByteArray): ZillitResult<Unit> = ZillitResult.Success(Unit)
        override suspend fun deleteSignature(id: String): ZillitResult<Unit> = ZillitResult.Success(Unit)
        override suspend fun pickFiles(extensions: List<String>, multiple: Boolean): List<PickedDealFile> = picked
    }

    private val accountant = DealMemoViewer(
        "u-acc",
        "department_accounts",
        hasPostingAccess = true,
        hasViewAccess = true,
        rightsLoaded = true,
    )

    private fun viewModel() = DealMemoViewModel(
        repository = DealMemoRepositoryImpl(apiClient, config),
        reference = DealReferenceSource(apiClient, config),
        viewer = { accountant },
        clock = { NOW },
        metadataRetryDelays = listOf(10L),
        productionData = production,
        store = store,
        workDispatcher = dispatcher,
    )

    private suspend fun TestScope.settle(condition: () -> Boolean) {
        repeat(SETTLE_TRIES) {
            advanceUntilIdle()
            if (condition()) return
            withContext(Dispatchers.Default) { delay(SETTLE_STEP_MILLIS) }
        }
        advanceUntilIdle()
    }

    private fun writes(method: HttpMethod, suffix: String) = calls.value.filter { it.method == method && it.path.endsWith(suffix) }

    private fun DealMemoViewModel.builder() = assertNotNull(state.value.builder)

    private fun DealMemoViewModel.page() = builder().setupPage

    /** A new setup of [group], loaded, with the project's settings in. */
    private suspend fun TestScope.setupPage(group: SetupGroup): DealMemoViewModel {
        val model = viewModel()
        model.start()
        model.onEvent(DealMemoEvent.Navigate(DealMemoRoute.SetupHub(group, DealMemoRoute.NEW)))
        settle { model.state.value.projectSettings.loaded && model.state.value.builder?.setupPage?.dayTypes != null }
        return model
    }

    // -- companies ----------------------------------------------------------------------------------

    @Test
    fun `a new company is written with the whole list and becomes the setup's entity`() = runTest(dispatcher) {
        val model = setupPage(SetupGroup.Union)

        model.onEvent(BuilderEvent.AddCompany)
        val draft = assertNotNull(model.page().companyDraft, "a production with companies opens the form at once")
        model.onEvent(BuilderEvent.SaveCompany)
        assertTrue(writes(HttpMethod.Patch, "/companies").isEmpty(), "a nameless draft is not saved")

        model.onEvent(
            BuilderEvent.EditCompany(
                mapOf("name" to JsonPrimitive("Frostline Pictures SAS"), "country" to JsonPrimitive("France"), "country_code" to JsonPrimitive("FR")),
            ),
        )
        model.onEvent(BuilderEvent.SaveCompany)
        settle { model.state.value.builder?.setupPage?.companyDraft == null }

        val sent = writes(HttpMethod.Patch, "/project-settings/companies").single().body!!.jsonArray
        assertEquals(listOf("co-1", draft["id"]?.jsonPrimitive?.content), sent.map { it.jsonObject["id"]?.jsonPrimitive?.content })
        assertEquals(JsonArray(emptyList()), sent[0].jsonObject["bank_ids"], "every company is sent with its banks, none claimed")
        assertEquals("Frostline Pictures SAS", sent[1].jsonObject["name"]?.jsonPrimitive?.content)
        val companyId = draft["id"]?.jsonPrimitive?.content
        assertEquals(companyId, model.builder().form.text("productionEntity"))
        settle { writes(HttpMethod.Post, "/deal-memo/templates").isNotEmpty() }
        val autosaved = writes(HttpMethod.Post, "/deal-memo/templates").single().body!!.jsonObject
        assertEquals(companyId, autosaved["company_id"]?.jsonPrimitive?.content, "picking it is the user's own edit, so the page saves it")
    }

    @Test
    fun `a production with no companies explains itself before the form`() = runTest(dispatcher) {
        companies = JsonArray(emptyList())
        val model = setupPage(SetupGroup.Union)

        model.onEvent(BuilderEvent.AddCompany)
        assertTrue(model.page().companyNotice)
        assertNull(model.page().companyDraft)

        model.onEvent(BuilderEvent.CloseCompanyNotice(proceed = true))
        assertFalse(model.page().companyNotice)
        assertNotNull(model.page().companyDraft)
    }

    // -- day types ----------------------------------------------------------------------------------

    @Test
    fun `Day Types seed from the project, refuse an incomplete row, then save as a bare array`() = runTest(dispatcher) {
        val model = setupPage(SetupGroup.NonUnion)
        val seeded = assertNotNull(model.page().dayTypes)
        assertEquals(listOf("SWD", "CWD", "SCWD", "NIGHT"), seeded.rows.map { it.code })
        assertEquals("660", seeded.rows.first().workMin)
        assertFalse(seeded.dirty)

        model.onEvent(BuilderEvent.AddDayType)
        val blank = model.page().dayTypes!!.rows.last()
        model.onEvent(BuilderEvent.SaveDayTypes)
        assertEquals("Every day type needs a code (e.g. CWD, SWD).", model.state.value.toast?.message)
        assertTrue(writes(HttpMethod.Patch, "/day-types").isEmpty())

        model.onEvent(BuilderEvent.EditDayType(blank.copy(code = "LATE", workMin = "630")))
        assertTrue(model.page().dayTypes!!.dirty)
        model.onEvent(BuilderEvent.SaveDayTypes)
        settle { writes(HttpMethod.Patch, "/day-types").isNotEmpty() && model.state.value.builder?.setupPage?.dayTypes?.saving == false }

        val sent = writes(HttpMethod.Patch, "/project-settings/day-types").single().body!!.jsonArray
        assertEquals(listOf("SWD", "CWD", "SCWD", "NIGHT", "LATE"), sent.map { it.jsonObject["day_type"]?.jsonPrimitive?.content })
        assertEquals("630", sent.last().jsonObject["work_min"].toString())
        settle { model.state.value.builder?.setupPage?.dayTypes?.rows?.any { it.code == "LATE" } == true }
        assertFalse(model.page().dayTypes!!.dirty, "what was saved is the new baseline")
    }

    // -- non-union pay rules ------------------------------------------------------------------------

    @Test
    fun `the project's pay rules save the whole slice, grid lists over it, and close the grid`() = runTest(dispatcher) {
        val model = setupPage(SetupGroup.NonUnion)

        model.onEvent(BuilderEvent.OpenProjectRules)
        val editor = assertNotNull(model.builder().rules)
        assertEquals(RulesTarget.Project, model.builder().rulesTarget)
        assertTrue(editor.agreementImport)
        assertEquals(listOf("OT"), editor.rows.map { it.label })

        model.onEvent(RulesEvent.Patch(editor.rows.single().uid, editor.rows.single().copy(amount = "1.75")))
        model.onEvent(RulesEvent.Save)
        settle { model.state.value.builder?.rules == null }

        val sent = writes(HttpMethod.Patch, "/project-settings/non-union-paybreakdown").single().body!!.jsonObject
        val overtime = sent["overtimes"]!!.jsonArray.single().jsonObject
        assertEquals("ot-1", overtime["id"]?.jsonPrimitive?.content)
        assertEquals("1.75", overtime["rate_amount"]?.jsonPrimitive?.content)
        assertEquals("departments", sent["apply_mode"]?.jsonPrimitive?.content, "keys the grid doesn't edit survive")
        assertEquals(listOf("dep-1"), sent["department_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(setOf("overtimes", "premiums", "penalties", "apply_mode", "department_ids"), sent.keys)
    }

    @Test
    fun `importing union rules appends the agreement's rules to the grid without saving`() = runTest(dispatcher) {
        val model = setupPage(SetupGroup.NonUnion)
        model.onEvent(BuilderEvent.OpenProjectRules)
        model.onEvent(RulesEvent.ImportAgreement)
        assertNotNull(model.page().ruleImport)

        model.onEvent(BuilderEvent.PickImportTerritory("uk"))
        settle { model.state.value.builder?.setupPage?.ruleImport?.agreements?.isNotEmpty() == true }
        model.onEvent(BuilderEvent.PickImportAgreement("pact-bectu-tvda"))
        settle { model.state.value.builder?.setupPage?.ruleImport?.agreement != null }
        model.onEvent(BuilderEvent.ConfirmRuleImport)

        val rows = model.builder().rules!!.rows
        assertEquals(listOf("OT", "Overtime", "6th Day", "Meal Penalty"), rows.map { it.label })
        assertTrue(rows.drop(1).all { it.id.startsWith("nu-imp-") }, "imported rules get fresh ids")
        assertEquals(3 to 0, model.builder().rules!!.importNote)
        assertNull(model.page().ruleImport)
        assertTrue(writes(HttpMethod.Patch, "/non-union-paybreakdown").isEmpty(), "only the grid's Save writes")
    }

    // -- agreement documents ------------------------------------------------------------------------

    @Test
    fun `picked PDFs queue, refused files are named, and the queue saves in one add`() = runTest(dispatcher) {
        val model = setupPage(SetupGroup.Union)
        picked = listOf(
            PickedDealFile("contract.pdf", ByteArray(10), "application/pdf"),
            PickedDealFile("notes.txt", ByteArray(10), "text/plain"),
            PickedDealFile("huge.pdf", ByteArray(20 * 1024 * 1024 + 1), "application/pdf"),
        )

        model.onEvent(BuilderEvent.QueueAgreementDocuments)
        settle { model.state.value.builder?.setupPage?.pendingDocuments?.isNotEmpty() == true }
        val queued = model.page().pendingDocuments.single()
        assertEquals("contract", queued.title)
        assertEquals("huge.pdf: over 20 MB", model.state.value.toast?.message)

        model.onEvent(BuilderEvent.EditPendingDocument(queued.id, "Contract", "Signed copy"))
        model.onEvent(BuilderEvent.SaveAgreementDocuments)
        settle { model.state.value.builder?.setupPage?.let { it.pendingDocuments.isEmpty() && !it.documentsBusy } == true }

        assertEquals(listOf("contract.pdf" to "application/pdf"), uploads)
        val added = writes(HttpMethod.Post, "/project-settings/agreements-documents").single().body!!.jsonArray.single().jsonObject
        assertEquals("Contract", added["title"]?.jsonPrimitive?.content)
        assertEquals("Signed copy", added["description"]?.jsonPrimitive?.content)
        assertEquals("m-contract.pdf", added["media"]?.jsonPrimitive?.content)
        assertEquals("10", added["file_size"].toString())
        assertEquals("Uploaded 1 document", model.state.value.toast?.message)
    }

    @Test
    fun `an edited document is added as a copy before the original is deleted`() = runTest(dispatcher) {
        val model = setupPage(SetupGroup.Union)

        model.onEvent(BuilderEvent.EditAgreementDocument("doc-1", "NDA", ""))
        model.onEvent(BuilderEvent.CommitAgreementDocument("doc-1"))
        assertTrue(calls.value.none { it.path.contains("/agreements-documents") }, "an unchanged field writes nothing")

        model.onEvent(BuilderEvent.EditAgreementDocument("doc-1", "NDA (2026)", ""))
        model.onEvent(BuilderEvent.CommitAgreementDocument("doc-1"))
        settle { model.state.value.builder?.setupPage?.let { it.documentEdits.isEmpty() && !it.documentsBusy } == true }

        val writesInOrder = calls.value.filter { it.path.contains("/agreements-documents") }
        assertEquals(listOf(HttpMethod.Post, HttpMethod.Delete), writesInOrder.map { it.method })
        val copy = writesInOrder.first().body!!.jsonArray.single().jsonObject
        assertEquals("NDA (2026)", copy["title"]?.jsonPrimitive?.content)
        assertEquals("m-nda", copy["media"]?.jsonPrimitive?.content, "the file itself is carried over")
        assertTrue(writesInOrder.last().path.endsWith("/agreements-documents/doc-1"))
    }

    // -- the Setup Hub ------------------------------------------------------------------------------

    private fun template(id: String, name: String, data: String) {
        templates[id] = json("""{"_id":"$id","name":"$name","created_at":1,"template_data":$data}""")
    }

    private suspend fun TestScope.hub(group: SetupGroup): DealMemoViewModel {
        val model = viewModel()
        model.start()
        model.onEvent(DealMemoEvent.Navigate(DealMemoRoute.SetupHub(group)))
        settle { model.state.value.templates.rows != null }
        return model
    }

    @Test
    fun `an empty group shows a new setup inline until it holds a named one`() = runTest(dispatcher) {
        val model = hub(SetupGroup.Union)
        assertEquals(SetupGroup.Union, model.state.value.hub.inlineFor)
        assertTrue(model.builder().mode.embedded)

        model.templateStore.upsert("draft-1", TemplatePatch(name = "TPL-DRFT-AB12", form = json("""{"union":"pact-bectu-tvda"}""")))
        assertEquals(SetupGroup.Union, model.state.value.hub.inlineFor, "the embed's own autosave draft never evicts it")
        assertNotNull(model.state.value.builder)

        model.templateStore.upsert("draft-1", TemplatePatch(name = "UK Camera"))
        assertNull(model.state.value.hub.inlineFor)
        assertNull(model.state.value.builder, "the list takes the tab back")
    }

    @Test
    fun `deleting a group's last setup asks first, then brings the inline create back`() = runTest(dispatcher) {
        template("t1", "UK Camera", """{"territory_union":{"agreement_identifier":"pact-bectu-tvda"}}""")
        val model = hub(SetupGroup.Union)
        assertNull(model.state.value.hub.inlineFor)
        val setup = model.state.value.templates.rows!!.single()

        model.onEvent(SetupHubEvent.AskDelete(setup))
        assertEquals(setup, model.state.value.hub.confirmDelete)
        assertTrue(calls.value.none { it.method == HttpMethod.Delete })

        model.onEvent(SetupHubEvent.ConfirmDelete)
        settle { model.state.value.hub.inlineFor == SetupGroup.Union }

        assertEquals(1, writes(HttpMethod.Delete, "/deal-memo/templates/t1").size)
        assertNull(model.state.value.hub.confirmDelete)
        assertTrue(model.state.value.templates.rows!!.isEmpty())
        assertTrue(model.builder().mode.embedded)
    }

    @Test
    fun `Create Deal Memo starts a deal from the setup, and an empty setup says so`() = runTest(dispatcher) {
        template("t1", "UK Camera", """{"territory_union":{"agreement_identifier":"pact-bectu-tvda"}}""")
        template("t2", "Blank", "{}")
        val model = hub(SetupGroup.Union)
        val rows = model.state.value.templates.rows!!

        model.onEvent(SetupHubEvent.Use(rows.first { it.id == "t2" }))
        assertEquals("This setup has nothing saved to use.", model.state.value.toast?.message)
        assertEquals(DealMemoRoute.SetupHub(SetupGroup.Union), model.state.value.route)

        model.onEvent(SetupHubEvent.Use(rows.first { it.id == "t1" }))
        assertEquals(
            DealMemoRoute.QuickDeal(templateId = "t1", exitTo = DealMemoRoute.SetupHub(SetupGroup.Union)),
            model.state.value.route,
        )
    }

    private companion object {
        const val NOW = 1_788_256_000_000L
        const val SETTLE_TRIES = 400
        const val SETTLE_STEP_MILLIS = 5L
    }
}

private class SetupMockEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
