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

    override fun toString(): String =
        "BadgeCounts(sections=${bySection.size}, tools=${byTool.size}, total=$total)"

    companion object {
        val Empty = BadgeCounts()
    }
}
