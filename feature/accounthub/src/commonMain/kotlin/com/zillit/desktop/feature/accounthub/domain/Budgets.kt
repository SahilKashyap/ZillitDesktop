package com.zillit.desktop.feature.accounthub.domain

/**
 * Where a budget version is in its life.
 *
 * At most one is Live on a production; promoting another demotes it. A Live or
 * Archived version is read-only — the way to change one is to clone it and
 * edit the clone, which is why this screen never offers an edit on those.
 */
enum class BudgetStatus(val wire: String, val label: String) {
    Draft("DRAFT", "Draft"),
    Approved("APPROVED", "Approved"),
    Live("LIVE", "Live"),
    Archived("ARCHIVED", "Archived"),
    ;

    /** Whether the version is fixed. */
    val isLocked: Boolean get() = this == Live || this == Archived

    companion object {
        fun from(wire: String?): BudgetStatus =
            entries.firstOrNull { it.wire.equals(wire?.trim(), ignoreCase = true) } ?: Draft
    }
}

/**
 * One version of the production's budget.
 *
 * [total] is the server's, not a sum of the lines: a version can hold rows the
 * chart no longer codes, and re-adding them here would quietly disagree with
 * every other screen that reads the same number.
 */
data class BudgetVersion(
    val id: String = "",
    /** The short label, e.g. `v3`. */
    val version: String = "",
    val name: String = "",
    val status: BudgetStatus = BudgetStatus.Draft,
    val total: Double = 0.0,
    /** Blank falls through to the production's default where it is shown. */
    val currencyCode: String = "",
    val createdAtMillis: Long? = null,
    /** The file it was imported from, when it was. */
    val sourceFileName: String = "",
)

/**
 * One line of a budget version.
 *
 * The hierarchy is the chart of accounts': a line names its own level and
 * carries the breadcrumb of ids above it, so the tree is rebuilt the same way
 * [CoaAccount] rebuilds its own.
 *
 * [rollupTotal] is the server's subtotal for a parent. Where it is absent the
 * line's own [amount] stands in — a leaf has no roll-up to speak of.
 */
data class BudgetLine(
    val id: String = "",
    /** The chart code, when the line is coded. */
    val account: String = "",
    /** What an uncoded line calls itself instead. */
    val uncodedName: String = "",
    val amount: Double = 0.0,
    val rollupTotal: Double? = null,
    val lineType: CoaLineType = CoaLineType.SubCategory,
    val headId: String? = null,
    val sectionId: String? = null,
    val categoryId: String? = null,
) {
    /** The immediate parent's id, or null at the top. */
    val parentId: String?
        get() = when (lineType) {
            CoaLineType.Header -> null
            CoaLineType.Section -> headId
            CoaLineType.Category -> sectionId
            CoaLineType.SubCategory -> categoryId
        }

    /** What the row is called: its code, or its own name when uncoded. */
    val title: String get() = account.ifBlank { uncodedName }

    /** What the row shows as its figure. */
    val shownTotal: Double get() = rollupTotal ?: amount
}

/** A budget line flattened for display, with the depth it reads at. */
data class BudgetRow(val line: BudgetLine, val depth: Int, val orphaned: Boolean = false)

/**
 * The lines as rows, parents before children.
 *
 * A line whose parent is not in the list is kept and marked [BudgetRow.orphaned]
 * rather than dropped: it still carries money, and a budget that silently
 * totals less than its own header is worse than one that shows a row out of
 * place. Siblings sort by level, then by what they are called.
 */
fun List<BudgetLine>.asRows(): List<BudgetRow> {
    val byId = associateBy { it.id }
    val children = groupBy { it.parentId }
    val out = mutableListOf<BudgetRow>()

    fun walk(parentId: String?, depth: Int) {
        children[parentId].orEmpty()
            .sortedWith(compareBy({ it.lineType.ordinal }, { it.title }))
            .forEach { line ->
                out += BudgetRow(line, depth)
                walk(line.id, depth + 1)
            }
    }

    walk(null, 0)
    // Whatever the walk could not reach: a line naming a parent that is not
    // here. Shown last, flat, and flagged.
    val placed = out.mapTo(mutableSetOf()) { it.line.id }
    filterNot { it.id in placed || it.parentId == null }
        .filter { byId[it.parentId] == null }
        .sortedWith(compareBy({ it.lineType.ordinal }, { it.title }))
        .forEach { out += BudgetRow(it, depth = 0, orphaned = true) }
    return out
}
