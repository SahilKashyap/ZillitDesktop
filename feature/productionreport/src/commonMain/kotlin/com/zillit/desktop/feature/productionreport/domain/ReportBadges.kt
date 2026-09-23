package com.zillit.desktop.feature.productionreport.domain

/**
 * One unread notification row of the production report tool, as the ledger
 * holds it: its unit and the three drill levels.
 */
data class BadgeLeaf(
    val unit: String,
    val level1: String = "",
    val level2: String = "",
    val level3: String = "",
    val unread: Int = 1,
)

/** The five lists a badge can sit on — `WORKFLOW_BADGE_SURFACES`. */
enum class BadgeSurface(val wire: String) {
    Drafts("drafts"),
    Received("received"),
    Sent("sent"),
    Finalized("finalized"),
    Published("published"),
    ;

    /** The Approvals sub-tabs live under one unit and are told apart by `level_1`. */
    val isApproval: Boolean get() = this == Received || this == Sent || this == Finalized

    companion object {
        fun fromWire(value: String): BadgeSurface? = entries.firstOrNull { it.wire == value.trim().lowercase() }
    }
}

/** What a badge is about — `level_2`: the report itself, or its comment thread. */
enum class BadgeKind(val wire: String) {
    Report("report"),
    Comment("comment"),
    ;

    companion object {
        fun fromWire(value: String): BadgeKind? = entries.firstOrNull { it.wire == value.trim().lowercase() }
    }
}

/** One unread leaf a surface holds: the report, what about it, and how many. */
data class UnreadLeaf(val kind: BadgeKind, val reportId: String, val unread: Int)

/**
 * Badges v2 (backend spec, Sep 2026) — `shared/workflow/workflowBadges.js`
 * bound to `production_report_label` (`productionReportBadgeTree.js`):
 *
 *   unit     the TAB        drafts | approval | published
 *   level_1  the Approvals sub-tab (approval unit only): received | sent | finalized
 *   level_2  report | comment
 *   level_3  the production report id
 *
 * Legacy units, an approval-unit `level_1` other than the three sub-tabs and
 * a `level_2` other than report|comment are dropped where the tree is built,
 * so tabs, rows and the tool total count the same rows and no drain read is
 * ever sent for them. Rows under any other unit (the tool chat, keyed by the
 * tool's own id) are the chat's, never Manage's.
 */
data class ReportBadges(
    val drafts: Int = 0,
    val received: Int = 0,
    val sent: Int = 0,
    val finalized: Int = 0,
    val published: Int = 0,
    /** Per surface, per kind, per report id. Finalized's comments are Received's plus Sent's. */
    val rows: Map<BadgeSurface, Map<BadgeKind, Map<String, Int>>> = emptyMap(),
) {
    fun surfaceCount(surface: BadgeSurface): Int = when (surface) {
        BadgeSurface.Drafts -> drafts
        BadgeSurface.Received -> received
        BadgeSurface.Sent -> sent
        BadgeSurface.Finalized -> finalized
        BadgeSurface.Published -> published
    }

    /** One report's unread of one kind on one surface. */
    fun count(surface: BadgeSurface?, kind: BadgeKind, reportId: String): Int =
        surface?.let { rows[it]?.get(kind)?.get(reportId) } ?: 0

    /** Every unread leaf of a surface — what an entry read walks. */
    fun leaves(surface: BadgeSurface): List<UnreadLeaf> =
        rows[surface].orEmpty().flatMap { (kind, byId) ->
            byId.filter { it.value > 0 }.map { (id, unread) -> UnreadLeaf(kind, id, unread) }
        }

    companion object {
        const val TOOL = "production_report_label"
        const val UNIT_DRAFTS = "production_report_drafts_label"
        const val UNIT_APPROVAL = "production_report_approval_label"
        const val UNIT_PUBLISHED = "production_report_published_label"

        /** Units of the previous model; they must not count anywhere (`PR_LEGACY_BADGE_UNITS`). */
        val LEGACY_UNITS = listOf(
            "production_report_comment_label",
            "production_report_approved_label",
            "production_report_approval_rejection_label",
            "production_report_approval_reminder_label",
        )

        val UNITS = listOf(UNIT_DRAFTS, UNIT_APPROVAL, UNIT_PUBLISHED)

        /** The unit a surface's rows are filed under. */
        fun unitOf(surface: BadgeSurface): String = when (surface) {
            BadgeSurface.Drafts -> UNIT_DRAFTS
            BadgeSurface.Published -> UNIT_PUBLISHED
            else -> UNIT_APPROVAL
        }

        fun from(leaves: List<BadgeLeaf>): ReportBadges {
            val counts = BadgeSurface.entries.associateWith { 0 }.toMutableMap()
            val rows = BadgeSurface.entries.associateWith {
                BadgeKind.entries.associateWith { mutableMapOf<String, Int>() }
            }
            leaves.forEach { leaf ->
                val surface = surfaceOf(leaf) ?: return@forEach
                val kind = BadgeKind.fromWire(leaf.level2) ?: return@forEach
                counts[surface] = (counts[surface] ?: 0) + leaf.unread
                val id = leaf.level3.trim()
                if (isDocId(id)) rows.getValue(surface).getValue(kind).add(id, leaf.unread)
            }
            // The backend files a finalized report's comments under received / sent.
            val finalizedComments = rows.getValue(BadgeSurface.Finalized).getValue(BadgeKind.Comment)
            rows.getValue(BadgeSurface.Received).getValue(BadgeKind.Comment).forEach { (id, n) ->
                finalizedComments.add(id, n)
            }
            rows.getValue(BadgeSurface.Sent).getValue(BadgeKind.Comment).forEach { (id, n) ->
                finalizedComments.add(id, n)
            }
            return ReportBadges(
                drafts = counts.getValue(BadgeSurface.Drafts),
                received = counts.getValue(BadgeSurface.Received),
                sent = counts.getValue(BadgeSurface.Sent),
                finalized = counts.getValue(BadgeSurface.Finalized),
                published = counts.getValue(BadgeSurface.Published),
                rows = rows.mapValues { (_, byKind) -> byKind.mapValues { (_, byId) -> byId.toMap() } },
            )
        }

        /** `keepUnit` + `keepLevel1`: the surface a leaf counts on, or null when it is a legacy row. */
        private fun surfaceOf(leaf: BadgeLeaf): BadgeSurface? = when (leaf.unit) {
            UNIT_DRAFTS -> BadgeSurface.Drafts
            UNIT_PUBLISHED -> BadgeSurface.Published
            UNIT_APPROVAL -> BadgeSurface.fromWire(leaf.level1)?.takeIf { it.isApproval }
            else -> null
        }

        /** The tree producer stringifies a missing `level_3`, so "null"/"undefined" is no document. */
        private fun isDocId(id: String): Boolean = id.isNotEmpty() && id != "null" && id != "undefined"

        private fun MutableMap<String, Int>.add(key: String, count: Int) {
            this[key] = (this[key] ?: 0) + count
        }
    }
}
