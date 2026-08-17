package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.feature.accounthub.domain.ChartOfAccounts
import com.zillit.desktop.feature.accounthub.domain.CoaAccount
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import com.zillit.desktop.feature.accounthub.domain.NewAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The chart's ordering, tree-building and parent rules.
 *
 * These are pinned because two surfaces must agree on them — the chart screen
 * and, in time, the cost report's worksheet. The web shipped with two
 * comparators and the tree and the table disagreed about which code came first.
 */
class ChartOfAccountsTest {

    private fun account(
        id: String,
        code: String,
        lineType: CoaLineType = CoaLineType.Category,
        headId: String? = null,
        sectionId: String? = null,
        categoryId: String? = null,
    ) = CoaAccount(
        id = id,
        code = code,
        name = "Account $code",
        lineType = lineType,
        headId = headId,
        sectionId = sectionId,
        categoryId = categoryId,
    )

    /**
     * Codes sort as numbers, not as text.
     *
     * `code` is a free-text column, so plain string order puts "1000" before
     * "2" — which is what an accountant reads as a broken chart.
     */
    @Test
    fun `codes sort by value, not by string order`() {
        val sorted = listOf("1000", "9", "99", "2").sortedWith(ChartOfAccounts::compareCodes)

        assertEquals(listOf("2", "9", "99", "1000"), sorted)
    }

    /**
     * A code carrying a letter is not sortable as a number and goes last.
     *
     * A prefix parse reads "125L" as 125 and files it between 124 and 126,
     * which is worse than putting it at the end: it looks correct.
     */
    @Test
    fun `codes with letters sort after every number`() {
        val sorted = listOf("125L", "200", "ADMIN", "10")
            .sortedWith(ChartOfAccounts::compareCodes)

        assertEquals(listOf("10", "200", "125L", "ADMIN"), sorted)
    }

    /** Blank is unsortable too — parsed as zero it would sort to the very top. */
    @Test
    fun `a blank code does not sort to the top`() {
        val sorted = listOf("", "50", "1").sortedWith(ChartOfAccounts::compareCodes)

        assertEquals(listOf("1", "50", ""), sorted)
    }

    /**
     * Dotted sub-codes read as decimals.
     *
     * So `1100.10` comes *before* `1100.2`. Deliberate, and the opposite of
     * segment-wise ordering where `.10` would be the tenth child.
     */
    @Test
    fun `dotted codes read as decimals`() {
        val sorted = listOf("1100.2", "1100.10", "1100.1")
            .sortedWith(ChartOfAccounts::compareCodes)

        assertEquals(listOf("1100.1", "1100.10", "1100.2"), sorted)
    }

    @Test
    fun `the tree nests each level under its breadcrumb parent`() {
        val rows = listOf(
            account("h1", "1000", CoaLineType.Header, headId = "h1"),
            account("s1", "1100", CoaLineType.Section, headId = "h1", sectionId = "s1"),
            account("c1", "1110", CoaLineType.Category, headId = "h1", sectionId = "s1", categoryId = "c1"),
        )

        val forest = ChartOfAccounts.tree(rows)

        assertEquals(1, forest.roots.size)
        assertEquals("s1", forest.roots.single().children.single().id)
        assertEquals("c1", forest.roots.single().children.single().children.single().id)
        assertTrue(forest.orphans.isEmpty())
    }

    @Test
    fun `siblings are ordered by code within their parent`() {
        val rows = listOf(
            account("h1", "1000", CoaLineType.Header, headId = "h1"),
            account("s2", "1200", CoaLineType.Section, headId = "h1", sectionId = "s2"),
            account("s1", "1100", CoaLineType.Section, headId = "h1", sectionId = "s1"),
        )

        val children = ChartOfAccounts.tree(rows).roots.single().children

        assertEquals(listOf("1100", "1200"), children.map { it.account.code })
    }

    /**
     * A row whose parent is missing is surfaced, not dropped.
     *
     * It happens whenever a parent is inactive and the caller filtered it out.
     * The code is still live and still postable, and a chart that silently
     * omits it is a chart nobody can trust to be complete.
     */
    @Test
    fun `a row whose parent is absent becomes an orphan rather than vanishing`() {
        val rows = listOf(
            account("c1", "1110", CoaLineType.Category, headId = "h1", sectionId = "gone", categoryId = "c1"),
        )

        val forest = ChartOfAccounts.tree(rows)

        assertTrue(forest.roots.isEmpty())
        assertEquals(listOf("c1"), forest.orphans.map { it.id })
        // Flattening includes them, so a caller that renders the flat list
        // cannot lose one by accident.
        assertEquals(1, forest.flatten().size)
    }

    @Test
    fun `parent options are the level immediately above, and none for a header`() {
        val rows = listOf(
            account("h1", "1000", CoaLineType.Header, headId = "h1"),
            account("s1", "1100", CoaLineType.Section, headId = "h1", sectionId = "s1"),
        )

        assertEquals(listOf("h1"), ChartOfAccounts.parentOptions(rows, CoaLineType.Section).map { it.id })
        assertEquals(listOf("s1"), ChartOfAccounts.parentOptions(rows, CoaLineType.Category).map { it.id })
        assertTrue(ChartOfAccounts.parentOptions(rows, CoaLineType.Header).isEmpty())
    }

    @Test
    fun `a parent at the wrong level is refused with a reason`() {
        val header = account("h1", "1000", CoaLineType.Header, headId = "h1")

        val problem = ChartOfAccounts.parentProblem(CoaLineType.Category, header)

        assertNotNull(problem)
        // Names both levels, so the fix is obvious from the message alone.
        assertEquals("Nominal sits under Headers, not Group.", problem)
    }

    @Test
    fun `a header with no parent is valid`() {
        assertNull(ChartOfAccounts.parentProblem(CoaLineType.Header, null))
    }

    /** The code is the natural key and immutable, so a clash is caught before the call. */
    @Test
    fun `a duplicate code is refused`() {
        val rows = listOf(account("a", "1100"))

        val problem = NewAccount(code = "1100", name = "Crew", lineType = CoaLineType.Header)
            .validationError(rows)

        assertEquals("Code 1100 is already in use.", problem)
    }

    @Test
    fun `a complete header passes validation`() {
        assertNull(
            NewAccount(code = "2000", name = "Post", lineType = CoaLineType.Header)
                .validationError(emptyList()),
        )
    }

    @Test
    fun `search matches code and name, and a blank term matches nothing`() {
        val rows = listOf(account("a", "1100").copy(name = "Camera hire"), account("b", "2200"))

        assertEquals(listOf("a"), ChartOfAccounts.search(rows, "camera").map { it.id })
        assertEquals(listOf("b"), ChartOfAccounts.search(rows, "2200").map { it.id })
        // Blank matches nothing rather than everything: the screen swaps the
        // tree for results, and "everything, flattened" is not a search result.
        assertTrue(ChartOfAccounts.search(rows, "  ").isEmpty())
    }
}
