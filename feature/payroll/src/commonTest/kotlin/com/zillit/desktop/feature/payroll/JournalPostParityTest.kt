package com.zillit.desktop.feature.payroll

import com.zillit.desktop.feature.payroll.domain.Journal
import com.zillit.desktop.feature.payroll.domain.JournalCategory
import com.zillit.desktop.feature.payroll.domain.JournalEdit
import com.zillit.desktop.feature.payroll.domain.JournalRow
import com.zillit.desktop.feature.payroll.domain.PayLine
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.PayrollAccount
import com.zillit.desktop.feature.payroll.domain.PayrollMetadata
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import com.zillit.desktop.feature.payroll.domain.TimecardDay
import com.zillit.desktop.feature.payroll.domain.TimecardStatus
import com.zillit.desktop.feature.payroll.ui.JournalEvent
import com.zillit.desktop.feature.payroll.ui.PayrollEvent
import com.zillit.desktop.feature.payroll.ui.PayrollViewModel
import com.zillit.desktop.feature.payroll.ui.journalRows
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
 * The Post to Ledger dialog and the journal's dates, against the web's
 * `PostToLedgerModal` / `PayrollRunModule`: the counts it states, a default
 * date that is optional, and a line date that has to be a real day.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JournalPostParityTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Wednesday 2026-05-06 12:00 UTC. */
    private val now = 1_778_068_800_000L
    private val lastMonday = PayPeriod.startOf(now, PayPeriod.MONDAY) - PayPeriod.WEEK_MILLIS

    private fun paidWeek(id: String, status: TimecardStatus = TimecardStatus.Locked) = PayrollTimecard(
        id = id,
        userId = "user-$id",
        status = status,
        weekStarting = lastMonday,
        currency = "GBP",
        days = listOf(
            TimecardDay(
                date = lastMonday,
                dayType = "SWD",
                rates = listOf(
                    PayLine(identifier = "basic", label = "Basic", rateAmount = 400.0),
                    PayLine(identifier = "overtime", label = "OT", rateAmount = 60.0),
                ),
            ),
        ),
    )

    private fun TestScope.model(
        repository: FakePayrollRepository,
        clock: () -> Long = { now },
    ): PayrollViewModel {
        val model = PayrollViewModel(repository, { controller }, now = clock)
        model.start()
        model.onEvent(PayrollEvent.Route("/film-tools/payroll/run"))
        advanceUntilIdle()
        return model
    }

    /** Codes and dates every row on screen, as a post needs. */
    private fun TestScope.codeEverything(model: PayrollViewModel) {
        model.onEvent(JournalEvent.HeaderDate("2026-05-04"))
        model.state.value.journalRows().filterNot { it.codeLocked }.forEach {
            model.onEvent(JournalEvent.Code(it.id, "5000"))
        }
        advanceUntilIdle()
    }

    @Test
    fun `the dialog counts the run's account lines and states only the debit side`() = runTest(dispatcher) {
        val repository = FakePayrollRepository(
            run = listOf(paidWeek("a"), paidWeek("b", TimecardStatus.Paid)),
            metadata = PayrollMetadata(payrollAccounts = listOf(PayrollAccount("2100", "Net pay"))),
        )
        val model = model(repository)
        codeEverything(model)
        val rows = model.state.value.journalRows()
        val accountRows = rows.count { it.timecardId == null }
        assertEquals(1, accountRows)

        model.onEvent(JournalEvent.Post)
        advanceUntilIdle()

        val dialog = assertNotNull(model.state.value.run.journal.post)
        assertEquals(2, dialog.timecardIds.size)
        // Four timecard lines (basic and OT, twice) plus the account line the run posts with them.
        assertEquals(rows.size, dialog.lineCount)
        assertEquals(accountRows + 4, dialog.lineCount)
        // 2 x (400 + 60): the credit row is the same money from the other side and is not added.
        assertEquals(920.0, dialog.gross)
    }

    @Test
    fun `a post without a default date is sent with none, and a typed one must be a real day`() =
        runTest(dispatcher) {
            val repository = FakePayrollRepository(run = listOf(paidWeek("a")))
            val model = model(repository)
            codeEverything(model)
            model.onEvent(JournalEvent.Post)
            advanceUntilIdle()

            model.onEvent(JournalEvent.PostDate("2026-13-40"))
            model.onEvent(JournalEvent.ConfirmPost)
            advanceUntilIdle()
            assertTrue(repository.submissions.isEmpty(), "a date that is not a day is refused")
            assertNotNull(model.state.value.run.journal.post?.error)

            model.onEvent(JournalEvent.PostDate(""))
            model.onEvent(JournalEvent.ConfirmPost)
            advanceUntilIdle()

            val sent = repository.submissions.single()
            assertTrue(sent.post)
            assertNull(sent.effectiveDate, "the default date is optional; each line carries its own")
        }

    @Test
    fun `a line date that is not a day does not count as filled in`() {
        val row = JournalRow(
            id = "r",
            timecardId = "a",
            companyId = null,
            src = "rates_ots",
            groupKey = "basic",
            identifier = "basic",
            label = "Basic",
            category = JournalCategory.Basic,
            description = "Basic",
            descriptionOverride = "",
            amount = 400.0,
            code = "5000",
            effectiveDate = null,
        )
        assertFalse(Journal.hasDate(row, null))
        assertFalse(Journal.hasDate(row, JournalEdit(effectiveDate = "2026-5-1")))
        assertFalse(Journal.hasDate(row, JournalEdit(effectiveDate = "2026-02-30")))
        assertTrue(Journal.hasDate(row, JournalEdit(effectiveDate = "2026-05-04")))

        val missing = Journal.incomplete(listOf(row), mapOf("r" to JournalEdit(effectiveDate = "2026-1")), setOf("a"))
        assertEquals(1, missing.size)
        assertTrue(missing.single().missingDate)
        assertFalse(missing.single().missingCode)
        val filled = mapOf("r" to JournalEdit(effectiveDate = "2026-05-04"))
        assertTrue(Journal.incomplete(listOf(row), filled, setOf("a")).isEmpty())
    }

    @Test
    fun `a window left open past midnight moves today and the current week on`() = runTest(dispatcher) {
        var clock = now
        val model = model(FakePayrollRepository(), clock = { clock })
        val before = model.state.value
        assertEquals("2026-05-06", before.todayIso)

        // Thursday 2026-05-14 00:05 UTC: next week, and no event has changed anything yet.
        clock = now + 7 * PayPeriod.DAY_MILLIS + 12 * 60 * 60_000L + 5 * 60_000L
        model.onEvent(PayrollEvent.ClearNotice)
        advanceUntilIdle()

        val after = model.state.value
        assertEquals("2026-05-14", after.todayIso)
        assertEquals(before.currentWeek + PayPeriod.WEEK_MILLIS, after.currentWeek)
    }
}
