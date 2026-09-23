package com.zillit.desktop.feature.accounthub.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Where a budget version is in its life.
 *
 * At most one is Live on a production; promoting another demotes it. A Live or
 * Archived version is read-only — the way to change one is to clone it and
 * edit the clone, which is why this screen never offers an edit on those.
 */
enum class BudgetStatus(val wire: String, private val labelKey: String) {
    Draft("DRAFT", S.draft),
    Approved("APPROVED", S.approved),
    Live("LIVE", S.desktop_status_live),
    Archived("ARCHIVED", S.desktop_archived),
    ;

    val label: String get() = str(labelKey)

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
    /** The stored source file, for "View file"; null when the version was not imported. */
    val attachment: AgreementDocument? = null,
)

/**
 * One line of a budget version.
 *
 * The hierarchy is the chart of accounts': a line names its own level and
 * carries the breadcrumb of ids above it, so the tree is rebuilt the same way
 * [CoaAccount] rebuilds its own.
 *
 * [rollupTotal] is the server's subtotal for a parent — this row and every
 * row beneath it. Never recompute it here: a parent's own [amount] already
 * equals its roll-up, so adding the children on top counts the group twice
 * (the web once showed a writers category at £1,252,345 instead of £686,770
 * that way). A leaf has no roll-up, and its [amount] stands in.
 */
data class BudgetLine(
    val id: String = "",
    /** The chart code, when the line is coded. */
    val account: String = "",
    /** The chart's name for the code. */
    val name: String = "",
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

    /** What the row sorts by: its code, or its own name when uncoded. */
    val title: String get() = account.ifBlank { uncodedName }

    /** The Account column: the code in capitals, or the uncoded line's own name. */
    val codeLabel: String get() = account.uppercase().ifBlank { uncodedName }.ifBlank { EM_DASH }

    /** The Name column. */
    val nameLabel: String get() = name.ifBlank { uncodedName }.ifBlank { EM_DASH }

    /** What the row shows as its figure. */
    val shownTotal: Double get() = rollupTotal ?: amount
}

/** A line with the lines beneath it — a node of the web's `buildLineTree`. */
data class BudgetNode(val line: BudgetLine, val children: List<BudgetNode> = emptyList()) {
    val id: String get() = line.id

    val hasChildren: Boolean get() = children.isNotEmpty()
}

/** One thing the detail pane draws, in the order it draws them. */
sealed interface BudgetItem {
    /** Stable across recompositions, for the list's keys. */
    val key: String

    data class Line(val node: BudgetNode, val depth: Int, val open: Boolean) : BudgetItem {
        override val key: String get() = "line:${node.id}"
    }

    /** "Total · Camera" — closes an open group, after its last child. */
    data class Subtotal(val node: BudgetNode, val depth: Int) : BudgetItem {
        override val key: String get() = "total:${node.id}"
    }

    /** The band above the lines whose parent is not in this version. */
    data class OrphanHeading(val count: Int) : BudgetItem {
        override val key: String get() = "orphans"
    }
}

/**
 * A version's lines as a tree.
 *
 * A line whose parent is not in the list becomes an orphan rather than being
 * dropped: it still carries money, and a budget that silently totals less than
 * its own header is worse than one showing a row out of place.
 */
data class BudgetTree(val roots: List<BudgetNode> = emptyList(), val orphans: List<BudgetNode> = emptyList()) {

    val isEmpty: Boolean get() = roots.isEmpty() && orphans.isEmpty()

    /**
     * The footer's figure: every top-level roll-up plus the orphans. Each
     * root's roll-up already covers its whole subtree, so the children are
     * never added again.
     */
    val total: Double get() = (roots + orphans).sumOf { it.line.shownTotal }

    /** Open when a version is first shown: the top level, as the web opens depth 0. */
    val initiallyOpen: Set<String>
        get() = (roots + orphans).filter { it.hasChildren }.mapTo(mutableSetOf()) { it.id }

    /** The rows to draw with [open] groups expanded, each open group closed by its subtotal. */
    fun items(open: Set<String>): List<BudgetItem> = buildList {
        fun walk(node: BudgetNode, depth: Int) {
            val expanded = node.hasChildren && node.id in open
            add(BudgetItem.Line(node, depth, expanded))
            if (expanded) {
                node.children.forEach { walk(it, depth + 1) }
                add(BudgetItem.Subtotal(node, depth))
            }
        }
        roots.forEach { walk(it, 0) }
        if (orphans.isNotEmpty()) {
            add(BudgetItem.OrphanHeading(orphans.size))
            orphans.forEach { walk(it, 0) }
        }
    }
}

/**
 * Builds the tree from the breadcrumbs, the way the web's `buildLineTree` does.
 *
 * Siblings sort by level, then by code (or uncoded name) ignoring case. A
 * chain of parents that loops back on itself cannot be reached from any root,
 * and is left out rather than walked forever — the web leaves it out too.
 */
fun List<BudgetLine>.asTree(): BudgetTree {
    val byId = associateBy { it.id }
    val children = mutableMapOf<String, MutableList<BudgetLine>>()
    val roots = mutableListOf<BudgetLine>()
    val orphans = mutableListOf<BudgetLine>()
    forEach { line ->
        val parent = line.parentId
        when {
            parent == null -> roots += line
            parent in byId && parent != line.id -> children.getOrPut(parent) { mutableListOf() } += line
            else -> orphans += line
        }
    }
    val order = compareBy<BudgetLine> { it.lineType.ordinal }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }

    fun build(line: BudgetLine, path: Set<String>): BudgetNode = BudgetNode(
        line = line,
        children = children[line.id].orEmpty()
            .filterNot { it.id in path }
            .sortedWith(order)
            .map { build(it, path + it.id) },
    )

    return BudgetTree(
        roots = roots.sortedWith(order).map { build(it, setOf(it.id)) },
        orphans = orphans.sortedWith(order).map { build(it, setOf(it.id)) },
    )
}

/** How the budget's figures read — the web's `BudgetsTab` formatting, in one place. */
object BudgetFigures {

    /** A line's share of the version's total; nothing when the version totals nothing. */
    fun share(amount: Double, versionTotal: Double): Double = if (versionTotal == 0.0) 0.0 else amount / versionTotal

    /** `12.5%` above a tenth of the budget, `0.25%` below it — the web's `toFixed(share > 0.1 ? 1 : 2)`. */
    fun percent(share: Double): String = fixed(share * PERCENT, if (share > TENTH) 1 else 2) + "%"

    /** The allocation bar never vanishes: at least 2% of the track, at most all of it. */
    fun bar(share: Double): Float = ((share * PERCENT).coerceIn(MIN_BAR, PERCENT) / PERCENT).toFloat()

    /**
     * `£686,770` or `£1,234.5` — the web's `toLocaleString("en-GB")`: grouped,
     * up to three decimals, trailing zeros dropped.
     */
    fun money(amount: Double, symbol: String): String {
        if (amount.isNaN() || amount.isInfinite()) return EM_DASH
        val body = symbol + grouped(abs(amount))
        return if (amount < 0) "-$body" else body
    }

    /** As [money], with a dash for nothing — the web's `fmtMoneyFull` for tree rows. */
    fun moneyOrDash(amount: Double, symbol: String): String = if (amount == 0.0) EM_DASH else money(amount, symbol)

    /**
     * `12 Sep, 14:05` in the machine's zone — the web's version-card stamp
     * (`day: 2-digit, month: short, hour, minute`).
     */
    fun stamp(millis: Long?): String {
        val moment = millis?.takeIf { it > 0 }?.let {
            runCatching { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault()) }
                .getOrNull()
        } ?: return EM_DASH
        return "${moment.dayOfMonth.pad()} ${MONTHS[moment.monthNumber - 1]}, " +
            "${moment.hour.pad()}:${moment.minute.pad()}"
    }

    private fun grouped(value: Double): String {
        val thousandths = (value * THOUSANDTHS).roundToLong()
        val whole = (thousandths / THOUSANDTHS.toLong()).toString()
            .reversed().chunked(GROUP).joinToString(",").reversed()
        val fraction = (thousandths % THOUSANDTHS.toLong()).toString().padStart(FRACTION_DIGITS, '0').trimEnd('0')
        return if (fraction.isEmpty()) whole else "$whole.$fraction"
    }

    private fun fixed(value: Double, decimals: Int): String {
        var scale = 1L
        repeat(decimals) { scale *= DECIMAL }
        val scaled = (abs(value) * scale).roundToLong()
        val whole = scaled / scale
        val fraction = (scaled % scale).toString().padStart(decimals, '0')
        val text = if (decimals == 0) "$whole" else "$whole.$fraction"
        return if (value < 0 && scaled != 0L) "-$text" else text
    }

    private fun Int.pad(): String = toString().padStart(2, '0')

    private val MONTHS: List<String>
        get() = listOf(
            S.desktop_month_short_jan, S.desktop_month_short_feb, S.desktop_month_short_mar, S.desktop_month_short_apr,
            S.desktop_month_short_may, S.desktop_month_short_jun, S.desktop_month_short_jul, S.desktop_month_short_aug,
            S.desktop_month_short_sep, S.desktop_month_short_oct, S.desktop_month_short_nov, S.desktop_month_short_dec,
        ).map { str(it) }
    private const val PERCENT = 100.0
    private const val TENTH = 0.1
    private const val MIN_BAR = 2.0
    private const val THOUSANDTHS = 1000.0
    private const val FRACTION_DIGITS = 3
    private const val GROUP = 3
    private const val DECIMAL = 10L
}

private const val EM_DASH = "—"
