package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.data.IN_CALL_KIND_MESSAGE
import com.zillit.desktop.feature.calls.data.IN_CALL_KIND_REACTION
import com.zillit.desktop.feature.calls.data.InCallData
import com.zillit.desktop.feature.calls.data.inCallDataEnvelope
import com.zillit.desktop.feature.calls.data.readInCallData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The relay envelope, which is the part with no second chance.
 *
 * `rooms` is how the CNC decides who hears a reaction or a line. Get it wrong
 * and there is no error anywhere — the send succeeds, the server addresses
 * nobody, and the feature is simply silent for everyone but the sender.
 */
class InCallDataTest {

    private val reaction = InCallData(
        roomId = "r1",
        kind = IN_CALL_KIND_REACTION,
        fromUserId = "me",
        name = "Vivek",
        emoji = "🎉",
        id = "dev-1000-0",
        atMillis = 1_000,
    )

    @Test
    fun `the envelope addresses each recipient by name`() {
        val envelope = inCallDataEnvelope("r1", reaction, listOf("alice", "bob"))

        assertEquals("call:incall-data", envelope["event"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("alice", "bob"),
            envelope["rooms"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `the payload carries the reaction the phones expect`() {
        val data = inCallDataEnvelope("r1", reaction, listOf("alice"))["eventData"]!!.jsonObject

        assertEquals(IN_CALL_KIND_REACTION, data["kind"]?.jsonPrimitive?.content)
        assertEquals("🎉", data["emoji"]?.jsonPrimitive?.content)
        assertEquals("r1", data["room_id"]?.jsonPrimitive?.content)
        assertEquals("me", data["from_user_id"]?.jsonPrimitive?.content)
        assertEquals("dev-1000-0", data["id"]?.jsonPrimitive?.content)
        // Absent, not empty: a reaction with a "" text field reads on the
        // other end as a message that happens to say nothing.
        assertTrue("text" !in data)
    }

    @Test
    fun `a message carries text and no emoji`() {
        val line = reaction.copy(kind = IN_CALL_KIND_MESSAGE, emoji = "", text = "two minutes")
        val data = inCallDataEnvelope("r1", line, listOf("alice"))["eventData"]!!.jsonObject

        assertEquals("two minutes", data["text"]?.jsonPrimitive?.content)
        assertTrue("emoji" !in data)
    }

    @Test
    fun `what one client sends the next one reads`() {
        val envelope = inCallDataEnvelope("r1", reaction, listOf("alice"))
        // The CNC relays the inner event verbatim, so eventData IS the payload
        // that arrives on the other side.
        val arrived = readInCallData(envelope["eventData"]!!)

        assertEquals(reaction, arrived)
    }

    @Test
    fun `a payload without a kind is not in-call data`() {
        // The relay carries whatever the server decides to put on this event;
        // anything unrecognised has to fall through rather than draw a blank
        // bubble on the stage.
        assertNull(readInCallData(Json.parseToJsonElement("""{"text":"hello"}""")))
    }
}
