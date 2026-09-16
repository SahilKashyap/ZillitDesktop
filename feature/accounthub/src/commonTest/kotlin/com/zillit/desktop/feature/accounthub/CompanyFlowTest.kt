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
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubViewModel
import com.zillit.desktop.feature.accounthub.ui.SetupRemoval
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
import kotlinx.serialization.json.JsonObject
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

private const val SETTLE_TRIES = 200
private const val SETTLE_STEP_MILLIS = 5L

/** Two companies; the first was saved before the pension pair shipped and carries no legal name. */
private const val COMPANIES = """
{"status":1,"data":{"value":[
 {"id":"co-1","name":"Zillit Films","legal_name":"","country":"United Kingdom","country_code":"GB",
  "bank_ids":[],"tax_credits":["UK HETV"],
  "uk":{"paye_ref":"120/AB12345","accounts_office_ref":"120PA00012345",
        "pension_provider":"NEST","pension_scheme_ref":"SCH-1"}},
 {"id":"co-2","name":"Other Co","legal_name":"Other Co","country":"France","country_code":"FR",
  "bank_ids":["b-legacy"],"tax_credits":[],"uk":{}}
]}}
"""

/** A bank pointing at co-1 by `entity_id` only, and a legacy one linked through co-2's list. */
private const val BANKS = """
{"status":1,"data":[
 {"id":"b-1","name":"Barclays","entity_id":"co-1","account_holder_name":"Zillit Films",
  "account_number":"41508833","currency":{"code":"GBP","name":"Pound","symbol":"£"}},
 {"id":"b-legacy","name":"Old Bank","account_holder_name":"Other Co","account_number":"1",
  "currency":{"code":"EUR","name":"Euro","symbol":"€"}}
]}
"""

/**
 * The company editor's flow — what its Done and Remove actually send.
 *
 * ## The bugs these pin
 *
 * The web's Done persists at once; the desktop's used to fold the draft into
 * the list and wait for a section Save nobody saw. And the `uk` block is
 * written whole on every save — a key omitted from it is **cleared** server
 * side, so a client without the pension pair wiped both on each save.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompanyFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val calls = MutableStateFlow<List<String>>(emptyList())
    private val companyWrites = MutableStateFlow<List<JsonArray>>(emptyList())
    private val bankWrites = MutableStateFlow<List<JsonObject>>(emptyList())
    private var refuseCompanyWrite = false

    @Test
    fun `a company's banks are counted from entity_id too`() = runTest(dispatcher) {
        val model = openSetup()

        model.onEvent(AccountHubEvent.EditCompany(model.state.value.setup.companies.edited.first { it.id == "co-1" }))
        advanceUntilIdle()

        // Seeded from the union, so the next save converges bank_ids onto the bank that points here.
        assertEquals(listOf("b-1"), model.state.value.setup.companyDraft?.bankIds)
    }

    @Test
    fun `done persists at once, with the whole UK block and a backfilled legal name`() = runTest(dispatcher) {
        val model = openSetup()
        val company = model.state.value.setup.companies.edited.first { it.id == "co-1" }
        model.onEvent(AccountHubEvent.EditCompany(company))
        advanceUntilIdle()
        val draft = model.state.value.setup.companyDraft!!

        model.onEvent(AccountHubEvent.UpdateCompanyDraft(draft.copy(name = "Zillit Films Ltd")))
        model.onEvent(AccountHubEvent.CommitCompanyDraft)
        settle { companyWrites.value.isNotEmpty() && model.state.value.setup.companyDraft == null }

        val sent = companyWrites.value.single()
        val first = sent.first().jsonObject
        assertEquals("Zillit Films Ltd", first["name"]?.jsonPrimitive?.content)
        assertEquals("Zillit Films Ltd", first["legal_name"]?.jsonPrimitive?.content, "blank legal name = trading name")
        val uk = first["uk"]!!.jsonObject
        assertEquals(setOf("paye_ref", "accounts_office_ref", "pension_provider", "pension_scheme_ref"), uk.keys)
        assertEquals("NEST", uk["pension_provider"]?.jsonPrimitive?.content, "an unseen field must survive a save")
        assertEquals(listOf("b-1"), first["bank_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(2, sent.size, "the whole list goes, the other company untouched")
        assertNull(model.state.value.setup.companyDraft, "the dialog closes once the save lands")
        assertFalse(model.state.value.setup.companies.dirty, "the echo is the new baseline")
    }

    @Test
    fun `a refused save keeps the dialog open and the section dirty`() = runTest(dispatcher) {
        val model = openSetup()
        refuseCompanyWrite = true
        val company = model.state.value.setup.companies.edited.first { it.id == "co-1" }
        model.onEvent(AccountHubEvent.EditCompany(company))
        advanceUntilIdle()
        model.onEvent(AccountHubEvent.UpdateCompanyDraft(model.state.value.setup.companyDraft!!.copy(name = "Renamed")))

        model.onEvent(AccountHubEvent.CommitCompanyDraft)
        settle { companyWrites.value.isNotEmpty() && !model.state.value.setup.companies.saving }

        assertNotNull(model.state.value.setup.companyDraft, "the person can fix and retry")
        assertTrue(model.state.value.setup.companies.dirty, "and the section's own Save is the fallback")
    }

    @Test
    fun `a draft without a country is refused before anything is sent`() = runTest(dispatcher) {
        val model = openSetup()
        model.onEvent(AccountHubEvent.EditCompany(null))
        advanceUntilIdle()
        model.onEvent(AccountHubEvent.UpdateCompanyDraft(model.state.value.setup.companyDraft!!.copy(name = "New")))

        model.onEvent(AccountHubEvent.CommitCompanyDraft)
        advanceUntilIdle()

        assertTrue(companyWrites.value.isEmpty())
        assertNotNull(model.state.value.setup.companyDraft)
    }

    @Test
    fun `removing a company saves the list without it, and keeps the row when refused`() = runTest(dispatcher) {
        val model = openSetup()
        val company = model.state.value.setup.companies.edited.first { it.id == "co-2" }

        model.onEvent(AccountHubEvent.AskRemove(SetupRemoval.CompanyRow(company)))
        model.onEvent(AccountHubEvent.ConfirmRemove)
        settle { companyWrites.value.isNotEmpty() && !model.state.value.setup.companies.saving }

        assertEquals(listOf("co-1"), companyWrites.value.single().map { it.jsonObject["id"]?.jsonPrimitive?.content })
        assertEquals(listOf("co-1"), model.state.value.setup.companies.edited.map { it.id })
        assertNull(model.state.value.setup.removal)

        refuseCompanyWrite = true
        val remaining = model.state.value.setup.companies.edited.single()
        model.onEvent(AccountHubEvent.AskRemove(SetupRemoval.CompanyRow(remaining)))
        model.onEvent(AccountHubEvent.ConfirmRemove)
        settle { companyWrites.value.size == 2 && !model.state.value.setup.companies.saving }

        assertEquals(
            listOf("co-1"),
            model.state.value.setup.companies.edited.map { it.id },
            "not removed optimistically",
        )
    }

    @Test
    fun `a bank added from inside the company editor is linked onto the draft`() = runTest(dispatcher) {
        val model = openSetup()
        val company = model.state.value.setup.companies.edited.first { it.id == "co-2" }
        model.onEvent(AccountHubEvent.EditCompany(company))
        advanceUntilIdle()

        model.onEvent(AccountHubEvent.EditBank(null, fromCompany = true))
        advanceUntilIdle()
        val bank = model.state.value.setup.bankDraft!!
        assertEquals("co-2", bank.entityId, "the holder is the company being edited")
        model.onEvent(
            AccountHubEvent.UpdateBankDraft(
                bank.copy(
                    name = "New Bank",
                    accountNumber = "99",
                    nominalCode = "1200",
                    apClearanceNominalCode = "2100",
                    currencyCode = "GBP",
                ),
            ),
        )
        model.onEvent(AccountHubEvent.CommitBankDraft)
        settle { bankWrites.value.isNotEmpty() && model.state.value.setup.bankDraft == null }

        assertEquals("co-2", bankWrites.value.single()["entity_id"]?.jsonPrimitive?.content)
        assertTrue("b-new" in model.state.value.setup.companyDraft!!.bankIds, "created for this company, so linked")
        assertNotNull(model.state.value.setup.companyDraft, "the company editor is still open underneath")
    }

    @Test
    fun `a company added from inside the bank editor becomes that bank's holder`() = runTest(dispatcher) {
        val model = openSetup()
        model.onEvent(AccountHubEvent.EditBank(null))
        advanceUntilIdle()

        model.onEvent(AccountHubEvent.EditCompany(null, fromBank = true))
        advanceUntilIdle()
        val draft = model.state.value.setup.companyDraft!!
        model.onEvent(
            AccountHubEvent.UpdateCompanyDraft(draft.copy(name = "Third Co", country = "Ireland", countryCode = "IE")),
        )
        model.onEvent(AccountHubEvent.CommitCompanyDraft)
        settle { companyWrites.value.isNotEmpty() && model.state.value.setup.companyDraft == null }

        val bank = model.state.value.setup.bankDraft
        assertNotNull(bank, "the bank editor stays open")
        assertEquals(draft.id, bank.entityId)
        assertEquals("Third Co", bank.accountHolderName)
    }

    // -- harness --------------------------------------------------------------

    private suspend fun TestScope.openSetup(): AccountHubViewModel {
        val model = AccountHubViewModel(repository = repository(::answer), viewer = { viewer() })
        model.start()
        model.onEvent(AccountHubEvent.Open(HubArea.ProductionSetup))
        settle { model.state.value.setup.companies.saved.isNotEmpty() && model.state.value.setup.banksLoaded }
        return model
    }

    private fun answer(request: HttpRequestData): String {
        val path = request.url.encodedPath
        val method = request.method
        calls.update { it + "${method.value} $path" }
        val body = (request.body as? TextContent)?.text
        return when {
            path.endsWith("/project-settings/companies") && method == HttpMethod.Get -> COMPANIES
            path.endsWith("/project-settings/companies") && method == HttpMethod.Patch -> {
                val sent = Json.parseToJsonElement(body.orEmpty()).jsonArray
                companyWrites.update { it + listOf(sent) }
                if (refuseCompanyWrite) {
                    """{"status":0,"message":"company_in_use_by_purchase_orders"}"""
                } else {
                    """{"status":1,"data":{"value":${sent}}}"""
                }
            }
            path.endsWith("/account-hub/bank-accounts") && method == HttpMethod.Get -> BANKS
            path.endsWith("/account-hub/bank-accounts") && method == HttpMethod.Post -> {
                val sent = Json.parseToJsonElement(body.orEmpty()).jsonObject
                bankWrites.update { it + sent }
                """{"status":1,"data":{"id":"b-new","name":"New Bank","entity_id":"co-2"}}"""
            }
            else -> """{"status":1,"data":{"value":[]}}"""
        }
    }

    private fun repository(answer: (HttpRequestData) -> String): AccountHubRepositoryImpl {
        val engine = MockEngine { request ->
            respond(answer(request), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return AccountHubRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ CompanyFlowEngineFactory(engine) }),
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
        repeat(SETTLE_TRIES) {
            advanceUntilIdle()
            if (condition()) return
            withContext(Dispatchers.Default) { delay(SETTLE_STEP_MILLIS) }
        }
        advanceUntilIdle()
    }
}

private class CompanyFlowEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
