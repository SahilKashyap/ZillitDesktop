package com.zillit.desktop.feature.callsheet

import com.zillit.desktop.feature.callsheet.domain.BadgeKind
import com.zillit.desktop.feature.callsheet.domain.BadgeLeaf
import com.zillit.desktop.feature.callsheet.domain.BadgeSurface
import com.zillit.desktop.feature.callsheet.domain.SheetBadges
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Badges v2 — `makeWorkflowBadges` bound to `call_sheet_label`: which chip
 * every unread row lights, and which reads clear it.
 */
class SheetBadgesTest {

    private val approval = SheetBadges.UNIT_APPROVAL

    @Test
    fun `per-tab units count on their surface, and per sheet on their row`() {
        val badges = SheetBadges.from(
            listOf(
                BadgeLeaf(SheetBadges.UNIT_DRAFTS, level2 = "report", level3 = "r1", unread = 2),
                BadgeLeaf(SheetBadges.UNIT_DRAFTS, level2 = "comment", level3 = "r1"),
                BadgeLeaf(approval, level1 = "received", level2 = "report", level3 = "r2"),
                BadgeLeaf(approval, level1 = "sent", level2 = "comment", level3 = "r3", unread = 3),
                BadgeLeaf(approval, level1 = "finalized", level2 = "report", level3 = "r4"),
                BadgeLeaf(SheetBadges.UNIT_PUBLISHED, level2 = "report", level3 = "r5"),
            ),
        )
        assertEquals(3, badges.drafts)
        assertEquals(1, badges.received)
        assertEquals(3, badges.sent)
        assertEquals(1, badges.finalized)
        assertEquals(1, badges.published)
        assertEquals(2, badges.count(BadgeSurface.Drafts, BadgeKind.Report, "r1"))
        assertEquals(1, badges.count(BadgeSurface.Drafts, BadgeKind.Comment, "r1"))
        assertEquals(3, badges.count(BadgeSurface.Sent, BadgeKind.Comment, "r3"))
        assertEquals(0, badges.count(BadgeSurface.Received, BadgeKind.Comment, "r3"), "a row counts on its own surface")
        assertEquals(0, badges.count(null, BadgeKind.Report, "r1"))
    }

    @Test
    fun `the Approvals chip sums only the sub-tabs this user can see`() {
        val badges = SheetBadges.from(
            listOf(
                BadgeLeaf(approval, level1 = "received", level2 = "report", level3 = "r1", unread = 1),
                BadgeLeaf(approval, level1 = "sent", level2 = "report", level3 = "r2", unread = 2),
                BadgeLeaf(approval, level1 = "finalized", level2 = "report", level3 = "r3", unread = 4),
            ),
        )
        assertEquals(6, badges.approvals(isPoster = true, isFinalApprover = false), "Sent + Finalized")
        assertEquals(7, badges.approvals(isPoster = true, isFinalApprover = true), "plus Received")
        assertEquals(5, badges.approvals(isPoster = false, isFinalApprover = false), "Received + Finalized")
    }

    @Test
    fun `legacy units, a foreign level_1 and an unknown kind are dropped where the tree is built`() {
        val badges = SheetBadges.from(
            listOf(
                BadgeLeaf("call_sheet_comment_label", level1 = "drafts", level3 = "r1", unread = 5),
                BadgeLeaf("call_sheet_approved_label", level1 = "status", unread = 5),
                BadgeLeaf(approval, level1 = "status", level2 = "report", level3 = "r1", unread = 5),
                BadgeLeaf(approval, level1 = "sent", level2 = "signature", level3 = "r1", unread = 5),
                BadgeLeaf(approval, level1 = "sent", level2 = "report", level3 = "r1"),
            ),
        )
        assertEquals(SheetBadges(sent = 1, rows = badges.rows), badges)
        assertEquals(1, badges.count(BadgeSurface.Sent, BadgeKind.Report, "r1"))
    }

    @Test
    fun `a row without a sheet id counts on its surface but on no row`() {
        val badges = SheetBadges.from(
            listOf(
                BadgeLeaf(SheetBadges.UNIT_DRAFTS, level2 = "report", level3 = "undefined", unread = 3),
                BadgeLeaf(SheetBadges.UNIT_DRAFTS, level2 = "report", level3 = "null"),
                BadgeLeaf(SheetBadges.UNIT_DRAFTS, level2 = "comment"),
            ),
        )
        assertEquals(5, badges.drafts)
        assertEquals(emptyList(), badges.leavesOf(BadgeSurface.Drafts))
    }

    @Test
    fun `finalized comments are the received and sent comments summed per sheet, and read on both`() {
        val badges = SheetBadges.from(
            listOf(
                BadgeLeaf(approval, level1 = "received", level2 = "comment", level3 = "r1", unread = 2),
                BadgeLeaf(approval, level1 = "sent", level2 = "comment", level3 = "r1", unread = 1),
            ),
        )
        assertEquals(3, badges.count(BadgeSurface.Finalized, BadgeKind.Comment, "r1"))
        assertEquals(0, badges.finalized, "the chip counts the finalized level_1 alone")
        assertEquals(
            listOf(approval to "received", approval to "sent"),
            SheetBadges.readScopes(BadgeSurface.Finalized, BadgeKind.Comment),
        )
        assertEquals(
            listOf(SheetBadges.UNIT_DRAFTS to null),
            SheetBadges.readScopes(BadgeSurface.Drafts, BadgeKind.Report),
        )
        assertEquals(listOf(approval to "sent"), SheetBadges.readScopes(BadgeSurface.Sent, BadgeKind.Report))
        assertEquals(
            listOf(SheetBadges.UNIT_PUBLISHED to null),
            SheetBadges.readScopes(BadgeSurface.Published, BadgeKind.Comment),
        )
    }

    @Test
    fun `the unread leaves of a surface are what a read-on-entry walks`() {
        val badges = SheetBadges.from(
            listOf(
                BadgeLeaf(SheetBadges.UNIT_PUBLISHED, level2 = "report", level3 = "p1", unread = 2),
                BadgeLeaf(SheetBadges.UNIT_PUBLISHED, level2 = "comment", level3 = "p2"),
            ),
        )
        assertEquals(
            setOf(Triple(BadgeKind.Report, "p1", 2), Triple(BadgeKind.Comment, "p2", 1)),
            badges.leavesOf(BadgeSurface.Published).toSet(),
        )
    }
}
