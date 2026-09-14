package com.zillit.desktop.core.badges

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.zillit.desktop.core.database.ZillitDatabase
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
            chatRoomId = "room", senderId = "peer", chatUnitId = "cu", calendarEnd = 5L, fromApi = fromApi,
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
    fun `the picker's counts group this device's unread by production`() {
        store.upsert(
            listOf(
                row("a"), row("b"), row("c", project = "p2"),
                row("read").copy(messageRead = true),
                row("other-device").copy(deviceId = "dev-2"),
            ),
        )

        assertEquals(mapOf("p1" to 2, "p2" to 1), store.unreadByProject("dev-1"))
        assertEquals(mapOf("p1" to 3, "p2" to 1), store.unreadByProject(""))
        assertTrue(store.unreadByProject("dev-9").isEmpty())
    }

    /** Mail rows carry their mailbox in `level1`; the picker counts only the one this desktop opens. */
    @Test
    fun `the picker's counts leave out mail for another mailbox`() {
        store.upsert(
            listOf(
                row("mine").copy(section = BadgeSections.EMAIL, level1 = " Me@Zillit.net "),
                row("untagged").copy(section = BadgeSections.EMAIL, level1 = ""),
                row("accounts").copy(section = BadgeSections.EMAIL, level1 = "invoices@zillit.net"),
                row("tool").copy(level1 = "invoices@zillit.net"),
            ),
        )

        assertEquals(mapOf("p1" to 4), store.unreadByProject("dev-1"), "no mailbox named: everything counts")
        assertEquals(mapOf("p1" to 3), store.unreadByProject("dev-1", mapOf("p1" to setOf("me@zillit.net"))))
        assertEquals(mapOf("p1" to 3), store.unreadByProject("", mapOf("p1" to setOf("ME@ZILLIT.NET"))), "loose")
        val both = store.unreadByProject("", mapOf("p1" to setOf("me@zillit.net", "invoices@zillit.net")))
        assertEquals(mapOf("p1" to 4), both, "both mailboxes openable: both count")
        val scopedElsewhere = store.unreadByProject("", mapOf("p2" to setOf("me@zillit.net")))
        assertEquals(mapOf("p1" to 4), scopedElsewhere, "another production's mailbox scopes nothing here")
    }

    @Test
    fun `a long read list is applied whole`() {
        val ids = (1..1200).map { "n$it" }
        store.upsert(ids.map { row(it) })

        store.markRead(ids)

        assertTrue(store.rows("p1").all { it.messageRead })
    }
}
