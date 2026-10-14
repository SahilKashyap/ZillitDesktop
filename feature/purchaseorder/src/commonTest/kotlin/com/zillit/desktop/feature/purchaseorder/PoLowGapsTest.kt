package com.zillit.desktop.feature.purchaseorder

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.purchaseorder.domain.NewPurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PoAssignmentRule
import com.zillit.desktop.feature.purchaseorder.domain.PoAttachment
import com.zillit.desktop.feature.purchaseorder.domain.PoBadgeBucket
import com.zillit.desktop.feature.purchaseorder.domain.PoBadgeLeaf
import com.zillit.desktop.feature.purchaseorder.domain.PoBadges
import com.zillit.desktop.feature.purchaseorder.domain.PoCompact
import com.zillit.desktop.feature.purchaseorder.domain.PoCurrencyRates
import com.zillit.desktop.feature.purchaseorder.domain.PoDepartment
import com.zillit.desktop.feature.purchaseorder.domain.PoHistoryEntry
import com.zillit.desktop.feature.purchaseorder.domain.PoLine
import com.zillit.desktop.feature.purchaseorder.domain.PoQueueDepartments
import com.zillit.desktop.feature.purchaseorder.domain.PoSettings
import com.zillit.desktop.feature.purchaseorder.domain.PoSettingsBundle
import com.zillit.desktop.feature.purchaseorder.domain.PoSortColumn
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.domain.PoTeamMember
import com.zillit.desktop.feature.purchaseorder.domain.PoUnread
import com.zillit.desktop.feature.purchaseorder.domain.PoViewer
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrderRepository
import com.zillit.desktop.feature.purchaseorder.domain.describe
import com.zillit.desktop.feature.purchaseorder.domain.isoDayToUtcMidnight
import com.zillit.desktop.feature.purchaseorder.ui.PoConfirmAction
import com.zillit.desktop.feature.purchaseorder.ui.PoDestination
import com.zillit.desktop.feature.purchaseorder.ui.PoEffect
import com.zillit.desktop.feature.purchaseorder.ui.PoEvent
import com.zillit.desktop.feature.purchaseorder.ui.PoFormState
import com.zillit.desktop.feature.purchaseorder.ui.PoPrompt
import com.zillit.desktop.feature.purchaseorder.ui.PoReassignState
import com.zillit.desktop.feature.purchaseorder.ui.PoUiState
import com.zillit.desktop.feature.purchaseorder.ui.PurchaseOrderViewModel
import com.zillit.desktop.feature.purchaseorder.ui.followingHeaderNominal
import com.zillit.desktop.feature.purchaseorder.ui.matches
import com.zillit.desktop.feature.purchaseorder.ui.pages.asWords
import com.zillit.desktop.feature.purchaseorder.ui.repriced
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The low-severity parity gaps closed against the web at `df68cfc58`: figures, search, forms, history. */
class PoLowGapsTest {

    private fun day(iso: String): Long? = iso.isoDayToUtcMidnight()

    // -- 1. compact money, as the web's stat cards print it -------------------------

    @Test
    fun `compact numbers are two decimals with trailing zeros dropped`() {
        assertEquals("1.05B", PoCompact.number(1_050_000_000.0))
        assertEquals("2.9M", PoCompact.number(2_900_000.0))
        assertEquals("2M", PoCompact.number(2_000_000.0))
        assertEquals("150K", PoCompact.number(150_000.0))
        assertEquals("150.5K", PoCompact.number(150_500.0), "Money.compact would round this to 151K")
        assertEquals("1.25K", PoCompact.number(1_250.0))
        assertEquals("1K", PoCompact.number(1_000.0))
        assertEquals("999.5", PoCompact.number(999.5))
        assertEquals("12", PoCompact.number(12.0))
        assertEquals("0", PoCompact.number(0.0))
        assertEquals("1.23M", PoCompact.number(1_234_567.0))
    }

    @Test
    fun `a total is compact on the tile and exact under the pointer`() {
        val rates = PoCurrencyRates(defaultCode = "GBP", rates = mapOf("USD" to 1.25))
        val one = rates.describe(listOf(parityOrder(gross = 1_234_567.89)), "GBP")
        assertEquals("£1.23M", one.value)
        assertEquals("£1,234,567.89", one.title)

        val mixed = rates.describe(
            listOf(parityOrder(id = "a", gross = 100.0), parityOrder(id = "b", gross = 125.0, currency = "USD")),
            "GBP",
        )
        assertEquals("£200", mixed.value)
        assertEquals("£200.00", mixed.title)
    }

    @Test
    fun `a currency with no rate is added at face value and the title says so`() {
        val rates = PoCurrencyRates(defaultCode = "GBP", rates = emptyMap())
        val total = rates.describe(
            listOf(parityOrder(id = "a", gross = 100.0), parityOrder(id = "b", gross = 50.0, currency = "EUR")),
            "GBP",
        )
        assertEquals("£150", total.value)
        assertTrue(total.title.endsWith(str(S.desktop_po_total_no_rate_note)), total.title)
        // One currency needs no rate at all, so says nothing.
        val single = rates.describe(listOf(parityOrder(currency = "EUR")), "GBP")
        assertFalse(single.title.contains(str(S.desktop_po_total_no_rate_note)))
        assertEquals("€1K", single.value)
    }

    // -- 2. the Queue's Assigned Depts --------------------------------------------------

    @Test
    fun `assigned depts come from the active rules aimed at this viewer`() {
        val rules = listOf(
            PoAssignmentRule(id = "r1", departments = listOf("dept-cam", "department_art_department"), assignTo = "me"),
            PoAssignmentRule(id = "r2", departments = listOf("dept-sound"), assignTo = "me", isActive = false),
            PoAssignmentRule(id = "r3", departments = listOf("dept-grip"), assignTo = "someone-else"),
            PoAssignmentRule(id = "r4", departments = listOf("dept-cam", "dept-loc"), assignTo = "me"),
        )
        val state = PoUiState(
            viewer = PoViewer("me", "department_accounts", "designation_accounts_assistant_accounts"),
            departments = listOf(PoDepartment("dept-cam", "Camera"), PoDepartment("dept-loc", "Locations")),
            assignmentRules = rules,
        )
        // The id the list can name is abbreviated as the web does; a bare slug is read as words.
        assertEquals(listOf("Cam", "Art", "Loc"), state.assignedDepartments)
        assertNull(state.copy(assignmentRules = null).assignedDepartments, "unread is not 'none'")
        assertEquals(emptyList(), state.copy(assignmentRules = emptyList()).assignedDepartments)
        assertEquals("Art Department", PoQueueDepartments.slugLabel("department_art_department"))
    }

    // -- 3. Assign / Reassign ----------------------------------------------------------------

    @Test
    fun `an unassigned order is an Assign that needs no reason`() {
        val fresh = PoReassignState(orderId = "po-1", ids = listOf("po-1"), label = "PO-1", assignedTo = null)
        assertTrue(fresh.isUnassigned)
        assertFalse(fresh.canSubmit, "still needs somebody to assign it to")
        assertTrue(fresh.copy(userId = "acc-2").canSubmit)

        val held = fresh.copy(assignedTo = "acc-1", userId = "acc-2")
        assertFalse(held.isUnassigned)
        assertFalse(held.canSubmit, "a hand-off needs its reason")
        assertTrue(held.copy(reason = PoReassignState.REASONS.first()).canSubmit)
    }

    @Test
    fun `the reasons are the web's own English, and a custom one is trimmed`() {
        assertEquals(
            listOf(
                "Complex PO - Needs Senior Review",
                "Workload Balancing",
                "Department Enterprise",
                "Holiday/Absence Cover",
                "Accountant Escalation",
            ),
            PoReassignState.REASONS.dropLast(1),
        )
        val custom = PoReassignState(
            orderId = "po-1",
            ids = listOf("po-1"),
            label = "PO-1",
            reason = PoReassignState.OTHER,
            customReason = " why ",
        )
        assertEquals("why", custom.resolvedReason)
        assertEquals(500, PoReassignState.CUSTOM_REASON_MAX)
    }

    // -- 5. search ----------------------------------------------------------------------------------

    @Test
    fun `search also reads the department, the effective date and the status`() {
        val order = parityOrder(effectiveDate = day("2026-10-12"), status = PoStatus.AccountsEntered)
        assertTrue(order.matches("grip", departmentName = "Grip"))
        assertFalse(order.matches("grip"), "no department name given, none found")
        assertTrue(order.matches("12 oct"), "as the date is shown")
        assertTrue(order.matches("2026-10-12"), "as the date is stored")
        assertTrue(order.matches("acct_entered"), "as the wire spells the status")
        assertTrue(order.matches("acct entered"), "as the pill says it")
        assertFalse(order.matches("zzz", "Grip"))

        val state = PoUiState(
            viewer = PoViewer("u", "department_accounts", null),
            destination = PoDestination.AllPos,
            departments = listOf(PoDepartment("dept-grip", "Grip")),
            orders = listOf(
                parityOrder(id = "a", departmentId = "dept-grip"),
                parityOrder(id = "b", departmentId = "x"),
            ),
            search = "grip",
        )
        assertEquals(listOf("a"), state.rows.map { it.id })
    }

    @Test
    fun `the Posted columns sort by relieved, remaining and relief`() {
        val state = PoUiState(
            viewer = PoViewer("u", "department_accounts", null),
            destination = PoDestination.Posted,
            orders = listOf(
                parityOrder(id = "open", status = PoStatus.Posted, gross = 100.0, paidAmount = 0.0),
                parityOrder(id = "part", status = PoStatus.Posted, gross = 100.0, paidAmount = 40.0),
                parityOrder(id = "full", status = PoStatus.Posted, gross = 100.0, paidAmount = 100.0),
            ),
            sortColumn = PoSortColumn.Relieved,
        )
        assertEquals(listOf("open", "part", "full"), state.rows.map { it.id })
        assertEquals(
            listOf("full", "part", "open"),
            state.copy(sortColumn = PoSortColumn.Remaining).rows.map { it.id },
        )
    }

    @Test
    fun `an order's file count prefers the larger of the mapped count and the list`() {
        assertEquals(2, parityOrder().copy(attachmentCount = 2).attachedFiles)
        assertEquals(1, parityOrder().copy(attachments = listOf(PoAttachment(media = "k"))).attachedFiles)
        assertEquals(0, parityOrder().attachedFiles)
    }

    // -- 7. the form --------------------------------------------------------------------------------------

    private fun coded(id: String, code: String?) =
        PoLine(id = id, description = "", quantity = 1.0, unitPrice = 1.0, nominalCode = code, vatRate = null)

    @Test
    fun `a changed header nominal reaches empty lines and lines still on the old code`() {
        val before = PoFormState(
            mode = com.zillit.desktop.feature.purchaseorder.ui.PoFormMode.NewOrder,
            nominalCode = "2400",
            lines = listOf(coded("a", null), coded("b", "2400"), coded("c", "5000")),
        )
        val after = before.copy(nominalCode = "3000").followingHeaderNominal(before)
        assertEquals(listOf("3000", "3000", "5000"), after.lines.map { it.nominalCode }, "5000 was typed by hand")
        // Clearing the header, or leaving it be, touches nothing.
        assertEquals(before.lines, before.copy(nominalCode = "").followingHeaderNominal(before).lines)
        assertEquals(before.lines, before.followingHeaderNominal(before).lines)
    }

    @Test
    fun `a typed quantity or price re-strikes the amount to the penny`() {
        val line = coded("a", null)
        val priced = line.repriced(quantity = 3.0).repriced(unitPrice = 33.333)
        assertEquals(100.0, priced.total, "3 x 33.333 is 99.999, struck to 100.00")
        assertEquals(3.0, priced.quantity)
        assertEquals(33.333, priced.unitPrice)
        // Half away from zero on the shortest form, as the web's toFixed(2) reads it.
        assertEquals(1.01, line.repriced(quantity = 1.0, unitPrice = 1.005).total)
    }

    // -- 8. history ----------------------------------------------------------------------------------------------

    @Test
    fun `an action reads as words`() {
        assertEquals("Reassigned By", "reassigned_by".asWords())
        assertEquals("Created", "created".asWords())
        assertEquals("", "".asWords())
    }

    // -- 9. which bucket a read clears ------------------------------------------------------------------------------

    private fun leaf(level1: String, level2: String, unread: Int) =
        PoBadgeLeaf(PoBadges.TOOL_HUB, PoBadges.UNIT_PO, level1, level2, "po-1", unread)

    @Test
    fun `an order's own rows and its query rows are told apart`() {
        val unread = PoUnread(
            listOf(
                leaf(PoBadges.LEVEL_QUEUE, PoBadges.ACTION_ORDER, 2),
                leaf(PoBadges.LEVEL_QUEUE, PoBadges.ACTION_QUERY, 1),
            ),
        )
        val scope = PoDestination.Queue.badgeScope
        assertEquals(3, unread.order(scope, "po-1"))
        assertEquals(2, unread.order(scope, "po-1", PoBadgeBucket.Order))
        assertEquals(1, unread.order(scope, "po-1", PoBadgeBucket.Query))
        assertEquals("query_chat", PoBadgeBucket.Query.level2)
        assertEquals("purchase_order_label", PoBadgeBucket.Order.level2)
    }

    @Test
    fun `an order's query thread counts on every tab, under the viewer's tool only`() {
        val unread = PoUnread(
            listOf(
                leaf(PoBadges.LEVEL_ALL_POS, PoBadges.ACTION_QUERY, 1),
                leaf(PoBadges.LEVEL_QUEUE, PoBadges.ACTION_QUERY, 2),
                leaf(PoBadges.LEVEL_QUEUE, PoBadges.ACTION_ORDER, 4),
                PoBadgeLeaf(
                    PoBadges.TOOL_PO, PoBadges.UNIT_PO, PoBadges.LEVEL_MY_POS, PoBadges.ACTION_QUERY, "po-1", 8,
                ),
            ),
        )
        assertEquals(3, unread.query(PoBadges.TOOL_HUB, "po-1"))
        assertEquals(8, unread.query(PoBadges.TOOL_PO, "po-1"))
        assertEquals(0, unread.query(PoBadges.TOOL_HUB, "po-2"))
    }

    // -- 10. the attachment's own words ------------------------------------------------------------------------------

    @Test
    fun `a file is typed by its MIME's major type, or its extension when the store could not say`() {
        assertEquals("application", PoAttachment(media = "k", contentType = "application/pdf").wireContentType)
        val octet = PoAttachment(media = "k", name = "a.JPG", contentType = "application/octet-stream")
        assertEquals("image", octet.wireContentType)
        assertEquals("image", PoAttachment(media = "k", contentType = "image/png").wireContentType)
        assertEquals("application", PoAttachment(media = "k", name = "terms.docx").wireContentType)
        assertEquals("jpg", PoAttachment(media = "k", name = "a.JPG").wireSubtype)
        assertEquals("pdf", PoAttachment(media = "k", name = "a.docx", contentSubtype = "pdf").wireSubtype)
    }
}

/** The flows the screens drive: the leave-the-form guard, the status an edit sends, Assign, History, the reads. */
@OptIn(ExperimentalCoroutinesApi::class)
class PoLowGapsFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val crew = PoViewer("crew", "department_camera", "designation_focus_puller_camera")
    private val senior = PoViewer("acct-1", "department_accounts", "designation_production_accountant_accounts")

    private val body = listOf(
        PoLine(
            id = "l1",
            description = "Body",
            quantity = 1.0,
            unitPrice = 100.0,
            nominalCode = "2400",
            vatRate = null,
        ),
    )

    /** Records the writes under test and answers the rest from [FlowFake]. */
    private class Recorder(
        rows: List<PurchaseOrder>,
        private val rules: List<PoAssignmentRule> = emptyList(),
        private val trail: List<PoHistoryEntry> = emptyList(),
    ) : PurchaseOrderRepository by FlowFake(rows) {
        val updates = mutableListOf<NewPurchaseOrder>()
        val reassigned = mutableListOf<Triple<String, String, String?>>()

        override suspend fun update(id: String, order: NewPurchaseOrder): ZillitResult<Unit> {
            updates += order
            return ZillitResult.Success(Unit)
        }

        override suspend fun reassign(id: String, userId: String, reason: String?): ZillitResult<Unit> {
            reassigned += Triple(id, userId, reason)
            return ZillitResult.Success(Unit)
        }

        override suspend fun history(id: String): ZillitResult<List<PoHistoryEntry>> = ZillitResult.Success(trail)

        override suspend fun settings(): ZillitResult<PoSettingsBundle> =
            ZillitResult.Success(PoSettingsBundle(PoSettings(), rules))
    }

    private fun TestScope.open(
        repository: PurchaseOrderRepository,
        viewer: PoViewer,
        badges: PoBadges = PoBadges.None,
    ): Pair<PurchaseOrderViewModel, MutableList<PoEffect>> {
        val model = PurchaseOrderViewModel(repository, { viewer }, badges = badges)
        val effects = mutableListOf<PoEffect>()
        backgroundScope.launch(dispatcher) { model.effects.collect { effects += it } }
        model.start()
        runCurrent()
        model.onEvent(PoEvent.Open(PoDestination.AllPos))
        runCurrent()
        return model to effects
    }

    // -- the leave-the-form guard ------------------------------------------------------------

    @Test
    fun `leaving a form nobody has typed into asks nothing`() = runTest(dispatcher) {
        val (model, _) = open(Recorder(emptyList()), crew)
        model.onEvent(PoEvent.CreateOrder)
        runCurrent()
        assertNotNull(model.state.value.form)
        model.onEvent(PoEvent.CloseForm)
        assertNull(model.state.value.form)
        assertNull(model.state.value.prompt)
    }

    @Test
    fun `leaving a dirty form asks first, and Leave discards it`() = runTest(dispatcher) {
        val (model, _) = open(Recorder(emptyList()), crew)
        model.onEvent(PoEvent.CreateOrder)
        runCurrent()
        model.onEvent(PoEvent.EditForm(model.state.value.form!!.copy(description = "Camera package")))
        assertTrue(model.state.value.form!!.dirty)

        model.onEvent(PoEvent.CloseForm)
        val prompt = model.state.value.prompt as PoPrompt.Confirm
        assertEquals(PoConfirmAction.DiscardForm, prompt.action)
        assertNotNull(model.state.value.form, "still on the form while the question is open")

        model.onEvent(PoEvent.DismissPrompt)
        assertNotNull(model.state.value.form)
        model.onEvent(PoEvent.CloseForm)
        model.onEvent(PoEvent.ConfirmPrompt)
        runCurrent()
        assertNull(model.state.value.form)
    }

    @Test
    fun `typing the header nominal fills the lines that were following it`() = runTest(dispatcher) {
        val (model, _) = open(Recorder(emptyList()), crew)
        model.onEvent(PoEvent.CreateOrder)
        runCurrent()
        model.onEvent(PoEvent.EditForm(model.state.value.form!!.copy(nominalCode = "3000")))
        assertEquals(listOf("3000"), model.state.value.form!!.lines.map { it.nominalCode })
    }

    // -- the status an edit sends -----------------------------------------------------------------

    private fun editedBy(viewer: PoViewer, repository: Recorder) = runTest(dispatcher) {
        val (model, _) = open(repository, viewer)
        model.onEvent(PoEvent.EditOrder("po-1"))
        runCurrent()
        assertNotNull(model.state.value.form, "the form opened on the order")
        model.onEvent(PoEvent.SubmitForm)
        runCurrent()
    }

    @Test
    fun `the console's Update and Submit sends ACCT_ENTERED, the department's edit sends no status`() {
        val mine = parityOrder(raisedBy = "acct-1", lines = body, status = PoStatus.Rejected)
        val accounts = Recorder(listOf(mine))
        editedBy(senior, accounts)
        assertEquals("ACCT_ENTERED", accounts.updates.single().status)

        val theirs = parityOrder(raisedBy = "crew", lines = body, status = PoStatus.Rejected)
        val department = Recorder(listOf(theirs))
        editedBy(crew, department)
        assertNull(department.updates.single().status)
    }

    // -- Assign -----------------------------------------------------------------------------------

    @Test
    fun `an unassigned order is assigned with a null reason, a held one needs one`() = runTest(dispatcher) {
        val loose = parityOrder(id = "po-1", status = PoStatus.Approved, assignedTo = null)
        val held = parityOrder(id = "po-2", status = PoStatus.Approved, assignedTo = "acct-9")
        val repository = Recorder(listOf(loose, held))
        val (model, effects) = open(repository, senior)

        model.onEvent(PoEvent.AskReassign("po-1"))
        assertTrue(model.state.value.reassign!!.isUnassigned)
        model.onEvent(PoEvent.EditReassign(model.state.value.reassign!!.copy(userId = "acct-2")))
        model.onEvent(PoEvent.ConfirmReassign)
        runCurrent()
        assertEquals(listOf(Triple<String, String, String?>("po-1", "acct-2", null)), repository.reassigned)

        model.onEvent(PoEvent.AskReassign("po-2"))
        assertEquals("acct-9", model.state.value.reassign!!.assignedTo)
        model.onEvent(PoEvent.EditReassign(model.state.value.reassign!!.copy(userId = "acct-2")))
        model.onEvent(PoEvent.ConfirmReassign)
        runCurrent()
        assertEquals(1, repository.reassigned.size, "no reason, nothing sent")
        assertTrue(effects.any { it is PoEffect.Failed })

        model.onEvent(PoEvent.EditReassign(model.state.value.reassign!!.copy(reason = "Workload Balancing")))
        model.onEvent(PoEvent.ConfirmReassign)
        runCurrent()
        assertEquals(Triple("po-2", "acct-2", "Workload Balancing"), repository.reassigned.last())
    }

    // -- History off a row, and the rules the Queue card reads ------------------------------------

    @Test
    fun `a row's History opens the trail on its own and closes again`() = runTest(dispatcher) {
        val trail = listOf(
            PoHistoryEntry("posted", "acct-1", null, 2L),
            PoHistoryEntry("created", "crew", "Raised", 1L),
        )
        val (model, _) = open(Recorder(listOf(parityOrder(status = PoStatus.Posted)), trail = trail), senior)
        model.onEvent(PoEvent.OpenHistory("po-1"))
        assertNull(model.state.value.historyPanel!!.entries, "loading")
        runCurrent()
        assertEquals(trail, model.state.value.historyPanel!!.entries)
        assertEquals("PO-0001", model.state.value.historyPanel!!.title)
        model.onEvent(PoEvent.CloseHistory)
        assertNull(model.state.value.historyPanel)
    }

    @Test
    fun `the settings read also keeps the rules for the Queue card`() = runTest(dispatcher) {
        val rule = PoAssignmentRule(id = "r1", departments = listOf("dept-cam"), assignTo = "acct-1")
        val (model, _) = open(Recorder(emptyList(), rules = listOf(rule)), senior)
        assertEquals(listOf(rule), model.state.value.assignmentRules)
        val assistant = PoViewer("acct-1", "department_accounts", "designation_accounts_assistant_accounts")
        val (other, _) = open(Recorder(emptyList(), rules = listOf(rule)), assistant)
        other.onEvent(PoEvent.Open(PoDestination.Queue))
        runCurrent()
        assertEquals(listOf("Dept-cam"), other.state.value.assignedDepartments)
    }

    // -- which badge rows each open clears ------------------------------------------------------------

    private class Reads(leaves: List<PoBadgeLeaf>) : PoBadges {
        override val leaves: Flow<List<PoBadgeLeaf>> = MutableStateFlow(leaves)
        val sent = mutableListOf<List<Any>>()

        override fun readOrder(tool: String, level1: String, orderId: String, bucket: PoBadgeBucket) {
            sent += listOf(tool, level1, orderId, bucket)
        }

        override fun readQuery(tool: String, orderId: String) {
            sent += listOf(tool, orderId, PoBadgeBucket.Query)
        }
    }

    private fun row(level1: String, level2: String, unread: Int) =
        PoBadgeLeaf(PoBadges.TOOL_HUB, PoBadges.UNIT_PO, level1, level2, "po-1", unread)

    @Test
    fun `opening an order reads its own rows, opening the Query panel reads the thread's`() = runTest(dispatcher) {
        val reads = Reads(
            listOf(
                row(PoBadges.LEVEL_ALL_POS, PoBadges.ACTION_ORDER, 1),
                row(PoBadges.LEVEL_ALL_POS, PoBadges.ACTION_QUERY, 1),
                row(PoBadges.LEVEL_QUEUE, PoBadges.ACTION_QUERY, 2),
            ),
        )
        val (model, _) = open(Recorder(listOf(parityOrder())), senior, badges = reads)
        runCurrent()

        model.onEvent(PoEvent.OpenOrder("po-1"))
        runCurrent()
        assertEquals(
            listOf(listOf<Any>(PoBadges.TOOL_HUB, PoBadges.LEVEL_ALL_POS, "po-1", PoBadgeBucket.Order)),
            reads.sent,
            "the query rows on the same tab stay lit",
        )

        model.onEvent(PoEvent.OpenQuery("po-1"))
        runCurrent()
        assertEquals(
            listOf<Any>(PoBadges.TOOL_HUB, "po-1", PoBadgeBucket.Query),
            reads.sent.last(),
            "the thread is read on every tab at once — no level_1, as iOS reads it",
        )
        assertEquals(2, reads.sent.size)
    }
}
