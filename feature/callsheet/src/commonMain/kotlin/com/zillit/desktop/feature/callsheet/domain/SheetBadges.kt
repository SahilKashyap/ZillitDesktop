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

/** Which list a comment badge belongs on. `Finalized` is derived: every status comment, both audiences. */
enum class CommentScope { Drafts, Sent, Received, Published, Finalized }

/**
 * The tool's badge counts — `getCallSheetBadges` + `getCallSheetCommentBadges`
 * (`callsheetUtils.js:283-490`, `shared/workflow/commentBadges.js`).
 *
 * The call sheet differs from the production report in one rule: approved-unit
 * rows filed under the `status` tab are the sender's news, so they badge
 * **Sent**, and Sent clears them with a level-scoped read.
 */
data class SheetBadges(
    val sent: Int = 0,
    val received: Int = 0,
    val finalized: Int = 0,
    /** The top-level Approvals chip. */
    val approvals: Int = 0,
    val drafts: Int = 0,
    val published: Int = 0,
    /** Unread approval-unit rows that Sent's entry clears. */
    val sentUnits: Int = 0,
    /** Approved-unit rows filed under `status` — cleared with a level read on Sent. */
    val approvedStatus: Int = 0,
    val receivedUnits: Int = 0,
    val commentsByScope: Map<CommentScope, Map<String, Int>> = emptyMap(),
) {
    fun commentsFor(scope: CommentScope?, sheetId: String): Int =
        scope?.let { commentsByScope[it]?.get(sheetId) } ?: 0

    companion object {
        const val TOOL = "call_sheet_label"
        const val UNIT_APPROVAL = "call_sheet_approval_label"
        const val UNIT_REMINDER = "call_sheet_approval_reminder_label"
        const val UNIT_REJECTION = "call_sheet_approval_rejection_label"
        const val UNIT_APPROVED = "call_sheet_approved_label"
        const val UNIT_COMMENT = "call_sheet_comment_label"

        fun from(leaves: List<BadgeLeaf>): SheetBadges {
            val byUnit = leaves.groupBy { it.unit }.mapValues { (_, rows) -> rows.sumOf { it.unread } }
            fun unit(name: String) = byUnit[name] ?: 0
            val approvalReceived = unit(UNIT_APPROVAL) + unit(UNIT_REMINDER)
            val approvalSent = unit(UNIT_REJECTION)
            val approvedStatus = leaves
                .filter { it.unit == UNIT_APPROVED && tabOf(it.level1) == TAB_STATUS }
                .sumOf { it.unread }
            val finalized = (unit(UNIT_APPROVED) - approvedStatus).coerceAtLeast(0)
            val comments = comments(leaves.filter { it.unit == UNIT_COMMENT })
            val sent = approvalSent + approvedStatus + comments.sent
            val received = approvalReceived + comments.received
            return SheetBadges(
                sent = sent,
                received = received,
                finalized = finalized,
                approvals = approvalSent + approvedStatus + approvalReceived + finalized + comments.status,
                drafts = comments.drafts,
                published = comments.published,
                sentUnits = approvalSent,
                approvedStatus = approvedStatus,
                receivedUnits = approvalReceived,
                commentsByScope = comments.byScope,
            )
        }

        private const val TAB_STATUS = "status"
        private const val TAB_PUBLISHED = "published"
        private const val TAB_DRAFTS = "drafts"

        /** `commentTabOf`: `status`/`approvals` → status, `published` → published, anything else → drafts. */
        fun tabOf(level1: String): String = when (level1.trim().lowercase()) {
            "status", "approvals" -> TAB_STATUS
            TAB_PUBLISHED -> TAB_PUBLISHED
            else -> TAB_DRAFTS
        }

        private class CommentCounts(
            val drafts: Int,
            val status: Int,
            val sent: Int,
            val received: Int,
            val published: Int,
            val byScope: Map<CommentScope, Map<String, Int>>,
        )

        @Suppress("CyclomaticComplexMethod") // One branch per level the badge tree files a comment under.
        private fun comments(leaves: List<BadgeLeaf>): CommentCounts {
            val byScope = CommentScope.entries.associateWith { mutableMapOf<String, Int>() }
            var drafts = 0
            var status = 0
            var sent = 0
            var received = 0
            var published = 0
            leaves.forEach { leaf ->
                val tab = tabOf(leaf.level1)
                val sub = leaf.level2.trim().lowercase()
                when (tab) {
                    TAB_STATUS -> {
                        status += leaf.unread
                        if (sub == "sent") sent += leaf.unread
                        if (sub == "received") received += leaf.unread
                    }
                    TAB_PUBLISHED -> published += leaf.unread
                    else -> drafts += leaf.unread
                }
                val doc = leaf.level3.trim()
                if (doc.isEmpty() || doc == "null" || doc == "undefined") return@forEach
                val scope = when {
                    tab == TAB_DRAFTS -> CommentScope.Drafts
                    tab == TAB_PUBLISHED -> CommentScope.Published
                    sub == "sent" -> CommentScope.Sent
                    sub == "received" -> CommentScope.Received
                    else -> null
                }
                scope?.let { byScope.getValue(it).add(doc, leaf.unread) }
                if (tab == TAB_STATUS) byScope.getValue(CommentScope.Finalized).add(doc, leaf.unread)
            }
            return CommentCounts(drafts, status, sent, received, published, byScope)
        }

        private fun MutableMap<String, Int>.add(key: String, count: Int) {
            this[key] = (this[key] ?: 0) + count
        }
    }
}
