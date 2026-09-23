package com.zillit.desktop.feature.purchaseorder

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.purchaseorder.domain.NewPurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PoApproval
import com.zillit.desktop.feature.purchaseorder.domain.PoApprovalTiers
import com.zillit.desktop.feature.purchaseorder.domain.PoEntryUpdate
import com.zillit.desktop.feature.purchaseorder.domain.PoHistoryEntry
import com.zillit.desktop.feature.purchaseorder.domain.PoLine
import com.zillit.desktop.feature.purchaseorder.domain.PoPeriodLock
import com.zillit.desktop.feature.purchaseorder.domain.PoPostRequest
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.domain.PoViewer
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrderRepository
import com.zillit.desktop.feature.purchaseorder.domain.Vendor
import com.zillit.desktop.feature.purchaseorder.domain.isoDayToUtcMidnight
import com.zillit.desktop.feature.purchaseorder.ui.PoConfirmAction
import com.zillit.desktop.feature.purchaseorder.ui.PoDestination
import com.zillit.desktop.feature.purchaseorder.ui.PoEffect
import com.zillit.desktop.feature.purchaseorder.ui.PoEvent
import com.zillit.desktop.feature.purchaseorder.ui.PoPrompt
import com.zillit.desktop.feature.purchaseorder.ui.PoQueueScope
import com.zillit.desktop.feature.purchaseorder.ui.PoReasonAction
import com.zillit.desktop.feature.purchaseorder.ui.PurchaseOrderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The gates the view model holds itself to, whatever the screen shows: who may
 * approve (the tier chain, on any tab), what Post refuses, what the lock
 * forbids, and which route lands where.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PoApprovalFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Accounts, not senior, and the approver of the production's tier 2. */
    private val accountant = PoViewer("accountant", "department_accounts", "designation_accounts_assistant_accounts")

    private val tierOneDone = parityOrder(approvals = listOf(PoApproval("hod", "", 1, "approved", null, 1L)))

    private fun TestScope.opened(
        repository: FlowFake,
        viewer: PoViewer = accountant,
    ): Pair<PurchaseOrderViewModel, MutableList<PoEffect>> {
        val model = PurchaseOrderViewModel(repository, { viewer })
        val effects = mutableListOf<PoEffect>()
        backgroundScope.launch(dispatcher) { model.effects.collect { effects += it } }
        model.start()
        runCurrent()
        model.onEvent(PoEvent.Open(PoDestination.AllPos))
        runCurrent()
        return model to effects
    }

    @Test
    fun `an accountant on the next tier approves from All POs with the tier body`() = runTest(dispatcher) {
        val repository = FlowFake(listOf(tierOneDone))
        val (model, _) = opened(repository)
        model.onEvent(PoEvent.Ask(PoPrompt.Confirm(PoConfirmAction.Approve, "po-1", "Approve", "")))
        model.onEvent(PoEvent.ConfirmPrompt)
        runCurrent()
        assertEquals(listOf(Triple("po-1", 2, 2)), repository.approved)
    }

    @Test
    fun `somebody who is not the next tier's approver is refused, and nothing is sent`() = runTest(dispatcher) {
        val repository = FlowFake(listOf(parityOrder()))
        val (model, effects) = opened(repository)
        model.onEvent(PoEvent.Ask(PoPrompt.Confirm(PoConfirmAction.Approve, "po-1", "Approve", "")))
        model.onEvent(PoEvent.ConfirmPrompt)
        model.onEvent(PoEvent.Ask(PoPrompt.WithReason(PoReasonAction.Reject, "po-1", "Reject", "", reason = "No")))
        model.onEvent(PoEvent.ConfirmPrompt)
        runCurrent()
        assertTrue(repository.approved.isEmpty(), "approved without being the tier's approver")
        assertTrue(repository.rejected.isEmpty(), "rejected without being the tier's approver")
        assertEquals(2, effects.count { it is PoEffect.Failed })
    }

    @Test
    fun `an order in the locked period cannot be decided`() = runTest(dispatcher) {
        val dated = tierOneDone.copy(effectiveDate = "2026-08-01".isoDayToUtcMidnight())
        val repository = FlowFake(listOf(dated), lock = PoPeriodLock("2026-08-30"))
        val (model, _) = opened(repository)
        model.onEvent(PoEvent.Ask(PoPrompt.Confirm(PoConfirmAction.Approve, "po-1", "Approve", "")))
        model.onEvent(PoEvent.ConfirmPrompt)
        runCurrent()
        assertTrue(repository.approved.isEmpty())
    }

    @Test
    fun `post refuses rows with no nominal and names them`() = runTest(dispatcher) {
        val order = parityOrder(
            status = PoStatus.AccountsEntered,
            gross = 100.0,
            assignedTo = "accountant",
            lines = listOf(
                PoLine("l1", "Camera body", 1.0, 100.0, nominalCode = null, vatRate = null),
            ),
            effectiveDate = "2026-09-10".isoDayToUtcMidnight(),
        ).copy(nominalCode = null)
        val repository = FlowFake(listOf(order))
        val (model, effects) = opened(repository)
        model.onEvent(PoEvent.ProcessOrder("po-1"))
        runCurrent()
        assertEquals(PoDestination.Entry, model.state.value.destination)
        model.onEvent(PoEvent.PostEntry)
        runCurrent()
        assertTrue(repository.posted.isEmpty(), "posted an uncoded line")
        assertEquals(listOf(1), model.state.value.entry?.missingCodes)
        assertTrue(effects.any { it is PoEffect.Failed && "Line 1" in it.message })

        // Coded, it goes — with the web's body.
        val entry = model.state.value.entry!!
        model.onEvent(PoEvent.EditEntry(entry.copy(lines = entry.lines.map { it.copy(nominalCode = "2400") })))
        model.onEvent(PoEvent.PostEntry)
        runCurrent()
        val request = repository.posted.single()
        assertEquals("2400", request.lines.single().nominalCode)
        assertEquals(order.effectiveDate, request.effectiveDate)
    }

    @Test
    fun `the bare route lands on the role's queue every time the tool is shown`() = runTest(dispatcher) {
        val repository = FlowFake(emptyList())
        val (model, _) = opened(repository)
        model.onEvent(PoEvent.OpenQueue(PoQueueScope.All))
        model.onEvent(PoEvent.Open(PoDestination.AllPos))
        runCurrent()
        // The hub re-embeds the bare path: the tool goes back to My Queue.
        model.openRoute("/film-tools/purchase-order")
        runCurrent()
        assertEquals(PoDestination.Queue, model.state.value.destination)
        assertEquals(PoQueueScope.Mine, model.state.value.queueScope)
        // A named half is honoured too.
        model.openRoute("/film-tools/purchase-order/queue/all")
        runCurrent()
        assertEquals(PoQueueScope.All, model.state.value.queueScope)
    }

    @Test
    fun `search, department and sort survive a tab switch`() = runTest(dispatcher) {
        val (model, _) = opened(FlowFake(emptyList()))
        model.onEvent(PoEvent.Search("Panavision"))
        model.onEvent(PoEvent.FilterDepartment("dept-cam"))
        model.onEvent(PoEvent.Open(PoDestination.Queue))
        runCurrent()
        assertEquals("Panavision", model.state.value.search)
        assertEquals("dept-cam", model.state.value.departmentFilter)
    }

    @Test
    fun `a locked row never joins a selection`() = runTest(dispatcher) {
        val august = "2026-08-01".isoDayToUtcMidnight()
        val september = "2026-09-10".isoDayToUtcMidnight()
        val locked = parityOrder(id = "old", status = PoStatus.Approved, effectiveDate = august)
        val open = parityOrder(id = "new", status = PoStatus.Approved, effectiveDate = september)
        val (model, _) = opened(FlowFake(listOf(locked, open), lock = PoPeriodLock("2026-08-30")))
        model.onEvent(PoEvent.ToggleSelection("old"))
        assertTrue(model.state.value.selection.isEmpty())
        model.onEvent(PoEvent.SelectAll(listOf("old", "new")))
        assertEquals(setOf("new"), model.state.value.selection)
        assertNull(model.state.value.prompt)
    }
}

/** A service with what these flows touch, and a record of what they send. */
internal class FlowFake(
    private val rows: List<PurchaseOrder>,
    private val tiers: PoApprovalTiers = parityTiers(),
    private val lock: PoPeriodLock = PoPeriodLock(),
) : PurchaseOrderRepository {
    val approved = mutableListOf<Triple<String, Int, Int>>()
    val rejected = mutableListOf<String>()
    val posted = mutableListOf<PoPostRequest>()
    val saved = mutableListOf<PoEntryUpdate>()

    override suspend fun approvalTiers() = ZillitResult.Success(tiers)
    override suspend fun periodLock() = ZillitResult.Success(lock)
    override suspend fun orders(status: PoStatus?, departmentId: String?): ZillitResult<List<PurchaseOrder>> =
        ZillitResult.Success(rows)
    override suspend fun approvalQueue(): ZillitResult<List<PurchaseOrder>> = ZillitResult.Success(rows)
    override suspend fun myOrders(): ZillitResult<List<PurchaseOrder>> = ZillitResult.Success(rows)
    override suspend fun order(id: String): ZillitResult<PurchaseOrder> =
        rows.firstOrNull { it.id == id }?.let { ZillitResult.Success(it) } ?: unsupported()
    override suspend fun history(id: String): ZillitResult<List<PoHistoryEntry>> = ZillitResult.Success(emptyList())
    override suspend fun create(order: NewPurchaseOrder): ZillitResult<Unit> = unsupported()
    override suspend fun update(id: String, order: NewPurchaseOrder): ZillitResult<Unit> = unsupported()
    override suspend fun delete(id: String): ZillitResult<Unit> = unsupported()
    override suspend fun approve(id: String, tierNumber: Int, totalTiers: Int): ZillitResult<Unit> {
        approved += Triple(id, tierNumber, totalTiers)
        return ZillitResult.Success(Unit)
    }
    override suspend fun reject(id: String, reason: String): ZillitResult<Unit> {
        rejected += id
        return ZillitResult.Success(Unit)
    }
    override suspend fun post(id: String, request: PoPostRequest): ZillitResult<Unit> {
        posted += request
        return ZillitResult.Success(Unit)
    }
    override suspend fun saveEntry(id: String, update: PoEntryUpdate): ZillitResult<Unit> {
        saved += update
        return ZillitResult.Success(Unit)
    }
    override suspend fun close(id: String, reason: String, effectiveDate: Long?): ZillitResult<Unit> = unsupported()
    override suspend fun closeAll(ids: List<String>, effectiveDate: Long?): ZillitResult<Unit> = unsupported()
    override suspend fun vendors(): ZillitResult<List<Vendor>> = ZillitResult.Success(emptyList())

    private fun <T> unsupported(): ZillitResult<T> = ZillitResult.Failure(ZillitError.Unknown("not in this test"))
}
