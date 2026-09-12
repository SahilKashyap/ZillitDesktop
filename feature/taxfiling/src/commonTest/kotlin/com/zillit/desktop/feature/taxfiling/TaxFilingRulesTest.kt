package com.zillit.desktop.feature.taxfiling

import com.zillit.desktop.feature.taxfiling.domain.BoxMapping
import com.zillit.desktop.feature.taxfiling.domain.CoaCode
import com.zillit.desktop.feature.taxfiling.domain.CoaCodes
import com.zillit.desktop.feature.taxfiling.domain.SupportedFiling
import com.zillit.desktop.feature.taxfiling.domain.TaxDates
import com.zillit.desktop.feature.taxfiling.domain.TaxFiling
import com.zillit.desktop.feature.taxfiling.domain.TaxFilingRoute
import com.zillit.desktop.feature.taxfiling.domain.TaxFormat
import com.zillit.desktop.feature.taxfiling.domain.TaxRegistration
import com.zillit.desktop.feature.taxfiling.domain.VatBox
import com.zillit.desktop.feature.taxfiling.domain.VatReturn
import com.zillit.desktop.feature.taxfiling.domain.exportFileName
import com.zillit.desktop.feature.taxfiling.domain.ledgerFileName
import com.zillit.desktop.feature.taxfiling.domain.roundPence
import com.zillit.desktop.feature.taxfiling.ui.CatalogState
import com.zillit.desktop.feature.taxfiling.ui.RegistrationDraft
import com.zillit.desktop.feature.taxfiling.ui.pages.summaryLine
import com.zillit.desktop.feature.taxfiling.ui.pages.toColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The arithmetic, the formats and the small rules the screens lean on. */
class TaxFilingRulesTest {

    // -- the nine boxes ---------------------------------------------------------------

    /** Box 3 is boxes 1 and 2 added, whatever the draft said it was. */
    @Test
    fun `box three is the sum of boxes one and two`() {
        val computed = VatReturn(
            mapOf(VatBox.DueOnSales to 900.0, VatBox.DueOnAcquisitions to 100.0, VatBox.TotalDue to 7.0),
        ).computed()

        assertEquals(1000.0, computed[VatBox.TotalDue])
    }

    /**
     * Box 5 is the amount, never a negative; its sign is kept apart.
     *
     * HMRC takes the figure and infers the direction from boxes 3 and 4, so a
     * reclaim is filed positive — and the screen still says it is a reclaim.
     */
    @Test
    fun `box five is the absolute difference, with its direction kept apart`() {
        val reclaim = VatReturn(mapOf(VatBox.DueOnSales to 100.0, VatBox.ReclaimedOnPurchases to 400.0))

        assertEquals(300.0, reclaim.computed()[VatBox.NetDue])
        assertEquals(-300.0, reclaim.netSigned)
        assertFalse(reclaim.isPayable)
    }

    @Test
    fun `a return that owes money is payable`() {
        val owed = VatReturn(mapOf(VatBox.DueOnSales to 900.0, VatBox.ReclaimedOnPurchases to 250.0))

        assertEquals(650.0, owed.computed()[VatBox.NetDue])
        assertTrue(owed.isPayable)
    }

    /** Empty boxes count as zero rather than dropping out of the arithmetic. */
    @Test
    fun `an empty return computes to zero rather than to nothing`() {
        val computed = VatReturn().computed()

        assertEquals(0.0, computed[VatBox.TotalDue])
        assertEquals(0.0, computed[VatBox.NetDue])
        assertTrue(VatReturn().isPayable)
    }

    /** JavaScript's `Math.round`, not Kotlin's: a half penny goes up, as the web rounds it. */
    @Test
    fun `pence round half up`() {
        assertEquals(0.3, roundPence(0.1 + 0.2))
        assertEquals(2.68, roundPence(2.675_000_000_001))
        assertEquals(-2.0, roundPence(-2.005))
    }

    /** Boxes 3 and 5 are not offered for mapping; the other seven are, in HMRC's order. */
    @Test
    fun `the computed boxes are not mappable`() {
        assertEquals(listOf(1, 2, 4, 6, 7, 8, 9), VatBox.mappable.map { it.number })
        assertEquals(listOf(0, 0, 0, 0), VatBox.entries.filter { it.wholePounds }.map { it.decimals })
    }

    /** A slot is the box's number; older rows saved the bare number, and both find it. */
    @Test
    fun `a box is found by its slot or its number`() {
        assertEquals("box9", VatBox.AcquisitionsExVat.slot)
        assertEquals(VatBox.SalesExVat, VatBox.bySlot("box6"))
        assertEquals(VatBox.SalesExVat, VatBox.bySlot("6"))
        assertNull(VatBox.bySlot("box10"))
        assertEquals(VatBox.NetDue, VatBox.byField("netVatDue"))
    }

    /** A date window alone selects nothing, so it does not make a box "mapped". */
    @Test
    fun `a box is configured by codes, layers, tags or zero — not by dates alone`() {
        assertFalse(BoxMapping(box = "box1", fromDate = "2026-01-01").isConfigured)
        assertTrue(BoxMapping(box = "box1", tags = listOf("VFX")).isConfigured)
        assertTrue(BoxMapping(box = "box1", markZero = true).isConfigured)
        assertTrue(BoxMapping(box = "box1", fromDate = "2026-01").hasInvalidDate)
        assertFalse(BoxMapping(box = "box1", fromDate = "2026-01-31").hasInvalidDate)
    }

    /** The collapsed row's line, as the web writes it. */
    @Test
    fun `a mapping summarises as two codes, then layers and tags`() {
        val mapping = BoxMapping(
            box = "box1",
            codes = listOf("4000", "4010", "4020", "4030"),
            layers = mapOf("set-loc" to "LOC-LON"),
            tags = listOf("VFX", "CAMERA"),
        )

        assertEquals("4000, 4010 +2  ·  1 layer  ·  2 tags", summaryLine(mapping))
    }

    // -- routes, formats and dates ----------------------------------------------------

    @Test
    fun `routes parse to the catalogue or a filing`() {
        assertEquals(TaxFilingRoute.Catalog, TaxFilingRoute.parse("/film-tools/account-hub/tax-filing"))
        assertEquals(TaxFilingRoute.Catalog, TaxFilingRoute.parse("/film-tools/account-hub/tax-filing/GB"))
        assertEquals(TaxFilingRoute.Catalog, TaxFilingRoute.parse("/film-tools/account-hub/tax-filingx/GB/mtd-vat"))
        val filing = TaxFilingRoute.parse("/film-tools/account-hub/tax-filing/gb/mtd-vat?connected=1")
        assertEquals(TaxFilingRoute.Filing("GB", "mtd-vat"), filing)
        assertEquals(SupportedFiling.MtdVat, (filing as TaxFilingRoute.Filing).supported)
        assertNull(TaxFilingRoute.Filing("FR", "tva").supported)
        assertEquals("/film-tools/account-hub/tax-filing/GB/mtd-vat", SupportedFiling.MtdVat.route.path)
    }

    @Test
    fun `numbers and frequencies are written as the web writes them`() {
        assertEquals("123 456 789", TaxFormat.vrn("123456789"))
        assertEquals("GB123", TaxFormat.vrn("GB123"))
        assertEquals("Annually", TaxFormat.frequency("annual"))
        assertEquals("Weekly", TaxFormat.frequency("weekly"))
        assertEquals("—", TaxFormat.frequency(""))
        assertEquals("£1,234.56", TaxFormat.gbp(1234.56))
        assertEquals("£5,000", TaxFormat.gbp(5000.0, 0))
        assertEquals("—", TaxFormat.gbp(null))
    }

    @Test
    fun `a date window is UTC midnight either way`() {
        assertEquals(1_767_225_600_000L, TaxDates.toMillis("2026-01-01"))
        assertEquals("2026-01-01", TaxDates.toYmd(1_767_225_600_000L))
        assertNull(TaxDates.toMillis("2026-02-30"))
        assertNull(TaxDates.toMillis(""))
        assertEquals("", TaxDates.toYmd(null))
    }

    /** A period key or a company name from the server does not become a path. */
    @Test
    fun `export file names keep to safe characters`() {
        assertEquals("vat-ledger_18A1_2026-09-12_1430.xlsx", ledgerFileName("18A1", "2026-09-12_1430"))
        assertEquals("vat-ledger_period.xlsx", ledgerFileName("../..", ""))
        assertEquals(
            "tax-filing-zillit-films-ltd-123456789.json",
            exportFileName(
                TaxRegistration(id = "r1", companyName = "Zillit Films Ltd", registrationNumber = "123456789"),
            ),
        )
        assertEquals(
            "tax-filing-company-r1.json",
            exportFileName(TaxRegistration(id = "r1")),
        )
    }

    // -- pickers ----------------------------------------------------------------------

    /** The web's `rankRows`: an exact code, then code prefixes, name prefixes, and the rest. */
    @Test
    fun `codes rank by relevance and then in numeric order`() {
        val chart = listOf(
            CoaCode("1400", "Travel"),
            CoaCode("400", "Camera"),
            CoaCode("4000", "Sales"),
            CoaCode("7400", "Other"),
            CoaCode("9", "400 series note"),
        )

        assertEquals(listOf("400", "4000", "9", "1400", "7400"), CoaCodes.rank(chart, "400").map { it.code })
        assertEquals(chart, CoaCodes.rank(chart, "  "))
        assertTrue(CoaCodes.compare("2", "1000") < 0)
        assertTrue(CoaCodes.compare("1100-10", "1200") < 0)
        assertTrue(CoaCodes.compare("125L", "9999") > 0)
    }

    @Test
    fun `a typed code keeps digits and hyphens, never a leading hyphen`() {
        assertEquals("1100-10", CoaCodes.sanitise("-1100-10a"))
        assertEquals("4000", CoaCodes.sanitise(" 4 0 0 0 "))
    }

    /** Only both groups labelled; one group is a plain grid. */
    @Test
    fun `the catalogue splits into your countries and the rest`() {
        val gb = TaxFiling(country = "GB", key = "mtd-vat")
        val ie = TaxFiling(country = "IE", key = "ros-vat")

        assertTrue(CatalogState(filings = listOf(gb, ie), companyCountries = setOf("GB")).grouped)
        assertFalse(CatalogState(filings = listOf(gb, ie), companyCountries = emptySet()).grouped)
        assertEquals(listOf(ie), CatalogState(filings = listOf(gb, ie), companyCountries = setOf("GB")).others)
    }

    @Test
    fun `a registration draft needs a company, nine digits and a real date if any`() {
        assertFalse(RegistrationDraft(companyId = "co-1", registrationNumber = "12345678").canSubmit)
        assertTrue(RegistrationDraft(companyId = "co-1", registrationNumber = "123456789").canSubmit)
        assertFalse(RegistrationDraft(registrationNumber = "123456789").canSubmit)
        assertFalse(
            RegistrationDraft(companyId = "co-1", registrationNumber = "123456789", registrationDate = "01/04/2026")
                .canSubmit,
        )
        assertEquals("123456789", RegistrationDraft.cleanNumber("GB123 456 7890"))
    }

    @Test
    fun `a layer colour is read from its hex`() {
        assertEquals(0xFF3B82F6.toInt(), "#3B82F6".toColor()?.let { colorBits(it) })
        assertNull("blue".toColor())
    }

    private fun colorBits(color: androidx.compose.ui.graphics.Color): Int =
        ((color.alpha * 255).toInt() shl 24) or
            ((color.red * 255 + 0.5f).toInt() shl 16) or
            ((color.green * 255 + 0.5f).toInt() shl 8) or
            (color.blue * 255 + 0.5f).toInt()
}
