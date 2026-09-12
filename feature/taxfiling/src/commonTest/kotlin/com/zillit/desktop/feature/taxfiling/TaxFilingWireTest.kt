package com.zillit.desktop.feature.taxfiling

import com.zillit.desktop.feature.taxfiling.data.BoxMappingDto
import com.zillit.desktop.feature.taxfiling.data.CoaRowDto
import com.zillit.desktop.feature.taxfiling.data.DraftResponseDto
import com.zillit.desktop.feature.taxfiling.data.FiledReturnDto
import com.zillit.desktop.feature.taxfiling.data.LedgerExportDto
import com.zillit.desktop.feature.taxfiling.data.ObligationDto
import com.zillit.desktop.feature.taxfiling.data.TaxCompanyDto
import com.zillit.desktop.feature.taxfiling.data.TaxFilingDto
import com.zillit.desktop.feature.taxfiling.data.TaxRegistrationDto
import com.zillit.desktop.feature.taxfiling.data.TrackingSetDto
import com.zillit.desktop.feature.taxfiling.data.boxMapBody
import com.zillit.desktop.feature.taxfiling.data.postableCodes
import com.zillit.desktop.feature.taxfiling.data.registrationBody
import com.zillit.desktop.feature.taxfiling.data.submitBody
import com.zillit.desktop.feature.taxfiling.data.syncBody
import com.zillit.desktop.feature.taxfiling.domain.BoxMapping
import com.zillit.desktop.feature.taxfiling.domain.FraudSignals
import com.zillit.desktop.feature.taxfiling.domain.RegistrationRequest
import com.zillit.desktop.feature.taxfiling.domain.VatBox
import com.zillit.desktop.feature.taxfiling.domain.VatReturn
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

/**
 * The shapes this service actually speaks, transcribed from the web's own
 * client, its VAT return view and the live catalogue.
 *
 * Written from response bodies rather than from the DTOs: a test that builds a
 * DTO and round-trips it agrees with itself even when the field is read under
 * the wrong key, and every bug here has the same signature — decodes cleanly,
 * renders zero, files zero.
 */
class TaxFilingWireTest {

    /** The catalogue as `taxfilingapi-dev` answered it on 2026-09-12, flag included. */
    @Test
    fun `the catalogue carries the country's flag`() {
        val filing = json.decodeFromString(
            TaxFilingDto.serializer(),
            """{"country":"GB","countryName":"United Kingdom","flag":"🇬🇧","regime":"VAT","key":"mtd-vat",
              "title":"MTD VAT","subtitle":"HMRC · Making Tax Digital",
              "description":"Register VAT numbers, connect to HMRC, and file returns."}""",
        ).toDomain()

        assertEquals("🇬🇧", filing.flag)
        assertEquals("mtd-vat", filing.key)
        assertEquals("HMRC · Making Tax Digital", filing.subtitle)
    }

    /** The companies list orders the catalogue by country, so the code must survive the read. */
    @Test
    fun `a company keeps its country code, upper-cased`() {
        val company = json.decodeFromString(
            TaxCompanyDto.serializer(),
            """{"id":"co-1","name":"Zillit Films Ltd","country_code":"gb"}""",
        ).toDomain()

        assertEquals("GB", company.countryCode)
        assertEquals("Zillit Films Ltd (GB)", company.pickerLabel)
    }

    /** `connected` has been seen as a boolean and as a flag; both read. */
    @Test
    fun `a registration reads its connection whichever way it is spelled`() {
        val rows = json.decodeFromString(
            ListSerializer(TaxRegistrationDto.serializer()),
            """[{"id":"r1","company_id":"co-1","registration_number":"123456789","connected":true},
               {"id":"r2","company_id":"co-2","connected":1,"status":""},
               {"id":"r3","company_id":"co-3","connected":"false"}]""",
        ).map { it.toDomain() }

        assertEquals(listOf(true, true, false), rows.map { it.connected })
        assertEquals("active", rows[1].status)
    }

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
              "totalVatDue":1000.5,"vatReclaimedCurrPeriod":"250.25","netVatDue":750.25,
              "totalValueSalesExVAT":5000,"totalValuePurchasesExVAT":1200,
              "totalValueGoodsSuppliedExVAT":0,"totalAcquisitionsExVAT":0},
             "diagnostics":{"rowsInScope":42,"nullCompanyRows":"3"}}
            """.trimIndent(),
        ).toDomain(json)

        assertEquals(1000.5, draft.vatReturn[VatBox.DueOnSales])
        // Postgres numerics arrive as text; they are still figures.
        assertEquals(250.25, draft.vatReturn[VatBox.ReclaimedOnPurchases])
        assertEquals("18A1", draft.vatReturn.periodKey)
        assertEquals(42, draft.diagnostics.rowsInScope)
        assertEquals(3, draft.diagnostics.nullCompanyRows)
        assertFalse(draft.isAllZero)
    }

    /** A mapping that selects nothing produces a draft the web's message explains. */
    @Test
    fun `a draft of nothing is recognised as all zero and says why`() {
        val draft = json.decodeFromString(
            DraftResponseDto.serializer(),
            """{"payload":{"periodKey":"18A2"},"diagnostics":{"rowsInScope":0,"nullCompanyRows":4}}""",
        ).toDomain(json)

        assertTrue(draft.isAllZero)
        val message = draft.allZeroExplanation
        assertTrue(message.startsWith("All boxes are £0. Ledger rows for this company in the obligation period: 0."))
        assertTrue(message.contains("(4 project GL row(s) have no company assigned.)"))
        assertTrue(message.endsWith("set a per-box date range to include other dates."))
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
        assertEquals("2026-01-01 → 2026-03-31 · 18A1", rows[0].pickerLabel)
        assertEquals("2025-12-31", rows[1].end)
        assertTrue(rows[0].isOpen)
        assertFalse(rows[1].isOpen)
    }

    /**
     * Fulfilment is HMRC's status, not the received date.
     *
     * A period can be fulfilled by a return HMRC has not stamped yet; reading
     * the date instead offers to file it a second time.
     */
    @Test
    fun `a fulfilled period with no received date is still closed`() {
        val row = json.decodeFromString(ObligationDto.serializer(), """{"periodKey":"18A1","status":"F"}""")
            .toDomain()

        assertFalse(row.isOpen)
    }

    /** A row that predates the status field falls back to the received date. */
    @Test
    fun `an obligation with no status reads the received date instead`() {
        val open = json.decodeFromString(ObligationDto.serializer(), """{"periodKey":"18A1"}""").toDomain()
        val done = json.decodeFromString(
            ObligationDto.serializer(),
            """{"periodKey":"18A1","received":"2026-05-01"}""",
        ).toDomain()

        assertTrue(open.isOpen)
        assertFalse(done.isOpen)
    }

    /**
     * A saved box: slot or bare number, window edges as epoch milliseconds in
     * a number or in text, flags as the driver sends them.
     */
    @Test
    fun `a saved box is read whichever way its fields arrive`() {
        val rows = json.decodeFromString(
            ListSerializer(BoxMappingDto.serializer()),
            """
            [{"box":"box1","codes":["4000"," ","4010"],"layers":{"set-loc":"LOC-LON"},"tags":["VFX"],
              "date_from":1767225600000,"date_to":"1774915200000","mark_zero":false},
             {"box":6,"codes":"[\"5000\"]","mark_zero":"true"},
             {"box":"box3","codes":["9999"]},
             {"box":"box42","codes":["1"]}]
            """.trimIndent(),
        ).mapNotNull { it.toDomain() }

        assertEquals(listOf("box1", "box6"), rows.map { it.box })
        assertEquals(listOf("4000", "4010"), rows[0].codes)
        assertEquals(mapOf("set-loc" to "LOC-LON"), rows[0].layers)
        assertEquals("2026-01-01", rows[0].fromDate)
        assertEquals("2026-03-31", rows[0].toDate)
        // A JSON-encoded list and a string boolean — an unparsed jsonb column.
        assertEquals(listOf("5000"), rows[1].codes)
        assertTrue(rows[1].markZero)
    }

    /**
     * HMRC's declaration flag rides in the payload.
     *
     * `finalised` is what makes the submission a legal return rather than a
     * draft; without it HMRC refuses the call.
     */
    @Test
    fun `a submission declares itself finalised`() {
        val body = submitBody("18A1", VatReturn(mapOf(VatBox.DueOnSales to 100.0)), FraudSignals())

        val payload = body["payload"]?.jsonObject
        assertNotNull(payload)
        assertEquals(true, payload["finalised"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("18A1", payload["periodKey"]?.jsonPrimitive?.content)
    }

    /** The draft's period wins over the screen's: the figures were computed for it. */
    @Test
    fun `the draft's own period key is what is filed`() {
        val body = submitBody("18A2", VatReturn(periodKey = "18A1"), FraudSignals())

        assertEquals("18A1", body["periodKey"]?.jsonPrimitive?.content)
        assertEquals("18A1", body["payload"]?.jsonObject?.get("periodKey")?.jsonPrimitive?.content)
    }

    /**
     * Boxes 3 and 5 are recomputed on the way out, to the penny.
     *
     * They are the two figures HMRC checks against the others — and a sum of
     * two doubles is `0.30000000000000004` often enough that an unrounded box
     * 3 would carry a third decimal HMRC refuses.
     */
    @Test
    fun `the computed boxes are recalculated and rounded before filing`() {
        val body = submitBody(
            "18A1",
            VatReturn(
                mapOf(
                    VatBox.DueOnSales to 0.1,
                    VatBox.DueOnAcquisitions to 0.2,
                    VatBox.TotalDue to 5.0,
                    VatBox.ReclaimedOnPurchases to 250.0,
                    VatBox.NetDue to 5.0,
                ),
            ),
            FraudSignals(),
        )

        val payload = body["payload"]?.jsonObject
        assertEquals(0.3, payload?.get(VatBox.TotalDue.field)?.jsonPrimitive?.doubleOrNull)
        // A reclaim is filed as its size; HMRC infers the direction.
        assertEquals(249.7, payload?.get(VatBox.NetDue.field)?.jsonPrimitive?.doubleOrNull)
    }

    /** The ledger boxes file exactly as the server built them; re-rounding them invents a rule. */
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
        assertEquals("UTC+01:00", submit["fraudData"]?.jsonObject?.get("timezone")?.jsonPrimitive?.content)
    }

    /**
     * The saved map: rows that select nothing are left out — the PUT replaces
     * everything, so an empty row stores a mapping nobody made — codes are
     * trimmed, and the window goes as UTC-midnight milliseconds.
     */
    @Test
    fun `the saved map drops empty rows and sends the window as milliseconds`() {
        val body = boxMapBody(
            listOf(
                BoxMapping(box = "box8", markZero = true),
                BoxMapping(box = "box1", codes = listOf(" 4000 ", ""), fromDate = "2026-01-01", toDate = "2026-03-31"),
                BoxMapping(box = "box2"),
                BoxMapping(box = "box4", fromDate = "2026-01-01"),
            ),
        )

        val rows = body["rows"] as JsonArray
        assertEquals(listOf("box1", "box8"), rows.map { (it as JsonObject)["box"]?.jsonPrimitive?.content })
        val first = rows[0].jsonObject
        assertEquals(listOf("4000"), first["codes"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals(1_767_225_600_000L, first["date_from"]?.jsonPrimitive?.longOrNull)
        assertEquals(1_774_915_200_000L, first["date_to"]?.jsonPrimitive?.longOrNull)
        assertEquals(JsonNull, rows[1].jsonObject["date_from"])
    }

    /**
     * A registration names its authority.
     *
     * The web sends the country and regime with every registration, and a
     * blank date as null — a string the server would try to parse as a date.
     */
    @Test
    fun `a registration is sent with its country, regime and optional date`() {
        val undated = registrationBody(
            RegistrationRequest(companyId = "co-1", registrationNumber = "123456789", filingFrequency = "quarterly"),
        )
        val dated = registrationBody(
            RegistrationRequest(
                companyId = "co-1",
                registrationNumber = "123 456 789",
                filingFrequency = "monthly",
                registrationDate = "2026-04-01",
            ),
        )

        assertEquals("GB", undated["country_code"]?.jsonPrimitive?.content)
        assertEquals("VAT", undated["regime"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, undated["registration_date"])
        assertEquals("2026-04-01", dated["registration_date"]?.jsonPrimitive?.content)
        assertEquals("123456789", dated["registration_number"]?.jsonPrimitive?.content)
        assertEquals("monthly", dated["filing_frequency"]?.jsonPrimitive?.content)
    }

    /** A receipt arrives as an object or as the JSON text it was stored as. */
    @Test
    fun `a filed return is read whether its payload is an object or a string`() {
        val rows = json.decodeFromString(
            ListSerializer(FiledReturnDto.serializer()),
            """
            [{"period_key":"18A1","payload":{"vatDueSales":1000.5,"totalValueSalesExVAT":5000},
              "receipt":{"formBundleNumber":"891614-1","processingDate":"2026-05-02T10:00:00Z"}},
             {"period_key":"17A4","payload":"{\"vatDueSales\":800}",
              "receipt":"{\"reference\":\"771234-9\"}"},
             {"period_key":"17A3","payload":null,"receipt":null}]
            """.trimIndent(),
        ).map { it.toDomain(json) }

        assertEquals(1000.5, rows[0].values[VatBox.DueOnSales])
        assertEquals("891614-1", rows[0].reference)
        assertEquals("2026-05-02T10:00:00Z", rows[0].processedAt)
        assertEquals(800.0, rows[1].values[VatBox.DueOnSales])
        assertEquals("771234-9", rows[1].reference)
        assertFalse(rows[2].hasFigures)
    }

    /** Ledger rows carry `numeric` columns as text; the export still gets numbers. */
    @Test
    fun `a ledger row reads its amounts and period from text`() {
        val export = json.decodeFromString(
            LedgerExportDto.serializer(),
            """{"rows":[{"box":"box1","account_code":4000,"debit":"0","credit":"1200.50",
              "period_year":"2026","period_month":4,"tracking_codes":{"dept":"CAM"},"memo":null}]}""",
        )
        val line = export.rows.orEmpty().single().toDomain()

        assertEquals("4000", line.accountCode)
        assertEquals(1200.5, line.credit)
        assertEquals("2026-04", line.periodLabel)
        assertEquals("dept:CAM", line.trackingLabel)
        assertEquals("", line.memo)
    }

    /**
     * The codes picker offers what the web's does: nominals and codes, never
     * headers or sections, never a code with Posting unticked — in numeric order.
     */
    @Test
    fun `the chart offers postable codes in numeric order`() {
        val rows = json.decodeFromString(
            ListSerializer(CoaRowDto.serializer()),
            """
            [{"code":"1000","name":"Header","line_type":"header"},
             {"code":"125L","name":"Lettered","line_type":"category"},
             {"code":"4000","name":"Sales","line_type":"category"},
             {"code":"200","name":"Section","line_type":"section"},
             {"code":"30","name":"Camera","line_type":"sub_category"},
             {"code":"31","name":"No posting","line_type":"sub_category","posting_box":false},
             {"code":"1100-10","name":"Budget code","line_type":"sub_category"},
             {"code":"50","name":"Legacy row"}]
            """.trimIndent(),
        ).postableCodes()

        assertEquals(listOf("30", "50", "1100-10", "4000", "125L"), rows.map { it.code })
    }

    /** Layers: inactive sets and headers are not offered, and codes keep the chart's order. */
    @Test
    fun `a tracking set offers its active leaves in the chart's order`() {
        val sets = json.decodeFromString(
            ListSerializer(TrackingSetDto.serializer()),
            """
            [{"id":"set-loc","name":"Locations","prefix":"LOC","color":"#3B82F6","active":true,"nodes":[
               {"id":"n1","code":"LOC-LON","label":"London","sort_order":2},
               {"id":"n0","code":"LOC-UK","label":"UK","is_header":true,"sort_order":0},
               {"id":"n2","code":"LOC-MAN","label":"Manchester","sort_order":1,"description":"Stage 3"},
               {"id":"n3","code":"LOC-OLD","label":"Old","active":false,"sort_order":0}]},
             {"id":"set-ep","name":"Episodes","active":false,"nodes":[]}]
            """.trimIndent(),
        ).mapNotNull { it.toDomain() }

        assertEquals(listOf("set-loc"), sets.map { it.id })
        assertEquals(listOf("LOC-MAN", "LOC-LON"), sets.single().codes.map { it.code })
        assertEquals("LOC-MAN · Manchester", sets.single().codes.first().pickerLabel)
        assertEquals("LOC-LON", sets.single().codeFor("n1")?.code)
        assertNull(sets.single().codeFor("nope"))
    }
}
