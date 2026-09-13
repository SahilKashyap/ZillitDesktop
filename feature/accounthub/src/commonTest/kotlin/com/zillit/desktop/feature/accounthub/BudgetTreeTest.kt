package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.feature.accounthub.data.BudgetLineDto
import com.zillit.desktop.feature.accounthub.data.BudgetVersionDto
import com.zillit.desktop.feature.accounthub.domain.BudgetFigures
import com.zillit.desktop.feature.accounthub.domain.BudgetItem
import com.zillit.desktop.feature.accounthub.domain.BudgetLine
import com.zillit.desktop.feature.accounthub.domain.BudgetStatus
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import com.zillit.desktop.feature.accounthub.domain.asTree
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The budget's shape, its figures and its lifecycle — the web's `BudgetsTab`.
 *
 * The tree matters because the figures are read off it: a line filed under the
 * wrong parent, dropped for naming one that is not there, or added to its own
 * roll-up, changes what the production believes it has budgeted.
 */
class BudgetTreeTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

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

    private val chain = listOf(
        line("code", CoaLineType.SubCategory, category = "cat", amount = 100.0),
        line("head", CoaLineType.Header, rollup = 100.0),
        line("cat", CoaLineType.Category, section = "sec", rollup = 100.0),
        line("sec", CoaLineType.Section, head = "head", rollup = 100.0),
    )

    private fun List<BudgetItem>.shape(): List<String> = map {
        when (it) {
            is BudgetItem.Line -> "${"  ".repeat(it.depth)}${it.node.id}${if (it.open) " ▾" else ""}"
            is BudgetItem.Subtotal -> "${"  ".repeat(it.depth)}total:${it.node.id}"
            is BudgetItem.OrphanHeading -> "orphans(${it.count})"
        }
    }

    @Test
    fun `lines nest by their breadcrumb`() {
        val tree = chain.asTree()
        assertEquals(listOf("head"), tree.roots.map { it.id })
        assertEquals("sec", tree.roots.single().children.single().id)
        assertEquals("code", tree.roots.single().children.single().children.single().children.single().id)
        assertTrue(tree.orphans.isEmpty())
    }

    /**
     * The top level opens and nothing below it, as the web's rows do — and an
     * open group is closed by its own "Total · name" row after its last child.
     */
    @Test
    fun `the top level starts open and each open group ends in its subtotal`() {
        val tree = chain.asTree()
        assertEquals(setOf("head"), tree.initiallyOpen)
        assertEquals(listOf("head ▾", "  sec", "total:head"), tree.items(tree.initiallyOpen).shape())

        val everything = setOf("head", "sec", "cat")
        assertEquals(
            listOf("head ▾", "  sec ▾", "    cat ▾", "      code", "    total:cat", "  total:sec", "total:head"),
            tree.items(everything).shape(),
        )
    }

    /**
     * A line naming a parent that is not here is kept, under its own heading.
     *
     * It still carries money. A budget that silently totals less than its own
     * header is worse than one showing a row out of place.
     */
    @Test
    fun `an orphan is shown rather than dropped`() {
        val tree = listOf(
            line("head", CoaLineType.Header, amount = 400.0),
            line("lost", CoaLineType.SubCategory, category = "gone", amount = 250.0),
        ).asTree()

        assertEquals(listOf("head", "orphans(1)", "lost"), tree.items(emptySet()).shape())
        assertEquals(650.0, tree.total, "the footer counts the orphan too")
    }

    /**
     * The footer is the top-level roll-ups plus orphans — never the children
     * again. A parent's roll-up already covers its subtree; adding the children
     * is how the web once showed a writers category at nearly double.
     */
    @Test
    fun `the total never counts a subtree twice`() {
        assertEquals(100.0, chain.asTree().total)
    }

    /** Siblings sort by level, then by code, ignoring case. */
    @Test
    fun `siblings read in a stable order`() {
        val tree = listOf(
            line("b", CoaLineType.Header, account = "b200"),
            line("a", CoaLineType.Header, account = "A100"),
            line("c", CoaLineType.Header, account = "c300"),
        ).asTree()

        assertEquals(listOf("A100", "b200", "c300"), tree.roots.map { it.line.account })
    }

    /** A parent chain that loops is left out rather than walked forever. */
    @Test
    fun `a loop in the breadcrumbs does not hang`() {
        val tree = listOf(
            line("x", CoaLineType.Section, head = "x"),
            line("head", CoaLineType.Header),
        ).asTree()
        assertEquals(listOf("head"), tree.roots.map { it.id })
        assertEquals(listOf("x"), tree.orphans.map { it.id }, "a line that is its own parent has no parent here")
    }

    /** A parent shows its roll-up; a leaf shows its own amount. */
    @Test
    fun `the figure shown is the roll-up where there is one`() {
        assertEquals(900.0, line("p", CoaLineType.Section, rollup = 900.0, amount = 10.0).shownTotal)
        assertEquals(10.0, line("l", CoaLineType.SubCategory, amount = 10.0).shownTotal)
    }

    /** The Name column is the chart's name; the Account column the code in capitals. */
    @Test
    fun `a line reads its name, and an uncoded one its own`() {
        val coded = json.decodeFromString(
            BudgetLineDto.serializer(),
            """{"id":"l1","account":"10-0110","name":"Writers","amount":"686770.00","line_type":"category"}""",
        ).toDomain()
        assertEquals("Writers", coded.nameLabel)
        assertEquals("10-0110", coded.codeLabel)
        assertEquals(686_770.0, coded.amount, "a NUMERIC sent as a string still reads")

        val uncoded = BudgetLine(id = "u", uncodedName = "Contingency")
        assertEquals("Contingency", uncoded.nameLabel)
        assertEquals("Contingency", uncoded.codeLabel)
        assertEquals("—", BudgetLine(id = "blank").nameLabel)
    }

    // -- figures ------------------------------------------------------------

    @Test
    fun `the allocation reads as the web prints it`() {
        assertEquals("25.0%", BudgetFigures.percent(0.25))
        assertEquals("5.25%", BudgetFigures.percent(0.0525))
        assertEquals("0.00%", BudgetFigures.percent(0.0))
        assertEquals(0.02f, BudgetFigures.bar(0.0), "the bar never vanishes")
        assertEquals(1f, BudgetFigures.bar(1.7), "nor overruns its track")
        assertEquals(0.0, BudgetFigures.share(500.0, 0.0), "a version totalling nothing shares nothing")
    }

    @Test
    fun `amounts are grouped with up to three decimals`() {
        assertEquals("£686,770", BudgetFigures.money(686_770.0, "£"))
        assertEquals("£1,234.5", BudgetFigures.money(1234.5, "£"))
        assertEquals("-$12.345", BudgetFigures.money(-12.345, "$"))
        assertEquals("—", BudgetFigures.moneyOrDash(0.0, "£"), "a zero row reads as a dash, as on the web")
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
