package com.zillit.desktop.feature.chat

import com.zillit.desktop.feature.chat.domain.ChatFilter
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.domain.GroupRoom
import com.zillit.desktop.feature.chat.domain.RecentRow
import com.zillit.desktop.feature.chat.domain.admits
import com.zillit.desktop.feature.chat.domain.displayName
import com.zillit.desktop.feature.chat.domain.searchRecents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The chips' per-tab predicates, the Groups chip above all (QA#3): a room
 * the server returned belongs under Groups whether or not anyone has spoken
 * in it — Android's Groups tab (`GroupsVM.searchList`, `GroupsVM.kt:168-184`)
 * lists every enabled room and never tests activity. The old predicate
 * required a stamp or a department, which hid every freshly created room
 * from the one chip named after them.
 */
class GroupsChipTest {

    private val aisha = CrewContact("u1", "Aisha Khan")
    private val silentRoom = RecentRow.Group(GroupRoom("g-silent", "Night Shoot"))
    private val spokenRoom = RecentRow.Group(GroupRoom("g-spoken", "Camera", sortingActivity = 5L))
    private val direct = RecentRow.Direct(aisha)

    @Test
    fun `a room with no messages appears under Groups`() {
        assertTrue(ChatFilter.Groups.admits(silentRoom, emptyMap(), emptyMap(), emptySet()))
    }

    @Test
    fun `every room the server returned passes the Groups chip`() {
        val rows = listOf(silentRoom, spokenRoom, direct)
        val admitted = rows.filter {
            ChatFilter.Groups.admits(it, emptyMap(), emptyMap(), emptySet())
        }
        assertEquals(listOf<RecentRow>(silentRoom, spokenRoom), admitted)
    }

    @Test
    fun `All still hides a silent, department-less room — Android's All tab does`() {
        assertFalse(ChatFilter.All.admits(silentRoom, emptyMap(), emptyMap(), emptySet()))
        assertTrue(ChatFilter.All.admits(spokenRoom, emptyMap(), emptyMap(), emptySet()))
        assertTrue(ChatFilter.All.admits(direct, emptyMap(), emptyMap(), emptySet()))
    }

    @Test
    fun `Members keeps people and never rooms`() {
        assertTrue(ChatFilter.Members.admits(direct, emptyMap(), emptyMap(), emptySet()))
        assertFalse(ChatFilter.Members.admits(spokenRoom, emptyMap(), emptyMap(), emptySet()))
    }

    @Test
    fun `Unread and Favourites are their own whole rule`() {
        assertTrue(
            ChatFilter.Unread.admits(silentRoom, emptyMap(), mapOf("g-silent" to 2), emptySet()),
        )
        assertFalse(ChatFilter.Unread.admits(silentRoom, emptyMap(), emptyMap(), emptySet()))
        assertTrue(
            ChatFilter.Favourites.admits(direct, emptyMap(), emptyMap(), setOf("u1")),
        )
    }

    @Test
    fun `the Chats search narrows by display name, case-blind`() {
        val rows = listOf<RecentRow>(silentRoom, spokenRoom, direct)

        assertEquals(listOf("Night Shoot"), rows.searchRecents("night").map { it.displayName() })
        assertEquals(listOf("Aisha Khan"), rows.searchRecents("AISHA").map { it.displayName() })
        assertEquals(rows, rows.searchRecents("  "), "blank matches everyone")
        assertTrue(rows.searchRecents("zz").isEmpty())
    }
}
