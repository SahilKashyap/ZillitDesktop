package com.zillit.desktop.feature.chat

import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.domain.GroupRoom
import com.zillit.desktop.feature.chat.domain.RecentRow
import com.zillit.desktop.feature.chat.domain.designationLabel
import com.zillit.desktop.feature.chat.domain.hasStanding
import com.zillit.desktop.feature.chat.domain.lastEntryDate
import com.zillit.desktop.feature.chat.domain.lastMessageAt
import com.zillit.desktop.feature.chat.domain.liveChatUnread
import com.zillit.desktop.feature.chat.domain.recentRows
import com.zillit.desktop.feature.chat.domain.searchCrew
import com.zillit.desktop.feature.chat.domain.sortedRecents
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The listing's rules: search that reaches roles, the flat activity order,
 * who has standing to be listed, and the web's row labels.
 */
class CrewDirectoryTest {

    private val crew = listOf(
        CrewContact("u1", "Vidya Pixel", designation = "Gaffer", department = "Lighting"),
        CrewContact("u2", "Aisha Khan", designation = "First AC", department = "Camera"),
        CrewContact("u3", "Sunil k Gautam", designation = "DIT", department = "Camera"),
        CrewContact("u4", "Zara Free", designation = null, department = null),
    )

    @Test
    fun `search reaches name, role and department, case-blind`() {
        assertEquals(listOf("u1"), crew.searchCrew("vidya").map { it.userId })
        assertEquals(listOf("u1"), crew.searchCrew("GAFFER").map { it.userId })
        assertEquals(listOf("u2", "u3"), crew.searchCrew("camera").map { it.userId })
        assertEquals(4, crew.searchCrew("  ").size, "blank matches everyone")
        assertEquals(0, crew.searchCrew("zz").size)
    }

    @Test
    fun `recents sort by known activity, strangers keep server order behind`() {
        val ids = listOf("a", "b", "c", "d")
        val newest = mapOf("b" to 10L, "d" to 20L)

        assertEquals(listOf("d", "b", "a", "c"), sortedRecents(ids, newest))
        assertEquals(ids, sortedRecents(ids, emptyMap()), "no activity, no reorder")
    }

    @Test
    fun `groups interleave with threads by activity, the phones' All order`() {
        val groups = listOf(GroupRoom("g1", "General"), GroupRoom("g2", "Camera Dept"))
        val contacts = listOf(crew[0], crew[1])
        val newest = mapOf("g2" to 30L, "u1" to 20L, "g1" to 10L, "u2" to 40L)

        val order = recentRows(groups, contacts, newest).map { it.id }

        assertEquals(listOf("u2", "g2", "u1", "g1"), order, "a fresh DM outranks a quiet room")
    }

    @Test
    fun `a room or person listed twice is one row, since the list is keyed by id`() {
        val groups = listOf(GroupRoom("g1", "General"), GroupRoom("g1", "General"))
        val contacts = listOf(crew[0], crew[0])

        val ids = recentRows(groups, contacts, emptyMap()).map { it.id }

        assertEquals(ids.distinct(), ids)
        assertEquals(2, ids.size)
    }

    @Test
    fun `rooms without a stamp sink behind stamped threads`() {
        val groups = listOf(GroupRoom("g1", "General"), GroupRoom("g2", "Camera Dept"))
        val contacts = listOf(crew[0])
        val newest = mapOf("u1" to 20L)

        val order = recentRows(groups, contacts, newest).map { it.id }

        assertEquals(listOf("u1", "g1", "g2"), order, "no stamp, given order, at the end")
    }

    @Test
    fun `a silent room has no standing, unless a department's or once spoken`() {
        val newest = mapOf("spoke" to 20L)

        assertFalse(RecentRow.Group(GroupRoom("silent", "General")).hasStanding(newest))
        assertTrue(RecentRow.Group(GroupRoom("silent", "Camera", departmentId = "dep-1")).hasStanding(newest))
        assertTrue(RecentRow.Group(GroupRoom("spoke", "General")).hasStanding(newest))
        assertTrue(
            RecentRow.Group(GroupRoom("silent", "General", sortingActivity = 5L)).hasStanding(newest),
            "the room row's own stamp counts when the backlog has forgotten",
        )
    }

    @Test
    fun `a thread in the server's list always stands — membership is the fact`() {
        assertTrue(RecentRow.Direct(crew[0]).hasStanding(emptyMap()))
    }

    @Test
    fun `unread in a room the user lost counts for nothing`() {
        val groups = listOf(GroupRoom("g-live", "Camera"))
        // Both rooms are in the ledger; only g-live is still in `chat-room`.
        // g-dead's rows are the server's garbage — the phones hide them.
        val ledgerRooms = setOf("g-live", "g-dead")
        val unread = mapOf("g-live" to 3, "u1" to 2, "g-dead" to 40)

        assertEquals(5, liveChatUnread(groups, ledgerRooms, unread))
        assertEquals(2, liveChatUnread(emptyList(), ledgerRooms, unread), "no rooms left: DMs still count")
        assertEquals(45, liveChatUnread(groups, emptySet(), unread), "an unfiled key is never dropped")
    }

    @Test
    fun `the listing's dates read like the web's`() {
        val utc = TimeZone.UTC
        // 2026-08-16 17:44:00 UTC
        val evening = 1_786_902_240_000L

        assertEquals("Aug 16, 2026", lastEntryDate(evening, utc))
        assertEquals("Aug 16, 2026 at 05:44 PM", lastMessageAt(evening, utc))
        // Midnight wears 12, not 0 — and mornings say AM.
        assertEquals("Aug 16, 2026 at 12:04 AM", lastMessageAt(evening - 17L * 3600_000 - 40L * 60_000, utc))
        assertEquals("", lastEntryDate(0L, utc), "the epoch is not a date")
    }

    @Test
    fun `the generic member designation is hidden, a real one shows`() {
        assertEquals("Gaffer", crew[0].designationLabel())
        assertEquals(null, crew[0].copy(designation = "member_label").designationLabel())
        assertEquals(null, crew[0].copy(designation = "  ").designationLabel())
        assertEquals(null, crew[0].copy(designation = null).designationLabel())
    }

    @Test
    fun `a room's own stamp orders it when the live map has forgotten`() {
        val groups = listOf(
            GroupRoom("g-old", "General", sortingActivity = 10L),
            GroupRoom("g-new", "Camera Dept", sortingActivity = 30L),
        )
        val contacts = listOf(crew[0])
        val newest = mapOf("u1" to 20L)

        val order = recentRows(groups, contacts, newest).map { it.id }

        assertEquals(listOf("g-new", "u1", "g-old"), order)
    }
}
