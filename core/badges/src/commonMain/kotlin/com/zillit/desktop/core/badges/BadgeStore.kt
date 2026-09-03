package com.zillit.desktop.core.badges

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Fetches the current unread counts for the open production. */
fun interface BadgeSource {
    suspend fun fetch(): ZillitResult<BadgeCounts>
}

/**
 * One grouped drill into the unread ledger — a tool screen asking for its
 * own tabs' counts (`?section=…&tool=…&group=level_1` and friends).
 *
 * Separate from [BadgeSource] because these are per-screen questions asked
 * when a screen opens, not the app-wide set the store refreshes; a PO queue's
 * tab counts have no business living in every rail redraw.
 */
fun interface BadgeDrilldown {
    suspend fun unread(query: BadgeDrilldownQuery): ZillitResult<Map<String, Int>>
}

/**
 * The scope to drill into and the dimension to group the answer by.
 *
 * [groupBy] names a record field (`tool`, `unit`, `level_1`, `level_2`…);
 * the answer maps that field's values to their unread. Null scope fields are
 * simply not sent.
 */
data class BadgeDrilldownQuery(
    val groupBy: String,
    val section: String? = null,
    val tool: String? = null,
    val unit: String? = null,
    val level1: String? = null,
    val level2: String? = null,
)

/**
 * The live unread counts, for whoever draws a badge.
 *
 * A single store rather than per-consumer state: the rail, the window tabs and
 * (later) the dock badge all show the same numbers, and three copies would
 * disagree the moment one missed an update.
 *
 * Lives in `core:` because its consumers span modules — `feature:shell` draws
 * the rail, `core:workspace` draws the tabs, and neither should depend on the
 * other to get a count.
 */
class BadgeStore(private val source: BadgeSource) {

    private val state = MutableStateFlow(BadgeCounts.Empty)
    val counts: StateFlow<BadgeCounts> = state.asStateFlow()

    /**
     * Tools and units this person has lost sight of, per `notification:silent`.
     *
     * Remembered, not just applied once: the server never removes those rows
     * from its ledger, so every later [refresh] would put them back. iOS keeps
     * the same memory in its local notification database.
     */
    private var lostTools: Set<String> = emptySet()
    private var lostUnits: Set<String> = emptySet()

    /** Applies the silent instruction now and to every refresh after it. */
    fun suppress(tools: Set<String> = emptySet(), units: Set<String> = emptySet()) {
        if (tools.isEmpty() && units.isEmpty()) return
        lostTools = lostTools + tools
        lostUnits = lostUnits + units
        state.value = state.value.without(tools, units)
    }

    /** The badge goes out the moment the read is sent; the refresh confirms. */
    fun clearTool(identifier: String) {
        state.value = state.value.clearingTool(identifier)
    }

    fun clearSection(key: String) {
        state.value = state.value.clearingSection(key)
    }

    /** One more unread, as a `notification:save` frame arrives; the refresh confirms. */
    fun bump(section: String?, tool: String?, unit: String?) {
        state.value = state.value.bumping(section, tool, unit).without(lostTools, lostUnits)
    }

    /**
     * Replaces the counts from the server.
     *
     * On failure the previous counts stand rather than being cleared: a dropped
     * request is not evidence that everything has been read, and blanking every
     * badge on a flaky connection would tell the user something false.
     */
    suspend fun refresh(): ZillitResult<BadgeCounts> =
        source.fetch().also { result ->
            if (result is ZillitResult.Success) state.value = result.data.without(lostTools, lostUnits)
        }

    /** Sign-out and production switch — the counts, and what was lost, belong to a production. */
    fun clear() {
        lostTools = emptySet()
        lostUnits = emptySet()
        state.value = BadgeCounts.Empty
    }
}
