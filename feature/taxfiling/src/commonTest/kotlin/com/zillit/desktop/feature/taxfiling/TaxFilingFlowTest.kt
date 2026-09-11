package com.zillit.desktop.feature.taxfiling

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.taxfiling.domain.BoxMapping
import com.zillit.desktop.feature.taxfiling.domain.FiledReturn
import com.zillit.desktop.feature.taxfiling.domain.FilingObligation
import com.zillit.desktop.feature.taxfiling.domain.FraudSignalSource
import com.zillit.desktop.feature.taxfiling.domain.FraudSignals
import com.zillit.desktop.feature.taxfiling.domain.LedgerLine
import com.zillit.desktop.feature.taxfiling.domain.TaxCompany
import com.zillit.desktop.feature.taxfiling.domain.TaxFiling
import com.zillit.desktop.feature.taxfiling.domain.TaxFilingRepository
import com.zillit.desktop.feature.taxfiling.domain.TaxRegistration
import com.zillit.desktop.feature.taxfiling.domain.VatBox
import com.zillit.desktop.feature.taxfiling.domain.VatDraft
import com.zillit.desktop.feature.taxfiling.domain.VatReturn
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingEvent
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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

/**
 * What this module refuses to do, mostly.
 *
 * The one screen in the application that files a legal document with a tax
 * authority, so its tests are about the guards: no machine description, no
 * HMRC call; no confirmation, no filing; a fulfilled period, no filing at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaxFilingFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val connected = TaxRegistration(
        id = "reg-1",
        companyId = "co-1",
        companyName = "Zillit Films Ltd",
        registrationNumber = "123456789",
        connected = true,
    )

    private open class FakeRepo : TaxFilingRepository {
        var registrationRows = listOf<TaxRegistration>()
        var obligationRows = listOf(FilingObligation(periodKey = "18A1", status = "O"))
        var syncedRows = listOf(FilingObligation(periodKey = "18A1", status = "F"))
        var draft = VatDraft(VatReturn(mapOf(VatBox.DueOnSales to 100.0), periodKey = "18A1"))
        var filedRows = listOf<FiledReturn>()
        var ledgerRows = listOf(LedgerLine(box = "box1", accountCode = "4000", credit = 100.0))

        val savedMaps = mutableListOf<List<BoxMapping>>()
        val submissions = mutableListOf<Triple<String, VatReturn, FraudSignals>>()
        val syncs = mutableListOf<FraudSignals>()
        var draftBuilds = 0
        var createdCount = 0
        var deletedCount = 0

        override suspend fun catalog(): ZillitResult<List<TaxFiling>> =
            ZillitResult.Success(listOf(TaxFiling(key = "mtd-vat")))

        override suspend fun companies(): ZillitResult<List<TaxCompany>> =
            ZillitResult.Success(listOf(TaxCompany("co-1", "Zillit")))

        override suspend fun registrations(): ZillitResult<List<TaxRegistration>> =
            ZillitResult.Success(registrationRows)

        override suspend fun createRegistration(
            companyId: String,
            registrationNumber: String,
            frequency: String,
        ): ZillitResult<Unit> {
            createdCount++
            return ZillitResult.Success(Unit)
        }

        override suspend fun deleteRegistration(id: String): ZillitResult<Unit> {
            deletedCount++
            return ZillitResult.Success(Unit)
        }

        override suspend fun connectUrl(registrationId: String): ZillitResult<String> =
            ZillitResult.Success("https://hmrc.example/consent")

        override suspend fun boxMap(companyId: String): ZillitResult<List<BoxMapping>> =
            ZillitResult.Success(emptyList())

        override suspend fun saveBoxMap(companyId: String, rows: List<BoxMapping>): ZillitResult<Unit> {
            savedMaps += rows
            return ZillitResult.Success(Unit)
        }

        override suspend fun obligations(registrationId: String): ZillitResult<List<FilingObligation>> =
            ZillitResult.Success(obligationRows)

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
            draftBuilds++
            return ZillitResult.Success(draft)
        }

        override suspend fun submitReturn(
            registrationId: String,
            periodKey: String,
            vatReturn: VatReturn,
            signals: FraudSignals,
        ): ZillitResult<Unit> {
            submissions += Triple(periodKey, vatReturn, signals)
            return ZillitResult.Success(Unit)
        }

        override suspend fun filedReturns(registrationId: String): ZillitResult<List<FiledReturn>> =
            ZillitResult.Success(filedRows)

        override suspend fun ledgerLines(
            registrationId: String,
            periodKey: String,
        ): ZillitResult<List<LedgerLine>> = ZillitResult.Success(ledgerRows)
    }

    private fun viewModel(
        repo: FakeRepo,
        signals: FraudSignalSource? = FraudSignalSource {
            FraudSignals(deviceId = "dev-1", timezone = "UTC+00:00")
        },
    ) = TaxFilingViewModel(
        repository = repo,
        signals = signals,
        obligationWindow = { "2025-09-10" to "2026-09-10" },
    )

    /**
     * Without a machine description, HMRC is not called at all.
     *
     * A return rejected for missing anti-fraud headers is a return the
     * accountant believes they filed, so it is refused here instead.
     */
    @Test
    fun `no anti-fraud signals means the authority is never reached`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val model = viewModel(repo, signals = null)

        assertFalse(model.state.value.canReachAuthority)

        model.onEvent(TaxFilingEvent.Open(connected))
        runCurrent()
        model.onEvent(TaxFilingEvent.SyncObligations)
        runCurrent()

        assertTrue(repo.syncs.isEmpty())
    }

    /** An incomplete description is the same refusal — a blank device is not a device. */
    @Test
    fun `an incomplete machine description is refused like a missing one`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val model = viewModel(repo, signals = FraudSignalSource { FraudSignals() })

        model.onEvent(TaxFilingEvent.Open(connected))
        runCurrent()
        model.onEvent(TaxFilingEvent.SyncObligations)
        runCurrent()

        assertTrue(repo.syncs.isEmpty())
        assertFalse(model.state.value.returnState.syncing)
    }

    /**
     * The mapping is saved before the draft is built.
     *
     * A draft built from a mapping the server has not been told about is
     * figures nobody can reproduce.
     */
    @Test
    fun `calculating saves the mapping first`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val model = viewModel(repo)

        model.onEvent(TaxFilingEvent.Open(connected))
        runCurrent()
        model.onEvent(TaxFilingEvent.EditMapping(BoxMapping(box = "box1", codes = listOf("4000"))))
        model.onEvent(TaxFilingEvent.Calculate)
        runCurrent()

        assertEquals(1, repo.savedMaps.size)
        assertEquals(listOf("4000"), repo.savedMaps.first().first().codes)
        assertEquals(1, repo.draftBuilds)
        assertEquals(100.0, model.state.value.returnState.draft?.get(VatBox.DueOnSales))
    }

    /** A mapping edited after a calculation invalidates the figures it produced. */
    @Test
    fun `editing the mapping drops the draft it produced`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val model = viewModel(repo)

        model.onEvent(TaxFilingEvent.Open(connected))
        runCurrent()
        model.onEvent(TaxFilingEvent.Calculate)
        runCurrent()
        assertTrue(model.state.value.returnState.draft != null)

        model.onEvent(TaxFilingEvent.EditMapping(BoxMapping(box = "box2", markZero = true)))

        assertNull(model.state.value.returnState.draft)
    }

    /** So does choosing another period: a draft belongs to the period it was built for. */
    @Test
    fun `choosing another period drops the draft`() = runTest(dispatcher) {
        val repo = FakeRepo()
        repo.obligationRows = listOf(
            FilingObligation(periodKey = "18A1", status = "O"),
            FilingObligation(periodKey = "18A2", status = "O"),
        )
        val model = viewModel(repo)

        model.onEvent(TaxFilingEvent.Open(connected))
        runCurrent()
        model.onEvent(TaxFilingEvent.Calculate)
        runCurrent()

        model.onEvent(TaxFilingEvent.SelectPeriod("18A2"))

        assertNull(model.state.value.returnState.draft)
        assertEquals("18A2", model.state.value.returnState.periodKey)
    }

    /** Asking to file does not file. The confirmation is not a formality. */
    @Test
    fun `asking to file sends nothing until it is confirmed`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val model = viewModel(repo)

        model.onEvent(TaxFilingEvent.Open(connected))
        runCurrent()
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
     * After filing, HMRC is asked again rather than the local list re-read.
     *
     * The authority decides when a period is fulfilled; the stored rows still
     * say open until it has said otherwise, and re-reading them would leave a
     * filed period looking like one still owed.
     */
    @Test
    fun `filing re-asks the authority rather than re-reading the cache`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val model = viewModel(repo)

        model.onEvent(TaxFilingEvent.Open(connected))
        runCurrent()
        model.onEvent(TaxFilingEvent.Calculate)
        runCurrent()
        model.onEvent(TaxFilingEvent.AskSubmit)
        model.onEvent(TaxFilingEvent.ConfirmSubmit)
        runCurrent()

        assertEquals(1, repo.submissions.size)
        assertEquals("18A1", repo.submissions.first().first)
        assertEquals(1, repo.syncs.size)
        assertFalse(model.state.value.returnState.obligations.first().isOpen)
    }

    /**
     * A period already filed cannot be filed again.
     *
     * Guarded here as well as on the screen: HMRC refuses a fulfilled period,
     * and a client that sends one anyway turns a clear refusal into an error
     * the accountant has to interpret.
     */
    @Test
    fun `a fulfilled period cannot be filed`() = runTest(dispatcher) {
        val repo = FakeRepo()
        repo.obligationRows = listOf(FilingObligation(periodKey = "18A1", status = "F"))
        val model = viewModel(repo)

        model.onEvent(TaxFilingEvent.Open(connected))
        runCurrent()
        model.onEvent(TaxFilingEvent.SelectPeriod("18A1"))
        model.onEvent(TaxFilingEvent.Calculate)
        runCurrent()
        model.onEvent(TaxFilingEvent.ConfirmSubmit)
        runCurrent()

        assertFalse(model.state.value.returnState.canSubmit)
        assertTrue(repo.submissions.isEmpty())
    }

    /**
     * A second press files nothing, even before HMRC has been re-asked.
     *
     * The obligation list still says open in that window, so the guard is the
     * period this screen just filed. HMRC would refuse the second one, but as
     * an error to interpret rather than a button that has stopped offering.
     */
    @Test
    fun `a period just filed cannot be filed again while the sync is in flight`() =
        runTest(dispatcher) {
            val repo = FakeRepo()
            // HMRC still reports it open, as it does until the sync catches up.
            repo.syncedRows = listOf(FilingObligation(periodKey = "18A1", status = "O"))
            val model = viewModel(repo)

            model.onEvent(TaxFilingEvent.Open(connected))
            runCurrent()
            model.onEvent(TaxFilingEvent.Calculate)
            runCurrent()
            model.onEvent(TaxFilingEvent.ConfirmSubmit)
            runCurrent()

            assertEquals(1, repo.submissions.size)
            assertFalse(model.state.value.returnState.canSubmit)

            model.onEvent(TaxFilingEvent.ConfirmSubmit)
            runCurrent()

            assertEquals(1, repo.submissions.size)
        }

    /** Nor can one whose registration has never authorised HMRC. */
    @Test
    fun `an unauthorised registration cannot file`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val model = viewModel(repo)

        model.onEvent(TaxFilingEvent.Open(connected.copy(connected = false)))
        runCurrent()
        model.onEvent(TaxFilingEvent.Calculate)
        runCurrent()
        model.onEvent(TaxFilingEvent.ConfirmSubmit)
        runCurrent()

        assertTrue(repo.submissions.isEmpty())
    }

    /** The period picker opens on the first period still owed, not the first row. */
    @Test
    fun `opening a registration lands on the first open period`() = runTest(dispatcher) {
        val repo = FakeRepo()
        repo.obligationRows = listOf(
            FilingObligation(periodKey = "17A4", status = "F"),
            FilingObligation(periodKey = "18A1", status = "O"),
        )
        val model = viewModel(repo)

        model.onEvent(TaxFilingEvent.Open(connected))
        runCurrent()

        assertEquals("18A1", model.state.value.returnState.periodKey)
    }

    /** A registration with a bad VAT number is not sent to be rejected by the server. */
    @Test
    fun `a nine-digit VAT number is required before the registration is created`() =
        runTest(dispatcher) {
            val repo = FakeRepo()
            val model = viewModel(repo)

            model.onEvent(TaxFilingEvent.ComposeRegistration)
            val draft = model.state.value.draft!!
            model.onEvent(TaxFilingEvent.EditDraft(draft.copy(companyId = "co-1", registrationNumber = "1234")))
            model.onEvent(TaxFilingEvent.SaveRegistration)
            runCurrent()

            assertEquals(0, repo.createdCount)

            model.onEvent(
                TaxFilingEvent.EditDraft(
                    model.state.value.draft!!.copy(registrationNumber = "GB 123 4567 89"),
                ),
            )
            model.onEvent(TaxFilingEvent.SaveRegistration)
            runCurrent()

            assertEquals(1, repo.createdCount)
        }

    /** A repository failure leaves nothing spinning. */
    @Test
    fun `a failed load stops the spinner`() = runTest(dispatcher) {
        val repo = object : FakeRepo() {
            override suspend fun registrations(): ZillitResult<List<TaxRegistration>> =
                ZillitResult.Failure(ZillitError.Unknown("nope"))
        }
        val model = viewModel(repo)

        model.onEvent(TaxFilingEvent.Load)
        runCurrent()

        assertFalse(model.state.value.loading)
    }
}
