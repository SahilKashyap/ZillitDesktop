package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.feature.accounthub.domain.ChartOfAccounts
import com.zillit.desktop.feature.accounthub.domain.CoaAccount
import com.zillit.desktop.feature.accounthub.domain.CoaBulk
import com.zillit.desktop.feature.accounthub.domain.CoaBulkRow
import com.zillit.desktop.feature.accounthub.domain.CoaCostType
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import com.zillit.desktop.feature.accounthub.domain.CoaStats
import com.zillit.desktop.feature.accounthub.domain.NewAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The chart's ordering, tree-building and parent rules — the web's `lib/coa.js`
 * and `coaBulkRows.js`.
 *
 * These are pinned because two surfaces must agree on them: the tree and the
 * table. The web shipped with two comparators once and the two disagreed about
 * which code came first.
 */
class ChartOfAccountsTest {

    private fun account(
        id: String,
        code: String,
        lineType: CoaLineType = CoaLineType.Category,
        headId: String? = null,
        sectionId: String? = null,
        categoryId: String? = null,
        name: String = "Account $code",
    ) = CoaAccount(
        id = id,
        code = code,
        name = name,
        lineType = lineType,
        headId = headId,
        sectionId = sectionId,
        categoryId = categoryId,
    )

    private fun sorted(vararg codes: String) = codes.toList().sortedWith(ChartOfAccounts::compareCodes)

    // -- ordering --------------------------------------------------------------------

    /** `code` is a free-text column, so plain string order would put "1000" before "2". */
    @Test
    fun `codes sort by value, not by string order`() {
        assertEquals(listOf("2", "9", "99", "1000"), sorted("1000", "9", "99", "2"))
    }

    /** A prefix parse reads "125L" as 125 and files it between 124 and 126 — worse, because it looks right. */
    @Test
    fun `codes with letters sort after every number`() {
        assertEquals(listOf("10", "200", "125L", "ADMIN"), sorted("125L", "200", "ADMIN", "10"))
    }

    /**
     * "125f" is a number to the JVM's parser (a float literal) and not to the
     * web's `Number()`. The chart follows the web: it is text, and goes last.
     */
    @Test
    fun `a float-suffixed code is text, not a number`() {
        assertEquals(listOf("130", "125f"), sorted("125f", "130"))
    }

    /** Blank is unsortable too — parsed as zero it would sort to the very top. */
    @Test
    fun `a blank code does not sort to the top`() {
        assertEquals(listOf("1", "50", ""), sorted("", "50", "1"))
    }

    /** `1100.10` comes *before* `1100.2`: decimals, not segments — the web's 2026-07-28 rule. */
    @Test
    fun `dotted codes read as decimals`() {
        assertEquals(listOf("1100.1", "1100.10", "1100.2"), sorted("1100.2", "1100.10", "1100.1"))
    }

    /**
     * A hyphenated budget code orders by its leading number, beside the plain
     * one, and hyphenated siblings order naturally — "1100-2" before "1100-10".
     */
    @Test
    fun `hyphenated codes file beside their leading number`() {
        assertEquals(
            listOf("1100", "1100-2", "1100-10", "1200", "ADMIN"),
            sorted("1200", "1100-10", "ADMIN", "1100", "1100-2"),
        )
    }

    @Test
    fun `the unsortable tail is in natural order`() {
        assertEquals(listOf("ADMIN2", "ADMIN10", "OFFICE"), sorted("OFFICE", "ADMIN10", "ADMIN2"))
    }

    // -- the tree --------------------------------------------------------------------

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
     * A row whose parent is missing is surfaced, not dropped: the code is still
     * live and postable, and a chart that silently omits it cannot be trusted.
     */
    @Test
    fun `a row whose parent is absent becomes an orphan rather than vanishing`() {
        val rows = listOf(
            account("c1", "1110", CoaLineType.Category, headId = "h1", sectionId = "gone", categoryId = "c1"),
        )

        val forest = ChartOfAccounts.tree(rows)

        assertTrue(forest.roots.isEmpty())
        assertEquals(listOf("c1"), forest.orphans.map { it.id })
        assertEquals(1, forest.flatten().size)
    }

    /** The web's search keeps every hit with the path above it, and drops branches with no hit. */
    @Test
    fun `a search keeps the path to each hit and nothing else`() {
        val rows = listOf(
            account("h1", "1000", CoaLineType.Header, headId = "h1", name = "Production"),
            account("s1", "1100", CoaLineType.Section, headId = "h1", sectionId = "s1", name = "Crew"),
            account("c1", "1110", CoaLineType.Category, "h1", "s1", categoryId = "c1", name = "Grips"),
            account("c2", "1120", CoaLineType.Category, "h1", "s1", categoryId = "c2", name = "Sparks"),
            account("h2", "2000", CoaLineType.Header, headId = "h2", name = "Post"),
        )

        val kept = ChartOfAccounts.filter(ChartOfAccounts.tree(rows).roots, "grip")

        assertEquals(listOf("h1"), kept.map { it.id })
        assertEquals(listOf("s1"), kept.single().children.map { it.id })
        assertEquals(listOf("c1"), kept.single().children.single().children.map { it.id })
    }

    @Test
    fun `a blank search keeps the whole tree`() {
        val roots = ChartOfAccounts.tree(listOf(account("h1", "1000", CoaLineType.Header, headId = "h1"))).roots

        assertEquals(roots, ChartOfAccounts.filter(roots, "  "))
    }

    /** The table's second line: ancestor names top down, the code where a name is missing, never the row itself. */
    @Test
    fun `the breadcrumb names each ancestor, falling back to its code`() {
        val header = account("h1", "1000", CoaLineType.Header, headId = "h1", name = "Production")
        val section = account("s1", "1100", CoaLineType.Section, headId = "h1", sectionId = "s1", name = "")
        val row = account("c1", "1110", CoaLineType.Category, headId = "h1", sectionId = "s1", categoryId = "c1")
        val byId = listOf(header, section, row).associateBy { it.id }

        assertEquals(listOf("Production", "1100"), ChartOfAccounts.breadcrumb(byId, row))
        assertTrue(ChartOfAccounts.breadcrumb(byId, header).isEmpty())
    }

    // -- parents ---------------------------------------------------------------------

    @Test
    fun `parent options are the active level immediately above, never the row itself`() {
        val rows = listOf(
            account("h1", "1000", CoaLineType.Header, headId = "h1"),
            account("h2", "2000", CoaLineType.Header, headId = "h2").copy(isActive = false),
            account("s1", "1100", CoaLineType.Section, headId = "h1", sectionId = "s1"),
        )

        assertEquals(listOf("h1"), ChartOfAccounts.parentOptions(rows, CoaLineType.Section).map { it.id })
        assertEquals(listOf("s1"), ChartOfAccounts.parentOptions(rows, CoaLineType.Category).map { it.id })
        assertTrue(ChartOfAccounts.parentOptions(rows, CoaLineType.Header).isEmpty())
        assertTrue(ChartOfAccounts.parentOptions(rows, CoaLineType.Category, excludingId = "s1").isEmpty())
    }

    /** The web's `validateParent`: below the top, no parent is a valid answer — the row sits at the root. */
    @Test
    fun `a parent is optional below the top level`() {
        assertNull(ChartOfAccounts.parentProblem(CoaLineType.Category, null, emptyList()))
        assertNull(ChartOfAccounts.parentProblem(CoaLineType.Header, null, emptyList()))
    }

    @Test
    fun `a parent at the wrong level is refused, naming both levels`() {
        val rows = listOf(account("h1", "1000", CoaLineType.Header, headId = "h1"))

        assertEquals(
            "Parent must be a Headers; \"1000\" is a Group.",
            ChartOfAccounts.parentProblem(CoaLineType.Category, "h1", rows),
        )
    }

    @Test
    fun `a header refuses any parent, a row refuses itself, and an unknown id is named`() {
        val rows = listOf(account("s1", "1100", CoaLineType.Section, headId = "h1", sectionId = "s1"))

        assertEquals("Header rows cannot have a parent.", ChartOfAccounts.parentProblem(CoaLineType.Header, "s1", rows))
        assertEquals(
            "A row cannot be its own parent.",
            ChartOfAccounts.parentProblem(CoaLineType.Category, "s1", rows, selfId = "s1"),
        )
        assertEquals(
            "Parent not found in this project.",
            ChartOfAccounts.parentProblem(CoaLineType.Category, "x", rows),
        )
    }

    /**
     * A change of level keeps the parent while it still fits, else falls back
     * to the row the form was opened from, else clears — never a pairing the
     * form would refuse to save.
     */
    @Test
    fun `a change of level keeps, falls back to, or clears the parent`() {
        val header = account("h1", "1000", CoaLineType.Header, headId = "h1")
        val section = account("s1", "1100", CoaLineType.Section, headId = "h1", sectionId = "s1")
        val rows = listOf(header, section)

        assertEquals("s1", ChartOfAccounts.reparent(CoaLineType.Category, "s1", null, rows))
        assertEquals("h1", ChartOfAccounts.reparent(CoaLineType.Section, "s1", header, rows))
        assertNull(ChartOfAccounts.reparent(CoaLineType.SubCategory, "s1", header, rows))
        assertNull(ChartOfAccounts.reparent(CoaLineType.Header, "s1", header, rows))
    }

    // -- creating --------------------------------------------------------------------

    /** The code is the natural key and a retired row still holds it, so a clash is caught before the call. */
    @Test
    fun `a taken code is refused, retired or not`() {
        val rows = listOf(account("a", "1100").copy(isActive = false))

        assertEquals(
            "Code \"1100\" already exists",
            NewAccount(code = "1100", lineType = CoaLineType.Header).validationError(rows),
        )
    }

    /** The web's rule: a code alone is an account — `coaLabel` prints the bare code where there is no name. */
    @Test
    fun `a name is optional`() {
        assertNull(NewAccount(code = "2000", name = "", lineType = CoaLineType.Header).validationError(emptyList()))
        assertEquals("Code is required", NewAccount(code = " ").validationError(emptyList()))
    }

    @Test
    fun `the label is the code alone when there is no name`() {
        assertEquals("1100 · Crew", account("a", "1100", name = "Crew").label("·"))
        assertEquals("1100", account("a", "1100", name = "").label("·"))
    }

    // -- the stat strip ----------------------------------------------------------------

    /** The web's own mapping: "Nominals" counts sections, "Codes" counts categories only. */
    @Test
    fun `the stat strip counts as the web does`() {
        val stats = CoaStats.of(
            listOf(
                account("h", "1", CoaLineType.Header),
                account("s", "2", CoaLineType.Section),
                account("c", "3", CoaLineType.Category),
                account("x", "4", CoaLineType.SubCategory).copy(isActive = false),
            ),
        )

        assertEquals(CoaStats(total = 4, headers = 1, nominals = 1, codes = 1, active = 3), stats)
    }

    @Test
    fun `search matches code and name, and a blank term matches nothing`() {
        val rows = listOf(account("a", "1100").copy(name = "Camera hire"), account("b", "2200"))

        assertEquals(listOf("a"), ChartOfAccounts.search(rows, "camera").map { it.id })
        assertEquals(listOf("b"), ChartOfAccounts.search(rows, "2200").map { it.id })
        assertTrue(ChartOfAccounts.search(rows, "  ").isEmpty())
    }

    // -- the bulk grid -----------------------------------------------------------------

    private fun row(id: String, code: String, serverId: String? = null) =
        CoaBulkRow(localId = id, code = code, serverId = serverId)

    /**
     * A taken code flags only a row not yet saved — a created row's own code
     * joins the taken set, and would otherwise flag itself forever — while two
     * grid rows sharing a code both flag, case-blind.
     */
    @Test
    fun `duplicates flag unsaved rows and every row sharing a code`() {
        val rows = listOf(row("a", "1100"), row("b", "2200", serverId = "srv"), row("c", "3300"), row("d", "3300 "))

        val flagged = CoaBulk.duplicateIds(rows, taken = setOf("1100", "2200"))

        assertEquals(setOf("a", "c", "d"), flagged)
        assertEquals(setOf("x", "y"), CoaBulk.duplicateIds(listOf(row("x", "ab"), row("y", "AB")), emptySet()))
    }

    /** The reason decides the remedy, so each kind of collision says something different. */
    @Test
    fun `a duplicate says why`() {
        val chart = listOf(account("live", "1100"), account("gone", "1200").copy(isActive = false))
        val rows = listOf(row("a", "1100"), row("b", "1200"), row("c", "1300"), row("d", "1400"), row("e", "1400"))

        assertEquals("Code \"1100\" already exists", CoaBulk.duplicateMessage(rows[0], rows, chart, emptySet()))
        assertEquals(
            "Code \"1200\" was previously used and retired — reactivate it from the Chart of Accounts list " +
                "instead of creating it again",
            CoaBulk.duplicateMessage(rows[1], rows, chart, emptySet()),
        )
        assertEquals(
            "Code \"1300\" was already used earlier in this session and can't be reused here",
            CoaBulk.duplicateMessage(rows[2], rows, chart, setOf("1300")),
        )
        assertEquals(
            "Code \"1400\" is used by another row in this list",
            CoaBulk.duplicateMessage(rows[3], rows, chart, emptySet()),
        )
    }

    /**
     * Create sends the whole row, the code upper-cased; an update never sends
     * the code, and sends the level only on a re-type.
     */
    @Test
    fun `the grid's payloads`() {
        val entry = CoaBulkRow(
            localId = "a",
            code = " 1100a ",
            name = " Crew ",
            lineType = CoaLineType.SubCategory,
            costType = CoaCostType.Asset,
            isActive = false,
        )

        val created = CoaBulk.newAccount(entry, parentId = "p")
        assertEquals("1100A", created.code)
        assertEquals("Crew", created.name)
        assertEquals(false, created.isActive)
        assertEquals("p", created.parentId)

        val plain = CoaBulk.patch(entry, retype = false, parentId = "p")
        assertEquals(false, plain.structureChanged)
        assertNull(plain.lineType)
        assertNull(plain.code)

        val retyped = CoaBulk.patch(entry, retype = true, parentId = "p")
        assertEquals(CoaLineType.SubCategory, retyped.lineType)
        assertEquals("p", retyped.parentId)
    }
}
