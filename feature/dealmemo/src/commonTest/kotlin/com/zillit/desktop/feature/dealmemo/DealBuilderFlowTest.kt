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
import com.zillit.desktop.feature.dealmemo.domain.authoring.DealValidators
import com.zillit.desktop.feature.dealmemo.domain.preview.DealCompany
import com.zillit.desktop.feature.dealmemo.domain.preview.DealCountry
import com.zillit.desktop.feature.dealmemo.domain.preview.DealUnit
import com.zillit.desktop.feature.dealmemo.ui.BuilderEvent
import com.zillit.desktop.feature.dealmemo.ui.DealCoaAccount
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoRoute
import com.zillit.desktop.feature.dealmemo.ui.DealMemoViewModel
import com.zillit.desktop.feature.dealmemo.ui.DealMemoViewer
import com.zillit.desktop.feature.dealmemo.ui.DealProductionData
import com.zillit.desktop.feature.dealmemo.ui.DealProjectInfo
import com.zillit.desktop.feature.dealmemo.ui.DealTab
import com.zillit.desktop.feature.dealmemo.ui.SetupGroup
import com.zillit.desktop.feature.dealmemo.ui.builder.BuilderAutosave
import com.zillit.desktop.feature.dealmemo.ui.builder.NameIntent
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
 * The one-page builder driven through the view model over a mock server:
 * what each page loads and seeds, when it saves itself, what Save, Issue and
 * Save Setup write, and what leaving deletes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DealBuilderFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private data class Call(
        val method: HttpMethod,
        val path: String,
        val query: Map<String, String>,
        val body: JsonElement?,
    )

    /** Every request, appended atomically — the client answers off the test thread. */
    private val calls = MutableStateFlow<List<Call>>(emptyList())

    /** The setups the server holds, by id. */
    private val templates = mutableMapOf(
        "t1" to Json.parseToJsonElement(
            """{"_id":"t1","name":"UK Camera","created_by":"u-acc","created_at":1,
               "template_data":{"user_id":"u-someone","is_external":true,"bank":{"name":"Barclays"},
                 "company_id":"co-1",
                 "territory_union":{"prod_entity":"co-1","territory_code":"uk","agreement_identifier":"pact-bectu-tvda"},
                 "crew_details":{"emp_status":"paye","crew_type":"shoot_crew","full_legal_name":"Someone Else","department_identifier":"department_camera"},
                 "deal":{"type":"weekly","additional_notes":"From the setup"},
                 "credit_conditions":{"custom_conditions":["Own clause"]}}}""",
        ).jsonObject,
    )

    private var nonUnionRules = false

    private fun settings(): String = """{"settings":{
        "companies":[{"id":"co-1","name":"Acme Productions","country":"United Kingdom"}],
        "project_currencies":{"default":"GBP","currencies":[{"code":"GBP","exr":1},{"code":"EUR","exr":1.17}]},
        "production":{
          "allowances_rentals":{"allowances":[{"id":"ps-1","name":"Per Diem","amount":35,"basis":"day","applies_to":"shoot","nominal_code":"4400","enable":true}],"rentals":[]},
          "standard_deal_conditions":[{"order":0,"condition":"Travel paid at agreed rate"}],
          "production_schedule":{"start_date":1788220800000,"end_date":1793491200000},
          "non_union_paybreakdown":{"overtimes":${if (nonUnionRules) """[{"id":"ot-1","label":"OT"}]""" else "[]"},"premiums":[],"penalties":[]}
        }}}"""

    private var dealCounter = 0

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        val body = (request.body as? TextContent)?.text?.let { Json.parseToJsonElement(it) }
        val query = request.url.parameters.names().associateWith { request.url.parameters[it].orEmpty() }
        calls.update { it + Call(request.method, path, query, body) }
        val method = request.method
        val answer = when {
            path.endsWith("/metadata") -> """{"status":1,"data":{"is_approver":false,"approval_tier_configs":[]}}"""
            path.endsWith("/project-settings") && method == HttpMethod.Get -> """{"status":1,"data":${settings()}}"""
            path.contains("/project-settings/") -> """{"status":1,"message":"ok"}"""
            path.endsWith("/deal-memo/templates") && method == HttpMethod.Get ->
                """{"status":1,"data":[${templates.values.joinToString(",") { row ->
                    """{"_id":${row["_id"]},"name":${row["name"]},"created_by":"u-acc","created_at":1}"""
                }}]}"""
            path.endsWith("/deal-memo/templates") && method == HttpMethod.Post -> {
                val sent = body!!.jsonObject
                val id = sent["_id"]!!.jsonPrimitive.content
                templates[id] = JsonObject(
                    mapOf("_id" to JsonPrimitive(id), "name" to sent["name"]!!, "template_data" to sent),
                )
                """{"status":1,"message":"template_saved","data":{"_id":"$id"}}"""
            }
            path.contains("/deal-memo/templates/") && method == HttpMethod.Patch -> {
                val id = path.substringAfterLast('/')
                val sent = body!!.jsonObject
                val name = sent["name"] ?: templates[id]?.get("name") ?: JsonPrimitive("")
                templates[id] = JsonObject(mapOf("_id" to JsonPrimitive(id), "name" to name, "template_data" to sent))
                """{"status":1,"message":"template_updated","data":{"_id":"$id"}}"""
            }
            path.contains("/deal-memo/templates/") && method == HttpMethod.Get ->
                """{"status":1,"data":${templates[path.substringAfterLast('/')]}}"""
            path.contains("/deal-memo/templates/") && method == HttpMethod.Delete ->
                """{"status":1,"message":"template_deleted"}"""
            path.endsWith("/deal-memo/deals") && method == HttpMethod.Post -> {
                dealCounter += 1
                val id = body!!.jsonObject["_id"]!!.jsonPrimitive.content
                """{"status":1,"message":"deal_saved","data":{"_id":"$id","deal_reference":"DRFT-$dealCounter"}}"""
            }
            path.endsWith("/deal-memo/deals") ->
                """{"status":1,"data":[{"_id":"d9","user_id":"u-taken","status":"issued"},{"_id":"d8","user_id":"u-free","status":"completed"}]}"""
            path.endsWith("/submit") -> """{"status":1,"message":"deal_issued"}"""
            path.contains("/deal-memo/deals/") && method == HttpMethod.Patch ->
                """{"status":1,"message":"deal_saved","data":{"_id":"${path.substringAfterLast('/')}","deal_reference":"DRFT-1"}}"""
            path.contains("/deal-memo/deals/") && method == HttpMethod.Delete ->
                """{"status":1,"message":"deal_deleted"}"""
            path.endsWith("/agreements") -> """{"status":1,"data":{"union":[{"_identifier":"pact-bectu-tvda","name":"PACT/BECTU TV Drama","short_label":"TVDA"}],
                "emp_statuses":[{"id":"paye","label":"PAYE"},{"id":"ltd","label":"Loan-out"}]}}"""
            path.endsWith("/agreements/pact-bectu-tvda") ->
                """{"status":1,"data":{"_identifier":"pact-bectu-tvda","name":"PACT/BECTU TV Drama","union_identifier":"bectu"}}"""
            path.endsWith("/covered-territories") -> """{"status":1,"data":["uk"]}"""
            path.endsWith("/departments") -> """{"status":1,"data":[{"_id":"dep-1","identifier":"department_camera","department_name":"camera_label",
                "designations":[{"_id":"des-1","identifier":"designation_focus_puller","designation_name":"focus_puller_label"}]}]}"""
            path.endsWith("/project/users") ->
                """{"status":1,"data":[{"user_id":"u-free","full_name":"Sam Free","status":"accepted","department_id":"dep-1","designation_id":"des-1"}]}"""
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
        httpClient = HttpClientFactory.create({ BuilderMockEngineFactory(engine) }),
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

    private val accountant = DealMemoViewer(
        "u-acc",
        "department_accounts",
        hasPostingAccess = true,
        hasViewAccess = true,
        rightsLoaded = true,
    )

    private fun viewModel() = DealMemoViewModel(
        repository = DealMemoRepositoryImpl(
            apiClient,
            config,
            files = DealFileFetcher { _, _ -> ZillitResult.Success(byteArrayOf(1)) },
        ),
        reference = DealReferenceSource(apiClient, config),
        viewer = { accountant },
        clock = { NOW },
        metadataRetryDelays = listOf(10L),
        productionData = production,
        workDispatcher = dispatcher,
    )

    /** Runs every timer and waits on the mock server until [condition] holds. */
    private suspend fun TestScope.settle(condition: () -> Boolean) {
        repeat(SETTLE_TRIES) {
            advanceUntilIdle()
            if (condition()) return
            withContext(Dispatchers.Default) { delay(SETTLE_STEP_MILLIS) }
        }
        advanceUntilIdle()
    }

    /** The setup PATCHes that carry a name — Save Setup's, never autosave's. */
    private fun namedPatches(id: String) = writes(HttpMethod.Patch, "/templates/$id").filter {
        it.body?.jsonObject?.containsKey("name") == true
    }

    private fun writes(method: HttpMethod, suffix: String) = calls.value.filter {
        it.method == method && it.path.endsWith(suffix)
    }

    private fun DealMemoViewModel.builder() = assertNotNull(state.value.builder)

    /** A deal from the Create menu's Union item, loaded and seeded. */
    private suspend fun TestScope.unionDeal(): DealMemoViewModel {
        val model = viewModel()
        model.start()
        model.onEvent(
            DealMemoEvent.Navigate(
                DealMemoRoute.QuickDeal(group = SetupGroup.Union, exitTo = DealMemoRoute.Tab(DealTab.Deals)),
            ),
        )
        settle {
            val builder = model.state.value.builder
            builder != null && builder.pickedTemplateId == "t1" && builder.form.list("allowances").isNotEmpty() &&
                builder.takenUserIds.isNotEmpty()
        }
        return model
    }

    // -- a deal page ------------------------------------------------------------------------------------

    @Test
    fun `a lone setup seeds the deal, stripped, with Production Setup's defaults laid over`() = runTest(dispatcher) {
        val model = unionDeal()
        val form = model.builder().form

        assertEquals("pact-bectu-tvda", form.text("union"))
        assertEquals("paye", form.text("employmentStatus"), "the employment status survives the strip")
        assertEquals("", form.text("userId"), "nobody's person carries over")
        assertEquals("", form.text("fullLegalName"))
        assertFalse(form.flag("isExternal"))
        assertEquals("", form.obj("bank")?.get("name")?.jsonPrimitive?.content.orEmpty(), "nor their bank")
        assertEquals("From the setup", form.text("additionalNotes"))
        val perDiem = form.objects("allowances").single()
        assertEquals("ps-1", perDiem["id"]?.jsonPrimitive?.content)
        assertEquals("35", perDiem["rate"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("Travel paid at agreed rate", "Own clause"),
            form.list("customConditions").map { it.jsonPrimitive.content },
            "Production Setup's clauses go in front of the setup's own",
        )
        assertEquals("GBP", form.text("currency"))
        assertEquals("2026-09-01", form.text("dealMemoDate"), "today's date, UTC")
        assertEquals(setOf("u-taken"), model.builder().takenUserIds, "a completed deal releases its crew member")
        assertTrue(calls.value.none { it.method != HttpMethod.Get }, "loading and seeding write nothing")
    }

    @Test
    fun `autosave waits for an edit, creates once, then patches the minted id with no status`() = runTest(dispatcher) {
        val model = unionDeal()

        model.onEvent(BuilderEvent.SetField("additionalNotes", JsonPrimitive("Pick up at 6")))
        advanceTimeBy(BuilderAutosave.IDLE_MILLIS - 1_000)
        runCurrent()
        assertTrue(writes(HttpMethod.Post, "/deal-memo/deals").isEmpty(), "nothing before the idle timer")

        settle { writes(HttpMethod.Post, "/deal-memo/deals").size == 1 && model.builder().dealId != null }
        val create = writes(HttpMethod.Post, "/deal-memo/deals").single()
        val sent = assertNotNull(create.body?.jsonObject)
        val minted = sent["_id"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(Regex("^[0-9a-f]{24}$").matches(minted))
        assertEquals("draft", sent["status"]?.jsonPrimitive?.content)
        assertEquals("DM-DRFT-${minted.take(4).uppercase()}", sent["crew_name"]?.jsonPrimitive?.content)
        assertFalse(sent.containsKey("notify"), "notify rides the query for deals")
        assertEquals("false", create.query["notify"])
        assertEquals(minted, model.builder().dealId)
        assertFalse(model.builder().dirty)

        model.onEvent(BuilderEvent.SetField("additionalNotes", JsonPrimitive("Pick up at 7")))
        settle { writes(HttpMethod.Patch, "/deals/$minted").size == 1 }
        val patch = writes(HttpMethod.Patch, "/deals/$minted").single()
        assertFalse(patch.body!!.jsonObject.containsKey("status"), "a PATCH never sends a status")
        assertEquals(1, writes(HttpMethod.Post, "/deal-memo/deals").size, "created once")
    }

    @Test
    fun `Save on a nameless deal asks for a name, then saves the draft with today's memo date`() = runTest(dispatcher) {
        val model = unionDeal()

        model.onEvent(BuilderEvent.Save)
        assertEquals(NameIntent.Save, model.builder().nameCapture?.intent)
        assertTrue(writes(HttpMethod.Post, "/deal-memo/deals").isEmpty())

        model.onEvent(BuilderEvent.EditName("  Sarah Mitchell "))
        model.onEvent(BuilderEvent.ConfirmName)
        settle { model.builder().dealId != null && !model.builder().saving }

        val create = writes(HttpMethod.Post, "/deal-memo/deals").single()
        val sent = create.body!!.jsonObject
        assertEquals("true", create.query["notify"])
        assertEquals("Sarah Mitchell", sent["crew_details"]?.jsonObject?.get("full_legal_name")?.jsonPrimitive?.content)
        assertEquals(TODAY_EPOCH.toString(), sent["deal"]?.jsonObject?.get("deal_memo_date")?.jsonPrimitive?.content)
        assertNull(sent["crew_name"], "a named deal carries no draft placeholder")
        assertEquals("DRFT-1", model.builder().dealReference)
        assertEquals("Sarah Mitchell", model.builder().form.text("fullLegalName"))
    }

    @Test
    fun `Issue flags every incomplete section at once and opens the first`() = runTest(dispatcher) {
        val model = unionDeal()

        model.onEvent(BuilderEvent.Issue)

        val builder = model.builder()
        assertTrue(DealValidators.CREW in builder.issueErrors)
        assertTrue(DealValidators.PERSONAL in builder.issueErrors)
        assertTrue(DealValidators.RATES in builder.issueErrors)
        assertEquals(builder.issueErrors.keys.first(), builder.editingSection)
        assertEquals("Complete these sections before issuing", builder.validation?.title)
        assertTrue(builder.validation?.fields.orEmpty().any { it.startsWith("Crew Details — ") })
        assertNull(builder.issuePreview)

        model.onEvent(BuilderEvent.SetField("fullLegalName", JsonPrimitive("Sarah Mitchell")))
        assertFalse(DealValidators.PERSONAL in model.builder().issueErrors, "a section that now passes drops its flag")
    }

    @Test
    fun `Leave Without Saving deletes only the draft this visit's autosave created`() = runTest(dispatcher) {
        val model = unionDeal()
        model.onEvent(BuilderEvent.SetField("additionalNotes", JsonPrimitive("Pick up at 6")))
        settle { model.builder().dealId != null && !model.builder().dirty }
        val created = assertNotNull(model.builder().dealId)

        model.onEvent(BuilderEvent.SetField("additionalNotes", JsonPrimitive("Pick up at 8")))
        model.onEvent(BuilderEvent.Back)
        assertTrue(model.builder().leaveGuard)

        model.onEvent(BuilderEvent.LeaveWithoutSaving)
        settle { model.state.value.route == DealMemoRoute.Tab(DealTab.Deals) }

        assertEquals(1, calls.value.count { it.method == HttpMethod.Delete && it.path.endsWith("/deals/$created") })
        assertNull(model.state.value.builder)
        advanceTimeBy(BuilderAutosave.MAX_WAIT_MILLIS * 2)
        runCurrent()
        assertTrue(writes(HttpMethod.Patch, "/deals/$created").isEmpty(), "a discarded page never writes again")
    }

    // -- a setup page -------------------------------------------------------------------------------------

    @Test
    fun `a new union setup takes the sole company's territory, refuses without an agreement, then saves by name`() =
        runTest(dispatcher) {
            val model = viewModel()
            model.start()
            model.onEvent(DealMemoEvent.Navigate(DealMemoRoute.SetupHub(SetupGroup.Union, DealMemoRoute.NEW)))
            settle { model.state.value.builder?.form?.text("territory") == "uk" }

            val form = model.builder().form
            assertEquals("co-1", form.text("productionEntity"))
            assertFalse(model.builder().dirty, "defaults the page fills in never make it dirty")

            model.onEvent(BuilderEvent.SaveSetup)
            assertEquals("Union Setup for United Kingdom is incomplete", model.builder().validation?.title)
            assertEquals(listOf("Agreement"), model.builder().validation?.fields)
            model.onEvent(BuilderEvent.CloseValidation)

            model.onEvent(BuilderEvent.SetField("union", JsonPrimitive("pact-bectu-tvda")))
            settle { model.builder().autosavedTemplateId != null }
            val draftId = assertNotNull(model.builder().autosavedTemplateId)
            val draft = writes(HttpMethod.Post, "/deal-memo/templates").single().body!!.jsonObject
            assertTrue(draft["name"]?.jsonPrimitive?.content.orEmpty().startsWith("TPL-DRFT-"))
            assertNull(draft["crew_name"], "no deal placeholder in a setup")

            model.onEvent(BuilderEvent.SaveSetup)
            assertTrue(model.builder().setupNamePrompt)
            model.onEvent(BuilderEvent.EditSetupName("UK Camera — Standard"))
            model.onEvent(BuilderEvent.ConfirmSetupName)
            settle { model.state.value.route == DealMemoRoute.SetupHub(SetupGroup.Union) }

            val rename = namedPatches(draftId).single().body!!.jsonObject
            assertEquals("UK Camera — Standard", rename["name"]?.jsonPrimitive?.content)
            assertEquals(
                1,
                writes(HttpMethod.Post, "/deal-memo/templates").size,
                "the autosaved row is renamed, not duplicated",
            )
            val row = model.state.value.templates.rows?.firstOrNull { it.id == draftId }
            assertEquals("UK Camera — Standard", row?.name)
            val bureau = writes(HttpMethod.Patch, "/project-settings/payroll-bureau").singleOrNull()
            assertEquals(
                JsonArray(emptyList()),
                bureau?.body,
                "Global sections that differ from the project are written in full",
            )
            assertTrue(
                writes(HttpMethod.Patch, "/project-settings/allowances-rentals").isEmpty(),
                "unchanged sections are left alone",
            )
        }

    @Test
    fun `a saved setup opens raw and updates without asking for its name again`() = runTest(dispatcher) {
        val model = viewModel()
        model.start()
        model.onEvent(DealMemoEvent.Navigate(DealMemoRoute.SetupHub(SetupGroup.Union, "t1")))
        settle { model.state.value.builder?.editLoading == false }

        val builder = model.builder()
        assertEquals("UK Camera", builder.templateName)
        assertTrue(builder.form.flag("isExternal"), "a setup being edited keeps every field it saved")
        assertEquals("Barclays", builder.form.obj("bank")?.get("name")?.jsonPrimitive?.content)
        assertEquals("Someone Else", builder.form.text("fullLegalName"))

        model.onEvent(BuilderEvent.SetField("additionalNotes", JsonPrimitive("Updated")))
        model.onEvent(BuilderEvent.SaveSetup)
        assertFalse(model.builder().setupNamePrompt)
        settle { model.state.value.route == DealMemoRoute.SetupHub(SetupGroup.Union) }

        val update = namedPatches("t1").single().body!!.jsonObject
        assertEquals("UK Camera", update["name"]?.jsonPrimitive?.content)
        assertEquals("Updated", update["deal"]?.jsonObject?.get("additional_notes")?.jsonPrimitive?.content)
        assertTrue(writes(HttpMethod.Post, "/deal-memo/templates").isEmpty())
    }

    @Test
    fun `a new non-union setup is locked to non-union and needs the project's pay rules`() = runTest(dispatcher) {
        val model = viewModel()
        model.start()
        model.onEvent(DealMemoEvent.Navigate(DealMemoRoute.SetupHub(SetupGroup.NonUnion, DealMemoRoute.NEW)))
        settle { model.state.value.builder?.form?.text("productionEntity") == "co-1" }

        assertEquals("non_union", model.builder().form.text("union"))
        assertTrue(model.builder().mode.unionLocked(editLoading = false))
        model.onEvent(BuilderEvent.SaveSetup)
        assertEquals("Non-Union Setup is incomplete", model.builder().validation?.title)
        assertEquals(listOf("Non-Union Pay Rules"), model.builder().validation?.fields)
        assertEquals(
            listOf(13, 5, 7, 8, 11),
            model.builder().sections(accountant = true).drop(1).map { it.id },
            "the non-union rules band sits between Territory & Union and the Global sections",
        )
    }

    private companion object {
        /** 2026-09-01T09:46:40Z. */
        const val NOW = 1_788_256_000_000L
        const val TODAY_EPOCH = 1_788_220_800_000L
        const val SETTLE_TRIES = 400
        const val SETTLE_STEP_MILLIS = 5L
    }
}

private class BuilderMockEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
