package com.zillit.desktop.feature.cardexpenses

import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.cardexpenses.data.CardRepositoryImpl
import com.zillit.desktop.feature.cardexpenses.domain.AttachmentChange
import com.zillit.desktop.feature.cardexpenses.domain.CardAttachment
import com.zillit.desktop.feature.cardexpenses.domain.CardCompanies
import com.zillit.desktop.feature.cardexpenses.domain.CardCompanyRef
import com.zillit.desktop.feature.cardexpenses.domain.CardCrewHost
import com.zillit.desktop.feature.cardexpenses.domain.CardDates
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardStatus
import com.zillit.desktop.feature.cardexpenses.domain.CardType
import com.zillit.desktop.feature.cardexpenses.domain.CardViewer
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.domain.CrewRules
import com.zillit.desktop.feature.cardexpenses.domain.DraftCardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.domain.MatchStatus
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptCategory
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptCoding
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptEdit
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptScope
import com.zillit.desktop.feature.cardexpenses.ui.CardDestination
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardExpensesViewModel
import com.zillit.desktop.feature.cardexpenses.ui.CrewEvent
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
 * The cardholder pages against the web: the receipt wire (batch, edit,
 * attachment model), the approval-queue read, and the crew rules the handlers
 * hold whatever the screen offered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CardCrewParityTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // -- wire ------------------------------------------------------------------

    /** `UserReceiptsPage.jsx:354-368`: the form's `YYYY-MM-DD`, the whole AttachmentModel, explicit nulls. */
    @Test
    fun `a batch row sends the date as the form's day and the attachment as a model`() = runTest {
        val (repo, sent) = repository()
        repo.submitReceipts(
            card = card().copy(companyId = null),
            receipts = listOf(
                DraftCardReceipt(
                    description = "Gaffer tape",
                    amount = "12.5",
                    date = CardDates.toMillis("2026-09-20"),
                    attachmentKey = "card-expenses/x/tape.jpg",
                    attachment = CardAttachment(
                        key = "card-expenses/x/tape.jpg",
                        fileName = "tape.jpg",
                        bucket = "zillit-dev",
                        region = "eu-west-2",
                        contentType = "image/jpeg",
                        contentSubtype = "jpg",
                    ),
                ),
            ),
        )
        val row = (sent.single().body["receipts"] as JsonArray).single().jsonObject
        assertEquals("2026-09-20", row["date"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, row["company_id"])
        assertEquals("", row["cost_code"]?.jsonPrimitive?.content)
        assertEquals("", row["episode"]?.jsonPrimitive?.content)
        assertEquals("", row["coded_description"]?.jsonPrimitive?.content)
        val model = row["receipt_attachment"] as JsonObject
        assertEquals("card-expenses/x/tape.jpg", model["media"]?.jsonPrimitive?.content)
        assertEquals("zillit-dev", model["bucket"]?.jsonPrimitive?.content)
        assertEquals("eu-west-2", model["region"]?.jsonPrimitive?.content)
        assertEquals("tape.jpg", model["name"]?.jsonPrimitive?.content)
        assertEquals("image/jpeg", model["content_type"]?.jsonPrimitive?.content)
        assertEquals("jpg", model["content_subtype"]?.jsonPrimitive?.content)
    }

    /** `UserReceiptsPage.jsx:1451-1475`: blank coding left out; the file key only when it changed. */
    @Test
    fun `the receipt edit patches the web's keys and the attachment three ways`() = runTest {
        val (repo, sent) = repository()
        val base = ReceiptEdit(
            description = "Taxi",
            amount = 18.0,
            date = 1_758_326_400_000,
            cardId = "c1",
            currency = "GBP",
            nominalCode = "",
            episode = "",
            codeDescription = "",
            category = ReceiptCategory.Travel,
            urgent = true,
            requestTopUp = false,
            attachment = AttachmentChange.Keep,
        )
        repo.updateReceipt("r1", base)
        repo.updateReceipt("r1", base.copy(attachment = AttachmentChange.Remove))
        repo.updateReceipt("r1", base.copy(attachment = AttachmentChange.Replace(CardAttachment("k", "f.pdf"))))

        val (keep, remove, replace) = sent
        assertEquals("PATCH", keep.method)
        assertTrue(keep.url.endsWith("/receipts/r1"))
        assertEquals("travel", keep.body["category"]?.jsonPrimitive?.content)
        assertEquals("true", keep.body["is_urgent"]?.jsonPrimitive?.content)
        assertEquals(1_758_326_400_000, keep.body["date"]?.jsonPrimitive?.content?.toLong())
        listOf("nominal_code", "episode", "code_description", "receipt_attachment").forEach {
            assertFalse(it in keep.body, "$it is left out")
        }
        assertEquals(JsonNull, remove.body["receipt_attachment"])
        assertEquals("k", (replace.body["receipt_attachment"] as JsonObject)["media"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the crew approval queue asks the server for this approver's pending cards`() = runTest {
        val (repo, sent) = repository(body = """{"status":1,"data":[]}""")
        repo.cardsForApproval()
        val url = sent.single().url
        assertTrue("status=pending" in url && "for_approval=true" in url, url)
    }

    /** `CodingQueuePage.jsx:88`: Save Draft sends `costCode || undefined`, never a blank code. */
    @Test
    fun `a draft save with no code leaves nominal_code out`() = runTest {
        val (repo, sent) = repository()
        repo.updateReceiptCoding("r1", ReceiptCoding(nominalCode = " ", cardId = "c1", currency = "GBP"))
        val body = sent.single().body
        assertFalse("nominal_code" in body)
        assertEquals("c1", body["card_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a web-uploaded receipt reads its media key and name`() = runTest {
        val (repo, _) = repository(
            body = """{"status":1,"data":[{"id":"r1","status":"pending_code","receipt_attachment":
                {"media":"uploads/a.png","bucket":"b","region":"r","name":"a.png"},"rejection_reason":"Blurry",
                "category":"fuel"}]}""",
        )
        val receipt = repo.receipts(ReceiptScope.Mine).getOrNull().orEmpty().single()
        assertEquals("uploads/a.png", receipt.attachmentKey)
        assertEquals("a.png", receipt.attachmentName)
        assertEquals("Blurry", receipt.rejectionReason)
        assertEquals("fuel", receipt.category)
    }

    @Test
    fun `a top-up row's stringified history reads as its trail`() = runTest {
        val (repo, _) = repository(
            body = """{"status":1,"data":[{"id":"t1","amount":"50","status":"pending","history":
                "[{\"action\":\"requested\",\"action_by\":\"u1\",\"action_at\":1758326400000,
                \"reason\":\"Fuel\",\"amount\":50}]"}]}""",
        )
        val trail = repo.cardTopUps("c1").getOrNull().orEmpty().single().trail.single()
        assertEquals("requested", trail.action)
        assertEquals("u1", trail.userId)
        assertEquals("Fuel", trail.reason)
        assertEquals(50.0, trail.amount)
    }

    // -- rules -------------------------------------------------------------------

    @Test
    fun `the batch is refused in the web's order`() {
        val now = CardDates.toMillis("2026-09-24")!! + HOUR
        val good = DraftCardReceipt(
            description = "Tape",
            amount = "5",
            date = CardDates.toMillis("2026-09-24"),
            attachmentKey = "k",
        )
        assertNull(CrewRules.batchError(listOf(good), now))
        val noFile = CrewRules.batchError(listOf(good.copy(attachmentKey = null, date = null)), now)
        assertTrue(noFile!!.contains("attachment"))
        assertTrue(CrewRules.batchError(listOf(good.copy(date = null, description = "")), now)!!.contains("date"))
        val noText = CrewRules.batchError(listOf(good.copy(description = "", amount = "0")), now)
        assertTrue(noText!!.contains("description"))
        assertTrue(CrewRules.batchError(listOf(good, good.copy(amount = "")), now)!!.startsWith("Receipt 2"))
        assertTrue(
            CrewRules.batchError(listOf(good.copy(date = CardDates.toMillis("2026-09-25"))), now)!!.contains("future"),
        )
    }

    @Test
    fun `the company is the card's pin, else the owner of its bank`() {
        val companies = listOf(CardCompanyRef("co-1", listOf("bank-9")), CardCompanyRef("co-2", listOf("bank-1")))
        assertEquals("pin", CardCompanies.resolve(card().copy(companyId = "pin", issuer = "bank-1"), companies))
        assertEquals("co-2", CardCompanies.resolve(card().copy(companyId = null, issuer = "bank-1"), companies))
        assertNull(CardCompanies.resolve(card().copy(companyId = null, issuer = "bank-x"), companies))
    }

    @Test
    fun `only an active card counts, and approved or posted receipts stay`() {
        assertNull(CrewRules.activeCard(listOf(card().copy(status = CardStatus.Pending))))
        assertFalse(CrewRules.canDelete(receipt(CardWorkflowStatus.Approved)))
        assertFalse(CrewRules.canDelete(receipt(CardWorkflowStatus.Posted)))
        assertTrue(CrewRules.canDelete(receipt(CardWorkflowStatus.AwaitingApproval)))
    }

    // -- handlers ----------------------------------------------------------------

    @Test
    fun `saving a rejected receipt patches it and then resubmits it`() = runTest(dispatcher) {
        val repository = FakeCardRepository(
            receipts = listOf(receipt(CardWorkflowStatus.Rejected).copy(attachmentKey = "k")),
            cards = listOf(card()),
        )
        val vm = viewModel(repository)
        vm.onEvent(CrewEvent.OpenReceipt("r1"))
        val draft = assertNotNull(vm.state.value.crew.edit, "a rejected card opens the edit")
        vm.onEvent(CrewEvent.EditReceipt(draft.copy(amount = "40")))
        vm.onEvent(CrewEvent.SaveEdit)
        advanceUntilIdle()

        assertEquals(listOf("updateReceipt", "confirmReceipt"), repository.calls.filter { "Receipt" in it })
        assertEquals(AttachmentChange.Keep, repository.receiptEdits.single().second.attachment)
        assertNull(vm.state.value.crew.edit)
    }

    @Test
    fun `an edit past the card's headroom is refused`() = runTest(dispatcher) {
        val repository = FakeCardRepository(
            receipts = listOf(receipt(CardWorkflowStatus.PendingCode).copy(attachmentKey = "k")),
            cards = listOf(card().copy(limit = 100.0, receiptsCommit = 90.0)),
        )
        val vm = viewModel(repository)
        vm.onEvent(CrewEvent.OpenEdit("r1"))
        val draft = assertNotNull(vm.state.value.crew.edit)
        vm.onEvent(CrewEvent.EditReceipt(draft.copy(amount = "31")))
        vm.onEvent(CrewEvent.SaveEdit)
        advanceUntilIdle()
        assertTrue("updateReceipt" !in repository.calls)
    }

    @Test
    fun `the upload goes against the active card with the resolved company`() = runTest(dispatcher) {
        val repository = FakeCardRepository(
            cards = listOf(card().copy(status = CardStatus.Pending, id = "old"), card().copy(companyId = null)),
        )
        val host = CardCrewHost(
            companies = { listOf(CardCompanyRef("co-7", listOf("bank-1"))) },
            now = { CardDates.toMillis("2026-09-24")!! },
        )
        val vm = viewModel(repository, host)
        vm.onEvent(CrewEvent.Prime)
        advanceUntilIdle()
        vm.onEvent(CrewEvent.OpenUpload)
        assertTrue(vm.state.value.crew.fullScreen, "the upload page takes the content area")
        vm.onEvent(
            CardEvent.EditDraftReceipt(
                0,
                DraftCardReceipt(
                    description = "Tape",
                    amount = "5",
                    date = CardDates.toMillis("2026-09-23"),
                    attachmentKey = "k",
                ),
            ),
        )
        vm.onEvent(CrewEvent.SubmitUpload)
        advanceUntilIdle()

        val (sentCard, rows) = repository.batches.single()
        assertEquals("c1", sentCard?.id)
        assertEquals("co-7", sentCard?.companyId)
        assertEquals(1, rows.size)
        assertFalse(vm.state.value.crew.uploadOpen)
    }

    @Test
    fun `no active card, no upload page`() = runTest(dispatcher) {
        val repository = FakeCardRepository(cards = listOf(card().copy(status = CardStatus.Suspended)))
        val vm = viewModel(repository)
        vm.onEvent(CrewEvent.OpenUpload)
        assertFalse(vm.state.value.crew.uploadOpen)
    }

    @Test
    fun `an approved receipt cannot be deleted even if asked`() = runTest(dispatcher) {
        val repository = FakeCardRepository(
            receipts = listOf(receipt(CardWorkflowStatus.Approved)),
            cards = listOf(card()),
        )
        val vm = viewModel(repository)
        vm.onEvent(CrewEvent.AskDelete("r1"))
        vm.onEvent(CrewEvent.ConfirmDelete)
        advanceUntilIdle()
        assertTrue("deleteReceipt" !in repository.calls)
    }

    // -- fixtures ----------------------------------------------------------------

    private val crew = CardViewer(
        userId = "crew-1",
        departmentIdentifier = "department_art",
        designationIdentifier = null,
    )

    private fun viewModel(repository: FakeCardRepository, host: CardCrewHost = CardCrewHost()) =
        CardExpensesViewModel(repository = repository, crewHost = host, viewer = { crew }).also {
            it.start()
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(CardDestination.MyTransactions, it.state.value.destination)
        }

    private fun card() = ExpenseCard(
        id = "c1",
        holderId = "crew-1",
        holderName = "",
        departmentId = "d-art",
        companyId = null,
        status = CardStatus.Active,
        type = CardType.Physical,
        lastFour = "4821",
        issuer = "bank-1",
        providerId = null,
        currency = "GBP",
        limit = 500.0,
        monthlyLimit = 500.0,
        balance = 500.0,
        receiptsCommit = 0.0,
        bsControlCode = "2100",
        proposedLimit = null,
        justification = null,
        requestedBy = "crew-1",
        rejectedBy = null,
        rejectionReason = null,
        createdAt = null,
    )

    private fun receipt(status: CardWorkflowStatus) = CardReceipt(
        id = "r1",
        cardId = "c1",
        holderId = "crew-1",
        holderName = "",
        description = "Taxi",
        merchant = null,
        amount = 20.0,
        currency = "GBP",
        date = CardDates.toMillis("2026-09-20"),
        status = status,
        matchStatus = MatchStatus.Unmatched,
        transactionId = null,
        transactionMerchant = null,
        transactionAmount = null,
        transactionDate = null,
        transactionCardLastFour = null,
        nominalCode = null,
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
    )

    private class Sent(val method: String, val url: String, val body: JsonObject)

    private fun repository(body: String = """{"status":1,"data":{}}"""): Pair<CardRepositoryImpl, MutableList<Sent>> {
        val sent = mutableListOf<Sent>()
        val engine = MockEngine { request ->
            val json = (request.body as? TextContent)?.text?.let { Json.parseToJsonElement(it) as JsonObject }
            sent += Sent(request.method.value, request.url.toString(), json ?: JsonObject(emptyMap()))
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val repo = CardRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ CrewMockEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
        )
        return repo to sent
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}

private class CrewMockEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
