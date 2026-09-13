package com.zillit.desktop.feature.callsheet

import com.zillit.desktop.feature.callsheet.domain.BadgeLeaf
import com.zillit.desktop.feature.callsheet.domain.CommentScope
import com.zillit.desktop.feature.callsheet.domain.SheetBadges
import kotlin.test.Test
import kotlin.test.assertEquals

/** `getCallSheetBadges` + `getCallSheetCommentBadges`: which chip every unread row lights. */
class SheetBadgesTest {

    private val comment = SheetBadges.UNIT_COMMENT

    @Test
    fun `approval units split into received, sent and finalized`() {
        val badges = SheetBadges.from(
            listOf(
                BadgeLeaf(SheetBadges.UNIT_APPROVAL, unread = 2),
                BadgeLeaf(SheetBadges.UNIT_REMINDER),
                BadgeLeaf(SheetBadges.UNIT_REJECTION),
                BadgeLeaf(SheetBadges.UNIT_APPROVED, unread = 4),
            ),
        )
        assertEquals(3, badges.received)
        assertEquals(1, badges.sent)
        assertEquals(4, badges.finalized)
        assertEquals(8, badges.approvals)
        assertEquals(3, badges.receivedUnits)
        assertEquals(1, badges.sentUnits)
    }

    @Test
    fun `an approved row stamped status is the sender's news and badges Sent, not Finalized`() {
        val badges = SheetBadges.from(
            listOf(
                BadgeLeaf(SheetBadges.UNIT_APPROVED, level1 = "status", unread = 2),
                BadgeLeaf(SheetBadges.UNIT_APPROVED, level1 = "finalized", unread = 3),
                BadgeLeaf(SheetBadges.UNIT_APPROVED, unread = 1),
            ),
        )
        assertEquals(2, badges.sent)
        assertEquals(2, badges.approvedStatus)
        assertEquals(4, badges.finalized, "rows without a level, or with any other, stay on Finalized")
        assertEquals(6, badges.approvals)
    }

    @Test
    fun `comments file under their tab and sub-tab, per sheet`() {
        val badges = SheetBadges.from(
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
        assertEquals(2, badges.sent, "sent comments light the Sent chip")
        assertEquals(1, badges.received)
        assertEquals(4, badges.approvals, "every status comment reaches the Approvals chip")
        assertEquals(1, badges.commentsFor(CommentScope.Drafts, "r1"))
        assertEquals(2, badges.commentsFor(CommentScope.Sent, "r2"))
        assertEquals(1, badges.commentsFor(CommentScope.Received, "r3"))
        assertEquals(1, badges.commentsFor(CommentScope.Published, "r4"))
        assertEquals(1, badges.commentsFor(CommentScope.Finalized, "r5"), "every status comment reaches Finalized")
        assertEquals(0, badges.commentsFor(CommentScope.Sent, "r5"), "a status comment without a sub-tab is on none")
        assertEquals(0, badges.commentsFor(null, "r1"))
    }

    @Test
    fun `a comment filed without a sheet counts on its tab but on no row`() {
        val badges = SheetBadges.from(listOf(BadgeLeaf(comment, level1 = "drafts", level3 = "undefined", unread = 3)))
        assertEquals(3, badges.drafts)
        assertEquals(0, badges.commentsFor(CommentScope.Drafts, "undefined"))
    }

    @Test
    fun `a missing or unknown level_1 is Drafts, as the spec says`() {
        assertEquals("drafts", SheetBadges.tabOf(""))
        assertEquals("drafts", SheetBadges.tabOf("null"))
        assertEquals("status", SheetBadges.tabOf("Approvals"))
        assertEquals("published", SheetBadges.tabOf("published"))
    }
}
