package com.zillit.desktop.feature.cardexpenses

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.feature.cardexpenses.data.CardRepositoryImpl
import com.zillit.desktop.feature.cardexpenses.data.importBody
import com.zillit.desktop.feature.cardexpenses.data.route
import com.zillit.desktop.feature.cardexpenses.domain.CardBank
import com.zillit.desktop.feature.cardexpenses.domain.CardProvider
import com.zillit.desktop.feature.cardexpenses.domain.CardRepository
import com.zillit.desktop.feature.cardexpenses.domain.CardTransaction
import com.zillit.desktop.feature.cardexpenses.domain.CardViewer
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.domain.InboxWrite
import com.zillit.desktop.feature.cardexpenses.domain.MatchStatus
import com.zillit.desktop.feature.cardexpenses.domain.ReconciliationBadge
import com.zillit.desktop.feature.cardexpenses.domain.ServerOutcome
import com.zillit.desktop.feature.cardexpenses.domain.StatementCurrencyOptions
import com.zillit.desktop.feature.cardexpenses.domain.StoredStatement
import com.zillit.desktop.feature.cardexpenses.domain.TransactionSelection
import com.zillit.desktop.feature.cardexpenses.domain.statementFileError
import com.zillit.desktop.feature.cardexpenses.ui.CardDestination
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardExpensesViewModel
import com.zillit.desktop.feature.cardexpenses.ui.InboxEvent
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Import Statement, the Receipt Inbox and All Transactions: the wire the web
 * speaks, the display rules it applies, and the bulk delete's selection
 * contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CardInboxTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // -- the badge rule --------------------------------------------------------

    @Test
    fun `the inbox badge ranks unreconciled, then suggested, then reconciled, then workflow`() {
        assertEquals(ReconciliationBadge.Unreconciled, ReconciliationBadge.inbox(MatchStatus.Unmatched, true))
        assertEquals(ReconciliationBadge.MatchSuggested, ReconciliationBadge.inbox(MatchStatus.Suggested, true))
        assertEquals(ReconciliationBadge.MatchSuggested, ReconciliationBadge.inbox(MatchStatus.Suggested, false))
        assertEquals(ReconciliationBadge.Reconciled, ReconciliationBadge.inbox(MatchStatus.Matched, false))
        assertNull(ReconciliationBadge.inbox(MatchStatus.Matched, true))
    }

    @Test
    fun `the crew never sees a suggestion or reconciled`() {
        assertEquals(ReconciliationBadge.Unreconciled, ReconciliationBadge.crew(MatchStatus.Unmatched))
        assertNull(ReconciliationBadge.crew(MatchStatus.Suggested))
        assertNull(ReconciliationBadge.crew(MatchStatus.Matched))
    }

    // -- the wire ----------------------------------------------------------------

    @Test
    fun `each inbox write goes where the web sends it`() {
        assertEquals(
            Triple(HttpVerb.Post, "/receipts/r1/confirm-match", null),
            InboxWrite.ConfirmMatch("r1").route(),
        )
        val manual = InboxWrite.ManualMatch("r1", "t9").route()
        assertEquals("/receipts/r1/manual-match", manual.second)
        assertEquals("t9", manual.third?.get("transactionId")?.jsonPrimitive?.content)
        assertEquals("/transactions/t1/flag-personal", InboxWrite.FlagTransactionPersonal("t1").route().second)
        assertEquals("/receipts/r1/flag-personal", InboxWrite.FlagReceiptPersonal("r1").route().second)
        // Re-run carries no statement: the web's `rerunMatch()` encodes `{}`.
        val rerun = InboxWrite.RerunMatch.route()
        assertEquals("/receipts/rerun-match", rerun.second)
        assertEquals(emptySet(), rerun.third?.keys)
        assertEquals(HttpVerb.Delete, InboxWrite.DeleteTransaction("t1").route().first)
    }

    @Test
    fun `a statement import sends the whole attachment model`() {
        val body = importBody(
            StoredStatement("card-expenses/x/march.csv", "zillit-bucket", "eu-west-2", "march.csv", "document", "csv"),
            currency = "GBP",
        )
        val attachment = body["attachment"]!!.jsonObject
        assertEquals(
            setOf("media", "bucket", "region", "name", "content_type", "content_subtype"),
            attachment.keys,
        )
        assertEquals("card-expenses/x/march.csv", attachment["media"]?.jsonPrimitive?.content)
        assertEquals("GBP", body["currency"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the import answer is read into the statement, its summary and its rows`() = runTest {
        val repo = repositoryAnswering(
            """{"status":1,"message":"statement_imported","data":{
              "import":{"id":"i1","file_name":"march.csv","issuer":"Barclaycard",
                        "sort_code":"20-00-00","currency":"EUR"},
              "summary":{"total_rows":3,"new_count":2,"duplicate_count":1,"declined_count":0},
              "processed":{"rowsProcessed":2},
              "rows":[{"id":"t1","row_index":1,"merchant_raw":"TESCO","userId":"u1","cardLastFour":"0000",
                       "amount":"12.50","status":"new"},
                      {"id":"t2","row_index":2,"merchant":"SHELL","user_id":"u2","card_last_four":"4821",
                       "amount":40,"currency":"GBP","status":"duplicate"}]}}""",
        )

        val answered = repo.importStatementFile(StoredStatement("k", null, null, "march.csv", "document", "csv"), null)
        val outcome = (answered as ZillitResult.Success).data
        val result = outcome.data
        assertEquals("march.csv", result.info.fileName)
        assertEquals("EUR", result.info.currency)
        assertEquals(2, result.rowsProcessed)
        assertEquals(1, result.summary.duplicateCount)
        val first = result.rows.first()
        assertEquals("TESCO", first.merchant)
        assertEquals("u1", first.holderId)
        assertNull(first.cardLastFour, "0000 names no card")
        assertTrue(first.isNew)
        assertEquals("4821", result.rows[1].cardLastFour)
        assertTrue(outcome.message!!.isNotBlank())
    }

    @Test
    fun `a bulk delete with no count is a delete and an explicit zero is nothing`() = runTest {
        val absent = repositoryAnswering("""{"status":1,"message":"bulk_deleted","data":{}}""")
        assertEquals(1, (absent.bulkDeleteTransactionsCounted(listOf("t1")) as ZillitResult.Success).data.data)

        val zero = repositoryAnswering("""{"status":1,"data":{"deletedTransactions":0}}""")
        assertEquals(0, (zero.bulkDeleteTransactionsCounted(listOf("t1")) as ZillitResult.Success).data.data)
    }

    @Test
    fun `a status zero inside a 200 is a refusal`() = runTest {
        val repo = repositoryAnswering("""{"status":0,"message":"receipt_not_found"}""")
        assertIs<ZillitResult.Failure>(repo.inboxWrite(InboxWrite.ConfirmMatch("r1")))
    }

    @Test
    fun `match candidates read the merchant name and a fractional score`() = runTest {
        val repo = repositoryAnswering(
            """{"status":1,"data":[{"id":"t1","merchant_name":"TESCO","card_last_four":"0000",
               "amount":"9.99","match_score":0.63}]}""",
        )
        val candidate = (repo.inboxMatchCandidates("r1") as ZillitResult.Success).data.single()
        assertEquals("TESCO", candidate.merchant)
        assertEquals(63, candidate.scorePercent)
        assertNull(candidate.cardLastFour)
    }

    @Test
    fun `the receipt detail drops tax lines and marks overrides`() = runTest {
        val repo = repositoryAnswering(
            """{"status":1,"data":{"id":"r1","description":"Batteries","amount":"12","match_status":"suggested_match",
               "receipt_attachment":{"media":"card-expenses/r1.pdf","name":"r1.pdf","bucket":"b","region":"r"},
               "line_items":[{"account":"4100","description":"Batteries","amount":"12","tax_amount":"2",
                              "tags":"[\"ASSET\"]"},
                             {"description":"VAT","amount":"2","is_tax":true},
                             {"description":"Half","amount":"6","split_parent_id":"l1"}],
               "approvals":[{"user_id":"u1","tier_number":1},{"user_id":"u2","tier_number":0}]}}""",
        )
        val detail = (repo.inboxReceiptDetail("r1") as ZillitResult.Success).data
        assertEquals(MatchStatus.Suggested, detail.receipt.matchStatus)
        assertEquals(2, detail.lines.size, "the consolidated tax line is not a display row")
        assertEquals(10.0, detail.lines.first().net)
        assertEquals(listOf("ASSET"), detail.lines.first().tags)
        assertTrue(detail.lines[1].splitChild)
        assertEquals(listOf(false, true), detail.approvals.map { it.override })
        assertEquals("r1.pdf", detail.media?.fileName)
        assertTrue(detail.media!!.isPdf)
    }

    // -- selection and files ---------------------------------------------------------

    @Test
    fun `delete sends the visible deletable selection in table order`() {
        val rows = listOf(txn("a"), txn("b", CardWorkflowStatus.Posted), txn("c"), txn("d"))
        val visible = TransactionSelection.selectableIds(rows)
        assertEquals(listOf("a", "c", "d"), visible)
        // "z" was ticked behind a search; it is kept but never sent.
        assertEquals(listOf("a", "d"), TransactionSelection.visibleSelection(setOf("d", "z", "a"), visible))
        val all = TransactionSelection.toggleAll(setOf("z"), visible)
        assertEquals(setOf("z", "a", "c", "d"), all)
        assertEquals(setOf("z"), TransactionSelection.toggleAll(all, visible))
    }

    @Test
    fun `only csv, ofx and qif can be imported`() {
        assertNull(statementFileError("march.CSV"))
        assertNull(statementFileError("a.b.qif"))
        assertNotNull(statementFileError("march.pdf"))
        assertNotNull(statementFileError("statement"))
    }

    @Test
    fun `a single complete currency is taken and anything less is a choice`() {
        val banks = listOf(CardBank("b1", "Barclays", "GBP"), CardBank("b2", "HSBC", "EUR"))
        val one = StatementCurrencyOptions.of(listOf(provider("b1")), banks, listOf("USD"))
        assertEquals(listOf("GBP"), one.codes)
        assertEquals("GBP", one.resolve(""))

        val two = StatementCurrencyOptions.of(listOf(provider("b1"), provider("b2")), banks, emptyList())
        assertEquals("", two.resolve(""))
        assertEquals("EUR", two.resolve("EUR"))

        // A provider whose bank did not resolve makes the list incomplete.
        val partial = StatementCurrencyOptions.of(listOf(provider("b1"), provider("gone")), banks, emptyList())
        assertEquals("", partial.resolve(""))

        // No provider currencies: the project's.
        assertEquals(listOf("USD", "GBP"), StatementCurrencyOptions.of(emptyList(), banks, listOf("USD", "GBP")).codes)
    }

    // -- the view model ------------------------------------------------------------

    @Test
    fun `a bulk delete that removed nothing keeps the selection`() = runTest(dispatcher) {
        val fake = InboxFake(deleted = 0)
        val vm = viewModel(fake)
        vm.onEvent(InboxEvent.ToggleTransaction("a"))
        vm.onEvent(InboxEvent.ConfirmBulkDelete(listOf("a")))
        advanceUntilIdle()
        assertEquals(setOf("a"), vm.state.value.selection)
        assertEquals(listOf(listOf("a")), fake.bulkDeletes)
    }

    @Test
    fun `a bulk delete drops only what it sent`() = runTest(dispatcher) {
        val vm = viewModel(InboxFake(deleted = 1))
        vm.onEvent(InboxEvent.ToggleTransaction("a"))
        vm.onEvent(InboxEvent.ToggleTransaction("hidden"))
        vm.onEvent(InboxEvent.ConfirmBulkDelete(listOf("a")))
        advanceUntilIdle()
        assertEquals(setOf("hidden"), vm.state.value.selection)
        assertEquals("deleted", vm.state.value.notice)
    }

    @Test
    fun `attach confirms the suggestion with no prompt and shows the server's message`() = runTest(dispatcher) {
        val fake = InboxFake()
        val vm = viewModel(fake)
        vm.onEvent(InboxEvent.Attach("r1"))
        advanceUntilIdle()
        assertEquals(listOf<InboxWrite>(InboxWrite.ConfirmMatch("r1")), fake.writes)
        assertNull(vm.state.value.prompt)
        assertEquals("done", vm.state.value.notice)
    }

    // -- harness -------------------------------------------------------------------

    private class InboxFake(
        private val deleted: Int = 1,
        private val base: FakeCardRepository = FakeCardRepository(),
    ) : CardRepository by base {
        val writes = mutableListOf<InboxWrite>()
        val bulkDeletes = mutableListOf<List<String>>()

        override suspend fun inboxWrite(write: InboxWrite): ZillitResult<String?> {
            writes += write
            return ZillitResult.Success("done")
        }

        override suspend fun bulkDeleteTransactionsCounted(
            transactionIds: List<String>,
        ): ZillitResult<ServerOutcome<Int>> {
            bulkDeletes += transactionIds
            return ZillitResult.Success(ServerOutcome(deleted, "deleted"))
        }
    }

    private fun viewModel(repository: CardRepository) = CardExpensesViewModel(
        repository = repository,
        viewer = {
            CardViewer(
                userId = "acc-1",
                departmentIdentifier = "department_accounts",
                designationIdentifier = "designation_production_accountant",
            )
        },
    ).also {
        it.start()
        it.onEvent(CardEvent.Open(CardDestination.AllTransactions))
        dispatcher.scheduler.advanceUntilIdle()
    }

    private fun provider(bankId: String) = CardProvider(id = "p-$bankId", name = "Provider", bankId = bankId)

    private fun txn(id: String, status: CardWorkflowStatus = CardWorkflowStatus.New) = CardTransaction(
        id = id,
        cardId = null,
        cardLastFour = null,
        holderId = null,
        holderName = "",
        merchant = "M",
        description = null,
        amount = 1.0,
        currency = null,
        date = null,
        status = status,
        nominalCode = null,
        codeDescription = null,
        episode = null,
        vatAmount = 0.0,
        matchStatus = MatchStatus.Unmatched,
        receiptId = null,
        personal = false,
    )

    private fun repositoryAnswering(json: String): CardRepositoryImpl {
        val engine = MockEngine {
            respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return CardRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ InboxEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
        )
    }
}

private class InboxEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
