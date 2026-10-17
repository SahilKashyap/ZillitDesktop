package com.zillit.desktop.core.badges

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    /** iOS `clearOutPendingNotificationsByProjectID` reads the rows; `badges:cleared:device` deletes the table. */
    @Test
    fun `clearing a production reads its rows, clearing the device empties the ledger`() = runTest {
        val storage = InMemoryNotificationLedgerStore()
        val store = BadgeStore(storage, nowMillis = { 1_000L })
        store.open(project)
        store.seed(listOf(row("a"), row("b", projectId = "p2")))

        store.clearProject(project)
        assertTrue(store.counts.value.isEmpty)
        assertTrue(storage.row("a")!!.messageRead, "read, not forgotten")
        assertEquals(false, storage.row("b")!!.messageRead, "the other production's rows stay unread")

        store.seed(listOf(row("a")))
        assertTrue(store.counts.value.isEmpty, "a page that hands it back unread does not badge it again")

        store.clearEverything()
        assertEquals(0, storage.rows("p2").size)
    }

    /**
     * ZL-21764: the read echo for a message read on the web can beat the
     * message's own save to this socket. The save that follows must be filed
     * read, or the badge sticks.
     */
    @Test
    fun `a row arriving after this person's read echo is filed read`() = runTest {
        var now = 1_000L
        val store = BadgeStore(nowMillis = { now })
        store.open(project)
        val echo = BadgeSilence(deletedChatIds = setOf("msg-1"), projectId = project, ownReadEcho = true)

        store.silence(echo)
        store.arrived(listOf(row("late", section = BadgeSections.CNC, tool = "chat_label", referenceId = "msg-1")))
        assertEquals(0, store.counts.value.section(BadgeSections.CNC))

        store.arrived(listOf(row("other", section = BadgeSections.CNC, tool = "chat_label", referenceId = "msg-2")))
        assertEquals(1, store.counts.value.section(BadgeSections.CNC), "only the message the echo named")

        now += 6 * 60 * 1000L
        store.arrived(
            listOf(row("much-later", section = BadgeSections.CNC, tool = "chat_label", referenceId = "msg-1")),
        )
        assertEquals(2, store.counts.value.section(BadgeSections.CNC), "the mark lapses after five minutes")
    }

    /** Someone else deleting a message, or a read by another person, says nothing about rows still to come. */
    @Test
    fun `only an own read is remembered for rows still to come`() = runTest {
        val store = BadgeStore(nowMillis = { 1_000L })
        store.open(project)

        store.silence(BadgeSilence(deletedChatIds = setOf("msg-1"), projectId = project))
        store.seed(listOf(row("late", section = BadgeSections.CNC, tool = "chat_label", referenceId = "msg-1")))

        assertEquals(1, store.counts.value.section(BadgeSections.CNC))
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

    /**
     * The card and the tabs inside run one rule — iOS's, where every unread
     * row of a section counts on that section. Only a row filed under no
     * section at all is on neither.
     */
    @Test
    fun `the card counts what the tabs inside count`() = runTest {
        val store = BadgeStore()
        store.open(project)
        store.seed(
            listOf(
                row("real"),
                row("dashboard", tool = NotificationRecord.AD_DASHBOARD_TOOL),
                row("invite", section = BadgeSections.HOME, tool = NotificationRecord.CALENDAR_TOOL),
                row("orphan-chat", section = BadgeSections.CNC, tool = NotificationRecord.CHAT_TOOL),
                row("stray-approval", section = BadgeSections.SETTINGS, tool = "", unit = "something_else"),
                row("sectionless", section = "", tool = ""),
            ),
        )

        assertEquals(5, store.counts.value.total, "every sectioned row is a badge inside")
        assertEquals(mapOf(project to 5), store.projectCounts(null), "and the card says the same")
    }

    /** One rule for both delivery paths: the socket dropped these, the backlog page did not. */
    @Test
    fun `a silent push and an echo of one's own action badge nothing`() = runTest {
        val store = BadgeStore()
        store.open(project)
        store.seed(
            listOf(
                row("real"),
                row("silent").copy(silent = true),
                row("own-echo").copy(self = true),
            ),
        )

        assertEquals(1, store.counts.value.section(BadgeSections.TOOLS))
        assertEquals(mapOf(project to 1), store.projectCounts(null))
    }

    /**
     * Verified live 2026-08-19: 40 of 45 `cnc_label` unread sat in rooms
     * `chat-room` no longer returns. The C&C rail hid them; the card counted
     * them, and a card has nothing to open and clear.
     */
    @Test
    fun `rooms the chat can no longer open are held out of both counts`() = runTest {
        val storage = InMemoryNotificationLedgerStore()
        val store = BadgeStore(storage)
        store.open(project)
        store.seed(listOf(groupChat("live", room = "r1"), groupChat("lost", room = "r-gone"), dm("peer-line")))
        assertEquals(3, store.counts.value.section(BadgeSections.CNC), "rooms unknown: every row counts")

        store.showChatRooms(project, setOf("r1"))

        assertEquals(2, store.counts.value.section(BadgeSections.CNC), "the DM is nobody's room")
        assertEquals(mapOf(project to 2), store.projectCounts(null))
        assertTrue(storage.rows(project).none { it.messageRead }, "held back, not read: a room can come back")

        store.showChatRooms(project, setOf("r1", "r-gone"))
        assertEquals(3, store.counts.value.section(BadgeSections.CNC), "and it does")
    }

    /** The listing's read marks, applied to the ledger rather than to its own fold alone. */
    @Test
    fun `a conversation read on this computer stops counting, up to the mark only`() = runTest {
        val store = BadgeStore()
        store.open(project)
        store.seed(
            listOf(
                dm("before", created = 50L),
                dm("after", created = 500L),
                groupChat("other-thread", room = "r1", created = 50L),
            ),
        )

        assertEquals(2, store.markRead(LedgerRead.ConversationsRead(mapOf("peer" to 100L, "r1" to 100L))))

        assertEquals(1, store.counts.value.section(BadgeSections.CNC), "the line nobody has seen stays")
        assertEquals(mapOf(project to 1), store.projectCounts(null))
    }

    /**
     * The listing replays its marks after every refresh and refreshes on
     * [BadgeStore.changes] — a read that moved nothing must not signal, or
     * the two answer each other for ever.
     */
    @Test
    fun `a read that turns no row signals nothing`() = runTest {
        val store = BadgeStore()
        store.open(project)
        store.seed(listOf(dm("line", created = 500L)))

        assertEquals(0, store.markRead(LedgerRead.ConversationsRead(mapOf("peer" to 100L))))
        assertEquals(0, store.markRead(LedgerRead.Tool("nothing_label")))
        assertEquals(1, store.counts.value.section(BadgeSections.CNC))
    }

    /**
     * A read made on a phone, or a message deleted before anyone read it,
     * arrives as `notification:silent`. It has to reach the ledger, not just
     * the open production's copy of it — between productions there is no open
     * one, and the picker counts them all. Measured before this: arrivals
     * moved a card in ~60 ms, prunes took 20-30 s (the next seeded page).
     */
    @Test
    fun `a prune reaches every production, including with none open`() = runTest {
        val storage = InMemoryNotificationLedgerStore()
        val store = BadgeStore(storage)
        store.open(project)
        store.seed(listOf(row("here", referenceId = "m-1"), row("elsewhere", projectId = "p2", referenceId = "m-2")))
        assertEquals(mapOf(project to 1, "p2" to 1), store.projectCounts(null))

        // Leaving a production empties the in-memory rows; the ledger keeps them.
        store.clear()
        store.silence(BadgeSilence(deletedChatIds = setOf("m-1", "m-2")))

        assertTrue(store.projectCounts(null).isEmpty(), "both productions' rows are pruned")
        assertTrue(storage.rows("p2").all { it.messageRead }, "and it stuck on disk")
    }

    /**
     * The update prune. Inside the span a page covers, the page is the
     * server's whole answer, so an unread row of ours it did not carry is one
     * the server has dropped — nothing will flip it and no screen can clear it.
     */
    @Test
    fun `a prune retires the unread rows the page no longer carries`() = runTest {
        val storage = InMemoryNotificationLedgerStore()
        val store = BadgeStore(storage)
        store.open(project)
        store.seed(listOf(row("stale", updated = 150L), row("live", updated = 200L)))

        val retired = store.pruneUnserved(coveredFrom = 100L, askedAt = 1_000L, carried = setOf("live"))

        assertEquals(1, retired)
        assertEquals(1, store.counts.value.section(BadgeSections.TOOLS))
        assertNull(storage.row("stale"), "gone from disk, not merely uncounted")
        assertNotNull(storage.row("live"))
    }

    /** A read is this ledger's own record, and worth more than the server's word; a prune cannot spend it. */
    @Test
    fun `a prune leaves read rows alone`() = runTest {
        val storage = InMemoryNotificationLedgerStore()
        val store = BadgeStore(storage)
        store.open(project)
        store.seed(listOf(row("read-here", updated = 150L, read = true)))

        assertEquals(0, store.pruneUnserved(coveredFrom = 100L, askedAt = 1_000L, carried = emptySet()))
        assertNotNull(storage.row("read-here"), "the read survives, and with it the watermark")
    }

    /** A page is a window, not a census: older than its oldest row, it says nothing. */
    @Test
    fun `a prune judges nothing older than the span the page covers`() = runTest {
        val storage = InMemoryNotificationLedgerStore()
        val store = BadgeStore(storage)
        store.open(project)
        store.seed(listOf(row("older", updated = 50L)))

        assertEquals(0, store.pruneUnserved(coveredFrom = 100L, askedAt = 1_000L, carried = emptySet()))
        assertNotNull(storage.row("older"))
    }

    /** A `notification:save` that landed while the page was in flight is newer than the ask. */
    @Test
    fun `a row that arrived after the page was asked for survives the prune`() = runTest {
        val storage = InMemoryNotificationLedgerStore()
        val store = BadgeStore(storage)
        store.open(project)
        store.seed(listOf(row("mid-flight", updated = 2_000L)))

        assertEquals(0, store.pruneUnserved(coveredFrom = 100L, askedAt = 1_000L, carried = emptySet()))
        assertNotNull(storage.row("mid-flight"), "the page never had a chance to carry it")
    }

    /** The listing is user-wide, so the prune is too — the picker's cards count every production. */
    @Test
    fun `a prune reaches rows of productions that are not open`() = runTest {
        val storage = InMemoryNotificationLedgerStore()
        val store = BadgeStore(storage)
        store.open(project)
        store.seed(listOf(row("here", updated = 150L), row("elsewhere", projectId = "p2", updated = 150L)))
        assertEquals(mapOf(project to 1, "p2" to 1), store.projectCounts(null))

        assertEquals(2, store.pruneUnserved(coveredFrom = 100L, askedAt = 1_000L, carried = emptySet()))

        assertTrue(store.projectCounts(null).isEmpty(), "both productions' stale rows are gone")
        assertTrue(storage.rows("p2").isEmpty())
    }

    /**
     * A row read here stays read whatever a page says — until the ledger is
     * dropped. Then the server's unread state is taken as sent again, on the
     * open production and on the picker's cards: the undo of the retired
     * stuck-badge sweep rests on exactly this.
     */
    @Test
    fun `a dropped ledger takes the server's unread rows as sent again`() = runTest {
        val store = BadgeStore(InMemoryNotificationLedgerStore())
        store.open(project)
        store.seed(listOf(row("here", updated = 150L), row("elsewhere", projectId = "p2", updated = 150L)))
        store.clearProject(project)
        store.clearProject("p2")
        store.seed(listOf(row("here", updated = 150L), row("elsewhere", projectId = "p2", updated = 150L)))
        assertTrue(store.projectCounts(null).isEmpty(), "local reads win over the page")

        store.clearEverything()
        store.seed(listOf(row("here", updated = 150L), row("elsewhere", projectId = "p2", updated = 150L)))

        assertEquals(1, store.counts.value.section(BadgeSections.TOOLS))
        assertEquals(mapOf(project to 1, "p2" to 1), store.projectCounts(null))
    }

    private fun dm(id: String, created: Long = 100L) =
        row(id, section = BadgeSections.CNC, tool = NotificationRecord.CHAT_TOOL, unit = "chat_member_label")
            .copy(senderId = "peer", created = created)

    private fun groupChat(id: String, room: String, created: Long = 100L) =
        row(id, section = BadgeSections.CNC, tool = NotificationRecord.CHAT_TOOL, unit = "chat_group_label")
            .copy(chatRoomId = room, created = created)
}
