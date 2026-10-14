package com.zillit.desktop

import com.zillit.desktop.core.badges.BadgeSections
import com.zillit.desktop.feature.purchaseorder.domain.PoBadgeBucket
import com.zillit.desktop.feature.purchaseorder.domain.PoBadgeLeaf
import com.zillit.desktop.feature.purchaseorder.domain.PoBadges
import com.zillit.desktop.feature.timecard.domain.TimecardBadges
import com.zillit.desktop.feature.timecard.domain.TimecardUnreadRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// The account hub's purchase-order and timecard badge reads and counts — split
// from AccountHubWiring so that file stays within its function budget.
//
// The hub's sidebar rows read nothing when taken (see `AccountHubViewModel.handOff`):
// Invoices, Card and Cash read their own rows, as on iOS and the web.

// Purchase-order badges ---------------------------------------------------------------------------

/**
 * The purchase-order tool's ledger rows as leaves, and its read.
 *
 * Both trees the web reads (`po-badge-helpers.js`): the accounts console's
 * under `account_hub_label`, the department view's under
 * `purchase_order_label` — every unread row of either with the PO or
 * invoice unit becomes one leaf. An opened order reads its own bucket on its
 * tab (the web's `readScope` / `markPoRead`, `PurchaseOrdersModule.jsx:3087`,
 * `DepartmentPOModule.jsx:610`); its query thread reads `query_chat` on every
 * tab ([PoBadges.readQuery]).
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

    // Opening the order reads its purchase_order_label rows; opening the Query
    // panel reads its query_chat rows — the web keeps the two apart, so an unseen
    // query is not cleared by merely opening the order.
    override fun readOrder(tool: String, level1: String, orderId: String, bucket: PoBadgeBucket) {
        scope.launch {
            emitLevelRead(
                tool = tool,
                unit = PoBadges.UNIT_PO,
                level1 = level1,
                level2 = bucket.level2,
                level3 = orderId,
            )
        }
    }

    // iOS `readBadge(entityType:entityId:)`: the thread on every tab — no level_1.
    override fun readQuery(tool: String, orderId: String) {
        scope.launch {
            emitLevelRead(tool = tool, unit = PoBadges.UNIT_PO, level2 = PoBadges.ACTION_QUERY, level3 = orderId)
        }
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

// Timecard badges -----------------------------------------------------------------------------------

/**
 * The timecard tool's ledger rows and its reads — iOS `TimecardBadgeViewModel`.
 *
 * Every row under `timecard_label` reaches the screen as it is, so it can count
 * per tab, per week and per record; a read is one record (`level_3`, with its
 * week's `level_1` when given) or one week (`level_1`) in one unit, sent as
 * `notification:level:read` and applied to the ledger at once — iOS
 * `readToolMessage(unit, action: .timecard, level1:, level2: nil, level3:)`
 * (`TimecardBadgeViewModel.swift:381-387, 401-407`).
 */
internal fun AppGraph.Ready.timecardBadges(): TimecardBadges = object : TimecardBadges {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val rows: Flow<List<TimecardUnreadRow>> = badgeStore.counts
        .map {
            badgeStore.unreadRows(BadgeSections.TOOLS)
                .filter { it.tool == TIMECARD_TOOL }
                .map { TimecardUnreadRow(unit = it.unit, level1 = it.level1, level3 = it.level3, created = it.created) }
        }
        .distinctUntilChanged()

    override fun readRecord(unit: String, recordId: String, week: String?) {
        if (recordId.isBlank()) return
        scope.launch { emitLevelRead(tool = TIMECARD_TOOL, unit = unit, level1 = week, level3 = recordId) }
    }

    override fun readWeek(unit: String, week: String) {
        if (week.isBlank()) return
        scope.launch { emitLevelRead(tool = TIMECARD_TOOL, unit = unit, level1 = week) }
    }
}

/** Every timecard and dispute event files under this tool, the tile as its unit (`constants.js:107-116`). */
private const val TIMECARD_TOOL = "timecard_label"
