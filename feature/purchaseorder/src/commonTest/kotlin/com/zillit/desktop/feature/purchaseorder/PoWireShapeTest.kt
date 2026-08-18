package com.zillit.desktop.feature.purchaseorder

import com.zillit.desktop.feature.purchaseorder.data.PoDto
import com.zillit.desktop.feature.purchaseorder.data.body
import com.zillit.desktop.feature.purchaseorder.domain.NewPurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PoLine
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The purchase-order wire, as the server actually speaks it — Android's
 * `CreatePORequest`/`PurchaseOrderResponse` and the web's `POForm`/`mapApiPO`.
 * The first live create (2026-08-18) landed with no vendor, £0.00 and an
 * unknown status because the body used `lines`/`total`/`vendor_name`; these
 * pin the corrected shape.
 */
class PoWireShapeTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Test
    fun `the create body speaks the server's names`() {
        val body = NewPurchaseOrder(
            vendorId = "v-1",
            vendorName = "Panavision",
            description = "Camera package",
            departmentId = null,
            companyId = null,
            currency = null,
            nominalCode = "4100",
            episode = null,
            notes = null,
            effectiveDate = null,
            lines = listOf(PoLine(null, "Alexa Mini", 2.0, 1_250.0, "4100", 20.0)),
            status = "PENDING",
        ).body()

        assertEquals("v-1", body["vendor_id"]?.jsonPrimitive?.content)
        assertEquals("GBP", body["currency"]?.jsonPrimitive?.content, "currency defaults, as Android's does")
        assertEquals(2_500.0, body["net_amount"]?.jsonPrimitive?.content?.toDouble())
        assertEquals("PENDING", body["status"]?.jsonPrimitive?.content)
        val line = body["line_items"]!!.jsonArray.single().jsonObject
        assertEquals("Alexa Mini", line["description"]?.jsonPrimitive?.content)
        assertEquals(2_500.0, line["total"]?.jsonPrimitive?.content?.toDouble())
        assertEquals("4100", line["account"]?.jsonPrimitive?.content)
        assertEquals(20.0, line["tax_rate"]?.jsonPrimitive?.content?.toDouble())
        // What the server does not take, and would silently drop.
        assertFalse("vendor_name" in body)
        assertFalse("total" in body)
        assertFalse("lines" in body)
    }

    @Test
    fun `an order comes back with the server's amounts, lines and status`() {
        val dto = json.decodeFromString(
            PoDto.serializer(),
            """
            {"id":"po-1","po_number":"PO-0007","vendor_id":"v-1","description":"Camera package",
             "status":"PENDING","currency":"GBP","gross_amount":"3000.00","net_total":"2500",
             "line_items":[{"id":"l-1","description":"Alexa Mini","quantity":2,"unit_price":"1250",
                            "total":2500,"account":"4100","tax_rate":20}],
             "created_at":"2026-08-18T06:00:00Z","raised_by":"user-1"}
            """.trimIndent(),
        )
        val order = dto.toDomain()!!

        assertEquals("PO-0007", order.number)
        assertEquals(PoStatus.AwaitingApproval, order.status)
        assertEquals(3_000.0, order.total, "the server-maintained gross wins")
        assertEquals(1, order.lines.size)
        assertEquals(1_250.0, order.lines.single().unitPrice)
        assertEquals("4100", order.lines.single().nominalCode)
        assertEquals(20.0, order.lines.single().vatRate)
        assertEquals("", order.vendorName, "no name on the wire — the vendor list supplies it")
    }

    @Test
    fun `line items may arrive as a JSON string, and the total falls back to the lines`() {
        val dto = json.decodeFromString(
            PoDto.serializer(),
            """{"id":"po-2","status":"ACCT_ENTERED",
                "line_items":"[{\"description\":\"Batteries\",\"quantity\":4,\"unit_price\":25,\"total\":100}]"}""",
        )
        val order = dto.toDomain()!!
        assertEquals(PoStatus.AccountsEntered, order.status)
        assertEquals(1, order.lines.size)
        assertEquals(100.0, order.total)
        assertTrue(order.status.isCommitted)
        assertNull(order.lines.single().vatRate)
    }
}
