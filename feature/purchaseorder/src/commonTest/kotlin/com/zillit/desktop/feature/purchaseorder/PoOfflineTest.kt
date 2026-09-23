package com.zillit.desktop.feature.purchaseorder

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.sync.InMemoryDraftStore
import com.zillit.desktop.core.sync.InMemoryOutboxStore
import com.zillit.desktop.core.sync.OfflineSupport
import com.zillit.desktop.core.sync.SyncContext
import com.zillit.desktop.core.sync.SyncEngine
import com.zillit.desktop.core.sync.SyncHandlerRegistry
import com.zillit.desktop.core.sync.SyncOperation
import com.zillit.desktop.core.sync.SyncOutcome
import com.zillit.desktop.core.sync.SyncScope
import com.zillit.desktop.core.sync.SyncState
import com.zillit.desktop.feature.purchaseorder.data.PO_CREATE_KIND
import com.zillit.desktop.feature.purchaseorder.data.PoSyncHandler
import com.zillit.desktop.feature.purchaseorder.data.QueuedPurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.NewPurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PoPostRequest
import com.zillit.desktop.feature.purchaseorder.domain.PoHistoryEntry
import com.zillit.desktop.feature.purchaseorder.domain.PoLine
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.domain.PoViewer
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrderRepository
import com.zillit.desktop.feature.purchaseorder.domain.Vendor
import com.zillit.desktop.feature.purchaseorder.ui.PoDestination
import com.zillit.desktop.core.forms.FormLayout
import com.zillit.desktop.core.forms.FormTemplate
import com.zillit.desktop.feature.purchaseorder.ui.PoFormMode
import com.zillit.desktop.feature.purchaseorder.ui.toRequest
import com.zillit.desktop.feature.purchaseorder.ui.PoFormState
import com.zillit.desktop.feature.purchaseorder.ui.PoEvent
import com.zillit.desktop.feature.purchaseorder.ui.PurchaseOrderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Raising an order with no network: it queues, it shows as waiting, the form
 * is kept, and the handler that sends it later never creates twice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PoOfflineTest {

    private val dispatcher = StandardTestDispatcher()
    private val online = MutableStateFlow(true)
    private val scope = SyncScope("user-1", "p1")
    private val outbox = InMemoryOutboxStore()
    private val drafts = InMemoryDraftStore()
    private var now = 1_000_000L

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.support(handlers: SyncHandlerRegistry = SyncHandlerRegistry()): OfflineSupport {
        val engine = SyncEngine(
            store = outbox,
            handlers = handlers,
            online = online,
            currentScope = { scope },
            scope = backgroundScope,
            nowMillis = { now },
            newId = { "op-${outbox.hashCode()}-${now++}" },
        )
        engine.start()
        return OfflineSupport(engine, drafts, online) { scope }
    }

    private val crew = PoViewer(userId = "user-1", departmentIdentifier = "camera", designationIdentifier = null)

    private fun TestScope.viewModel(repository: FakeOrders, support: OfflineSupport?) =
        PurchaseOrderViewModel(repository, { crew }, support, nowMillis = { now }).also {
            it.start()
            runCurrent()
        }

    private val filled = PoFormState(
        mode = PoFormMode.NewOrder,
        vendorName = "Panavision",
        description = "Camera package",
        currency = "GBP",
        lines = listOf(PoLine(null, "Alexa Mini", 1.0, 2_500.0, null, null)),
    )

    @Test
    fun `offline, raising an order queues it and shows it as waiting to send`() = runTest(dispatcher) {
        val repository = FakeOrders()
        val support = support()
        val vm = viewModel(repository, support)
        online.value = false
        runCurrent()

        vm.onEvent(PoEvent.EditForm(filled))
        vm.onEvent(PoEvent.SubmitForm)
        runCurrent()

        assertEquals(0, repository.creates, "nothing is sent while offline")
        val queued = outbox.all().single()
        assertEquals(PO_CREATE_KIND, queued.kind)
        assertEquals(SyncState.Pending, queued.state)
        assertNull(vm.state.value.form, "the form closes once the order is queued")
        assertEquals(PurchaseOrderViewModel.QUEUED_NOTICE, vm.state.value.notice)

        val row = vm.state.value.localOrders.single()
        assertTrue(row.isLocalOnly)
        assertEquals("Panavision", row.vendorName)
        assertEquals(2_500.0, row.total)
        assertEquals("", row.number)
        vm.onEvent(PoEvent.Open(PoDestination.MyPos))
        runCurrent()
        assertTrue(vm.state.value.rows.first().isLocalOnly, "the local row leads My Orders")
    }

    @Test
    fun `online, a raise that never left the machine is queued rather than lost`() = runTest(dispatcher) {
        val repository = FakeOrders(createAnswer = ZillitResult.Failure(ZillitError.NoConnection()))
        val vm = viewModel(repository, support())

        vm.onEvent(PoEvent.EditForm(filled))
        vm.onEvent(PoEvent.SubmitForm)
        runCurrent()

        assertEquals(1, repository.creates, "it was tried")
        assertEquals(1, outbox.all().size, "and then queued")
        assertNull(vm.state.value.form)
    }

    @Test
    fun `online, a refusal is reported and the form keeps its words`() = runTest(dispatcher) {
        val repository = FakeOrders(createAnswer = ZillitResult.Failure(ZillitError.Http(status = 422)))
        val vm = viewModel(repository, support())

        vm.onEvent(PoEvent.EditForm(filled))
        vm.onEvent(PoEvent.SubmitForm)
        runCurrent()

        assertEquals(0, outbox.all().size, "a refusal is not something to send again later")
        assertEquals(filled, vm.state.value.form?.copy(saving = false, problems = emptyList()))
    }

    @Test
    fun `without offline support nothing changes`() = runTest(dispatcher) {
        val repository = FakeOrders(createAnswer = ZillitResult.Failure(ZillitError.NoConnection()))
        val vm = viewModel(repository, support = null)

        vm.onEvent(PoEvent.EditForm(filled))
        vm.onEvent(PoEvent.SubmitForm)
        runCurrent()

        assertEquals(0, outbox.all().size)
        assertEquals(
            filled,
            vm.state.value.form?.copy(saving = false, problems = emptyList()),
            "the form is kept, as before",
        )
    }

    @Test
    fun `the form is kept on disk as it is typed and restored on reopen`() = runTest(dispatcher) {
        val support = support()
        val first = viewModel(FakeOrders(), support)
        first.onEvent(PoEvent.EditForm(filled))
        advanceTimeBy(1_000)
        runCurrent()

        val second = viewModel(FakeOrders(), support)
        assertEquals(filled, second.state.value.form)

        // Raising it forgets the saved copy: a restore must not resurrect a sent order.
        second.onEvent(PoEvent.SubmitForm)
        runCurrent()
        val third = viewModel(FakeOrders(), support)
        assertNull(third.state.value.form, "a restore must not resurrect a sent order")
    }

    @Test
    fun `a list that cannot be fetched is shown from its saved copy, dated`() = runTest(dispatcher) {
        val support = support()
        val served = listOf(order("po-1", "PO-0001"), order("po-2", "PO-0002"))
        viewModel(FakeOrders(myOrders = served), support)
        // Fetched and remembered at `now`.
        val fetchedAt = now

        now += 60_000
        val cut = viewModel(FakeOrders(myOrdersAnswer = ZillitResult.Failure(ZillitError.NoConnection())), support)
        assertEquals(served.map { it.id }, cut.state.value.orders.map { it.id })
        assertEquals(fetchedAt, cut.state.value.staleSince)
        assertNull(cut.state.value.error)

        // A server refusal is not "offline": no saved copy is shown for it.
        val refused = viewModel(FakeOrders(myOrdersAnswer = ZillitResult.Failure(ZillitError.Http(500))), support)
        assertNull(refused.state.value.staleSince)
        assertNotNull(refused.state.value.error)
    }

    // -- the handler ---------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true }

    private fun queuedOp(queuedAt: Long = now) = SyncOperation(
        id = "op-1",
        scope = scope,
        kind = PO_CREATE_KIND,
        label = "l",
        payload = json.encodeToString(
            QueuedPurchaseOrder.serializer(),
            QueuedPurchaseOrder(
                filled.toRequest(status = null, layout = FormLayout(FormTemplate())),
                raisedBy = "user-1",
                queuedAt = queuedAt,
            ),
        ),
        nextAttemptAt = 0,
        createdAt = queuedAt,
        updatedAt = queuedAt,
    )

    private val context = object : SyncContext {
        override suspend fun dependencyResult(operation: SyncOperation): String? = null
        override suspend fun updatePayload(operation: SyncOperation, payload: String) = Unit
        override fun nowMillis(): Long = now
    }

    @Test
    fun `the handler adopts an order the server already has instead of creating it again`() = runTest(dispatcher) {
        val already = order("po-9", "PO-0009", vendor = "Panavision", description = "Camera package", total = 2_500.0)
            .copy(createdAt = now + 1)
        val repository = FakeOrders(myOrders = listOf(already))

        val outcome = PoSyncHandler(repository, json).execute(queuedOp(), context)

        assertEquals(SyncOutcome.Done(result = "po-9"), outcome)
        assertEquals(0, repository.creates)
    }

    @Test
    fun `the handler creates when nothing matches and adopts the id afterwards`() = runTest(dispatcher) {
        val older = order("po-3", "PO-0003", vendor = "Panavision", description = "Camera package", total = 2_500.0)
            .copy(createdAt = now - 60 * 60_000)
        val repository = FakeOrders(myOrders = listOf(older))
        repository.onCreate = { created ->
            val landed = order("po-10", "PO-0010", created.vendorName, created.description, created.total)
            repository.myOrders = repository.myOrders + landed.copy(createdAt = now + 5)
        }

        val outcome = PoSyncHandler(repository, json).execute(queuedOp(), context)

        assertEquals(1, repository.creates, "an hour-old twin is a different order")
        assertEquals(SyncOutcome.Done(result = "po-10"), outcome)
    }

    @Test
    fun `the handler retries on the network and parks on a refusal`() = runTest(dispatcher) {
        val cut = FakeOrders(myOrdersAnswer = ZillitResult.Failure(ZillitError.NoConnection()))
        assertIs<SyncOutcome.RetryLater>(PoSyncHandler(cut, json).execute(queuedOp(), context))

        val refused = FakeOrders(createAnswer = ZillitResult.Failure(ZillitError.Http(status = 400)))
        assertIs<SyncOutcome.Failed>(PoSyncHandler(refused, json).execute(queuedOp(), context))
    }

    // -- fixtures ------------------------------------------------------------

    private fun order(
        id: String,
        number: String,
        vendor: String = "Vendor",
        description: String = "Thing",
        total: Double = 10.0,
    ) = PurchaseOrder(
        id = id, number = number, vendorId = null, vendorName = vendor, description = description,
        departmentId = null, companyId = null, status = PoStatus.AwaitingApproval, currency = "GBP", total = total,
        vatTreatment = null, nominalCode = null, episode = null, notes = null, effectiveDate = null,
        createdAt = null, raisedBy = "user-1", assignedTo = null, reassignmentReason = null, deliveryAddress = null,
    )

    @Suppress("TooManyFunctions") // One override per server operation.
    private class FakeOrders(
        var myOrders: List<PurchaseOrder> = emptyList(),
        private val myOrdersAnswer: ZillitResult<List<PurchaseOrder>>? = null,
        private val createAnswer: ZillitResult<Unit> = ZillitResult.Success(Unit),
    ) : PurchaseOrderRepository {
        var creates = 0
        var onCreate: (NewPurchaseOrder) -> Unit = {}

        override suspend fun myOrders(): ZillitResult<List<PurchaseOrder>> =
            myOrdersAnswer ?: ZillitResult.Success(myOrders)

        override suspend fun create(order: NewPurchaseOrder): ZillitResult<Unit> {
            creates++
            if (createAnswer is ZillitResult.Success) onCreate(order)
            return createAnswer
        }

        override suspend fun vendors(): ZillitResult<List<Vendor>> = ZillitResult.Success(emptyList())
        override suspend fun orders(
            status: PoStatus?,
            departmentId: String?,
        ): ZillitResult<List<PurchaseOrder>> = myOrders()
        override suspend fun approvalQueue(): ZillitResult<List<PurchaseOrder>> = ZillitResult.Success(emptyList())
        override suspend fun order(id: String): ZillitResult<PurchaseOrder> = unsupported()
        override suspend fun history(id: String): ZillitResult<List<PoHistoryEntry>> = ZillitResult.Success(emptyList())
        override suspend fun update(id: String, order: NewPurchaseOrder): ZillitResult<Unit> = unsupported()
        override suspend fun delete(id: String): ZillitResult<Unit> = unsupported()
        override suspend fun approve(id: String, tierNumber: Int, totalTiers: Int): ZillitResult<Unit> = unsupported()
        override suspend fun reject(id: String, reason: String): ZillitResult<Unit> = unsupported()
        override suspend fun post(id: String, request: PoPostRequest): ZillitResult<Unit> = unsupported()
        override suspend fun close(id: String, reason: String, effectiveDate: Long?): ZillitResult<Unit> =
            unsupported()
        override suspend fun closeAll(ids: List<String>, effectiveDate: Long?): ZillitResult<Unit> = unsupported()

        private fun <T> unsupported(): ZillitResult<T> = ZillitResult.Failure(ZillitError.Unknown("not in this test"))
    }
}
