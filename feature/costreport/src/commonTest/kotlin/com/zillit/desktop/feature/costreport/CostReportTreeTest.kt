package com.zillit.desktop.feature.costreport

import com.zillit.desktop.feature.costreport.domain.CoaLevel
import com.zillit.desktop.feature.costreport.domain.CoaRow
import com.zillit.desktop.feature.costreport.domain.CostLine
import com.zillit.desktop.feature.costreport.domain.CrNominal
import com.zillit.desktop.feature.costreport.domain.CrRow
import com.zillit.desktop.feature.costreport.domain.CrSection
import com.zillit.desktop.feature.costreport.domain.FigureMode
import com.zillit.desktop.feature.costreport.domain.TreeToggles
import com.zillit.desktop.feature.costreport.domain.buildSections
import com.zillit.desktop.feature.costreport.domain.figuresFor
import com.zillit.desktop.feature.costreport.domain.flattenRows
import com.zillit.desktop.feature.costreport.domain.lineKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A small chart: two bands, one header each, nominals with and without sets. */
internal val COA = listOf(
    CoaRow("h-prod", "prod", "Production", CoaLevel.Section),
    CoaRow("h-atl", "atl", "Above the line", CoaLevel.Section),
    CoaRow("s-1100", "1100", "Story & Rights", CoaLevel.Header, headId = "h-atl"),
    CoaRow("s-2100", "2100", "Camera", CoaLevel.Header, headId = "h-prod"),
    CoaRow("c-1110", "1110", "Writers", CoaLevel.Nominal, secId = "s-1100"),
    CoaRow("c-2120", "2120", "Camera hire", CoaLevel.Nominal, secId = "s-2100"),
    CoaRow("c-2110", "2110", "Camera crew", CoaLevel.Nominal, secId = "s-2100"),
    CoaRow("x-2110-01", "2110-01", "DOP", CoaLevel.Set, catId = "c-2110"),
    CoaRow("x-2110-02", "2110-02", "Focus puller", CoaLevel.Set, catId = "c-2110"),
)

internal val LINES = listOf(
    CostLine("1110", budget = 1000.0, atd = 250.0, atp = 50.0),
    CostLine("1110", department = "art", budget = 200.0, atd = 50.0),
    CostLine("2110", budget = 5000.0),
    CostLine("2110-01", atd = 1200.0, po = 300.0),
    CostLine("2110-02", atd = 400.0, card = 100.0),
    CostLine("2120", budget = 800.0, cash = 80.0),
    CostLine("2100", name = "Camera misc", budget = 100.0, atd = 20.0),
    CostLine("__uncoded__", atd = 33.0),
    CostLine(null, name = "Fringes (ABOVE THE LINE) — SAG", budget = 77.0),
    CostLine("art_4110", name = "Mis-coded art", atd = 12.0),
)

class CostReportTreeTest {

    private val sections = buildSections(COA, LINES)

    @Test
    fun sectionsFollowCanonicalOrderThenUncoded() {
        assertEquals(listOf("atl", "prod", CrSection.UNCODED_SECTION_ID), sections.map { it.id })
        assertEquals(listOf("ABOVE THE LINE", "PRODUCTION", "NON-ALLOCATED ITEMS"), sections.map { it.sec })
    }

    @Test
    fun linesAreSummedByAccountAcrossDepartments() {
        val writers = nominal("1110")
        assertEquals(1200.0, writers.line.budget)
        assertEquals(300.0, writers.line.atd)
        assertEquals(50.0, writers.line.atp)
        assertEquals("1110", writers.identity)
    }

    @Test
    fun nominalWithSetsKeepsOnlyItsBudgetAndSetsCarryNoBudget() {
        val crew = nominal("2110")
        assertEquals(5000.0, crew.line.budget)
        assertEquals(0.0, crew.line.atd)
        assertEquals(listOf("2110-01", "2110-02"), crew.sets.map { it.code })
        assertEquals(1200.0, crew.sets[0].line.atd)
        assertEquals(0.0, crew.sets[0].line.budget)
        assertEquals(300.0, crew.sets[0].line.po)
    }

    @Test
    fun dataCodedToHeaderBecomesFirstDirectNominal() {
        val camera = sections.first { it.id == "prod" }.headers.single()
        val first = camera.nominals.first()
        assertEquals("2100.direct", first.code)
        assertEquals("Camera — direct entries", first.name)
        assertEquals("2100", first.apiCode)
        assertEquals("2100", first.identity)
        assertEquals(20.0, first.line.atd)
        // Header budget = Σ nominal budgets, direct entries included.
        assertEquals(100.0 + 5000.0 + 800.0, camera.budget)
        // Children sorted lexicographically by code, direct first.
        assertEquals(listOf("2100.direct", "2110", "2120"), camera.nominals.map { it.code })
    }

    @Test
    fun unknownAccountsLandInUncodedSection() {
        val uncoded = sections.first { it.isUncoded }
        val header = uncoded.headers.single()
        assertEquals("NA", header.code)
        val byAccount = header.nominals.associateBy { it.account }
        val card = byAccount.getValue("__uncoded__")
        assertEquals(CrNominal.BUCKET_CODE, card.code)
        assertEquals("Card — Unmatched Transactions", card.name)
        assertTrue(card.isBucket)
        val fringes = byAccount.getValue("__unallocated__:Fringes (ABOVE THE LINE) — SAG")
        assertEquals("-", fringes.code)
        assertEquals("Fringes (ABOVE THE LINE) — SAG", fringes.name)
        assertEquals(77.0, fringes.line.budget)
        val orphan = byAccount.getValue("art_4110")
        assertEquals("art_4110", orphan.code)
        assertEquals("Mis-coded art", orphan.name)
        assertEquals(77.0, header.budget)
    }

    @Test
    fun lineKeyDistinguishesNamedAndNamelessNullBuckets() {
        assertEquals("__unallocated__", lineKey(CostLine(null)))
        assertEquals("__unallocated__:Foo", lineKey(CostLine(null, name = "Foo")))
        assertEquals("1110", lineKey(CostLine(" 1110 ", name = "ignored")))
    }

    @Test
    fun emptyChartPutsEverythingUnderUncoded() {
        val only = buildSections(emptyList(), LINES)
        assertEquals(1, only.size)
        assertTrue(only.single().isUncoded)
        assertEquals(LINES.map(::lineKey).distinct().size, only.single().headers.single().nominals.size)
    }

    @Test
    fun flattenStartsWithSectionsOpenHeadersClosedAndAlwaysEndsWithGrandTotal() {
        val rows = flattenRows(sections, figuresFor(FigureMode.Live, emptyMap(), false), "", TreeToggles())
        assertTrue(rows.first() is CrRow.Section)
        assertTrue(rows.last() is CrRow.GrandTotal)
        assertTrue(rows.none { it is CrRow.Nominal })
        val header = rows.filterIsInstance<CrRow.Header>().first { it.header.code == "1100" }
        assertEquals(false, header.open)
        assertEquals(1200.0, header.figures.bud)
    }

    @Test
    fun openingAHeaderListsNominalsAndAppendsATotalRow() {
        val toggles = TreeToggles().toggleHeader("prod/2100").toggleNominal("2100/2110")
        val rows = flattenRows(sections, figuresFor(FigureMode.Live, emptyMap(), false), "", toggles)
        val kinds = rows.dropWhile { !(it is CrRow.Header && it.header.code == "2100") }
            .takeWhile { it !is CrRow.GrandTotal }
            .map { it::class.simpleName }
        assertEquals(
            listOf("Header", "Nominal", "Nominal", "Set", "Set", "Nominal", "HeaderTotal", "Section", "Header"),
            kinds,
        )
    }

    @Test
    fun searchRevealsAncestorsOfAMatchingLeafAndPrunesTheRest() {
        val rows = flattenRows(sections, figuresFor(FigureMode.Live, emptyMap(), false), "focus", TreeToggles())
        val sectionIds = rows.filterIsInstance<CrRow.Section>().map { it.section.id }
        assertEquals(listOf("prod"), sectionIds)
        val header = rows.filterIsInstance<CrRow.Header>().single()
        assertEquals("2100", header.header.code)
        assertTrue(header.open)
        assertEquals(listOf("2110"), rows.filterIsInstance<CrRow.Nominal>().map { it.nominal.code })
        assertEquals(listOf("2110-02"), rows.filterIsInstance<CrRow.Set>().map { it.set.code })
        // Totals are never pruned.
        assertTrue(rows.any { it is CrRow.HeaderTotal })
        assertTrue(rows.last() is CrRow.GrandTotal)
    }

    @Test
    fun searchOnAParentRevealsItsWholeSubtree() {
        val rows = flattenRows(sections, figuresFor(FigureMode.Live, emptyMap(), false), "camera", TreeToggles())
        // "Camera" header matches by name; the header row shows collapsed (its own toggle) with all children eligible.
        val header = rows.filterIsInstance<CrRow.Header>().single { it.header.code == "2100" }
        assertEquals(false, header.open)
        assertNull(rows.filterIsInstance<CrRow.Section>().firstOrNull { it.section.id == "atl" })
    }

    private fun nominal(code: String): CrNominal =
        sections.flatMap { it.headers }.flatMap { it.nominals }.first { it.code == code }
}
