package com.zillit.desktop.feature.accounthub.domain

/**
 * Where a code sits in the nominal taxonomy.
 *
 * The wire keys stay `header`/`section`/`category`/`sub_category`; the labels
 * are what accountants actually call them, and they do not match. A `section`
 * is presented as "Headers", a `category` as "Nominal", a `sub_category` as
 * "Code" — renaming the enum to match the labels would break every payload.
 *
 * The top level's label is deliberately blank: its tag is hidden in the tree.
 * [tagLabel] supplies "Group" for the flat surfaces that need something to
 * print in a Type column.
 */
enum class CoaLineType(val wire: String, val label: String) {
    Header("header", ""),
    Section("section", "Headers"),
    Category("category", "Nominal"),
    SubCategory("sub_category", "Code"),
    ;

    /**
     * How far down the tree this sits.
     *
     * Declaration order *is* the hierarchy, so the ordinal is the depth rather
     * than a number repeated beside each entry that could drift out of step
     * with it. Reordering these entries reorders the chart — which is the
     * point, and is why they are not alphabetical.
     */
    val depth: Int get() = ordinal

    val tagLabel: String get() = label.ifBlank { "Group" }

    /** The level immediately above, or null at the top. */
    val parentType: CoaLineType?
        get() = entries.firstOrNull { it.depth == depth - 1 }

    /** The level immediately below, for defaulting a new child's type. */
    val childType: CoaLineType?
        get() = entries.firstOrNull { it.depth == depth + 1 }

    companion object {
        fun from(wire: String?): CoaLineType? =
            entries.firstOrNull { it.wire == wire?.lowercase() }
    }
}

/** The five double-entry classes. */
enum class CoaCostType(val wire: String, val label: String) {
    Asset("asset", "Asset"),
    Liability("liability", "Liability"),
    Capital("capital", "Capital"),
    Income("income", "Income"),
    Expense("expense", "Expense"),
    ;

    /** Everything that is not an expense — the balance-sheet view. */
    val isBalanceSheet: Boolean get() = this != Expense

    companion object {
        /**
         * Defaults to [Expense].
         *
         * That is what budget-imported rows carry, and it is by far the
         * commonest class on a production's chart.
         */
        fun from(wire: String?): CoaCostType =
            entries.firstOrNull { it.wire == wire?.lowercase() } ?: Expense
    }
}

/**
 * One row of the chart of accounts.
 *
 * ## The hierarchy is a breadcrumb, not a pointer
 *
 * There is no `parent_id`. Every row carries the id of *each* ancestor —
 * [headId], [sectionId], [categoryId] — including a self-reference at its own
 * level (a section's [sectionId] is its own id). Building the tree is therefore
 * one group-by pass rather than a recursive walk, and asking "what is above
 * this row" is O(1) at any depth.
 */
data class CoaAccount(
    val id: String,
    val code: String = "",
    val name: String = "",
    val lineType: CoaLineType = CoaLineType.Category,
    val costType: CoaCostType = CoaCostType.Expense,
    val headId: String? = null,
    val sectionId: String? = null,
    val categoryId: String? = null,
    val subCategoryId: String? = null,
    val isActive: Boolean = true,
    /**
     * Whether transactions may code against this row.
     *
     * Opt-out like [isActive]: a row written before the field existed reads as
     * postable, and defaulting it the other way would silently make every
     * legacy code unusable on every line-item picker in the platform.
     */
    val isPosting: Boolean = true,
) {
    /** The immediate parent's id, or null for a top-level row. */
    val parentId: String?
        get() = when (lineType) {
            CoaLineType.Header -> null
            CoaLineType.Section -> headId
            CoaLineType.Category -> sectionId
            CoaLineType.SubCategory -> categoryId
        }

    val display: String get() = listOf(code, name).filter { it.isNotBlank() }.joinToString(" · ")
}

/** A row with its children resolved. */
data class CoaNode(val account: CoaAccount, val children: List<CoaNode> = emptyList()) {
    val id: String get() = account.id

    /** This node and everything beneath it, depth-first. */
    fun flatten(): List<CoaNode> = listOf(this) + children.flatMap { it.flatten() }
}

/**
 * Ordering, tree-building and parent validation for the chart.
 *
 * Kept out of the UI because two consumers need identical answers: the chart
 * screen and, in time, the cost report's worksheet adapter. Two implementations
 * of "which code comes first" is how the web ended up with a tree and a table
 * that disagreed.
 */
object ChartOfAccounts {

    /**
     * Ascending code order.
     *
     * `code` is free text, so it is read as a **number** first: "9" before
     * "99" before "1000", never string order.
     *
     * The conversion is a whole-string parse, not a prefix one. A prefix parse
     * reads "125L" as 125 and files it between 124 and 126; a code carrying any
     * letter is not sortable as a number at all and belongs at the end. Blank
     * is treated the same way — parsed as zero it would sort to the very top,
     * which is exactly where a missing code should not be.
     *
     * Dotted sub-codes read as decimals, so "1100.10" sorts *before* "1100.2".
     * That is deliberate and is the opposite of segment-wise ordering, where
     * `.10` would be the tenth child.
     */
    fun compareCodes(a: String?, b: String?): Int {
        val left = a.orEmpty().trim()
        val right = b.orEmpty().trim()
        val leftNumber = left.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        val rightNumber = right.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        return when {
            leftNumber != null && rightNumber != null ->
                leftNumber.compareTo(rightNumber).takeIf { it != 0 } ?: left.compareTo(right)
            // Exactly one is a number: it wins, pushing the unsortable one down.
            leftNumber != null -> -1
            rightNumber != null -> 1
            else -> left.compareTo(right)
        }
    }

    /**
     * Flat rows to a forest, children sorted by code.
     *
     * Rows whose parent is absent from [rows] — normally because the parent is
     * inactive and the caller asked for active rows only — are **orphans**.
     * They are returned separately rather than dropped: a code the server still
     * accepts postings against, missing from the screen that is supposed to
     * list every code, is worse than one shown without its ancestry.
     */
    fun tree(rows: List<CoaAccount>): CoaForest {
        val childrenOf = rows.groupBy { it.parentId }
        val known = rows.map { it.id }.toSet()

        fun build(account: CoaAccount): CoaNode = CoaNode(
            account = account,
            children = childrenOf[account.id].orEmpty()
                .sortedWith { a, b -> compareCodes(a.code, b.code) }
                .map(::build),
        )

        val roots = rows.filter { it.parentId == null }
            .sortedWith { a, b -> compareCodes(a.code, b.code) }
            .map(::build)
        val orphans = rows.filter { it.parentId != null && it.parentId !in known }
            .sortedWith { a, b -> compareCodes(a.code, b.code) }
            .map(::build)
        return CoaForest(roots = roots, orphans = orphans)
    }

    /**
     * Which rows may be the parent of a new row of [lineType].
     *
     * Empty for a header, which has no parent — and that is a legitimate answer,
     * not a failure to find one.
     */
    fun parentOptions(rows: List<CoaAccount>, lineType: CoaLineType): List<CoaAccount> {
        val required = lineType.parentType ?: return emptyList()
        return rows.filter { it.lineType == required && it.isActive }
            .sortedWith { a, b -> compareCodes(a.code, b.code) }
    }

    /**
     * Why this parent will not do, or null when it will.
     *
     * Returned as prose because it is shown to the person who picked it; a
     * boolean would leave the form saying only that something is wrong.
     */
    fun parentProblem(lineType: CoaLineType, parent: CoaAccount?): String? {
        val required = lineType.parentType
        // The labels are used verbatim rather than lowercased with an article
        // in front: they are proper names on this screen ("Headers", "Nominal",
        // "Code"), and "a headers" is what article-fitting produces.
        return when {
            required == null && parent != null ->
                "${lineType.tagLabel} sits at the top and cannot have a parent."
            required == null -> null
            parent == null -> "Choose the ${required.tagLabel} row this sits under."
            parent.lineType != required ->
                "${lineType.tagLabel} sits under ${required.tagLabel}, " +
                    "not ${parent.lineType.tagLabel}."
            else -> null
        }
    }

    /**
     * Whether [code] is already taken.
     *
     * The code is the natural key and is immutable after create, so this is
     * checked before the call rather than after the server's 409 — by then the
     * form has been dismissed.
     */
    fun codeTaken(rows: List<CoaAccount>, code: String, excludingId: String? = null): Boolean =
        rows.any { it.id != excludingId && it.code.equals(code.trim(), ignoreCase = true) }

    /** Rows matching [term] in code or name. Blank matches nothing, not everything. */
    fun search(rows: List<CoaAccount>, term: String): List<CoaAccount> {
        val needle = term.trim()
        if (needle.isEmpty()) return emptyList()
        return rows.filter {
            it.code.contains(needle, ignoreCase = true) || it.name.contains(needle, ignoreCase = true)
        }
    }
}

/**
 * The chart as a forest, plus whatever could not be placed in it.
 *
 * Orphans are surfaced rather than swallowed — see [ChartOfAccounts.tree].
 */
data class CoaForest(
    val roots: List<CoaNode> = emptyList(),
    val orphans: List<CoaNode> = emptyList(),
) {
    val isEmpty: Boolean get() = roots.isEmpty() && orphans.isEmpty()

    /** Every node in display order, roots first then the unplaceable ones. */
    fun flatten(): List<CoaNode> =
        roots.flatMap { it.flatten() } + orphans.flatMap { it.flatten() }
}

/** An analytical dimension parallel to the nominal chart — locations, episodes. */
data class TrackingSet(
    val id: String,
    val name: String = "",
    val code: String = "",
    val isActive: Boolean = true,
    val nodes: List<TrackingNode> = emptyList(),
)

/** One code within a [TrackingSet]. Nests via [parentId], unlike the chart. */
data class TrackingNode(
    val id: String,
    val setId: String = "",
    val code: String = "",
    val name: String = "",
    val parentId: String? = null,
    val isActive: Boolean = true,
)
