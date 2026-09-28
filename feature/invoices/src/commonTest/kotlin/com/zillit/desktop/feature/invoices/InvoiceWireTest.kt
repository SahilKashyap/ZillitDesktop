package com.zillit.desktop.feature.invoices

import com.zillit.desktop.feature.invoices.data.approveBody
import com.zillit.desktop.feature.invoices.data.enteredInvoiceBody
import com.zillit.desktop.feature.invoices.data.parseBankAccounts
import com.zillit.desktop.feature.invoices.data.parseHistory
import com.zillit.desktop.feature.invoices.data.parseInvoice
import com.zillit.desktop.feature.invoices.data.parseSettings
import com.zillit.desktop.feature.invoices.data.parseTierConfigs
import com.zillit.desktop.feature.invoices.data.rowsOf
import com.zillit.desktop.feature.invoices.domain.EnteredInvoice
import com.zillit.desktop.feature.invoices.domain.InvoiceAttachment
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.TierScope
import com.zillit.desktop.feature.invoices.domain.Vendor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InvoiceWireTest {

    private val attachment = InvoiceAttachment(
        media = "p/film-tools/account-hub/invoices/inbox/actual/invoice_1.pdf",
        bucket = "b",
        region = "eu-west-2",
        name = "invoice.pdf",
        contentType = "document",
        contentSubtype = "pdf",
    )

    @Test
    fun `pay method normalises the legacy faster_payment spelling and blanks to bacs`() {
        assertEquals(PayMethod.Faster, PayMethod.from("faster_payment"))
        assertEquals(PayMethod.Faster, PayMethod.from("FASTER"))
        assertEquals(PayMethod.Bacs, PayMethod.from(null))
        assertEquals(PayMethod.Bacs, PayMethod.from(""))
        assertEquals("faster", PayMethod.normalise("faster_payment"))
        assertTrue(PayMethod.Wire.isUrgent && PayMethod.Cheque.isUrgent && !PayMethod.Faster.isUrgent)
        assertTrue(PayMethod.Faster.isOverridePayable && !PayMethod.Bacs.isOverridePayable)
    }

    @Test
    fun `entered invoice body omits net and tax when blank and lands in the inbox`() {
        val entered = EnteredInvoice(
            attachment = attachment,
            invoiceNumber = "INV-001",
            vendorId = "v1",
            description = "Lens hire",
            grossAmount = 1200.0,
            invoiceDateMs = 1_755_475_200_000,
            dueDateMs = 1_758_067_200_000,
            effectiveDateMs = null,
            payMethod = PayMethod.Bacs,
            currency = "GBP",
            departmentId = "d-cam",
            bankId = null,
        )
        val body = enteredInvoiceBody(entered)
        assertEquals("INV-001", body["reference"]!!.jsonPrimitive.content)
        assertEquals("INV-001", body["invoice_number"]!!.jsonPrimitive.content)
        assertNull(body["net_amount"])
        assertNull(body["tax_amount"])
        assertEquals(JsonNull, body["bank_id"])
        assertEquals(JsonNull, body["company_id"])
        assertEquals(JsonNull, body["effective_date"])
        assertEquals("inbox", body["status"]!!.jsonPrimitive.content)
        assertNull(body["upload_id"])

        val split = enteredInvoiceBody(entered.copy(netAmount = 1000.0, taxAmount = 200.0, uploadId = "u9"))
        assertEquals(1000.0, split["net_amount"]!!.jsonPrimitive.content.toDouble())
        assertEquals(200.0, split["tax_amount"]!!.jsonPrimitive.content.toDouble())
        assertEquals("u9", split["upload_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the approve body carries the tier arithmetic`() {
        val approve = approveBody(2, 0)
        assertEquals(2, approve["tier_number"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, approve["total_tiers"]!!.jsonPrimitive.content.toInt(), "total_tiers is at least 1")
    }

    @Test
    fun `parse reads JSON-string approvals, vat_amount fallback, numeric strings and date shapes`() {
        val obj = Json.parseToJsonElement(
            """{"_id":"i1","invoice_number":"INV-9","gross_amount":"1,250.50","vat_amount":"250.5",
               "approvals":"[{\"user_id\":\"u1\",\"tier_number\":\"1\",\"approved_at\":1755475200000}]",
               "attachments":"[{\"media\":\"m\",\"bucket\":\"b\",\"region\":\"r\",\"name\":\"a.PDF\",""" +
                """\"content_type\":\"document\",\"content_subtype\":\"\"}]",
               "invoice_date":"2026-08-01","due_date":"1758067200000","created_at":1755475200000,
               "pay_method":"faster_payment","status":"approval",
               "linked_pos":[{"po_id":"p1","po_number":"PO-1","po_gross_total":"99"}]}""",
        ) as JsonObject
        val invoice = assertNotNull(parseInvoice(obj))
        assertEquals("i1", invoice.id)
        assertEquals(1250.5, invoice.grossAmount)
        assertEquals(250.5, invoice.taxAmount)
        assertNull(invoice.netAmount)
        assertEquals(1, invoice.approvals.size)
        assertEquals(1, invoice.approvals.single().tierNumber)
        assertEquals("pdf", invoice.attachments.single().extension)
        assertTrue(invoice.attachments.single().isPdf)
        assertEquals(1_785_542_400_000L, invoice.invoiceDateMs, "YYYY-MM-DD → UTC midnight")
        assertEquals(1_758_067_200_000L, invoice.dueDateMs)
        assertEquals(PayMethod.Faster, invoice.payMethod)
        assertEquals(InvoiceStatus.Approval, invoice.status)
        assertEquals("PO-1", invoice.poLabel)
        assertEquals(99.0, invoice.linkedPos.single().poGrossTotal)
    }

    @Test
    fun `an unknown status is kept for display and an empty list may be an object`() {
        val invoice = assertNotNull(
            parseInvoice(Json.parseToJsonElement("""{"id":"x","status":"vaporised"}""") as JsonObject),
        )
        assertEquals(InvoiceStatus.Unknown, invoice.status)
        // Upper-cased, as the web's `STATUS_MAP` fallback prints an unknown status.
        assertEquals("VAPORISED", invoice.statusLabel)
        assertEquals("—", invoice.displayNumber)
        assertTrue(rowsOf(Json.parseToJsonElement("{}")).isEmpty())
        assertEquals(1, rowsOf(Json.parseToJsonElement("""{"data":[{"id":"1"}]}""")).size)
        assertEquals(1, parseBankAccounts(Json.parseToJsonElement("""{"data":[{"_id":"b1","name":"Main"}]}""")).size)
    }

    @Test
    fun `override falls back to team_members when the me block is absent, seniority never does`() {
        val withMe = parseSettings(
            Json.parseToJsonElement("""{"me":{"can_override":true,"is_senior":false},"team_members":[]}"""),
        )
        assertEquals(true, withMe.overrideFor("anyone"))
        assertEquals(false, withMe.seniorFor("anyone"))

        val legacy = parseSettings(
            Json.parseToJsonElement(
                """{"team_members":"[{\"user_id\":\"u1\",\"override_access\":\"1\",\"is_senior\":true}]",
                    "run_authorization":"[{\"tier\":1,\"user\":[\"u7\"]}]"}""",
            ),
        )
        assertTrue(legacy.overrideFor("u1"))
        // The web's `serverIsSenior` is the me block's alone (`InvoicesModule.jsx:392-393`).
        assertFalse(legacy.seniorFor("u1"))
        assertFalse(legacy.hasMe)
        assertTrue(withMe.hasMe)
        assertFalse(legacy.overrideFor("u2"))
        assertEquals(setOf("u7"), legacy.runApprovers)
    }

    @Test
    fun `tier configs read tiers encoded as a JSON string with amount thresholds`() {
        val configs = parseTierConfigs(
            Json.parseToJsonElement(
                """[{"_id":"c1","scope":"department","department_id":"d1",""" +
                    """"tiers":"[{\"order\":2,\"rules\":[{\"type\":\"amount\",\"amount_threshold\":\"500\",""" +
                    """\"user_ids\":[\"big\"]}]},{\"order\":1,\"rules\":[{\"type\":\"default\",""" +
                    """\"user_ids\":[\"hod\"]}]}]"}]""",
            ),
        )
        val config = configs.single()
        assertEquals(TierScope.Department, config.scope)
        assertEquals("d1", config.departmentId)
        // Array order, as the server lists them — the web never sorts by `order`.
        assertEquals(listOf(2, 1), config.tiers.map { it.order })
        assertEquals(500.0, config.tiers[0].rules.single().amountThreshold)
    }

    @Test
    fun `history sorts newest first whatever the server order`() {
        val rows = parseHistory(
            Json.parseToJsonElement(
                """[{"action":"created","action_by":"u1","action_at":1},""" +
                    """{"action":"approved","action_by":"u2","action_at":5}]""",
            ),
        )
        assertEquals(listOf("approved", "created"), rows.map { it.action })
    }

    @Test
    fun `money and terms render as the web does`() {
        assertEquals("£1,234.50", InvoiceFormat.money(1234.5, "GBP"))
        assertEquals("$0.00", InvoiceFormat.money(0.0, "usd"))
        assertEquals("—", InvoiceFormat.money(null, "GBP"))
        assertEquals("-12.30", InvoiceFormat.amount(-12.3))
        assertEquals("30 days", Vendor("v", "A", terms = "net_30").slaLabel)
        assertNull(Vendor("v", "A", terms = "").slaLabel)
        assertEquals("1 Aug 2026", InvoiceFormat.date(1_785_542_400_000L))
        assertEquals("2026-08-01", InvoiceFormat.toDateInput(1_785_542_400_000L))
        assertEquals(1_785_542_400_000L, InvoiceFormat.parseDateInput("2026-08-01"))
        assertNull(InvoiceFormat.parseDateInput("01/08/2026"))
    }
}
