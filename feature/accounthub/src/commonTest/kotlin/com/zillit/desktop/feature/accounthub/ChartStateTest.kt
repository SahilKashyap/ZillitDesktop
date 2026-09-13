package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.feature.accounthub.domain.ChartSort
import com.zillit.desktop.feature.accounthub.domain.ChartSortKey
import com.zillit.desktop.feature.accounthub.domain.CoaAccount
import com.zillit.desktop.feature.accounthub.domain.CoaCostType
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import com.zillit.desktop.feature.accounthub.ui.AccountForm
import com.zillit.desktop.feature.accounthub.ui.ChartState
import com.zillit.desktop.feature.accounthub.ui.ChartView
import com.zillit.desktop.feature.accounthub.ui.TreeFold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the Chart of Accounts screen draws from its state — the web's
 * `AccountsTab`: which rows each tab shows, how the tree folds, and what the
 * edit form will accept.
 */
class ChartStateTest {

    private val header = CoaAccount(
        id = "h1",
        code = "1000",
        name = "Production",
        lineType = CoaLineType.Header,
        headId = "h1",
    )
    private val section = CoaAccount(
        id = "s1",
        code = "1100",
        name = "Crew",
        lineType = CoaLineType.Section,
        headId = "h1",
        sectionId = "s1",
    )
    private val nominal = CoaAccount(
        id = "c1",
        code = "1110",
        name = "Grips",
        lineType = CoaLineType.Category,
        headId = "h1",
        sectionId = "s1",
        categoryId = "c1",
    )
    private val bank = CoaAccount(
        id = "b1",
        code = "0100",
        name = "Bank",
        lineType = CoaLineType.Header,
        costType = CoaCostType.Asset,
        headId = "b1",
    )
    private val retired = CoaAccount(
        id = "r1",
        code = "1900",
        name = "Old",
        lineType = CoaLineType.Header,
        headId = "r1",
        isActive = false,
    )

    private val chart = ChartState(accounts = listOf(header, section, nominal, bank, retired), loaded = true)

    // -- tabs --------------------------------------------------------------------------

    /** One chart, split by class: an untagged row is a cost, as the column defaults on the server. */
    @Test
    fun `each tab shows its own classes`() {
        assertEquals(listOf("h1", "s1", "c1", "r1"), chart.visibleAccounts.map { it.id })
        assertEquals(listOf("b1"), chart.copy(view = ChartView.BalanceSheet).visibleAccounts.map { it.id })
        assertTrue(chart.copy(view = ChartView.Layers).visibleAccounts.isEmpty())
    }

    /** The strip describes the tab's chart, so hiding the retired rows does not change its counts. */
    @Test
    fun `the stat strip ignores Show inactive`() {
        val hidden = chart.copy(showInactive = false)

        assertEquals(listOf("h1", "s1", "c1"), hidden.visibleAccounts.map { it.id })
        assertEquals(4, hidden.stats.total)
        assertEquals(3, hidden.stats.active)
    }

    /** The empty state is the tab's, not the chart's: a production with costs but no balance sheet sees it there. */
    @Test
    fun `an empty tab is empty even when the chart is not`() {
        val costsOnly = chart.copy(accounts = listOf(header), view = ChartView.BalanceSheet)

        assertTrue(costsOnly.isViewEmpty)
        assertFalse(costsOnly.isEmpty)
    }

    // -- the fold ----------------------------------------------------------------------

    private fun ChartState.drawn(): List<String> = treeRows().map { it.account.id }

    /** The web's first paint: top-level rows open, everything below closed, and the button offers "Collapse all". */
    @Test
    fun `the tree opens at its top level`() {
        assertEquals(listOf("h1", "s1", "r1"), chart.drawn())
        assertEquals("Collapse all", chart.foldLabel)
    }

    /**
     * A top-level row starts open, so its first click closes it — and opening a
     * row beneath leaves every other top-level row as it was.
     */
    @Test
    fun `toggling a row flips only that row`() {
        val closed = chart.toggled("h1", depth = 0)
        assertEquals(listOf("h1", "r1"), closed.drawn())

        val deeper = chart.toggled("s1", depth = 1)
        assertEquals(listOf("h1", "s1", "c1", "r1"), deeper.drawn())
    }

    @Test
    fun `collapse all, then expand all`() {
        val collapsed = chart.foldedAll()
        assertEquals(TreeFold.AllClosed, collapsed.fold)
        assertEquals(listOf("h1", "r1"), collapsed.drawn())
        assertEquals("Expand all", collapsed.foldLabel)

        val expanded = collapsed.foldedAll()
        assertEquals(listOf("h1", "s1", "c1", "r1"), expanded.drawn())
        assertEquals("Collapse all", expanded.foldLabel)
    }

    /** A hit beneath folded rows is drawn with its path open, or the search would look empty. */
    @Test
    fun `a search opens the path to every hit`() {
        val searching = chart.foldedAll().copy(search = "grip")

        assertEquals(listOf("h1", "s1", "c1"), searching.drawn())
        assertTrue(searching.copy(search = "nothing like it").treeRows().isEmpty())
    }

    // -- the table ---------------------------------------------------------------------

    @Test
    fun `the table sorts by the chosen column and filters by the search`() {
        val byCode = chart.copy(sort = ChartSort(ChartSortKey.Code))
        assertEquals(listOf("1000", "1100", "1110", "1900"), byCode.tableRows.map { it.code })

        val byType = chart.copy(sort = ChartSort(ChartSortKey.Type, ascending = false))
        assertEquals(CoaLineType.Category, byType.tableRows.first().lineType)

        assertEquals(listOf("s1"), chart.copy(search = "crew").tableRows.map { it.id })
    }

    // -- the edit form -----------------------------------------------------------------

    @Test
    fun `an edit is titled by its code and never re-checks the code it already holds`() {
        val form = AccountForm.editing(section, chart.accounts)

        assertEquals("Edit code · 1100", form.title)
        assertNull(form.codeError(chart.accounts))
        assertTrue(form.canSave(chart.accounts))
    }

    /** A budget row's level, parent and class belong to the budget, so the form will not move them. */
    @Test
    fun `a budget row is structurally locked`() {
        val imported = nominal.copy(source = CoaAccount.BUDGET_SOURCE)
        val form = AccountForm.editing(imported, chart.accounts).copy(lineType = CoaLineType.SubCategory)

        assertTrue(form.structureLocked)
        assertFalse(form.structureChanged)
        assertTrue(form.costTypeHint.startsWith("This row was created by a budget import"))
    }

    @Test
    fun `re-typing a manual row is a structural change`() {
        val form = AccountForm.editing(section, chart.accounts).copy(lineType = CoaLineType.Category, parentId = null)

        assertTrue(form.structureChanged)
        assertTrue(form.canSave(chart.accounts))
    }

    @Test
    fun `adding under a row starts one level below it`() {
        val form = AccountForm.adding(section, CoaCostType.Expense)

        assertEquals(CoaLineType.Category, form.lineType)
        assertEquals("s1", form.parentId)
        assertEquals("New chart-of-accounts entry", form.title)
        assertEquals("Code \"1100\" already exists", form.copy(code = "1100").codeError(chart.accounts))
    }
}
