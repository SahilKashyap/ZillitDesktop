package com.zillit.desktop.core.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals

/** The DM cache: thread replace, newest-per-peer, and project isolation. */
class ChatCacheTest {

    private fun cache(): ChatCache {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ZillitDatabase.Schema.create(driver)
        return ChatCache(ZillitDatabase(driver))
    }

    private fun row(id: String, peer: String, at: Long, mine: Boolean = true) = ChatMessageRow(
        messageId = id,
        peerId = peer,
        uniqueId = id,
        senderId = if (mine) "me" else peer,
        receiverId = if (mine) peer else "me",
        bodyCipher = "cipher-$id",
        createdAt = at,
        isMine = mine,
    )

    @Test
    fun `a thread replaces wholesale and reads back in time order`() {
        val cache = cache()
        cache.replaceThread("p1", "alice", listOf(row("m2", "alice", 2), row("m1", "alice", 1)))
        cache.replaceThread("p1", "alice", listOf(row("m3", "alice", 3)))

        assertEquals(listOf("m3"), cache.thread("p1", "alice").map { it.messageId })
    }

    @Test
    fun `the newest row per peer answers the previews`() {
        val cache = cache()
        cache.upsert("p1", row("a1", "alice", 1))
        cache.upsert("p1", row("a2", "alice", 5))
        cache.upsert("p1", row("b1", "bob", 3, mine = false))

        val newest = cache.lastPerPeer("p1").associate { it.peerId to it.messageId }
        assertEquals(mapOf("alice" to "a2", "bob" to "b1"), newest)
    }

    @Test
    fun `projects never see each other's threads`() {
        val cache = cache()
        cache.upsert("p1", row("m1", "alice", 1))

        assertEquals(0, cache.thread("p2", "alice").size)
        assertEquals(0, cache.lastPerPeer("p2").size)
    }

    @Test
    fun `read marks persist per thread and project`() {
        val cache = cache()
        cache.markReadUntil("p1", "alice", 5)
        cache.markReadUntil("p1", "alice", 9)
        cache.markReadUntil("p2", "alice", 1)

        assertEquals(mapOf("alice" to 9L), cache.readMarks("p1"))
        assertEquals(mapOf("alice" to 1L), cache.readMarks("p2"))
    }
}
