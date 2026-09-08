package com.zillit.desktop.feature.invoices

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.invoices.data.InvoiceSyncEnvelope
import com.zillit.desktop.feature.invoices.data.invoiceRefreshFor
import com.zillit.desktop.feature.invoices.domain.ApprovalStatus
import com.zillit.desktop.feature.invoices.domain.ApprovalTierConfig
import com.zillit.desktop.feature.invoices.domain.BankAccount
import com.zillit.desktop.feature.invoices.domain.DepartmentUpload
import com.zillit.desktop.feature.invoices.domain.EnteredInvoice
import com.zillit.desktop.feature.invoices.domain.HistoryEntry
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceAttachment
import com.zillit.desktop.feature.invoices.domain.InvoiceExtraction
import com.zillit.desktop.feature.invoices.domain.InvoiceFiles
import com.zillit.desktop.feature.invoices.domain.InvoiceQuery
import com.zillit.desktop.feature.invoices.domain.InvoiceRefresh
import com.zillit.desktop.feature.invoices.domain.InvoiceSettings
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.InvoiceViewer
import com.zillit.desktop.feature.invoices.domain.InvoicesRepository
import com.zillit.desktop.feature.invoices.domain.PickedInvoiceFile
import com.zillit.desktop.feature.invoices.domain.Vendor
import com.zillit.desktop.feature.invoices.ui.InvoicesViewModel
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
 * The socket's invoice announcements land as one debounced refetch of the open
 * tab — the web's `ah:invoice:*` pattern — while vendor, tier and settings
 * frames re-pull the reference data instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InvoicesSyncTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    // -- the wire ----------------------------------------------------------

    @Test
    fun `lifecycle events refetch rows, config events refetch reference data`() {
        assertEquals(InvoiceRefresh.Rows, invoiceRefreshFor(SocketEventName("invoice:approved")))
        assertEquals(InvoiceRefresh.Rows, invoiceRefreshFor(SocketEventName("activeRun:cancelled")))
        assertEquals(InvoiceRefresh.Rows, invoiceRefreshFor(SocketEventName("creditNote:dispute_created")))
        assertEquals(InvoiceRefresh.Reference, invoiceRefreshFor(SocketEventName("vendor:updated")))
        assertEquals(InvoiceRefresh.Reference, invoiceRefreshFor(SocketEventName("approval_tier:updated")))
        assertEquals(InvoiceRefresh.Reference, invoiceRefreshFor(SocketEventName("invoice:settings:updated")))
    }

    @Test
    fun `the envelope keeps only the project id and gates on it`() {
        val envelope = Json { ignoreUnknownKeys = true }.decodeFromString(
            InvoiceSyncEnvelope.serializer(),
            """{"project_id":"p1","user_id":"u2","device_id":"d","data":{"invoice_id":"i-9"}}""",
        )
        assertEquals("p1", envelope.projectId)
        assertTrue(envelope.inProject("p1"))
        assertFalse(envelope.inProject("p2"), "another project's frame must drop")
        assertTrue(InvoiceSyncEnvelope().inProject("p1"), "an unnamed frame passes rather than starving the screen")
    }

    // -- the view model ----------------------------------------------------

    private val accountant = InvoiceViewer(
        userId = "acc",
        departmentId = "d-acc",
        departmentIdentifier = "accounts",
        ready = true,
    )

    @Test
    fun `a burst of row events is one refetch, and reference events reload the margin`() = runTest(dispatcher) {
        val events = MutableSharedFlow<InvoiceRefresh>()
        val repository = FakeInvoices(events)
        val model = InvoicesViewModel(
            repository = repository,
            files = NoFiles,
            resolveViewer = { accountant },
            projectCurrency = { "GBP" },
            resolveUser = { null },
            departmentName = { null },
            nowMillis = { 0L },
        )
        model.start()
        runCurrent()
        assertEquals(1, repository.listLoads, "start loads the register once")
        assertEquals(1, repository.tierLoads)

        repeat(3) { events.emit(InvoiceRefresh.Rows) }
        runCurrent()
        assertEquals(1, repository.listLoads, "nothing reloads until the debounce window closes")
        advanceTimeBy(InvoicesViewModel.SYNC_DEBOUNCE_MILLIS)
        runCurrent()
        assertEquals(2, repository.listLoads, "three frames collapse into one refetch")
        assertEquals(1, repository.tierLoads, "row events leave the reference data alone")

        events.emit(InvoiceRefresh.Reference)
        advanceTimeBy(InvoicesViewModel.SYNC_DEBOUNCE_MILLIS)
        runCurrent()
        assertEquals(2, repository.tierLoads)
        assertEquals(2, repository.listLoads, "reference events do not refetch the rows")
    }

    private object NoFiles : InvoiceFiles {
        override suspend fun pick(): List<PickedInvoiceFile> = emptyList()
        override suspend fun upload(file: PickedInvoiceFile): ZillitResult<InvoiceAttachment> =
            ZillitResult.Failure(ZillitError.Unknown("not in this test"))
        override suspend fun fetch(attachment: InvoiceAttachment): ZillitResult<ByteArray> =
            ZillitResult.Failure(ZillitError.Unknown("not in this test"))
        override suspend fun saveAndOpen(name: String, bytes: ByteArray): ZillitResult<Unit> =
            ZillitResult.Success(Unit)
    }

    private class FakeInvoices(
        override val refreshes: Flow<InvoiceRefresh>,
    ) : InvoicesRepository {
        var listLoads = 0
        var tierLoads = 0

        override suspend fun list(query: InvoiceQuery): ZillitResult<List<Invoice>> = counted()
        override suspend fun approvalQueue(): ZillitResult<List<Invoice>> = counted()
        override suspend fun mine(): ZillitResult<List<Invoice>> = counted()

        private fun counted(): ZillitResult<List<Invoice>> {
            listLoads++
            return ZillitResult.Success(emptyList())
        }

        override suspend fun approvalTiers(): ZillitResult<List<ApprovalTierConfig>> {
            tierLoads++
            return ZillitResult.Success(emptyList())
        }

        override suspend fun invoice(id: String): ZillitResult<Invoice> = unsupported()
        override suspend fun createFromUpload(upload: DepartmentUpload): ZillitResult<Invoice?> = unsupported()
        override suspend fun createEntered(entered: EnteredInvoice): ZillitResult<Invoice?> = unsupported()
        override suspend fun patchStatus(
            id: String,
            status: InvoiceStatus,
            approvalStatus: ApprovalStatus,
        ): ZillitResult<Invoice?> = unsupported()
        override suspend fun delete(id: String): ZillitResult<Unit> = unsupported()
        override suspend fun approve(id: String, tierNumber: Int, totalTiers: Int): ZillitResult<Invoice?> =
            unsupported()
        override suspend fun reject(id: String, reason: String): ZillitResult<Invoice?> = unsupported()
        override suspend fun chase(id: String): ZillitResult<Unit> = unsupported()
        override suspend fun history(id: String): ZillitResult<List<HistoryEntry>> = ZillitResult.Success(emptyList())
        override suspend fun extract(attachment: InvoiceAttachment): ZillitResult<InvoiceExtraction> = unsupported()
        override suspend fun settings(): ZillitResult<InvoiceSettings> = ZillitResult.Success(InvoiceSettings())
        override suspend fun vendors(): ZillitResult<List<Vendor>> = ZillitResult.Success(emptyList())
        override suspend fun bankAccounts(): ZillitResult<List<BankAccount>> = ZillitResult.Success(emptyList())

        private fun <T> unsupported(): ZillitResult<T> = ZillitResult.Failure(ZillitError.Unknown("not in this test"))
    }
}
