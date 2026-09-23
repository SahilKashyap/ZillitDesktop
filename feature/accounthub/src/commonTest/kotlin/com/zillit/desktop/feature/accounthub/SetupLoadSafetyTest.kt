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
import com.zillit.desktop.feature.accounthub.domain.Company
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.PayRuleKind
import com.zillit.desktop.feature.accounthub.domain.PayRuleTemplate
import com.zillit.desktop.feature.accounthub.domain.PayrollAccountRow
import com.zillit.desktop.feature.accounthub.domain.PoDescriptionFormat
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubViewModel
import com.zillit.desktop.feature.accounthub.ui.SetupModal
import com.zillit.desktop.feature.accounthub.ui.SetupSection
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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

private const val COMPANIES = """
{"status":1,"data":{"value":[
 {"id":"co-1","name":"Zillit Films","legal_name":"Zillit Films","country":"United Kingdom","country_code":"GB"},
 {"id":"co-2","name":"Other Co","legal_name":"Other Co","country":"France","country_code":"FR"}
]}}
"""

/** A legacy sort code with hyphens and details stored as a JSON string, as older clients saved them. */
private const val BANKS = """
{"status":1,"data":[
 {"id":"b-1","name":"Barclays","entity_id":"co-1","account_holder_name":"Zillit Films","account_number":"41508833",
  "sort_code":"20-00-00-99","nominal_code":"1200","ap_clearance_nominal_code":"2100",
  "currency":{"code":"GBP","name":"Pound","symbol":"£"},
  "additional_details":"[{\"field\":\"Branch\",\"value\":\"Soho\",\"field_type\":\"text\"}]"}
]}
"""

/**
 * The Production Setup data-safety rules end to end — what reaches the wire
 * when a read failed, when a modal opens, and when a nested editor saves.
 *
 * ## The bugs these pin
 *
 * A section that failed to load held the empty default and saved it: the
 * companies PATCH replaces the whole list, so adding one company to a list
 * that never loaded deleted the rest. The three module modals read
 * `data.value` from services that answer the document bare, opened on the
 * defaults, and Save wrote the defaults back. A payroll-accounts save took its
 * echo as the whole settings document and lost unsaved approvers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupLoadSafetyTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val calls = MutableStateFlow<List<String>>(emptyList())
    private val writes = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    private var companiesDown = false
    private var payrollSettingsDown = false
    private var poWriteRefused = false
    private var payrollAccounts = """["6000","6010","9999"]"""

    // -- companies ------------------------------------------------------------------

    @Test
    fun `a company list that never loaded cannot be written over`() = runTest(dispatcher) {
        companiesDown = true
        val model = openSetup { it.setup.slices.failure(SetupSection.Companies) != null }
        assertTrue(model.state.value.setup.companies.saved.isEmpty(), "what failed reads as empty")

        model.onEvent(AccountHubEvent.EditCompany(null))
        advanceUntilIdle()
        val draft = model.state.value.setup.companyDraft!!
        model.onEvent(AccountHubEvent.UpdateCompanyDraft(draft.copy(name = "New Co", country = "Ireland")))
        model.onEvent(AccountHubEvent.CommitCompanyDraft)
        model.onEvent(AccountHubEvent.SaveSection(SetupSection.Companies))
        settle { false }

        assertTrue(companyWrites().isEmpty(), "a list of one would have deleted every company it never read")
        assertNotNull(model.state.value.setup.companyDraft, "the draft waits for a retry")

        companiesDown = false
        model.onEvent(AccountHubEvent.RetrySetupSection(SetupSection.Companies))
        settle { model.state.value.setup.slices.isLoaded(SetupSection.Companies) }
        model.onEvent(AccountHubEvent.CommitCompanyDraft)
        settle { companyWrites().isNotEmpty() }

        val sent = Json.parseToJsonElement(companyWrites().single()).jsonArray
        assertEquals(listOf("co-1", "co-2", draft.id), sent.map { it.jsonObject["id"]?.jsonPrimitive?.content })
    }

    @Test
    fun `a regime typed and not entered is kept on Done, and an old company gets its country code`() =
        runTest(dispatcher) {
            val model = openSetup { it.setup.slices.isLoaded(SetupSection.Companies) }

            val legacy = Company(id = "co-9", name = "Legacy", country = "United Kingdom")
            model.onEvent(AccountHubEvent.EditCompany(legacy))
            advanceUntilIdle()
            assertEquals("GB", model.state.value.setup.companyDraft?.countryCode, "so its UK fields show")
            model.onEvent(AccountHubEvent.DismissCompanyDraft)

            model.onEvent(AccountHubEvent.EditCompany(model.state.value.setup.companies.edited.first()))
            advanceUntilIdle()
            model.onEvent(AccountHubEvent.EditTaxCreditDraft("UK HETV"))
            model.onEvent(AccountHubEvent.CommitCompanyDraft)
            settle { companyWrites().isNotEmpty() }

            val first = Json.parseToJsonElement(companyWrites().single()).jsonArray.first().jsonObject
            assertEquals(listOf("UK HETV"), first["tax_credits"]!!.jsonArray.map { it.jsonPrimitive.content })
        }

    // -- the modals ---------------------------------------------------------------------

    @Test
    fun `a modal reads its document afresh, bare, and a refused save is not a save`() = runTest(dispatcher) {
        val model = openSetup { it.setup.slices.isLoaded(SetupSection.PoSetup) }
        settle { false } // every page read in, so only the modal's own is counted
        val before = calls.value.count { it == "GET /api/v2/purchase-orders/settings" }

        model.onEvent(AccountHubEvent.OpenSetupModal(SetupModal.PurchaseOrders))
        settle { model.state.value.setup.modal?.loading == false }

        assertEquals(before + 1, calls.value.count { it == "GET /api/v2/purchase-orders/settings" }, "read on open")
        val po = model.state.value.setup.poSetup.saved
        assertEquals(PoDescriptionFormat.ItemDayMonth, po.descriptionFormat, "read from the bare body")
        assertEquals("QW", po.numberPrefix)

        poWriteRefused = true
        model.onEvent(AccountHubEvent.EditPoSetup(po.copy(numberPrefix = "ZZ")))
        model.onEvent(AccountHubEvent.SaveSetupModal)
        settle { writes.value.any { it.first == "PATCH /api/v2/purchase-orders/settings" } && !saving(model) }

        assertTrue(writes.value.any { it.first == "PATCH /api/v2/purchase-orders/settings" }, "the save was sent")
        assertTrue(model.state.value.setup.poSetup.dirty, "status 0 is a refusal: the edit is still unsaved")
    }

    @Test
    fun `a modal whose read failed saves nothing`() = runTest(dispatcher) {
        payrollSettingsDown = true
        val model = openSetup { it.setup.slices.failure(SetupSection.PayrollSettings) != null }

        model.onEvent(AccountHubEvent.OpenSetupModal(SetupModal.Payroll))
        settle { model.state.value.setup.modal?.loadError != null }
        model.onEvent(AccountHubEvent.EditPayrollSettings(model.state.value.setup.payrollSettings.edited.copy(
            approverIds = listOf("u9"),
        )))
        model.onEvent(AccountHubEvent.SaveSetupModal)
        settle { false }

        assertTrue(writes.value.none { it.first.startsWith("PATCH /api/v2/account-hub/payroll-settings") })

        payrollSettingsDown = false
        model.onEvent(AccountHubEvent.RetrySetupModal)
        settle { model.state.value.setup.modal?.loadError == null && model.state.value.setup.modal?.loading == false }
        assertEquals(listOf("u1"), model.state.value.setup.payrollSettings.saved.approverIds)
    }

    @Test
    fun `saving payroll accounts keeps unsaved approvers and sends only what changed`() = runTest(dispatcher) {
        val model = openSetup {
            it.setup.slices.isLoaded(SetupSection.PayrollSettings) && it.chart.accounts.isNotEmpty()
        }
        model.onEvent(AccountHubEvent.OpenSetupModal(SetupModal.Payroll))
        settle { model.state.value.setup.modal?.loading == false }
        val edited = model.state.value.setup.payrollSettings.edited
        model.onEvent(AccountHubEvent.EditPayrollSettings(edited.copy(approverIds = listOf("u1", "u2"))))

        model.onEvent(AccountHubEvent.OpenPayrollAccounts)
        advanceUntilIdle()
        val grid = model.state.value.setup.payrollAccounts!!
        assertEquals(listOf("6000", "6010"), grid.rows.map { it.code }, "a code the chart cannot resolve is not seeded")
        assertEquals(listOf("9999"), grid.unmatched)

        val renamed = grid.rows.map { if (it.code == "6000") it.copy(name = "Gross wages") else it }
        model.onEvent(AccountHubEvent.EditPayrollAccounts(renamed + PayrollAccountRow(code = "6020", name = "Pension")))
        payrollAccounts = """["6000","6010","9999","6020"]"""
        model.onEvent(AccountHubEvent.SavePayrollAccounts)
        settle { model.state.value.setup.payrollSettings.saved.payrollAccounts.size == 4 }

        val body = Json.parseToJsonElement(
            writes.value.single { it.first == "PATCH /api/v2/account-hub/payroll-settings/custom-accounts" }.second,
        ).jsonObject
        val rows = body["rows"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("6000", "6020"), rows.map { it["code"]?.jsonPrimitive?.content }, "unchanged 6010 not sent")
        assertEquals("coa-6000", rows[0]["id"]?.jsonPrimitive?.content)
        assertNull(rows[1]["id"], "a new row is a create")
        assertEquals(listOf("u1", "u2"), model.state.value.setup.payrollSettings.edited.approverIds, "still unsaved")
        assertTrue(model.state.value.setup.payrollSettings.dirty)
    }

    @Test
    fun `a team member is added once, and a senior saves as unlimited`() = runTest(dispatcher) {
        val model = openSetup { it.setup.slices.isLoaded(SetupSection.InvoicesSetup) }
        model.onEvent(AccountHubEvent.OpenSetupModal(SetupModal.Invoices))
        settle { model.state.value.setup.modal?.loading == false }

        model.onEvent(AccountHubEvent.ComposeInvoiceMember(null))
        advanceUntilIdle()
        val fresh = model.state.value.setup.invoiceMemberDraft!!.member
        assertTrue(fresh.isSubmitOnly, "a new member starts submit-only, as on the web")
        model.onEvent(AccountHubEvent.EditInvoiceMember(fresh.copy(userId = "u1")))
        model.onEvent(AccountHubEvent.CommitInvoiceMember)
        advanceUntilIdle()
        assertEquals(1, model.state.value.setup.invoicesSetup.edited.teamMembers.size, "u1 is already on the team")

        model.onEvent(AccountHubEvent.DismissInvoiceMember)
        model.onEvent(AccountHubEvent.ComposeInvoiceMember(0))
        advanceUntilIdle()
        val existing = model.state.value.setup.invoiceMemberDraft!!.member
        model.onEvent(AccountHubEvent.EditInvoiceMember(existing.copy(isSenior = true)))
        model.onEvent(AccountHubEvent.CommitInvoiceMember)
        model.onEvent(AccountHubEvent.SaveSetupModal)
        settle { writes.value.any { it.first == "PATCH /api/v2/invoices/settings" } }

        val sent = Json.parseToJsonElement(
            writes.value.single { it.first == "PATCH /api/v2/invoices/settings" }.second,
        ).jsonObject["team_members"]!!.jsonArray.single().jsonObject
        assertIs<JsonNull>(sent["posting_limit"], "a senior is unlimited, whatever the draft held")
        assertEquals("true", sent["run_access"]?.jsonPrimitive?.content)
        assertEquals("true", sent["override_access"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a section save refused with status 0 stays unsaved`() = runTest(dispatcher) {
        val model = openSetup { it.setup.slices.isLoaded(SetupSection.AssetTags) }

        model.onEvent(AccountHubEvent.EditAssetTags(listOf("VFX")))
        model.onEvent(AccountHubEvent.SaveSection(SetupSection.AssetTags))
        settle {
            writes.value.any { it.first == "PATCH /api/v2/account-hub/project-settings/asset-tags" } &&
                !model.state.value.setup.assetTags.saving
        }

        assertTrue(model.state.value.setup.assetTags.dirty, "the web rejects anything but status 1")
        assertTrue(model.state.value.setup.assetTags.saved.isEmpty())
    }

    // -- banks ----------------------------------------------------------------------------

    @Test
    fun `banks are the production's, and a save sends details as an array and the sort code untouched`() =
        runTest(dispatcher) {
            val model = openSetup { it.setup.banksLoaded && it.setup.slices.isLoaded(SetupSection.Companies) }
            assertTrue(
                calls.value.any { it == "GET /api/v2/account-hub/bank-accounts?entity_type=production&per_page=200" },
                calls.value.filter { "bank" in it }.toString(),
            )

            val bank = model.state.value.setup.banks.single()
            model.onEvent(AccountHubEvent.EditBank(bank))
            advanceUntilIdle()
            val draft = model.state.value.setup.bankDraft!!
            model.onEvent(AccountHubEvent.UpdateBankDraft(draft.copy(name = "Barclays UK")))
            model.onEvent(AccountHubEvent.CommitBankDraft)
            settle { writes.value.any { it.first == "PATCH /api/v2/account-hub/bank-accounts/b-1" } }

            val sent = Json.parseToJsonElement(
                writes.value.single { it.first == "PATCH /api/v2/account-hub/bank-accounts/b-1" }.second,
            ).jsonObject
            assertEquals("20-00-00-99", sent["sort_code"]?.jsonPrimitive?.content, "nobody touched it")
            val details = assertIs<JsonArray>(sent["additional_details"])
            assertEquals("Branch", details.single().jsonObject["field"]?.jsonPrimitive?.content)
        }

    @Test
    fun `a bank delete confirmed twice sends one DELETE`() = runTest(dispatcher) {
        val model = openSetup { it.setup.banksLoaded }
        val bank = model.state.value.setup.banks.single()

        model.onEvent(AccountHubEvent.AskRemove(com.zillit.desktop.feature.accounthub.ui.SetupRemoval.BankRow(bank)))
        model.onEvent(AccountHubEvent.ConfirmRemove)
        model.onEvent(AccountHubEvent.ConfirmRemove)
        settle { model.state.value.setup.removal == null }

        assertEquals(1, writes.value.count { it.first == "DELETE /api/v2/account-hub/bank-accounts/b-1" })
    }

    // -- pay rules ------------------------------------------------------------------------

    @Test
    fun `a new overtime starts at eight hours, and a half-typed time is refused`() = runTest(dispatcher) {
        val model = openSetup { it.setup.slices.isLoaded(SetupSection.NonUnionPay) }

        model.onEvent(AccountHubEvent.ComposePayRule(PayRuleKind.Overtimes, null))
        advanceUntilIdle()
        val editor = model.state.value.setup.ruleEditor!!
        assertEquals(480, editor.rule.singleTrigger?.afterMinutes, "overtime after 0 hours pays every hour")
        assertEquals("8", editor.conditionText)
        model.onEvent(AccountHubEvent.DismissPayRule)

        model.onEvent(AccountHubEvent.ComposePayRule(PayRuleKind.Premiums, null))
        advanceUntilIdle()
        val premium = model.state.value.setup.ruleEditor!!.rule
        val preDawn = premium.copy(triggers = listOf(PayRuleTemplate.PreDawn.defaultTrigger()))
        model.onEvent(AccountHubEvent.EditPayRule(preDawn))
        model.onEvent(AccountHubEvent.EditPayRuleCondition("06:0"))
        model.onEvent(AccountHubEvent.CommitPayRule)
        advanceUntilIdle()

        assertEquals("06:0", model.state.value.setup.ruleEditor?.conditionText, "refused, the dialog stays open")
        assertTrue(model.state.value.setup.nonUnionPay.edited.premiums.isEmpty())
        assertFalse(model.state.value.setup.nonUnionPay.dirty)
    }

    // -- harness ------------------------------------------------------------------------

    private fun companyWrites(): List<String> =
        writes.value.filter { it.first == "PATCH /api/v2/account-hub/project-settings/companies" }.map { it.second }

    private fun saving(model: AccountHubViewModel): Boolean = model.state.value.setup.poSetup.saving

    private suspend fun TestScope.openSetup(
        ready: (com.zillit.desktop.feature.accounthub.ui.AccountHubUiState) -> Boolean,
    ): AccountHubViewModel {
        val model = AccountHubViewModel(repository = repository(), viewer = { viewer() })
        model.start()
        model.onEvent(AccountHubEvent.Open(HubArea.ProductionSetup))
        settle { ready(model.state.value) }
        return model
    }

    @Suppress("CyclomaticComplexMethod") // One branch per route the page reads.
    private fun answer(request: HttpRequestData): Pair<HttpStatusCode, String> {
        val path = request.url.encodedPath
        val query = request.url.encodedQuery.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
        val method = request.method
        calls.update { it + "${method.value} $path$query" }
        val body = (request.body as? TextContent)?.text.orEmpty()
        if (method != HttpMethod.Get) writes.update { it + ("${method.value} $path" to body) }
        val ok = HttpStatusCode.OK
        return when {
            path.endsWith("/project-settings/companies") && method == HttpMethod.Get ->
                if (companiesDown) HttpStatusCode.InternalServerError to "{}" else ok to COMPANIES
            path.endsWith("/project-settings/companies") -> ok to """{"status":1,"data":{"value":$body}}"""
            path.endsWith("/account-hub/bank-accounts") && method == HttpMethod.Get -> ok to BANKS
            path.startsWith("/api/v2/account-hub/bank-accounts/") -> ok to """{"status":1,"data":$body}"""
            path.endsWith("/purchase-orders/settings") && method == HttpMethod.Get ->
                ok to """{"status":1,"data":{"description_format":"ITEM_DDMON","po_number_prefix":"QW"}}"""
            path.endsWith("/purchase-orders/settings") ->
                ok to if (poWriteRefused) """{"status":0,"message":"refused"}""" else """{"status":1,"data":$body}"""
            path.endsWith("/invoices/settings") && method == HttpMethod.Get ->
                ok to """{"status":1,"data":{"team_members":[{"user_id":"u1","posting_limit":100}]}}"""
            path.endsWith("/invoices/settings") -> ok to """{"status":1,"data":$body}"""
            path.endsWith("/account-hub/payroll-settings") && method == HttpMethod.Get ->
                if (payrollSettingsDown) {
                    HttpStatusCode.InternalServerError to "{}"
                } else {
                    ok to """{"status":1,"data":{"payroll_approvers":["u1"],"payroll_accounts":$payrollAccounts}}"""
                }
            path.endsWith("/payroll-settings/custom-accounts") -> ok to """{"status":1,"data":{"coa_rows":[]}}"""
            path.endsWith("/project-settings/asset-tags") && method == HttpMethod.Patch ->
                ok to """{"status":0,"message":"asset_tags_rejected","data":{"value":["VFX"]}}"""
            path.endsWith("/assignment-rules") && method == HttpMethod.Get -> ok to """{"status":1,"data":[]}"""
            path.endsWith("/vendors") && method == HttpMethod.Get -> ok to """{"status":1,"data":[]}"""
            path.endsWith("/chart-of-accounts") && method == HttpMethod.Get -> ok to """{"status":1,"data":[
                {"id":"coa-6000","code":"6000","name":"Wages","line_type":"category","is_active":true},
                {"id":"coa-6010","code":"6010","name":"NI","line_type":"category","is_active":true}]}"""
            else -> ok to """{"status":1,"data":{"value":[]}}"""
        }
    }

    private fun repository(): AccountHubRepositoryImpl {
        val engine = MockEngine { request ->
            val (status, body) = answer(request)
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return AccountHubRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ SetupSafetyEngineFactory(engine) }),
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

private class SetupSafetyEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
