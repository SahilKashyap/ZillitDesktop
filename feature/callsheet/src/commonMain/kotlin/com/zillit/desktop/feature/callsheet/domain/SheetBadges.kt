package com.zillit.desktop.feature.callsheet.domain

/**
 * One unread notification row of the call sheet tool, as the ledger holds it:
 * its unit and the three drill levels.
 */
data class BadgeLeaf(
    val unit: String,
    val level1: String = "",
    val level2: String = "",
    val level3: String = "",
    val unread: Int = 1,
)

/** What a badge row is about — the tree's `level_2`. */
enum class BadgeKind(val wire: String) { Report("report"), Comment("comment") }

/**
 * The five lists a badge can sit on — the web's `WORKFLOW_BADGE_SURFACES`.
 * Drafts and Published are whole units; the three others are `level_1` of
 * the approval unit.
 */
enum class BadgeSurface(val wire: String) {
    Drafts("drafts"),
    Received("received"),
    Sent("sent"),
    Finalized("finalized"),
    Published("published"),
    ;

    /** The approval unit's sub-tab surfaces, keyed by their `level_1`. */
    val isApprovalSection: Boolean get() = this == Received || this == Sent || this == Finalized
}

/**
 * The tool's badge counts — badges v2 (`shared/workflow/workflowBadges.js`,
 * bound to `call_sheet_label` by `callSheetBadgeTree.js`):
 *
 * ```
 * unit     the TAB        call_sheet_drafts_label | call_sheet_approval_label | call_sheet_published_label
 * level_1  the Approvals sub-tab (approval unit only): received | sent | finalized
 * level_2  what it is about: report | comment
 * level_3  the sheet id, always
 * ```
 *
 * Legacy units (comment / approved / rejection / reminder) and approval rows
 * whose `level_1` is not a sub-tab are dropped where the tree is built, so no
 * chip, tile or read ever names them. A row whose `level_2` is neither kind
 * has no read that could clear it and is dropped too. A row with no sheet id
 * still counts on its surface, but on no row.
 */
data class SheetBadges(
    val drafts: Int = 0,
    val received: Int = 0,
    val sent: Int = 0,
    val finalized: Int = 0,
    val published: Int = 0,
    /** Per surface, per kind, per sheet id — `rowBadges`. */
    val rows: Map<BadgeSurface, Map<BadgeKind, Map<String, Int>>> = emptyMap(),
) {
    fun surfaceCount(surface: BadgeSurface): Int = when (surface) {
        BadgeSurface.Drafts -> drafts
        BadgeSurface.Received -> received
        BadgeSurface.Sent -> sent
        BadgeSurface.Finalized -> finalized
        BadgeSurface.Published -> published
    }

    /**
     * The top-level Approvals chip sums only the sub-tabs this user can see:
     * a poster sees Sent + Finalized, plus Received when also a final
     * approver; anyone else Received + Finalized. A count on a hidden
     * sub-tab could never be cleared.
     */
    fun approvals(isPoster: Boolean, isFinalApprover: Boolean): Int =
        finalized + (if (isPoster) sent else 0) + (if (!isPoster || isFinalApprover) received else 0)

    fun count(surface: BadgeSurface?, kind: BadgeKind, sheetId: String): Int =
        surface?.let { rows[it]?.get(kind)?.get(sheetId) } ?: 0

    /** Every unread (kind, id, count) leaf of a surface — what a read-on-entry walks. */
    fun leavesOf(surface: BadgeSurface): List<Triple<BadgeKind, String, Int>> =
        rows[surface].orEmpty().flatMap { (kind, byId) ->
            byId.filterValues { it > 0 }.map { (id, count) -> Triple(kind, id, count) }
        }

    companion object {
        const val TOOL = "call_sheet_label"
        const val UNIT_DRAFTS = "call_sheet_drafts_label"
        const val UNIT_APPROVAL = "call_sheet_approval_label"
        const val UNIT_PUBLISHED = "call_sheet_published_label"

        /** Units from the previous badge model — they must not count anywhere. */
        val LEGACY_UNITS = setOf(
            "call_sheet_comment_label",
            "call_sheet_approved_label",
            "call_sheet_approval_rejection_label",
            "call_sheet_approval_reminder_label",
        )

        fun surfaceOf(unit: String, level1: String): BadgeSurface? = when (unit) {
            UNIT_DRAFTS -> BadgeSurface.Drafts
            UNIT_PUBLISHED -> BadgeSurface.Published
            UNIT_APPROVAL -> BadgeSurface.entries.firstOrNull {
                it.isApprovalSection && it.wire == level1.trim().lowercase()
            }
            else -> null
        }

        fun kindOf(level2: String): BadgeKind? =
            BadgeKind.entries.firstOrNull { it.wire == level2.trim().lowercase() }

        /** The tree producer stringifies a missing `level_3`, so "null"/"undefined" is no document. */
        fun isSheetId(level3: String): Boolean =
            level3.trim().let { it.isNotEmpty() && it != "null" && it != "undefined" }

        fun from(leaves: List<BadgeLeaf>): SheetBadges {
            val counts = BadgeSurface.entries.associateWith { 0 }.toMutableMap()
            val rows = BadgeSurface.entries.associateWith { _ ->
                BadgeKind.entries.associateWith { mutableMapOf<String, Int>() }
            }
            leaves.forEach { leaf ->
                val surface = surfaceOf(leaf.unit, leaf.level1) ?: return@forEach
                val kind = kindOf(leaf.level2) ?: return@forEach
                counts[surface] = counts.getValue(surface) + leaf.unread
                if (!isSheetId(leaf.level3)) return@forEach
                val doc = leaf.level3.trim()
                val bucket = rows.getValue(surface).getValue(kind)
                bucket[doc] = (bucket[doc] ?: 0) + leaf.unread
            }
            // Finalized rows keep their comments filed under received / sent: both leaves sum per id.
            val finalizedComments = rows.getValue(BadgeSurface.Finalized).getValue(BadgeKind.Comment)
            listOf(BadgeSurface.Received, BadgeSurface.Sent).forEach { surface ->
                rows.getValue(surface).getValue(BadgeKind.Comment).forEach { (id, n) ->
                    finalizedComments[id] = (finalizedComments[id] ?: 0) + n
                }
            }
            return SheetBadges(
                drafts = counts.getValue(BadgeSurface.Drafts),
                received = counts.getValue(BadgeSurface.Received),
                sent = counts.getValue(BadgeSurface.Sent),
                finalized = counts.getValue(BadgeSurface.Finalized),
                published = counts.getValue(BadgeSurface.Published),
                rows = rows.mapValues { (_, byKind) -> byKind.mapValues { (_, byId) -> byId.toMap() } },
            )
        }

        /**
         * `readPayloads`: the (unit, level_1) scopes one read names — never
         * unit-wide. A Finalized comment lives under received AND sent, so
         * that is two scopes.
         */
        fun readScopes(surface: BadgeSurface, kind: BadgeKind): List<Pair<String, String?>> = when {
            surface == BadgeSurface.Finalized && kind == BadgeKind.Comment ->
                listOf(UNIT_APPROVAL to BadgeSurface.Received.wire, UNIT_APPROVAL to BadgeSurface.Sent.wire)
            surface == BadgeSurface.Drafts -> listOf(UNIT_DRAFTS to null)
            surface == BadgeSurface.Published -> listOf(UNIT_PUBLISHED to null)
            else -> listOf(UNIT_APPROVAL to surface.wire)
        }
    }
}
