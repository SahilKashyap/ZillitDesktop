package com.zillit.desktop.core.badges

/**
 * Unread counts, keyed by tool identifier — and, one level finer, by unit id.
 *
 * Immutable; the ledger recomputes the whole set from its rows after every
 * change rather than adjusting deltas — the Android client keeps a running
 * total corrected per socket message (~900 lines of per-tool arithmetic whose
 * drift is permanent until a restart), and a badge that is wrong forever is
 * worse than one that costs a recount.
 */
class BadgeCounts(
    private val bySection: Map<String, Int> = emptyMap(),
    private val byTool: Map<String, Int> = emptyMap(),
    private val byUnit: Map<String, Int> = emptyMap(),
) {

    /** The rail's areas — `home_label`, `cnc_label`, `tools_label`… */
    fun section(key: String): Int = bySection[key] ?: 0

    operator fun get(identifier: String): Int = byTool[identifier] ?: 0

    /**
     * The count for one unit — a board tab, a call-sheet tab. Unit ids are
     * server ids, unique across tools, so one flat map is safe.
     */
    fun unit(unitId: String): Int = byUnit[unitId] ?: 0

    /**
     * The dock/taskbar badge. Sections only: tools and units are the same
     * unread counted at finer grain, and adding the maps together would
     * badge the dock with every message twice or three times over.
     */
    val total: Int get() = bySection.values.sum()

    /** The total with one section's count replaced — the rail's own C&C number, so the dock agrees with it. */
    fun totalWith(section: String, count: Int): Int = total - section(section) + count

    val isEmpty: Boolean get() = bySection.isEmpty() && byTool.isEmpty() && byUnit.isEmpty()

    /** For tests and diagnostics; never for iteration order in the UI. */
    fun asMap(): Map<String, Int> = bySection + byTool

    fun sectionMap(): Map<String, Int> = bySection
    fun toolMap(): Map<String, Int> = byTool
    fun unitMap(): Map<String, Int> = byUnit

    /**
     * Value equality over the three maps.
     *
     * The store is a `StateFlow`, which skips an emission whose value equals
     * the last — with identity equality every recount that changed nothing
     * still redrew the rail, the tabs and every tile.
     */
    override fun equals(other: Any?): Boolean =
        other is BadgeCounts && bySection == other.bySection && byTool == other.byTool && byUnit == other.byUnit

    override fun hashCode(): Int = (bySection.hashCode() * HASH_MIX + byTool.hashCode()) * HASH_MIX + byUnit.hashCode()

    override fun toString(): String =
        "BadgeCounts(sections=${bySection.size}, tools=${byTool.size}, total=$total)"

    companion object {
        val Empty = BadgeCounts()
        private const val HASH_MIX = 31
    }
}

/**
 * The section keys the ledger groups by. Named once so the store, the tally
 * and the wiring do not each spell them.
 */
object BadgeSections {
    const val CNC = "cnc_label"
    const val TOOLS = "tools_label"
    const val HOME = "home_label"
    const val SETTINGS = "settings_label"
    const val SOS = "sos_label"
    const val GLOBAL = "global_label"
    const val EMAIL = "email_label"
    val all: Set<String> = setOf(CNC, TOOLS, HOME, SETTINGS, SOS, GLOBAL, EMAIL)

    /** The three settings units Android sums (`CommonBadgesHandler.kt:2432-2445`). */
    val settingsUnits: Set<String> = setOf(
        "project_join_user_request_label",
        "project_approve_profile_user_request_label",
        "deal_memo_label",
    )
}
