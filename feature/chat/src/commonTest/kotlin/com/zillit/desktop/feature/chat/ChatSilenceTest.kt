package com.zillit.desktop.feature.chat

import com.zillit.desktop.feature.chat.data.chatSilenceFrom
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatSilenceTest {
    @Test
    fun `rooms lost and messages deleted are read from the record's reference data`() {
        val payload = Json.parseToJsonElement(
            """{"data":{"silent":true,"reference_data":{"chat_room_no_access":["r1","r2"],"deleted_chat_ids":["m9"],
               "tools_no_view_access":["budget_label"]}}}""",
        )
        val silence = chatSilenceFrom(payload)!!
        assertEquals(setOf("r1", "r2"), silence.rooms)
        assertEquals(setOf("m9"), silence.deletedMessageIds)
    }

    @Test
    fun `a data string and a top-level record both unwrap`() {
        val asString = Json.parseToJsonElement("""{"data":"{\"reference_data\":{\"chat_room_no_access\":[\"r7\"]}}"}""")
        assertEquals(setOf("r7"), chatSilenceFrom(asString)!!.rooms)
        val bare = Json.parseToJsonElement("""{"deleted_chat_ids":["m1","m2"]}""")
        assertEquals(setOf("m1", "m2"), chatSilenceFrom(bare)!!.deletedMessageIds)
    }

    @Test
    fun `a silence about tools only is nothing to chat`() {
        val payload = Json.parseToJsonElement("""{"reference_data":{"tools_no_view_access":["budget_label"]}}""")
        assertNull(chatSilenceFrom(payload))
        assertNull(chatSilenceFrom(null))
    }
}
