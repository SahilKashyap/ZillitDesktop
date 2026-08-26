package com.zillit.desktop.feature.chat

import com.zillit.desktop.feature.chat.data.createRoomBody
import com.zillit.desktop.feature.chat.data.readUntillEnvelope
import com.zillit.desktop.feature.chat.data.sendEnvelope
import com.zillit.desktop.feature.chat.domain.ChatScope
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One socket, more than one surface.
 *
 * C&C and the budget tools share every endpoint and message shape; the tool
 * name is what separates their conversations. These pin both halves of that:
 * a budget message must carry its tool, and a C&C message must be byte-for-byte
 * what it was before the scope existed.
 */
class ChatScopeTest {

    /** The default is C&C, so nothing that already worked changes. */
    @Test
    fun `an unscoped envelope is still cnc_section`() {
        val envelope = sendEnvelope(
            projectId = "p1",
            uniqueId = "u1",
            senderId = "me",
            receiverId = "them",
            cipherBody = "cipher",
            nowMillis = 1,
            isGroup = false,
        )

        assertEquals("cnc_section", envelope["chat_tool"]?.jsonPrimitive?.content)
        assertFalse("department_id" in envelope, "C&C has no department to name")
    }

    /** A budget message names its tool and its department. */
    @Test
    fun `a budget envelope carries its tool and department`() {
        val envelope = sendEnvelope(
            projectId = "p1",
            uniqueId = "u1",
            senderId = "me",
            receiverId = "room-1",
            cipherBody = "cipher",
            nowMillis = 1,
            isGroup = true,
            tool = "department_budget_tool",
            departmentId = "d9",
        )

        assertEquals("department_budget_tool", envelope["chat_tool"]?.jsonPrimitive?.content)
        assertEquals("d9", envelope["department_id"]?.jsonPrimitive?.content)
    }

    /**
     * A budget message names its document, and says `main_budget_tool` even
     * in a department budget's room.
     *
     * Both are the server's rules rather than tidy ones: it refuses
     * `department_budget_tool` on a message outright, and refuses a budget
     * message with no `budget_document_id` — complaining, in both cases,
     * `cnc_invalid_chat_tool`, which names the wrong field entirely.
     */
    @Test
    fun `a budget message names its document and the main tool`() {
        val envelope = sendEnvelope(
            projectId = "p1",
            uniqueId = "u1",
            senderId = "me",
            receiverId = "room-1",
            cipherBody = "cipher",
            nowMillis = 1,
            isGroup = true,
            tool = "main_budget_tool",
            departmentId = "d9",
            budgetDocumentId = "b1",
        )

        assertEquals("main_budget_tool", envelope["chat_tool"]?.jsonPrimitive?.content)
        assertEquals("b1", envelope["budget_document_id"]?.jsonPrimitive?.content)
    }

    /** The room tool and the message tool are separate words for a reason. */
    @Test
    fun `a scope keeps its room tool apart from its message tool`() {
        val scope = ChatScope(
            tool = "department_budget_tool",
            departmentId = "d9",
            budgetDocumentId = "b1",
            messageTool = "main_budget_tool",
        )

        assertEquals("department_budget_tool", scope.tool)
        assertEquals("main_budget_tool", scope.messageTool)
        assertEquals("cnc_section", ChatScope().messageTool, "C&C says one word for both")
    }

    /** Receipts are scoped too, or a budget read clears a C&C badge. */
    @Test
    fun `a read receipt carries the tool it belongs to`() {
        val receipt = readUntillEnvelope("them", "m1", "p1", status = 3, tool = "main_budget_tool")

        assertEquals("main_budget_tool", receipt["chat_tool"]?.jsonPrimitive?.content)
    }

    /** A room created for a budget belongs to that budget, not to C&C. */
    @Test
    fun `a budget room is created against its own tool`() {
        val body = createRoomBody(
            name = "Camera budget",
            ownerId = "me",
            memberIds = listOf("u1"),
            scope = ChatScope(tool = "department_budget_tool", departmentId = "d9", budgetDocumentId = "b1"),
        )

        assertEquals("department_budget_tool", body["room_tool"]?.jsonPrimitive?.content)
        assertEquals("d9", body["department_id"]?.jsonPrimitive?.content)
        assertEquals("b1", body["budget_document_id"]?.jsonPrimitive?.content)
    }

    /** A C&C room still sends neither field, as Android omits its defaults. */
    @Test
    fun `a cnc room names no department`() {
        val body = createRoomBody("Camera", "me", listOf("u1"))

        assertEquals("cnc_section", body["room_tool"]?.jsonPrimitive?.content)
        assertFalse("department_id" in body)
        assertFalse("budget_document_id" in body)
    }

    /** Reads carry the department even when it is empty — absent is not empty here. */
    @Test
    fun `message parameters send all three`() {
        val cnc = ChatScope().messageParameters()
        val budget = ChatScope("main_budget_tool", "d9", "b1").messageParameters()

        assertEquals("cnc_section", cnc["tool"])
        assertTrue("department_id" in cnc, "absent is not the same as empty to this server")
        assertEquals("b1", budget["budget_document_id"])
        assertFalse("budget_document_id" in cnc)
    }

    /**
     * Listing rooms never names a document.
     *
     * A room belongs to a department, not to one budget file; asking for one
     * document's rooms would hide every other room in that department, and
     * the web's own `getChatList` sends only the two.
     */
    @Test
    fun `listing rooms names no document`() {
        val budget = ChatScope("department_budget_tool", "d9", "b1").listingParameters()

        assertEquals("department_budget_tool", budget["tool"])
        assertEquals("d9", budget["department_id"])
        assertFalse("budget_document_id" in budget)
    }

    /**
     * The budget surface is a `budget:`-prefixed mirror of every C&C event.
     *
     * Sending a budget message on C&C's own `group_chat` is refused
     * `cnc_invalid_chat_tool` (live, 2026-08-26) — and listening on C&C's
     * events would never hear a budget room at all.
     */
    @Test
    fun `a budget scope renames every event`() {
        val budget = ChatScope(tool = "department_budget_tool", eventPrefix = "budget:")

        assertEquals("budget:group_chat", budget.event("group_chat"))
        assertEquals("budget:private_chat_message_read_untill", budget.event("private_chat_message_read_untill"))
        assertEquals("budget:private-chat:typing", budget.event("private-chat:typing"))
    }

    /** C&C keeps every event name it always had. */
    @Test
    fun `cnc events are untouched`() {
        assertEquals("group_chat", ChatScope().event("group_chat"))
        assertEquals("private_chat", ChatScope().event("private_chat"))
    }

    /** Two scopes must never share one cached answer. */
    @Test
    fun `each scope caches under its own key`() {
        assertEquals("cnc_section", ChatScope().cacheKey())
        assertEquals("main_budget_tool-d9", ChatScope("main_budget_tool", "d9").cacheKey())
    }
}
