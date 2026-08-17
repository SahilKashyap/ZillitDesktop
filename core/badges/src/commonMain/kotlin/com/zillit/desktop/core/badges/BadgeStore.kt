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
     * Replaces the counts from the server.
     *
     * On failure the previous counts stand rather than being cleared: a dropped
     * request is not evidence that everything has been read, and blanking every
     * badge on a flaky connection would tell the user something false.
     */
    suspend fun refresh(): ZillitResult<BadgeCounts> =
        source.fetch().also { result ->
            if (result is ZillitResult.Success) state.value = result.data
        }

    /** Sign-out and production switch — the counts belong to a production. */
    fun clear() {
        state.value = BadgeCounts.Empty
    }
}
