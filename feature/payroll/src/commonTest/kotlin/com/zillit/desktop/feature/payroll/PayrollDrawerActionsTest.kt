package com.zillit.desktop.feature.payroll

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.payroll.domain.ExportFormat
import com.zillit.desktop.feature.payroll.domain.PayLine
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.PayrollAttachmentLinks
import com.zillit.desktop.feature.payroll.domain.PayrollDocuments
import com.zillit.desktop.feature.payroll.domain.PayrollExportFile
import com.zillit.desktop.feature.payroll.domain.PayrollFiles
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import com.zillit.desktop.feature.payroll.domain.PayrollViewer
import com.zillit.desktop.feature.payroll.domain.RunAction
import com.zillit.desktop.feature.payroll.domain.TimecardAttachment
import com.zillit.desktop.feature.payroll.domain.TimecardDay
import com.zillit.desktop.feature.payroll.domain.TimecardNote
import com.zillit.desktop.feature.payroll.domain.TimecardStatus
import com.zillit.desktop.feature.payroll.ui.PayrollEffect
import com.zillit.desktop.feature.payroll.ui.PayrollEvent
import com.zillit.desktop.feature.payroll.ui.PayrollViewModel
import com.zillit.desktop.feature.payroll.ui.RunEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
 * What the drawer's footer and the Export CSV button do: the notes, the
 * read-only week, the invoice, and the confirm that stands in front of an
 * export when the run still has weeks to approve or lock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PayrollDrawerActionsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Wednesday 2026-05-06 12:00 UTC. */
    private val now = 1_778_068_800_000L
    private val lastMonday = PayPeriod.startOf(now, PayPeriod.MONDAY) - PayPeriod.WEEK_MILLIS

    private fun card(id: String, status: TimecardStatus, userId: String = "user-$id") =
        PayrollTimecard(id = id, userId = userId, status = status, weekStarting = lastMonday, currency = "GBP")

    private class Documents : PayrollDocuments {
        val summaries = mutableListOf<ExportFormat>()
        override suspend fun payslip(weekStarting: Long, userId: String) = ZillitResult.Success(ByteArray(0))
        override suspend fun runSummary(weekStarting: Long, format: ExportFormat): ZillitResult<PayrollExportFile> {
            summaries += format
            return ZillitResult.Success(PayrollExportFile(ByteArray(1), format.wire))
        }
        override suspend fun weekWorkbook(weekStarting: Long) = ZillitResult.Success(ByteArray(0))
        override suspend fun outstandingWorkbook() = ZillitResult.Success(ByteArray(0))
    }

    private class Harness(
        val model: PayrollViewModel,
        val effects: MutableList<PayrollEffect>,
        val documents: Documents,
        val saved: MutableList<String>,
    )

    private fun TestScope.harness(
        repository: FakePayrollRepository,
        viewer: PayrollViewer = controller,
        route: String = "/film-tools/payroll/run",
        links: PayrollAttachmentLinks? = null,
    ): Harness {
        val documents = Documents()
        val saved = mutableListOf<String>()
        val model = PayrollViewModel(
            repository = repository,
            viewer = { viewer },
            now = { now },
            documents = documents,
            files = PayrollFiles { name, _ -> saved += name; ZillitResult.Success(Unit) },
            attachmentLinks = links,
        )
        val effects = mutableListOf<PayrollEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.effects.collect { effects += it } }
        model.start()
        model.onEvent(PayrollEvent.Route(route))
        advanceUntilIdle()
        return Harness(model, effects, documents, saved)
    }

    // -- Export CSV ---------------------------------------------------------------------

    @Test
    fun `export csv with weeks to approve asks first, and approve and export moves them then downloads`() =
        runTest(dispatcher) {
            val repository = FakePayrollRepository(run = listOf(card("a", TimecardStatus.Approved)))
            val h = harness(repository)
            h.model.onEvent(RunEvent.Export(ExportFormat.Csv))
            val confirm = assertNotNull(h.model.state.value.run.confirm)
            assertEquals(RunAction.FinalApproveAndLock, confirm.action, "a controller approves and locks")
            assertTrue(confirm.thenExport)
            assertTrue(confirm.runScope, "the workbook is the whole run's, not a ticked selection")
            assertEquals(listOf("a"), confirm.ids)
            assertTrue(h.documents.summaries.isEmpty(), "nothing downloads until the statuses have moved")

            h.model.onEvent(RunEvent.Confirm)
            advanceUntilIdle()
            assertEquals(listOf("finalApprove:a", "lock:a"), repository.calls)
            assertEquals(listOf(ExportFormat.Csv), h.documents.summaries)
            assertEquals(1, h.saved.size)
            assertNull(h.model.state.value.run.confirm)
        }

    @Test
    fun `export only downloads as the statuses stand and moves nothing`() = runTest(dispatcher) {
        val repository = FakePayrollRepository(run = listOf(card("a", TimecardStatus.Approved)))
        val h = harness(repository)
        h.model.onEvent(RunEvent.Export(ExportFormat.Csv))
        h.model.onEvent(RunEvent.ExportOnly)
        advanceUntilIdle()
        assertTrue(repository.calls.isEmpty())
        assertEquals(listOf(ExportFormat.Csv), h.documents.summaries)
        assertNull(h.model.state.value.run.confirm)
    }

    @Test
    fun `export only does nothing when there is no export confirm open`() = runTest(dispatcher) {
        val h = harness(FakePayrollRepository(run = listOf(card("a", TimecardStatus.Approved))))
        h.model.onEvent(RunEvent.ExportOnly)
        advanceUntilIdle()
        assertTrue(h.documents.summaries.isEmpty())
    }

    @Test
    fun `export csv goes straight to the file when nothing is actionable, and so does the summary`() =
        runTest(dispatcher) {
            val paid = FakePayrollRepository(run = listOf(card("p", TimecardStatus.Paid)))
            val h = harness(paid)
            h.model.onEvent(RunEvent.Export(ExportFormat.Csv))
            advanceUntilIdle()
            assertNull(h.model.state.value.run.confirm)
            assertEquals(listOf(ExportFormat.Csv), h.documents.summaries)

            val open = harness(FakePayrollRepository(run = listOf(card("a", TimecardStatus.Approved))))
            open.model.onEvent(RunEvent.Export(ExportFormat.Pdf))
            advanceUntilIdle()
            assertNull(open.model.state.value.run.confirm, "the PDF summary is never held up")
            assertEquals(listOf(ExportFormat.Pdf), open.documents.summaries)
        }

    @Test
    fun `the confirm offers only what the viewer's roles allow`() = runTest(dispatcher) {
        val rows = listOf(card("a", TimecardStatus.Approved), card("f", TimecardStatus.FinalApproved))

        // An approver who cannot lock is offered a plain approval, never approve-and-lock.
        // The approvers list is the server's word, so the metadata names this one.
        val approving = FakePayrollRepository(run = rows)
            .also { it.metadata = it.metadata.copy(isFinalApprover = true) }
        val approverView = harness(approving, viewer = accountant)
        approverView.model.onEvent(RunEvent.Export(ExportFormat.Csv))
        val approval = assertNotNull(approverView.model.state.value.run.confirm)
        assertEquals(RunAction.FinalApprove, approval.action)
        assertEquals(listOf("a"), approval.ids)

        // The payroll accountant locks what is already accountant-approved.
        val locker = harness(FakePayrollRepository(run = rows), viewer = payrollAccountant)
        locker.model.onEvent(RunEvent.Export(ExportFormat.Csv))
        val lock = assertNotNull(locker.model.state.value.run.confirm)
        assertEquals(RunAction.Lock, lock.action)
        assertEquals(listOf("f"), lock.ids)

        // An accountant who is neither is offered nothing, and the file just downloads.
        val neither = harness(FakePayrollRepository(run = rows), viewer = accountant)
        neither.model.onEvent(RunEvent.Export(ExportFormat.Csv))
        advanceUntilIdle()
        assertNull(neither.model.state.value.run.confirm)
        assertEquals(listOf(ExportFormat.Csv), neither.documents.summaries)
    }

    // -- notes, the week and the invoice ------------------------------------------------

    @Test
    fun `notes open for an accountant on someone else's card, and never on their own`() = runTest(dispatcher) {
        val h = harness(FakePayrollRepository())
        val theirs = card("a", TimecardStatus.Approved).copy(notes = listOf(TimecardNote("Left early", 1L)))
        h.model.onEvent(PayrollEvent.OpenNotes(theirs, "Crew a"))
        val view = assertNotNull(h.model.state.value.notesView)
        assertEquals("Crew a", view.crewName)
        assertEquals(listOf("Left early"), view.notes.map { it.text })
        h.model.onEvent(PayrollEvent.CloseNotes)
        assertNull(h.model.state.value.notesView)

        h.model.onEvent(PayrollEvent.OpenNotes(theirs.copy(userId = controller.userId), "Me"))
        assertNull(h.model.state.value.notesView, "an accountant reading their own week adds notes from their timecard")

        val producer = harness(FakePayrollRepository(), viewer = crew, route = "/film-tools/payroll")
        producer.model.onEvent(PayrollEvent.OpenNotes(theirs, "Crew a"))
        assertNull(producer.model.state.value.notesView)
    }

    @Test
    fun `view time card opens a full week as it stands, and fetches the full week for a slim row`() =
        runTest(dispatcher) {
            val full = card("a", TimecardStatus.Approved).copy(
                days = listOf(
                    TimecardDay(date = lastMonday, dayType = "SWD", rates = listOf(PayLine(identifier = "basic"))),
                ),
            )
            val h = harness(FakePayrollRepository(run = listOf(full)))
            h.model.onEvent(PayrollEvent.OpenTimecardView(full))
            assertFalse(assertNotNull(h.model.state.value.timecardView).loading, "a drawer's week needs no fetch")

            h.model.onEvent(PayrollEvent.CloseTimecardView)
            assertNull(h.model.state.value.timecardView)

            val slim = full.copy(days = emptyList())
            h.model.onEvent(PayrollEvent.OpenTimecardView(slim))
            assertTrue(assertNotNull(h.model.state.value.timecardView).loading)
            advanceUntilIdle()
            val settled = assertNotNull(h.model.state.value.timecardView)
            assertFalse(settled.loading)
            assertEquals(1, settled.timecard.days.size, "the full document replaced the slim row")
        }

    @Test
    fun `view time card is the accountants' alone, and a closed viewer ignores a late fetch`() = runTest(dispatcher) {
        val producer = harness(FakePayrollRepository(), viewer = crew, route = "/film-tools/payroll")
        producer.model.onEvent(PayrollEvent.OpenTimecardView(card("a", TimecardStatus.Approved)))
        assertNull(producer.model.state.value.timecardView)

        val h = harness(FakePayrollRepository(run = listOf(card("a", TimecardStatus.Approved))))
        h.model.onEvent(PayrollEvent.OpenTimecardView(card("a", TimecardStatus.Approved)))
        h.model.onEvent(PayrollEvent.CloseTimecardView)
        advanceUntilIdle()
        assertNull(h.model.state.value.timecardView, "the fetch must not reopen a closed modal")
    }

    @Test
    fun `an invoice with a link opens it, a stored one is presigned first`() = runTest(dispatcher) {
        val seen = mutableListOf<TimecardAttachment>()
        val h = harness(FakePayrollRepository(), links = PayrollAttachmentLinks { seen += it; "https://s3/signed" })

        h.model.onEvent(PayrollEvent.OpenInvoice(TimecardAttachment(name = "a.pdf", url = "https://x/a.pdf")))
        advanceUntilIdle()
        assertEquals(PayrollEffect.OpenUrl("https://x/a.pdf"), h.effects.last())
        assertTrue(seen.isEmpty(), "a link that opens as it stands is never presigned")

        val stored = TimecardAttachment("b.pdf", null, media = "u/b.pdf", bucket = "bk", region = "eu-west-2")
        h.model.onEvent(PayrollEvent.OpenInvoice(stored))
        advanceUntilIdle()
        assertEquals(listOf(stored), seen)
        assertEquals(PayrollEffect.OpenUrl("https://s3/signed"), h.effects.last())
    }

    @Test
    fun `an invoice that cannot be reached says so rather than opening nothing`() = runTest(dispatcher) {
        val stored = TimecardAttachment("b.pdf", null, media = "u/b.pdf", bucket = "bk", region = "eu-west-2")

        // No storage wired.
        val bare = harness(FakePayrollRepository())
        bare.model.onEvent(PayrollEvent.OpenInvoice(stored))
        advanceUntilIdle()
        assertTrue(bare.effects.last() is PayrollEffect.Failed)

        // Storage that cannot sign, or throws.
        listOf<suspend (TimecardAttachment) -> String?>({ null }, { error("no credentials") }).forEach { link ->
            val h = harness(FakePayrollRepository(), links = PayrollAttachmentLinks { link(it) })
            h.model.onEvent(PayrollEvent.OpenInvoice(stored))
            advanceUntilIdle()
            assertTrue(h.effects.last() is PayrollEffect.Failed)
            assertTrue(h.effects.none { it is PayrollEffect.OpenUrl })
        }

        // Nothing to open at all.
        val empty = harness(FakePayrollRepository(), links = PayrollAttachmentLinks { "https://s3/x" })
        empty.model.onEvent(PayrollEvent.OpenInvoice(TimecardAttachment(name = "only a name", url = null)))
        advanceUntilIdle()
        assertTrue(empty.effects.last() is PayrollEffect.Failed)
    }
}
