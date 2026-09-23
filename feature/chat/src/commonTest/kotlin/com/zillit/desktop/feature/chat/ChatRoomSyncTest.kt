package com.zillit.desktop.feature.chat

import com.zillit.desktop.feature.chat.data.CHAT_ROOM_SYNC_EVENTS
import com.zillit.desktop.feature.chat.data.GROUP_TYPING
import com.zillit.desktop.feature.chat.data.TYPING
import com.zillit.desktop.feature.chat.data.TypingSignal
import com.zillit.desktop.feature.chat.data.typingFrom
import com.zillit.desktop.feature.chat.domain.ChatScope
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The room listing hears about rooms.
 *
 * All three names were declared and subscribed by nobody, so a group made or
 * left elsewhere did not reach this client until something unrelated re-read
 * the listing. These pin the set and the budget surface's spelling of it.
 */
class ChatRoomSyncTest {

    @Test
    fun `the listing follows every room lifecycle event`() {
        assertEquals(
            listOf("chat-room:create", "chat-room:remove", "chat-room:updated"),
            CHAT_ROOM_SYNC_EVENTS.map { it.value },
        )
    }

    @Test
    fun `a budget conversation hears its own prefixed rooms`() {
        val budget = ChatScope(tool = "department_budget_tool", eventPrefix = "budget:")

        assertEquals(
            listOf("budget:chat-room:create", "budget:chat-room:remove", "budget:chat-room:updated"),
            CHAT_ROOM_SYNC_EVENTS.map { budget.event(it.value) },
        )
    }

    /** C&C keeps the bare names, so nothing that already worked moves. */
    @Test
    fun `cnc rooms keep the unprefixed names`() {
        assertEquals(
            CHAT_ROOM_SYNC_EVENTS.map { it.value },
            CHAT_ROOM_SYNC_EVENTS.map { ChatScope().event(it.value) },
        )
    }
}

/**
 * Typing has two names, and the desktop only ever used one.
 *
 * Android chooses `group-chat:typing` or `private-chat:typing` from the
 * conversation on both the emit and the listen. These pin which id each
 * flavour reports, and that a group does not echo our own keystrokes back.
 */
class ChatTypingSplitTest {

    @Test
    fun `a dm reports the person who typed`() {
        val payload = Json.parseToJsonElement("""{"detail":{"sender":"u1","receiver":"me","status":"start"}}""")

        assertEquals(TypingSignal("u1", typistId = "u1", started = true), typingFrom(payload))
    }

    @Test
    fun `a group reports the room, and who in it is typing`() {
        val payload = Json.parseToJsonElement("""{"detail":{"sender":"u1","receiver":"room-9","status":"start"}}""")

        // The typist travels too: the thread names them instead of "Someone".
        assertEquals(
            TypingSignal("room-9", typistId = "u1", started = true),
            typingFrom(payload, isGroup = true, myUserId = "me"),
        )
    }

    /** The room broadcasts back to its author, so our own typing is dropped. */
    @Test
    fun `our own keystrokes in a group are ignored`() {
        val payload = Json.parseToJsonElement("""{"detail":{"sender":"me","receiver":"room-9","status":"start"}}""")

        assertNull(typingFrom(payload, isGroup = true, myUserId = "me"))
    }

    @Test
    fun `stopping is reported as stopped`() {
        val payload = Json.parseToJsonElement("""{"sender":"u1","receiver":"room-9","status":"end"}""")

        assertEquals(
            TypingSignal("room-9", typistId = "u1", started = false),
            typingFrom(payload, isGroup = true, myUserId = "me"),
        )
    }

    /** The two names are separate, and a budget group scopes both. */
    @Test
    fun `each flavour keeps its own name`() {
        assertEquals("private-chat:typing", TYPING.value)
        assertEquals("group-chat:typing", GROUP_TYPING.value)
        assertEquals(
            "budget:group-chat:typing",
            ChatScope(tool = "main_budget_tool", eventPrefix = "budget:").event(GROUP_TYPING.value),
        )
    }
}
