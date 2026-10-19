package com.zillit.desktop.feature.chat

import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.ui.ThreadAnchor
import com.zillit.desktop.feature.chat.ui.followsNewest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The thread list's "should I come down to the newest line" decision.
 *
 * Worth a headless test of its own because the three rules it balances pull
 * against each other, and getting the balance wrong is invisible in review:
 * too eager and a reader scrolling back through a shoot day gets yanked to
 * the bottom by someone else's chatter, too shy and your own reply lands
 * below the fold — which is the bug this file was written for.
 */
class ThreadFollowTest {

    private fun line(uniqueId: String, mine: Boolean = false) = ChatMessage(
        id = "srv-$uniqueId",
        uniqueId = uniqueId,
        senderId = if (mine) "me" else "u1",
        receiverId = if (mine) "u1" else "me",
        body = "hello $uniqueId",
        timestampMillis = 1_786_507_000_000L,
        isMine = mine,
    )

    private val anchoredOnTheirLine = ThreadAnchor(threadId = "u1", newestId = "w-9")

    // -- opening and switching -----------------------------------------------

    @Test
    fun `a thread with nothing in it has nowhere to come down to`() {
        assertFalse(followsNewest(anchor = null, threadId = "u1", newest = null, atBottom = false))
    }

    @Test
    fun `a thread opening for the first time lands on its newest line`() {
        // The list has never been anchored, so there is no reader mid-scroll
        // to protect: every messenger opens on the latest message.
        assertTrue(followsNewest(anchor = null, threadId = "u1", newest = line("w-9"), atBottom = false))
    }

    @Test
    fun `switching to another conversation lands on that one's newest line`() {
        // Where the reader was standing in the thread they just left says
        // nothing about the thread they just asked for.
        assertTrue(
            followsNewest(
                anchor = anchoredOnTheirLine,
                threadId = "u2",
                newest = line("w-40"),
                atBottom = false,
            ),
        )
    }

    // -- our own messages ----------------------------------------------------

    @Test
    fun `our own message comes into view even from far back in the history`() {
        // Pressing Enter is the reader saying they are done reading back, in
        // the clearest way the composer offers.
        assertTrue(
            followsNewest(
                anchor = anchoredOnTheirLine,
                threadId = "u1",
                newest = line("w-10", mine = true),
                atBottom = false,
            ),
        )
    }

    @Test
    fun `each of a burst of our own messages comes into view in turn`() {
        // Three sent before any of them is acked: each one is a newest line
        // the list has not been brought down to yet, so each one follows.
        var anchor: ThreadAnchor? = anchoredOnTheirLine
        listOf("w-10", "w-11", "w-12").forEach { id ->
            assertTrue(
                followsNewest(anchor, threadId = "u1", newest = line(id, mine = true), atBottom = false),
                "sending $id should have come down to it",
            )
            anchor = ThreadAnchor(threadId = "u1", newestId = id)
        }
    }

    // -- other people's messages ---------------------------------------------

    @Test
    fun `someone else's message comes into view for a reader at the bottom`() {
        assertTrue(
            followsNewest(
                anchor = anchoredOnTheirLine,
                threadId = "u1",
                newest = line("w-10"),
                atBottom = true,
            ),
        )
    }

    @Test
    fun `someone else's message leaves a reader scrolled back where they are`() {
        assertFalse(
            followsNewest(
                anchor = anchoredOnTheirLine,
                threadId = "u1",
                newest = line("w-10"),
                atBottom = false,
            ),
        )
    }

    // -- changes that are not arrivals ---------------------------------------

    @Test
    fun `a line already come down to does not move the reader a second time`() {
        // This one case carries every change that is not an arrival: an edit,
        // a read receipt, the server's echo replacing an optimistic bubble,
        // and above all a page of older history, which grows the list at the
        // far end. Each leaves the newest line's id alone, and none of them
        // is reason to move a reader — not even when that newest line is our
        // own, which is why the rule is keyed on identity rather than on "is
        // the last message mine".
        assertFalse(
            followsNewest(
                anchor = ThreadAnchor(threadId = "u1", newestId = "w-10"),
                threadId = "u1",
                newest = line("w-10", mine = true),
                atBottom = false,
            ),
        )
    }
}
