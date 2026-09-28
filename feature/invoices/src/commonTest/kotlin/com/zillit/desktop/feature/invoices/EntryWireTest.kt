package com.zillit.desktop.feature.invoices

import com.zillit.desktop.feature.invoices.data.assignBody
import com.zillit.desktop.feature.invoices.data.entryUpdateBody
import com.zillit.desktop.feature.invoices.data.normaliseLockedDate
import com.zillit.desktop.feature.invoices.data.parseInvoice
import com.zillit.desktop.feature.invoices.data.parsePeriodLock
import com.zillit.desktop.feature.invoices.data.parseProjectSettings
import com.zillit.desktop.feature.invoices.data.parseQueryThread
import com.zillit.desktop.feature.invoices.data.parseSettings
import com.zillit.desktop.feature.invoices.data.quickEntryBody
import com.zillit.desktop.feature.invoices.domain.QuickEntry
import com.zillit.desktop.feature.invoices.domain.CodedLine
import com.zillit.desktop.feature.invoices.domain.EntryCoding
import com.zillit.desktop.feature.invoices.domain.EntryHeader
import com.zillit.desktop.feature.invoices.domain.EntryTotals
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.TaxLine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the coding screen reads and writes, against the web's payloads. */
class EntryWireTest {

    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun `saved lines are read from an array or a string, and the tax line is kept apart`() {
        val invoice = parseInvoice(
            obj(
                """{"_id":"i1","line_items":"[{\"id\":\"l1\",\"description\":\"Lamps\",\"account\":\"2400\",""" +
                    """\"total\":100,\"tax_rate\":\"20%\",\"tax_type\":\"vat\"},""" +
                    """{\"is_tax\":true,\"account\":\"2200\",\"total\":19.5}]"}""",
            ),
        )!!
        val line = invoice.lineItems.single()
        assertEquals("l1", line.id)
        assertEquals(20.0, line.taxRate)
        assertEquals(100.0, line.amount)
        assertEquals(TaxLine(account = "2200", amount = 19.5, overridden = true), invoice.taxLine)
        assertTrue(invoice.lineItemsJson.contains("l1"))
    }

    @Test
    fun `the header follows buildInvoiceHeaderPayload - blanks left out or nulled, never guessed`() {
        val body = entryUpdateBody(
            header = EntryHeader(
                invoiceNumber = "INV-9",
                invoiceDate = "2026-09-01",
                payMethod = PayMethod.Faster,
            ),
            lines = null,
        )
        assertEquals("INV-9", body["invoice_number"]?.jsonPrimitive?.content)
        assertEquals(1_788_220_800_000L, body["invoice_date"]?.jsonPrimitive?.content?.toLong())
        assertFalse("due_date" in body, "a blank date is left out, not cleared")
        assertFalse("currency" in body, "no picked currency, so the stored one is kept")
        assertEquals(JsonNull, body["company_id"])
        assertEquals(JsonNull, body["bank_id"])
        assertEquals(JsonNull, body["episode"])
        assertEquals("faster", body["pay_method"]?.jsonPrimitive?.content)
        assertFalse("line_items" in body)
        assertFalse("status" in body)
    }

    @Test
    fun `lines go in the web's shape, carrying what this client does not edit`() {
        val saved = """[{"id":"l1","tracking_codes":{"set":"n1"},"tags":["prep"],"custom_fields":[{"name":"x"}],""" +
            """"rental_start":1,"tax_amount":99},{"is_tax":true,"tags":["t"]}]"""
        val lines = listOf(
            CodedLine("l1", description = "Lamps", account = "9999", amount = 60.0).withAmount(60.0),
            CodedLine("c1", description = "Lamps", amount = 40.0, splitParentId = "l1"),
        )
        val body = entryUpdateBody(
            header = EntryHeader(),
            lines = lines,
            tax = TaxLine(account = "2200", tags = listOf("t")),
            taxAmount = 12.0,
            savedLinesJson = saved,
            chart = setOf("2200"),
            status = "under_review",
        )
        val rows = body["line_items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, rows.size)
        val first = rows[0]
        assertEquals("[[9999]]", first["account"]?.jsonPrimitive?.content, "a code the chart lacks is created")
        assertEquals(60.0, first["total"]?.jsonPrimitive?.content?.toDouble())
        assertEquals("n1", first["tracking_codes"]!!.jsonObject["set"]?.jsonPrimitive?.content)
        assertEquals(1, first["custom_fields"]!!.jsonArray.size)
        assertEquals(JsonPrimitive(1), first["rental_start"])
        assertNull(first["tax_amount"], "stale keys the web does not write are not carried")
        // A new child takes its parent's layers and tags, not its custom fields.
        val child = rows[1]
        assertEquals("l1", child["split_parent_id"]?.jsonPrimitive?.content)
        assertEquals("prep", child["tags"]!!.jsonArray.single().jsonPrimitive.content)
        assertTrue(child["custom_fields"]!!.jsonArray.isEmpty())
        val tax = rows[2]
        assertEquals(true, tax["is_tax"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(12.0, tax["total"]?.jsonPrimitive?.content?.toDouble())
        assertEquals("2200", tax["account"]?.jsonPrimitive?.content)
        assertEquals("t", tax["tags"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("under_review", body["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a changed currency writes the coded totals as the invoice's own amounts, rounded`() {
        val body = entryUpdateBody(
            header = EntryHeader(currency = "USD"),
            lines = emptyList(),
            amounts = EntryTotals(net = 100.004, tax = 20.0049, gross = 120.0089),
        )
        assertEquals(100.0, body["net_amount"]?.jsonPrimitive?.content?.toDouble())
        assertEquals(20.0, body["tax_amount"]?.jsonPrimitive?.content?.toDouble())
        assertEquals(120.01, body["gross_amount"]?.jsonPrimitive?.content?.toDouble())
        assertEquals("USD", body["currency"]?.jsonPrimitive?.content)
        // Without a change the entered split is left as it is.
        val plain = entryUpdateBody(header = EntryHeader(), lines = emptyList())
        assertFalse("net_amount" in plain || "tax_amount" in plain || "gross_amount" in plain)
    }

    @Test
    fun `a line seeded from a PO keeps its layers, tags, custom fields and rental dates on the first save`() {
        val order = com.zillit.desktop.feature.invoices.data.parseLinkedPos(
            Json.parseToJsonElement(
                """[{"po_id":"po1","po_number":"PO-1","line_items":[{"id":"pl1","description":"Lamps",""" +
                    """"total":100,"account":"2400","tracking_codes":{"set":"LOC"},"tags":["prep"],""" +
                    """"custom_fields":[{"name":"Set","value":"A"}],"rental_start":1789257600000,"rental_end":null}]}]""",
            ),
        )
        var n = 0
        val lines = EntryCoding.poLines(order, { "x${++n}" })
        // An uncoded invoice has no saved lines to fall back on.
        val row = entryUpdateBody(EntryHeader(), lines, savedLinesJson = "")["line_items"]!!.jsonArray.single().jsonObject
        assertEquals("LOC", row["tracking_codes"]!!.jsonObject["set"]?.jsonPrimitive?.content)
        assertEquals("prep", row["tags"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("Set", row["custom_fields"]!!.jsonArray.single().jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals(1_789_257_600_000L, row["rental_start"]?.jsonPrimitive?.content?.toLong())
        assertEquals(JsonNull, row["rental_end"])
    }

    @Test
    fun `a saved line without an id keeps its extras on save`() {
        val invoice = parseInvoice(
            obj(
                """{"_id":"i1","line_items":[{"description":"Lamps","total":100,"account":"2400",""" +
                    """"tracking_codes":{"set":"LOC"},"tags":["prep"],"custom_fields":[{"fields":[{"name":"Set","value":"B"}]}],""" +
                    """"rental_start":"2026-09-01"}]}""",
            ),
        )!!
        val line = invoice.lineItems.single()
        assertEquals("line-0", line.id)
        assertEquals(listOf("Set" to "B"), line.carried?.customFields)
        val row = entryUpdateBody(EntryHeader(), invoice.lineItems, savedLinesJson = invoice.lineItemsJson)["line_items"]!!
            .jsonArray.single().jsonObject
        assertEquals("LOC", row["tracking_codes"]!!.jsonObject["set"]?.jsonPrimitive?.content)
        assertEquals("prep", row["tags"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(1, row["custom_fields"]!!.jsonArray.size)
        assertEquals("2026-09-01", row["rental_start"]?.jsonPrimitive?.content)
    }

    @Test
    fun `layers and tags edited here are what is written, cleared ones included`() {
        val invoice = parseInvoice(
            obj("""{"_id":"i1","line_items":[{"id":"l1","total":10,"tracking_codes":{"set":"LOC"},"tags":["prep"]}]}"""),
        )!!
        val cleared = invoice.lineItems.map { it.copy(trackingCodes = emptyMap(), tags = listOf("wrap")) }
        val row = entryUpdateBody(EntryHeader(), cleared, savedLinesJson = invoice.lineItemsJson)["line_items"]!!
            .jsonArray.single().jsonObject
        assertTrue(row["tracking_codes"]!!.jsonObject.isEmpty())
        assertEquals("wrap", row["tags"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun `a pay method this client does not know is written back as it was stored`() {
        val invoice = parseInvoice(obj("""{"_id":"i1","pay_method":"direct_credit"}"""))!!
        val header = EntryHeader.of(invoice)
        assertEquals("direct_credit", header.payMethodCode)
        assertEquals("direct_credit", entryUpdateBody(header, null)["pay_method"]?.jsonPrimitive?.content)
        // Picking a method replaces it.
        val picked = header.copy(payMethod = PayMethod.Wire, payMethodCode = "")
        assertEquals("wire", entryUpdateBody(picked, null)["pay_method"]?.jsonPrimitive?.content)
        // A known one, or the legacy spelling of one, is not kept raw.
        assertEquals("", EntryHeader.of(parseInvoice(obj("""{"_id":"i2","pay_method":"faster_payment"}"""))!!).payMethodCode)
    }

    @Test
    fun `assigning sends who, why and when, as the web does`() {
        val body = assignBody("u2", "Workload Balancing", 1_789_257_600_000L)
        assertEquals("u2", body["assigned_to"]?.jsonPrimitive?.content)
        assertEquals("Workload Balancing", body["assignment_reason"]?.jsonPrimitive?.content)
        assertEquals(1_789_257_600_000L, body["updated_at"]?.jsonPrimitive?.content?.toLong())
    }

    @Test
    fun `a posting right is an unlimited or positive limit, and an absent one is none`() {
        val settings = parseSettings(
            obj(
                """{"team_members":[{"user_id":"a","posting_limit":null},""" +
                    """{"user_id":"b","posting_limit":"unlimited"},""" +
                    """{"user_id":"c","posting_limit":500},{"user_id":"d","posting_limit":0},{"user_id":"e"}]}""",
            ),
        )
        assertEquals(
            listOf(true, true, true, false, false),
            listOf("a", "b", "c", "d", "e").map(settings::postingRightFor),
        )
    }

    @Test
    fun `production setup gives companies, tax types and the stored boundary`() {
        val settings = parseProjectSettings(
            obj(
                """{"project_id":"p","settings":{"companies":[{"id":"co1","name":"Prod Co","country":"GB"}],""" +
                    """"tax_types":[{"identifier":"vat20","label":"VAT","value":"20","is_recoverable":true},""" +
                    """{"identifier":"exempt","label":"Exempt"}],""" +
                    """"last_cr_locked_date":"2026-09-13","timezone":"Europe/London"}}""",
            ),
        )
        assertEquals("Prod Co", settings.companies.single().name)
        assertEquals(20.0, settings.taxTypes.first().rate)
        assertTrue(settings.taxTypes.first().isRecoverable)
        assertNull(settings.taxTypes.last().rate)
        assertEquals("2026-09-13", settings.lock?.lockedThrough)
    }

    @Test
    fun `the lock route's date is read in any of its shapes`() {
        assertEquals("2026-09-13", parsePeriodLock(obj("""{"lockedDate":"2026-09-13","tz":"UTC"}"""))?.lockedThrough)
        assertEquals(
            "2026-09-13",
            parsePeriodLock(obj("""{"last_cr_locked_date":"2026-09-13T00:00:00.000Z"}"""))?.lockedThrough,
        )
        assertEquals("2026-09-13", normaliseLockedDate(JsonPrimitive(1_789_257_600_000L)))
        assertEquals("", normaliseLockedDate(JsonNull))
    }

    @Test
    fun `a query thread reads its messages and who sent them`() {
        val thread = parseQueryThread(
            obj("""{"id":"q1","queries":[{"query":"Which PO?","queried_by":"u1","queried_at":1789257600000}]}"""),
        )
        assertEquals("q1", thread.id)
        assertEquals("Which PO?", thread.messages.single().text)
        assertEquals("u1", thread.messages.single().by)
    }

    @Test
    fun `quick entry goes straight to ready to pay, with the gross worked out`() {
        val body = quickEntryBody(
            QuickEntry(
                reference = " INV-7 ",
                vendorId = "v1",
                nominal = "2400",
                costCentre = "CAM",
                net = 100.0,
                taxRate = 20.0,
                effectiveDate = "",
                today = "2026-09-23",
                tags = listOf("prep"),
            ),
        )
        assertEquals("INV-7", body["reference"]?.jsonPrimitive?.content)
        assertEquals(120.0, body["gross_amount"]?.jsonPrimitive?.content?.toDouble())
        assertEquals(20.0, body["tax_amount"]?.jsonPrimitive?.content?.toDouble())
        assertEquals("ready_to_pay", body["status"]?.jsonPrimitive?.content)
        assertEquals("bacs", body["pay_method"]?.jsonPrimitive?.content)
        assertEquals("2026-09-23", body["invoice_date"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, body["effective_date"])
        assertEquals("prep", body["tags"]!!.jsonArray.single().jsonPrimitive.content)
    }
}
