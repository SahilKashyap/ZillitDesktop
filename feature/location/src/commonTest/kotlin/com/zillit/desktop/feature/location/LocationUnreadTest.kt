package com.zillit.desktop.feature.location

import com.zillit.desktop.feature.location.domain.GroupBy
import com.zillit.desktop.feature.location.domain.LocationBadgeLeaf
import com.zillit.desktop.feature.location.domain.LocationBadges
import com.zillit.desktop.feature.location.domain.LocationFolder
import com.zillit.desktop.feature.location.domain.LocationPick
import com.zillit.desktop.feature.location.domain.LocationStatus
import com.zillit.desktop.feature.location.domain.LocationUnread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The tool's badges are one set of leaves counted at every grain — a tab
 * is its folders added up, a folder its galleries, a gallery its records —
 * the way the web sums the combined-level rows.
 */
class LocationUnreadTest {

    private fun leaf(
        status: LocationStatus = LocationStatus.Selected,
        location: String = "Lucknow",
        scene: String = "12",
        episode: String = "",
        recordId: String = "",
        unread: Int = 1,
    ) = LocationBadgeLeaf(status, location, scene, episode, recordId, unread)

    private fun folder(key: String) = LocationFolder(key, emptyList(), emptyList(), emptyList(), emptyList(), 0L)

    private val unread = LocationUnread(
        listOf(
            leaf(),
            leaf(scene = "14", unread = 2),
            leaf(location = "Delhi", scene = "3"),
            leaf(recordId = "rec-1"),
            leaf(status = LocationStatus.Published, location = "Delhi", scene = "3", episode = "1"),
        ),
    )

    @Test
    fun `a status tab sums every leaf of its list`() {
        assertEquals(5, unread.status(LocationStatus.Selected))
        assertEquals(1, unread.status(LocationStatus.Published))
        assertEquals(0, unread.status(LocationStatus.Shortlisted))
    }

    @Test
    fun `a folder counts by the grouping key, a gallery by the whole pick`() {
        assertEquals(4, unread.folder(LocationStatus.Selected, GroupBy.LocationName, folder("Lucknow")))
        assertEquals(2, unread.folder(LocationStatus.Selected, GroupBy.SceneNo, folder("14")))
        assertEquals(2, unread.pick(LocationStatus.Selected, LocationPick("Lucknow", "12")))
        assertEquals(0, unread.pick(LocationStatus.Selected, LocationPick("Lucknow", "12", episode = "2")))
        assertEquals(1, unread.pick(LocationStatus.Published, LocationPick("Delhi", "3", episode = "1")))
    }

    @Test
    fun `a record counts only the comments made on it`() {
        assertEquals(1, unread.record("rec-1"))
        assertEquals(0, unread.record("rec-2"))
        assertEquals(0, unread.record(""))
    }

    @Test
    fun `the status lists map to the service's units both ways`() {
        LocationStatus.entries.forEach { assertEquals(it, LocationBadges.statusOf(LocationBadges.unitOf(it))) }
        assertNull(LocationBadges.statusOf("something_else"))
    }
}

class LocationStrayScenesTest {
    @Test
    fun `rows under a scene no gallery lists are the place's strays`() {
        val unread = LocationUnread(
            listOf(
                LocationBadgeLeaf(LocationStatus.Selected, "Cbcbc", "", "", "", 2),
                LocationBadgeLeaf(LocationStatus.Selected, "Cbcbc", "99ee", "", "", 1),
                LocationBadgeLeaf(LocationStatus.Published, "Cbcbc", "7", "", "", 1),
            ),
        )
        assertEquals(setOf(""), unread.strayScenes(LocationStatus.Selected, "Cbcbc", listOf("99ee")))
        assertEquals(emptySet(), unread.strayScenes(LocationStatus.Selected, "Cbcbc", listOf("99ee", "")))
        assertEquals(emptySet(), unread.strayScenes(LocationStatus.Selected, "Noida", listOf()))
    }
}

class LocationOrphansTest {
    @Test
    fun `rows under a place no folder shows are the list's orphans`() {
        val unread = LocationUnread(
            listOf(
                LocationBadgeLeaf(LocationStatus.Selected, "Cbcbc", "99ee", "", "", 1),
                LocationBadgeLeaf(LocationStatus.Selected, "Gone", "1", "", "", 2),
                LocationBadgeLeaf(LocationStatus.Published, "Gone", "1", "", "", 1),
            ),
        )
        val orphans = unread.orphans(LocationStatus.Selected, listOf("Cbcbc", "Noida"))
        assertEquals(listOf("Gone"), orphans.map { it.location })
        assertEquals(emptyList(), unread.orphans(LocationStatus.Selected, listOf("Cbcbc", "Gone")))
    }
}
