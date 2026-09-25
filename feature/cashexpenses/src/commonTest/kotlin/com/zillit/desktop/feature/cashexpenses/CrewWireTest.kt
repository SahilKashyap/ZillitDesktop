package com.zillit.desktop.feature.cashexpenses

import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.cashexpenses.data.CashRepositoryImpl
import com.zillit.desktop.feature.cashexpenses.data.toSubmitJson
import com.zillit.desktop.feature.cashexpenses.domain.BankDetailsDraft
import com.zillit.desktop.feature.cashexpenses.domain.CashAttachment
import com.zillit.desktop.feature.cashexpenses.domain.CrewDates
import com.zillit.desktop.feature.cashexpenses.domain.CrewInput
import com.zillit.desktop.feature.cashexpenses.domain.DraftReceipt
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType
import com.zillit.desktop.feature.cashexpenses.domain.ExtraBankField
import com.zillit.desktop.feature.cashexpenses.domain.FollowUp
import com.zillit.desktop.feature.cashexpenses.domain.NewClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.NewFloatRequest
import com.zillit.desktop.feature.cashexpenses.domain.ReimbursementMethod
import com.zillit.desktop.feature.cashexpenses.domain.SettlementDetails
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The crew's two writes — `POST /claims` and `POST /float-requests` — key for key with the web. */
class CrewWireTest {

    private val file = CashAttachment(media = "k/r.jpg", name = "r.jpg", contentType = "image", contentSubtype = "jpg")

    private fun receipt() = DraftReceipt(
        description = "B&Q",
        supplier = "ignored",
        amount = "42.50",
        vat = "7.08",
        category = "materials",
        date = CrewDates.utcMillis("2026-09-01"),
        costCode = "4100",
        episode = "Ep.3",
        codedDescription = "Set dressing",
        attachment = file,
        attachmentKey = file.media,
    )

    @Test
    fun `a petty-cash reimbursement sends the batch keys, the settlement details and each claim's own`() {
        val body = NewClaimBatch(
            expenseType = ExpenseType.PettyCash,
            floatId = "f1",
            receipts = listOf(receipt()),
            settlementType = "REIMBURSE",
            notes = "",
            departmentId = "d1",
            currency = "EUR",
            settlementDetails = SettlementDetails(
                followUp = FollowUp.TOP_UP,
                topUpAmount = 42.5,
                paymentMethod = ReimbursementMethod.Bacs,
                bankDetails = BankDetailsDraft(
                    accountName = "Ada",
                    sortCode = "204891",
                    accountNumber = "12345678",
                    extras = listOf(ExtraBankField("IBAN", "GB00", "text"), ExtraBankField("", "dropped")),
                ),
            ),
        ).toSubmitJson()

        assertEquals("pc", body.string("expense_type"))
        assertEquals("d1", body.string("department_id"))
        assertEquals("f1", body.string("float_request_id"))
        assertEquals("EUR", body.string("currency"))
        assertEquals(JsonNull, body["notes"], "a blank note goes as null")
        val details = body["settlement_details"]!!.jsonObject
        assertEquals("top_up", details.string("follow_up"))
        assertEquals(42.5, details["top_up_amount"]!!.jsonPrimitive.content.toDouble())
        assertEquals("BACS", details.string("payment_method"))
        val bank = details["bank_details"]!!.jsonObject
        assertEquals("204891", bank.string("sort_code"), "digits only on the wire")
        val extras = bank["additional_details"]!!.jsonArray
        assertEquals(1, extras.size, "an untitled row is dropped")
        assertEquals("IBAN", extras.single().jsonObject.string("label"))

        val claim = body["claims"]!!.jsonArray.single().jsonObject
        assertEquals("B&Q", claim.string("description"))
        assertEquals("EUR", claim.string("currency"), "the float's currency on every claim")
        assertEquals(CrewDates.utcMillis("2026-09-01"), claim["receipt_date"]!!.jsonPrimitive.content.toLong())
        assertEquals("Ep.3", claim.string("episode"))
        assertEquals("Set dressing", claim.string("coded_description"))
        assertEquals("k/r.jpg", claim["attachment"]!!.jsonObject.string("media"))
        assertFalse("supplier" in claim, "the web never sends a supplier on submit")
        assertFalse("vat_amount" in claim, "accounts extract the tax")
    }

    @Test
    fun `a reduce sends a null follow-up and no payment keys, out of pocket no follow-up at all`() {
        val reduce = NewClaimBatch(
            ExpenseType.PettyCash, "f1", listOf(receipt()), "REDUCE_FLOAT", null,
            settlementDetails = SettlementDetails(),
        ).toSubmitJson()["settlement_details"]!!.jsonObject
        assertEquals(JsonNull, reduce["follow_up"])
        assertFalse("payment_method" in reduce)
        assertFalse("bank_details" in reduce)

        val oop = NewClaimBatch(
            ExpenseType.OutOfPocket, null, listOf(receipt()), "REIMBURSE", null,
            settlementDetails = SettlementDetails(includeFollowUp = false, paymentMethod = ReimbursementMethod.Payroll),
        ).toSubmitJson()
        val details = oop["settlement_details"]!!.jsonObject
        assertFalse("follow_up" in details)
        assertEquals("PAYROLL", details.string("payment_method"))
        assertFalse("bank_details" in details, "payroll needs no bank details")
        assertFalse("float_request_id" in oop)
    }

    @Test
    fun `a float request sends whole days only for days, and the collection answers`() = runTest {
        val (repo, sent) = repository()
        repo.requestFloat(
            NewFloatRequest(
                amount = 250.0, currency = "GBP", purpose = "Props", departmentId = "d1",
                duration = "7", durationType = "days",
                collectDate = CrewDates.utcMillis("2026-10-01"), episode = "ep_3",
                collectionMethod = "production_office",
            ),
        )
        repo.requestFloat(
            NewFloatRequest(
                amount = 100.0, currency = "GBP", purpose = "Props", departmentId = null,
                duration = "7", durationType = "run_of_show",
            ),
        )

        val days = sent[0]
        assertEquals(7, days["duration"]!!.jsonPrimitive.content.toInt())
        assertEquals("days", days.string("duration_type"))
        assertEquals(CrewDates.utcMillis("2026-10-01"), days["collect_date"]!!.jsonPrimitive.content.toLong())
        assertEquals("ep_3", days.string("episode"))
        assertEquals("production_office", days.string("collection_method"))
        assertEquals(JsonNull, sent[1]["duration"], "run of show sends no day count")
    }

    @Test
    fun `input shaping matches the web`() {
        assertEquals("12.34", CrewInput.amount("£12.345abc"))
        assertEquals("1.5", CrewInput.amount("1..5"))
        assertEquals("20-48-91", CrewInput.sortCode("204891"))
        assertEquals("2048", CrewInput.digits("20-48", 6))
        assertEquals("2026-09-01", CrewDates.utcIso(CrewDates.utcMillis("2026-09-01")))
        assertNull(CrewDates.utcMillis("2026-13-01"))
        assertTrue(CrewDates.isAfter("2026-09-02", "2026-09-01"))
    }

    private fun JsonObject.string(key: String) = this[key]!!.jsonPrimitive.content

    private fun repository(): Pair<CashRepositoryImpl, List<JsonObject>> {
        val sent = mutableListOf<JsonObject>()
        val engine = MockEngine { request: HttpRequestData ->
            val body = (request.body as? TextContent)?.text?.let { Json.parseToJsonElement(it) as? JsonObject }
            sent += body ?: JsonObject(emptyMap())
            respond(
                """{"status":1,"data":{}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repo = CashRepositoryImpl(
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
}

private class CrewMockEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
