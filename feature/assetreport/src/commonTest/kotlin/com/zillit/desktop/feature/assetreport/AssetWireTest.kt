package com.zillit.desktop.feature.assetreport

import com.zillit.desktop.feature.assetreport.data.LineDto
import com.zillit.desktop.feature.assetreport.data.RecordDto
import com.zillit.desktop.feature.assetreport.data.epochMillis
import com.zillit.desktop.feature.assetreport.data.exportBody
import com.zillit.desktop.feature.assetreport.domain.AssetAttachment
import com.zillit.desktop.feature.assetreport.domain.AssetCategory
import com.zillit.desktop.feature.assetreport.domain.AssetCurrencies
import com.zillit.desktop.feature.assetreport.domain.AssetCurrency
import com.zillit.desktop.feature.assetreport.domain.AssetExportFormat
import com.zillit.desktop.feature.assetreport.domain.AssetFileRules
import com.zillit.desktop.feature.assetreport.domain.AssetFormat
import com.zillit.desktop.feature.assetreport.domain.ExpenditureType
import kotlinx.datetime.TimeZone
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The register's wire and arithmetic, each rule one some client already got wrong. */
class AssetWireTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Test
    fun `the feed's aliases coalesce and po_ spellings win`() {
        val lines = json.decodeFromString(
            ListSerializer(LineDto.serializer()),
            """
            [
              {"id":"l1","description":"Dolly","vendor":"v9","department":"d3","currency":"GBP",
               "quantity":2,"unit_price":100.5,"total":201.0,"expenditure_type":"Rent",
               "rental_start":1700000000000,"rental_end":"2026-09-30","po_number":"PO-7","po_id":"p1",
               "asset_id":"a1","category":"Keep"},
              {"id":"l2","po_vendor_id":"v1","vendor":"ignored","po_currency":"USD","currency":"GBP",
               "expenditure_type":"Consumption","is_tax":true,"total":"40.00"},
              {"description":"no id"}
            ]
            """,
        ).mapNotNull { it.toModel() }

        assertEquals(2, lines.size, "a row without a line id is not a line")
        assertEquals("v9", lines[0].vendorId, "the alias fills the po_ spelling")
        assertEquals("v1", lines[1].vendorId, "the po_ spelling wins when both arrive")
        assertEquals("USD", lines[1].currency)
        assertEquals(ExpenditureType.Rent, lines[0].expenditureType)
        assertEquals("Consumables", lines[1].expenditureType.label, "the web's word for Consumption")
        assertEquals(40.0, lines[1].total, "a quoted figure costs nothing")
        assertEquals(AssetCategory.Keep, lines[0].category)
        assertEquals("a1", lines[0].assetId)
        assertEquals(1_700_000_000_000L, lines[0].rentalStartMillis)
        assertTrue((lines[0].rentalEndMillis ?: 0) > 0, "an ISO day reads as a day, not as nothing")
    }

    @Test
    fun `one odd field never empties the register`() {
        val lines = json.decodeFromString(
            ListSerializer(LineDto.serializer()),
            """[{"id":"l1","currency":{"code":"EUR","symbol":"€"},"department":{"_id":"d9"},"quantity":null}]""",
        ).mapNotNull { it.toModel() }
        assertEquals("EUR", lines.single().currency)
        assertEquals("d9", lines.single().departmentId)
        assertEquals(0.0, lines.single().quantity)
    }

    @Test
    fun `a record carries its note, its stamp and its attachments`() {
        val record = json.decodeFromString(
            RecordDto.serializer(),
            """
            {"id":"a1","line_item_id":"l1","category":"sell","comments":"In store B",
             "comment_by":"u7","comment_at":"2026-08-19T12:00:00.000Z",
             "attachments":[
               {"media":"p/actual/photo.jpg","bucket":"b","region":"eu-west-2","name":"photo.jpg",
                "content_type":"image","content_subtype":"jpg","uploaded_by":"u7"},
               {"name":"no key at all"}
             ]}
            """,
        ).toModel()

        assertEquals(AssetCategory.Sell, record.category, "the category is matched without regard to case")
        assertEquals("In store B", record.comments)
        assertEquals("u7", record.commentBy)
        assertEquals(1_787_140_800_000L, record.commentAtMillis)
        assertEquals(1, record.attachments.size, "an attachment with no key points at no file")
        assertTrue(record.attachments.single().isImage)
        assertTrue(record.attachments.single().isComplete)
    }

    @Test
    fun `dates read both spellings and nothing else`() {
        assertEquals(1_700_000_000_000L, JsonPrimitive(1_700_000_000_000L).epochMillis())
        assertEquals(1_700_000_000_000L, JsonPrimitive("1700000000000").epochMillis())
        assertEquals(1_787_140_800_000L, JsonPrimitive("2026-08-19T12:00:00Z").epochMillis())
        assertEquals(1_789_776_000_000L, JsonPrimitive("2026-09-19").epochMillis(), "a bare day is UTC midnight")
        assertNull(JsonPrimitive("soon").epochMillis())
        assertNull(JsonPrimitive(0).epochMillis(), "zero is no date, not 1970")
        assertNull(null.epochMillis())
    }

    @Test
    fun `the export body omits empty departments`() {
        val whole = exportBody(AssetExportFormat.Pdf, emptyList())
        assertEquals("\"pdf\"", whole["format"].toString())
        assertFalse("department_ids" in whole.keys, "empty means omitted, never []")

        val scoped = exportBody(AssetExportFormat.Excel, listOf("d1", "", "d2"))
        assertEquals("\"xlsx\"", scoped["format"].toString())
        assertEquals(2, (scoped["department_ids"] as JsonArray).size)
    }

    @Test
    fun `the export is named as the web names it`() {
        assertEquals(
            "asset-register_2026-09-13_0142.xlsx",
            AssetFormat.exportFileName(AssetExportFormat.Excel, 1_789_263_720_000L, TimeZone.UTC),
        )
    }

    @Test
    fun `amounts convert through the default at exr`() {
        val currencies = AssetCurrencies(
            options = listOf(
                AssetCurrency("GBP", "Pound Sterling", "£", 1.0),
                AssetCurrency("USD", "US Dollar", "$", 1.25),
                AssetCurrency("EUR", "Euro", "€", 1.2),
                AssetCurrency("JPY", "Yen", "¥", null),
            ),
            defaultCode = "GBP",
        )
        // A USD line shown in the default: ÷ exr.
        assertEquals(100.0, currencies.convert(125.0, "USD", ""))
        // …and re-expressed in EUR: × exr.
        assertEquals(120.0, currencies.convert(125.0, "USD", "EUR"), 1e-9)
        // No rate is face value, as the web takes it.
        assertEquals(500.0, currencies.convert(500.0, "JPY", "GBP"))
        assertEquals(40.0, currencies.convert(40.0, "", "GBP"), "a line with no currency is in the default")
        assertEquals("€", currencies.symbolFor("EUR"))
    }

    @Test
    fun `the default is always among the choices`() {
        val currencies = AssetCurrencies(options = listOf(AssetCurrency("USD")), defaultCode = "GBP")
        assertEquals(listOf("GBP", "USD"), currencies.choices.map { it.code })
    }

    @Test
    fun `files are images or PDFs, ten megabytes each, type checked first`() {
        assertNull(AssetFileRules.refusal("receipt.PDF", 1_000))
        assertNull(AssetFileRules.refusal("shelf.heic", AssetFileRules.MAX_BYTES))
        assertEquals(
            "notes.docx: unsupported file type. Only images and PDFs are allowed.",
            AssetFileRules.refusal("notes.docx", 20L * 1024 * 1024),
        )
        assertEquals(
            "scan.png: exceeds the 10MB per-file limit.",
            AssetFileRules.refusal("scan.png", AssetFileRules.MAX_BYTES + 1),
        )
        assertEquals("image", AssetFileRules.familyOf("shelf.JPG"))
        assertEquals("document", AssetFileRules.familyOf("receipt.pdf"))
    }

    @Test
    fun `an attachment's kind comes from its family or its extension`() {
        assertTrue(AssetAttachment(media = "k", contentType = "image").isImage)
        assertTrue(AssetAttachment(media = "k", contentSubtype = "png").isImage)
        assertFalse(AssetAttachment(media = "k", name = "receipt.pdf", contentType = "document").isImage)
        assertEquals("pdf", AssetAttachment(media = "k", contentSubtype = "PDF").extension)
        assertFalse(AssetAttachment(media = "k", bucket = "b").isComplete, "no region is a half-upload")
    }

    @Test
    fun `figures and days print like the web`() {
        assertEquals("£1,234,567.89", AssetFormat.money(1_234_567.89, "£"))
        assertEquals("$0.50", AssetFormat.money(0.5, "$"))
        assertEquals("-£42.00", AssetFormat.money(-42.0, "£"))
        assertEquals("3", AssetFormat.quantity(3.0))
        assertEquals("2.5", AssetFormat.quantity(2.5))
        assertEquals("19 Aug 2026", AssetFormat.date(1_787_140_800_000L, TimeZone.UTC))
        assertEquals(
            "19 Aug 2026 – 30 Sep 2026",
            AssetFormat.range(1_787_140_800_000L, 1_790_769_600_000L, TimeZone.UTC),
        )
        assertEquals("19 Aug 2026", AssetFormat.range(1_787_140_800_000L, null, TimeZone.UTC))
        assertEquals("", AssetFormat.date(null))
    }
}
