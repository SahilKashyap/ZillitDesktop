package com.zillit.desktop.feature.taxfiling

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.taxfiling.domain.BoxMapping
import com.zillit.desktop.feature.taxfiling.domain.CoaCode
import com.zillit.desktop.feature.taxfiling.domain.FiledReturn
import com.zillit.desktop.feature.taxfiling.domain.FilingObligation
import com.zillit.desktop.feature.taxfiling.domain.FraudSignalSource
import com.zillit.desktop.feature.taxfiling.domain.FraudSignals
import com.zillit.desktop.feature.taxfiling.domain.LayerSet
import com.zillit.desktop.feature.taxfiling.domain.LedgerLine
import com.zillit.desktop.feature.taxfiling.domain.RegistrationRequest
import com.zillit.desktop.feature.taxfiling.domain.TaxCompany
import com.zillit.desktop.feature.taxfiling.domain.TaxFileSink
import com.zillit.desktop.feature.taxfiling.domain.TaxFiling
import com.zillit.desktop.feature.taxfiling.domain.TaxFilingRepository
import com.zillit.desktop.feature.taxfiling.domain.TaxFilingRoute
import com.zillit.desktop.feature.taxfiling.domain.TaxRegistration
import com.zillit.desktop.feature.taxfiling.domain.VatBox
import com.zillit.desktop.feature.taxfiling.domain.VatDraft
import com.zillit.desktop.feature.taxfiling.domain.VatReturn
import com.zillit.desktop.feature.taxfiling.ui.RegistrationDraft
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingEffect
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingEvent
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingView
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingViewModel
import com.zillit.desktop.feature.taxfiling.ui.TaxToastTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FILING = "/film-tools/account-hub/tax-filing/GB/mtd-vat"
private const val POLL = 1_000L

/**
 * What this module does and, mostly, what it refuses to do.
 *
 * The one screen in the application that files a legal document with a tax
 * authority, so most of these are guards: no machine description, no HMRC
 * call; no confirmation, no filing; a fulfilled period, no filing at all. The
 * rest pin the web's flow — the catalogue, a registration's life, the connect
 * round trip a desktop has to wait out rather than be redirected back from.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaxFilingFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val connected = TaxRegistration(
        id = "reg-1",
        companyId = "co-1",
        registrationNumber = "123456789",
        filingFrequency = "quarterly",
        connected = true,
    )

    private open class FakeRepo : TaxFilingRepository {
        var catalogRows = listOf(TaxFiling(country = "GB", key = "mtd-vat", title = "MTD VAT"))
        var companyRows = listOf(TaxCompany("co-1", "Zillit Films Ltd", "GB"), TaxCompany("co-2", "Second Unit Ltd"))
        var registrationRows = listOf<TaxRegistration>()
        var obligationRows = listOf(FilingObligation(periodKey = "18A1", status = "O"))
        var syncedRows = listOf(FilingObligation(periodKey = "18A1", status = "F"))
        var draft = VatDraft(VatReturn(mapOf(VatBox.DueOnSales to 100.0), periodKey = "18A1"))
        var filedRows = listOf<FiledReturn>()
        var ledgerRows = listOf(LedgerLine(box = "box1", accountCode = "4000", credit = 100.0))
        var mapRows = listOf<BoxMapping>()

        val calls = mutableListOf<String>()
        val savedMaps = mutableListOf<List<BoxMapping>>()
        val created = mutableListOf<RegistrationRequest>()
        val submissions = mutableListOf<Triple<String, VatReturn, FraudSignals>>()
        val syncs = mutableListOf<FraudSignals>()

        override suspend fun catalog() = ZillitResult.Success(catalogRows).also { calls += "catalog" }
        override suspend fun companies() = ZillitResult.Success(companyRows).also { calls += "companies" }
        override suspend fun registrations(): ZillitResult<List<TaxRegistration>> =
            ZillitResult.Success(registrationRows).also { calls += "registrations" }

        override suspend fun createRegistration(request: RegistrationRequest): ZillitResult<Unit> {
            created += request
            registrationRows = registrationRows + TaxRegistration(id = "new", companyId = request.companyId)
            return ZillitResult.Success(Unit)
        }

        override suspend fun deleteRegistration(id: String): ZillitResult<Unit> {
            calls += "delete:$id"
            registrationRows = registrationRows.filterNot { it.id == id }
            return ZillitResult.Success(Unit)
        }

        override suspend fun exportRegistration(id: String) = ZillitResult.Success("{\n  \"id\": \"$id\"\n}")
        override suspend fun connectUrl(registrationId: String) = ZillitResult.Success("https://hmrc.test/consent")
        override suspend fun boxMap(companyId: String) = ZillitResult.Success(mapRows).also { calls += "box-map" }

        override suspend fun saveBoxMap(companyId: String, rows: List<BoxMapping>): ZillitResult<Unit> {
            savedMaps += rows
            return ZillitResult.Success(Unit)
        }

        override suspend fun obligations(registrationId: String) = ZillitResult.Success(obligationRows)

        override suspend fun syncObligations(
            registrationId: String,
            fromDate: String,
            toDate: String,
            signals: FraudSignals,
        ): ZillitResult<List<FilingObligation>> {
            syncs += signals
            return ZillitResult.Success(syncedRows)
        }

        override suspend fun buildDraft(registrationId: String, periodKey: String): ZillitResult<VatDraft> {
            calls += "draft:$periodKey"
            return ZillitResult.Success(draft)
        }

        override suspend fun filedReturns(registrationId: String) = ZillitResult.Success(filedRows)
        override suspend fun ledgerLines(registrationId: String, periodKey: String) = ZillitResult.Success(ledgerRows)

        override suspend fun submitReturn(
            registrationId: String,
            periodKey: String,
            vatReturn: VatReturn,
            signals: FraudSignals,
        ): ZillitResult<Unit> {
            submissions += Triple(periodKey, vatReturn, signals)
            return ZillitResult.Success(Unit)
        }

        override suspend fun coaCodes() = ZillitResult.Success(listOf(CoaCode("4000", "Sales"))).also { calls += "coa" }
        override suspend fun layerSets() = ZillitResult.Success(listOf(LayerSet("set-loc", "Locations")))
        override suspend fun assetTags() = ZillitResult.Success(listOf("VFX"))
    }

    private class SavedFile(val name: String, val bytes: ByteArray, val open: Boolean)

    private val saved = mutableListOf<SavedFile>()
    private val sink = TaxFileSink { name, bytes, open ->
        saved += SavedFile(name, bytes, open)
        ZillitResult.Success(Unit)
    }

    private fun viewModel(
        repo: FakeRepo,
        signals: FraudSignalSource? = FraudSignalSource { FraudSignals(deviceId = "dev-1", timezone = "UTC+00:00") },
        project: MutableStateFlow<String?>? = null,
    ) = TaxFilingViewModel(
        repository = repo,
        signals = signals,
        obligationWindow = { "2025-09-10" to "2026-09-10" },
        fileSink = sink,
        exportStamp = { "2026-09-12_1430" },
        projectChanges = project,
        connectPollMillis = POLL,
        connectPolls = 3,
    )

    /** Everything the view model says, in order, for a test to read back. */
    private fun TestScope.effectsOf(model: TaxFilingViewModel): MutableList<TaxFilingEffect> {
        val seen = mutableListOf<TaxFilingEffect>()
        backgroundScope.launch(dispatcher) { model.effects.collect { seen += it } }
        return seen
    }

    private fun TestScope.openFiling(model: TaxFilingViewModel) {
        model.onEvent(TaxFilingEvent.RouteChanged(FILING))
        runCurrent()
    }

    private fun TestScope.openReturn(model: TaxFilingViewModel, registration: TaxRegistration = connected) {
        model.onEvent(TaxFilingEvent.Open(registration))
        runCurrent()
    }

    // -- the catalogue and its routes --------------------------------------------------

    /** The landing page loads the catalogue and orders it by the companies' countries. */
    @Test
    fun `the catalogue loads with the countries that order it`() = runTest(dispatcher) {
        val repo = FakeRepo()
        repo.catalogRows = repo.catalogRows + TaxFiling(country = "IE", key = "ros-vat", title = "ROS VAT")
        val model = viewModel(repo)

        model.onEvent(TaxFilingEvent.RouteChanged(TaxFilingRoute.BASE_PATH))
        runCurrent()

        val catalog = model.state.value.catalog
        assertFalse(catalog.loading)
        assertEquals(listOf("mtd-vat"), catalog.mine.map { it.key })
        assertEquals(listOf("ros-vat"), catalog.others.map { it.key })
        assertTrue(catalog.grouped)
    }

    /** A card opens its filing as a route; the back chip walks back up the same way. */
    @Test
    fun `opening a filing and going back are route changes`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val model = viewModel(repo)
        val effects = effectsOf(model)

        model.onEvent(TaxFilingEvent.OpenFiling(repo.catalogRows.first()))
        openFiling(model)
        model.onEvent(TaxFilingEvent.Back)
        model.onEvent(TaxFilingEvent.RouteChanged(TaxFilingRoute.BASE_PATH))
        runCurrent()
        model.onEvent(TaxFilingEvent.Back)
        runCurrent()

        assertEquals(
            listOf(
                TaxFilingEffect.Navigate(FILING),
                TaxFilingEffect.Navigate(TaxFilingRoute.BASE_PATH),
                TaxFilingEffect.LeaveTool,
            ),
            effects.filter { it !is TaxFilingEffect.Toast },
        )
    }

    /** A filing this client has no screen for fetches nothing and opens nothing. */
    @Test
    fun `an unsupported filing loads nothing`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val model = viewModel(repo)

        model.onEvent(TaxFilingEvent.RouteChanged("${TaxFilingRoute.BASE_PATH}/FR/tva"))
        runCurrent()

        assertTrue(repo.calls.isEmpty())
        assertEquals(TaxFilingRoute.Filing("FR", "tva"), model.state.value.route)
    }

    /** Entering a filing lists its registrations, named by their companies. */
    @Test
    fun `a filing lists its registrations by company name`() = runTest(dispatcher) {
        val repo = FakeRepo()
        repo.registrationRows = listOf(connected)
        val model = viewModel(repo)

        openFiling(model)

        val state = model.state.value
        assertFalse(state.loading)
        assertEquals("Zillit Films Ltd", state.named.single().companyName)
        assertEquals(listOf("co-2"), state.availableCompanies.map { it.id })
    }

    // -- a registration's life ----------------------------------------------------------

    /**
     * A new registration carries its authority and date, and the number is
     * nine digits however it was typed.
     */
    @Test
    fun `registering sends the web's fields and closes the dialog`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val model = viewModel(repo)
        val effects = effectsOf(model)
        openFiling(model)

        model.onEvent(TaxFilingEvent.ComposeRegistration)
        model.onEvent(
            TaxFilingEvent.EditDraft(
                RegistrationDraft(
                    companyId = "co-1",
                    registrationNumber = "GB 123 456 789 0",
                    registrationDate = "2026-04-01",
                ),
            ),
        )
        assertEquals("123456789", model.state.value.draft?.registrationNumber)
        model.onEvent(TaxFilingEvent.SaveRegistration)
        runCurrent()

        val request = repo.created.single()
        assertEquals("GB", request.countryCode)
        assertEquals("VAT", request.regime)
        assertEquals("2026-04-01", request.registrationDate)
        assertEquals("quarterly", request.filingFrequency)
        assertNull(model.state.value.draft)
        assertTrue(effects.any { it is TaxFilingEffect.Toast && it.toast.message == "Registered Zillit Films Ltd" })
    }

    /** A number short of nine digits, or a date that is not one, is not sent. */
    @Test
    fun `an incomplete registration is not sent`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val model = viewModel(repo)
        openFiling(model)

        model.onEvent(TaxFilingEvent.ComposeRegistration)
        model.onEvent(TaxFilingEvent.EditDraft(RegistrationDraft(companyId = "co-1", registrationNumber = "1234")))
        model.onEvent(TaxFilingEvent.SaveRegistration)
        model.onEvent(
            TaxFilingEvent.EditDraft(
                RegistrationDraft(companyId = "co-1", registrationNumber = "123456789", registrationDate = "2026-13"),
            ),
        )
        model.onEvent(TaxFilingEvent.SaveRegistration)
        runCurrent()

        assertTrue(repo.created.isEmpty())
    }

    /** Removing the registration whose return is open closes the return too. */
    @Test
    fun `removing the open registration closes its return`() = runTest(dispatcher) {
        val repo = FakeRepo()
        repo.registrationRows = listOf(connected)
        val model = viewModel(repo)
        openFiling(model)
        openReturn(model)

        model.onEvent(TaxFilingEvent.AskRemove(connected))
        model.onEvent(TaxFilingEvent.ConfirmRemove)
        runCurrent()

        assertTrue("delete:reg-1" in repo.calls)
        assertEquals(TaxFilingView.Registrations, model.state.value.view)
        assertNull(model.state.value.removing)
        assertTrue(model.state.value.registrations.isEmpty())
    }

    /** The data export is saved, not opened, under the web's file name. */
    @Test
    fun `exporting a registration's data saves its JSON`() = runTest(dispatcher) {
        val repo = FakeRepo()
        repo.registrationRows = listOf(connected)
        val model = viewModel(repo)
        openFiling(model)

        model.onEvent(TaxFilingEvent.ExportData(connected))
        runCurrent()

        val file = saved.single()
        assertEquals("tax-filing-zillit-films-ltd-123456789.json", file.name)
        assertFalse(file.open)
        assertTrue(file.bytes.decodeToString().contains("\"reg-1\""))
    }

    /**
     * Connecting opens HMRC's consent in the browser and then waits for it.
     *
     * The web is redirected back; a desktop is not, so it asks again every few
     * seconds and says so when the grant lands.
     */
    @Test
    fun `connecting waits for the grant and says when it lands`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val pending = connected.copy(connected = false)
        repo.registrationRows = listOf(pending)
        val model = viewModel(repo)
        val effects = effectsOf(model)
        openFiling(model)

        model.onEvent(TaxFilingEvent.Connect(pending))
        runCurrent()
        assertTrue(TaxFilingEffect.OpenInBrowser("https://hmrc.test/consent") in effects)
        assertEquals("reg-1", model.state.value.connectingId)

        repo.registrationRows = listOf(connected)
        advanceTimeBy(POLL + 1)
        runCurrent()

        assertNull(model.state.value.connectingId)
        assertTrue(model.state.value.named.single().connected)
        assertTrue(effects.any { it is TaxFilingEffect.Toast && it.toast.message == "Connected to HMRC" })
    }

    /** A consent never given stops being waited for, and the accountant is told. */
    @Test
    fun `an abandoned consent stops being waited for`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val pending = connected.copy(connected = false)
        repo.registrationRows = listOf(pending)
        val model = viewModel(repo)
        val effects = effectsOf(model)
        openFiling(model)

        model.onEvent(TaxFilingEvent.Connect(pending))
        runCurrent()
        advanceTimeBy(POLL * 4)
        runCurrent()

        assertNull(model.state.value.connectingId)
        assertTrue(effects.any { it is TaxFilingEffect.Toast && it.toast.tone == TaxToastTone.Info })
    }

    // -- the return ------------------------------------------------------------------------

    /** The web's "Open VAT return" waits for HMRC; so does the view model. */
    @Test
    fun `a registration not connected to HMRC cannot be opened`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val model = viewModel(repo)
        openFiling(model)

        openReturn(model, connected.copy(connected = false))

        assertEquals(TaxFilingView.Registrations, model.state.value.view)
    }

    /**
     * Opening a return loads its periods, receipts, mapping and the pickers'
     * lists — and leaves the period for the accountant to choose, as the web does.
     */
    @Test
    fun `opening a return loads everything and chooses no period`() = runTest(dispatcher) {
        val repo = FakeRepo()
        repo.mapRows = listOf(BoxMapping(box = "box6", codes = listOf("5000")))
        val model = viewModel(repo)
        openFiling(model)
        openReturn(model)

        val state = model.state.value
        assertEquals(TaxFilingView.Return, state.view)
        assertEquals("", state.returnState.periodKey)
        assertEquals(listOf("5000"), state.returnState.mappingFor(VatBox.SalesExVat).codes)
        assertEquals(listOf("4000"), state.lookups.coa.map { it.code })
        assertEquals(listOf("VFX"), state.lookups.assetTags)

        model.onEvent(TaxFilingEvent.BackToRegistrations)
        openReturn(model)
        // Once per production: the chart is not fetched again for the next return.
        assertEquals(1, repo.calls.count { it == "coa" })
    }

    /** Without a machine description, HMRC is not called at all. */
    @Test
    fun `no anti-fraud signals means the authority is never reached`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val model = viewModel(repo, signals = null)

        assertFalse(model.state.value.canReachAuthority)
        openReturn(model)
        model.onEvent(TaxFilingEvent.SyncObligations)
        runCurrent()

        assertTrue(repo.syncs.isEmpty())
    }

    /** An incomplete description is the same refusal — a blank device is not a device. */
    @Test
    fun `an incomplete machine description is refused like a missing one`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val model = viewModel(repo, signals = FraudSignalSource { FraudSignals() })

        openReturn(model)
        model.onEvent(TaxFilingEvent.SyncObligations)
        runCurrent()

        assertTrue(repo.syncs.isEmpty())
        assertFalse(model.state.value.returnState.syncing)
    }

    /** Calculating needs a period, as the web's button does. */
    @Test
    fun `calculating without a period builds nothing`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val model = viewModel(repo)
        openReturn(model)

        model.onEvent(TaxFilingEvent.Calculate)
        runCurrent()

        assertTrue(repo.savedMaps.isEmpty())
        assertFalse(repo.calls.any { it.startsWith("draft") })
    }

    /** The mapping is saved before the draft is built: figures nobody can reproduce otherwise. */
    @Test
    fun `calculating saves the mapping first`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val model = viewModel(repo)
        openReturn(model)
        model.onEvent(TaxFilingEvent.SelectPeriod("18A1"))

        model.onEvent(TaxFilingEvent.EditMapping(BoxMapping(box = "box1", codes = listOf("4000"))))
        model.onEvent(TaxFilingEvent.Calculate)
        runCurrent()

        assertEquals(listOf("4000"), repo.savedMaps.single().first().codes)
        assertTrue("draft:18A1" in repo.calls)
        assertEquals(100.0, model.state.value.returnState.draft?.get(VatBox.DueOnSales))
    }

    /** A date typed but not a date would be saved as no date — so nothing is saved. */
    @Test
    fun `a box with an unreadable date is not saved`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val model = viewModel(repo)
        openReturn(model)

        model.onEvent(
            TaxFilingEvent.EditMapping(BoxMapping(box = "box1", codes = listOf("4000"), fromDate = "2026-02")),
        )
        model.onEvent(TaxFilingEvent.SaveMapping)
        runCurrent()

        assertTrue(repo.savedMaps.isEmpty())
    }

    /**
     * A mapping edited after a calculation leaves its figures on screen, as
     * the web does — but stale, and not to be submitted until recalculated.
     */
    @Test
    fun `editing the mapping makes the draft stale and unsubmittable`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val model = viewModel(repo)
        openReturn(model)
        model.onEvent(TaxFilingEvent.SelectPeriod("18A1"))
        model.onEvent(TaxFilingEvent.Calculate)
        runCurrent()
        assertTrue(model.state.value.returnState.canSubmit)

        model.onEvent(TaxFilingEvent.EditMapping(BoxMapping(box = "box2", markZero = true)))

        val state = model.state.value.returnState
        assertTrue(state.draft != null)
        assertTrue(state.draftStale)
        assertFalse(state.canSubmit)

        model.onEvent(TaxFilingEvent.Calculate)
        runCurrent()
        assertFalse(model.state.value.returnState.draftStale)
    }

    /** Choosing another period drops the draft: it belongs to the period it was built for. */
    @Test
    fun `choosing another period drops the draft`() = runTest(dispatcher) {
        val repo = FakeRepo()
        repo.obligationRows = listOf(
            FilingObligation(periodKey = "18A1", status = "O"),
            FilingObligation(periodKey = "18A2", status = "O"),
        )
        val model = viewModel(repo)
        openReturn(model)
        model.onEvent(TaxFilingEvent.SelectPeriod("18A1"))
        model.onEvent(TaxFilingEvent.Calculate)
        runCurrent()

        model.onEvent(TaxFilingEvent.SelectPeriod("18A2"))

        assertNull(model.state.value.returnState.draft)
        assertEquals("18A2", model.state.value.returnState.periodKey)
    }

    /** Asking to submit does not submit. The confirmation is not a formality. */
    @Test
    fun `asking to submit sends nothing until it is confirmed`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val model = viewModel(repo)
        openReturn(model)
        model.onEvent(TaxFilingEvent.SelectPeriod("18A1"))
        model.onEvent(TaxFilingEvent.Calculate)
        runCurrent()

        model.onEvent(TaxFilingEvent.AskSubmit)
        runCurrent()
        assertTrue(model.state.value.returnState.confirmingSubmit)
        assertTrue(repo.submissions.isEmpty())

        model.onEvent(TaxFilingEvent.DismissSubmit)
        runCurrent()
        assertTrue(repo.submissions.isEmpty())
    }

    /**
     * After filing, HMRC is asked again rather than the local list re-read:
     * the authority decides when a period is fulfilled.
     */
    @Test
    fun `submitting re-asks the authority rather than re-reading the cache`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val model = viewModel(repo)
        openReturn(model)
        model.onEvent(TaxFilingEvent.SelectPeriod("18A1"))
        model.onEvent(TaxFilingEvent.Calculate)
        runCurrent()
        model.onEvent(TaxFilingEvent.AskSubmit)
        model.onEvent(TaxFilingEvent.ConfirmSubmit)
        runCurrent()

        assertEquals("18A1", repo.submissions.single().first)
        assertEquals(1, repo.syncs.size)
        assertFalse(model.state.value.returnState.obligations.first().isOpen)
        assertNull(model.state.value.returnState.draft)
    }

    /** A period already filed cannot be filed again, guarded here as well as on screen. */
    @Test
    fun `a fulfilled period cannot be filed`() = runTest(dispatcher) {
        val repo = FakeRepo()
        repo.obligationRows = listOf(FilingObligation(periodKey = "18A1", status = "F"))
        val model = viewModel(repo)
        openReturn(model)
        model.onEvent(TaxFilingEvent.SelectPeriod("18A1"))
        model.onEvent(TaxFilingEvent.Calculate)
        runCurrent()
        model.onEvent(TaxFilingEvent.ConfirmSubmit)
        runCurrent()

        assertFalse(model.state.value.returnState.canSubmit)
        assertTrue(repo.submissions.isEmpty())
    }

    /** A second press files nothing, even before HMRC has been re-asked. */
    @Test
    fun `a period just filed cannot be filed again while the sync is in flight`() = runTest(dispatcher) {
        val repo = FakeRepo()
        // HMRC still reports it open, as it does until the sync catches up.
        repo.syncedRows = listOf(FilingObligation(periodKey = "18A1", status = "O"))
        val model = viewModel(repo)
        openReturn(model)
        model.onEvent(TaxFilingEvent.SelectPeriod("18A1"))
        model.onEvent(TaxFilingEvent.Calculate)
        runCurrent()
        model.onEvent(TaxFilingEvent.AskSubmit)
        model.onEvent(TaxFilingEvent.ConfirmSubmit)
        runCurrent()

        model.onEvent(TaxFilingEvent.Calculate)
        runCurrent()
        model.onEvent(TaxFilingEvent.AskSubmit)
        model.onEvent(TaxFilingEvent.ConfirmSubmit)
        runCurrent()

        assertEquals(1, repo.submissions.size)
    }

    /** Syncing keeps the chosen period when HMRC still lists it, and clears it when not. */
    @Test
    fun `a sync keeps the chosen period only while it exists`() = runTest(dispatcher) {
        val repo = FakeRepo()
        repo.syncedRows = listOf(FilingObligation(periodKey = "18A2", status = "O"))
        val model = viewModel(repo)
        openReturn(model)
        model.onEvent(TaxFilingEvent.SelectPeriod("18A1"))

        model.onEvent(TaxFilingEvent.SyncObligations)
        runCurrent()

        assertEquals("", model.state.value.returnState.periodKey)
        assertEquals(listOf("18A2"), model.state.value.returnState.obligations.map { it.periodKey })
    }

    /** The ledger export is a workbook, saved and opened, named for the period. */
    @Test
    fun `the ledger export saves and opens a workbook`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val model = viewModel(repo)
        openReturn(model)
        model.onEvent(TaxFilingEvent.SelectPeriod("18A1"))

        model.onEvent(TaxFilingEvent.ExportLedger)
        runCurrent()

        val file = saved.single()
        assertEquals("vat-ledger_18A1_2026-09-12_1430.xlsx", file.name)
        assertTrue(file.open)
        // A zip — an xlsx is one.
        assertEquals('P'.code.toByte(), file.bytes[0])
        assertFalse(model.state.value.returnState.exporting)
    }

    /** Nothing to export is said, not saved as an empty sheet. */
    @Test
    fun `an empty ledger is not exported`() = runTest(dispatcher) {
        val repo = FakeRepo()
        repo.ledgerRows = emptyList()
        val model = viewModel(repo)
        val effects = effectsOf(model)
        openReturn(model)
        model.onEvent(TaxFilingEvent.SelectPeriod("18A1"))

        model.onEvent(TaxFilingEvent.ExportLedger)
        runCurrent()

        assertTrue(saved.isEmpty())
        assertTrue(effects.any { it is TaxFilingEffect.Toast && it.toast.message.startsWith("No ledger rows") })
    }

    /** "Expand all" opens every mapped box; pressed again, it closes them. */
    @Test
    fun `expand all and collapse all`() = runTest(dispatcher) {
        val model = viewModel(FakeRepo())
        openReturn(model)

        model.onEvent(TaxFilingEvent.ToggleAllBoxes)
        assertEquals(VatBox.mappable.toSet(), model.state.value.returnState.expanded)
        model.onEvent(TaxFilingEvent.ToggleAllBoxes)
        assertTrue(model.state.value.returnState.expanded.isEmpty())
    }

    // -- the production ----------------------------------------------------------------------

    /**
     * A production switch forgets the last one — and an answer still on its way
     * from the last one lands nowhere.
     */
    @Test
    fun `a production switch forgets the last production`() = runTest(dispatcher) {
        val repo = FakeRepo()
        repo.registrationRows = listOf(connected)
        val project = MutableStateFlow<String?>("p1")
        val model = viewModel(repo, project = project)
        runCurrent()
        openFiling(model)
        openReturn(model)
        assertEquals(TaxFilingView.Return, model.state.value.view)

        repo.registrationRows = emptyList()
        project.value = "p2"
        runCurrent()

        val state = model.state.value
        assertEquals(TaxFilingView.Registrations, state.view)
        assertTrue(state.registrations.isEmpty())
        assertFalse(state.lookups.loaded)
    }

    /** A repository failure leaves nothing spinning. */
    @Test
    fun `a failed load stops the spinner`() = runTest(dispatcher) {
        val repo = object : FakeRepo() {
            override suspend fun registrations(): ZillitResult<List<TaxRegistration>> =
                ZillitResult.Failure(ZillitError.Unknown("nope"))
        }
        val model = viewModel(repo)

        openFiling(model)

        assertFalse(model.state.value.loading)
    }
}
