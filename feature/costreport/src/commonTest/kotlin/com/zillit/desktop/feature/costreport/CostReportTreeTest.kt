package com.zillit.desktop.feature.costreport

import com.zillit.desktop.feature.costreport.domain.CoaLevel
import com.zillit.desktop.feature.costreport.domain.CoaRow
import com.zillit.desktop.feature.costreport.domain.CostLine
import com.zillit.desktop.feature.costreport.domain.CrColumn
import com.zillit.desktop.feature.costreport.domain.CrForecast
import com.zillit.desktop.feature.costreport.domain.CrLineFilter
import com.zillit.desktop.feature.costreport.domain.CrNominal
import com.zillit.desktop.feature.costreport.domain.CrRow
import com.zillit.desktop.feature.costreport.domain.CrSection
import com.zillit.desktop.feature.costreport.domain.CrSort
import com.zillit.desktop.feature.costreport.domain.CrTableSpec
import com.zillit.desktop.feature.costreport.domain.CrViewMode
import com.zillit.desktop.feature.costreport.domain.SnapshotRow
import com.zillit.desktop.feature.costreport.domain.SnapshotToggles
import com.zillit.desktop.feature.costreport.domain.TreeToggles
import com.zillit.desktop.feature.costreport.domain.buildSections
import com.zillit.desktop.feature.costreport.domain.buildSnapshotTable
import com.zillit.desktop.feature.costreport.domain.buildWorksheetTable
import com.zillit.desktop.feature.costreport.domain.isInternalAccountKey
import com.zillit.desktop.feature.costreport.domain.lineKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        // Its own row, apart from the header's code — the web's `rowAccountKey`.
        assertEquals("2100.direct", first.identity)
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

    private fun table(spec: CrTableSpec = CrTableSpec()) = buildWorksheetTable(sections, CrForecast(), spec)

    /** Sections and headers start open and nominals closed — the web's `!== false` maps. */
    @Test
    fun theWorksheetOpensOnHeadersWithTheirNominalsAndATotalBeneath() {
        val rows = table().rows
        assertTrue(rows.first() is CrRow.Section)
        val header = rows.filterIsInstance<CrRow.Header>().first { it.header.code == "2100" }
        assertTrue(header.open)
        assertFalse(header.showValues, "the Total row carries the figures while nominals show")
        val kinds = rows.dropWhile { !(it is CrRow.Header && it.header.code == "2100") }
            .takeWhile { !(it is CrRow.Section && it.section.id != "prod") }
            .map { it::class.simpleName }
        assertEquals(listOf("Header", "Nominal", "Nominal", "Nominal", "HeaderTotal"), kinds)
        assertTrue(rows.none { it is CrRow.Set })
    }

    @Test
    fun aClosedHeaderKeepsItsFiguresInlineAndSetCodesOpensEverySet() {
        val closed = table(CrTableSpec(toggles = TreeToggles().toggleHeader("2100"))).rows
        val header = closed.filterIsInstance<CrRow.Header>().first { it.header.code == "2100" }
        assertFalse(header.open)
        assertTrue(header.showValues)
        assertTrue(closed.none { it is CrRow.Nominal && it.header.code == "2100" })

        val nominalsMode = table(
            CrTableSpec(toggles = TreeToggles().toggleHeader("2100"), viewMode = CrViewMode.Nominals),
        ).rows
        assertTrue(
            nominalsMode.any { it is CrRow.Nominal && it.header.code == "2100" },
            "Nominals mode shows them anyway",
        )

        val sets = table(CrTableSpec(viewMode = CrViewMode.SetCodes)).rows.filterIsInstance<CrRow.Set>()
        assertEquals(listOf("2110-01", "2110-02"), sets.map { it.set.code })
    }

    @Test
    fun expandAndCollapseAllFollowTheWebsMaps() {
        val collapsed = TreeToggles.collapsed(sections)
        assertFalse(collapsed.allHeadersOpen(sections))
        assertTrue(table(CrTableSpec(toggles = collapsed)).rows.none { it is CrRow.Nominal })
        val expanded = TreeToggles.expanded(sections)
        assertTrue(expanded.allHeadersOpen(sections))
        assertEquals(setOf("2110"), expanded.openNominals)
    }

    @Test
    fun searchRevealsAncestorsOfAMatchingLeafAndPrunesTheRest() {
        val result = table(CrTableSpec(search = "focus"))
        val rows = result.rows
        assertEquals(listOf("prod"), rows.filterIsInstance<CrRow.Section>().map { it.section.id })
        val header = rows.filterIsInstance<CrRow.Header>().single()
        assertEquals("2100", header.header.code)
        assertTrue(header.open)
        assertEquals(listOf("2110"), rows.filterIsInstance<CrRow.Nominal>().map { it.nominal.code })
        assertTrue(rows.filterIsInstance<CrRow.Nominal>().single().open, "a nominal with a shown set is forced open")
        assertEquals(listOf("2110-02"), rows.filterIsInstance<CrRow.Set>().map { it.set.code })
        assertEquals(1, result.search.matches)
        assertTrue(rows.any { it is CrRow.HeaderTotal })
    }

    @Test
    fun searchOnAParentRevealsItsWholeSubtreeAndSaysWhenNothingMatches() {
        val rows = table(CrTableSpec(search = "camera", toggles = TreeToggles.collapsed(sections))).rows
        val header = rows.filterIsInstance<CrRow.Header>().single { it.header.code == "2100" }
        assertTrue(header.open, "a search forces every shown header open")
        assertEquals(
            listOf("2100.direct", "2110", "2120"),
            rows.filterIsInstance<CrRow.Nominal>().map { it.nominal.code },
        )
        assertEquals(2, rows.count { it is CrRow.Set }, "a matching header reveals its sets too")
        assertNull(rows.filterIsInstance<CrRow.Section>().firstOrNull { it.section.id == "atl" })

        val none = table(CrTableSpec(search = "zzz")).rows
        assertEquals(CrRow.NoMatches("zzz"), none.single())
    }

    @Test
    fun aSortOrFilterFlattensToHeaderLinesButASearchTakesOver() {
        val sorted = table(
            CrTableSpec(sort = CrSort().toggled(CrColumn.Atd), toggles = TreeToggles.collapsed(sections)),
        )
        assertTrue(sorted.flat)
        val strip = sorted.rows.first() as CrRow.ModeStrip
        assertEquals(sorted.rows.count { it is CrRow.Header }, strip.lines)
        val order = sorted.rows.filterIsInstance<CrRow.Header>().map { it.header.code }
        assertEquals("2100", order.first(), "high to low on actuals to date")
        assertEquals("ATL", sorted.rows.filterIsInstance<CrRow.Header>().first { it.header.code == "1100" }.badge)

        val active = table(CrTableSpec(filter = CrLineFilter.ActiveThisWeek, toggles = TreeToggles.collapsed(sections)))
        assertTrue(active.rows.filterIsInstance<CrRow.Header>().all { it.figures.atp > 0 || it.figures.atd > 0 })

        assertFalse(table(CrTableSpec(search = "camera", sort = CrSort().toggled(CrColumn.Atd))).flat)
        assertEquals(CrSort(), CrSort().toggled(CrColumn.Atd).toggled(CrColumn.Atd).toggled(CrColumn.Atd))
    }

    @Test
    fun contractualBudgetLinesGetTheirOwnSectionAboveNonAllocated() {
        val lines = LINES + listOf(
            CostLine(null, name = "Completion Bond", budget = 900.0, id = "b-1", sectionId = "__contractual__"),
            CostLine(null, name = "Completion Bond", budget = 100.0, id = "b-2", sectionId = "__contractual__"),
        )
        val built = buildSections(COA, lines)
        assertEquals(
            listOf("atl", "prod", CrSection.CONTRACTUAL_SECTION_ID, CrSection.UNCODED_SECTION_ID),
            built.map { it.id },
        )
        val contractual = built.first { it.isContractual }.headers.single()
        assertEquals("CI", contractual.code)
        assertEquals(1000.0, contractual.budget)
        assertEquals(2, contractual.nominals.size, "two lines of one name stay apart by id")
        assertTrue(contractual.nominals.all { it.isContractual && it.isBucket })
        assertEquals(2, contractual.nominals.map { it.identity }.distinct().size)
    }

    @Test
    fun bucketRowsAreToldApartByTheirKeyAndDrillByIt() {
        val uncoded = sections.first { it.isUncoded }.headers.single().nominals
        val buckets = uncoded.filter { it.isBucket }
        assertEquals(buckets.size, buckets.map { it.identity }.distinct().size)
        assertTrue(buckets.all { isInternalAccountKey(it.identity) && it.apiCode == it.identity })
        assertFalse(buckets.any { it.isContractual })
        val direct = nominal("2100.direct")
        assertEquals("2100.direct", direct.identity)
        assertEquals("2100", direct.apiCode)
        assertEquals(uncoded.map { it.code }.sorted(), uncoded.map { it.code }, "orphans sort by code")
    }

    @Test
    fun aZeroLineCodedToAHeaderIsNotADirectEntry() {
        val built = buildSections(COA, listOf(CostLine("1100")))
        assertTrue(built.flatMap { it.headers }.flatMap { it.nominals }.none { it.code.endsWith(".direct") })
    }

    @Test
    fun theSnapshotTableStartsWithHeadersClosedAndRollsUpBands() {
        val closed = buildSnapshotTable(sections, "", SnapshotToggles())
        assertTrue(closed.rows.none { it is SnapshotRow.Nominal })
        val prod = closed.rows.filterIsInstance<SnapshotRow.Section>().first { it.section.id == "prod" }
        assertEquals(5900.0, prod.figures.budget)
        val open = buildSnapshotTable(sections, "", SnapshotToggles().toggleHeader("2100"))
        assertEquals(3, open.rows.count { it is SnapshotRow.Nominal })
        val searched = buildSnapshotTable(sections, "focus", SnapshotToggles())
        assertTrue(searched.rows.single() is SnapshotRow.NoMatches, "sets are not searched on the snapshot page")
    }

    private fun nominal(code: String): CrNominal =
        sections.flatMap { it.headers }.flatMap { it.nominals }.first { it.code == code }
}
