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
import com.zillit.desktop.feature.accounthub.domain.HubDepartment
import com.zillit.desktop.feature.accounthub.domain.HubUser
import com.zillit.desktop.feature.accounthub.domain.IsdCountries
import com.zillit.desktop.feature.accounthub.domain.IsdCountry
import com.zillit.desktop.feature.accounthub.domain.NewVendor
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubViewModel
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SETTLE_TRIES = 200
private const val SETTLE_STEP_MILLIS = 5L

private const val REGISTER = """
{"status":1,"data":[
 {"id":"v-1","name":"Pinewood Props","email":"hire@pinewood.co.uk","contact_person":"Ada",
  "status":"PENDING","added_by":"acc",
  "phone":{"country_code":"+44","number":"1753785000"},
  "address":{"line1":"Pinewood Road","city":"Iver","state":"Buckinghamshire","postal_code":"SL0 0NH",
   "country":"United Kingdom"}}
]}
"""

private const val LIVE_COUNTRIES = """
{"status":1,"data":[
 {"name":"India","dial_code":"+91","code":"IN"},
 {"name":"United Kingdom","dial_code":"+44","code":"GB"},
 {"name":"","dial_code":"+0","code":"XX"}
]}
"""

/**
 * The vendor form's two preset helpers: the country catalogue its pickers
 * read, and the postcode lookup that fills in a city and a county.
 *
 * ## Why these exist
 *
 * The form's country picker used to read the Production Setup tax catalogue,
 * which only loads on that page — so a Vendors page opened first offered no
 * countries at all. A pre-filled "United Kingdom" hid it; ZL-20520 removed the
 * pre-fill, which made the empty picker a form nobody could save.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VendorFormPresetsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val calls = MutableStateFlow<List<String>>(emptyList())

    /** What the postcode lookup answers; `null` sends a refusal. */
    private var placeAnswer: String? = """{"status":1,"data":[{"city":"Leavesden","state":"Hertfordshire"}]}"""

    private var countriesAnswer = LIVE_COUNTRIES

    // -- the catalogue ----------------------------------------------------------

    @Test
    fun `the pickers read the live country catalogue`() = runTest(dispatcher) {
        val model = openVendors()
        settle { model.state.value.vendors.countriesLoaded }

        assertTrue("GET /api/v2/preset/isd-codes" in calls.value)
        assertEquals(
            listOf(IsdCountry("India", "+91", "IN"), IsdCountry("United Kingdom", "+44", "GB")),
            model.state.value.vendors.countries,
            "a row with no name cannot be picked, so it is not offered",
        )
    }

    @Test
    fun `a failed catalogue leaves the bundled list in place`() = runTest(dispatcher) {
        countriesAnswer = """{"status":0,"message":"something_went_wrong"}"""
        val model = openVendors()
        settle { calls.value.any { it.endsWith("/preset/isd-codes") } }

        assertEquals(IsdCountries.bundled, model.state.value.vendors.countries)
        assertFalse(model.state.value.vendors.countriesLoaded, "tried again on the next visit")
        assertTrue(IsdCountries.bundled.size > 200, "the fallback is the whole list, not a handful")
    }

    // -- the postcode lookup ------------------------------------------------------

    @Test
    fun `a postcode fills the city and county after a pause`() = runTest(dispatcher) {
        val model = openVendors()
        model.onEvent(AccountHubEvent.ComposeVendor(null))
        runCurrent()

        model.type { copy(address = address.copy(country = "United Kingdom", postalCode = "WD25 7LR")) }
        runCurrent()
        assertTrue(calls.value.none { "geonames" in it }, "nothing is asked until the typing pauses")

        settle { model.state.value.vendors.page?.draft?.address?.city == "Leavesden" }

        assertTrue("GET /api/v2/preset/geonames/postalcode/GB/WD25%207LR" in calls.value, calls.value.toString())
        val address = model.state.value.vendors.page!!.draft.address
        assertEquals("Leavesden", address.city)
        assertEquals("Hertfordshire", address.state)
        assertFalse(model.state.value.vendors.page!!.postcodeLooking)
    }

    /** ZL-20356: a confirmed answer is written over the old place, blanks included. */
    @Test
    fun `a new postcode replaces the old city, and one with no place clears it`() = runTest(dispatcher) {
        val model = openVendors()
        model.onEvent(AccountHubEvent.ComposeVendor(model.state.value.vendors.rows.single()))
        settle { model.state.value.vendors.page != null }

        placeAnswer = """{"status":1,"data":[]}"""
        model.type { copy(address = address.copy(postalCode = "ZZ9 9ZZ")) }
        settle { calls.value.any { "geonames" in it } && model.state.value.vendors.page?.postcodeLooking == false }

        val address = model.state.value.vendors.page!!.draft.address
        assertEquals("", address.city, "the previous postcode's city must not stay under the new one")
        assertEquals("", address.state)
    }

    @Test
    fun `a failed lookup changes nothing`() = runTest(dispatcher) {
        val model = openVendors()
        model.onEvent(AccountHubEvent.ComposeVendor(model.state.value.vendors.rows.single()))
        settle { model.state.value.vendors.page != null }

        placeAnswer = null
        model.type { copy(address = address.copy(postalCode = "SL0 0NJ")) }
        settle { calls.value.any { "geonames" in it } && model.state.value.vendors.page?.postcodeLooking == false }

        assertEquals("Iver", model.state.value.vendors.page!!.draft.address.city)
    }

    /** Opening a saved vendor must not rewrite its address by looking it up. */
    @Test
    fun `opening a vendor does not look its postcode up`() = runTest(dispatcher) {
        val model = openVendors()

        model.onEvent(AccountHubEvent.ComposeVendor(model.state.value.vendors.rows.single()))
        settle { model.state.value.vendors.page != null }
        advanceUntilIdle()

        assertTrue(calls.value.none { "geonames" in it })
        assertEquals("Iver", model.state.value.vendors.page!!.draft.address.city)
    }

    @Test
    fun `typing on cancels the earlier lookup`() = runTest(dispatcher) {
        val model = openVendors()
        model.onEvent(AccountHubEvent.ComposeVendor(null))
        model.type { copy(address = address.copy(country = "United Kingdom", postalCode = "WD2")) }
        runCurrent()

        model.type { copy(address = address.copy(postalCode = "WD25 7LR")) }
        settle { model.state.value.vendors.page?.draft?.address?.city == "Leavesden" }

        assertEquals(
            listOf("GET /api/v2/preset/geonames/postalcode/GB/WD25%207LR"),
            calls.value.filter { "geonames" in it },
        )
    }

    @Test
    fun `a short postcode or an unknown country is not looked up`() = runTest(dispatcher) {
        val model = openVendors()
        model.onEvent(AccountHubEvent.ComposeVendor(null))

        model.type { copy(address = address.copy(country = "United Kingdom", postalCode = "WD")) }
        advanceUntilIdle()
        model.type { copy(address = address.copy(country = "Narnia", postalCode = "WD25 7LR")) }
        advanceUntilIdle()

        assertTrue(calls.value.none { "geonames" in it }, calls.value.toString())
    }

    /** A form closed before its answer arrives stays closed. */
    @Test
    fun `closing the form drops its lookup`() = runTest(dispatcher) {
        val model = openVendors()
        model.onEvent(AccountHubEvent.ComposeVendor(null))
        model.type { copy(address = address.copy(country = "United Kingdom", postalCode = "WD25 7LR")) }
        runCurrent()

        model.onEvent(AccountHubEvent.DismissVendorForm)
        advanceUntilIdle()

        assertEquals(null, model.state.value.vendors.page)
        assertTrue(calls.value.none { "geonames" in it })
    }

    // -- the department -----------------------------------------------------------

    /** The roster names a department; the list numbers it. The new form starts in the viewer's. */
    @Test
    fun `a new vendor starts in the viewer's own department, an edit keeps its own`() = runTest(dispatcher) {
        val model = openVendors()

        model.onEvent(AccountHubEvent.ComposeVendor(null))
        runCurrent()
        assertEquals("d-acc", model.state.value.vendors.page?.draft?.departmentId)

        model.onEvent(AccountHubEvent.ComposeVendor(model.state.value.vendors.rows.single()))
        runCurrent()
        assertEquals(null, model.state.value.vendors.page?.draft?.departmentId, "a saved vendor is not re-filed")
    }

    // -- the dial code ------------------------------------------------------------

    /** +44 is four countries; the vendor's own country says which one is meant. */
    @Test
    fun `a shared dial code reads as the vendor's own country`() {
        val countries = IsdCountries.bundled

        assertEquals("GB", IsdCountries.forDial(countries, "+44", "United Kingdom")?.code)
        assertEquals("GG", IsdCountries.forDial(countries, "+44", "")?.code, "the list's first match otherwise")
        assertEquals(null, IsdCountries.forDial(countries, "", "United Kingdom"), "nothing stored, nothing shown")
        assertEquals("+999", IsdCountries.forDial(countries, "+999")?.dialCode, "an unknown code is kept as stored")
    }

    @Test
    fun `a stored country the list does not hold is kept as it was`() {
        assertEquals("Middle-earth", IsdCountries.forName(IsdCountries.bundled, "Middle-earth")?.name)
        assertEquals("GB", IsdCountries.isoFor(IsdCountries.bundled, "united kingdom"))
        assertEquals("", IsdCountries.isoFor(IsdCountries.bundled, "Middle-earth"))
    }

    // -- harness ------------------------------------------------------------------

    private fun AccountHubViewModel.type(edit: NewVendor.() -> NewVendor) {
        val draft = state.value.vendors.page?.draft ?: error("no form open")
        onEvent(AccountHubEvent.UpdateVendorDraft(draft.edit()))
    }

    private suspend fun TestScope.openVendors(): AccountHubViewModel {
        val model = AccountHubViewModel(
            repository = repository(),
            viewer = { accountant() },
            users = { listOf(HubUser(id = "acc", name = "Sahil Kashyap", department = "Accounts")) },
            departmentList = {
                listOf(
                    HubDepartment(id = "d-art", name = "Art"),
                    HubDepartment(id = "d-acc", name = "Accounts", identifier = "department_accounts"),
                )
            },
        )
        model.start()
        model.onEvent(AccountHubEvent.Open(HubArea.Vendors))
        settle {
            model.state.value.vendors.rows.isNotEmpty() &&
                model.state.value.vendors.countriesLoaded &&
                model.state.value.departmentList.isNotEmpty()
        }
        return model
    }

    private fun answer(request: HttpRequestData): String {
        val path = request.url.encodedPath
        // The engine answers on its own thread while the test reads.
        calls.update { it + "${request.method.value} $path" }
        return when {
            path.endsWith("/api/v2/vendors") -> REGISTER
            path.endsWith("/preset/isd-codes") -> countriesAnswer
            "/preset/geonames/postalcode/" in path -> placeAnswer ?: """{"status":0,"message":"lookup_failed"}"""
            else -> """{"status":1,"data":[]}"""
        }
    }

    private fun repository(): AccountHubRepositoryImpl {
        val engine = MockEngine { request ->
            respond(answer(request), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return AccountHubRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ PresetsEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
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

private class PresetsEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
