package com.zillit.desktop.feature.chat

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

    @Test
    fun `an enveloped answer reads its data array`() {
        val payload = Json.parseToJsonElement(
            """{"status":1,"data":[{"section":"cnc_label","tool":"chat_label","unit":"chat_member_label",
               "sender":"u1","message_read":false}]}""",
        )
        assertEquals(mapOf("u1" to 1), conversationUnreadFrom(payload))
    }
}
