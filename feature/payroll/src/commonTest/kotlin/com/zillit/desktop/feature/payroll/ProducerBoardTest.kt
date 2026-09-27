package com.zillit.desktop.feature.payroll

import com.zillit.desktop.feature.payroll.domain.ActiveDeal
import com.zillit.desktop.feature.payroll.domain.ActiveDealSource
import com.zillit.desktop.feature.payroll.domain.DayCalc
import com.zillit.desktop.feature.payroll.domain.DealRates
import com.zillit.desktop.feature.payroll.domain.EstimateInput
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.PayrollCrewRow
import com.zillit.desktop.feature.payroll.domain.PayrollEstimator
import com.zillit.desktop.feature.payroll.domain.PayrollMetadata
import com.zillit.desktop.feature.payroll.domain.PayrollPerson
import com.zillit.desktop.feature.payroll.domain.PayrollProducerSeams
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import com.zillit.desktop.feature.payroll.domain.PayrollViewer
import com.zillit.desktop.feature.payroll.domain.ProductionReportSource
import com.zillit.desktop.feature.payroll.domain.ReportDay
import com.zillit.desktop.feature.payroll.domain.TimecardDay
import com.zillit.desktop.feature.payroll.domain.TimecardStatus
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.payroll.ui.EstimateStatus
import com.zillit.desktop.feature.payroll.ui.PayrollDestination
import com.zillit.desktop.feature.payroll.ui.PayrollEvent
import com.zillit.desktop.feature.payroll.ui.PayrollViewModel
import com.zillit.desktop.feature.payroll.ui.ProducerBoardEvent
import com.zillit.desktop.feature.payroll.ui.ProductionReportEvent
import com.zillit.desktop.feature.payroll.ui.estimateTimecard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two producer surfaces — the boards a Payroll TOOL TILE entry opens, and
 * the only ones a non-accountant with view access is ever offered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProducerBoardTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Wednesday 2026-05-06 12:00 UTC. */
    private val now = 1_778_068_800_000L
    private val currentMonday = PayPeriod.startOf(now, PayPeriod.MONDAY)

    private val producer = PayrollViewer("me", "department_production", "designation_producer")
        .copy(canView = true, rightsLoaded = true)

    private val people = mapOf(
        "cam" to PayrollPerson("cam", "Ada Reeve", "department_camera", "designation_focus_puller", hasDeal = true),
        "spk" to PayrollPerson("spk", "Bo Ives", "department_sparks", "designation_gaffer", hasDeal = true),
        "ext" to PayrollPerson("ext", "Cy Noor", "department_camera", "designation_trainee", hasDeal = false),
    )

    private fun row(id: String, userId: String, status: TimecardStatus = TimecardStatus.Submitted) =
        PayrollCrewRow(id = id, userId = userId, status = status, totalPay = 100.0, totalDays = 5)

    private fun TestScope.model(
        repository: FakePayrollRepository,
        route: String,
        reports: ProductionReportSource? = null,
        deals: ActiveDealSource? = null,
        estimator: PayrollEstimator? = null,
    ): PayrollViewModel {
        val model = PayrollViewModel(
            repository = repository,
            viewer = { producer },
            now = { now },
            people = { people },
            seams = PayrollProducerSeams(reports = reports, deals = deals, estimator = estimator),
        )
        model.start()
        model.onEvent(PayrollEvent.Route(route))
        advanceUntilIdle()
        return model
    }

    // -- Producer Board -------------------------------------------------------------------

    /**
     * The board reads no week out of the route: entering it mid-month lands on
     * the period in progress, and the navigator walks back from there.
     */
    @Test
    fun `the board opens on the current period, on the production's own start day`() = runTest(dispatcher) {
        val repository = FakePayrollRepository(metadata = PayrollMetadata(payPeriodStartDay = 3))
        val model = model(repository, "/film-tools/payroll/producer-board?entry=tool")
        val wednesday = PayPeriod.startOf(now, 3)
        assertEquals(PayrollDestination.ProducerBoard, model.state.value.destination)
        assertEquals(wednesday, model.state.value.producer.weekStarting)
        assertEquals(listOf(wednesday), repository.crewWeeks)
    }

    @Test
    fun `the first row opens, and the crew reads by department then name`() = runTest(dispatcher) {
        val repository = FakePayrollRepository(crewRows = listOf(row("t2", "spk"), row("t1", "cam")))
        val model = model(repository, "/film-tools/payroll/producer-board?entry=tool")
        val producerState = model.state.value.producer
        assertEquals(listOf("t1", "t2"), producerState.crew.map { it.id })
        assertEquals("t1", producerState.selectedId)
        assertNotNull(producerState.timecard)
    }

    /** No future weeks: there are no timecards there to read. */
    @Test
    fun `the board never steps past the current week`() = runTest(dispatcher) {
        val repository = FakePayrollRepository()
        val model = model(repository, "/film-tools/payroll/producer-board?entry=tool")
        model.onEvent(ProducerBoardEvent.ShiftWeek(1))
        advanceUntilIdle()
        assertEquals(currentMonday, model.state.value.producer.weekStarting)
        model.onEvent(ProducerBoardEvent.ShiftWeek(-1))
        advanceUntilIdle()
        assertEquals(currentMonday - PayPeriod.WEEK_MILLIS, model.state.value.producer.weekStarting)
        // And forward again is allowed once there is somewhere to go.
        model.onEvent(ProducerBoardEvent.ShiftWeek(1))
        advanceUntilIdle()
        assertEquals(currentMonday, model.state.value.producer.weekStarting)
    }

    /** Selecting a row nobody is showing does nothing rather than opening it. */
    @Test
    fun `a row that is not in the week cannot be selected`() = runTest(dispatcher) {
        val repository = FakePayrollRepository(crewRows = listOf(row("t1", "cam")))
        val model = model(repository, "/film-tools/payroll/producer-board?entry=tool")
        model.onEvent(ProducerBoardEvent.Select("nope"))
        advanceUntilIdle()
        assertEquals("t1", model.state.value.producer.selectedId)
    }

    // -- Production Report Payroll --------------------------------------------------------

    /** The roster is the crew with a DEAL — not the crew with a timecard. */
    @Test
    fun `the estimate board rosters crew with a deal, including those with no timecard`() = runTest(dispatcher) {
        val repository = FakePayrollRepository(crewRows = listOf(row("t1", "cam")))
        val model = model(repository, "/film-tools/payroll/production-report?entry=tool")
        val estimate = model.state.value.estimate
        assertEquals(listOf("cam", "spk"), estimate.crew.map { it.userId })
        assertEquals("cam", estimate.selectedUserId)
        // The one with a timecard shows its real status; the one without says so.
        assertEquals(EstimateStatus.Real(TimecardStatus.Submitted), estimate.rowStatus("cam"))
        assertEquals(EstimateStatus.None, estimate.rowStatus("spk"))
    }

    @Test
    fun `arming auto-fill says so on every crew member it has not reached yet`() = runTest(dispatcher) {
        val repository = FakePayrollRepository()
        val model = model(repository, "/film-tools/payroll/production-report?entry=tool")
        model.onEvent(ProductionReportEvent.ToggleAutoFill)
        advanceUntilIdle()
        assertTrue(model.state.value.estimate.autoFill)
        assertEquals(EstimateStatus.Auto, model.state.value.estimate.rowStatus("spk"))
    }

    /** A week that has been filled is money; it reads differently from a promise. */
    @Test
    fun `a filled week is priced by the engine and overlays only the blank days`() = runTest(dispatcher) {
        val real = PayrollTimecard(
            id = "t1",
            userId = "cam",
            status = TimecardStatus.Draft,
            weekStarting = currentMonday,
            currency = "GBP",
            basicPay = 500.0,
            days = listOf(TimecardDay(date = currentMonday, dayType = "SWD")),
        )
        val repository = FakePayrollRepository(crewRows = listOf(row("t1", "cam", TimecardStatus.Draft)))
        repository.run = listOf(real)
        val model = model(
            repository,
            "/film-tools/payroll/production-report?entry=tool",
            reports = FakeReports(currentMonday),
            deals = FakeDeals,
            estimator = FakeEstimator,
        )
        model.onEvent(ProductionReportEvent.FillWeek)
        advanceUntilIdle()
        val estimate = model.state.value.estimate
        // Monday is a real entry, so it keeps it; the other six are filled.
        assertNull(estimate.selectedDays[0])
        assertEquals(6, estimate.selectedDays.count { it != null })
        // The pill keeps saying what the TIMECARD says: an estimate laid over
        // a real week is a reading aid, not a change to its standing.
        assertEquals(EstimateStatus.Real(TimecardStatus.Draft), estimate.rowStatus("cam"))

        val shown = model.state.value.estimateTimecard()
        assertNotNull(shown)
        assertEquals("SWD", shown.days[0].dayType)
        // The real basic stands and the estimate's is added to it.
        assertEquals(500.0 + 6 * 100.0, shown.basicPay)
    }

    /** A settled week is not estimated over — the web's `FILL_LOCKED_STATUSES`. */
    @Test
    fun `a locked week is never filled, and its rates are never loaded`() = runTest(dispatcher) {
        val locked = PayrollTimecard(
            id = "t1",
            userId = "cam",
            status = TimecardStatus.Locked,
            weekStarting = currentMonday,
        )
        val repository = FakePayrollRepository(crewRows = listOf(row("t1", "cam", TimecardStatus.Locked)))
        repository.run = listOf(locked)
        val deals = CountingDeals()
        val model = model(
            repository,
            "/film-tools/payroll/production-report?entry=tool",
            reports = FakeReports(currentMonday),
            deals = deals,
            estimator = FakeEstimator,
        )
        // The deal is still read — the holiday-pay accrual on the KPI strip is
        // derived from it whatever the week's standing — but the FILL's rates
        // are not, so there is nothing to price a day with.
        assertNull(model.state.value.estimate.deal)
        model.onEvent(ProductionReportEvent.FillWeek)
        advanceUntilIdle()
        assertTrue(model.state.value.estimate.selectedDays.all { it == null })
    }

    /** With no pay engine nothing is priced, exactly as the web behaves when its bundle fails. */
    @Test
    fun `no engine means no estimate, and the state says so`() = runTest(dispatcher) {
        val model = model(
            FakePayrollRepository(),
            "/film-tools/payroll/production-report?entry=tool",
            reports = FakeReports(currentMonday),
            deals = FakeDeals,
            estimator = null,
        )
        assertFalse(model.state.value.hasPayEngine)
        assertNull(model.state.value.estimate.deal)
        model.onEvent(ProductionReportEvent.FillWeek)
        advanceUntilIdle()
        assertTrue(model.state.value.estimate.selectedDays.all { it == null })
    }
}

/** Every day of the week published, with the same call and wrap. */
private class FakeReports(private val week: Long) : ProductionReportSource {
    override suspend fun day(dateIso: String, dayMillis: Long, userId: String) =
        ZillitResult.Success(published(dateIso))

    override suspend fun week(weekStarting: Long, userId: String) = ZillitResult.Success(
        (0 until PayPeriod.DAYS_IN_WEEK).associate { offset ->
            val date = PayPeriod.isoDate(week + offset * PayPeriod.DAY_MILLIS)
            date to published(date)
        },
    )

    private fun published(date: String) = ReportDay(
        date = date,
        published = true,
        dayType = "SWD",
        crewCall = "07:00",
        unitWrap = "19:00",
        timeIn = null,
        timeOut = null,
    )
}

private val deal = ActiveDeal(
    userId = "cam",
    agreementIdentifier = "pact",
    currency = "GBP",
    primaryDayType = "SWD",
    document = "{}",
)

private object FakeDeals : ActiveDealSource {
    override suspend fun activeDeal(userId: String) = ZillitResult.Success(deal.copy(userId = userId))
}

private class CountingDeals : ActiveDealSource {
    var calls = 0
    override suspend fun activeDeal(userId: String): ZillitResult<ActiveDeal?> {
        calls += 1
        return ZillitResult.Success(deal.copy(userId = userId))
    }
}

/** Prices every day the same, so the arithmetic under test is the board's, not the engine's. */
private object FakeEstimator : PayrollEstimator {
    override suspend fun load(deal: ActiveDeal) =
        ZillitResult.Success(DealRates(deal = deal, rates = "{}", engineKey = "default@1", holidayPayRate = 0.1077))

    override suspend fun calcDay(
        input: EstimateInput,
        index: Int,
        rates: DealRates,
        previous: EstimateInput?,
    ) = ZillitResult.Success<DayCalc?>(
        DayCalc(basicPay = 100.0, otPay = 25.0, dayGross = 125.0, workedMinutes = 600),
    )
}
