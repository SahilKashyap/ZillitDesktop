package com.zillit.desktop.feature.email

import com.zillit.desktop.feature.email.domain.EmailSync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which mail gets fetched, and in what order.
 *
 * This is the whole of mail pagination: the API has no "page 2" call, so the
 * client diffs the server's uid list against what it holds. Get it wrong and a
 * folder loads its oldest mail first, or loops fetching the same batch forever.
 */
class EmailSyncTest {

    @Test
    fun `the newest mail is fetched first`() {
        // IMAP hands out uids ascending, so the highest is the newest. Fetching
        // from the front of the list would fill an inbox with last year's mail
        // and look empty at the top.
        val batch = EmailSync.nextBatch(serverUids = listOf(1, 2, 3, 4, 5), cachedUids = emptySet(), batch = 3)

        assertEquals(listOf(5, 4, 3), batch)
    }

    @Test
    fun `mail already held is not fetched again`() {
        val batch = EmailSync.nextBatch(
            serverUids = listOf(1, 2, 3, 4, 5),
            cachedUids = setOf(5, 4),
            batch = 3,
        )

        assertEquals(listOf(3, 2, 1), batch)
    }

    @Test
    fun `a fully cached folder asks for nothing`() {
        // The caller reads this as "stop": an empty batch is how paging ends.
        val batch = EmailSync.nextBatch(listOf(1, 2, 3), cachedUids = setOf(1, 2, 3))

        assertTrue(batch.isEmpty())
        assertTrue(EmailSync.isComplete(listOf(1, 2, 3), setOf(1, 2, 3)))
    }

    @Test
    fun `a partly cached folder is not complete`() {
        assertFalse(EmailSync.isComplete(listOf(1, 2, 3), setOf(3)))
    }

    @Test
    fun `mail deleted on another device is dropped`() {
        // Nothing else ever revisits a uid we already hold, so without this a
        // message deleted elsewhere stays in the list forever.
        val stale = EmailSync.staleUids(serverUids = listOf(2, 3), cachedUids = setOf(1, 2, 3))

        assertEquals(setOf(1), stale)
    }

    @Test
    fun `an empty folder is complete and stales nothing`() {
        assertTrue(EmailSync.isComplete(emptyList(), emptySet()))
        assertTrue(EmailSync.staleUids(emptyList(), emptySet()).isEmpty())
    }

    @Test
    fun `an emptied folder stales everything held`() {
        assertEquals(setOf(1, 2), EmailSync.staleUids(emptyList(), setOf(1, 2)))
    }

    @Test
    fun `uids arriving out of order still sort newest first`() {
        // The server does not promise ordering, and one production returns them
        // grouped rather than sorted.
        val batch = EmailSync.nextBatch(listOf(3, 1, 5, 2, 4), cachedUids = emptySet(), batch = 2)

        assertEquals(listOf(5, 4), batch)
    }

    @Test
    fun `the batch is capped even on a huge folder`() {
        val batch = EmailSync.nextBatch((1..10_000).toList(), cachedUids = emptySet())

        assertEquals(EmailSync.BATCH, batch.size)
        assertEquals(10_000, batch.first(), "the newest message must be in the first batch")
    }
}
