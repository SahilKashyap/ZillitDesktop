package com.zillit.desktop.feature.costumesetsync.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Costumes & Set Sync unread notifications (the web's `lib/badges.js`).
 *
 * The tool's unread is read the way every other tool's is: Zillit's own
 * Notification service through the shared badge ledger. Its rows arrive as
 * tool [CSYNC_BADGE_TOOL], one unit per kind, no levels, and `reference_id`
 * is the record — or, for an upload, the document. The service's own
 * `/notifications` list and mark-read are retired (BE, 2026-10-05).
 */
object CsyncBadges {

    /** The notification service's name for this tool, as its rows carry it. */
    const val CSYNC_BADGE_TOOL = "costume_set_sync_tool_label"

    const val CLEANING = "costume_set_sync_cleaning_label"
    const val ALTERATION = "costume_set_sync_alteration_label"
    const val DAMAGE = "costume_set_sync_damage_label"
    const val MISSING = "costume_set_sync_missing_label"
    const val FITTING = "costume_set_sync_fitting_label"
    const val RENTAL = "costume_set_sync_rental_label"
    const val READINESS = "costume_set_sync_readiness_label"
    const val GENERAL = "costume_set_sync_general_label"

    /** Uploads (BE, 2026-10-07): script and schedule, the call sheet, a budget sheet imported. */
    const val SCENE_BREAKDOWN = "costume_set_sync_scene_breakdown_label"
    const val CONTINUITY = "costume_set_sync_continuity_label"
    const val BUDGET = "costume_set_sync_budget_label"

    /**
     * `level_1` of a "production created" row (unit [GENERAL]): a new
     * production's first script. It has no tab — it counts on the Film Tools
     * tile and clears when the module is opened, scoped by this level so the
     * other [GENERAL] rows (record chat) are left alone.
     */
    const val PRODUCTION_LEVEL = "production"

    /**
     * The page under the module that shows — and, opened, clears — each unit
     * (look one up with [unitForPath]). Readiness is the Dashboard's "Today's
     * scenes"; [GENERAL] has no page: record chat clears per record when that
     * chat opens, "production created" when the module opens.
     *
     * A page mapped to null shows nothing: Continuity ▸ Book never carries the
     * call sheet, so opening it must not clear On set's unread.
     */
    val UNIT_BY_PAGE: Map<String, String?> = mapOf(
        // Uploads: script and schedule on Scene Breakdown (either of its
        // addresses), the call sheet on Continuity ▸ On set — not the Book.
        "breakdown" to SCENE_BREAKDOWN,
        "scenes" to SCENE_BREAKDOWN,
        "continuity" to CONTINUITY,
        "continuity/book" to null,
        // A budget sheet imported — finance roles only, as the Budget tab is.
        "budget" to BUDGET,
        "dashboard" to READINESS,
        "fittings" to FITTING,
        "cleaning" to CLEANING,
        "alterations" to ALTERATION,
        "damages" to DAMAGE,
        "missing" to MISSING,
        "vendors" to RENTAL,
    )

    /**
     * The unit a page shows and clears, from its path under the module
     * (`breakdown`, `continuity/book`, `cleaning/123`…): a two-segment page
     * listed in [UNIT_BY_PAGE] wins (even as null), else the first segment's.
     */
    fun unitForPath(path: String): String? {
        val segments = path.split('/').filter { it.isNotEmpty() }
        val two = segments.take(2).joinToString("/")
        if (UNIT_BY_PAGE.containsKey(two)) return UNIT_BY_PAGE[two]
        return segments.firstOrNull()?.let { UNIT_BY_PAGE[it] }
    }
}

/**
 * The tool's unread, and the reads its screens make — the seam the host app
 * fills from the badge ledger ([CsyncBadges]). Empty off the app, so every
 * screen draws in a test and a screenshot without one.
 */
interface SyncBadges {

    /** Unread per unit, live: `costume_set_sync_cleaning_label` → 3. */
    val unread: Flow<Map<String, Int>> get() = emptyFlow()

    /** A page was opened — its unit is read. */
    fun readUnit(unit: String) {}

    /** The module was opened — "production created" is read, that `level_1` alone. */
    fun readProductionCreated() {}

    /** A record's chat was opened — that record's chat notifications are read. */
    fun readRecordChat(entityId: String) {}

    companion object {
        val None: SyncBadges = object : SyncBadges {}
    }
}
