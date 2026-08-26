package com.zillit.desktop.feature.chat.domain

/**
 * Which conversation surface a chat belongs to.
 *
 * The same socket, the same endpoints and the same message shapes serve more
 * than one tool: C&C is `cnc_section`, and the budget tools hang their
 * discussions off `main_budget_tool` / `department_budget_tool` scoped to a
 * department and (optionally) one budget document — the web's
 * `budgetApi/api.js:53-80` sends exactly these three as query parameters, and
 * its emits carry the tool in the envelope.
 *
 * Every value here was a hard-coded `"cnc_section"` before, in eight places.
 * The default keeps C&C behaving precisely as it did.
 */
data class ChatScope(
    /** The wire's `chat_tool` / `room_tool` / `tool` — all the same word. */
    val tool: String = CNC,
    /** Empty for C&C. The server distinguishes absent from empty, so it is always sent. */
    val departmentId: String = "",
    /** Only the budget tools use this; empty is omitted rather than sent blank. */
    val budgetDocumentId: String = "",
    /**
     * What a *message* calls its tool, which is not always what a room calls
     * its own.
     *
     * The budget tools are asymmetric: rooms are listed and created under
     * `department_budget_tool`, but every message the web sends — from all
     * three of its send paths (`ChatFooterBudget.jsx:177,454,498`) — carries
     * `chat_tool: 'main_budget_tool'`, with the department named separately.
     * Sending `department_budget_tool` on a message is refused outright
     * (`cnc_invalid_chat_tool`, live 2026-08-26).
     */
    val messageTool: String = tool,
    /**
     * What every socket event of this surface is called.
     *
     * The budget tools are not C&C with different fields — they are a
     * `budget:`-prefixed mirror of the whole event set: `budget:group_chat`,
     * `budget:private_chat_message_read_untill`,
     * `budget:private-chat:typing`, `budget:group-chat:edit`, and so on
     * (`src/socket/cncEmit.js`, `listenerSocket.js`). Sending a budget
     * message on C&C's own `group_chat` is refused `cnc_invalid_chat_tool`,
     * and listening on C&C's would never hear a budget room at all.
     */
    val eventPrefix: String = "",
) {

    /** This surface's name for a C&C event. */
    fun event(name: String): String = eventPrefix + name

    /**
     * Listing rooms: the tool and the department, and deliberately *not* the
     * document. The web's `getChatList` sends exactly these two
     * (`budgetApi/api.js:82-88`) — a room belongs to a department, and asking
     * for one document's rooms would hide the rest.
     */
    fun listingParameters(): Map<String, Any?> = buildMap {
        put("tool", tool)
        put("department_id", departmentId)
    }

    /**
     * Reading messages: all three. The document is what separates one
     * budget's discussion from another's inside the same department
     * (`budgetApi/api.js:53-68`), and is omitted rather than sent blank when
     * there is none.
     */
    fun messageParameters(): Map<String, Any?> = buildMap {
        putAll(listingParameters())
        if (budgetDocumentId.isNotBlank()) put("budget_document_id", budgetDocumentId)
    }

    /** A cache key fragment, so two scopes never share one saved answer. */
    fun cacheKey(): String = listOf(tool, departmentId, budgetDocumentId)
        .filter { it.isNotBlank() }
        .joinToString("-")

    companion object {
        const val CNC = "cnc_section"
    }
}
