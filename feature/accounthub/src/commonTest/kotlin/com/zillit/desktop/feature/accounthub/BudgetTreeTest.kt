package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.feature.accounthub.data.BudgetVersionDto
import com.zillit.desktop.feature.accounthub.domain.BudgetLine
import com.zillit.desktop.feature.accounthub.domain.BudgetStatus
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import com.zillit.desktop.feature.accounthub.domain.asRows
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The budget's shape and its lifecycle.
 *
 * The tree matters because the figures are read off it: a line filed under the
 * wrong parent, or dropped for naming one that is not there, changes what the
 * production believes it has budgeted.
 */
class BudgetTreeTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** A line, built by its own level's parent column. */
    @Suppress("LongParameterList") // A test fixture over a wide record.
    private fun line(
        id: String,
        type: CoaLineType,
        head: String? = null,
        section: String? = null,
        category: String? = null,
        amount: Double = 0.0,
        rollup: Double? = null,
        account: String = id,
    ) = BudgetLine(
        id = id,
        account = account,
        amount = amount,
        rollupTotal = rollup,
        lineType = type,
        headId = head,
        sectionId = section,
        categoryId = category,
    )

    @Test
    fun `lines nest by their breadcrumb, parents before children`() {
        val rows = listOf(
            line("code", CoaLineType.SubCategory, category = "cat", amount = 100.0),
            line("head", CoaLineType.Header, rollup = 100.0),
            line("cat", CoaLineType.Category, section = "sec"),
            line("sec", CoaLineType.Section, head = "head"),
        ).asRows()

        assertEquals(listOf("head", "sec", "cat", "code"), rows.map { it.line.id })
        assertEquals(listOf(0, 1, 2, 3), rows.map { it.depth })
        assertTrue(rows.none { it.orphaned })
    }

    /**
     * A line naming a parent that is not here is kept, and flagged.
     *
     * It still carries money. A budget that silently totals less than its own
     * header is worse than one showing a row out of place.
     */
    @Test
    fun `an orphan is shown rather than dropped`() {
        val rows = listOf(
            line("head", CoaLineType.Header),
            line("lost", CoaLineType.SubCategory, category = "gone", amount = 250.0),
        ).asRows()

        assertEquals(listOf("head", "lost"), rows.map { it.line.id })
        assertTrue(rows.last().orphaned)
        assertEquals(0, rows.last().depth, "an orphan has no parent to indent under")
    }

    /** Siblings sort by level, then by what they are called. */
    @Test
    fun `siblings read in a stable order`() {
        val rows = listOf(
            line("b", CoaLineType.Section, head = null, account = "200"),
            line("a", CoaLineType.Section, head = null, account = "100"),
            line("h", CoaLineType.Header, account = "000"),
        ).asRows()

        assertEquals(listOf("000", "100", "200"), rows.map { it.line.account })
    }

    /** A parent shows its roll-up; a leaf shows its own amount. */
    @Test
    fun `the figure shown is the roll-up where there is one`() {
        assertEquals(900.0, line("p", CoaLineType.Section, rollup = 900.0, amount = 10.0).shownTotal)
        assertEquals(10.0, line("l", CoaLineType.SubCategory, amount = 10.0).shownTotal)
    }

    // -- versions -----------------------------------------------------------

    /** Live and Archived versions are fixed; the way to change one is to clone it. */
    @Test
    fun `only draft and approved versions are open`() {
        assertTrue(!BudgetStatus.Draft.isLocked)
        assertTrue(!BudgetStatus.Approved.isLocked)
        assertTrue(BudgetStatus.Live.isLocked)
        assertTrue(BudgetStatus.Archived.isLocked)
    }

    @Test
    fun `a status is read whatever case it arrives in`() {
        assertEquals(BudgetStatus.Live, BudgetStatus.from("LIVE"))
        assertEquals(BudgetStatus.Live, BudgetStatus.from("live"))
        assertEquals(BudgetStatus.Draft, BudgetStatus.from(null), "absent is a draft")
        assertEquals(BudgetStatus.Draft, BudgetStatus.from("something else"))
    }

    /** The budget's own currency wins over the production's default. */
    @Test
    fun `a version reads its own currency and stamp`() {
        val version = json.decodeFromString(
            BudgetVersionDto.serializer(),
            """{"id":"b1","version":"v3","label":"Locked budget","status":"LIVE","total":1250000,
               "currency":"USD","created_at":"1750000000000","attachment":{"name":"budget.xlsx"}}""",
        ).toDomain()

        assertEquals("v3", version.version)
        assertEquals(BudgetStatus.Live, version.status)
        assertEquals("USD", version.currencyCode)
        assertEquals(1_750_000_000_000L, version.createdAtMillis)
        assertEquals("budget.xlsx", version.sourceFileName)
    }
}
