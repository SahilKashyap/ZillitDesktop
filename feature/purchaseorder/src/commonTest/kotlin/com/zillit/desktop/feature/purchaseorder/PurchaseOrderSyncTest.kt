package com.zillit.desktop.feature.purchaseorder

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.purchaseorder.data.PoSyncEnvelope
import com.zillit.desktop.feature.purchaseorder.data.PO_ORDER_SYNC_EVENTS
import com.zillit.desktop.feature.purchaseorder.data.PO_SYNC_EVENTS
import com.zillit.desktop.feature.purchaseorder.data.PO_VENDOR_SYNC_EVENTS
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
        assertEquals(PoRefresh.Orders, poRefreshFor(SocketEventName("po:created"), module = null))
        assertEquals(PoRefresh.Orders, poRefreshFor(SocketEventName("purchase-order:accept"), module = null))
        assertEquals(
            PoRefresh.Orders,
            poRefreshFor(SocketEventName("purchase-order:approval-level:delete"), module = null),
        )
        assertEquals(PoRefresh.Vendors, poRefreshFor(SocketEventName("vendor:updated"), module = null))
        assertEquals(PoRefresh.Vendors, poRefreshFor(SocketEventName("purchase-order:supplier:update"), module = null))
    }

    /**
     * The backend has not settled on one spelling for a removed approval
     * level, so both are subscribed — the web bridges each and two of its
     * components consume each alias. An event that never fires costs
     * nothing; a missed one leaves a stale approval chain on screen.
     */
    @Test
    fun `both spellings of a removed approval level are subscribed`() {
        val names = PO_ORDER_SYNC_EVENTS.map { it.value }

        assertTrue("purchase-order:approval-level:delete" in names)
        assertTrue("purchase-order:approval-level:removed" in names)
    }

    /**
     * `supplier:sent` is named for the supplier but reports that the *order*
     * was sent — it moves one out of the draft list — so it reloads the
     * orders, not the vendor picker.
     */
    @Test
    fun `sending an order to its supplier reloads the orders`() {
        assertEquals(
            PoRefresh.Orders,
            poRefreshFor(SocketEventName("purchase-order:supplier:sent"), module = null),
        )
    }

    @Test
    fun `no event is subscribed twice or routed to both refreshes`() {
        val names = PO_SYNC_EVENTS.map { it.value }

        assertEquals(names.size, names.toSet().size, "duplicate subscription")
        assertTrue(
            PO_ORDER_SYNC_EVENTS.none { it in PO_VENDOR_SYNC_EVENTS },
            "an event routed to both would refetch twice",
        )
    }

    @Test
    fun `the envelope keeps only the project id and gates on it`() {
        val envelope = Json { ignoreUnknownKeys = true }.decodeFromString(
            PoSyncEnvelope.serializer(),
            """{"project_id":"p1","user_id":"u9","device_id":"d1","data":{"po_id":"po-7"}}""",
        )
        assertEquals("p1", envelope.projectId)
        assertTrue(envelope.inProject("p1"))
        assertFalse(envelope.inProject("p2"), "another project's frame must drop")
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
