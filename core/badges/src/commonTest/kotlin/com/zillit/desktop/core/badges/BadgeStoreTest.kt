package com.zillit.desktop.core.badges

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ledger's contract — the rules every phone's local badge database
 * follows, and the reason a desktop badge can agree with a phone's.
 */
class BadgeStoreTest {

    private val project = "p1"

    @Suppress("LongParameterList") // One factory, every column a test may care about.
    private fun row(
        id: String,
        section: String = BadgeSections.TOOLS,
        tool: String = "sides_label",
        unit: String = "",
        read: Boolean = false,
        fromApi: Boolean = true,
        updated: Long = 100L,
        projectId: String = project,
        referenceId: String = "",
    ) = NotificationRecord(
        id = id, projectId = projectId, section = section, tool = tool, unit = unit,
        messageRead = read, fromApi = fromApi, updated = updated, referenceId = referenceId,
        raw = """{"notification_uuid":"$id","section":"$section","tool":"$tool","unit":"$unit"}""",
    )

    @Test
    fun `a seed counts what the server sent`() = runTest {
        val store = BadgeStore()
        store.open(project)

        store.seed(listOf(row("a"), row("b"), row("c", read = true)))

        assertEquals(2, store.counts.value.section(BadgeSections.TOOLS))
        assertEquals(2, store.counts.value["sides_tool"])
    }

    /** Android `insertOrUpdateFromApi`: the server keeps saying unread for rows the phones read long ago. */
    @Test
    fun `a read made here outlives the server handing the row back unread`() = runTest {
        val storage = InMemoryNotificationLedgerStore()
        val store = BadgeStore(storage)
        store.open(project)
        store.seed(listOf(row("a")))

        store.markRead(LedgerRead.Tool("sides_label"))
        assertEquals(0, store.counts.value.section(BadgeSections.TOOLS))

        store.seed(listOf(row("a")))
        assertEquals(0, store.counts.value.section(BadgeSections.TOOLS), "the next page put it back")

        // …and across a relaunch: the flag is on disk, not in memory.
        val relaunched = BadgeStore(storage)
        relaunched.open(project)
        assertEquals(0, relaunched.counts.value.section(BadgeSections.TOOLS))
    }

    /** A read on the phone comes back as a read row on the next `next` page. */
    @Test
    fun `a row the server now says is read is taken as read`() = runTest {
        val store = BadgeStore()
        store.open(project)
        store.seed(listOf(row("a")))

        store.seed(listOf(row("a", read = true)))

        assertEquals(0, store.counts.value.section(BadgeSections.TOOLS))
    }

    @Test
    fun `the watermark is the newest API row and socket rows never move it`() = runTest {
        val store = BadgeStore()
        store.open(project)
        assertEquals(0L, store.watermark(project))

        store.seed(listOf(row("a", updated = 10L), row("b", updated = 30L)))
        store.arrived(listOf(row("c", updated = 90L, fromApi = false)))

        assertEquals(30L, store.watermark(project))
        store.seed(listOf(row("z", projectId = "p2", updated = 70L)))
        assertEquals(70L, store.watermark(""), "blank asks every production")
    }

    @Test
    fun `a socket row for another production is kept but not counted`() = runTest {
        val store = BadgeStore()
        store.open(project)

        store.arrived(listOf(row("x", projectId = "p2", fromApi = false)))
        assertTrue(store.counts.value.isEmpty)

        store.open("p2")
        assertEquals(1, store.counts.value.section(BadgeSections.TOOLS))
    }

    /** Android `insertOrUpdate` 118-139: a Document Distribution row replaces the earlier one for the same entity. */
    @Test
    fun `a document distribution row supersedes the earlier unread for the same entity`() = runTest {
        val store = BadgeStore()
        store.open(project)
        store.seed(listOf(row("a", tool = "document_distribution_label", unit = "folder", referenceId = "f1")))

        store.arrived(
            listOf(row("b", tool = "document_distribution_label", unit = "folder", referenceId = "f1")
                .copy(fromApi = false)),
        )

        assertEquals(1, store.counts.value["document_distribution_tool"])
    }

    @Test
    fun `a silent instruction drops every kind of row it names, for good`() = runTest {
        val storage = InMemoryNotificationLedgerStore()
        val store = BadgeStore(storage)
        store.open(project)
        store.seed(
            listOf(
                row("read-elsewhere"),
                row("chat", section = BadgeSections.CNC, tool = "chat_label", referenceId = "m1"),
                row("comment", section = BadgeSections.HOME, tool = "", unit = "u1", referenceId = "c1"),
                row("lost-tool", tool = "map_label"),
                row("lost-unit", section = BadgeSections.HOME, tool = "", unit = "u-gone"),
                row("admin", section = BadgeSections.SETTINGS, tool = "admin_settings_label")
                    .copy(unit = "project_join_user_request_label"),
                row("kept", tool = "sides_label"),
            ),
        )

        store.silence(
            BadgeSilence(
                readIds = setOf("read-elsewhere"),
                deletedChatIds = setOf("m1"),
                deletedCommentIds = setOf("c1"),
                lostTools = setOf("map_label"),
                lostUnits = setOf("u-gone"),
                lostAdminSettings = setOf("admin_settings_label"),
            ),
        )

        val counts = store.counts.value
        assertEquals(1, counts.section(BadgeSections.TOOLS), "only the untouched tool row is left")
        assertEquals(0, counts.section(BadgeSections.HOME))
        assertEquals(0, counts.section(BadgeSections.CNC))
        assertEquals(0, counts.section(BadgeSections.SETTINGS))
        assertEquals(6, storage.rows(project).count { it.messageRead }, "the prunes are on disk")
    }

    @Test
    fun `a room lost is matched by level_3 as on the web, or by its chat room id`() = runTest {
        val store = BadgeStore()
        store.open(project)
        store.seed(
            listOf(
                row("by-level", section = BadgeSections.CNC, tool = "chat_label", unit = "chat_group_label")
                    .copy(level3 = "room-1", chatRoomId = "room-1"),
                row("by-ref", section = BadgeSections.CNC, tool = "chat_label", unit = "chat_group_label")
                    .copy(chatRoomId = "room-2"),
            ),
        )

        store.silence(BadgeSilence(lostRooms = setOf("room-1", "room-2")))

        assertEquals(0, store.counts.value.section(BadgeSections.CNC))
    }

    /** Android 8625-8637: a silent frame that names this device as its origin is not news. */
    @Test
    fun `a silent frame from this very device is ignored`() = runTest {
        val store = BadgeStore()
        store.open(project)
        store.seed(listOf(row("a")))

        store.silence(BadgeSilence(readIds = setOf("a"), selfDeviceId = "dev-1"), ownDeviceId = "dev-1")
        assertEquals(1, store.counts.value.section(BadgeSections.TOOLS))

        store.silence(BadgeSilence(readIds = setOf("a"), selfDeviceId = "dev-2"), ownDeviceId = "dev-1")
        assertEquals(0, store.counts.value.section(BadgeSections.TOOLS))
    }

    @Test
    fun `reads clear by board, tool, section, reference and conversation`() = runTest {
        val store = BadgeStore()
        store.open(project)
        store.seed(
            listOf(
                row("board", section = BadgeSections.HOME, tool = "", unit = "u1"),
                row("unit-chat", section = BadgeSections.HOME, tool = "", unit = "other").copy(chatUnitId = "u1"),
                row("global-on-unit", section = BadgeSections.HOME, tool = "", unit = "u1").copy(isGlobal = true),
                row("tool", tool = "info_label"),
                row("sos", section = BadgeSections.SOS, tool = ""),
                row("mail", section = BadgeSections.EMAIL, tool = "", referenceId = "msg-1"),
                row("dm", section = BadgeSections.CNC, tool = "chat_label").copy(senderId = "peer"),
            ),
        )

        store.markRead(LedgerRead.Board("u1"))
        assertEquals(1, store.counts.value.section(BadgeSections.HOME), "the global row is not a board read's")

        store.markRead(LedgerRead.Tool("info_label"))
        assertEquals(0, store.counts.value["info_tool"])

        store.markRead(LedgerRead.Section(BadgeSections.SOS))
        assertEquals(0, store.counts.value.section(BadgeSections.SOS))

        store.markRead(LedgerRead.Reference("msg-1"))
        assertEquals(0, store.counts.value.section(BadgeSections.EMAIL))

        store.markRead(LedgerRead.Conversation("peer"))
        assertEquals(0, store.counts.value.section(BadgeSections.CNC))
    }

    @Test
    fun `clearing a production or the device empties the ledger`() = runTest {
        val storage = InMemoryNotificationLedgerStore()
        val store = BadgeStore(storage)
        store.open(project)
        store.seed(listOf(row("a"), row("b", projectId = "p2")))

        store.clearProject(project)
        assertTrue(store.counts.value.isEmpty)
        assertEquals(1, storage.rows("p2").size, "the other production's rows stay")

        store.clearEverything()
        assertEquals(0, storage.rows("p2").size)
    }

    /**
     * Android `calculateAllProjectsBadges`: own rows grouped by production —
     * and a listing that never names devices still counts.
     */
    @Test
    fun `the picker's counts prefer this device's rows and fall back to every row`() = runTest {
        val store = BadgeStore()
        store.open(project)
        store.seed(listOf(row("a").copy(deviceId = "me"), row("b", projectId = "p2").copy(deviceId = "other")))

        assertEquals(mapOf(project to 1), store.projectCounts("me"))
        assertEquals(mapOf(project to 1, "p2" to 1), store.projectCounts(null))
        assertEquals(mapOf(project to 1, "p2" to 1), store.projectCounts("nobody"), "no own rows: every row counts")
    }

    @Test
    fun `clear forgets the production in memory but not on disk`() = runTest {
        val storage = InMemoryNotificationLedgerStore()
        val store = BadgeStore(storage)
        store.open(project)
        store.seed(listOf(row("a")))

        store.clear()

        assertTrue(store.counts.value.isEmpty)
        assertEquals(1, storage.rows(project).size)
    }

    @Test
    fun `the chat listing gets the wire rows with the ledger's word on message_read`() = runTest {
        val store = BadgeStore()
        store.open(project)
        store.seed(listOf(row("dm", section = BadgeSections.CNC, tool = "chat_label").copy(senderId = "peer")))
        store.markRead(LedgerRead.Conversation("peer"))

        val rows: JsonArray = store.wireRows(BadgeSections.CNC)

        assertEquals(1, rows.size)
        assertEquals("true", (rows[0] as JsonObject)["message_read"]?.jsonPrimitive?.content)
        assertEquals("chat_label", (rows[0] as JsonObject)["tool"]?.jsonPrimitive?.content)
        assertEquals(JsonArray(emptyList()), store.wireRows("nowhere_label"))
    }

    @Test
    fun `a split groups the open production's live rows by one field`() = runTest {
        val store = BadgeStore()
        store.open(project)
        store.seed(
            listOf(
                row("c1", section = BadgeSections.CNC, tool = "chat_label").copy(senderId = "x"),
                row("c2", section = BadgeSections.CNC, tool = "call_label"),
                row("c3", section = BadgeSections.CNC, tool = "call_label", read = true),
                row("m1", section = BadgeSections.EMAIL, tool = "", unit = "inbox"),
            ),
        )

        assertEquals(
            mapOf("chat_label" to 1, "call_label" to 1),
            store.split(BadgeDrilldownQuery(groupBy = "tool", section = BadgeSections.CNC)),
        )
        assertEquals(
            mapOf("inbox" to 1),
            store.split(BadgeDrilldownQuery(groupBy = "unit", section = BadgeSections.EMAIL)),
        )
    }

    @Test
    fun `wire rows survive a raw column that is not json`() = runTest {
        val store = BadgeStore()
        store.open(project)
        store.seed(listOf(row("a", section = BadgeSections.CNC).copy(raw = "not json")))

        assertEquals(0, store.wireRows(BadgeSections.CNC).size)
        assertEquals(Json.parseToJsonElement("[]"), store.wireRows("x"))
    }
}
