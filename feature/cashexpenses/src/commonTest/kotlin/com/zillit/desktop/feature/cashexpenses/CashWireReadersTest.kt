package com.zillit.desktop.feature.cashexpenses

import com.zillit.desktop.feature.cashexpenses.data.BatchDto
import com.zillit.desktop.feature.cashexpenses.data.ClaimDto
import com.zillit.desktop.feature.cashexpenses.data.FloatDto
import com.zillit.desktop.feature.cashexpenses.data.MetadataDto
import com.zillit.desktop.feature.cashexpenses.data.ReconciliationDto
import com.zillit.desktop.feature.cashexpenses.data.SettingsDto
import com.zillit.desktop.feature.cashexpenses.data.TopUpDto
import com.zillit.desktop.feature.cashexpenses.data.cashLenient
import com.zillit.desktop.feature.cashexpenses.data.readFloatDetails
import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.CashAccount
import com.zillit.desktop.feature.cashexpenses.domain.CashCurrencies
import com.zillit.desktop.feature.cashexpenses.domain.CashDepartment
import com.zillit.desktop.feature.cashexpenses.domain.CashDepartments
import com.zillit.desktop.feature.cashexpenses.domain.CashNominals
import com.zillit.desktop.feature.cashexpenses.domain.AssigneeOption
import com.zillit.desktop.feature.cashexpenses.domain.CashRules
import com.zillit.desktop.feature.cashexpenses.domain.FloatStatus
import com.zillit.desktop.feature.cashexpenses.domain.LineItemEditor
import com.zillit.desktop.feature.cashexpenses.domain.RequestCap
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fields the web reads that the desktop dropped, each read in every shape
 * the service has been seen to send: objects and strings holding them, numbers
 * and strings, present and absent.
 */
class CashWireReadersTest {

    private fun <T> decode(serializer: KSerializer<T>, json: String): T =
        cashLenient.decodeFromJsonElement(serializer, Json.parseToJsonElement(json))

    // -- attachments ----------------------------------------------------------------

    @Test
    fun `a claim's attachment object wins over the flat receipt url`() {
        val claim = decode(
            ClaimDto.serializer(),
            """{"id":"c1","receipt_url":"old/key.jpg","attachment":{"media":"cash-expenses/x/r.pdf",
               "bucket":"b","region":"eu-west-2","name":"r.pdf","content_type":"application",
               "content_subtype":"pdf","caption":""}}""",
        ).toDomain()!!

        assertEquals("cash-expenses/x/r.pdf", claim.receiptKey)
        assertEquals("eu-west-2", claim.attachment?.region)
        assertTrue(claim.receiptIsPdf, "a PDF by its content_subtype")
    }

    @Test
    fun `a claim with only a receipt url opens that`() {
        val claim = decode(ClaimDto.serializer(), """{"id":"c1","receipt_url":"k/photo.png"}""").toDomain()!!
        assertNull(claim.attachment)
        assertEquals("k/photo.png", claim.receiptKey)
        assertFalse(claim.receiptIsPdf)
    }

    @Test
    fun `an attachment stored as a string, with a whole mime type, still reads`() {
        val claim = decode(
            ClaimDto.serializer(),
            """{"id":"c1","attachment":"{\"media\":\"k/a.bin\",\"content_type\":\"application/pdf\"}"}""",
        ).toDomain()!!
        assertEquals("k/a.bin", claim.receiptKey)
        assertEquals("application", claim.attachment?.contentType)
        assertEquals("pdf", claim.attachment?.contentSubtype)
        assertTrue(claim.receiptIsPdf)
    }

    @Test
    fun `an attachment with no key is no attachment`() {
        val claim = decode(ClaimDto.serializer(), """{"id":"c1","attachment":{"name":"x.jpg"}}""").toDomain()!!
        assertNull(claim.attachment)
        assertNull(claim.receiptKey)
    }

    // -- line items -------------------------------------------------------------------

    @Test
    fun `a tax line and a coded line keep their own keys through the editor`() {
        val claim = decode(
            ClaimDto.serializer(),
            """{"id":"c1","line_items":[
              {"id":"11111111-1111-1111-1111-111111111111","account":"5010","total":"120",
               "tax_rate":20,"quantity":1,"unit_price":"100","tags":["props"],
               "tracking_codes":[{"layer":"ep","code":"101"}],"sort_order":2,
               "rental_start":"2026-09-01","expenditure_type":"rental"},
              {"id":"22222222-2222-2222-2222-222222222222","account":"2200","total":20,
               "is_tax":true,"tax_amount":"20"}]}""",
        ).toDomain()!!

        val saved = LineItemEditor.toWire(LineItemEditor.fromWire(claim.lineItems)) { "new" }

        val coded = saved.first { it.account == "5010" }
        assertEquals(120.0, coded.total)
        assertEquals(2, coded.sortOrder)
        assertEquals("2026-09-01", coded.rentalStart)
        assertEquals("rental", coded.expenditureType)
        assertEquals("[\"props\"]", coded.tags.toString())
        assertTrue(coded.trackingCodes is JsonArray)
        assertFalse(coded.isTax)

        val tax = saved.first { it.account == "2200" }
        assertTrue(tax.isTax, "the tax line is still one after a save")
        assertEquals(20.0, tax.taxAmount)
        assertEquals(20.0, tax.total)
    }

    // -- floats, batches, top-ups, reconciliations ---------------------------------------

    @Test
    fun `a float reads its collection, custom answers and settled spend`() {
        val float = decode(
            FloatDto.serializer(),
            """{"id":"f1","status":"SPENDING","transaction_currency":"EUR","collect_date":"1788220800000",
               "collect_time":"09:30","collection_method":"cash","activated_at":1788220800000,"spent":"12.50",
               "receipts_commits":"0","company_name":"Prod Co","episode":"101",
               "custom_fields":"$CUSTOM"}""",
        ).toDomain()!!

        assertEquals("EUR", float.currency, "transaction_currency stands in for a missing currency")
        assertEquals(1_788_220_800_000L, float.collectDate)
        assertEquals("09:30", float.collectTime)
        assertEquals(12.5, float.reportedSpent)
        assertEquals("3", float.customFields.single().fields.single().value)
        assertTrue(float.hasReceipts, "settled spend counts as spent against")
        assertFalse(float.bsCodeEditable(isAccountant = true))
    }

    @Test
    fun `an untouched live float's BS code is an accountant's to correct`() {
        val float = decode(FloatDto.serializer(), """{"id":"f1","status":"APPROVED"}""").toDomain()!!
        assertTrue(float.bsCodeEditable(isAccountant = true))
        assertFalse(float.bsCodeEditable(isAccountant = false))
        assertFalse(float.copy(status = FloatStatus.Closed).bsCodeEditable(isAccountant = true))
    }

    @Test
    fun `a batch reads its trail and its settlement follow-up`() {
        val batch = decode(
            BatchDto.serializer(),
            """{"id":"b1","status":"DESCALATED","posted_at":"1788220800000","rejection_reason":"Blurry",
               "float_request_id":"f1","query_reason":"Which van?",
               "settlement_details":"{\"follow_up\":\"top_up\",\"payment_method\":\"BACS\"}"}""",
        ).toDomain()!!

        assertEquals(BatchStatus.Descalated, batch.status)
        assertTrue(CashRules.canQuery(batch), "a descalated batch carries a query thread")
        assertEquals(1_788_220_800_000L, batch.postedAt)
        assertEquals("Blurry", batch.rejectionReason)
        assertEquals("f1", batch.floatRequestId)
        assertEquals("top_up", batch.followUp)
        assertEquals("BACS", batch.paymentMethod)
    }

    @Test
    fun `top-up history reads as a string or as an array`() {
        val stringified = decode(
            TopUpDto.serializer(),
            """{"id":"t1","method":"cash","history":"$HISTORY"}""",
        ).toDomain()!!
        assertEquals(listOf("requested", "partial"), stringified.history.map { it.action })
        assertEquals("u2", stringified.history.first().actionBy)
        assertEquals(1_788_220_800_000L, stringified.history.first().actionAt)
        assertEquals(30.0, stringified.history.last().amount)
        assertEquals("Only 30", stringified.history.last().reason)
        assertEquals("cash", stringified.method)

        val array = decode(TopUpDto.serializer(), """{"id":"t1","history":[{"action":"skipped"}]}""").toDomain()!!
        assertEquals("skipped", array.history.single().action)

        val broken = decode(TopUpDto.serializer(), """{"id":"t1","history":"not json"}""").toDomain()!!
        assertTrue(broken.history.isEmpty(), "unreadable history is no history, not a failed row")
    }

    @Test
    fun `a reconciliation reads who submitted and who signed`() {
        val recon = decode(
            ReconciliationDto.serializer(),
            """{"id":"r1","submitted_by":"u3","submitted_at":"1788220800000","signed_by":"u4"}""",
        ).toDomain()!!
        assertEquals("u3", recon.submittedBy)
        assertEquals(1_788_220_800_000L, recon.submittedAt)
        assertEquals("u4", recon.signedBy)
    }

    // -- metadata and settings ----------------------------------------------------------

    @Test
    fun `metadata carries the request cap crew are held to`() {
        val metadata = decode(
            MetadataDto.serializer(),
            """{"request_cap":{"enabled":true,"basis":"weekly_salary","max_amount":"500","salary_multiplier":1.5}}""",
        ).toDomain()
        assertEquals(RequestCap(true, RequestCap.WEEKLY_SALARY, 500.0, 1.5), metadata.requestCap)

        assertNull(decode(MetadataDto.serializer(), "{}").toDomain().requestCap, "no cap sent is no cap")
    }

    @Test
    fun `department coordinators read as an array or as a string holding one`() {
        val array = decode(
            SettingsDto.serializer(),
            """{"department_coordinators":[{"department_id":"d1","user_ids":["u1","u2"],"coding_required":true,
               "view_department_floats":"true"}]}""",
        ).toDomain().departmentCoordinators.single()
        assertEquals("d1", array.departmentId)
        assertEquals(listOf("u1", "u2"), array.userIds)
        assertTrue(array.codingRequired)
        assertTrue(array.viewDepartmentFloats)

        val stringified = decode(
            SettingsDto.serializer(),
            """{"department_coordinators":"[{\"department_id\":\"d2\",\"user_ids\":[]},{\"user_ids\":[\"u9\"]}]"}""",
        ).toDomain().departmentCoordinators
        assertEquals(listOf("d2"), stringified.map { it.departmentId }, "a row with no department is dropped")
        assertFalse(stringified.single().codingRequired)
    }

    // -- float details ---------------------------------------------------------------------

    @Test
    fun `float details read every part, and a broken part costs only itself`() {
        val details = Json.parseToJsonElement(
            """{"float":{"id":"f1","req_number":"PC-7","status":"CLOSED"},
               "totals":{"issued":"200","spent":150,"topped_up":"50","returned":"100","final_balance":0},
               "batches":[{"id":"b1","status":"POSTED"}],"topups":"oops",
               "returns":[{"return_amount":"100","return_reason":"close_full_return","recorded_at":1788220800000}]}""",
        ).readFloatDetails()

        assertEquals("PC-7", details.float?.requestNumber)
        assertEquals(200.0, details.totals.issued)
        assertEquals(50.0, details.totals.toppedUp)
        assertEquals(listOf("b1"), details.batches.map { it.id })
        assertTrue(details.topUps.isEmpty())
        assertEquals(100.0, details.returns.single().amount)
        assertEquals("close_full_return", details.returns.single().reason)
    }

    private companion object {
        /** Custom answers, stringified as the service stores them — on one line, as JSON requires. */
        const val CUSTOM = """[{\"section\":\"float_request\",\"fields\":[{\"name\":\"Van\",""" +
            """\"label\":\"van\",\"type\":\"number\",\"value\":3}]}]"""

        const val HISTORY = """[{\"action\":\"requested\",\"action_by\":\"u2\",\"action_at\":1788220800000,""" +
            """\"amount\":\"50\"},{\"action\":\"partial\",\"reason\":\"Only 30\",\"amount\":30}]"""
    }

    // -- reference helpers ------------------------------------------------------------------

    @Test
    fun `a record renders in its own currency, else the default, else GBP`() {
        assertEquals("GBP", CashCurrencies().codeFor(null))
        val project = CashCurrencies(defaultCode = "EUR")
        assertEquals("EUR", project.codeFor(null))
        assertEquals("USD", project.codeFor("USD"))
        assertEquals("EUR", project.default)
    }

    @Test
    fun `a nominal the chart does not hold goes out wrapped`() {
        val chart = listOf(CashAccount("5010", "Materials"))
        assertEquals("5010", CashNominals.wrap("5010", chart))
        assertEquals("abc", CashNominals.wrap("abc", listOf(CashAccount("ABC"))), "case-insensitive hit")
        assertEquals("[[9999]]", CashNominals.wrap(" 9999 ", chart))
        assertEquals("9999", CashNominals.wrap("9999", emptyList()), "an unread chart wraps nothing")
        assertEquals("", CashNominals.wrap(null, chart))
    }

    @Test
    fun `crew get department ids by name, never by a name two departments share`() {
        val departments = listOf(
            CashDepartment("d-acc", "Accounts"),
            CashDepartment("d-cam1", "Camera"),
            CashDepartment("d-cam2", "camera"),
        )
        val crew = listOf(
            AssigneeOption("u1", "Ann", department = "department_accounts"),
            AssigneeOption("u2", "Ben", department = "Camera"),
            AssigneeOption("u3", "Cat", department = "Accounts", departmentId = "given"),
        )

        val joined = CashDepartments.withIds(crew, departments).associate { it.userId to it.departmentId }

        assertEquals("d-acc", joined["u1"])
        assertEquals("", joined["u2"], "ambiguous names match nothing")
        assertEquals("given", joined["u3"], "a host-supplied id is kept")
    }
}
