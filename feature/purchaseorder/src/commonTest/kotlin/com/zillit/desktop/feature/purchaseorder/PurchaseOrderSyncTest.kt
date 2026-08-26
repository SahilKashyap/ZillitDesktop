package com.zillit.desktop.feature.purchaseorder

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.purchaseorder.data.PoSyncEnvelope
import com.zillit.desktop.feature.purchaseorder.data.poRefreshFor
import com.zillit.desktop.feature.purchaseorder.domain.NewPurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PoHistoryEntry
import com.zillit.desktop.feature.purchaseorder.domain.PoRefresh
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.domain.PoViewer
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrderRepository
import com.zillit.desktop.feature.purchaseorder.domain.Vendor
import com.zillit.desktop.feature.purchaseorder.ui.PurchaseOrderViewModel
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
 * The socket's purchase-order announcements land as one debounced reload of
 * whatever page is open — the web's `ah:po:*` refetch pattern — and vendor
 * events refill the picker instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseOrderSyncTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    // -- the wire ----------------------------------------------------------

    @Test
    fun `order events reload the lists, vendor events reload the picker`() {
        assertEquals(PoRefresh.Orders, poRefreshFor(SocketEventName("po:created")))
        assertEquals(PoRefresh.Orders, poRefreshFor(SocketEventName("purchase-order:accept")))
        assertEquals(PoRefresh.Orders, poRefreshFor(SocketEventName("purchase-order:approval-level:delete")))
        assertEquals(PoRefresh.Vendors, poRefreshFor(SocketEventName("vendor:updated")))
        assertEquals(PoRefresh.Vendors, poRefreshFor(SocketEventName("purchase-order:supplier:update")))
    }

    @Test
    fun `the envelope keeps only the project id and gates on it`() {
        val envelope = Json { ignoreUnknownKeys = true }.decodeFromString(
            PoSyncEnvelope.serializer(),
            """{"project_id":"p1","user_id":"u9","device_id":"d1","data":{"po_id":"po-7"}}""",
        )
        assertEquals("p1", envelope.projectId)
        assertTrue(envelope.inProject("p1"))
        assertFalse(envelope.inProject("p2"), "another production's frame must drop")
        assertTrue(envelope.inProject(null), "an unnamed local project cannot gate")
        assertTrue(PoSyncEnvelope().inProject("p1"), "a frame with no project id passes — the classic family")
    }

    // -- the view model ----------------------------------------------------

    private val crew = PoViewer(userId = "user-1", departmentIdentifier = "camera", designationIdentifier = null)

    @Test
    fun `a burst of order events is one reload, and a vendor event refills the picker`() = runTest(dispatcher) {
        val events = MutableSharedFlow<PoRefresh>()
        val repository = FakeOrders(events)
        val model = PurchaseOrderViewModel(repository, { crew }, offline = null)
        model.start()
        runCurrent()
        assertEquals(1, repository.listLoads, "start loads the landing page once")
        assertEquals(1, repository.vendorLoads)

        repeat(3) { events.emit(PoRefresh.Orders) }
        runCurrent()
        assertEquals(1, repository.listLoads, "nothing reloads until the debounce window closes")
        advanceTimeBy(PurchaseOrderViewModel.SYNC_DEBOUNCE_MILLIS)
        runCurrent()
        assertEquals(2, repository.listLoads, "three frames collapse into one reload")
        assertEquals(1, repository.vendorLoads, "order events leave the vendor picker alone")

        events.emit(PoRefresh.Vendors)
        advanceTimeBy(PurchaseOrderViewModel.SYNC_DEBOUNCE_MILLIS)
        runCurrent()
        assertEquals(2, repository.vendorLoads)
        assertEquals(2, repository.listLoads)
    }

    private class FakeOrders(
        override val refreshes: Flow<PoRefresh>,
    ) : PurchaseOrderRepository {
        var listLoads = 0
        var vendorLoads = 0

        override suspend fun orders(status: PoStatus?): ZillitResult<List<PurchaseOrder>> = counted()
        override suspend fun approvalQueue(): ZillitResult<List<PurchaseOrder>> = counted()
        override suspend fun myOrders(): ZillitResult<List<PurchaseOrder>> = counted()

        private fun counted(): ZillitResult<List<PurchaseOrder>> {
            listLoads++
            return ZillitResult.Success(emptyList())
        }

        override suspend fun vendors(): ZillitResult<List<Vendor>> {
            vendorLoads++
            return ZillitResult.Success(emptyList())
        }

        override suspend fun order(id: String): ZillitResult<PurchaseOrder> = unsupported()
        override suspend fun history(id: String): ZillitResult<List<PoHistoryEntry>> =
            ZillitResult.Success(emptyList())
        override suspend fun create(order: NewPurchaseOrder): ZillitResult<Unit> = unsupported()
        override suspend fun update(id: String, order: NewPurchaseOrder): ZillitResult<Unit> = unsupported()
        override suspend fun delete(id: String): ZillitResult<Unit> = unsupported()
        override suspend fun approve(id: String, note: String?): ZillitResult<Unit> = unsupported()
        override suspend fun reject(id: String, reason: String): ZillitResult<Unit> = unsupported()
        override suspend fun post(id: String, note: String?): ZillitResult<Unit> = unsupported()
        override suspend fun close(id: String, note: String?): ZillitResult<Unit> = unsupported()
        override suspend fun closeAll(ids: List<String>, effectiveDate: Long?): ZillitResult<Unit> = unsupported()

        private fun <T> unsupported(): ZillitResult<T> = ZillitResult.Failure(ZillitError.Unknown("not in this test"))
    }
}
