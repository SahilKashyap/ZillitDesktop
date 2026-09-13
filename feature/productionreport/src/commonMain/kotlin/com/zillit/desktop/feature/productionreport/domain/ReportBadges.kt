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

/** Which list a comment badge belongs on. `Finalized` is derived: every status comment, both audiences. */
enum class CommentScope { Drafts, Sent, Received, Published, Finalized }

/**
 * The tool's badge counts — `getProductionReportBadges` + `getCommentBadges`
 * (`productionReportUtils.js:282-364`, `shared/workflow/commentBadges.js`).
 */
data class ReportBadges(
    /** Rejection unit + Sent comments. */
    val sent: Int = 0,
    /** Approval + reminder units + Received comments. */
    val received: Int = 0,
    /** Approved unit (comments never badge Finalized's chip). */
    val finalized: Int = 0,
    val approvalSentUnits: Int = 0,
    val approvalReceivedUnits: Int = 0,
    /** Rows under a unit this client does not route — drained on Approvals entry. */
    val unmapped: Int = 0,
    val unmappedUnits: List<String> = emptyList(),
    val drafts: Int = 0,
    val published: Int = 0,
    /** Every status-level comment, including ones with no recognised sub-tab. */
    val commentStatus: Int = 0,
    val commentSent: Int = 0,
    val commentReceived: Int = 0,
    val commentsByScope: Map<CommentScope, Map<String, Int>> = emptyMap(),
    /** Every unread comment per report, whatever tab it was filed under. */
    val commentUnreadByReport: Map<String, Int> = emptyMap(),
) {
    fun commentsFor(scope: CommentScope?, reportId: String): Int =
        scope?.let { commentsByScope[it]?.get(reportId) } ?: 0

    companion object {
        const val TOOL = "production_report_label"
        const val UNIT_APPROVAL = "production_report_approval_label"
        const val UNIT_REMINDER = "production_report_approval_reminder_label"
        const val UNIT_REJECTION = "production_report_approval_rejection_label"
        const val UNIT_APPROVED = "production_report_approved_label"
        const val UNIT_COMMENT = "production_report_comment_label"

        val RECEIVED_UNITS = listOf(UNIT_APPROVAL, UNIT_REMINDER)
        val SENT_UNITS = listOf(UNIT_REJECTION)
        val FINALIZED_UNITS = listOf(UNIT_APPROVED)
        private val ROUTED_UNITS = (RECEIVED_UNITS + SENT_UNITS + FINALIZED_UNITS).toSet()
        private val COMMENTISH = Regex("comment", RegexOption.IGNORE_CASE)

        fun from(leaves: List<BadgeLeaf>): ReportBadges {
            val byUnit = leaves.groupBy { it.unit }.mapValues { (_, rows) -> rows.sumOf { it.unread } }
            fun units(names: List<String>) = names.sumOf { byUnit[it] ?: 0 }
            val unmapped = byUnit.filterKeys {
                it.isNotBlank() && it !in ROUTED_UNITS && !COMMENTISH.containsMatchIn(it)
            }
            val comments = commentCounts(leaves.filter { it.unit == UNIT_COMMENT })
            val approvalReceived = units(RECEIVED_UNITS)
            val approvalSent = units(SENT_UNITS)
            return comments.copy(
                sent = approvalSent + comments.commentSent,
                received = approvalReceived + comments.commentReceived,
                finalized = units(FINALIZED_UNITS),
                approvalSentUnits = approvalSent,
                approvalReceivedUnits = approvalReceived,
                unmapped = unmapped.values.sum(),
                unmappedUnits = unmapped.keys.toList(),
            )
        }

        /** `commentTabOf`: `status`/`approvals` → status, `published` → published, anything else → drafts. */
        private fun tabOf(level1: String): String = when (level1.trim().lowercase()) {
            "status", "approvals" -> "status"
            "published" -> "published"
            else -> "drafts"
        }

        private fun commentCounts(leaves: List<BadgeLeaf>): ReportBadges {
            val tabbed = leaves.map { leaf -> Triple(leaf, tabOf(leaf.level1), leaf.level2.trim().lowercase()) }
            val status = tabbed.filter { it.second == "status" }
            val byScope = CommentScope.entries.associateWith { mutableMapOf<String, Int>() }
            val byReport = mutableMapOf<String, Int>()
            tabbed.forEach { (leaf, tab, sub) ->
                val doc = leaf.level3.trim()
                if (doc.isEmpty() || doc == "null" || doc == "undefined") return@forEach
                byReport.add(doc, leaf.unread)
                scopeOf(tab, sub)?.let { byScope.getValue(it).add(doc, leaf.unread) }
                if (tab == "status") byScope.getValue(CommentScope.Finalized).add(doc, leaf.unread)
            }
            return ReportBadges(
                drafts = tabbed.filter { it.second == "drafts" }.sumOf { it.first.unread },
                published = tabbed.filter { it.second == "published" }.sumOf { it.first.unread },
                commentStatus = status.sumOf { it.first.unread },
                commentSent = status.filter { it.third == "sent" }.sumOf { it.first.unread },
                commentReceived = status.filter { it.third == "received" }.sumOf { it.first.unread },
                commentsByScope = byScope,
                commentUnreadByReport = byReport,
            )
        }

        /** Drafts and Published rows scope by tab; status rows by their sub-tab, or nowhere. */
        private fun scopeOf(tab: String, sub: String): CommentScope? = when {
            tab == "drafts" -> CommentScope.Drafts
            tab == "published" -> CommentScope.Published
            sub == "sent" -> CommentScope.Sent
            sub == "received" -> CommentScope.Received
            else -> null
        }

        private fun MutableMap<String, Int>.add(key: String, count: Int) {
            this[key] = (this[key] ?: 0) + count
        }
    }
}
