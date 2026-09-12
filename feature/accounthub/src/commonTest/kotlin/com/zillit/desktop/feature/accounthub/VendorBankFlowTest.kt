package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.feature.accounthub.data.AccountHubRepositoryImpl
import com.zillit.desktop.feature.accounthub.domain.AccountHubViewer
import com.zillit.desktop.feature.accounthub.domain.BankAccount
import com.zillit.desktop.feature.accounthub.domain.BankDetail
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.Vendor
import com.zillit.desktop.feature.accounthub.domain.VendorBank
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubViewModel
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SETTLE_TRIES = 200
private const val SETTLE_STEP_MILLIS = 5L

/** A vendor saved through the current flow: bank columns empty, details in the linked record. */
private const val VENDORS = """
{"status":1,"data":[
 {"id":"v-mine","name":"Pinewood Props","email":"hire@pinewood.co.uk","contact_person":"Ada",
  "status":"PENDING","added_by":"dept-user","bank_id":"b-1",
  "address":{"line1":"1 Studio Way","city":"Iver","postal_code":"SL0 0NH","country":"United Kingdom"},
  "bank_name":null,"account_number":null,"iban_code":null},
 {"id":"v-theirs","name":"Grip House","email":"desk@griphouse.co.uk","contact_person":"Grace",
  "status":"VERIFIED","added_by":"someone-else",
  "address":{"line1":"2 Dolly Lane","city":"Leavesden","postal_code":"WD25 7LR","country":"United Kingdom"}}
]}
"""

/** The linked bank record — note its own spellings: `name`, `iban_number`, `additional_details`. */
private const val BANK_RECORD = """
{"status":1,"data":{"id":"b-1","name":"Barclays Bank","account_holder_name":"Pinewood Props Ltd",
 "account_number":"41508833","sort_code":"204455","iban_number":"GB29NWBK60161331926819",
 "swift_code":"BARCGB22","entity_type":"vendor",
 "additional_details":[{"field":"Routing","value":"021000021","field_type":"text"}]}}
"""

/**
 * A vendor's bank details, read from where they live and deleted where they live.
 *
 * ## The bug these pin
 *
 * A vendor's bank block is written flat on the vendor row but kept in a linked
 * `/account-hub/bank-accounts` record, and the row's own bank columns are empty
 * on a vendor saved through the current flow. The desktop read only the row, so
 * the detail showed no bank details — and the edit form seeded an empty bank
 * block, so saving an unrelated change **sent every bank field back as null**.
 * The web reads the record first; so does this now.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VendorBankFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Each request as "METHOD path", and the body of each vendor write. */
    private val calls = MutableStateFlow<List<String>>(emptyList())
    private val vendorWrites = MutableStateFlow<List<JsonObject>>(emptyList())

    // -- the resolution rule -------------------------------------------------

    @Test
    fun `the linked record wins, with its own field names`() {
        val bank = VendorBank.resolve(
            vendor = Vendor(id = "v", bankId = "b-1"),
            record = BankAccount(
                id = "b-1",
                name = "Barclays Bank",
                ibanNumber = "GB29NWBK60161331926819",
                additionalDetails = listOf(BankDetail(title = "Routing", value = "021000021")),
            ),
        )

        assertEquals("Barclays Bank", bank.bankName)
        assertEquals("GB29NWBK60161331926819", bank.ibanCode, "iban_number on the record is ibanCode here")
        assertEquals("Routing", bank.additionalInfo.single().title)
    }

    @Test
    fun `with no record, the row's legacy copy is read`() {
        val bank = VendorBank.resolve(Vendor(id = "v", bankName = "Lloyds", accountNumber = "123"), record = null)

        assertEquals("Lloyds", bank.bankName)
        assertEquals("123", bank.accountNumber)
    }

    /** A record that came back empty must not blank out a populated row. */
    @Test
    fun `an empty record does not override a populated row`() {
        val bank = VendorBank.resolve(
            vendor = Vendor(id = "v", bankName = "Lloyds", bankId = "b-9"),
            record = BankAccount(id = "b-9"),
        )

        assertEquals("Lloyds", bank.bankName)
    }

    // -- who may change a vendor --------------------------------------------

    @Test
    fun `the accounts team may change any vendor`() {
        val viewer = viewer(userId = "acc", accountant = true)

        assertTrue(viewer.mayModifyVendor(Vendor(id = "v", addedBy = "someone-else")))
    }

    @Test
    fun `a department user may change only the vendors they added`() {
        val viewer = viewer(userId = "dept-user", accountant = false)

        assertTrue(viewer.mayModifyVendor(Vendor(id = "v", addedBy = "dept-user")))
        assertFalse(viewer.mayModifyVendor(Vendor(id = "v", addedBy = "someone-else")))
        assertTrue(viewer.mayAddVendor, "anyone who may post can add a vendor, as on the web")
    }

    /**
     * The blank-id guard is load-bearing.
     *
     * A vendor with no recorded adder and a viewer with no id would otherwise
     * compare equal and hand every such row to every viewer.
     */
    @Test
    fun `a blank id never matches a blank adder`() {
        val viewer = viewer(userId = "", accountant = false)

        assertFalse(viewer.mayModifyVendor(Vendor(id = "v", addedBy = "")))
        assertFalse(viewer.mayModifyVendor(Vendor(id = "v", addedBy = null)))
    }

    // -- the wire -------------------------------------------------------------

    /** A 200 carrying `status: 0` is a refusal, as it is for vendor deletes. */
    @Test
    fun `a status zero bank delete is a refusal`() = runTest(dispatcher) {
        val repository = repository { _ -> """{"status":0,"message":"bank_account_in_use"}""" }

        val result = repository.deleteBankAccount("b-1")

        val refusal = (result as? ZillitResult.Failure)?.error as? ZillitError.Http
        assertTrue(refusal != null, "a refusal must not clear the form as though it deleted")
        assertEquals("bank_account_in_use", refusal.serverMessage)
    }

    // -- the flow: the data-loss fix ------------------------------------------

    /**
     * Editing a vendor seeds its bank block from the linked record, and the save
     * carries those details back rather than nulls.
     */
    @Test
    fun `an unrelated edit keeps the vendor's bank details`() = runTest(dispatcher) {
        val model = openVendors(viewer(userId = "acc", accountant = true))
        val vendor = model.state.value.vendors.rows.first { it.id == "v-mine" }

        model.onEvent(AccountHubEvent.ComposeVendor(vendor))
        settle { model.state.value.vendors.page?.bankLoading == false }

        val draft = model.state.value.vendors.page!!.draft
        assertEquals("Barclays Bank", draft.bankName, "the form shows the bank on file, not an empty block")
        assertEquals("GB29NWBK60161331926819", draft.ibanCode)

        model.onEvent(AccountHubEvent.UpdateVendorDraft(draft.copy(contactPerson = "Ada Lovelace")))
        model.onEvent(AccountHubEvent.SaveVendor)
        settle { vendorWrites.value.isNotEmpty() }

        val sent = vendorWrites.value.single()
        assertEquals("Barclays Bank", sent["bank_name"]?.jsonPrimitive?.content)
        assertEquals("GB29NWBK60161331926819", sent["iban_code"]?.jsonPrimitive?.content)
        assertTrue(sent["account_number"] != JsonNull, "the account number must not be sent back as null")
    }

    /** The detail reads the record too, so a vendor with details on file shows them. */
    @Test
    fun `the detail shows the bank details on file`() = runTest(dispatcher) {
        val model = openVendors(viewer(userId = "acc", accountant = true))

        model.onEvent(AccountHubEvent.OpenVendorDetail("v-mine"))
        settle { model.state.value.vendors.bankRecord != null }

        val bank = model.state.value.vendors.detailBank!!
        assertEquals("Barclays Bank", bank.bankName)
        assertFalse(bank.isEmpty)
    }

    /**
     * Deleting bank details deletes the record, immediately.
     *
     * Not a vendor PATCH with blank fields deferred to save — that left the
     * record and the vendor's `bank_id` exactly where they were.
     */
    @Test
    fun `deleting bank details removes the linked record now`() = runTest(dispatcher) {
        val model = openVendors(viewer(userId = "acc", accountant = true))
        val vendor = model.state.value.vendors.rows.first { it.id == "v-mine" }
        model.onEvent(AccountHubEvent.ComposeVendor(vendor))
        settle { model.state.value.vendors.page?.bankLoading == false }

        model.onEvent(AccountHubEvent.ConfirmDeleteVendorBank)
        settle { calls.value.any { it.startsWith("DELETE") } && model.state.value.vendors.page?.bankId == null }

        assertTrue(calls.value.any { it == "DELETE /api/v2/account-hub/bank-accounts/b-1" })
        assertTrue(vendorWrites.value.isEmpty(), "no vendor write is needed to delete bank details")
        val page = model.state.value.vendors.page!!
        assertNull(page.bankId)
        assertEquals("", page.draft.bankName)
    }

    /**
     * The hub's Vendors page is the accounts team's.
     *
     * The web routes a department user who reaches it to Purchase Orders
     * (`AccountHubToolHost`: `isAccountant ? <VendorsModule/> : <Navigate …>`);
     * their own vendor list lives inside the department PO module instead. The
     * per-row adder rule above is what that embedding enforces.
     */
    @Test
    fun `a department user cannot open the hub's vendors`() = runTest(dispatcher) {
        val model = AccountHubViewModel(
            repository = repository(::answer),
            viewer = { viewer(userId = "dept-user", accountant = false) },
        )
        model.start()
        model.onEvent(AccountHubEvent.Open(HubArea.Vendors))
        advanceUntilIdle()

        assertTrue(model.state.value.area != HubArea.Vendors)
        assertTrue(calls.value.none { it.endsWith("/api/v2/vendors") }, "the register is not even fetched")
    }

    /**
     * Even an event that reaches the handler directly is refused.
     *
     * The screen hides Edit on a vendor the viewer may not change, but an event
     * can arrive without the button — so the handler holds the rule too.
     */
    @Test
    fun `the handler refuses an edit the screen would have hidden`() = runTest(dispatcher) {
        val model = openVendors(viewer(userId = "acc", accountant = true))
        val theirs = model.state.value.vendors.rows.first { it.id == "v-theirs" }
        // Downgrade to a viewer with no posting right, then send the event anyway.
        val readOnly = AccountHubViewModel(
            repository = repository(::answer),
            viewer = { viewer(userId = "acc", accountant = true, canPost = false) },
        )
        readOnly.start()
        readOnly.onEvent(AccountHubEvent.Open(HubArea.Vendors))
        settle { readOnly.state.value.vendors.rows.isNotEmpty() }

        readOnly.onEvent(AccountHubEvent.ComposeVendor(theirs))
        advanceUntilIdle()

        assertNull(readOnly.state.value.vendors.page, "no posting right, no form")
    }

    /**
     * A link to edit a vendor arrives before the register that holds it.
     *
     * The route's events fire as the console opens, so the edit has to wait for
     * the rows rather than look for a vendor that is not loaded yet and give up.
     */
    @Test
    fun `an edit link opens the form once the register has loaded`() = runTest(dispatcher) {
        val model = AccountHubViewModel(repository = repository(::answer), viewer = { viewer("acc", true) })
        model.start()
        // Both at once, as the route delivers them — no settling in between.
        model.onEvent(AccountHubEvent.Open(HubArea.Vendors))
        model.onEvent(AccountHubEvent.OpenVendorForm("v-mine"))

        settle { model.state.value.vendors.page != null }

        assertEquals("v-mine", model.state.value.vendors.page?.editingId)
        assertNull(model.state.value.vendors.pendingEditId, "a delivered edit is not kept waiting")
    }

    /** A link to a vendor the register does not hold opens nothing and waits for nothing. */
    @Test
    fun `an edit link for an unknown vendor is dropped`() = runTest(dispatcher) {
        val model = openVendors(viewer(userId = "acc", accountant = true))

        model.onEvent(AccountHubEvent.OpenVendorForm("v-gone"))
        advanceUntilIdle()

        assertNull(model.state.value.vendors.page)
        assertNull(model.state.value.vendors.pendingEditId)
    }

    // -- harness --------------------------------------------------------------

    private suspend fun TestScope.openVendors(viewer: AccountHubViewer): AccountHubViewModel {
        val model = AccountHubViewModel(repository = repository(::answer), viewer = { viewer })
        model.start()
        model.onEvent(AccountHubEvent.Open(HubArea.Vendors))
        settle { model.state.value.vendors.rows.isNotEmpty() }
        return model
    }

    private fun answer(request: HttpRequestData): String {
        val path = request.url.encodedPath
        val method = request.method
        // The engine answers on its own thread while the test reads.
        calls.update { it + "${method.value} $path" }
        val isVendorWrite = path.contains("/api/v2/vendors/") && method == HttpMethod.Patch
        if (isVendorWrite) {
            (request.body as? TextContent)?.text?.let { body ->
                vendorWrites.update { it + (Json.parseToJsonElement(body) as JsonObject) }
            }
        }
        return when {
            path.endsWith("/api/v2/vendors") && method == HttpMethod.Get -> VENDORS
            isVendorWrite -> """{"status":1,"data":{"id":"v-mine","name":"Pinewood Props"}}"""
            path.endsWith("/bank-accounts/b-1") && method == HttpMethod.Get -> BANK_RECORD
            path.endsWith("/bank-accounts/b-1") && method == HttpMethod.Delete ->
                """{"status":1,"message":"bank_account_deleted"}"""
            else -> """{"status":1,"data":[]}"""
        }
    }

    private fun repository(answer: (HttpRequestData) -> String): AccountHubRepositoryImpl {
        val engine = MockEngine { request ->
            respond(answer(request), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return AccountHubRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ BankFlowEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
        )
    }

    private fun viewer(userId: String, accountant: Boolean, canPost: Boolean = true) = AccountHubViewer.from(
        ProjectPermissions(
            listOf(
                ToolAccess(
                    identifier = AccountHubViewer.TOOL_IDENTIFIER,
                    enabled = true,
                    canView = true,
                    canPost = canPost,
                    canDownload = true,
                ),
            ),
        ),
        userId,
        isAccountant = accountant,
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

private class BankFlowEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
