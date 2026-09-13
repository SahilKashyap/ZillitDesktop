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
import com.zillit.desktop.feature.dealmemo.domain.preview.DealAttachment
import com.zillit.desktop.feature.dealmemo.domain.preview.EditAction
import com.zillit.desktop.feature.dealmemo.domain.preview.NominalCoding
import com.zillit.desktop.feature.dealmemo.domain.preview.NominalKind
import com.zillit.desktop.feature.dealmemo.domain.preview.passportList
import com.zillit.desktop.feature.dealmemo.ui.CrewFormEvent
import com.zillit.desktop.feature.dealmemo.ui.DealDocumentStore
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoRoute
import com.zillit.desktop.feature.dealmemo.ui.DealMemoViewModel
import com.zillit.desktop.feature.dealmemo.ui.DealMemoViewer
import com.zillit.desktop.feature.dealmemo.ui.DealSavedSignature
import com.zillit.desktop.feature.dealmemo.ui.DealTab
import com.zillit.desktop.feature.dealmemo.ui.DealToastTone
import com.zillit.desktop.feature.dealmemo.ui.MyDealEvent
import com.zillit.desktop.feature.dealmemo.ui.NominalsEvent
import com.zillit.desktop.feature.dealmemo.ui.PickedDealFile
import com.zillit.desktop.feature.dealmemo.ui.PreviewEvent
import com.zillit.desktop.feature.dealmemo.ui.RulesEvent
import com.zillit.desktop.feature.dealmemo.ui.preview.GateMode
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The deal page and "Complete your details", driven through the view model
 * over a mock engine: what each action writes, what it refuses to write, and
 * where the tool goes next.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DealPreviewFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val calls = mutableListOf<Triple<HttpMethod, String, JsonObject?>>()

    /** The one deal on the server; a crew-details save lays its body over it. */
    private var deal: JsonObject = Json.parseToJsonElement(
        """{"_id":"d1","status":"issued","deal_reference":"DM-1","user_id":"u-crew","created_by":"u-acc",
            "territory_union":{"territory_code":"uk","agreement_identifier":"non_union"},
            "crew_details":{"crew_name":"Amara Okafor","emp_status":"paye","email":"amara@example.com",
              "passport_attachment":[{"media":"p/one.pdf","bucket":"b","region":"r","name":"one.pdf"}]},
            "bank":{"name":"Barclays"},
            "overtimes":[{"row_id":"ot-1","nominal_code":"","source":{"id":"ot-1","label":"Overtime","rate_type":"multiplier","rate_amount":1.5,"basis":"hour"}}]}""",
    ).jsonObject

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        val body = (request.body as? TextContent)?.text?.let { Json.parseToJsonElement(it).jsonObject }
        calls += Triple(request.method, path, body)
        val answer = when {
            path.endsWith("/metadata") -> """{"status":1,"data":{"is_approver":false,"approval_tier_configs":[]}}"""
            path.endsWith("/deal-memo/deal/crew-details") -> {
                val sent = body ?: JsonObject(emptyMap())
                val before = deal["crew_details"]?.jsonObject.orEmpty()
                val crew = JsonObject(before + sent["crew_details"]?.jsonObject.orEmpty())
                deal = JsonObject(deal + ("crew_details" to crew))
                """{"status":1,"message":"personal_details_saved","data":$deal}"""
            }
            path.endsWith("/deal-memo/deal") && request.method == HttpMethod.Get -> """{"status":1,"data":$deal}"""
            path.endsWith("/deal-memo/deals/d1") && request.method == HttpMethod.Get -> """{"status":1,"data":$deal}"""
            path.endsWith("/deal-memo/deals") -> """{"status":1,"data":[$deal]}"""
            path.endsWith("/project-settings") -> """{"status":1,"data":{"settings":{}}}"""
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
        httpClient = HttpClientFactory.create({ PreviewMockEngineFactory(engine) }),
        headerProvider = { _, _, _, _ -> emptyMap() },
    )

    /** What the file chooser hands back next, and every upload asked for. */
    private var picked: List<PickedDealFile> = emptyList()
    private val uploads = mutableListOf<String>()

    private val store = object : DealDocumentStore {
        override suspend fun fetch(attachment: DealAttachment) = ZillitResult.Success(byteArrayOf(1))

        override suspend fun upload(
            fileName: String,
            contentType: String,
            bytes: ByteArray,
        ): ZillitResult<DealAttachment> {
            uploads += fileName
            return ZillitResult.Success(
                DealAttachment(
                    buildJsonObject {
                        put("media", "up/$fileName")
                        put("bucket", "b")
                        put("region", "r")
                        put("name", fileName)
                    },
                ),
            )
        }

        override suspend fun savedSignatures() = ZillitResult.Success(emptyList<DealSavedSignature>())

        override suspend fun saveSignature(png: ByteArray) = ZillitResult.Success(Unit)

        override suspend fun deleteSignature(id: String) = ZillitResult.Success(Unit)

        override suspend fun pickFiles(extensions: List<String>, multiple: Boolean) = picked
    }

    private val crew = DealMemoViewer("u-crew", "department_camera", hasViewAccess = true, rightsLoaded = true)

    private val accountant = DealMemoViewer(
        "u-acc",
        "department_accounts",
        hasPostingAccess = true,
        hasViewAccess = true,
        rightsLoaded = true,
    )

    private fun viewModel(viewer: DealMemoViewer) = DealMemoViewModel(
        repository = DealMemoRepositoryImpl(
            apiClient,
            config,
            files = DealFileFetcher { _, _ -> ZillitResult.Success(byteArrayOf(1)) },
        ),
        reference = DealReferenceSource(apiClient, config),
        viewer = { viewer },
        clock = { NOW },
        metadataRetryDelays = listOf(10L),
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

    private fun count(method: HttpMethod, suffix: String) = calls.count {
        it.first == method && it.second.endsWith(suffix)
    }

    /** A crew member on "Complete your details" over their own deal. */
    private suspend fun TestScope.crewForm(): DealMemoViewModel {
        val model = viewModel(crew)
        model.start()
        settle { model.state.value.myDeal.loaded && model.state.value.preview?.deal != null }
        model.onEvent(DealMemoEvent.Navigate(DealMemoRoute.CompleteDetails))
        settle { model.state.value.preview?.crewForm != null }
        return model
    }

    // -- complete your details ----------------------------------------------------------------------

    @Test
    fun `Save refuses a malformed email, then sends the whole draft and returns to My Deal`() = runTest(dispatcher) {
        val model = crewForm()

        model.onEvent(CrewFormEvent.Crew("email", JsonPrimitive("amara@example")))
        model.onEvent(CrewFormEvent.Save)
        advanceUntilIdle()

        assertTrue(model.state.value.preview?.crewForm?.submitAttempted == true, "every error now shows")
        assertEquals(0, count(HttpMethod.Patch, "/deal/crew-details"))

        model.onEvent(CrewFormEvent.Crew("email", JsonPrimitive("amara.okafor@example.com")))
        model.onEvent(CrewFormEvent.Save)
        settle { model.state.value.preview?.crewForm == null }

        val sent = calls.last { it.second.endsWith("/deal/crew-details") }.third
        assertEquals(
            "amara.okafor@example.com",
            sent?.get("crew_details")?.jsonObject?.get("email")?.jsonPrimitive?.content,
        )
        assertTrue(sent?.containsKey("bank") == true)
        assertEquals(DealMemoRoute.Tab(DealTab.MyDeal), model.state.value.route)
        assertNull(model.state.value.preview?.crewDraft)
        val mine = model.state.value.myDeal.deal?.crew?.get("email")?.jsonPrimitive?.content
        assertEquals("amara.okafor@example.com", mine, "My Deal shows the saved deal at once")
    }

    @Test
    fun `the topbar Save does nothing for an unchanged draft, Save and Finish still saves`() = runTest(dispatcher) {
        val model = crewForm()

        model.onEvent(CrewFormEvent.Save)
        advanceUntilIdle()
        assertEquals(0, count(HttpMethod.Patch, "/deal/crew-details"))

        model.onEvent(CrewFormEvent.Finish)
        settle { count(HttpMethod.Patch, "/deal/crew-details") == 1 && model.state.value.preview?.crewForm == null }
        assertEquals(1, count(HttpMethod.Patch, "/deal/crew-details"))
    }

    @Test
    fun `leaving with unsaved changes asks first, and discarding writes nothing`() = runTest(dispatcher) {
        val model = crewForm()
        model.onEvent(CrewFormEvent.Crew("preferred_name", JsonPrimitive("Amara O.")))

        model.onEvent(CrewFormEvent.Close)
        assertTrue(model.state.value.preview?.crewForm?.discardPrompt == true)
        assertEquals(DealMemoRoute.CompleteDetails, model.state.value.route)

        model.onEvent(CrewFormEvent.KeepEditing)
        assertFalse(model.state.value.preview?.crewForm?.discardPrompt == true)

        model.onEvent(CrewFormEvent.Close)
        model.onEvent(CrewFormEvent.DiscardChanges)
        advanceUntilIdle()

        assertNull(model.state.value.preview?.crewDraft)
        assertEquals(DealMemoRoute.Tab(DealTab.MyDeal), model.state.value.route)
        assertEquals(0, count(HttpMethod.Patch, "/deal/crew-details"))
    }

    @Test
    fun `a Continue with a format error stays put and names it, and the rail is never guarded`() = runTest(dispatcher) {
        val model = crewForm()
        model.onEvent(CrewFormEvent.Crew("mobile", JsonPrimitive("12")))

        model.onEvent(CrewFormEvent.Continue)
        assertEquals(0, model.state.value.preview?.crewForm?.step)
        assertTrue(model.state.value.preview?.crewForm?.submitAttempted == true)

        model.onEvent(CrewFormEvent.GoToStep(3))
        assertEquals(3, model.state.value.preview?.crewForm?.step)
    }

    @Test
    fun `passports upload on pick, two at most, PDF JPG or PNG only`() = runTest(dispatcher) {
        val model = crewForm()

        picked = listOf(PickedDealFile("notes.txt", byteArrayOf(1), "text/plain"))
        model.onEvent(CrewFormEvent.AddPassport)
        settle { model.state.value.toast != null }
        assertEquals("Please upload a PDF, JPG or PNG file.", model.state.value.toast?.message)
        assertTrue(uploads.isEmpty())

        picked = listOf(
            PickedDealFile("scan.png", byteArrayOf(1), "image/png"),
            PickedDealFile("extra.pdf", byteArrayOf(2), "application/pdf"),
        )
        model.onEvent(CrewFormEvent.AddPassport)
        settle { passportList(model.state.value.preview?.crewDraft?.crewDetails?.get("passport_attachment")).size == 2 }

        val files = passportList(model.state.value.preview?.crewDraft?.crewDetails?.get("passport_attachment"))
        assertEquals(
            listOf("one.pdf", "scan.png"),
            files.map { it.name },
            "appended, and only as many as there is room for",
        )
        assertEquals("image", files.last().contentType)
        assertEquals(listOf("scan.png"), uploads)

        model.onEvent(CrewFormEvent.AddPassport)
        advanceUntilIdle()
        assertEquals("You can upload up to 2 files.", model.state.value.toast?.message)
        assertEquals(DealToastTone.Error, model.state.value.toast?.tone)
    }

    // -- the deal page ---------------------------------------------------------------------------------

    @Test
    fun `Send for Approval with anything missing opens Not ready yet and sends nothing`() = runTest(dispatcher) {
        val model = viewModel(crew)
        model.start()
        settle { model.state.value.preview?.deal != null }

        model.onEvent(PreviewEvent.SendForApproval)
        advanceUntilIdle()

        assertEquals(GateMode.Send, model.state.value.preview?.gate)
        assertEquals(0, count(HttpMethod.Post, "/send-for-approval"))
    }

    @Test
    fun `a crew reject sends the trimmed reason, closes and refetches the deal`() = runTest(dispatcher) {
        val model = viewModel(crew)
        model.start()
        settle { model.state.value.preview?.deal != null }

        model.onEvent(PreviewEvent.OpenReject)
        model.onEvent(PreviewEvent.RejectReason("  Wrong day rate  "))
        model.onEvent(PreviewEvent.ConfirmReject)
        settle { model.state.value.preview?.reject == null }

        val sent = calls.single { it.second.endsWith("/deal/reject-crew") }.third
        assertEquals("Wrong day rate", sent?.get("reason")?.jsonPrimitive?.content)
        assertEquals(1, count(HttpMethod.Get, "/deals/d1"))
    }

    @Test
    fun `an amendment is acknowledged only while one is pending`() = runTest(dispatcher) {
        val model = viewModel(crew)
        model.start()
        settle { model.state.value.preview?.deal != null }

        model.onEvent(PreviewEvent.Acknowledge)
        advanceUntilIdle()
        assertEquals(0, count(HttpMethod.Post, "/deal/acknowledge-amendment"), "nothing to acknowledge")

        deal = JsonObject(deal + ("amendment_ack" to Json.parseToJsonElement("""{"status":"pending"}""")))
        model.onEvent(MyDealEvent.Retry)
        settle { model.state.value.preview?.deal?.json?.containsKey("amendment_ack") == true }

        model.onEvent(PreviewEvent.Acknowledge)
        settle {
            count(HttpMethod.Post, "/deal/acknowledge-amendment") == 1 &&
                model.state.value.preview?.acknowledging == false
        }
        assertEquals(JsonObject(emptyMap()), calls.last { it.second.endsWith("/acknowledge-amendment") }.third)
    }

    @Test
    fun `an accountant re-codes a line and the codes are saved against the deal`() = runTest(dispatcher) {
        val model = viewModel(accountant)
        model.start()
        model.onEvent(DealMemoEvent.Navigate(DealMemoRoute.Deal("d1")))
        settle { model.state.value.preview?.deal != null }

        model.onEvent(PreviewEvent.Edit(EditAction.Nominals))
        val editor = assertNotNull(model.state.value.preview?.nominals)
        val opened = assertNotNull(model.state.value.preview?.deal)
        val row = NominalCoding.rowsFromDeal(opened, editor.form).first { it.kind == NominalKind.Overtime }
        model.onEvent(NominalsEvent.Code(row, "4499"))
        model.onEvent(NominalsEvent.Save)
        settle { model.state.value.preview?.nominals == null }

        val sent = calls.single { it.second.endsWith("/deals/d1/nominal-codes") }.third
        assertTrue(sent.toString().contains("4499"))
    }

    @Test
    fun `the rules grid saves the deal's rules, and crew can't open it at all`() = runTest(dispatcher) {
        val model = viewModel(accountant)
        model.start()
        model.onEvent(DealMemoEvent.Navigate(DealMemoRoute.Deal("d1")))
        settle { model.state.value.preview?.deal != null }

        model.onEvent(PreviewEvent.Edit(EditAction.Rules))
        assertNotNull(model.state.value.preview?.rules)
        model.onEvent(RulesEvent.Save)
        settle { model.state.value.preview?.rules == null }
        assertTrue(calls.single { it.second.endsWith("/deals/d1/deal-rules") }.third?.containsKey("overtimes") == true)

        val crewModel = viewModel(crew)
        crewModel.start()
        settle { crewModel.state.value.preview?.deal != null }
        crewModel.onEvent(PreviewEvent.Edit(EditAction.Rules))
        crewModel.onEvent(PreviewEvent.Edit(EditAction.Nominals))
        assertNull(crewModel.state.value.preview?.rules)
        assertNull(crewModel.state.value.preview?.nominals)
    }

    private companion object {
        const val NOW = 1_786_000_000_000L
        const val SETTLE_TRIES = 300
        const val SETTLE_STEP_MILLIS = 5L
    }
}

private class PreviewMockEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
