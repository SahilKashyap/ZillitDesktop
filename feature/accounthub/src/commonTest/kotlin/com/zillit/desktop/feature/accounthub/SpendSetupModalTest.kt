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
import com.zillit.desktop.feature.accounthub.domain.AssignmentRule
import com.zillit.desktop.feature.accounthub.domain.CardProvider
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.SpendKind
import com.zillit.desktop.feature.accounthub.ui.AccountHubEffect
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubViewModel
import com.zillit.desktop.feature.accounthub.ui.SetupModal
import com.zillit.desktop.feature.accounthub.ui.SpendDraft
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
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

private const val TRIES = 200
private const val STEP_MILLIS = 5L
private const val CARD_PATH = "/api/v2/card-expenses/settings"
private const val CASH_PATH = "/api/v2/cash-expenses/settings"

/** A production configured on the web: a senior, a ceiling, a submit-only, and keys this modal does not edit. */
private const val CARD_DOC = """
{"team_members":[
  {"user_id":"u1","posting_limit":null,"can_override":true,"is_senior":true,"name":"Ann"},
  {"user_id":"u2","posting_limit":250,"can_override":false,"is_senior":false,"note":"keep me"},
  {"user_id":"u3","posting_limit":0,"can_override":false,"is_senior":false}],
 "department_coordinators":[{"department_id":"d1","user_ids":["u2"],"coding_required":true}],
 "approval_override":{"override_card_req":true,"override_receipt":false,"custom_flag":"keep"},
 "card_providers":[{"id":"p1","name":"Barclays","bank_id":"b-1","company_id":"co-1","custodian_account":"1200",
   "float_min":"1000","float_max":"1999","issuer_note":"keep"}],
 "request_cap":{"enabled":true,"basis":"fixed","max_amount":500},
 "deduction_rules":[{"id":"r1","type":"fuel_deduction","title":"Fuel","process_type":"deduct_amount",
   "threshold_type":"percentage","threshold_value":20,"enable":true,"trigger_codes":["fuel"],
   "system_default":true,"currency":"GBP"}],
 "quick_codes":[{"id":"q1","name":"Fuel","nominal_code":"5000","keywords":["fuel","petrol"],"vat":20}],
 "some_future_key":"untouched"}
"""

private const val CASH_DOC = """
{"float_custodian_account":"1100","bs_code_from":"1000","bs_code_to":"1999",
 "team_members":[{"user_id":"u2","posting_limit":100,"can_override":false,"is_senior":false}],
 "department_coordinators":[{"department_id":"d1","user_ids":["u2"],"coding_required":false,
   "view_department_floats":true}],
 "approval_override":{"override_float_req":true,"require_senior_sign_off":true},
 "reimburse_to_payroll":true}
"""

private enum class PatchAnswer { Keep, Refuse }

/**
 * The Card and Petty Cash Entry Setup modals end to end: what the read puts on
 * screen, what a save puts on the wire, and what a refusal, a failed read or a
 * dropped key does to the section.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpendSetupModalTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val calls = MutableStateFlow<List<String>>(emptyList())
    private val writes = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    private var cardDoc = Json.parseToJsonElement(CARD_DOC).jsonObject
    private var cashDoc = Json.parseToJsonElement(CASH_DOC).jsonObject
    private var settingsDown = false
    private var patchAnswer = PatchAnswer.Keep

    /** Keys the server will answer "saved" to and then not store — a PATCH to something that is not a column. */
    private var dropKeys = emptySet<String>()

    // -- the read ---------------------------------------------------------------------------

    @Test
    fun `the card modal reads the card service's document, rules and all`() = runTest(dispatcher) {
        val model = openModal(SetupModal.CardExpenses)

        val value = model.state.value.setup.spendSetup.edited
        assertEquals(SpendKind.Cards, value.kind)
        assertEquals(listOf("u1", "u2", "u3"), value.team.map { it.userId })
        assertNull(value.team[0].postingLimit, "null is unlimited")
        assertEquals(250.0, value.team[1].postingLimit)
        assertEquals(0.0, value.team[2].postingLimit, "zero is submit only, not unlimited")
        assertTrue(value.team[0].isSenior)
        assertEquals("d1", value.coordinators.single().departmentId)
        assertTrue(value.coordinators.single().codingRequired)
        assertTrue(value.approvalOn("override_card_req"))
        assertFalse(value.approvalOn("require_coord_code"))
        assertEquals("Fuel", value.deductionRules.single().title, "the stored rules, not the web's defaults")
        assertEquals("fuel, petrol", value.quickCodes.single().keywordsText)
        assertEquals("p1", value.providers.single().id)
        assertEquals("1200", value.providers.single().custodianAccount)
        assertFalse(model.state.value.setup.modalDirty, "a fresh read is not an edit")
        assertTrue(calls.value.any { it == "GET $CARD_PATH" })
        assertTrue(
            calls.value.any { it.startsWith("GET /api/v2/account-hub/assignment-rules") && "card_expenses" in it },
            "the module's rules are read beside the document",
        )
    }

    @Test
    fun `the cash modal reads the cash service, with its custodian, range and four switches`() = runTest(dispatcher) {
        val model = openModal(SetupModal.PettyCash)

        val value = model.state.value.setup.spendSetup.edited
        assertEquals(SpendKind.Cash, value.kind)
        assertEquals("1100", value.custodianAccount)
        assertEquals("1000", value.bsCodeFrom)
        assertEquals("1999", value.bsCodeTo)
        assertTrue(value.coordinators.single().viewDepartmentFloats)
        assertTrue(value.approvalOn("override_float_req"))
        assertTrue(value.approvalOn("require_senior_sign_off"))
        assertEquals(4, value.deductionRules.size, "a production with none stored shows the web's four")
        assertEquals(4, value.quickCodes.size)
        assertTrue(calls.value.any { it == "GET $CASH_PATH" })
    }

    // -- the save -----------------------------------------------------------------------------

    @Test
    fun `a save sends only the changed key, null for unlimited, and keeps what the rows carry`() =
        runTest(dispatcher) {
            val model = openModal(SetupModal.CardExpenses)

            // Make u2 unlimited through the dialog, as a person would.
            model.onEvent(AccountHubEvent.ComposeSpendMember(1))
            val draft = model.state.value.setup.spendDraft as SpendDraft.Member
            model.onEvent(AccountHubEvent.EditSpendDraft(draft.copy(member = draft.member.copy(postingLimit = null))))
            model.onEvent(AccountHubEvent.CommitSpendDraft)
            assertTrue(model.state.value.setup.modalDirty)
            model.onEvent(AccountHubEvent.SaveSetupModal)
            settle { patches(CARD_PATH).isNotEmpty() && !model.state.value.setup.modalDirty }

            val body = Json.parseToJsonElement(patches(CARD_PATH).single()).jsonObject
            assertEquals(setOf("team_members"), body.keys, "nothing the person did not touch goes back")
            val team = body.getValue("team_members").jsonArray.map { it.jsonObject }
            assertIs<JsonNull>(team[1]["posting_limit"], "unlimited is an explicit null, never absent")
            assertEquals(0.0, team[2]["posting_limit"]?.jsonPrimitive?.doubleOrNull, "submit only stays zero")
            assertEquals("keep me", team[1]["note"]?.jsonPrimitive?.content, "a field this modal does not know stays")
            assertEquals("Ann", team[0]["name"]?.jsonPrimitive?.content)
            assertIs<JsonNull>(team[0]["posting_limit"], "a senior is unlimited")
            assertEquals(true, team[0]["can_override"]?.jsonPrimitive?.booleanOrNull)
            assertFalse(model.state.value.setup.modalDirty)
            assertEquals(JsonPrimitive("untouched"), cardDoc["some_future_key"], "the rest of the document is intact")
            assertEquals(1, writes.value.count { it.first.startsWith("PATCH") })
        }

    @Test
    fun `approval switches and providers keep their unknown keys and are sanitised like the web's`() =
        runTest(dispatcher) {
            val model = openModal(SetupModal.CardExpenses)
            val value = model.state.value.setup.spendSetup.edited

            val withSwitch = value.copy(approval = value.approval + ("require_coord_code" to true))
            // A blank row the person never filled in is dropped; a half-filled one has its empty codes sent as null.
            val extra = listOf(
                CardProvider(id = "p-blank"),
                CardProvider(id = "p2", name = " Amex ", bankId = "b-2"),
            )
            model.onEvent(AccountHubEvent.EditSpendSetup(withSwitch.copy(providers = value.providers + extra)))
            model.onEvent(AccountHubEvent.SaveSetupModal)
            settle { patches(CARD_PATH).isNotEmpty() && !model.state.value.setup.modalDirty }

            val body = Json.parseToJsonElement(patches(CARD_PATH).single()).jsonObject
            assertEquals(setOf("approval_override", "card_providers"), body.keys)
            val approval = body.getValue("approval_override").jsonObject
            assertEquals("keep", approval["custom_flag"]?.jsonPrimitive?.content, "an unknown toggle is not wiped")
            assertEquals(true, approval["require_coord_code"]?.jsonPrimitive?.booleanOrNull)
            assertEquals(true, approval["override_card_req"]?.jsonPrimitive?.booleanOrNull)
            val providers = body.getValue("card_providers").jsonArray.map { it.jsonObject }
            assertEquals(listOf("p1", "p2"), providers.map { it["id"]?.jsonPrimitive?.content })
            assertEquals("keep", providers[0]["issuer_note"]?.jsonPrimitive?.content)
            assertEquals("Amex", providers[1]["name"]?.jsonPrimitive?.content, "trimmed")
            assertEquals("b-2", providers[1]["bank_id"]?.jsonPrimitive?.content)
            assertIs<JsonNull>(providers[1]["company_id"], "an empty optional field is null")
            assertIs<JsonNull>(providers[1]["custodian_account"])
        }

    @Test
    fun `the cash modal writes its own keys to the cash service`() = runTest(dispatcher) {
        val model = openModal(SetupModal.PettyCash)
        val value = model.state.value.setup.spendSetup.edited

        model.onEvent(AccountHubEvent.EditSpendSetup(value.copy(custodianAccount = "1150")))
        model.onEvent(AccountHubEvent.SaveSetupModal)
        settle { patches(CASH_PATH).isNotEmpty() && !model.state.value.setup.modalDirty }

        assertTrue(patches(CARD_PATH).isEmpty(), "never the card service")
        val body = Json.parseToJsonElement(patches(CASH_PATH).single()).jsonObject
        assertEquals(setOf("float_custodian_account"), body.keys)
        assertEquals("1150", body["float_custodian_account"]?.jsonPrimitive?.content)
        assertEquals(JsonPrimitive(true), cashDoc["reimburse_to_payroll"], "the reimbursement flag is not this modal's")
    }

    @Test
    fun `a coordinator row carries the cash view-floats flag and a card one does not`() = runTest(dispatcher) {
        val cash = openModal(SetupModal.PettyCash)
        val value = cash.state.value.setup.spendSetup.edited
        val coordinator = value.coordinators.single().copy(viewDepartmentFloats = false)
        cash.onEvent(AccountHubEvent.EditSpendSetup(value.copy(coordinators = listOf(coordinator))))
        cash.onEvent(AccountHubEvent.SaveSetupModal)
        settle { patches(CASH_PATH).isNotEmpty() && !cash.state.value.setup.modalDirty }

        val row = Json.parseToJsonElement(patches(CASH_PATH).single()).jsonObject
            .getValue("department_coordinators").jsonArray.single().jsonObject
        assertEquals(false, row["view_department_floats"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("d1", row["department_id"]?.jsonPrimitive?.content)
    }

    // -- the guards -----------------------------------------------------------------------------

    @Test
    fun `a failed read blocks save, and nothing is written over the stored document`() = runTest(dispatcher) {
        settingsDown = true
        val model = openModal(SetupModal.CardExpenses, expectLoaded = false)
        assertNotNull(model.state.value.setup.modal?.loadError, "the modal says why it cannot be edited")

        val defaults = model.state.value.setup.spendSetup.edited
        model.onEvent(AccountHubEvent.EditSpendSetup(defaults.copy(team = emptyList())))
        model.onEvent(AccountHubEvent.SaveSetupModal)
        settle { false }

        assertTrue(writes.value.none { it.first.startsWith("PATCH") }, "a modal on defaults would erase the team")
    }

    @Test
    fun `a status 0 answer is a refusal, and the edit stays unsaved`() = runTest(dispatcher) {
        val model = openModal(SetupModal.CardExpenses)
        val effects = collectEffects(model)
        patchAnswer = PatchAnswer.Refuse
        val value = model.state.value.setup.spendSetup.edited
        model.onEvent(AccountHubEvent.EditSpendSetup(value.copy(team = value.team.drop(1))))
        model.onEvent(AccountHubEvent.SaveSetupModal)
        settle { patches(CARD_PATH).isNotEmpty() && !model.state.value.setup.modalSaving }

        assertEquals(1, patches(CARD_PATH).size)
        assertTrue(model.state.value.setup.modalDirty, "refused is not saved")
        assertFalse(model.state.value.setup.modalSaving)
        assertNull(model.state.value.notice)
        assertEquals(3, model.state.value.setup.spendSetup.saved.team.size)
        assertIs<AccountHubEffect.Failed>(effects.single())
    }

    @Test
    fun `a key the server answers saved and does not keep is reported, not celebrated`() = runTest(dispatcher) {
        // A document with no quick codes of its own: the web's four are shown, and the server has no column to keep.
        cardDoc = JsonObject(cardDoc - "quick_codes")
        val model = openModal(SetupModal.CardExpenses)
        val effects = collectEffects(model)
        dropKeys = setOf("quick_codes")
        val value = model.state.value.setup.spendSetup.edited
        val code = value.quickCodes.first()
        model.onEvent(AccountHubEvent.EditSpendSetup(value.copy(quickCodes = listOf(code.copy(name = "Diesel")))))
        model.onEvent(AccountHubEvent.SaveSetupModal)
        settle { patches(CARD_PATH).isNotEmpty() && !model.state.value.setup.modalSaving }

        assertTrue(model.state.value.setup.modalDirty, "the edit was lost, so it is still an edit")
        assertNull(model.state.value.notice, "no \"saved\" over a lost change")
        val effect = effects.single()
        assertIs<AccountHubEffect.Failed>(effect)
        assertTrue("quick_codes" in effect.message, "the key is named: ${effect.message}")
    }

    @Test
    fun `closing the modal discards its edits`() = runTest(dispatcher) {
        val model = openModal(SetupModal.CardExpenses)
        val value = model.state.value.setup.spendSetup.edited
        model.onEvent(AccountHubEvent.EditSpendSetup(value.copy(team = emptyList())))
        model.onEvent(AccountHubEvent.ComposeSpendRule(null))
        assertTrue(model.state.value.setup.modalDirty)

        model.onEvent(AccountHubEvent.CloseSetupModal)

        assertFalse(model.state.value.setup.spendSetup.dirty)
        assertNull(model.state.value.setup.spendDraft)
        assertNull(model.state.value.setup.modal)
        assertTrue(writes.value.isEmpty())
    }

    // -- the rules ---------------------------------------------------------------------------------

    @Test
    fun `a new auto-assignment rule is created under the module's key, as the other modals do`() =
        runTest(dispatcher) {
            val model = openModal(SetupModal.PettyCash)
            val rule = AssignmentRule(id = "rule-new-1", module = "cash_expenses", assignTo = "u2")
            model.onEvent(AccountHubEvent.EditSpendRules(listOf(rule)))
            assertTrue(model.state.value.setup.modalDirty)
            model.onEvent(AccountHubEvent.SaveSetupModal)
            settle { writes.value.any { it.first == "POST /api/v2/account-hub/assignment-rules" } }

            val created = writes.value.single { it.first == "POST /api/v2/account-hub/assignment-rules" }
            val body = Json.parseToJsonElement(created.second).jsonObject
            assertEquals("cash_expenses", body["module"]?.jsonPrimitive?.content)
            assertEquals("u2", body["target_user_id"]?.jsonPrimitive?.content)
            assertTrue(patches(CASH_PATH).isEmpty(), "the document did not change, so it was not written")
        }

    // -- the dialogs -------------------------------------------------------------------------------

    @Test
    fun `a senior member cascades to unlimited and override, and one person cannot be added twice`() =
        runTest(dispatcher) {
            val model = openModal(SetupModal.CardExpenses)
            model.onEvent(AccountHubEvent.ComposeSpendMember(null))
            val draft = model.state.value.setup.spendDraft as SpendDraft.Member
            assertEquals(0.0, draft.member.postingLimit, "a new member starts submit-only")

            model.onEvent(AccountHubEvent.EditSpendDraft(draft.copy(member = draft.member.copy(userId = "u2"))))
            model.onEvent(AccountHubEvent.CommitSpendDraft)
            assertEquals(3, model.state.value.setup.spendSetup.edited.team.size, "u2 is already on the team")
            assertIs<AccountHubEffect.Failed>(model.effects.first())

            val fresh = model.state.value.setup.spendDraft as SpendDraft.Member
            val senior = fresh.member.copy(userId = "u9", isSenior = true, postingLimit = 40.0)
            model.onEvent(AccountHubEvent.EditSpendDraft(fresh.copy(member = senior, limitText = "40")))
            model.onEvent(AccountHubEvent.CommitSpendDraft)

            val added = model.state.value.setup.spendSetup.edited.team.last()
            assertEquals("u9", added.userId)
            assertNull(added.postingLimit, "a senior is unlimited whatever the limit field said")
            assertTrue(added.canOverride)
            assertNull(model.state.value.setup.spendDraft)
        }

    @Test
    fun `a deduction rule edit keeps the stored row's other fields, and a non-deduction locks to a minimum`() =
        runTest(dispatcher) {
            val model = openModal(SetupModal.CardExpenses)
            model.onEvent(AccountHubEvent.ComposeSpendRule(0))
            val draft = model.state.value.setup.spendDraft as SpendDraft.Rule
            assertEquals("20", draft.valueText)
            val changed = draft.rule.withProcess("senior_review")
            assertEquals("min_amount", changed.thresholdType)
            model.onEvent(AccountHubEvent.EditSpendDraft(draft.copy(rule = changed, valueText = "75")))
            model.onEvent(AccountHubEvent.CommitSpendDraft)
            model.onEvent(AccountHubEvent.SaveSetupModal)
            settle { patches(CARD_PATH).isNotEmpty() && !model.state.value.setup.modalDirty }

            val body = Json.parseToJsonElement(patches(CARD_PATH).single()).jsonObject
            assertEquals(setOf("deduction_rules"), body.keys)
            val rule = body.getValue("deduction_rules").jsonArray.single().jsonObject
            assertEquals("senior_review", rule["process_type"]?.jsonPrimitive?.content)
            assertEquals("min_amount", rule["threshold_type"]?.jsonPrimitive?.content)
            assertEquals(75.0, rule["threshold_value"]?.jsonPrimitive?.doubleOrNull)
            assertEquals("GBP", rule["currency"]?.jsonPrimitive?.content, "a field the modal does not know stays")
            assertEquals(JsonArray(listOf(JsonPrimitive("fuel"))), rule["trigger_codes"])
            assertEquals(true, rule["enable"]?.jsonPrimitive?.booleanOrNull)
        }

    // -- harness ---------------------------------------------------------------------------------------

    /** Everything the model announces from here on; an effect is gone for good if nobody was listening. */
    private fun TestScope.collectEffects(model: AccountHubViewModel): List<AccountHubEffect> {
        val seen = mutableListOf<AccountHubEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.effects.collect { seen += it } }
        return seen
    }

    private fun patches(path: String): List<String> =
        writes.value.filter { it.first == "PATCH $path" }.map { it.second }

    private suspend fun TestScope.openModal(modal: SetupModal, expectLoaded: Boolean = true): AccountHubViewModel {
        val model = AccountHubViewModel(repository = repository(), viewer = { viewer() })
        model.start()
        model.onEvent(AccountHubEvent.Open(HubArea.ProductionSetup))
        settle { model.state.value.setup.loaded }
        model.onEvent(AccountHubEvent.OpenSetupModal(modal))
        settle { model.state.value.setup.modal?.let { !it.loading } == true }
        if (expectLoaded) assertNull(model.state.value.setup.modal?.loadError)
        return model
    }

    private fun answer(request: HttpRequestData): Pair<HttpStatusCode, String> {
        val path = request.url.encodedPath
        val query = request.url.encodedQuery.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
        val method = request.method
        calls.update { it + "${method.value} $path$query" }
        val body = (request.body as? TextContent)?.text.orEmpty()
        if (method != HttpMethod.Get) writes.update { it + ("${method.value} $path" to body) }
        val ok = HttpStatusCode.OK
        return when {
            path == CARD_PATH || path == CASH_PATH -> settings(path, method, body)
            path.endsWith("/assignment-rules") && method == HttpMethod.Get -> ok to """{"status":1,"data":[]}"""
            path.endsWith("/assignment-rules") ->
                ok to """{"status":1,"data":{"id":"rule-1","module":"cash_expenses","target_user_id":"u2"}}"""
            else -> ok to """{"status":1,"data":{"value":[]}}"""
        }
    }

    /** A document store with a PATCH that merges, as these services do, and can be told to forget keys. */
    private fun settings(path: String, method: HttpMethod, body: String): Pair<HttpStatusCode, String> {
        val card = path == CARD_PATH
        if (method == HttpMethod.Get) {
            return if (settingsDown) {
                HttpStatusCode.InternalServerError to "{}"
            } else {
                HttpStatusCode.OK to """{"status":1,"data":${if (card) cardDoc else cashDoc}}"""
            }
        }
        if (patchAnswer == PatchAnswer.Refuse) {
            return HttpStatusCode.OK to """{"status":0,"message":"settings_rejected"}"""
        }
        val sent = Json.parseToJsonElement(body).jsonObject.filterKeys { it !in dropKeys }
        if (card) cardDoc = JsonObject(cardDoc + sent) else cashDoc = JsonObject(cashDoc + sent)
        return HttpStatusCode.OK to """{"status":1,"data":${if (card) cardDoc else cashDoc}}"""
    }

    private fun repository(): AccountHubRepositoryImpl {
        val engine = MockEngine { request ->
            val (status, body) = answer(request)
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return AccountHubRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ SpendEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
        )
    }

    private fun viewer() = AccountHubViewer.from(
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
        "acc",
        isAccountant = true,
    )

    private suspend fun TestScope.settle(condition: () -> Boolean) {
        repeat(TRIES) {
            advanceUntilIdle()
            if (condition()) return
            withContext(Dispatchers.Default) { delay(STEP_MILLIS) }
        }
        advanceUntilIdle()
    }
}

private class SpendEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
