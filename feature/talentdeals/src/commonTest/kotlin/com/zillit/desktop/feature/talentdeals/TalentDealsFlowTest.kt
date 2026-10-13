package com.zillit.desktop.feature.talentdeals

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.feature.talentdeals.domain.FeeBasis
import com.zillit.desktop.feature.talentdeals.domain.PaymentMode
import com.zillit.desktop.feature.talentdeals.domain.PickedTalentFile
import com.zillit.desktop.feature.talentdeals.domain.SigningDay
import com.zillit.desktop.feature.talentdeals.domain.SigningRow
import com.zillit.desktop.feature.talentdeals.domain.TalentCountry
import com.zillit.desktop.feature.talentdeals.domain.TalentCurrencies
import com.zillit.desktop.feature.talentdeals.domain.TalentCurrency
import com.zillit.desktop.feature.talentdeals.domain.TalentDealsViewer
import com.zillit.desktop.feature.talentdeals.domain.TalentField
import com.zillit.desktop.feature.talentdeals.domain.TalentFileSlot
import com.zillit.desktop.feature.talentdeals.domain.TalentForm
import com.zillit.desktop.feature.talentdeals.domain.TalentRefresh
import com.zillit.desktop.feature.talentdeals.domain.TalentUnion
import com.zillit.desktop.feature.talentdeals.ui.TalentDealsEffect
import com.zillit.desktop.feature.talentdeals.ui.TalentDealsEvent
import com.zillit.desktop.feature.talentdeals.ui.TalentDealsViewModel
import com.zillit.desktop.feature.talentdeals.ui.TalentReference
import com.zillit.desktop.feature.talentdeals.ui.TalentRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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

/**
 * The tool end to end over an in-memory service: the register, the role
 * picker, the form's save (uploads first, then the envelope), Edit, remove, the
 * rights each handler re-checks, the socket, Exhibit G's lazy lookup and the
 * file viewer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TalentDealsFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val repo = FakeTalentRepository()
    private val files = FakeTalentFiles()
    private var viewer: TalentDealsViewer = Talent.poster

    private val currencies = TalentCurrencies(
        selected = listOf("GBP", "USD"),
        defaultCode = "GBP",
        catalogue = listOf(
            TalentCurrency("GBP", "Pound Sterling", "£", "United Kingdom"),
            TalentCurrency("USD", "US Dollar", "$", "United States"),
        ),
    )

    private fun vm() = TalentDealsViewModel(
        repository = repo,
        resolveViewer = { viewer },
        files = files,
        reference = object : TalentReference {
            override suspend fun countries() = emptyList<TalentCountry>()
            override suspend fun currencies() = this@TalentDealsFlowTest.currencies
        },
        randomDigits = { 123456 },
    )

    private class Harness(val vm: TalentDealsViewModel, val effects: MutableList<TalentDealsEffect>,
        val collector: Job) {
        val state get() = vm.currentState
        fun notices() = effects.filterIsInstance<TalentDealsEffect.Notice>()
    }

    private fun TestScope.open(): Harness {
        val vm = vm()
        val effects = mutableListOf<TalentDealsEffect>()
        val collector = CoroutineScope(dispatcher).launch { vm.effects.collect { effects += it } }
        vm.start()
        advanceUntilIdle()
        return Harness(vm, effects, collector)
    }

    private fun TestScope.send(h: Harness, vararg events: TalentDealsEvent) {
        events.forEach { h.vm.onEvent(it) }
        advanceUntilIdle()
    }

    private fun Harness.edit(change: TalentForm.() -> TalentForm) =
        TalentDealsEvent.FormEdit(change)

    private fun pdf(name: String = "Deal.pdf") = PickedTalentFile(name, byteArrayOf(1, 2, 3), "application/pdf")

    // -- the register ------------------------------------------------------------------

    @Test
    fun `the register loads the list on open and searches it client-side`() = runTest(dispatcher) {
        repo.rows = mutableListOf(Talent.contract("c1", "Jane Doe"), Talent.contract("c2", "Sam Poe",
            role = "Director"))
        val h = open()

        assertEquals(TalentRoute.Register, h.state.route)
        assertEquals(2, h.state.register.contracts.size)
        assertFalse(h.state.register.loading)

        send(h, TalentDealsEvent.Search("sam"))
        assertEquals(listOf("c2"), h.state.register.visible.map { it.id })
        send(h, TalentDealsEvent.Search("director"))
        assertEquals(listOf("c2"), h.state.register.visible.map { it.id })
        send(h, TalentDealsEvent.Search("zzz"))
        assertEquals(emptyList(), h.state.register.visible)
        assertEquals(2, h.state.register.contracts.size)
        h.collector.cancel()
    }

    @Test
    fun `a failed first load says so and a retry recovers`() = runTest(dispatcher) {
        repo.listError = ZillitError.NoConnection()
        val h = open()
        assertEquals("No internet connection.", h.state.register.error)
        assertEquals(emptyList(), h.state.register.contracts)

        repo.listError = null
        repo.rows = mutableListOf(Talent.contract())
        send(h, TalentDealsEvent.Retry)
        assertNull(h.state.register.error)
        assertEquals(1, h.state.register.contracts.size)
        h.collector.cancel()
    }

    @Test
    fun `a socket frame refreshes the list quietly and a failed re-read keeps the rows`() = runTest(dispatcher) {
        repo.rows = mutableListOf(Talent.contract("c1"))
        val h = open()
        repo.rows.add(Talent.contract("c2"))
        repo.socket.tryEmit(TalentRefresh("c2"))
        advanceUntilIdle()
        assertEquals(2, h.state.register.contracts.size)

        repo.listError = ZillitError.Timeout()
        repo.socket.tryEmit(TalentRefresh(null))
        advanceUntilIdle()
        assertEquals(2, h.state.register.contracts.size)
        assertNull(h.state.register.error)
        h.collector.cancel()
    }

    @Test
    fun `nobody without view_access is turned away and nothing is fetched`() = runTest(dispatcher) {
        viewer = Talent.nobody
        val h = open()
        assertTrue(h.state.noAccess)
        assertEquals(0, repo.listCalls)
        h.collector.cancel()
    }

    @Test
    fun `before the grid answers nothing is refused`() = runTest(dispatcher) {
        viewer = TalentDealsViewer(rightsLoaded = false)
        val h = open()
        assertFalse(h.state.noAccess)
        assertEquals(1, repo.listCalls)
        h.collector.cancel()
    }

    // -- Add Contract: the picker and the role -----------------------------------------

    @Test
    fun `Add Contract opens the role picker for a poster, and refuses a reader`() = runTest(dispatcher) {
        val h = open()
        send(h, TalentDealsEvent.AddContract)
        assertTrue(h.state.register.rolePickerOpen)

        viewer = Talent.reader
        h.vm.onRightsChanged()
        send(h, TalentDealsEvent.CancelRolePicker, TalentDealsEvent.AddContract)
        assertFalse(h.state.register.rolePickerOpen)
        assertEquals(1, h.notices().size)
        assertTrue(h.notices().single().isError)
        h.collector.cancel()
    }

    @Test
    fun `a picked role opens the form with a fresh reference, the role riding the route`() = runTest(dispatcher) {
        val h = open()
        send(h, TalentDealsEvent.AddContract, TalentDealsEvent.PickRole("Cast"))

        assertEquals(TalentRoute.New("Cast"), h.state.route)
        assertEquals("Cast", h.state.form.role)
        assertEquals("TC-123456", h.state.form.reference)
        assertFalse(h.state.register.rolePickerOpen)
        assertEquals("issued", h.state.form.status)
        h.collector.cancel()
    }

    @Test
    fun `a typed role is trimmed and a blank one goes nowhere`() = runTest(dispatcher) {
        val h = open()
        send(h, TalentDealsEvent.PickRole("   "))
        assertEquals(TalentRoute.Register, h.state.route)
        send(h, TalentDealsEvent.PickRole("  Dialect coach "))
        assertEquals(TalentRoute.New("Dialect coach"), h.state.route)
        h.collector.cancel()
    }

    @Test
    fun `a reader dispatching a role directly still cannot open the form`() = runTest(dispatcher) {
        viewer = Talent.reader
        val h = open()
        send(h, TalentDealsEvent.PickRole("Cast"))
        assertEquals(TalentRoute.Register, h.state.route)
        h.collector.cancel()
    }

    // -- the form ----------------------------------------------------------------------

    private suspend fun TestScope.fillCast(h: Harness) {
        send(h, TalentDealsEvent.AddContract, TalentDealsEvent.PickRole("Cast"))
        files.picks += pdf()
        repo.unionRows = listOf(TalentUnion("1", "sag-aftra", "Screen Actors Guild - AFTRA"))
        send(
            h,
            h.edit { copy(person = "  Jane Doe ") },
            TalentDealsEvent.PickContractFile,
            TalentDealsEvent.SelectTerritory("us"),
            h.edit { copy(union = "sag-aftra", fee = "100000") },
            h.edit { pickMode(PaymentMode.Escrow).pickBasis(FeeBasis.Salary) },
        )
    }

    @Test
    fun `saving an empty form only counts what is outstanding`() = runTest(dispatcher) {
        val h = open()
        send(h, TalentDealsEvent.AddContract, TalentDealsEvent.PickRole("Cast"), TalentDealsEvent.Save)

        assertTrue(h.state.form.attempted)
        assertTrue(TalentField.Person in h.state.form.shownErrors)
        assertTrue(TalentField.Contract in h.state.form.shownErrors)
        assertEquals(0, repo.creates.size)
        assertEquals(0, files.uploads.size)
        h.collector.cancel()
    }

    @Test
    fun `errors stay hidden until someone tries to save`() = runTest(dispatcher) {
        val h = open()
        send(h, TalentDealsEvent.AddContract, TalentDealsEvent.PickRole("Cast"))
        assertTrue(h.state.form.errors.isNotEmpty())
        assertEquals(emptySet(), h.state.form.shownErrors)
        h.collector.cancel()
    }

    @Test
    fun `a territory sets its currency when the picker can show it, and asks for unions`() = runTest(dispatcher) {
        val h = open()
        send(h, TalentDealsEvent.AddContract, TalentDealsEvent.PickRole("Cast"), TalentDealsEvent.SelectTerritory("us"))

        assertEquals("USD", h.state.form.form.currency)
        assertEquals("$", h.state.symbol)
        assertEquals(listOf("cast" to "us"), repo.unionAsks)

        // Germany's default is EUR, which this production's picker does not hold: the currency stays.
        send(h, TalentDealsEvent.SelectTerritory("de"))
        assertEquals("USD", h.state.form.form.currency)
        assertEquals("cast" to "de", repo.unionAsks.last())
        h.collector.cancel()
    }

    @Test
    fun `with no currency chosen the project default stands in`() = runTest(dispatcher) {
        val h = open()
        send(h, TalentDealsEvent.AddContract, TalentDealsEvent.PickRole("Cast"))
        assertEquals("GBP", h.state.activeCurrency)
        assertEquals("£", h.state.symbol)
        h.collector.cancel()
    }

    @Test
    fun `a failed union lookup never strands the form`() = runTest(dispatcher) {
        repo.unionsError = ZillitError.Timeout()
        val h = open()
        send(h, TalentDealsEvent.AddContract, TalentDealsEvent.PickRole("Cast"), TalentDealsEvent.SelectTerritory("us"))
        assertEquals(emptyList(), h.state.form.unions)
        assertFalse(h.state.form.unionsLoading)
        // The role's own guild is still there to pick.
        assertEquals(listOf("sag-aftra"), h.state.form.unionOptions.map { it.identifier })
        h.collector.cancel()
    }

    @Test
    fun `a typed role asks for all unions`() = runTest(dispatcher) {
        val h = open()
        send(h, TalentDealsEvent.AddContract, TalentDealsEvent.PickRole("Dialect coach"),
            TalentDealsEvent.SelectTerritory("uk"))
        assertEquals(listOf("all" to "uk"), repo.unionAsks)
        assertFalse(h.state.form.unionRequired)
        h.collector.cancel()
    }

    @Test
    fun `a complete form uploads first, then posts the envelope and returns to the register`() = runTest(dispatcher) {
        val h = open()
        fillCast(h)
        assertEquals(listOf("pdf", "doc", "docx"), files.pickedExtensions)
        assertIs<TalentFileSlot.Picked>(h.state.form.form.contractFile)

        send(h, TalentDealsEvent.Save)

        assertEquals(listOf("Deal.pdf"), files.uploads.map { it.name })
        val body = repo.creates.single()
        assertEquals("Jane Doe", body["person"]!!.jsonPrimitive.content)
        assertEquals("Cast", body["role"]!!.jsonPrimitive.content)
        assertEquals("TC-123456", body["talent_reference"]!!.jsonPrimitive.content)
        assertEquals("issued", body["status"]!!.jsonPrimitive.content)
        assertEquals("store/1/Deal.pdf", body["long_form_contract"]!!.jsonObject["media"]!!.jsonPrimitive.content)
        // The currency the form showed — the project's default was never chosen, the territory set USD.
        assertEquals("USD", body["contract_currency"]!!.jsonPrimitive.content)
        assertEquals("100000", body["fee"]!!.jsonPrimitive.content)
        assertEquals(TalentRoute.Register, h.state.route)
        assertTrue(h.notices().any { !it.isError })
        assertFalse(h.state.form.saving)
        h.collector.cancel()
    }

    @Test
    fun `an upload that fails stops the save before anything is posted`() = runTest(dispatcher) {
        val h = open()
        fillCast(h)
        files.uploadError = ZillitError.NoConnection()
        send(h, TalentDealsEvent.Save)

        assertEquals(0, repo.creates.size)
        assertIs<TalentRoute.New>(h.state.route)
        assertFalse(h.state.form.saving)
        assertTrue(h.notices().last().isError)
        h.collector.cancel()
    }

    @Test
    fun `a refused save keeps the form and says why`() = runTest(dispatcher) {
        val h = open()
        fillCast(h)
        repo.saveError = ZillitError.Http(400, "talent_contract_validation")
        send(h, TalentDealsEvent.Save)

        assertIs<TalentRoute.New>(h.state.route)
        assertEquals("Jane Doe", h.state.form.form.person.trim())
        assertTrue(h.notices().last().isError)
        assertFalse(h.state.form.saving)
        h.collector.cancel()
    }

    @Test
    fun `the certificate is only uploaded while a pre-authorised rate stands`() = runTest(dispatcher) {
        val h = open()
        fillCast(h)
        files.picks += pdf("HMRC.pdf")
        send(
            h,
            h.edit { copy(withholding = true, withholdingValue = "20") },
            h.edit { setAuthorised(true).copy(authorisedRate = "8") },
            TalentDealsEvent.PickCertificateFile,
        )
        assertEquals(listOf("pdf", "jpg", "jpeg", "png"), files.pickedExtensions)
        send(h, h.edit { setAuthorised(false) }, TalentDealsEvent.Save)

        assertEquals(listOf("Deal.pdf"), files.uploads.map { it.name })
        assertEquals(1, repo.creates.size)
        h.collector.cancel()
    }

    @Test
    fun `a handler re-checks the right - a reader dispatching Save changes nothing`() = runTest(dispatcher) {
        val h = open()
        fillCast(h)
        viewer = Talent.reader
        h.vm.onRightsChanged()
        advanceUntilIdle()
        // The change of rights took the form away; a direct dispatch is refused too.
        assertEquals(TalentRoute.Register, h.state.route)
        send(h, TalentDealsEvent.Save)
        assertEquals(0, repo.creates.size)
        assertEquals(0, files.uploads.size)
        h.collector.cancel()
    }

    @Test
    fun `weekly and expenses seed one row and unticking clears them`() = runTest(dispatcher) {
        val h = open()
        send(h, TalentDealsEvent.AddContract, TalentDealsEvent.PickRole("Cast"), TalentDealsEvent.ToggleWeekly(true),
            TalentDealsEvent.ToggleExpenses(true))
        assertEquals(listOf("Prep"), h.state.form.form.tranches.map { it.label })
        assertEquals(1, h.state.form.form.expenses.size)

        send(h, TalentDealsEvent.AddTranche, TalentDealsEvent.AddExpense)
        assertEquals(2, h.state.form.form.tranches.size)
        assertEquals(2, h.state.form.form.expenses.size)
        assertEquals(2, h.state.form.form.tranches.map { it.id }.toSet().size)

        send(h, TalentDealsEvent.ToggleWeekly(false), TalentDealsEvent.ToggleExpenses(false))
        assertEquals(emptyList(), h.state.form.form.tranches)
        assertEquals(emptyList(), h.state.form.form.expenses)
        h.collector.cancel()
    }

    // -- Edit --------------------------------------------------------------------------

    @Test
    fun `Edit loads the stored row into the same form and patches it back`() = runTest(dispatcher) {
        repo.rows = mutableListOf(Talent.contract("c1", weekly = true))
        repo.unionRows = listOf(TalentUnion("1", "sag-aftra", "Screen Actors Guild - AFTRA"))
        val h = open()
        send(h, TalentDealsEvent.OpenContract("c1"), TalentDealsEvent.OpenEdit("c1"))

        assertEquals(TalentRoute.Edit("c1"), h.state.route)
        assertFalse(h.state.form.loadingRow)
        assertEquals("Jane Doe", h.state.form.form.person)
        assertEquals("TC-C1", h.state.form.reference)
        assertEquals("Cast", h.state.form.role)
        assertEquals("100000", h.state.form.form.fee)
        assertEquals(listOf("cast" to "us"), repo.unionAsks)
        assertIs<TalentFileSlot.Stored>(h.state.form.form.contractFile)

        send(h, h.edit { copy(person = "Jane Q. Doe", tranches = tranches.map { it.copy(value = "100") }) },
            TalentDealsEvent.Save)

        // The stored agreement is sent back as it is — nothing is uploaded.
        assertEquals(0, files.uploads.size)
        val (id, body) = repo.updates.single()
        assertEquals("c1", id)
        assertEquals("Jane Q. Doe", body["person"]!!.jsonPrimitive.content)
        assertEquals("TC-C1", body["talent_reference"]!!.jsonPrimitive.content)
        assertEquals("store/c1.pdf", body["long_form_contract"]!!.jsonObject["media"]!!.jsonPrimitive.content)
        assertEquals(1, body["payment_schedule"]!!.jsonObject["tranches"]!!.jsonArray.size)
        assertEquals(TalentRoute.View("c1"), h.state.route)
        h.collector.cancel()
    }

    @Test
    fun `an Edit whose row cannot be read goes back to the contract with the server's words`() = runTest(dispatcher) {
        repo.rows = mutableListOf(Talent.contract("c1"))
        val h = open()
        send(h, TalentDealsEvent.OpenContract("c1"))
        repo.detailError = ZillitError.Http(500, "something_went_wrong")
        send(h, TalentDealsEvent.OpenEdit("c1"))

        assertEquals(TalentRoute.View("c1"), h.state.route)
        assertTrue(h.notices().last().isError)
        h.collector.cancel()
    }

    @Test
    fun `a reader cannot open Edit`() = runTest(dispatcher) {
        viewer = Talent.reader
        repo.rows = mutableListOf(Talent.contract("c1"))
        val h = open()
        send(h, TalentDealsEvent.OpenEdit("c1"))
        assertEquals(TalentRoute.Register, h.state.route)
        assertEquals(0, repo.detailCalls.size)
        h.collector.cancel()
    }

    // -- one contract, and back --------------------------------------------------------

    @Test
    fun `a contract opens read-only and Back steps out one face at a time`() = runTest(dispatcher) {
        repo.rows = mutableListOf(Talent.contract("c1"))
        val h = open()
        send(h, TalentDealsEvent.OpenContract("c1"))
        assertEquals(TalentRoute.View("c1"), h.state.route)
        assertEquals("Jane Doe", h.state.detail.contract!!.person)

        send(h, TalentDealsEvent.OpenEdit("c1"))
        send(h, TalentDealsEvent.Back)
        assertEquals(TalentRoute.View("c1"), h.state.route)
        send(h, TalentDealsEvent.Back)
        assertEquals(TalentRoute.Register, h.state.route)
        send(h, TalentDealsEvent.Back)
        assertTrue(h.effects.any { it == TalentDealsEffect.CloseTool })
        h.collector.cancel()
    }

    @Test
    fun `a socket frame re-reads the open contract quietly, and a failed read shows why`() = runTest(dispatcher) {
        repo.rows = mutableListOf(Talent.contract("c1"))
        val h = open()
        send(h, TalentDealsEvent.OpenContract("c1"))
        val reads = repo.detailCalls.size

        repo.socket.tryEmit(TalentRefresh("other"))
        advanceUntilIdle()
        assertEquals(reads, repo.detailCalls.size)

        repo.socket.tryEmit(TalentRefresh("c1"))
        advanceUntilIdle()
        assertEquals(reads + 1, repo.detailCalls.size)

        repo.detailError = ZillitError.Timeout()
        repo.socket.tryEmit(TalentRefresh("c1"))
        advanceUntilIdle()
        // A failed re-read keeps the contract on screen rather than blanking it.
        assertNotNull(h.state.detail.contract)
        assertNull(h.state.detail.error)

        send(h, TalentDealsEvent.Back, TalentDealsEvent.OpenContract("c1"))
        assertEquals("The server took too long to respond.", h.state.detail.error)
        h.collector.cancel()
    }

    // -- remove ------------------------------------------------------------------------

    @Test
    fun `remove asks first, then deletes and reloads the list`() = runTest(dispatcher) {
        repo.rows = mutableListOf(Talent.contract("c1"), Talent.contract("c2", "Sam"))
        val h = open()
        val first = h.state.register.contracts.first()
        send(h, TalentDealsEvent.RequestRemove(first))

        assertEquals(first, h.state.register.deleteTarget)
        assertEquals(0, repo.removes.size)

        send(h, TalentDealsEvent.ConfirmRemove)
        assertEquals(listOf("c1"), repo.removes)
        assertNull(h.state.register.deleteTarget)
        assertEquals(listOf("c2"), h.state.register.contracts.map { it.id })
        assertTrue(h.notices().last().text.isNotBlank())
        h.collector.cancel()
    }

    @Test
    fun `cancelling a remove leaves the contract alone`() = runTest(dispatcher) {
        repo.rows = mutableListOf(Talent.contract("c1"))
        val h = open()
        send(h, TalentDealsEvent.RequestRemove(h.state.register.contracts.single()), TalentDealsEvent.CancelRemove,
            TalentDealsEvent.ConfirmRemove)
        assertEquals(0, repo.removes.size)
        h.collector.cancel()
    }

    @Test
    fun `a reader cannot ask to remove, and a confirm with nothing pending removes nothing`() = runTest(dispatcher) {
        viewer = Talent.reader
        repo.rows = mutableListOf(Talent.contract("c1"))
        val h = open()
        send(h, TalentDealsEvent.RequestRemove(h.state.register.contracts.single()), TalentDealsEvent.ConfirmRemove)
        assertNull(h.state.register.deleteTarget)
        assertEquals(0, repo.removes.size)
        assertTrue(h.notices().single().isError)
        h.collector.cancel()
    }

    @Test
    fun `losing the posting right between asking and confirming stops the remove`() = runTest(dispatcher) {
        repo.rows = mutableListOf(Talent.contract("c1"))
        val h = open()
        send(h, TalentDealsEvent.RequestRemove(h.state.register.contracts.single()))
        viewer = Talent.reader
        h.vm.onRightsChanged()
        send(h, TalentDealsEvent.ConfirmRemove)
        assertEquals(0, repo.removes.size)
        h.collector.cancel()
    }

    // -- rights changing under an open face --------------------------------------------

    @Test
    fun `rights that stop allowing the form send the viewer to the register`() = runTest(dispatcher) {
        val h = open()
        send(h, TalentDealsEvent.AddContract, TalentDealsEvent.PickRole("Cast"))
        assertIs<TalentRoute.New>(h.state.route)

        viewer = Talent.reader
        h.vm.onRightsChanged()
        advanceUntilIdle()
        assertEquals(TalentRoute.Register, h.state.route)
        h.collector.cancel()
    }

    @Test
    fun `a production switch wipes everything`() = runTest(dispatcher) {
        repo.rows = mutableListOf(Talent.contract("c1"))
        val h = open()
        assertEquals(1, h.state.register.contracts.size)
        h.vm.onProjectChanged()
        assertEquals(emptyList(), h.state.register.contracts)
        assertFalse(h.state.viewer.rightsLoaded)
        h.collector.cancel()
    }

    // -- Exhibit G ---------------------------------------------------------------------

    private val day = SigningDay("r1", 1767571200000L, emptyList(), listOf(SigningRow(rowId = "a", name = "Jane Doe",
        signed = true)))

    @Test
    fun `the read-only page looks the talent up once the panel is opened, by the saved name`() = runTest(dispatcher) {
        repo.rows = mutableListOf(Talent.contract("c1", person = " Jane Doe "))
        repo.exhibitDays = listOf(day)
        val h = open()
        send(h, TalentDealsEvent.OpenContract("c1"))
        assertEquals(emptyList(), repo.exhibitAsks)

        send(h, TalentDealsEvent.ToggleExhibit)
        assertEquals(listOf("Jane Doe"), repo.exhibitAsks)
        assertEquals(listOf(day), h.state.exhibit.days)
        assertFalse(h.state.exhibit.loading)

        // Closing forgets the answer; opening again asks again.
        send(h, TalentDealsEvent.ToggleExhibit)
        assertEquals(emptyList(), h.state.exhibit.days)
        send(h, TalentDealsEvent.ToggleExhibit)
        assertEquals(2, repo.exhibitAsks.size)
        h.collector.cancel()
    }

    @Test
    fun `no Exhibit G for another role or another union`() = runTest(dispatcher) {
        repo.rows = mutableListOf(Talent.contract("d1", role = "Director", union = "dga"), Talent.contract("c2",
            union = "equity-uk"))
        val h = open()
        send(h, TalentDealsEvent.OpenContract("d1"), TalentDealsEvent.ToggleExhibit)
        send(h, TalentDealsEvent.Back, TalentDealsEvent.OpenContract("c2"), TalentDealsEvent.ToggleExhibit)
        assertEquals(emptyList(), repo.exhibitAsks)
        h.collector.cancel()
    }

    @Test
    fun `a lookup that fails shows the server's words in the panel`() = runTest(dispatcher) {
        repo.rows = mutableListOf(Talent.contract("c1"))
        repo.exhibitError = ZillitError.Http(404, "exhibit_g_person_not_found")
        val h = open()
        send(h, TalentDealsEvent.OpenContract("c1"), TalentDealsEvent.ToggleExhibit)
        assertNotNull(h.state.exhibit.error)
        assertEquals(emptyList(), h.state.exhibit.days)
        h.collector.cancel()
    }

    @Test
    fun `on the form the lookup waits for the committed name, and a real change refetches`() = runTest(dispatcher) {
        val h = open()
        repo.unionRows = listOf(TalentUnion("1", "sag-aftra", "SAG"))
        send(h, TalentDealsEvent.AddContract, TalentDealsEvent.PickRole("Cast"), TalentDealsEvent.SelectTerritory("us"))
        send(h, h.edit { copy(union = "sag-aftra", person = "jane doe") }, TalentDealsEvent.ToggleExhibit)
        // Typing alone fetches nothing.
        assertEquals(emptyList(), repo.exhibitAsks)

        send(h, TalentDealsEvent.CommitName("jane doe"))
        assertEquals(listOf("jane doe"), repo.exhibitAsks)

        // The server ignores case and spacing; so does the commit.
        send(h, TalentDealsEvent.CommitName("Jane  Doe"))
        assertEquals(1, repo.exhibitAsks.size)

        send(h, TalentDealsEvent.CommitName("Janet Doe"))
        assertEquals(listOf("jane doe", "Janet Doe"), repo.exhibitAsks)
        h.collector.cancel()
    }

    @Test
    fun `Exhibit G is not offered on the form for another union`() = runTest(dispatcher) {
        val h = open()
        send(h, TalentDealsEvent.AddContract, TalentDealsEvent.PickRole("Cast"), h.edit { copy(union = "equity-uk",
            person = "Jane") })
        send(h, TalentDealsEvent.ToggleExhibit, TalentDealsEvent.CommitName("Jane"))
        assertFalse(h.state.form.exhibitApplies)
        assertEquals(emptyList(), repo.exhibitAsks)
        h.collector.cancel()
    }

    // -- the file viewer ---------------------------------------------------------------

    @Test
    fun `a PDF opens as rendered pages and only download_access offers Download`() = runTest(dispatcher) {
        viewer = Talent.reader
        repo.rows = mutableListOf(Talent.contract("c1"))
        val h = open()
        send(h, TalentDealsEvent.OpenContract("c1"))
        val attachment = h.state.detail.contract!!.longFormContract!!
        send(h, TalentDealsEvent.OpenAttachment(attachment))

        val view = h.state.viewing!!
        assertFalse(view.loading)
        assertEquals(1, view.pages.size)
        assertEquals(listOf(attachment), files.fetched)

        // View is open to every viewer; leaving with the file is gated.
        send(h, TalentDealsEvent.DownloadViewed)
        assertEquals(emptyList(), files.saved)
        assertFalse(h.state.viewer.canDownload)

        viewer = Talent.reader2
        h.vm.onRightsChanged()
        send(h, TalentDealsEvent.DownloadViewed)
        assertEquals(listOf("Agreement-c1.pdf"), files.saved)

        send(h, TalentDealsEvent.CloseViewer)
        assertNull(h.state.viewing)
        h.collector.cancel()
    }

    @Test
    fun `an accountant downloads without a download row`() = runTest(dispatcher) {
        viewer = Talent.reader.copy(departmentIdentifier = "department_accounts")
        repo.rows = mutableListOf(Talent.contract("c1"))
        val h = open()
        send(h, TalentDealsEvent.OpenContract("c1"),
            TalentDealsEvent.OpenAttachment(h.state.register.contracts.single().longFormContract!!))
        send(h, TalentDealsEvent.DownloadViewed)
        assertEquals(1, files.saved.size)
        h.collector.cancel()
    }

    @Test
    fun `a file the viewer cannot draw says so rather than failing`() = runTest(dispatcher) {
        repo.rows = mutableListOf(Talent.contract("c1"))
        val h = open()
        val docx = Talent.contract("c1").longFormContract!!.copy(name = "Deal.docx", contentSubtype = "docx")
        send(h, TalentDealsEvent.OpenContract("c1"), TalentDealsEvent.OpenAttachment(docx))
        assertTrue(h.state.viewing!!.unsupported)

        val png = docx.copy(name = "cert.png", contentSubtype = "png")
        send(h, TalentDealsEvent.OpenAttachment(png))
        assertNotNull(h.state.viewing!!.image)
        h.collector.cancel()
    }
}
