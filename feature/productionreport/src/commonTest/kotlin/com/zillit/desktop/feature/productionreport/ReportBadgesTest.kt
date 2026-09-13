package com.zillit.desktop.feature.productionreport

import com.zillit.desktop.feature.productionreport.domain.BadgeLeaf
import com.zillit.desktop.feature.productionreport.domain.CommentScope
import com.zillit.desktop.feature.productionreport.domain.ReportBadges
import kotlin.test.Test
import kotlin.test.assertEquals

/** `getProductionReportBadges` + `getCommentBadges`: which chip every unread row lights. */
class ReportBadgesTest {

    private val comment = ReportBadges.UNIT_COMMENT

    @Test
    fun `approval units split into received, sent and finalized`() {
        val badges = ReportBadges.from(
            listOf(
                BadgeLeaf(ReportBadges.UNIT_APPROVAL, unread = 2),
                BadgeLeaf(ReportBadges.UNIT_REMINDER),
                BadgeLeaf(ReportBadges.UNIT_REJECTION),
                BadgeLeaf(ReportBadges.UNIT_APPROVED, unread = 4),
            ),
        )
        assertEquals(3, badges.received)
        assertEquals(1, badges.sent)
        assertEquals(4, badges.finalized)
        assertEquals(0, badges.unmapped)
    }

    @Test
    fun `comments file under their tab and sub-tab, per report`() {
        val badges = ReportBadges.from(
            listOf(
                BadgeLeaf(comment, level1 = "drafts", level3 = "r1"),
                BadgeLeaf(comment, level1 = "status", level2 = "sent", level3 = "r2", unread = 2),
                BadgeLeaf(comment, level1 = "approvals", level2 = "received", level3 = "r3"),
                BadgeLeaf(comment, level1 = "published", level3 = "r4"),
                BadgeLeaf(comment, level1 = "status", level3 = "r5"),
            ),
        )
        assertEquals(1, badges.drafts)
        assertEquals(1, badges.published)
        assertEquals(4, badges.commentStatus)
        assertEquals(2, badges.commentSent)
        assertEquals(1, badges.commentReceived)
        assertEquals(2, badges.sent, "sent comments light the Sent chip")
        assertEquals(1, badges.received)
        assertEquals(1, badges.commentsFor(CommentScope.Drafts, "r1"))
        assertEquals(2, badges.commentsFor(CommentScope.Sent, "r2"))
        assertEquals(1, badges.commentsFor(CommentScope.Received, "r3"))
        assertEquals(1, badges.commentsFor(CommentScope.Published, "r4"))
        assertEquals(1, badges.commentsFor(CommentScope.Finalized, "r5"), "every status comment reaches Finalized")
        assertEquals(
            0,
            badges.commentsFor(CommentScope.Sent, "r5"),
            "a status comment with no sub-tab is on no sub-tab",
        )
        assertEquals(0, badges.commentsFor(null, "r1"))
    }

    @Test
    fun `a comment filed without a report counts on its tab but on no row`() {
        val badges = ReportBadges.from(listOf(BadgeLeaf(comment, level1 = "drafts", level3 = "undefined", unread = 3)))
        assertEquals(3, badges.drafts)
        assertEquals(emptyMap(), badges.commentUnreadByReport)
    }

    @Test
    fun `an unknown unit is unmapped so the Approvals chip can drain it`() {
        val badges = ReportBadges.from(listOf(BadgeLeaf("production_report_new_thing_label", unread = 2)))
        assertEquals(2, badges.unmapped)
        assertEquals(listOf("production_report_new_thing_label"), badges.unmappedUnits)
    }
}
