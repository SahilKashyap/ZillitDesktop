package com.zillit.desktop.feature.cardexpenses

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.cardexpenses.data.CardBinaryPost
import com.zillit.desktop.feature.cardexpenses.data.CardRepositoryImpl
import com.zillit.desktop.feature.cardexpenses.domain.BulkAction
import com.zillit.desktop.feature.cardexpenses.domain.CardActivation
import com.zillit.desktop.feature.cardexpenses.domain.CardExportRow
import com.zillit.desktop.feature.cardexpenses.domain.CardType
import com.zillit.desktop.feature.cardexpenses.domain.ExportFormat
import com.zillit.desktop.feature.cardexpenses.domain.FixedLine
import com.zillit.desktop.feature.cardexpenses.domain.FundRequestDraft
import com.zillit.desktop.feature.cardexpenses.domain.QueryThread
import com.zillit.desktop.feature.cardexpenses.domain.ProcessLine
import com.zillit.desktop.feature.cardexpenses.domain.ProcessSubmission
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptAssignment
import com.zillit.desktop.feature.cardexpenses.domain.TierVisibility
import com.zillit.desktop.feature.cardexpenses.domain.TopUpMethod
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The routes and bodies the web-parity work added, pinned against the web
 * client (`zillit_web/src/accountHub/components/cardExpenses`).
 *
 * Each of these reaches a route that answers `status: 1` whatever it is sent,
 * so a wrong key is a silent loss rather than an error.
 */
class CardParityWireTest {

    // -- processing -------------------------------------------------------------

    /**
     * Posting goes through `save-process` with the whole editor, never `/post`
     * with an empty body (`ProcessReceiptModal.jsx:371-460`).
     */
    @Test
    fun `posting a receipt sends the lines, the ledger date and the top-up`() = runTest {
        val (repo, calls) = repository()
        val auto = buildJsonObject {
            put("description", JsonPrimitive("Auto deduction"))
            put("amount", JsonPrimitive(-5.0))
            put("meta", buildJsonObject { put("auto", JsonPrimitive(true)) })
        }

        repo.saveProcessReceipt(
            "receipt-1",
            ProcessSubmission(
                lines = listOf(
                    ProcessLine("line-1", "Batteries", "4100", net = 100.0, taxRate = 20.0),
                ),
                fixedLines = listOf(FixedLine(auto, gross = -5.0, tax = 0.0, countsInTotal = true)),
                net = 95.0,
                tax = 20.0,
                gross = 115.0,
                description = " Camera Store ",
                nominalCode = "4100",
                effectiveDate = 1_754_006_400_000,
                userId = "user-1",
                status = ProcessSubmission.POSTED,
                topUpMethod = TopUpMethod.Restore,
                topUpAmount = 115.0,
            ),
        )

        val call = calls.single()
        assertEquals("POST", call.method)
        assertTrue(call.url.endsWith("/receipts/receipt-1/save-process"), call.url)
        val body = call.body
        val lines = body.getValue("line_items").jsonArray
        assertEquals(2, lines.size)
        val coded = lines[0].jsonObject
        assertEquals("line-1", coded["id"]?.jsonPrimitive?.content)
        assertEquals(120.0, coded["amount"]?.jsonPrimitive?.content?.toDouble(), "amount is gross")
        assertEquals(100.0, coded["unit_price"]?.jsonPrimitive?.content?.toDouble(), "unit price is net")
        assertEquals(20.0, coded["tax_amount"]?.jsonPrimitive?.content?.toDouble())
        assertEquals("4100", coded["account"]?.jsonPrimitive?.content)
        assertEquals(auto, lines[1].jsonObject, "server-owned lines go back verbatim")
        assertEquals("posted", body["status"]?.jsonPrimitive?.content)
        assertEquals("Camera Store", body["description"]?.jsonPrimitive?.content)
        assertEquals(1_754_006_400_000, body["effective_date"]?.jsonPrimitive?.content?.toLong())
        assertEquals("user-1", body["user_id"]?.jsonPrimitive?.content)
        assertEquals("restore", body["topUpMethod"]?.jsonPrimitive?.content)
        assertEquals(115.0, body["topUpAmount"]?.jsonPrimitive?.content?.toDouble())
        assertEquals(115.0, body["gross_amount"]?.jsonPrimitive?.content?.toDouble())
    }

    /** A plain save carries no status, so the receipt stays where it is. */
    @Test
    fun `a save carries no status and no top-up`() = runTest {
        val (repo, calls) = repository()

        repo.saveProcessReceipt(
            "receipt-1",
            ProcessSubmission(
                lines = listOf(ProcessLine(description = "Tape", account = "", net = 10.0)),
                fixedLines = emptyList(),
                net = 10.0,
                tax = 0.0,
                gross = 10.0,
                description = "",
                nominalCode = "",
                effectiveDate = null,
                userId = "user-1",
            ),
        )

        val body = calls.single().body
        listOf("status", "topUpMethod", "topUpAmount", "effective_date", "description", "nominal_code").forEach {
            assertFalse(it in body, "$it is only sent when there is something to say")
        }
        assertEquals(JsonNull, body.getValue("line_items").jsonArray.single().jsonObject["account"])
    }

    /** The route, not the body, tells "assigned" from "reassigned" (ZL-20749). */
    @Test
    fun `assigning and reassigning use their own routes`() = runTest {
        val (repo, calls) = repository()
        val assignment = ReceiptAssignment("user-3", "user-1", "Workload balancing")

        repo.assignReceipt("receipt-1", assignment, reassign = false)
        repo.assignReceipt("receipt-1", assignment, reassign = true)

        assertTrue(calls[0].url.endsWith("/assignments/receipt-1/assign"), calls[0].url)
        assertTrue(calls[1].url.endsWith("/assignments/receipt-1/reassign"), calls[1].url)
        val body = calls[0].body
        assertEquals("user-3", body["assign_to"]?.jsonPrimitive?.content)
        assertEquals("user-1", body["assigned_by"]?.jsonPrimitive?.content)
        assertEquals("Workload balancing", body["reason"]?.jsonPrimitive?.content)
    }

    // -- the card lifecycle -----------------------------------------------------

    /** An approval says which step of how many it signs (`CardRegisterPage.jsx:408`). */
    @Test
    fun `a card approval carries its tier`() = runTest {
        val (repo, calls) = repository()

        repo.approveCard("card-1", TierVisibility(canApprove = true, nextTier = 2, totalTiers = 3), "user-1")

        val body = calls.single().body
        assertEquals(2, body["tier_number"]?.jsonPrimitive?.content?.toInt())
        assertEquals(3, body["total_tiers"]?.jsonPrimitive?.content?.toInt())
        assertEquals("user-1", body["user_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `rejecting and overriding a card say who and why`() = runTest {
        val (repo, calls) = repository()

        repo.rejectCard("card-1", "Too high", "user-1")
        repo.overrideCard("card-1", "user-1", "Overridden by accountant")

        assertTrue(calls[0].url.endsWith("/cards/card-1/reject"))
        assertEquals("Too high", calls[0].body["reason"]?.jsonPrimitive?.content)
        assertTrue(calls[1].url.endsWith("/cards/card-1/override"))
        assertEquals("user-1", calls[1].body["user_id"]?.jsonPrimitive?.content)
    }

    /**
     * Activation carries the card's type and number, and — for a request
     * raised without a provider — the provider's bank as `card_issuer`.
     */
    @Test
    fun `activation sends the type, the number and the provider binding`() = runTest {
        val (repo, calls) = repository()

        repo.activateCard(
            "card-1",
            CardActivation(
                cardType = CardType.Digital,
                cardNumber = "4000 1234 5678 9010",
                providerId = "prov-1",
                bankId = "bank-1",
                companyId = "company-1",
            ),
        )

        val body = calls.single().body
        assertEquals("digital", body["card_type"]?.jsonPrimitive?.content)
        assertEquals("9010", body["last_four"]?.jsonPrimitive?.content)
        assertEquals("4000123456789010", body["full_card_number"]?.jsonPrimitive?.content)
        assertEquals("prov-1", body["card_provider_id"]?.jsonPrimitive?.content)
        assertEquals("bank-1", body["card_issuer"]?.jsonPrimitive?.content)
        assertEquals("company-1", body["company_id"]?.jsonPrimitive?.content)
    }

    /** A card that already has a provider is not re-pointed on activation. */
    @Test
    fun `activation without a provider leaves the binding alone`() = runTest {
        val (repo, calls) = repository()

        repo.activateCard("card-1", CardActivation(CardType.Physical, "4000123456789010"))

        val body = calls.single().body
        assertFalse("card_provider_id" in body)
        assertFalse("card_issuer" in body)
        assertFalse("company_id" in body)
    }

    // -- approvals and top-ups --------------------------------------------------

    @Test
    fun `a bulk override names its action`() = runTest {
        val (repo, calls) = repository()

        repo.bulkApproval(BulkAction.Override, listOf("r1", "r2"))

        val body = calls.single().body
        assertEquals("override", body["action"]?.jsonPrimitive?.content)
        assertEquals(listOf("r1", "r2"), body.getValue("receiptIds").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `a receipt override says who and why`() = runTest {
        val (repo, calls) = repository()

        repo.overrideReceipt("receipt-1", "user-1", "Accountant override")

        assertEquals("Accountant override", calls.single().body["reason"]?.jsonPrimitive?.content)
    }

    /** The part-payment note is sent; an amount only when one was given (`TopUpToDoPage.jsx:223`). */
    @Test
    fun `a partial top-up sends its note and an optional amount`() = runTest {
        val (repo, calls) = repository()

        repo.partialTopUp("top-1", null, " Card issuer capped the transfer ")
        repo.partialTopUp("top-1", 250.0, "Half now")

        assertEquals("PATCH", calls[0].method)
        assertEquals("Card issuer capped the transfer", calls[0].body["note"]?.jsonPrimitive?.content)
        assertFalse("amount" in calls[0].body)
        assertEquals(250.0, calls[1].body["amount"]?.jsonPrimitive?.content?.toDouble())
    }

    // -- queries and fund requests ----------------------------------------------

    /**
     * The first message opens the thread on the hub's own host; later ones are
     * added to it by id (`QueryPanel.jsx handleSend`).
     */
    @Test
    fun `a query opens its thread, then adds to it`() = runTest {
        val (repo, calls) = repository()

        repo.sendQuery(QueryThread(), "card_receipt", "receipt-1", " Which film was this for? ")
        repo.sendQuery(QueryThread(id = "thread-9"), "card_receipt", "receipt-1", "Thanks")

        assertTrue(calls[0].url.startsWith("https://accounthub.test/api/v2/account-hub/queries"), calls[0].url)
        assertEquals("card_receipt", calls[0].body["entity_type"]?.jsonPrimitive?.content)
        assertEquals("receipt-1", calls[0].body["entity_id"]?.jsonPrimitive?.content)
        assertEquals("Which film was this for?", calls[0].body["query"]?.jsonPrimitive?.content)
        assertTrue(calls[1].url.endsWith("/queries/thread-9/add"), calls[1].url)
        assertEquals(setOf("query"), calls[1].body.keys)
    }

    @Test
    fun `a fund request names the bank, the account and the amount`() = runTest {
        val (repo, calls) = repository()

        repo.createFundRequest(FundRequestDraft(bankId = "bank-1", fundAccount = " 2100 ", amount = "1,500.50"))
        repo.receiveFundRequest("fr-1")

        val body = calls[0].body
        assertTrue(calls[0].url.endsWith("/card-expenses/fund-requests"))
        assertEquals("bank-1", body["bank_id"]?.jsonPrimitive?.content)
        assertEquals("2100", body["fund_account"]?.jsonPrimitive?.content)
        assertEquals(1_500.5, body["amount"]?.jsonPrimitive?.content?.toDouble())
        assertEquals("PATCH", calls[1].method)
        assertTrue(calls[1].url.endsWith("/fund-requests/fr-1/receive"))
    }

    // -- exports ----------------------------------------------------------------

    /** Without the host's byte POST the export refuses, rather than saving an error page. */
    @Test
    fun `an export with no byte channel refuses`() = runTest {
        val (repo, _) = repository()

        assertIs<ZillitResult.Failure>(repo.exportTransactions(ExportFormat.Pdf))
    }

    @Test
    fun `the register export posts the rows it prints`() = runTest {
        var posted: Pair<String, JsonObject>? = null
        val (repo, _) = repository(
            CardBinaryPost { url, body ->
                posted = url to body
                ZillitResult.Success(ByteArray(3))
            },
        )

        val result = repo.exportCards(
            ExportFormat.Excel,
            listOf(
                CardExportRow("card-1", "4821", "Ada", "Camera", "Barclaycard", "active", "GBP", 2_000.0, 1_240.0),
            ),
        )

        assertIs<ZillitResult.Success<ByteArray>>(result)
        val (url, body) = posted ?: error("nothing was posted")
        assertTrue(url.endsWith("/cards/export"), url)
        assertEquals("xlsx", body["format"]?.jsonPrimitive?.content)
        val row = (body.getValue("rows") as JsonArray).single().jsonObject
        assertEquals("Ada", row["holder"]?.jsonPrimitive?.content)
        assertEquals("4821", row["last4"]?.jsonPrimitive?.content)
    }

    // -- fixtures ---------------------------------------------------------------

    private class Call(val method: String, val url: String, val body: JsonObject)

    private fun repository(binaryPost: CardBinaryPost? = null): Pair<CardRepositoryImpl, MutableList<Call>> {
        val calls = mutableListOf<Call>()
        val engine = MockEngine { request ->
            val body = (request.body as? TextContent)?.text?.let { Json.parseToJsonElement(it) as JsonObject }
            calls += Call(request.method.value, request.url.toString(), body ?: JsonObject(emptyMap()))
            respond(
                """{"status":1,"data":{}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repo = CardRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ ParityEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(
                environment = Environment.Develop,
                services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
                realtime = emptyMap(),
            ),
            binaryPost = binaryPost,
        )
        return repo to calls
    }
}

private class ParityEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
