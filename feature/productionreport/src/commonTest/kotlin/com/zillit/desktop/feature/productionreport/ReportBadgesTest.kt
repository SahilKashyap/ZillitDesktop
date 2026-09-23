package com.zillit.desktop.feature.productionreport

import com.zillit.desktop.feature.productionreport.domain.BadgeKind
import com.zillit.desktop.feature.productionreport.domain.BadgeLeaf
import com.zillit.desktop.feature.productionreport.domain.BadgeSurface
import com.zillit.desktop.feature.productionreport.domain.ReportBadges
import com.zillit.desktop.feature.productionreport.domain.UnreadLeaf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Badges v2 — `makeWorkflowBadges().getBadges` bound to the production report. */
class ReportBadgesTest {

    private val drafts = ReportBadges.UNIT_DRAFTS
    private val approval = ReportBadges.UNIT_APPROVAL
    private val published = ReportBadges.UNIT_PUBLISHED

    @Test
    fun `per-tab units count on their surface, report and comment split per document`() {
        val badges = ReportBadges.from(
            listOf(
                BadgeLeaf(drafts, level1 = "null", level2 = "report", level3 = "r1", unread = 2),
                BadgeLeaf(drafts, level1 = "", level2 = "comment", level3 = "r1"),
                BadgeLeaf(approval, level1 = "received", level2 = "report", level3 = "r2"),
                BadgeLeaf(approval, level1 = "sent", level2 = "comment", level3 = "r3", unread = 3),
                BadgeLeaf(approval, level1 = "finalized", level2 = "report", level3 = "r4"),
                BadgeLeaf(published, level2 = "comment", level3 = "r5"),
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
        assertEquals(0, badges.count(BadgeSurface.Received, BadgeKind.Comment, "r3"))
        assertEquals(0, badges.count(null, BadgeKind.Report, "r1"))
        assertEquals(listOf(UnreadLeaf(BadgeKind.Comment, "r5", 1)), badges.leaves(BadgeSurface.Published))
    }

    @Test
    fun `finalized comments are the received and sent comments summed per document`() {
        val badges = ReportBadges.from(
            listOf(
                BadgeLeaf(approval, level1 = "received", level2 = "comment", level3 = "r1", unread = 2),
                BadgeLeaf(approval, level1 = "sent", level2 = "comment", level3 = "r1"),
                BadgeLeaf(approval, level1 = "sent", level2 = "comment", level3 = "r2"),
            ),
        )
        assertEquals(3, badges.count(BadgeSurface.Finalized, BadgeKind.Comment, "r1"))
        assertEquals(1, badges.count(BadgeSurface.Finalized, BadgeKind.Comment, "r2"))
        assertEquals(0, badges.finalized, "the chip counts the finalized unit alone")
    }

    @Test
    fun `legacy units, unknown sub-tabs and unknown kinds never count`() {
        val badges = ReportBadges.from(
            ReportBadges.LEGACY_UNITS.map { BadgeLeaf(it, level2 = "report", level3 = "r1", unread = 5) } +
                listOf(
                    BadgeLeaf(approval, level1 = "status", level2 = "comment", level3 = "r1", unread = 5),
                    BadgeLeaf(approval, level1 = "received", level2 = "reminder", level3 = "r1", unread = 5),
                    BadgeLeaf("6aacf32011484698ddd2d8aa", level2 = "report", level3 = "r1", unread = 5),
                ),
        )
        assertTrue(BadgeSurface.entries.all { badges.surfaceCount(it) == 0 }, "$badges")
        assertTrue(BadgeSurface.entries.all { badges.leaves(it).isEmpty() }, "$badges")
    }

    @Test
    fun `a row filed without a document counts on its tab but on no row`() {
        val badges = ReportBadges.from(
            listOf(
                BadgeLeaf(drafts, level2 = "report", level3 = "undefined", unread = 3),
                BadgeLeaf(drafts, level2 = "report", level3 = "", unread = 1),
            ),
        )
        assertEquals(4, badges.drafts)
        assertTrue(badges.leaves(BadgeSurface.Drafts).isEmpty())
    }
}
