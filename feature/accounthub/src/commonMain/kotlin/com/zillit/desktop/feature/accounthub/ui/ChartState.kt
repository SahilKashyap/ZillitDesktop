package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.feature.accounthub.domain.ChartMode
import com.zillit.desktop.feature.accounthub.domain.ChartOfAccounts
import com.zillit.desktop.feature.accounthub.domain.ChartSort
import com.zillit.desktop.feature.accounthub.domain.CoaAccount
import com.zillit.desktop.feature.accounthub.domain.CoaBulk
import com.zillit.desktop.feature.accounthub.domain.CoaBulkRow
import com.zillit.desktop.feature.accounthub.domain.CoaBulkStatus
import com.zillit.desktop.feature.accounthub.domain.CoaCostType
import com.zillit.desktop.feature.accounthub.domain.CoaForest
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import com.zillit.desktop.feature.accounthub.domain.CoaNode
import com.zillit.desktop.feature.accounthub.domain.CoaStats
import com.zillit.desktop.feature.accounthub.domain.TrackingNode
import com.zillit.desktop.feature.accounthub.domain.TrackingSet
import com.zillit.desktop.feature.accounthub.domain.TrackingSets

/*
 * The Chart of Accounts screen's state — the web's `ChartOfAccountsModule`,
 * `AccountsTab`, `AccountFormModal`, `CoaBulkAddPage` and `TrackingCodesTab`.
 */

/** Which classes the chart is filtered to — the module's three tabs. */
enum class ChartView(val slug: String, val label: String) {
    /** The cost side — what a production spends against. */
    Expense("accounts", "Cost Accounts"),

    /** Everything else: asset, liability, capital, income. */
    BalanceSheet("balance", "Balance Sheet Codes"),

    /** Analytical dimensions parallel to the nominal chart. */
    Layers("tracking", "Layers"),
    ;

    /** Whether [account] belongs on this tab. Layers holds no accounts. */
    fun shows(account: CoaAccount): Boolean = when (this) {
        Expense -> !account.costType.isBalanceSheet
        BalanceSheet -> account.costType.isBalanceSheet
        Layers -> false
    }

    /** The class a code added on this tab starts as, so it does not save itself out of the tab. */
    val defaultCostType: CoaCostType get() = if (this == BalanceSheet) CoaCostType.Asset else CoaCostType.Expense
}

/** A layer (set) being added or edited — the web's `SetEditorModal`. */
data class LayerSetDraft(val set: TrackingSet, val isNew: Boolean, val saving: Boolean = false)

/** A layer code being added or edited — the web's `NodeEditorModal`. */
data class LayerNodeDraft(val node: TrackingNode, val isNew: Boolean, val saving: Boolean = false)

/** What a delete confirmation on the Layers tab is about. */
sealed interface LayerDelete {
    /** The confirmation's question. */
    val message: String

    /** The refusal's title when the server says it is still in use. */
    val refusalTitle: String

    /** What the refusal names when the server gives no reason. */
    val fallbackRefusal: String

    data class WholeSet(val set: TrackingSet) : LayerDelete {
        override val message: String
            get() {
                val count = set.nodes.size
                return if (count > 0) {
                    "Delete \"${set.name}\" and its $count code${if (count == 1) "" else "s"}? This cannot be undone."
                } else {
                    "Delete \"${set.name}\"?"
                }
            }
        override val refusalTitle: String get() = "Can't delete this layer"
        override val fallbackRefusal: String get() = "Failed to delete \"${set.name}\"."
    }

    data class OneNode(val setId: String, val node: TrackingNode) : LayerDelete {
        override val message: String get() = "Delete \"${node.code}\"?"
        override val refusalTitle: String get() = "Can't delete this code"
        override val fallbackRefusal: String get() = "Failed to delete \"${node.code}\"."
    }
}

/** The server's refusal to delete something still in use, in its own words — the web's `InUseModal`. */
data class LayerInUse(val title: String, val message: String)

/**
 * Where the tree's fold starts from.
 *
 * [Default] is the web's first paint: top-level rows open, the rest closed.
 * Expand all and Collapse all replace it; a row opened or closed by hand is
 * kept on top of whichever applies.
 */
enum class TreeFold { Default, AllOpen, AllClosed }

/** One drawn row of the tree. */
data class ChartTreeRow(
    val account: CoaAccount,
    val depth: Int,
    val hasChildren: Boolean,
    val open: Boolean,
)

/** The full-page "New COA Entry" grid — the web's `CoaBulkAddPage`. */
data class BulkAddState(
    /** The row "Add child" was raised from; null adds top-level entries. */
    val parent: CoaAccount? = null,
    val rows: List<CoaBulkRow> = emptyList(),
    /** The class the grid was opened for, so a new row defaults to it. */
    val costType: CoaCostType = CoaCostType.Expense,
    /** Each row's autosave, by local id. A row never sent has none. */
    val status: Map<String, CoaBulkStatus> = emptyMap(),
    /** The server's reason a row's last save failed, for its tooltip. */
    val errors: Map<String, String> = emptyMap(),
    /** Codes this session created, which stay reserved even once removed. */
    val createdCodes: Set<String> = emptySet(),
    /** Done was pressed and the last saves are going out. */
    val finishing: Boolean = false,
    /** The row whose code field takes the caret — set by Tab off the last row. */
    val focusRowId: String? = null,
) {
    val anySaving: Boolean get() = CoaBulkStatus.Saving in status.values

    val anyError: Boolean get() = CoaBulkStatus.Error in status.values

    val anySaved: Boolean get() = CoaBulkStatus.Saved in status.values

    /** The web's save label: "Saving…", "Couldn't save some rows", "All changes saved". */
    val saveLabel: String
        get() = when {
            anySaving -> "Saving…"
            anyError -> "Couldn't save some rows"
            anySaved -> "All changes saved"
            else -> ""
        }

    /** The rows whose code collides with the chart, this session's codes, or each other. */
    fun duplicateIds(chart: List<CoaAccount>): Set<String> =
        CoaBulk.duplicateIds(rows, CoaBulk.takenCodes(chart, createdCodes))
}

/** The chart of accounts screen. */
data class ChartState(
    val view: ChartView = ChartView.Expense,
    val loading: Boolean = false,
    /** True once the chart has answered at least once — the tour's `coaReady`. */
    val loaded: Boolean = false,
    val accounts: List<CoaAccount> = emptyList(),
    val search: String = "",
    /** On by default, as on the web — a retired code stays discoverable, chipped "Inactive". */
    val showInactive: Boolean = true,
    /** Rows opened by hand, over the [fold]. */
    val expanded: Set<String> = emptySet(),
    /** Rows closed by hand, over the [fold]. */
    val collapsed: Set<String> = emptySet(),
    val fold: TreeFold = TreeFold.Default,
    val mode: ChartMode = ChartMode.Tree,
    val sort: ChartSort = ChartSort(),
    val form: AccountForm? = null,
    val confirmDeactivate: CoaAccount? = null,
    val deactivating: Boolean = false,
    val bulk: BulkAddState? = null,
    /** The analytical dimensions behind the Layers tab. */
    val trackingSets: List<TrackingSet> = emptyList(),
    val layersLoading: Boolean = false,
    val layerSetDraft: LayerSetDraft? = null,
    val layerNodeDraft: LayerNodeDraft? = null,
    val layerDelete: LayerDelete? = null,
    val layerDeleting: Boolean = false,
    val layerInUse: LayerInUse? = null,
    /** The one layer shown open — one at a time, as on the web, and none to begin with. */
    val openLayer: String? = null,
) {
    /** This tab's rows, inactive ones included — what the stat strip counts. */
    val tabAccounts: List<CoaAccount> get() = accounts.filter(view::shows)

    /** The rows the tree and table draw. */
    val visibleAccounts: List<CoaAccount>
        get() = tabAccounts.filter { showInactive || it.isActive }

    val stats: CoaStats get() = CoaStats.of(tabAccounts)

    val forest: CoaForest get() = ChartOfAccounts.tree(visibleAccounts)

    /** The whole chart is empty — the setup tour's `coaEmpty`. */
    val isEmpty: Boolean get() = loaded && accounts.isEmpty()

    /** Nothing to draw on this tab — the web's empty state, with its two ways to start. */
    val isViewEmpty: Boolean get() = loaded && visibleAccounts.isEmpty()

    /** The toolbar's fold button. The web's starts at "Collapse all". */
    val foldLabel: String get() = if (fold == TreeFold.AllClosed) "Expand all" else "Collapse all"

    fun isOpen(id: String, depth: Int): Boolean = when {
        id in expanded -> true
        id in collapsed -> false
        else -> when (fold) {
            TreeFold.Default -> depth == 0
            TreeFold.AllOpen -> true
            TreeFold.AllClosed -> false
        }
    }

    /** Opens a closed row or closes an open one, by hand. */
    fun toggled(id: String, depth: Int): ChartState =
        if (isOpen(id, depth)) {
            copy(expanded = expanded - id, collapsed = collapsed + id)
        } else {
            copy(expanded = expanded + id, collapsed = collapsed - id)
        }

    /** Expand all when anything is folded away, Collapse all otherwise. */
    fun foldedAll(): ChartState = copy(
        fold = if (fold == TreeFold.AllClosed) TreeFold.AllOpen else TreeFold.AllClosed,
        expanded = emptySet(),
        collapsed = emptySet(),
    )

    /** The top of the tree, which decides when the hand-set fold starts over. */
    val rootIds: List<String> get() = forest.let { it.roots + it.orphans }.map { it.id }

    /**
     * The tree as drawn rows.
     *
     * A search keeps every hit and the path above it; while one is present each
     * kept row is drawn open, because a hit several levels down would otherwise
     * sit behind folded ancestors and the search would look empty.
     */
    fun treeRows(): List<ChartTreeRow> {
        val tree = forest
        val searching = search.isNotBlank()
        val out = mutableListOf<ChartTreeRow>()
        fun walk(nodes: List<CoaNode>, depth: Int) {
            nodes.forEach { node ->
                val hasChildren = node.children.isNotEmpty()
                val open = hasChildren && (searching || isOpen(node.id, depth))
                out += ChartTreeRow(node.account, depth, hasChildren, open)
                if (open) walk(node.children, depth + 1)
            }
        }
        walk(ChartOfAccounts.filter(tree.roots, search), 0)
        walk(ChartOfAccounts.filter(tree.orphans, search), 0)
        return out
    }

    /** The table's rows: searched or all, sorted by the active column. */
    val tableRows: List<CoaAccount>
        get() {
            val rows = if (search.isBlank()) visibleAccounts else ChartOfAccounts.search(visibleAccounts, search)
            return rows.sortedWith(sort.comparator())
        }

    /** A layer's codes in the web's order: flat, every one, by stored order then code. */
    fun layerNodes(set: TrackingSet): List<TrackingNode> = TrackingSets.ordered(set.nodes)
}

/**
 * The edit form for one chart row — the web's `AccountFormModal`.
 *
 * [editing] is null when adding. A manual row may be re-typed or re-parented —
 * a structural edit the server re-walks — while a budget-imported row keeps its
 * level, its parent and its class, which the budget owns.
 */
data class AccountForm(
    val editing: CoaAccount? = null,
    /** The row the form was opened from, which a change of level falls back to as parent. */
    val preselectedParent: CoaAccount? = null,
    val code: String = "",
    val name: String = "",
    val lineType: CoaLineType = CoaLineType.Header,
    val costType: CoaCostType = CoaCostType.Expense,
    val parentId: String? = null,
    val isActive: Boolean = true,
    val isPosting: Boolean = true,
    val saving: Boolean = false,
) {
    val isEdit: Boolean get() = editing != null

    val title: String get() = editing?.let { "Edit code · ${it.code}" } ?: "New chart-of-accounts entry"

    /** A budget row's level and parent come from the budget. */
    val structureLocked: Boolean get() = editing?.isFromBudget == true

    /** Whether the edit re-types or re-parents the row. */
    val structureChanged: Boolean
        get() = editing != null && !structureLocked &&
            (lineType != editing.lineType || parentId != editing.parentId)

    fun codeError(rows: List<CoaAccount>): String? = when {
        code.isBlank() -> "Code is required"
        editing == null && ChartOfAccounts.codeTaken(rows, code) -> "Code \"${code.trim().uppercase()}\" already exists"
        else -> null
    }

    fun parentProblem(rows: List<CoaAccount>): String? =
        ChartOfAccounts.parentProblem(lineType, parentId, rows, selfId = editing?.id)

    fun canSave(rows: List<CoaAccount>): Boolean = !saving && codeError(rows) == null && parentProblem(rows) == null

    /** The cost type's hint, which changes with what an edit of it would do. */
    val costTypeHint: String
        get() = when {
            editing?.isFromBudget == true ->
                "This row was created by a budget import — it's permanently classified as Expense. To track an " +
                    "asset / liability / capital / income account, add a new row manually."
            editing != null && lineType != CoaLineType.SubCategory ->
                "Changing this cascades to every descendant. Re-classify a specific child afterwards if needed."
            else ->
                "Pick the accounting class for this row. Asset (Cash, Bank), Liability (Loans), Capital (Equity), " +
                    "Income (Revenue, Tax Credits), Expense (Costs)."
        }

    companion object {
        /** A new row under [parent] — one level below it — or a new top-level group. */
        fun adding(parent: CoaAccount?, costType: CoaCostType) = AccountForm(
            preselectedParent = parent,
            lineType = if (parent == null) CoaLineType.Header else parent.lineType.childType ?: CoaLineType.Category,
            costType = costType,
            parentId = parent?.id,
        )

        fun editing(account: CoaAccount, rows: List<CoaAccount>) = AccountForm(
            editing = account,
            preselectedParent = rows.firstOrNull { it.id == account.parentId },
            code = account.code,
            name = account.name,
            lineType = account.lineType,
            costType = account.costType,
            parentId = account.parentId,
            isActive = account.isActive,
            isPosting = account.isPosting,
        )
    }
}
