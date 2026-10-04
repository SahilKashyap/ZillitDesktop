package com.zillit.desktop.feature.chat

import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.domain.RecentRow
import com.zillit.desktop.feature.chat.domain.chatPeople
import com.zillit.desktop.feature.chat.domain.recentRows
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Who has a row under Chats. Users reported whole conversations missing:
 * the list knew a DM only from the socket's `user:list`, this computer's
 * threads and the backlog, and dropped anyone with a private name or a
 * pending join before it ever looked. Android lists anyone whose users-list
 * `sorting_activity` says a message passed, and filters neither.
 */
class ChatPeopleTest {

    private val aisha = CrewContact("u1", "Aisha Khan")
    private val vivek = CrewContact("u2", "Vivek Mishra", sortingActivity = 5_000L)
    private val priya = CrewContact("u3", "Priya Sharma", inDirectory = false, sortingActivity = 9_000L)
    private val me = CrewContact("me", "Sahil Kashyap", sortingActivity = 1_000L)

    @Test
    fun `a conversation the server's list missed is proven by sorting_activity`() {
        val people = chatPeople(listed = listOf("u1"), crew = listOf(aisha, vivek), selfId = "me")
        assertEquals(listOf("u1", "u2"), people.map { it.userId })
    }

    @Test
    fun `someone outside the directory still has their conversation listed`() {
        val people = chatPeople(listed = emptyList(), crew = listOf(priya), selfId = "me")
        assertEquals(listOf("u3"), people.map { it.userId })
    }

    @Test
    fun `nobody is listed twice, the signed-in user never, and unknown ids are skipped`() {
        val people = chatPeople(listed = listOf("u2", "ghost", "me"), crew = listOf(vivek, me), selfId = "me")
        assertEquals(listOf("u2"), people.map { it.userId })
    }

    @Test
    fun `sorting_activity orders a DM the live map does not know`() {
        val rows = recentRows(
            groups = emptyList(),
            contacts = listOf(aisha, vivek, priya),
            newest = mapOf("u1" to 7_000L),
        )
        assertEquals(listOf("u3", "u1", "u2"), rows.map { (it as RecentRow.Direct).contact.userId })
    }
}
