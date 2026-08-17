package com.zillit.desktop.feature.chat

import com.zillit.desktop.feature.chat.data.deleteEnvelope
import com.zillit.desktop.feature.chat.data.deletedIdsFrom
import com.zillit.desktop.feature.chat.domain.ChatMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Withdrawing a message, on the wire and in the thread.
 *
 * The emit is `{message_ids, project_id}` and the answer's rows hide in
 * `detail` — both transcribed from the web's `cncEmit.deletePrivateChat` and
 * the listener it pairs with. What matters most is the guard: a bubble whose
 * server id has not arrived yet must not offer deletion, because asking the
 * server to delete an id it never issued is a refusal, not a removal.
 */
class ChatDeleteTest {

    // -- the envelope ---------------------------------------------------------

    @Test
    fun `the emit carries the ids and the production`() {
        val envelope = deleteEnvelope(listOf("m1", "m2"), "project-9")

        assertEquals(
            listOf("m1", "m2"),
            envelope["message_ids"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("project-9", envelope["project_id"]!!.jsonPrimitive.content)
    }

    // -- the answer, in its shapes -------------------------------------------

    @Test
    fun `the ack wraps deleted rows in detail`() {
        val ack = Json.parseToJsonElement(
            """{"success":true,"detail":[{"_id":"m1"},{"_id":"m2"}]}""",
        )
        assertEquals(listOf("m1", "m2"), deletedIdsFrom(ack))
    }

    @Test
    fun `the broadcast may arrive bare`() {
        val broadcast = Json.parseToJsonElement("""[{"_id":"m3"}]""")
        assertEquals(listOf("m3"), deletedIdsFrom(broadcast))
    }

    @Test
    fun `rows without ids are skipped, not thrown`() {
        val ack = Json.parseToJsonElement(
            """{"detail":[{"_id":"m1"},{"body":"orphan"},{"message_id":"m2"}]}""",
        )
        assertEquals(listOf("m1", "m2"), deletedIdsFrom(ack))
    }

    @Test
    fun `an answer naming nothing deletes nothing`() {
        assertTrue(deletedIdsFrom(Json.parseToJsonElement("""{"success":true}""")).isEmpty())
        assertTrue(deletedIdsFrom(Json.parseToJsonElement(""""just a string"""")).isEmpty())
    }

    // -- the guard ------------------------------------------------------------

    @Test
    fun `only rows the server can address are deletable`() {
        fun message(id: String, uniqueId: String, mine: Boolean) = ChatMessage(
            id = id,
            uniqueId = uniqueId,
            senderId = if (mine) "me" else "them",
            receiverId = if (mine) "them" else "me",
            body = "x",
            timestampMillis = 1L,
            isMine = mine,
        )

        // A history row: the server issued its id — deletable.
        assertTrue(message("server-1", "unique-1", mine = true).isDeletableForTest())
        // A just-sent row still carries its local unique id — not yet.
        assertTrue(!message("unique-2", "unique-2", mine = true).isDeletableForTest())
        // Someone else's row is never ours to withdraw.
        assertTrue(!message("server-3", "unique-3", mine = false).isDeletableForTest())
    }
}

/** The screen's rule, restated here so a change to it must touch this test. */
private fun ChatMessage.isDeletableForTest(): Boolean = isMine && id != uniqueId
