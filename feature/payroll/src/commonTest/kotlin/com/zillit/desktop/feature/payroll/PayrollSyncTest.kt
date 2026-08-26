package com.zillit.desktop.feature.payroll

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.payroll.data.PayrollSyncEnvelope
import com.zillit.desktop.feature.payroll.domain.BankAccount
import com.zillit.desktop.feature.payroll.domain.NominalAllocation
import com.zillit.desktop.feature.payroll.domain.PayrollRepository
import com.zillit.desktop.feature.payroll.domain.PayrollViewer
import com.zillit.desktop.feature.payroll.domain.PayrollWeek
import com.zillit.desktop.feature.payroll.domain.Payslip
import com.zillit.desktop.feature.payroll.domain.PostOutcome
import com.zillit.desktop.feature.payroll.ui.PayrollViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The socket's payroll-relevant timecard announcements land as one debounced
 * reload of the week on screen — the web's `ah:payroll:list` refetch pattern.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PayrollSyncTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Monday 18 May 2026, midnight UTC. */
    private val monday = 1_779_062_400_000L

    private val accountant = PayrollViewer(
        userId = "user-1",
        departmentIdentifier = "accounts_department_label",
        designationIdentifier = "production accountant",
    )

    @Test
    fun `the envelope keeps only the project id and gates on it`() {
        val envelope = Json { ignoreUnknownKeys = true }.decodeFromString(
            PayrollSyncEnvelope.serializer(),
            """{"project_id":"p1","user_id":"u2","data":{"_id":"tc-4","user_id":"crew-9"}}""",
        )
        assertEquals("p1", envelope.projectId)
        assertTrue(envelope.inProject("p1"))
        assertFalse(envelope.inProject("p2"), "another production's frame must drop")
        assertTrue(PayrollSyncEnvelope().inProject("p1"), "an unnamed frame passes rather than starving the screen")
    }

    @Test
    fun `a burst of payroll events is one reload of the week on screen`() = runTest(dispatcher) {
        val events = MutableSharedFlow<Unit>()
        val repository = FakePayroll(events, monday)
        val model = PayrollViewModel(repository, { accountant }, now = { monday })
        model.start()
        runCurrent()
        assertEquals(1, repository.currentWeekLoads, "start asks the server for its current week")
        assertEquals(monday, model.state.value.weekStarting)

        repeat(3) { events.emit(Unit) }
        runCurrent()
        assertEquals(0, repository.weekLoads, "nothing reloads until the debounce window closes")
        advanceTimeBy(PayrollViewModel.SYNC_DEBOUNCE_MILLIS)
        runCurrent()
        assertEquals(1, repository.weekLoads, "three frames collapse into one reload of the named week")
        assertEquals(listOf(monday), repository.weeksAsked, "the week on screen is what reloads")
        assertEquals(1, repository.currentWeekLoads, "the server's current week is not re-guessed")
    }

    private class FakePayroll(
        override val refreshes: Flow<Unit>,
        private val monday: Long,
    ) : PayrollRepository {
        var currentWeekLoads = 0
        var weekLoads = 0
        val weeksAsked = mutableListOf<Long>()

        override suspend fun currentWeek(): ZillitResult<PayrollWeek> {
            currentWeekLoads++
            return ZillitResult.Success(PayrollWeek(monday, "GBP", emptyList()))
        }

        override suspend fun week(weekStarting: Long): ZillitResult<PayrollWeek> {
            weekLoads++
            weeksAsked += weekStarting
            return ZillitResult.Success(PayrollWeek(weekStarting, "GBP", emptyList()))
        }

        override suspend fun isFinalApprover(): ZillitResult<Boolean> = ZillitResult.Success(false)
        override suspend fun bankAccounts(): ZillitResult<List<BankAccount>> = ZillitResult.Success(emptyList())
        override suspend fun markPaid(timecardIds: List<String>): ZillitResult<Unit> = unsupported()
        override suspend fun markUnpaid(timecardId: String): ZillitResult<Unit> = unsupported()
        override suspend fun markPosted(
            timecardIds: List<String>,
            bankId: String,
            effectiveDate: Long,
        ): ZillitResult<PostOutcome> = unsupported()
        override suspend fun nominalSplit(
            weekStarting: Long,
            crewId: String,
        ): ZillitResult<List<NominalAllocation>> = ZillitResult.Success(emptyList())
        override suspend fun saveNominalSplit(
            weekStarting: Long,
            crewId: String,
            allocations: List<NominalAllocation>,
        ): ZillitResult<Unit> = unsupported()
        override suspend fun payslip(weekStarting: Long, crewId: String): ZillitResult<Payslip?> =
            ZillitResult.Success(null)

        private fun <T> unsupported(): ZillitResult<T> = ZillitResult.Failure(ZillitError.Unknown("not in this test"))
    }
}
