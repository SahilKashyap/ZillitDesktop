package com.zillit.desktop.feature.payroll

import com.zillit.desktop.feature.payroll.data.compareChartCodes
import com.zillit.desktop.feature.payroll.data.toPostableAccounts
import com.zillit.desktop.feature.payroll.data.toTaxTypes
import com.zillit.desktop.feature.payroll.domain.Journal
import com.zillit.desktop.feature.payroll.domain.JournalCategory
import com.zillit.desktop.feature.payroll.domain.JournalEdit
import com.zillit.desktop.feature.payroll.domain.JournalRow
import com.zillit.desktop.feature.payroll.domain.PayrollTaxType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun taxLine(base: Double? = 200.0, rate: Double? = null, type: String = "", amount: Double? = null) =
    JournalRow(
        id = "tax::tc1",
        timecardId = "tc1",
        companyId = "co",
        src = Journal.SRC_TAX,
        groupKey = Journal.SRC_TAX,
        identifier = Journal.SRC_TAX,
        label = "Tax",
        category = JournalCategory.Tax,
        description = "Week Ada Tax",
        descriptionOverride = "",
        amount = amount,
        code = "2200",
        effectiveDate = "2026-05-08",
        taxType = type,
        taxRate = rate,
        taxBase = base,
        taxSaved = true,
    )

private val TYPES = listOf(
    PayrollTaxType("vat_20", "VAT", 20.0, "United Kingdom"),
    PayrollTaxType("vat_5", "Reduced VAT", 5.5, "United Kingdom"),
    PayrollTaxType("gst", "GST", 10.0, "Australia"),
    PayrollTaxType("house", "House rate", null, null),
)

/**
 * The journal's tax line: a type picker over Production Setup's tax types
 * with a custom-rate escape, and the figure and the rate kept in step in both
 * directions — the web's `TaxCell` and its debit cell's back-solve.
 */
class JournalTaxPickerTest {

    private val options = Journal.taxOptions(TYPES)

    @Test
    fun `the options read Label and rate, by country, with Other last`() {
        assertEquals(
            listOf("gst", "vat_20", "vat_5", "house", "other"),
            options.map { it.value },
            "Australia before the United Kingdom, the custom ones after, Other last",
        )
        assertEquals("VAT · 20%", options.first { it.value == "vat_20" }.label)
        assertEquals("Reduced VAT · 5.5%", options.first { it.value == "vat_5" }.label)
        // A type saved without a rate has no suffix and stamps no rate.
        assertEquals("House rate", options.first { it.value == "house" }.label)
        assertNull(options.first { it.value == "house" }.percent)
    }

    @Test
    fun `picking a type stamps its rate and drops a typed figure`() {
        val line = taxLine()
        val typed = JournalEdit(amount = 99.0)
        val picked = Journal.pickTaxType(line, typed, "vat_20", options)
        assertEquals("vat_20", picked.taxType)
        assertEquals(20.0, Journal.rateOf(line, picked))
        assertNull(picked.amount)
        // The figure follows the rate again: 20% of the 200 base.
        assertEquals(40.0, Journal.amountOf(line, picked))
    }

    @Test
    fun `a type without a rate leaves the line with none, not the saved one`() {
        val line = taxLine(rate = 20.0, type = "vat_20")
        val picked = Journal.pickTaxType(line, JournalEdit(), "house", options)
        assertNull(Journal.rateOf(line, picked), "an edit that clears the rate must beat the row's own")
    }

    @Test
    fun `only Other to Other keeps the custom rate`() {
        val line = taxLine(rate = 12.5, type = "other")
        assertEquals(12.5, Journal.rateOf(line, Journal.pickTaxType(line, JournalEdit(), "other", options)))
        val named = taxLine(rate = 20.0, type = "vat_20")
        assertNull(Journal.rateOf(named, Journal.pickTaxType(named, JournalEdit(), "other", options)))
    }

    @Test
    fun `a typed figure turns the type to Other and back-solves the rate`() {
        val line = taxLine(base = 178.46, rate = 20.0, type = "vat_20")
        val typed = Journal.typeTaxAmount(line, JournalEdit(), 35.69)
        assertEquals("other", typed.taxType)
        assertEquals(20.0, Journal.rateOf(line, typed))
        assertEquals(35.69, Journal.amountOf(line, typed))
    }

    @Test
    fun `a figure the percent field could not hold stores no rate`() {
        val line = taxLine(base = 178.46, rate = 20.0, type = "vat_20")
        // £500 on a £178.46 base is 280%: no valid rate, so none rather than a clamped one.
        val over = Journal.typeTaxAmount(line, JournalEdit(), 500.0)
        assertEquals("other", over.taxType)
        assertNull(Journal.rateOf(line, over))
        assertEquals(500.0, Journal.amountOf(line, over))
        // And no base to solve against.
        assertNull(Journal.impliedRate(0.0, 10.0))
        assertNull(Journal.impliedRate(null, 10.0))
    }

    @Test
    fun `the back-solve rounds half up to two places`() {
        assertEquals(16.67, Journal.impliedRate(300.0, 50.0))
        assertEquals(0.0, Journal.impliedRate(300.0, 0.0))
        assertEquals(100.0, Journal.impliedRate(300.0, 300.0))
    }

    @Test
    fun `the custom rate is clamped, blank is none, and nonsense is ignored`() {
        val line = taxLine(rate = 20.0, type = "other")
        assertEquals(100.0, Journal.rateOf(line, Journal.typeTaxRate(JournalEdit(), "250")))
        assertNull(Journal.rateOf(line, Journal.typeTaxRate(JournalEdit(), "  ")))
        val kept = JournalEdit(taxRate = 7.0)
        assertEquals(kept, Journal.typeTaxRate(kept, "abc"))
    }

    @Test
    fun `the wire carries the figure the grid shows, or zero for a tax line`() {
        val line = taxLine(base = 200.0, rate = 20.0, type = "vat_20")
        val derived = Journal.lineFor(line, null).single()
        assertEquals(40.0, derived.debit)
        assertEquals("vat_20", derived.taxType)
        assertEquals(20.0, derived.taxRate)
        // No rate, nothing typed: zero, not a null the server would read as "resolve it yourself".
        assertEquals(0.0, Journal.lineFor(taxLine(), null).single().debit)
    }

    @Test
    fun `a saved type that is no longer offered still reads`() {
        // The picker adds it back as an option (see the journal page); the wire just round-trips it.
        val line = taxLine(rate = 15.0, type = "retired")
        assertEquals("retired", Journal.lineFor(line, null).single().taxType)
    }

    @Test
    fun `tax types come off the project settings document, in either envelope`() {
        val doc = Json.parseToJsonElement(
            """{"project_id":"p","settings":{"tax_types":[
                {"type":"vat","identifier":"vat_20","label":"VAT","value":20,"country":"United Kingdom"},
                {"type":"vat","identifier":"vat_5","label":"Reduced","value":"5.5%","country_code":"GB"},
                {"identifier":"house","label":"House","value":null},
                {"label":"no identifier","value":3}]}}""",
        )
        val types = doc.toTaxTypes()
        assertEquals(listOf("vat_20", "vat_5", "house"), types.map { it.identifier })
        assertEquals(listOf(20.0, 5.5, null), types.map { it.percent })
        assertEquals(listOf("United Kingdom", "GB", null), types.map { it.country })
        val wrapped = Json.parseToJsonElement(
            """{"value":{"settings":{"tax_types":[{"identifier":"a","label":"A","value":1}]}}}""",
        )
        assertEquals(1, wrapped.toTaxTypes().size)
    }

    @Test
    fun `the code picker offers postable accounts only, in numeric order`() {
        val chart = Json.parseToJsonElement(
            """[{"code":"4100","name":"Props","line_type":"category"},
                {"code":"4000","name":"Head","line_type":"header"},
                {"code":"4200","name":"Off","line_type":"sub_category","posting_box":false},
                {"code":"125L","name":"Odd","line_type":"sub_category"},
                {"code":"900","name":"Legacy"},
                {"code":"4100","name":"Duplicate","line_type":"category"},
                {"code":"1100","line_type":"category"}]""",
        ) as JsonArray
        val accounts = chart.map { it as JsonObject }.toPostableAccounts()
        assertEquals(listOf("900", "1100", "4100", "125L"), accounts.map { it.code })
        assertEquals("1100", accounts[1].caption, "an account with no name is its bare code")
        assertEquals("4100 — Props", accounts[2].caption)
        assertTrue(compareChartCodes("9", "10") < 0)
    }
}
