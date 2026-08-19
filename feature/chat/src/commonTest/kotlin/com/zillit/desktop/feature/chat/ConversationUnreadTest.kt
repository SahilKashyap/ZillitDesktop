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
}
