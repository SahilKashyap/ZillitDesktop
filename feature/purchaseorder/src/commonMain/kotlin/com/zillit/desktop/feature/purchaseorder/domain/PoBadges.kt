package com.zillit.desktop.feature.purchaseorder.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * One unread leaf of the purchase-order ledger.
 *
 * The web's two modules read two trees of the same shape
 * (`po-badge-helpers.js`): the accounts console's rows file under
 * `tool=account_hub_label`, the department view's under
 * `tool=purchase_order_label`; both put `purchase_order_label` in `unit`,
 * the tab in `level_1` (`all_po_unit`, `po_queue_assigned`,
 * `po_approval_queue`, `my_po`), the action bucket in `level_2`
 * (`purchase_order_label`, or `query_chat`) and the order in `level_3`.
 * The department view's invoice rows ride the same tool with
 * `unit=invoice_label` — its Invoices tab's count.
 */
data class PoBadgeLeaf(
    val tool: String,
    val unit: String,
    val level1: String,
    val level2: String,
    val orderId: String,
    val unread: Int,
)

/**
 * The tool's unread rows, and the one read its screens make — an order's
 * rows under one tab, when the order is opened (`PODetailModal`) or, on the
 * approval queue, when it is decided (ZL-20775: a failed decision leaves
 * the badge lit).
 */
interface PoBadges {
    val leaves: Flow<List<PoBadgeLeaf>> get() = emptyFlow()

    fun readOrder(tool: String, level1: String, orderId: String) {}

    /**
     * One bucket of an order's rows read — the web keeps two apart: opening the
     * order reads its `purchase_order_label` rows (`PODetailModal`'s on-open
     * read) and opening the Query panel reads its `query_chat` rows. A host
     * that does not tell the buckets apart reads the whole order, which is what
     * [readOrder] did before this existed, so the default delegates to it.
     */
    fun readOrder(tool: String, level1: String, orderId: String, bucket: PoBadgeBucket) =
        readOrder(tool, level1, orderId)

    /**
     * An order's query thread read wherever it is filed — iOS `readBadge(entityType:entityId:)`
     * (`AccountHubViewModel+funcs.swift:55-100`): `unit=purchase_order_label`,
     * `level_2=query_chat`, `level_3=<order>` and **no** `level_1`, because the
     * thread's rows land under whichever tab the message fired on (`all_po_unit`,
     * `po_queue_assigned`, `my_po`…) and opening the thread acknowledges all of
     * them. Reading one fixed tab left the others' query rows lit.
     */
    fun readQuery(tool: String, orderId: String) {}

    /**
     * The department view's Invoices tab taken — the hand-off to the invoices
     * tool reads the invoice rows filed under this tool, whole: the desktop's
     * invoices tool has no per-invoice read yet to clear them one by one.
     */
    fun readInvoices() {}

    companion object {
        val None: PoBadges = object : PoBadges {}

        const val TOOL_HUB = "account_hub_label"
        const val TOOL_PO = "purchase_order_label"
        const val UNIT_PO = "purchase_order_label"
        const val UNIT_INVOICE = "invoice_label"
        const val LEVEL_ALL_POS = "all_po_unit"
        const val LEVEL_QUEUE = "po_queue_assigned"
        const val LEVEL_APPROVAL_QUEUE = "po_approval_queue"
        const val LEVEL_MY_POS = "my_po"
        const val ACTION_ORDER = "purchase_order_label"
        const val ACTION_QUERY = "query_chat"
    }
}

/** The two kinds of unread row an order carries, by the wire's `level_2`. */
enum class PoBadgeBucket(val level2: String) {
    /** Everything but the query thread — read when the order is opened. */
    Order(PoBadges.ACTION_ORDER),

    /** The query thread's rows — read only when the Query panel opens. */
    Query(PoBadges.ACTION_QUERY),
}

/** Where one tab's rows live — its tool and `level_1` — for the tabs that carry a badge slice. */
data class PoBadgeScope(val tool: String, val level1: String)

/** The leaves cut the way the screen asks: a tab, one order on it. */
data class PoUnread(val leaves: List<PoBadgeLeaf> = emptyList()) {

    fun tab(scope: PoBadgeScope?): Int = scope?.let { s -> leaves.filter { it.isIn(s) }.sumOf { it.unread } } ?: 0

    /** The department view's Invoices tab: every invoice row under the PO tool. */
    val invoices: Int
        get() = leaves.filter { it.tool == PoBadges.TOOL_PO && it.unit == PoBadges.UNIT_INVOICE }.sumOf { it.unread }

    fun order(scope: PoBadgeScope?, orderId: String): Int =
        scope?.let { s -> leaves.filter { it.isIn(s) && it.orderId == orderId }.sumOf { it.unread } } ?: 0

    /** One bucket of an order's rows on a tab — what is left to clear once the other has been read. */
    fun order(scope: PoBadgeScope?, orderId: String, bucket: PoBadgeBucket): Int =
        scope?.let { s ->
            leaves.filter { it.isIn(s) && it.orderId == orderId && it.inBucket(bucket) }.sumOf { it.unread }
        } ?: 0

    /**
     * An order's query-thread rows under [tool] on every tab — what [PoBadges.readQuery]
     * clears. The thread is one conversation, whichever `level_1` each message was
     * filed under.
     */
    fun query(tool: String, orderId: String): Int =
        leaves.filter {
            it.tool == tool && it.unit == PoBadges.UNIT_PO && it.orderId == orderId && it.inBucket(PoBadgeBucket.Query)
        }.sumOf { it.unread }

    private fun PoBadgeLeaf.inBucket(bucket: PoBadgeBucket): Boolean =
        (level2 == PoBadges.ACTION_QUERY) == (bucket == PoBadgeBucket.Query)

    private fun PoBadgeLeaf.isIn(scope: PoBadgeScope): Boolean =
        tool == scope.tool && unit == PoBadges.UNIT_PO && level1 == scope.level1

    companion object {
        val None = PoUnread()
    }
}
