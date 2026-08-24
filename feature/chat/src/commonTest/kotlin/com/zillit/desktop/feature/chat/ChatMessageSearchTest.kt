package com.zillit.desktop.feature.chat

import com.zillit.desktop.feature.chat.data.MessageHit
import com.zillit.desktop.feature.chat.data.searchCachedThreads
import com.zillit.desktop.feature.chat.data.snippetAround
import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.domain.GroupRoom
import com.zillit.desktop.feature.chat.ui.messageHitRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Chats search's reach into cached bodies (QA#12): the sweep itself
 * (case folding, snippet windowing, ordering) and the mapping that resolves
 * each hit to a row the listing can open.
 */
class ChatMessageSearchTest {

    private fun message(
        id: String,
        body: String,
        at: Long,
        isGroup: Boolean = false,
    ) = ChatMessage(
        id = id,
        uniqueId = id,
        senderId = "u-peer",
        receiverId = "me",
        body = body,
        timestampMillis = at,
        isMine = false,
        isGroup = isGroup,
    )

    @Test
    fun `matching is case-blind on both sides`() {
        val threads = mapOf(
            "u1" to listOf(message("m1", "Rolling at 8 sharp", at = 10L)),
        )

        assertEquals(1, searchCachedThreads(threads, "ROLLING").size)
        assertEquals(1, searchCachedThreads(threads, "rolling").size)
        assertTrue(searchCachedThreads(threads, "wrapped").isEmpty())
    }

    @Test
    fun `hits carry their conversation, group flag and stamp, newest first`() {
        val threads = mapOf(
            "u1" to listOf(message("m1", "call sheet is out", at = 10L)),
            "g1" to listOf(message("m2", "new call sheet posted", at = 20L, isGroup = true)),
        )

        val hits = searchCachedThreads(threads, "call sheet")

        assertEquals(listOf("g1", "u1"), hits.map(MessageHit::peerId), "newest first")
        assertEquals(listOf(true, false), hits.map(MessageHit::isGroup))
        assertEquals(20L, hits.first().timestampMillis)
    }

    @Test
    fun `attachment-only lines with empty bodies never match`() {
        val threads = mapOf("u1" to listOf(message("m1", "", at = 10L)))

        assertTrue(searchCachedThreads(threads, "anything").isEmpty())
    }

    @Test
    fun `a short body is its own snippet, unellipsised`() {
        assertEquals("Rolling at 8", snippetAround("Rolling at 8", at = 0, matchLength = 7))
    }

    @Test
    fun `a long body windows around the match with ellipses both sides`() {
        val body = "a".repeat(60) + "NEEDLE" + "b".repeat(60)

        val snippet = snippetAround(body, at = 60, matchLength = 6)

        assertEquals("…" + "a".repeat(24) + "NEEDLE" + "b".repeat(24) + "…", snippet)
    }

    @Test
    fun `a match at the very start ellipsises only the tail`() {
        val body = "NEEDLE then a long long tail that runs past the margin easily"

        val snippet = snippetAround(body, at = 0, matchLength = 6)

        assertTrue(snippet.startsWith("NEEDLE"))
        assertTrue(snippet.endsWith("…"))
    }

    @Test
    fun `the row mapping resolves rooms first, then people, and drops strays`() {
        val groups = listOf(GroupRoom("g1", "Camera Dept"))
        val crew = listOf(CrewContact("u1", "Aisha Khan"))
        val hits = listOf(
            // isGroup false on purpose: a disk-restored thread lost the flag,
            // and the room list is the authority.
            MessageHit("g1", isGroup = false, snippet = "s1", timestampMillis = 3L),
            MessageHit("u1", isGroup = false, snippet = "s2", timestampMillis = 2L),
            MessageHit("gone", isGroup = false, snippet = "s3", timestampMillis = 1L),
        )

        val rows = messageHitRows(hits, groups, crew)

        assertEquals(2, rows.size, "the unresolvable hit is dropped")
        assertEquals("Camera Dept", rows[0].title)
        assertEquals("g1", rows[0].room?.id)
        assertEquals("Aisha Khan", rows[1].title)
        assertEquals("u1", rows[1].contact?.userId)
    }
}
