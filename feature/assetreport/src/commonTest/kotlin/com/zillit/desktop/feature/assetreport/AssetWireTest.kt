package com.zillit.desktop.feature.assetreport

import com.zillit.desktop.feature.assetreport.data.LineDto
import com.zillit.desktop.feature.assetreport.data.RecordDto
import com.zillit.desktop.feature.assetreport.data.exportBody
import com.zillit.desktop.feature.assetreport.data.normaliseFormat
import com.zillit.desktop.feature.assetreport.domain.AssetCategory
import com.zillit.desktop.feature.assetreport.domain.AssetLine
import com.zillit.desktop.feature.assetreport.domain.ExpenditureType
import com.zillit.desktop.feature.assetreport.domain.assetTotal
import com.zillit.desktop.feature.assetreport.domain.moneyLabel
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The register's wire rules, each one a bug some client already had. */
class AssetWireTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Test
    fun `the feed's aliases coalesce - vendor rides po_vendor_id`() {
        // The client hands the serializer the UNWRAPPED `data` — a bare array.
        val decoded = json.decodeFromString(
            ListSerializer(LineDto.serializer()),
            """
            [
              {"id":"l1","description":"Dolly","vendor":"v9","department":"d3","currency":"GBP",
               "quantity":2,"unit_price":100.5,"total":201.0,"expenditure_type":"Rent",
               "rental_start":1700000000000,"po_number":"PO-7","po_id":"p1","asset_id":"a1",
               "category":"Keep"},
              {"id":"l2","po_vendor_id":"v1","vendor":"ignored","expenditure_type":"consume",
               "is_tax":true,"total":40.0},
              {"description":"no id"}
            ]
            """,
        )
        val lines = decoded.mapNotNull { it.toModel() }

        assertEquals(2, lines.size)
        assertEquals("v9", lines[0].vendorId, "the alias fills the po_ spelling")
        assertEquals("v1", lines[1].vendorId, "the po_ spelling wins when both arrive")
        assertEquals(ExpenditureType.Rent, lines[0].expenditureType)
        assertEquals(ExpenditureType.Consumption, lines[1].expenditureType, "Android's tolerance kept")
        assertEquals(AssetCategory.Keep, lines[0].category)
        assertEquals("a1", lines[0].assetId)
        assertEquals(1700000000000L, lines[0].rentalStartMillis)
    }

    @Test
    fun `tax lines render but never count, mixed currencies refuse to sum`() {
        val a = AssetLine(lineItemId = "1", total = 100.0, currency = "GBP")
        val b = AssetLine(lineItemId = "2", total = 40.0, currency = "GBP", isTax = true)

        assertEquals(100.0 to "GBP", assetTotal(listOf(a, b)))
        assertNull(assetTotal(listOf(a, a.copy(lineItemId = "3", currency = "USD"))))
    }

    @Test
    fun `the record's polymorphic date decodes both spellings`() {
        val epoch = json.decodeFromString(
            RecordDto.serializer(),
            """{"id":"a1","comment_at":1700000000000,"comments":"note"}""",
        )
        assertEquals(1700000000000L, epoch.toModel().commentAtMillis)

        val iso = json.decodeFromString(
            RecordDto.serializer(),
            """{"id":"a1","comment_at":"2026-08-19T12:00:00.000Z"}""",
        )
        assertEquals(0L, iso.toModel().commentAtMillis, "an ISO stamp reads as no-date, never a crash")
    }

    @Test
    fun `the export body - format normalised, empty departments omitted`() {
        assertEquals("xlsx", normaliseFormat("excel"))
        assertEquals("pdf", normaliseFormat("anything"))
        assertEquals("csv", normaliseFormat("CSV"))

        val bare = exportBody("pdf", emptyList())
        assertTrue("department_ids" !in bare.keys, "empty means omitted, not []")
        val scoped = exportBody("excel", listOf("d1"))
        assertTrue("department_ids" in scoped.keys)
        assertEquals("\"xlsx\"", scoped["format"].toString())
    }

    @Test
    fun `money reads like the phones print it`() {
        assertEquals("1,234,567.89", moneyLabel(1234567.89))
        assertEquals("0.50", moneyLabel(0.5))
        assertEquals("-42.00", moneyLabel(-42.0))
    }
}
