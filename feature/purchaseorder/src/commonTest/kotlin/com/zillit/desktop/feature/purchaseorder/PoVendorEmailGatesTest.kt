package com.zillit.desktop.feature.purchaseorder

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.purchaseorder.domain.PoAccess
import com.zillit.desktop.feature.purchaseorder.domain.PoEmailReceipt
import com.zillit.desktop.feature.purchaseorder.domain.PoLine
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.domain.PoVendorEmailWarning
import com.zillit.desktop.feature.purchaseorder.domain.PoViewer
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrderRepository
import com.zillit.desktop.feature.purchaseorder.domain.vendorEmailWarning
import com.zillit.desktop.feature.purchaseorder.ui.PoConfirmAction
import com.zillit.desktop.feature.purchaseorder.ui.PoDestination
import com.zillit.desktop.feature.purchaseorder.ui.PoEffect
import com.zillit.desktop.feature.purchaseorder.ui.PoEvent
import com.zillit.desktop.feature.purchaseorder.ui.PoPrompt
import com.zillit.desktop.feature.purchaseorder.ui.PoUiState
import com.zillit.desktop.feature.purchaseorder.ui.PurchaseOrderViewModel
import com.zillit.desktop.feature.purchaseorder.ui.pages.poColumns
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Send to Vendor's two confirms (`vendorEmailConfirm`), and the console-only
 * gates the web hangs on the senior designation: the selection column, the bulk
 * bar, the assignee chip, and the department view's missing Process / Send.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PoVendorEmailGatesTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val senior = PoViewer("acct-1", "department_accounts", "designation_production_accountant_accounts")
    private val assistant = PoViewer("acct-2", "department_accounts", "designation_accounts_assistant_accounts")

    private fun coded(code: String? = "2400", description: String = "Body", total: Double = 100.0) =
        PoLine("l1", description, 1.0, total, code, null)

    private fun order(
        lines: List<PoLine>,
        emailAt: Long? = null,
        status: PoStatus = PoStatus.AccountsEntered,
    ) = parityOrder(status = status, gross = 100.0, assignedTo = "acct-1", lines = lines).copy(emailAt = emailAt)

    // -- the question -----------------------------------------------------------------

    @Test
    fun `a coded first send raises nothing`() {
        assertNull(order(listOf(coded())).vendorEmailWarning())
    }

    @Test
    fun `an uncoded line warns, the persisted tax row included, but a spare row is exempt`() {
        assertEquals(PoVendorEmailWarning.Uncoded, order(listOf(coded(code = null))).vendorEmailWarning())
        assertEquals(PoVendorEmailWarning.Uncoded, order(listOf(coded(code = "  "))).vendorEmailWarning())

        val taxRow = PoLine("t", "", 1.0, 20.0, null, null, amount = 20.0, isTax = true)
        assertEquals(PoVendorEmailWarning.Uncoded, order(listOf(coded(), taxRow)).vendorEmailWarning())

        val spare = PoLine("s", "", 1.0, 0.0, null, null)
        assertNull(order(listOf(coded(), spare)).vendorEmailWarning())
    }

    @Test
    fun `uncoded outranks resend, and a coded order that was sent warns of the second email`() {
        assertEquals(
            PoVendorEmailWarning.Uncoded,
            order(listOf(coded(code = null)), emailAt = 5L).vendorEmailWarning(),
        )
        assertEquals(PoVendorEmailWarning.Resend, order(listOf(coded()), emailAt = 5L).vendorEmailWarning())
    }

    // -- the flow ------------------------------------------------------------------------

    private class Fake(val inner: FlowFake) : PurchaseOrderRepository by inner {
        val sent = mutableListOf<String>()

        override suspend fun sendVendorEmail(id: String): ZillitResult<PoEmailReceipt> {
            sent += id
            return ZillitResult.Success(PoEmailReceipt(true, "vendor@example.com", 9L, "acct-1"))
        }
    }

    private fun TestScope.open(
        row: PurchaseOrder,
        viewer: PoViewer = senior,
    ): Triple<PurchaseOrderViewModel, Fake, MutableList<PoEffect>> {
        val repository = Fake(FlowFake(listOf(row)))
        val model = PurchaseOrderViewModel(repository, { viewer })
        val effects = mutableListOf<PoEffect>()
        backgroundScope.launch(dispatcher) { model.effects.collect { effects += it } }
        model.start()
        runCurrent()
        model.onEvent(PoEvent.Open(PoDestination.AllPos))
        runCurrent()
        return Triple(model, repository, effects)
    }

    @Test
    fun `from the detail an uncoded order asks first and sends on Send anyway`() = runTest(dispatcher) {
        val (model, repository, _) = open(order(listOf(coded(code = null))))
        model.onEvent(PoEvent.OpenOrder("po-1"))
        runCurrent()

        model.onEvent(PoEvent.SendVendorEmail("po-1"))
        runCurrent()
        val prompt = model.state.value.prompt as PoPrompt.Confirm
        assertEquals(PoConfirmAction.SendToVendor, prompt.action)
        assertTrue(prompt.message.contains("Do you still want to send it to the vendor"))
        assertEquals("Send anyway", prompt.confirmLabel)
        assertTrue(repository.sent.isEmpty(), "nothing goes before the answer")

        model.onEvent(PoEvent.ConfirmPrompt)
        runCurrent()
        assertEquals(listOf("po-1"), repository.sent)
    }

    @Test
    fun `from the detail a coded first send goes straight out`() = runTest(dispatcher) {
        val (model, repository, _) = open(order(listOf(coded())))
        model.onEvent(PoEvent.OpenOrder("po-1"))
        runCurrent()

        model.onEvent(PoEvent.SendVendorEmail("po-1"))
        runCurrent()
        assertNull(model.state.value.prompt)
        assertEquals(listOf("po-1"), repository.sent)
    }

    /** On the Process page a resend is allowed, and it is the confirm that says a second email is going out. */
    @Test
    fun `on the Process page a resend asks, and the saved order is what was judged`() = runTest(dispatcher) {
        val (model, repository, _) = open(order(listOf(coded()), emailAt = 5L))
        model.onEvent(PoEvent.ProcessOrder("po-1"))
        runCurrent()

        model.onEvent(PoEvent.SendVendorEmail("po-1", allowResend = true))
        runCurrent()
        assertEquals(1, repository.inner.saved.size, "the page is saved first")
        val prompt = model.state.value.prompt as PoPrompt.Confirm
        assertEquals("Resend PO to vendor", prompt.title)
        assertEquals("Resend to vendor", prompt.confirmLabel)
        assertTrue(repository.sent.isEmpty())

        model.onEvent(PoEvent.ConfirmPrompt)
        runCurrent()
        assertEquals(listOf("po-1"), repository.sent)
        assertEquals(1, repository.inner.saved.size, "confirming does not save a second time")
    }

    @Test
    fun `a department user cannot send through the confirm either`() = runTest(dispatcher) {
        val (model, repository, _) = open(order(listOf(coded(code = null))), viewer = senior.copy(enteredAsTool = true))
        model.onEvent(PoEvent.Ask(PoPrompt.Confirm(PoConfirmAction.SendToVendor, "po-1", "t", "m")))
        model.onEvent(PoEvent.ConfirmPrompt)
        runCurrent()
        assertTrue(repository.sent.isEmpty())
    }

    // -- the senior-only console gates -----------------------------------------------------

    @Test
    fun `selection, bulk and the assignee chip belong to the senior in the console`() {
        val posted = order(listOf(coded()), status = PoStatus.Posted)
        assertTrue(senior.isConsoleSenior)
        assertTrue(PoAccess.canReassign(posted, senior))

        // An accounts assistant sees the console and none of those controls.
        assertTrue(assistant.isAccountant)
        assertFalse(assistant.isConsoleSenior)
        assertFalse(PoAccess.canReassign(posted, assistant))

        // The senior through the Film Tools tile is in the department module, which has none of them.
        val throughTheTile = senior.copy(enteredAsTool = true)
        assertTrue(throughTheTile.isSeniorAccountant)
        assertFalse(throughTheTile.isConsoleSenior)
        assertFalse(PoAccess.canReassign(posted, throughTheTile))
        // Nor does a project admin outside accounts, who used to be let through by hasFullAccess.
        val admin = PoViewer("a", "department_art", "designation_x", isProjectAdmin = true)
        assertFalse(PoAccess.canReassign(posted, admin))
    }

    @Test
    fun `the selection column is the senior's alone`() {
        fun columns(viewer: PoViewer) = poColumns(PoUiState(viewer = viewer), emptyList()) { }.size
        assertEquals(columns(assistant) + 1, columns(senior))
        assertEquals(columns(assistant), columns(senior.copy(enteredAsTool = true)))
    }

    @Test
    fun `an assistant's reassign and bulk date are refused at the handler too`() = runTest(dispatcher) {
        val row = order(listOf(coded()), status = PoStatus.Approved)
        val (model, _, effects) = open(row, viewer = assistant)

        model.onEvent(PoEvent.AskReassign("po-1"))
        runCurrent()
        assertNull(model.state.value.reassign)
        assertTrue(effects.any { it is PoEffect.Failed })

        effects.clear()
        model.onEvent(PoEvent.AskBulkDate(listOf("po-1")))
        model.onEvent(PoEvent.ConfirmBulkDate)
        runCurrent()
        assertNull(model.state.value.bulkDate)
        assertTrue(effects.any { it is PoEffect.Failed })

        val (seniorModel, _, _) = open(row)
        seniorModel.onEvent(PoEvent.AskReassign("po-1"))
        runCurrent()
        assertEquals(listOf("po-1"), seniorModel.state.value.reassign?.ids)
    }

    /** The web's department module hands its detail modal neither `onProcess` nor `onSendVendorEmail`. */
    @Test
    fun `the department view has no Process and no Send to Vendor`() {
        val ready = order(listOf(coded()))
        assertTrue(PoAccess.canProcess(ready, senior))
        assertTrue(PoAccess.canSendVendorEmail(ready, senior))

        val throughTheTile = senior.copy(enteredAsTool = true)
        assertFalse(PoAccess.canProcess(ready, throughTheTile))
        assertFalse(PoAccess.canSendVendorEmail(ready, throughTheTile))
        // Not even for the order's own assignee, who is in accounts but came in through the tile.
        val assignee = assistant.copy(userId = "acct-1", enteredAsTool = true)
        assertFalse(PoAccess.canProcess(ready, assignee))
    }
}
