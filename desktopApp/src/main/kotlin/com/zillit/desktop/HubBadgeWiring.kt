package com.zillit.desktop

import com.zillit.desktop.core.badges.BadgeSections
import com.zillit.desktop.core.badges.wireToolToIdentifier
import com.zillit.desktop.feature.accounthub.domain.HubBadges
import com.zillit.desktop.feature.purchaseorder.domain.PoBadgeLeaf
import com.zillit.desktop.feature.purchaseorder.domain.PoBadges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// The account hub's and purchase-order tool's badge reads and leaves — split
// from AccountHubWiring so that file stays within its function budget.

/**
 * A sidebar tool row taken, for the modules whose desktop screens badge
 * nothing inside yet: their rows are read whole — the hub's own unit for an
 * accountant, the tool's own rows for a department user — so the count
 * falls once the module is on screen. Purchase Orders reads per order and
 * Bank Reconciliation per tab; neither is touched here.
 */
internal fun AppGraph.Ready.readHubToolRow(itemId: String, isAccountant: Boolean) {
    val unit = when (itemId) {
        "invoices" -> HubBadges.INVOICES_UNIT
        "card-expenses" -> HubBadges.CARD_UNIT
        "cash-expenses" -> HubBadges.CASH_UNIT
        else -> return
    }
    val tool = when {
        isAccountant -> HubBadges.HUB_TOOL
        itemId == "invoices" -> HubBadges.INVOICES_TOOL
        else -> unit
    }
    hubReadScope.launch {
        if (isAccountant) {
            emitLevelRead(tool = tool, unit = unit)
        } else {
            // A department user's rows are the tool's own (`tool=card_expenses_label`):
            // the whole-tool read, as fronting the tool's window sends it.
            emitToolRead(this@readHubToolRow, wireToolToIdentifier(tool))
        }
    }
}

private val hubReadScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)


// Purchase-order badges ---------------------------------------------------------------------------

/**
 * The purchase-order tool's ledger rows as leaves, and its read.
 *
 * Both trees the web reads (`po-badge-helpers.js`): the accounts console's
 * under `account_hub_label`, the department view's under
 * `purchase_order_label` — every unread row of either with the PO or
 * invoice unit becomes one leaf. The read is the web's `readScope` /
 * `markPoRead` (`PurchaseOrdersModule.jsx:3087`, `DepartmentPOModule.jsx:610`)
 * without the action bucket: the desktop has no query thread of its own to
 * read the `query_chat` rows from, so an opened order reads all of its rows.
 */
internal fun AppGraph.Ready.purchaseOrderBadges(): PoBadges = object : PoBadges {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tools = setOf(PoBadges.TOOL_HUB, PoBadges.TOOL_PO)
    private val units = setOf(PoBadges.UNIT_PO, PoBadges.UNIT_INVOICE)

    override val leaves: Flow<List<PoBadgeLeaf>> = badgeStore.counts.map { leavesNow() }.distinctUntilChanged()

    private fun leavesNow(): List<PoBadgeLeaf> =
        badgeStore.unreadRows(BadgeSections.TOOLS)
            .filter { it.tool in tools && it.unit in units }
            .groupingBy { PoLeafKey(it.tool, it.unit, it.level1, it.level2, it.level3) }
            .eachCount()
            .map { (key, count) -> PoBadgeLeaf(key.tool, key.unit, key.level1, key.level2, key.orderId, count) }

    override fun readOrder(tool: String, level1: String, orderId: String) {
        scope.launch { emitLevelRead(tool = tool, unit = PoBadges.UNIT_PO, level1 = level1, level3 = orderId) }
    }

    override fun readInvoices() {
        scope.launch { emitLevelRead(tool = PoBadges.TOOL_PO, unit = PoBadges.UNIT_INVOICE) }
    }
}

private data class PoLeafKey(
    val tool: String,
    val unit: String,
    val level1: String,
    val level2: String,
    val orderId: String,
)
