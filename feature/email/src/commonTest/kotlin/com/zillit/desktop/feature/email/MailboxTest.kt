package com.zillit.desktop.feature.email

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.email.data.InMemoryMailboxCache
import com.zillit.desktop.feature.email.data.Mailbox
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Paging a folder, which here is the same mechanism as caching it.
 *
 * Driven against a fake server holding a known set of uids, so the assertions
 * are about *which* mail the client asked for — the thing a live test could
 * never pin down.
 */
class MailboxTest {

    private fun mailbox(uids: List<Int>): Pair<Mailbox, FakeMailServer> {
        val server = FakeMailServer(uids = uids)
        return Mailbox(server, InMemoryMailboxCache()) to server
    }

    @Test
    fun `the first batch is the newest mail`() = runTest {
        val (mailbox, server) = mailbox((1..120).toList())

        val page = (mailbox.syncNext("INBOX") as ZillitResult.Success).data

        assertEquals(50, page.messages.size)
        assertEquals(120, server.indexed.single().first(), "should start at the newest uid")
        assertTrue(page.hasMore)
    }

    @Test
    fun `scrolling asks only for what is missing`() = runTest {
        val (mailbox, server) = mailbox((1..120).toList())

        mailbox.syncNext("INBOX")
        mailbox.syncNext("INBOX")

        assertEquals(2, server.indexed.size)
        assertTrue(
            server.indexed[0].intersect(server.indexed[1].toSet()).isEmpty(),
            "a batch was fetched twice",
        )
    }

    @Test
    fun `a folder syncs to completion and then stops`() = runTest {
        val (mailbox, server) = mailbox((1..120).toList())

        repeat(3) { mailbox.syncNext("INBOX") }
        val last = (mailbox.syncNext("INBOX") as ZillitResult.Success).data

        assertEquals(120, last.messages.size, "every message should be cached")
        assertFalse(last.hasMore)
        assertEquals(3, server.indexed.size, "a complete folder must not keep fetching")
    }

    @Test
    fun `a small folder completes in one batch`() = runTest {
        val (mailbox, _) = mailbox(listOf(1, 2, 3))

        val page = (mailbox.syncNext("INBOX") as ZillitResult.Success).data

        assertEquals(3, page.messages.size)
        assertFalse(page.hasMore)
    }

    @Test
    fun `an empty folder reports no mail and no more`() = runTest {
        val (mailbox, server) = mailbox(emptyList())

        val page = (mailbox.syncNext("INBOX") as ZillitResult.Success).data

        assertTrue(page.messages.isEmpty())
        assertFalse(page.hasMore)
        assertTrue(server.indexed.isEmpty(), "nothing to ask for")
    }

    @Test
    fun `the list is newest first`() = runTest {
        val (mailbox, _) = mailbox(listOf(1, 2, 3))

        val page = (mailbox.syncNext("INBOX") as ZillitResult.Success).data

        assertEquals(listOf("m3", "m2", "m1"), page.messages.map { it.id })
    }

    @Test
    fun `mail deleted elsewhere disappears on the next sync`() = runTest {
        val cache = InMemoryMailboxCache()
        val full = Mailbox(FakeMailServer(uids = listOf(1, 2, 3)), cache)
        full.syncNext("INBOX")

        // The same cache, now talking to a server that has lost uid 2.
        val page = (Mailbox(FakeMailServer(uids = listOf(1, 3)), cache).syncNext("INBOX") as ZillitResult.Success).data

        assertEquals(listOf("m3", "m1"), page.messages.map { it.id })
    }

    @Test
    fun `cached mail is readable without asking the server`() = runTest {
        val cache = InMemoryMailboxCache()
        Mailbox(FakeMailServer(uids = listOf(1, 2)), cache).syncNext("INBOX")

        val offline = Mailbox(FakeMailServer(uids = emptyList()), cache)

        assertEquals(2, offline.cachedMessages("INBOX").size)
    }

    @Test
    fun `opening a message marks it read in the cache`() = runTest {
        val cache = InMemoryMailboxCache()
        val mailbox = Mailbox(FakeMailServer(uids = listOf(1)), cache)
        mailbox.syncNext("INBOX")

        mailbox.markRead("INBOX", "m1")

        assertTrue(mailbox.cachedMessages("INBOX").single().isRead)
    }

    @Test
    fun `folders are cached as they sync`() = runTest {
        val cache = InMemoryMailboxCache()
        val mailbox = Mailbox(FakeMailServer(uids = emptyList()), cache)

        mailbox.syncFolders()

        assertEquals(listOf("INBOX", "Drafts"), mailbox.cachedFolders().map { it.name })
    }
}
