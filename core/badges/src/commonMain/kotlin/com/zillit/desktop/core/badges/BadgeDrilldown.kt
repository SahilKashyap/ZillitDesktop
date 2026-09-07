package com.zillit.desktop.core.badges

import com.zillit.desktop.core.common.ZillitResult

/**
 * One grouped drill into the ledger — a tool screen asking for its own
 * tabs' counts.
 *
 * Separate from the store's standing counts because these are per-screen
 * questions asked when a screen opens; a PO queue's tab counts have no
 * business living in every rail redraw.
 */
fun interface BadgeDrilldown {
    suspend fun unread(query: BadgeDrilldownQuery): ZillitResult<Map<String, Int>>
}

/**
 * The scope to drill into and the dimension to group the answer by.
 *
 * [groupBy] names a record field (`tool`, `unit`, `level_1`, `level_2`…);
 * the answer maps that field's values to their unread.
 */
data class BadgeDrilldownQuery(
    val groupBy: String,
    val section: String? = null,
    val tool: String? = null,
    val unit: String? = null,
    val level1: String? = null,
    val level2: String? = null,
)
