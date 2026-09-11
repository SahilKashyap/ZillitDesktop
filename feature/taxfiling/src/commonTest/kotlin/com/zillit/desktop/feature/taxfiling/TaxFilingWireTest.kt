package com.zillit.desktop.feature.taxfiling

import com.zillit.desktop.feature.taxfiling.data.DraftResponseDto
import com.zillit.desktop.feature.taxfiling.data.FiledReturnDto
import com.zillit.desktop.feature.taxfiling.data.ObligationDto
import com.zillit.desktop.feature.taxfiling.data.boxMapBody
import com.zillit.desktop.feature.taxfiling.data.submitBody
import com.zillit.desktop.feature.taxfiling.data.syncBody
import com.zillit.desktop.feature.taxfiling.domain.BoxMapping
import com.zillit.desktop.feature.taxfiling.domain.FraudSignals
import com.zillit.desktop.feature.taxfiling.domain.VatBox
import com.zillit.desktop.feature.taxfiling.domain.VatReturn
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * The shapes this service actually speaks, transcribed from the web's own
 * client and its VAT return view.
 *
 * Written from response bodies rather than from the DTOs: a test that builds a
 * DTO and round-trips it agrees with itself even when the field is read under
 * the wrong key, and every bug here has the same signature — decodes cleanly,
 * renders zero, files zero.
 */
class TaxFilingWireTest {

    /**
     * The draft's boxes live under `payload`, a level below the envelope.
     *
     * Reading the envelope as the return finds no field it knows and produces
     * a return of nine zeroes with no error at all — which on this screen is
     * a legally filed nothing.
     */
    @Test
    fun `a draft is read from payload, not from the envelope`() {
        val draft = json.decodeFromString(
            DraftResponseDto.serializer(),
            """
            {"payload":{"periodKey":"18A1","vatDueSales":1000.5,"vatDueAcquisitions":0,
              "totalVatDue":1000.5,"vatReclaimedCurrPeriod":250.25,"netVatDue":750.25,
              "totalValueSalesExVAT":5000,"totalValuePurchasesExVAT":1200,
              "totalValueGoodsSuppliedExVAT":0,"totalAcquisitionsExVAT":0},
             "diagnostics":{"rowsInScope":42,"nullCompanyRows":3}}
            """.trimIndent(),
        ).toDomain()

        assertEquals(1000.5, draft.vatReturn[VatBox.DueOnSales])
        assertEquals(250.25, draft.vatReturn[VatBox.ReclaimedOnPurchases])
        assertEquals("18A1", draft.vatReturn.periodKey)
        assertEquals(42, draft.diagnostics.rowsInScope)
        assertEquals(3, draft.diagnostics.nullCompanyRows)
        assertFalse(draft.isAllZero)
    }

    /** A mapping that selects nothing produces a draft the notice explains. */
    @Test
    fun `a draft of nothing is recognised as all zero`() {
        val draft = json.decodeFromString(
            DraftResponseDto.serializer(),
            """{"payload":{"periodKey":"18A2"},"diagnostics":{"rowsInScope":0}}""",
        ).toDomain()

        assertTrue(draft.isAllZero)
        assertEquals(0, draft.diagnostics.rowsInScope)
    }

    /**
     * An obligation arrives in either spelling.
     *
     * Fresh from HMRC it is camelCase; read back from the service's own table
     * it is snake_case. Reading one only leaves the period picker full of
     * blanks after whichever call was not the expected one.
     */
    @Test
    fun `an obligation is read in both spellings`() {
        val rows = json.decodeFromString(
            ListSerializer(ObligationDto.serializer()),
            """
            [{"periodKey":"18A1","start":"2026-01-01","end":"2026-03-31","due":"2026-05-07",
              "status":"O"},
             {"period_key":"17A4","period_start":"2025-10-01","period_end":"2025-12-31",
              "due":"2026-02-07","status":"F","received":"2026-01-30"}]
            """.trimIndent(),
        ).map { it.toDomain() }

        assertEquals(listOf("18A1", "17A4"), rows.map { it.periodKey })
        assertEquals("2026-01-01", rows[0].start)
        assertEquals("2025-12-31", rows[1].end)
        assertTrue(rows[0].isOpen)
        assertFalse(rows[1].isOpen)
    }

    /**
     * Fulfilment is HMRC's status, not the received date.
     *
     * A period can be fulfilled by a return HMRC has not stamped yet; reading
     * the date instead offers to file it a second time, which HMRC refuses
     * and the accountant reads as a broken screen.
     */
    @Test
    fun `a fulfilled period with no received date is still closed`() {
        val row = json.decodeFromString(
            ObligationDto.serializer(),
            """{"periodKey":"18A1","status":"F"}""",
        ).toDomain()

        assertFalse(row.isOpen)
    }

    /** A row that predates the status field falls back to the received date. */
    @Test
    fun `an obligation with no status reads the received date instead`() {
        val open = json.decodeFromString(
            ObligationDto.serializer(),
            """{"periodKey":"18A1"}""",
        ).toDomain()
        val done = json.decodeFromString(
            ObligationDto.serializer(),
            """{"periodKey":"18A1","received":"2026-05-01"}""",
        ).toDomain()

        assertTrue(open.isOpen)
        assertFalse(done.isOpen)
    }

    /**
     * HMRC's declaration flag rides in the payload.
     *
     * `finalised` is what makes the submission a legal return rather than a
     * draft; without it HMRC refuses the call, and the accountant is left
     * believing a period is filed when it is not.
     */
    @Test
    fun `a submission declares itself finalised`() {
        val body = submitBody("18A1", VatReturn(mapOf(VatBox.DueOnSales to 100.0)), FraudSignals())

        val payload = body["payload"]?.jsonObject
        assertNotNull(payload)
        assertEquals(true, payload["finalised"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("18A1", payload["periodKey"]?.jsonPrimitive?.content)
    }

    /**
     * The draft's period wins over the screen's.
     *
     * The figures were computed for one period. If the two ever disagree,
     * filing them under the other files the wrong quarter's numbers.
     */
    @Test
    fun `the draft's own period key is what is filed`() {
        val body = submitBody("18A2", VatReturn(periodKey = "18A1"), FraudSignals())

        assertEquals("18A1", body["periodKey"]?.jsonPrimitive?.content)
        assertEquals("18A1", body["payload"]?.jsonObject?.get("periodKey")?.jsonPrimitive?.content)
    }

    /**
     * Boxes 3 and 5 are recomputed on the way out.
     *
     * They are the two figures HMRC checks against the others, and a draft
     * that disagrees with its own arithmetic is a rejected return.
     */
    @Test
    fun `the computed boxes are recalculated before filing`() {
        val body = submitBody(
            "18A1",
            VatReturn(
                mapOf(
                    VatBox.DueOnSales to 900.0,
                    VatBox.DueOnAcquisitions to 100.0,
                    VatBox.TotalDue to 5.0,
                    VatBox.ReclaimedOnPurchases to 250.0,
                    VatBox.NetDue to 5.0,
                ),
            ),
            FraudSignals(),
        )

        val payload = body["payload"]?.jsonObject
        assertEquals(1000.0, payload?.get(VatBox.TotalDue.field)?.jsonPrimitive?.doubleOrNull)
        assertEquals(750.0, payload?.get(VatBox.NetDue.field)?.jsonPrimitive?.doubleOrNull)
    }

    /**
     * The ledger boxes file exactly as the server built them.
     *
     * Boxes 6 to 9 are whole pounds because the service computed them that
     * way. Re-rounding here would invent a tie-breaking rule of the client's
     * own, and then the screen and the wire could disagree about a figure on a
     * legal return.
     */
    @Test
    fun `the ledger boxes are filed exactly as they were built`() {
        val body = submitBody(
            "18A1",
            VatReturn(mapOf(VatBox.SalesExVat to 5000.49, VatBox.PurchasesExVat to 1200.5)),
            FraudSignals(),
        )

        val payload = body["payload"]?.jsonObject
        assertEquals(5000.49, payload?.get(VatBox.SalesExVat.field)?.jsonPrimitive?.doubleOrNull)
        assertEquals(1200.5, payload?.get(VatBox.PurchasesExVat.field)?.jsonPrimitive?.doubleOrNull)
    }

    /** The two calls that reach HMRC carry the machine's description. */
    @Test
    fun `the HMRC calls carry the anti-fraud signals`() {
        val signals = FraudSignals(deviceId = "dev-1", timezone = "UTC+01:00")

        val sync = syncBody("2025-09-10", "2026-09-10", signals)
        val submit = submitBody("18A1", VatReturn(), signals)

        assertEquals("2025-09-10", sync["from"]?.jsonPrimitive?.content)
        assertEquals("dev-1", sync["fraudData"]?.jsonObject?.get("deviceId")?.jsonPrimitive?.content)
        assertEquals(
            "UTC+01:00",
            submit["fraudData"]?.jsonObject?.get("timezone")?.jsonPrimitive?.content,
        )
    }

    /**
     * A row that selects nothing is not sent.
     *
     * The save replaces the whole map, so an empty row and an absent one mean
     * the same thing — and sending it stores a mapping nobody made.
     */
    @Test
    fun `an unconfigured box is left out of the saved map`() {
        val body = boxMapBody(
            listOf(
                BoxMapping(box = "box1", codes = listOf("4000")),
                BoxMapping(box = "box2"),
                BoxMapping(box = "box8", markZero = true),
            ),
        )

        val rows = body["rows"] as JsonArray
        assertEquals(
            listOf("box1", "box8"),
            rows.map { (it as JsonObject)["box"]?.jsonPrimitive?.content },
        )
    }

    /**
     * A receipt arrives as an object or as the JSON text it was stored as.
     *
     * The service keeps both the payload and the receipt as text and does not
     * always parse them on the way out.
     */
    @Test
    fun `a filed return is read whether its payload is an object or a string`() {
        val rows = json.decodeFromString(
            ListSerializer(FiledReturnDto.serializer()),
            """
            [{"period_key":"18A1","payload":{"vatDueSales":1000.5,"totalValueSalesExVAT":5000},
              "receipt":{"formBundleNumber":"891614-1","processingDate":"2026-05-02T10:00:00Z"}},
             {"period_key":"17A4","payload":"{\"vatDueSales\":800}",
              "receipt":"{\"reference\":\"771234-9\"}"}]
            """.trimIndent(),
        ).map { it.toDomain(json) }

        assertEquals(1000.5, rows[0].values[VatBox.DueOnSales])
        assertEquals("891614-1", rows[0].reference)
        assertEquals("2026-05-02T10:00:00Z", rows[0].processedAt)
        assertEquals(800.0, rows[1].values[VatBox.DueOnSales])
        assertEquals("771234-9", rows[1].reference)
    }
}
