package com.zillit.desktop.feature.documentdistribution

import com.zillit.desktop.feature.documentdistribution.domain.DocDistBadgeLeaf
import com.zillit.desktop.feature.documentdistribution.domain.DocDistBadges
import com.zillit.desktop.feature.documentdistribution.domain.DocDistUnread
import com.zillit.desktop.feature.documentdistribution.ui.DocDistDestination
import kotlin.test.Test
import kotlin.test.assertEquals

class DocDistUnreadTest {

    private val unread = DocDistUnread(
        listOf(
            // A file two folders deep counts on both folders above it.
            DocDistBadgeLeaf(DocDistBadges.UNIT_DOCUMENT, "file-1", setOf("f-a", "f-b"), 1),
            // A folder's own event counts on itself and its parent.
            DocDistBadgeLeaf(DocDistBadges.UNIT_FOLDER, "f-b", setOf("f-a", "f-b"), 1),
            DocDistBadgeLeaf(DocDistBadges.UNIT_TEMPLATE, "t-1", emptySet(), 2),
            DocDistBadgeLeaf(DocDistBadges.UNIT_DISTRIBUTION, "d-1", emptySet(), 1),
        ),
    )

    @Test
    fun `a folder's bubble is every row filed under it`() {
        assertEquals(2, unread.folder("f-a"))
        assertEquals(2, unread.folder("f-b"))
        assertEquals(0, unread.folder("f-c"))
    }

    @Test
    fun `a file's dot is its own events only`() {
        assertEquals(1, unread.file("file-1"))
        assertEquals(0, unread.file("f-b"))
    }

    @Test
    fun `the side sections count their units`() {
        assertEquals(2, unread.unit(*DocDistDestination.Templates.badgeUnits.toTypedArray()))
        assertEquals(1, unread.unit(*DocDistDestination.Lists.badgeUnits.toTypedArray()))
        assertEquals(0, unread.unit(*DocDistDestination.Library.badgeUnits.toTypedArray()))
    }
}
