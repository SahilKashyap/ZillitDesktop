package com.zillit.desktop.feature.chat

import com.zillit.desktop.feature.chat.data.conversationBacklogFrom
import com.zillit.desktop.feature.chat.data.conversationUnreadFrom
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** The notification backlog folded into a badge per DM peer and per room. */
class ConversationUnreadTest {

    @Test
    fun `chat rows count per sender or room, everything else is left alone`() {
        val payload = Json.parseToJsonElement(
            """
            [
              {"section":"cnc_label","tool":"chat_label","unit":"chat_member_label","sender":"u1",
               "reference_data":{"sender_id":"u1"},"message_read":false},
              {"section":"cnc_label","tool":"chat_label","unit":"chat_member_label","sender":"u1",
               "reference_data":{"sender_id":"u1"},"message_read":false},
              {"section":"cnc_label","tool":"chat_label","unit":"chat_member_label","sender":"u2",
               "reference_data":{"sender_id":"u2"},"message_read":true},
              {"section":"cnc_label","tool":"chat_label","unit":"chat_group_label","sender":"u3",
               "reference_data":{"sender_id":"u3","chat_room_id":"room9"},"message_read":false},
              {"section":"cnc_label","tool":"call_label","unit":"x","sender":"u4","message_read":false},
              {"section":"home_label","tool":"home_label","unit":"x","sender":"u5","message_read":false},
              {"section":"cnc_label","tool":"chat_label","unit":"chat_member_label","sender":"u6",
               "reference_data":{"sender_id":"u6","self":true},"message_read":false},
              {"section":"cnc_label","tool":"chat_label","unit":"chat_member_label","sender":"u7",
               "reference_data":{"sender_id":"u7"},"message_read":false,"deleted":1720000000000}
            ]
            """.trimIndent(),
        )
        assertEquals(mapOf("u1" to 2, "room9" to 1), conversationUnreadFrom(payload))
    }

    /**
     * The stamps come from every chat row — read, unread, our own echo —
     * because a thread moved whichever of those the newest row is; only the
     * counts are picky. A row that is not a chat row stamps nothing.
     */
    @Test
    fun `each conversation's newest created is its activity, counts stay strict`() {
        val payload = Json.parseToJsonElement(
            """
            [
              {"section":"cnc_label","tool":"chat_label","unit":"chat_member_label","sender":"u1",
               "reference_data":{"sender_id":"u1"},"message_read":false,"created":100},
              {"section":"cnc_label","tool":"chat_label","unit":"chat_member_label","sender":"u1",
               "reference_data":{"sender_id":"u1"},"message_read":true,"created":250},
              {"section":"cnc_label","tool":"chat_label","unit":"chat_member_label","sender":"u2",
               "reference_data":{"sender_id":"u2","self":true},"message_read":false,"created":"300"},
              {"section":"cnc_label","tool":"chat_label","unit":"chat_group_label","sender":"u3",
               "reference_data":{"sender_id":"u3","chat_room_id":"room9"},"message_read":false,"created":50},
              {"section":"cnc_label","tool":"call_label","unit":"x","sender":"u4","message_read":false,"created":999},
              {"section":"cnc_label","tool":"chat_label","unit":"chat_member_label","sender":"u5",
               "reference_data":{"sender_id":"u5"},"message_read":false,"created":700,"deleted":1720000000000}
            ]
            """.trimIndent(),
        )
        val backlog = conversationBacklogFrom(payload)
        assertEquals(mapOf("u1" to 1, "room9" to 1), backlog.unread)
        assertEquals(mapOf("u1" to 250L, "u2" to 300L, "room9" to 50L), backlog.activity)
    }

    @Test
    fun `an enveloped answer reads its data array`() {
        val payload = Json.parseToJsonElement(
            """{"status":1,"data":[{"section":"cnc_label","tool":"chat_label","unit":"chat_member_label",
               "sender":"u1","message_read":false}]}""",
        )
        assertEquals(mapOf("u1" to 1), conversationUnreadFrom(payload))
    }

    /** The server keeps rows the phones marked read long ago; the local read mark is the device's memory of that. */
    @Test
    fun `rows no newer than the local read mark do not count, and a silenced message never does`() {
        val payload = Json.parseToJsonElement(
            """
            [
              {"section":"cnc_label","tool":"chat_label","unit":"chat_member_label","sender":"u1","created":100,
               "reference_data":{"sender_id":"u1","chat_id":"m1"},"message_read":false},
              {"section":"cnc_label","tool":"chat_label","unit":"chat_member_label","sender":"u1","created":300,
               "reference_data":{"sender_id":"u1","chat_id":"m2"},"message_read":false},
              {"section":"cnc_label","tool":"chat_label","unit":"chat_group_label","sender":"u3","created":500,
               "reference_data":{"sender_id":"u3","chat_room_id":"room9","chat_id":"m3"},"message_read":false},
              {"section":"cnc_label","tool":"chat_label","unit":"chat_group_label","sender":"u3","created":600,
               "reference_data":{"sender_id":"u3","chat_room_id":"room9","chat_id":"m4"},"message_read":false}
            ]
            """.trimIndent(),
        )
        val backlog = conversationBacklogFrom(payload, readMarks = mapOf("u1" to 200L), silencedIds = setOf("m4"))
        assertEquals(mapOf("u1" to 1, "room9" to 1), backlog.unread, "m1 is older than the mark, m4 was silenced")
        assertEquals(mapOf("m2" to "u1", "m3" to "room9"), backlog.messageKeys)
        assertEquals(600L, backlog.activity["room9"], "activity still follows every row, read or not")
    }
}
