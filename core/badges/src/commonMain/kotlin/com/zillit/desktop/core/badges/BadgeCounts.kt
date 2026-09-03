package com.zillit.desktop.core.badges

/**
 * Unread counts, keyed by tool identifier — and, one level finer, by unit id.
 *
 * Immutable. The set is replaced wholesale on each refresh rather than
 * delta-adjusted: the Android client keeps a running total corrected per
 * socket message — ~900 lines of per-tool arithmetic whose drift is permanent
 * until an app restart — and a badge that is wrong forever is worse than one
 * that updates a beat late. Recomputing trades one cheap request for the
 * whole class of drift bug.
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

    val isEmpty: Boolean get() = bySection.isEmpty() && byTool.isEmpty() && byUnit.isEmpty()

    /** For tests and diagnostics; never for iteration order in the UI. */
    fun asMap(): Map<String, Int> = bySection + byTool

    /** The slices, for a source that must keep the good ones on a partial refresh. */
    fun sectionMap(): Map<String, Int> = bySection
    fun toolMap(): Map<String, Int> = byTool
    fun unitMap(): Map<String, Int> = byUnit

    /**
     * Drops what this person can no longer see.
     *
     * The server's ledger keeps rows for tools and units whose view access
     * was taken away; the phones apply the `notification:silent`
     * instruction to their own copy and never show them. Each dropped key's
     * own count comes off its section total too, so the rail agrees with
     * the tiles.
     */
    fun without(tools: Set<String>, units: Set<String>): BadgeCounts {
        if (tools.isEmpty() && units.isEmpty()) return this
        val droppedTools = tools.sumOf { byTool[it] ?: 0 }
        val droppedUnits = units.sumOf { byUnit[it] ?: 0 }
        return BadgeCounts(
            bySection = bySection
                .lessening(BadgeSections.TOOLS, droppedTools)
                .lessening(BadgeSections.HOME, droppedUnits),
            byTool = byTool - tools,
            byUnit = byUnit - units,
        )
    }

    /** The tool read locally, before the server confirms — and its share of the section. */
    fun clearingTool(identifier: String): BadgeCounts {
        val had = byTool[identifier] ?: return this
        return BadgeCounts(
            bySection = bySection.lessening(BadgeSections.TOOLS, had),
            byTool = byTool - identifier,
            byUnit = byUnit,
        )
    }

    /** A whole section read locally, before the server confirms. */
    fun clearingSection(key: String): BadgeCounts {
        if ((bySection[key] ?: 0) == 0) return this
        val tools = if (key == BadgeSections.TOOLS) emptyMap() else byTool
        val units = if (key == BadgeSections.HOME) emptyMap() else byUnit
        return BadgeCounts(bySection = bySection - key, byTool = tools, byUnit = units)
    }

    /** One more unread, as a `notification:save` frame says — the refresh will confirm. */
    fun bumping(section: String?, tool: String?, unit: String?): BadgeCounts {
        if (section == null && tool == null && unit == null) return this
        return BadgeCounts(
            bySection = section?.let { bySection.adding(it) } ?: bySection,
            byTool = tool?.let { byTool.adding(it) } ?: byTool,
            byUnit = unit?.let { byUnit.adding(it) } ?: byUnit,
        )
    }

    /**
     * Value equality over the three maps.
     *
     * The store is a `StateFlow`, which skips an emission whose value equals
     * the last — with identity equality every refresh that changed nothing
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

        private fun Map<String, Int>.lessening(key: String, by: Int): Map<String, Int> {
            if (by <= 0) return this
            val left = (this[key] ?: 0) - by
            return if (left > 0) this + (key to left) else this - key
        }

        private fun Map<String, Int>.adding(key: String): Map<String, Int> = this + (key to (this[key] ?: 0) + 1)
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
    val all: Set<String> = setOf(CNC, TOOLS, HOME, SETTINGS)
}
