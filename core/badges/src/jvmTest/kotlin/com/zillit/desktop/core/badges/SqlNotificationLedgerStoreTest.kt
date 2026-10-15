package com.zillit.desktop.core.badges

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.zillit.desktop.core.database.ZillitDatabase
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The ledger table — against a real in-memory SQLite, because the behaviour that matters is the database's. */
class SqlNotificationLedgerStoreTest {

    private lateinit var store: SqlNotificationLedgerStore

    @BeforeTest
    fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ZillitDatabase.Schema.create(driver)
        store = SqlNotificationLedgerStore(ZillitDatabase(driver))
    }

    private fun row(id: String, project: String = "p1", updated: Long = 1L, fromApi: Boolean = true) =
        NotificationRecord(
            id = id, projectId = project, deviceId = "dev-1", mongoId = "m-$id", section = "tools_label",
            tool = "sides_label",
            unit = "u", level1 = "a", level2 = "b", level3 = "c", action = "act", referenceId = "ref",
            sender = "s", receiver = "r", created = updated, updated = updated, isGlobal = true,
            chatRoomId = "room", senderId = "peer", chatUnitId = "cu", calendarExpiry = 5L, fromApi = fromApi,
            raw = """{"notification_uuid":"$id"}""",
        )

    @Test
    fun `a row round-trips column for column`() {
        store.upsert(listOf(row("a")))

        assertEquals(row("a"), store.row("a"))
        assertEquals(listOf(row("a")), store.rows("p1"))
        assertNull(store.row("missing"))
    }

    @Test
    fun `upsert replaces by id and marks read in place`() {
        store.upsert(listOf(row("a", updated = 1L)))
        store.upsert(listOf(row("a", updated = 2L)))
        assertEquals(1, store.rows("p1").size)
        assertEquals(2L, store.row("a")?.updated)

        store.markRead(listOf("a"))
        assertTrue(store.row("a")?.messageRead == true)
        store.markRead(emptyList())
    }

    @Test
    fun `the watermark is the newest API row of the production`() {
        assertEquals(0L, store.watermark("p1"))
        store.upsert(listOf(row("a", updated = 10L), row("b", updated = 30L), row("c", updated = 99L, fromApi = false)))
        store.upsert(listOf(row("d", project = "p2", updated = 500L)))

        assertEquals(30L, store.watermark("p1"))
        assertEquals(500L, store.watermark(""), "blank asks every production")
    }

    @Test
    fun `deleting a production leaves the others`() {
        store.upsert(listOf(row("a"), row("b", project = "p2")))

        store.deleteProject("p1")
        assertTrue(store.rows("p1").isEmpty())
        assertEquals(1, store.rows("p2").size)

        store.deleteAll()
        assertTrue(store.rows("p2").isEmpty())
    }

    @Test
    fun `the picker's rows are this device's, and only the ones that count`() {
        store.upsert(
            listOf(
                row("a"), row("b"), row("c", project = "p2"),
                row("read").copy(messageRead = true),
                row("ignored").copy(ignored = true),
                row("deleted").copy(deleted = true),
                row("silent").copy(silent = true),
                row("own-echo").copy(self = true),
                row("other-device").copy(deviceId = "dev-2"),
            ),
        )

        assertEquals(setOf("a", "b", "c"), store.unreadAcrossProductions("dev-1").map { it.id }.toSet())
        assertEquals(
            setOf("a", "b", "c", "other-device"),
            store.unreadAcrossProductions("").map { it.id }.toSet(),
            "a blank device takes every device's rows",
        )
        assertTrue(store.unreadAcrossProductions("dev-9").isEmpty())
    }

    /** The update prune's writer: rows leave the table, and the ones it did not name stay. */
    @Test
    fun `deleting by id removes those rows and no others`() {
        store.upsert(listOf(row("a"), row("b"), row("c", project = "p2")))

        store.delete(listOf("a", "missing"))

        assertNull(store.row("a"))
        assertEquals(listOf("b"), store.rows("p1").map { it.id })
        assertEquals(1, store.rows("p2").size, "another production's row is not collateral")
        store.delete(emptyList())
        assertEquals(1, store.rows("p1").size)
    }

    /** Past SQLite's bound-variable limit, which is why the delete is chunked. */
    @Test
    fun `a long delete list is applied whole`() {
        val ids = (1..1200).map { "n$it" }
        store.upsert(ids.map { row(it) } + row("keep"))

        store.delete(ids)

        assertEquals(listOf("keep"), store.rows("p1").map { it.id })
    }

    @Test
    fun `a long read list is applied whole`() {
        val ids = (1..1200).map { "n$it" }
        store.upsert(ids.map { row(it) })

        store.markRead(ids)

        assertTrue(store.rows("p1").all { it.messageRead })
    }

    /**
     * The whole prune over the real table, which is where it will run: the
     * stale row leaves the database, the carried row and the read row stay, and
     * the counts a screen draws fall with them.
     */
    @Test
    fun `the update prune over the real table takes only the unserved rows`() = runTest {
        val badges = BadgeStore(store)
        badges.open("p1")
        badges.seed(
            listOf(
                row("stale", updated = 1_500L),
                row("live", updated = 1_600L),
                row("read-here", updated = 1_550L).copy(messageRead = true),
                row("elsewhere", project = "p2", updated = 1_500L),
            ),
        )
        assertEquals(2, badges.counts.value.total, "the read row is already off the count")

        val retired = badges.pruneUnserved(coveredFrom = 1_000L, askedAt = 9_000L, carried = setOf("live"))

        assertEquals(2, retired, "the stale row here and the stale row of the other production")
        assertNull(store.row("stale"))
        assertNull(store.row("elsewhere"))
        assertEquals(
            setOf("live", "read-here"),
            store.rows("p1").map { it.id }.toSet(),
            "the carried row and the read row survive on disk",
        )
        assertTrue(store.row("read-here")?.messageRead == true, "and the read is still a read")
        assertEquals(1, badges.counts.value.total, "and the rail shows only what the server still serves")
    }
}
