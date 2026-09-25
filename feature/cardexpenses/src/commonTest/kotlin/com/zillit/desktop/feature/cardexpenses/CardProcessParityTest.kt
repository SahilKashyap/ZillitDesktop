package com.zillit.desktop.feature.cardexpenses

import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.cardexpenses.data.CardRepositoryImpl
import com.zillit.desktop.feature.cardexpenses.data.parseChart
import com.zillit.desktop.feature.cardexpenses.data.parseLockRoute
import com.zillit.desktop.feature.cardexpenses.data.parseTaxTypes
import com.zillit.desktop.feature.cardexpenses.data.readLineItems
import com.zillit.desktop.feature.cardexpenses.domain.ApprovalTier
import com.zillit.desktop.feature.cardexpenses.domain.BulkCoding
import com.zillit.desktop.feature.cardexpenses.domain.CardApproval
import com.zillit.desktop.feature.cardexpenses.domain.CardMetadata
import com.zillit.desktop.feature.cardexpenses.domain.CardPeriodLock
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardTaxType
import com.zillit.desktop.feature.cardexpenses.domain.CardViewer
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.domain.MatchStatus
import com.zillit.desktop.feature.cardexpenses.domain.ProcessFigures
import com.zillit.desktop.feature.cardexpenses.domain.ProcessLine
import com.zillit.desktop.feature.cardexpenses.domain.ProcessLines
import com.zillit.desktop.feature.cardexpenses.domain.ProcessRefs
import com.zillit.desktop.feature.cardexpenses.domain.ProcessRules
import com.zillit.desktop.feature.cardexpenses.domain.ProcessSubmission
import com.zillit.desktop.feature.cardexpenses.domain.ProcessingFlag
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptProcessing
import com.zillit.desktop.feature.cardexpenses.domain.TaxLineDraft
import com.zillit.desktop.feature.cardexpenses.domain.TaxLineWire
import com.zillit.desktop.feature.cardexpenses.domain.TierConfig
import com.zillit.desktop.feature.cardexpenses.domain.TierRule
import com.zillit.desktop.feature.cardexpenses.ui.CardDestination
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardExpensesViewModel
import com.zillit.desktop.feature.cardexpenses.ui.ProcessPageEvent
import com.zillit.desktop.feature.cardexpenses.ui.pages.ruleHeadline
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The process pages' web-parity rules — the approval wire, the split and
 * reclaimable-tax arithmetic, the closed period, the bulk tax — pinned against
 * `ApprovalQueuePage.jsx`, `ProcessReceiptModal.jsx` and `BulkProcessPage.jsx`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CardProcessParityTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // -- the wire -------------------------------------------------------------------

    @Test
    fun `approve names the tier being signed and who signed it`() = runTest {
        val (repo, calls) = repository()
        repo.approveReceipt("r1", tierNumber = 2, userId = "u1")
        repo.rejectReceipt("r1", "Wrong code", userId = "u1")

        val (approve, reject) = calls
        assertTrue(approve.first.endsWith("/approvals/r1/approve"), approve.first)
        assertEquals(2, approve.second["tier_number"]?.jsonPrimitive?.content?.toInt())
        assertEquals("u1", approve.second["user_id"]?.jsonPrimitive?.content)
        assertTrue(reject.first.endsWith("/approvals/r1/reject"), reject.first)
        assertEquals("Wrong code", reject.second["reason"]?.jsonPrimitive?.content)
        assertEquals("u1", reject.second["user_id"]?.jsonPrimitive?.content)
    }

    /** `details.tax` is null or `{tax_type, tax_rate}` — never a bare string (`BulkProcessPage.jsx:165`). */
    @Test
    fun `a bulk tax override goes as the type with its rate`() = runTest {
        val (repo, calls) = repository()
        repo.bulkProcess(listOf("r1"), BulkCoding(taxType = "vat_20", taxRate = 20.0))
        repo.bulkProcess(listOf("r1"), BulkCoding())

        val tax = calls[0].second.getValue("details").jsonObject.getValue("tax").jsonObject
        assertEquals("vat_20", tax["tax_type"]?.jsonPrimitive?.content)
        assertEquals(20.0, tax["tax_rate"]?.jsonPrimitive?.content?.toDouble())
        assertEquals(JsonNull, calls[1].second.getValue("details").jsonObject["tax"])
    }

    /** Splits go as every line with its parent's id, and the tax row as an `is_tax` line last. */
    @Test
    fun `a split and the reclaimable tax row go on the wire`() = runTest {
        val (repo, calls) = repository()
        repo.saveProcessReceipt(
            "r1",
            ProcessSubmission(
                lines = listOf(
                    ProcessLine(id = "p", description = "Kit", account = "4100", net = 100.0),
                    ProcessLine(id = "c1", account = "4100", net = 60.0, splitParentId = "p"),
                    ProcessLine(id = "c2", account = "4200", net = 40.0, splitParentId = "p", tags = listOf("ASSET")),
                ),
                fixedLines = emptyList(),
                net = 100.0,
                tax = 20.0,
                gross = 120.0,
                description = null,
                nominalCode = null,
                effectiveDate = null,
                userId = "u1",
                taxLine = TaxLineWire(amount = 20.0, account = "2200", trackingCodes = mapOf("set-1" to "LOC")),
            ),
        )
        val lines = calls.single().second.getValue("line_items").jsonArray.map { it.jsonObject }
        assertEquals(4, lines.size)
        assertEquals("p", lines[1]["split_parent_id"]?.jsonPrimitive?.content)
        assertEquals("ASSET", lines[2].getValue("tags").jsonArray.single().jsonPrimitive.content)
        val tax = lines[3]
        assertEquals("true", tax["is_tax"]?.jsonPrimitive?.content)
        assertEquals(20.0, tax["amount"]?.jsonPrimitive?.content?.toDouble())
        assertEquals("2200", tax["account"]?.jsonPrimitive?.content)
        assertEquals(3, tax["sort_order"]?.jsonPrimitive?.content?.toInt())
        assertEquals("LOC", tax.getValue("tracking_codes").jsonObject["set-1"]?.jsonPrimitive?.content)
    }

    /** A saved `is_tax` line is the tax row, hydrated as an override — not a line to re-send verbatim. */
    @Test
    fun `a saved tax line reopens as the tax row`() {
        val read = Json.parseToJsonElement(
            """[
              {"id":"p","description":"Kit","amount":"120","tax_rate":"20","tax_type":"vat_20","account":"4100"},
              {"id":"c","amount":"60","tax_rate":"20","split_parent_id":"p","tags":"[\"CIS\"]"},
              {"is_tax":true,"amount":"15","account":"2200","tracking_codes":{"s":"X"}}
            ]""",
        ).readLineItems()

        assertEquals(2, read.coded.size)
        assertEquals("p", read.coded[1].splitParentId)
        assertEquals(listOf("CIS"), read.coded[1].tags)
        assertEquals("vat_20", read.coded[0].taxType)
        val tax = assertNotNull(read.taxLine)
        assertTrue(tax.overridden)
        assertEquals(15.0, tax.amount)
        assertEquals(mapOf("s" to "X"), tax.trackingCodes)
        assertTrue(read.fixed.isEmpty())
    }

    // -- the arithmetic -----------------------------------------------------------------

    /** A split parent is not counted beside its children (`ProcessReceiptModal.jsx:325-327`). */
    @Test
    fun `a saved split is not double-counted`() {
        val figures = ProcessFigures(
            receiptAmount = 100.0,
            lines = listOf(
                ProcessLine(id = "p", account = "4100", net = 100.0),
                ProcessLine(id = "c1", account = "4100", net = 50.0, splitParentId = "p"),
                ProcessLine(id = "c2", account = "4100", net = 50.0, splitParentId = "p"),
            ),
            fixedLines = emptyList(),
            cardLimit = null,
            cardBalance = null,
        )
        assertEquals(100.0, figures.gross)
        assertFalse(figures.mismatch)
    }

    @Test
    fun `the reclaimable tax follows recoverable lines until it is typed over`() {
        val types = listOf(CardTaxType("vat_20", "VAT", 20.0, recoverable = true), CardTaxType("exempt", "Exempt", 0.0))
        val base = ProcessFigures(
            receiptAmount = 120.0,
            lines = listOf(ProcessLine(id = "a", account = "4100", net = 100.0, taxRate = 20.0, taxType = "vat_20")),
            fixedLines = emptyList(),
            cardLimit = null,
            cardBalance = null,
            taxTypes = types,
            taxTypesKnown = true,
        )
        assertEquals(20.0, base.reclaimableTax)
        assertTrue(base.sendsTaxLine)
        assertEquals(120.0, base.gross)
        val uncoded = base.copy(taxLine = TaxLineDraft(account = ""))
        assertEquals(listOf(2), uncoded.linesMissingNominal, "the tax row needs a code too")

        val overridden = base.copy(taxLine = TaxLineDraft(account = "2200", amount = 15.0, overridden = true))
        assertEquals(15.0, overridden.effectiveTax)
        assertEquals(115.0, overridden.gross, "the typed amount stands in for the derived one")
        assertTrue(overridden.linesMissingNominal.isEmpty())
    }

    @Test
    fun `splitting halves a line, and a parent edit re-cuts and re-codes its children`() {
        var next = 0
        val (split, selected) = ProcessLines.split(
            listOf(ProcessLine(id = "p", account = "4100", net = 101.0)),
            "p",
        ) { "n${next++}" }
        assertEquals(listOf(50.5, 50.5), split.filter { it.isSplit }.map { it.net })
        assertEquals("n0", selected)

        val edited = ProcessLines.update(split, "p") { it.copy(net = 200.0, account = "4200") }
        assertEquals(listOf(100.0, 100.0), edited.filter { it.isSplit }.map { it.net })
        assertTrue(edited.filter { it.isSplit }.all { it.account == "4200" }, "children that held the old code follow")
    }

    // -- the closed period and the rules -------------------------------------------------

    @Test
    fun `the lock reads its day, its first open day and its default`() {
        val lock = assertNotNull(parseLockRoute(Json.parseToJsonElement("""{"lockedDate":"2026-08-31","tz":"UTC"}""")))
        assertTrue(lock.isLocked(AUG_31_NOON))
        assertFalse(lock.isLocked(AUG_31_NOON + DAY))
        assertEquals("2026-09-01", lock.firstOpenDay)
        assertEquals("2026-09-01", lock.defaultDay("2026-08-20"), "never a default behind the cap")
        assertEquals("2026-09-24", lock.defaultDay("2026-09-24"))
        assertFalse(CardPeriodLock().isLocked(AUG_31_NOON))
    }

    @Test
    fun `bulk rows are the viewer's own, out of the closed period`() {
        val lock = CardPeriodLock("2026-08-31", "UTC")
        val mine = receipt("r1", assignedTo = "junior-1").copy(date = AUG_31_NOON + DAY)
        val locked = mine.copy(id = "r2", date = AUG_31_NOON)
        assertTrue(ProcessRules.bulkSelectable(junior, mine, lock))
        assertFalse(ProcessRules.bulkSelectable(junior, locked, lock))
        assertFalse(ProcessRules.bulkSelectable(junior, mine.copy(assignedTo = null), lock), "unassigned is a senior's")
        assertTrue(ProcessRules.bulkSelectable(senior, mine.copy(assignedTo = null), lock))
    }

    @Test
    fun `a rule banner reads as the web words it`() {
        val review = ProcessingFlag("review", title = "High value", thresholdValue = 500.0)
        assertEquals(
            "Senior review required because receipt amount is more than £500.00 as per High value rule.",
            ruleHeadline(review) { "£500.00" },
        )
        val deduct = ProcessingFlag("deduct", title = "Fuel rule", thresholdValue = 10.0, thresholdType = "percentage")
        assertEquals("10% deduction applied as per Fuel rule.", ruleHeadline(deduct) { it.toString() })
        assertEquals("Query required as per rule.", ruleHeadline(ProcessingFlag("query")) { it.toString() })
    }

    @Test
    fun `the chart offers postable codes and the tax types carry their rates`() {
        val chart = parseChart(
            Json.parseToJsonElement(
                """[{"code":"4100","name":"Props","line_type":"category"},
                    {"code":"4000","name":"Head","line_type":"header"},
                    {"code":"4200","name":"Off","line_type":"sub_category","posting_box":false}]""",
            ),
        )
        assertEquals(listOf("4100"), chart.map { it.code })
        val types = parseTaxTypes(
            Json.parseToJsonElement(
                """{"tax_types":[{"identifier":"vat_20","label":"VAT","value":20,"is_recoverable":true}]}""",
            ).jsonObject,
        )
        assertEquals(CardTaxType("vat_20", "VAT", 20.0, recoverable = true), types.single())
    }

    // -- the handlers ---------------------------------------------------------------------

    @Test
    fun `approve signs the next tier, and only for the next approver`() = runTest(dispatcher) {
        val chain = TierConfig(
            scope = "all",
            departmentId = null,
            tiers = listOf(
                ApprovalTier(1, listOf(TierRule("default", null, listOf("first")))),
                ApprovalTier(2, listOf(TierRule("default", null, listOf("junior-1")))),
            ),
        )
        val signedOnce = receipt("r1", assignedTo = null).copy(
            status = CardWorkflowStatus.AwaitingApproval,
            approvals = listOf(CardApproval("first", 1)),
        )
        val repository = FakeCardRepository(
            metadata = CardMetadata(tierConfigs = listOf(chain)),
            receipts = listOf(signedOnce, signedOnce.copy(id = "r2", approvals = emptyList())),
        )
        val vm = viewModel(repository, junior)
        vm.onEvent(CardEvent.Open(CardDestination.ApprovalQueue))
        advanceUntilIdle()

        vm.onEvent(ProcessPageEvent.ApproveRow("r1"))
        vm.onEvent(ProcessPageEvent.ApproveRow("r2"))
        advanceUntilIdle()
        assertEquals(listOf("approveReceipt:2"), repository.calls.filter { it.startsWith("approveReceipt") })

        vm.onEvent(ProcessPageEvent.OverrideRow("r2"))
        advanceUntilIdle()
        assertTrue("overrideReceipt" !in repository.calls, "no override grant")
    }

    @Test
    fun `a refused save keeps the editor open on what was typed`() = runTest(dispatcher) {
        val repository = FakeCardRepository(receipts = listOf(receipt("r1", assignedTo = "junior-1")))
        repository.refuseSave = "Period locked"
        val vm = viewModel(repository, junior)
        vm.onEvent(CardEvent.Open(CardDestination.ProcessQueue))
        advanceUntilIdle()
        vm.onEvent(CardEvent.OpenProcess("r1"))
        advanceUntilIdle()

        vm.onEvent(CardEvent.SaveProcess)
        advanceUntilIdle()
        assertEquals(1, repository.submissions.size)
        assertNotNull(vm.state.value.process, "the editor stays for the correction")
    }

    @Test
    fun `a locked approval row cannot be ticked`() = runTest(dispatcher) {
        val repository = FakeCardRepository(
            receipts = listOf(receipt("r1", assignedTo = null).copy(date = AUG_31_NOON)),
        )
        repository.processRefs = ProcessRefs(loaded = true, lock = CardPeriodLock("2026-08-31", "UTC"))
        val vm = viewModel(repository, junior)
        vm.onEvent(CardEvent.Open(CardDestination.ApprovalQueue))
        advanceUntilIdle()

        vm.onEvent(ProcessPageEvent.ToggleRow("r1"))
        vm.onEvent(ProcessPageEvent.ToggleAllRows(listOf("r1")))
        assertTrue(vm.state.value.selection.isEmpty())
    }

    @Test
    fun `bulk process lists the approved rows of the process queue`() = runTest(dispatcher) {
        val repository = FakeCardRepository(
            receipts = listOf(
                receipt("r1", assignedTo = "junior-1"),
                receipt("r2", assignedTo = "junior-1").copy(status = CardWorkflowStatus.UnderReview),
            ),
        )
        val vm = viewModel(repository, junior)
        vm.onEvent(CardEvent.Open(CardDestination.BulkProcess))
        advanceUntilIdle()
        assertEquals(listOf("r1"), vm.state.value.receipts.map { it.id })
        assertNull(vm.state.value.process)
    }

    // -- helpers --------------------------------------------------------------------------

    private val senior = CardViewer(
        userId = "senior-1",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_production_accountant",
    )

    private val junior = CardViewer(
        userId = "junior-1",
        departmentIdentifier = "department_accounts",
        designationIdentifier = "designation_assistant_accountant",
    )

    private fun viewModel(repository: FakeCardRepository, viewer: CardViewer) =
        CardExpensesViewModel(repository = repository, today = { AUG_31_NOON + DAY * 3 }, viewer = { viewer }).also {
            it.start()
            dispatcher.scheduler.advanceUntilIdle()
        }

    private fun receipt(id: String, assignedTo: String?) = CardReceipt(
        id = id,
        cardId = "c1",
        holderId = "crew-1",
        holderName = "",
        description = "Batteries",
        merchant = null,
        amount = 120.0,
        currency = "GBP",
        date = AUG_31_NOON + DAY * 2,
        status = CardWorkflowStatus.Approved,
        matchStatus = MatchStatus.Matched,
        transactionId = null,
        transactionMerchant = null,
        transactionAmount = null,
        transactionDate = null,
        transactionCardLastFour = null,
        nominalCode = "4100",
        codeDescription = null,
        episode = null,
        attachmentKey = null,
        urgent = false,
        matchScore = null,
        duplicateScore = null,
        duplicateDismissed = false,
        personalScore = null,
        personalDismissed = false,
        createdAt = null,
        processing = ReceiptProcessing(
            loaded = true,
            lines = listOf(ProcessLine(id = "l1", description = "Batteries", account = "4100", net = 120.0)),
        ),
        assignedTo = assignedTo,
    )

    private fun repository(): Pair<CardRepositoryImpl, MutableList<Pair<String, JsonObject>>> {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val engine = MockEngine { request ->
            val body = (request.body as? TextContent)?.text?.let { Json.parseToJsonElement(it) as JsonObject }
            calls += request.url.toString() to (body ?: JsonObject(emptyMap()))
            respond(
                """{"status":1,"data":{"succeeded":1,"failed":0}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repo = CardRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ ProcessEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
        )
        return repo to calls
    }

    private companion object {
        /** 2026-08-31 12:00 UTC. */
        const val AUG_31_NOON = 1_788_177_600_000L
        const val DAY = 86_400_000L
    }
}

private class ProcessEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
