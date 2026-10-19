package com.zillit.desktop.feature.chat

import com.zillit.desktop.feature.chat.data.chatRows
import com.zillit.desktop.feature.chat.data.conversationStampsFrom
import com.zillit.desktop.feature.chat.domain.ChatSendState
import com.zillit.desktop.feature.chat.data.readReceiptFrom
import com.zillit.desktop.feature.chat.data.recentPeerIds
import com.zillit.desktop.feature.chat.data.roomsFrom
import com.zillit.desktop.feature.chat.data.readChatMessage
import com.zillit.desktop.feature.chat.data.lastMessagesEnvelope
import com.zillit.desktop.feature.chat.data.sendEnvelope
import com.zillit.desktop.feature.chat.data.SelfRead
import com.zillit.desktop.feature.chat.data.selfReadOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The CNC wire: tolerant reads, and an envelope pinned field by field. */
class ChatWireTest {

    private fun decrypt(cipher: String): String? =
        if (cipher.startsWith("hex:")) cipher.removePrefix("hex:") else null

    @Test
    fun `a row reads, decrypting when it can and passing through when not`() {
        val encrypted = readChatMessage(
            Json.parseToJsonElement(
                """{"_id":"m1","unique_id":"u-1","sender":"alice","receiver":"bob",
                   "message":"hex:hello","created":1700000000000}""",
            ),
            myUserId = "bob",
            decrypt = ::decrypt,
        )!!
        assertEquals("hello", encrypted.body)
        assertEquals(false, encrypted.isMine)

        val legacy = readChatMessage(
            Json.parseToJsonElement(
                """{"_id":"m2","sender":"bob","receiver":"alice","message":"plain","created":1}""",
            ),
            myUserId = "bob",
            decrypt = ::decrypt,
        )!!
        assertEquals("plain", legacy.body, "a legacy plaintext row passes through")
        assertTrue(legacy.isMine)
    }

    @Test
    fun `an attachment reads its size and knows a PDF by type or by name`() {
        val row = readChatMessage(
            Json.parseToJsonElement(
                """{"_id":"m3","sender":"alice","receiver":"bob","message":"","created":1,
                   "attachment":{"media":"chat/file/p/x.pdf","name":"x.pdf","content_type":"document",
                   "content_subtype":"pdf","thumbnail":"","bucket":"b","region":"r","file_size":"173419"}}""",
            ),
            myUserId = "bob",
            decrypt = ::decrypt,
        )!!
        assertEquals(173419L, row.attachment?.sizeBytes)
        assertTrue(row.attachment!!.isPdf, "a document/pdf row is a PDF")
        val word = com.zillit.desktop.feature.chat.domain.ChatAttachment(
            media = "k",
            name = "a.docx",
            contentType = "document",
        )
        assertTrue(!word.isPdf)
    }

    @Test
    fun `a row without ids or sender is dropped, not thrown`() {
        assertNull(readChatMessage(Json.parseToJsonElement("""{"message":"x"}"""), "me", ::decrypt))
        assertNull(readChatMessage(Json.parseToJsonElement(""""just a string""""), "me", ::decrypt))
    }

    @Test
    fun `history rows unwrap bare or enveloped`() {
        assertEquals(2, chatRows(Json.parseToJsonElement("""[{"a":1},{"b":2}]""")).size)
        assertEquals(1, chatRows(Json.parseToJsonElement("""{"data":[{"a":1}]}""")).size)
        assertEquals(
            2,
            chatRows(
                Json.parseToJsonElement("""{"data":{"chat_records":[{"a":1},{"b":2}]}}"""),
            ).size,
            "the live server's shape",
        )
        assertEquals(0, chatRows(Json.parseToJsonElement("""{"success":true}""")).size)
    }

    @Test
    fun `recent peers read from the ack, wherever the list sits`() {
        assertEquals(
            listOf("u1", "u2"),
            recentPeerIds(
                Json.parseToJsonElement("""{"detail":{"usersList":["u1","u2"]}}"""),
            ),
        )
        assertEquals(
            listOf("u3"),
            recentPeerIds(Json.parseToJsonElement("""{"usersList":["u3",""]}""")),
        )
        assertEquals(0, recentPeerIds(Json.parseToJsonElement("null")).size)
    }

    @Test
    fun `the send envelope carries Android's fields`() {
        val envelope = sendEnvelope(
            projectId = "p1",
            uniqueId = "u-9",
            senderId = "me",
            receiverId = "you",
            cipherBody = "cipher",
            nowMillis = 42L,
        )
        assertEquals("private", envelope["type"]!!.jsonPrimitive.content)
        assertEquals("you", envelope["messageUniqueId"]!!.jsonPrimitive.content)
        assertEquals("0", envelope["deleted"]!!.jsonPrimitive.content)
        assertEquals("text", envelope["message_type"]!!.jsonPrimitive.content)
        assertEquals("cnc_section", envelope["chat_tool"]!!.jsonPrimitive.content)
        assertEquals("cipher", envelope["message"]!!.jsonPrimitive.content)
        assertEquals("p1", envelope["project_id"]!!.jsonPrimitive.content)
        assertEquals("1", envelope["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `rooms read from the envelope, nameless rows get a fallback`() {
        val rooms = roomsFrom(
            Json.parseToJsonElement(
                """{"data":{"chat_rooms":[
                   {"_id":"r1","room_name":"Camera Dept"},{"_id":"r2"},{"nope":1}]}}""",
            ),
        )
        assertEquals(listOf("r1" to "Camera Dept", "r2" to "Group"), rooms.map { it.id to it.name })
    }

    @Test
    fun `a room's stamp comes from last_message_posted_on, in any of its shapes`() {
        // `chat-room` does not send `sorting_activity` at all — read live on
        // prod 2026-10-14, where every row answered null and two of DN6's
        // three rooms were consequently hidden from the All chip. The field
        // the server does send is `last_message_posted_on`.
        val rooms = roomsFrom(
            Json.parseToJsonElement(
                """{"data":{"chat_rooms":[
                   {"_id":"millis","last_message_posted_on":1760000000000},
                   {"_id":"string","last_message_posted_on":"1760000000001"},
                   {"_id":"iso","last_message_posted_on":"2026-10-09T18:30:00Z"},
                   {"_id":"legacy","sorting_activity":1760000000002},
                   {"_id":"newest-wins","last_message_posted_on":5,"sorting_activity":9},
                   {"_id":"none","created":1700000000000},
                   {"_id":"junk","last_message_posted_on":"not a time"}]}}""",
            ),
        )
        val stamps = rooms.associate { it.id to it.sortingActivity }
        assertEquals(1760000000000L, stamps["millis"])
        assertEquals(1760000000001L, stamps["string"])
        assertEquals(1791570600000L, stamps["iso"])
        assertEquals(1760000000002L, stamps["legacy"])
        assertEquals(9L, stamps["newest-wins"])
        // A room's birthday is not activity: `created` must not stand in, or
        // an empty room outranks a live conversation.
        assertEquals(0L, stamps["none"])
        assertEquals(0L, stamps["junk"])
    }

    @Test
    fun `users-group stamps both halves of the listing, people included`() {
        // The one listing read that timestamps a private thread; the web
        // splits the same single array on `is_group`.
        val stamps = conversationStampsFrom(
            Json.parseToJsonElement(
                """{"status":1,"data":[
                   {"user_id":"u-1","is_group":false,"last_message_posted_on":1760000000000},
                   {"_id":"r-1","is_group":true,"last_message_posted_on":"1760000000001"},
                   {"user_id":"u-2","is_group":false,"last_message_posted_on":"2026-10-09T18:30:00Z"},
                   {"user_id":"u-3","is_group":false},
                   {"user_id":"u-4","is_group":false,"last_message_posted_on":0},
                   {"is_group":false,"last_message_posted_on":5}]}""",
            ),
        )
        assertEquals(1760000000000L, stamps["u-1"])
        assertEquals(1760000000001L, stamps["r-1"])
        assertEquals(1791570600000L, stamps["u-2"])
        // Never spoken, zero, or no id at all: no stamp rather than a false one.
        assertEquals(null, stamps["u-3"])
        assertEquals(null, stamps["u-4"])
        assertEquals(3, stamps.size)
    }

    @Test
    fun `a room keeps its creator - the Delete affordance is theirs alone`() {
        val rooms = roomsFrom(
            Json.parseToJsonElement(
                """{"data":{"chat_rooms":[
                   {"_id":"r1","room_name":"Camera Dept","owned_by":"u-me"},
                   {"_id":"r2","room_name":"Not Mine"}]}}""",
            ),
        )
        assertEquals(listOf("u-me", null), rooms.map { it.ownedBy })
    }

    @Test
    fun `a group envelope says group and rides the room id`() {
        val envelope = sendEnvelope(
            projectId = "p1", uniqueId = "u", senderId = "me",
            receiverId = "room-9", cipherBody = "c", nowMillis = 1L, isGroup = true,
        )
        assertEquals("group", envelope["type"]!!.jsonPrimitive.content)
        assertEquals("room-9", envelope["receiver"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a file message carries the attachment and its kind`() {
        val file = com.zillit.desktop.feature.chat.domain.ChatAttachment(
            media = "chat/day7.pdf", name = "day7.pdf", contentType = "application/pdf",
        )
        val envelope = sendEnvelope(
            projectId = "p", uniqueId = "u", senderId = "me", receiverId = "you",
            cipherBody = "", nowMillis = 1L, attachment = file,
        )
        assertEquals("document", envelope["message_type"]!!.jsonPrimitive.content)
        val sent = envelope["attachment"]!! as kotlinx.serialization.json.JsonObject
        assertEquals("chat/day7.pdf", sent["media"]!!.jsonPrimitive.content)

        val read = readChatMessage(
            Json.parseToJsonElement(
                """{"_id":"m","sender":"you","receiver":"me","message":"",
                   "created":9,"attachment":{"media":"chat/set.jpg","name":"set.jpg",
                   "content_type":"image","bucket":"b","region":"r"}}""",
            ),
            myUserId = "me",
            decrypt = { null },
        )!!
        assertEquals("set.jpg", read.attachment?.name)
        assertEquals("b", read.attachment?.bucket)
    }

    @Test
    fun `a live socket message reads through its detail envelope`() {
        // History rows are bare; socket events wrap the same row in
        // {success, detail}. Reading only the top level dropped every live
        // message — the realtime bug of record.
        val live = readChatMessage(
            Json.parseToJsonElement(
                """{"success":true,"detail":{"_id":"m9","unique_id":"u9","sender":"alice",
                   "receiver":"me","message":"hex:live one","created":77}}""",
            ),
            myUserId = "me",
            decrypt = ::decrypt,
        )!!
        assertEquals("live one", live.body)
        assertEquals("alice", live.senderId)
        assertEquals(77L, live.timestampMillis)

        // The bare shape still reads, so history is unaffected.
        val bare = readChatMessage(
            Json.parseToJsonElement(
                """{"_id":"m1","sender":"alice","receiver":"me","message":"hex:old","created":1}""",
            ),
            myUserId = "me",
            decrypt = ::decrypt,
        )!!
        assertEquals("old", bare.body)
    }

    @Test
    fun `the wire's status becomes the delivery ladder`() {
        assertEquals(ChatSendState.Sending, ChatSendState.ofWire(0))
        assertEquals(ChatSendState.Sending, ChatSendState.ofWire(-1))
        assertEquals(ChatSendState.Sending, ChatSendState.ofWire(null))
        assertEquals(ChatSendState.Sent, ChatSendState.ofWire(1))
        assertEquals(ChatSendState.Delivered, ChatSendState.ofWire(2))
        assertEquals(ChatSendState.Read, ChatSendState.ofWire(3))
        assertEquals(ChatSendState.Sent, ChatSendState.ofWire(9), "an unknown code still sent")
    }

    @Test
    fun `states only ever move forward`() {
        assertTrue(ChatSendState.Read.atLeast(ChatSendState.Delivered))
        assertTrue(ChatSendState.Delivered.atLeast(ChatSendState.Delivered))
        assertFalse(ChatSendState.Sent.atLeast(ChatSendState.Read), "sent is behind read")
    }

    @Test
    fun `a receipt counts only when we are the sender`() {
        val mine = readReceiptFrom(
            Json.parseToJsonElement(
                """{"detail":{"sender":"me","receiver":"alice","status":3}}""",
            ),
            myUserId = "me",
        )!!
        assertEquals("alice", mine.peerId)
        assertEquals(ChatSendState.Read, mine.state)

        // The server tells both ends; the receiver's copy must not tick our
        // own screen for their reading.
        assertNull(
            readReceiptFrom(
                Json.parseToJsonElement(
                    """{"detail":{"sender":"alice","receiver":"me","status":3}}""",
                ),
                myUserId = "me",
            ),
        )
    }

    @Test
    fun `my own read on another device names the conversation to clear`() {
        // DM: I read alice's messages on my phone — desktop clears alice.
        assertEquals(
            "alice",
            com.zillit.desktop.feature.chat.data.selfReadFrom(
                Json.parseToJsonElement(
                    """{"detail":{"sender":"alice","receiver":"me","status":3}}""",
                ),
                myUserId = "me",
            ),
        )
        // Group: the room id is the conversation, and the reader must be me.
        assertEquals(
            "room-1",
            com.zillit.desktop.feature.chat.data.selfReadFrom(
                Json.parseToJsonElement(
                    """{"detail":{"room_id":"room-1","user_id":"me","status":3}}""",
                ),
                myUserId = "me",
            ),
        )
    }

    @Test
    fun `other people's reads clear nothing here`() {
        // The peer reading MY messages is a receipt, not my own read.
        assertNull(
            com.zillit.desktop.feature.chat.data.selfReadFrom(
                Json.parseToJsonElement(
                    """{"detail":{"sender":"me","receiver":"alice","status":3}}""",
                ),
                myUserId = "me",
            ),
        )
        // Someone else reading the group room is their state, not mine.
        assertNull(
            com.zillit.desktop.feature.chat.data.selfReadFrom(
                Json.parseToJsonElement(
                    """{"detail":{"room_id":"room-1","user_id":"bob","status":3}}""",
                ),
                myUserId = "me",
            ),
        )
        // Delivery ticks below status 3 are not reads at all.
        assertNull(
            com.zillit.desktop.feature.chat.data.selfReadFrom(
                Json.parseToJsonElement(
                    """{"detail":{"sender":"alice","receiver":"me","status":2}}""",
                ),
                myUserId = "me",
            ),
        )
    }

    /**
     * The frame names the messages its read covered — iOS flips its ledger
     * rows by them (`ChatObserver.swift:321-345`), and a lone id arrives as
     * a bare string now and then (`ChattingApiModel.swift:698-706`).
     */
    @Test
    fun `my DM read elsewhere carries the message ids it read`() {
        val read = selfReadOf(
            Json.parseToJsonElement(
                """{"detail":{"sender":"alice","receiver":"me","status":3,"project_id":"p-1",
                   "chat_message_ids":["m-1","m-2",""]}}""",
            ),
            myUserId = "me",
            isGroup = false,
        )
        assertEquals(SelfRead("alice", setOf("m-1", "m-2"), isGroup = false, projectId = "p-1"), read)

        val lone = selfReadOf(
            Json.parseToJsonElement(
                """{"detail":{"sender":"alice","receiver":"me","status":3,"chat_message_ids":"m-9"}}""",
            ),
            myUserId = "me",
            isGroup = false,
        )
        assertEquals(setOf("m-9"), lone?.messageIds)
    }

    /**
     * A room's frame in the phones' shape: the room under `receiver`, the
     * reader under `emitted_sender`, and the reader's rung in
     * `emitted_status` (Android `ChatSocketHelper.kt:1396`, iOS
     * `ChatObserver.swift:368`) — its `status` is not the reader's.
     */
    @Test
    fun `a room's read is judged by what the reader emitted`() {
        val read = selfReadOf(
            Json.parseToJsonElement(
                """{"detail":{"receiver":"room-1","sender":"bob","emitted_sender":"me","status":2,
                   "emitted_status":3,"chat_message_ids":["m-4"]}}""",
            ),
            myUserId = "me",
            isGroup = true,
        )
        assertEquals(SelfRead("room-1", setOf("m-4"), isGroup = true), read)

        // Delivered by me, whatever the room's own status says, is no read.
        assertNull(
            selfReadOf(
                Json.parseToJsonElement(
                    """{"detail":{"room_id":"room-1","user_id":"me","status":3,"emitted_status":2}}""",
                ),
                myUserId = "me",
                isGroup = true,
            ),
        )
    }

    /**
     * iOS clears a room on anybody's read (`ChatObserver.swift:368-381` never
     * looks at the reader). That is the reader's state, not mine.
     */
    @Test
    fun `somebody else's read of a room clears nothing here, ids or not`() {
        assertNull(
            selfReadOf(
                Json.parseToJsonElement(
                    """{"detail":{"receiver":"room-1","emitted_sender":"bob","emitted_status":3,
                       "chat_message_ids":["m-4"]}}""",
                ),
                myUserId = "me",
                isGroup = true,
            ),
        )
        // A room frame that names no reader at all is nobody's read.
        assertNull(
            selfReadOf(
                Json.parseToJsonElement("""{"detail":{"receiver":"room-1","emitted_status":3}}"""),
                myUserId = "me",
                isGroup = true,
            ),
        )
    }

    @Test
    fun `the warm ask names the conversation, one row, previous`() {
        val envelope = lastMessagesEnvelope(
            projectId = "p-1",
            senderId = "me",
            receiverId = "u-a",
            nowMillis = 1_700_000_000_000,
        )
        assertEquals("p-1", envelope["project_id"]?.jsonPrimitive?.content)
        assertEquals("me", envelope["sender"]?.jsonPrimitive?.content)
        assertEquals("u-a", envelope["receiver"]?.jsonPrimitive?.content)
        assertEquals("1700000000000", envelope["timestamp"]?.jsonPrimitive?.content)
        assertEquals("1", envelope["max_records"]?.jsonPrimitive?.content)
        assertEquals("previous", envelope["next_previous"]?.jsonPrimitive?.content)
        assertEquals("cnc_section", envelope["chat_tool"]?.jsonPrimitive?.content)
        // C&C sends neither; the budget tools send both, as their sends do.
        assertNull(envelope["department_id"])
        assertNull(envelope["budget_document_id"])
    }

    @Test
    fun `the warm ask's rows are read out of the ack's detail`() {
        // The socket acks under `detail`, where REST answers under `data`.
        val rows = chatRows(
            Json.parseToJsonElement(
                """{"success":true,"detail":{"chat_records":[
                   {"_id":"m1","sender":"alice","receiver":"me","message":"hex:late","created":300}]}}""",
            ),
        )
        assertEquals(1, rows.size)
        assertEquals("late", readChatMessage(rows.first(), myUserId = "me", decrypt = ::decrypt)?.body)
    }

    @Test
    fun `a tag's name rides in message_elements`() {
        val message = readChatMessage(
            Json.parseToJsonElement(
                """{"_id":"m9","sender":"alice","receiver":"room1","message":"hex:hi @{{67fcd7fc}}",
                   "message_elements":[{"search":"{{67fcd7fc}}","replacer":"Aisha Khan"},
                                       {"search":"group_name","replacer":"Unit A"},
                                       {"search":"{{blank99}}","replacer":"  "}]}""",
            ),
            myUserId = "bob",
            decrypt = ::decrypt,
            isGroup = true,
        )!!
        // A search that is not a {{tag}} is skipped; a blank name is no name.
        assertEquals(mapOf("67fcd7fc" to "Aisha Khan"), message.taggedNames)
    }
}
