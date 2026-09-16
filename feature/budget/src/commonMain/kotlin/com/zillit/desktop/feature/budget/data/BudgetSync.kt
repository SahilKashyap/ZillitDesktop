package com.zillit.desktop.feature.budget.data

import com.zillit.desktop.core.socket.SocketEventName

/**
 * Live updates for the budget documents list.
 *
 * Two events, and they are the *document* family — not the budget chat. All
 * three clients carry them: Android's `_budgetObserver`, iOS's
 * `.updateBudgetPage`, the web's project-gated `budget_saved` /
 * `budget_deleted` (`listenerSocket.js:1890-1897`). A department budget
 * uploaded by its HOD appears for the accountant watching the list.
 */
val BUDGET_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("budget:saved"),
    SocketEventName("budget:deleted"),
)

/**
 * Live updates for the conversation list beside a budget.
 *
 * The rooms of a budget come and go on their own `budget:`-prefixed events
 * (`listenerSocket.js:1060-1079` — "the budget chat group observers are
 * coming differently from cnc chat observers"), and a first private message
 * from someone new is a new row (`CommonBudget.jsx:'budget:private_chat'`).
 * The list is re-asked rather than patched: the ack is one socket round trip
 * and carries the names the payloads do not.
 */
val BUDGET_CHAT_LIST_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("budget:chat-room:create"),
    SocketEventName("budget:chat-room:remove"),
    SocketEventName("budget:chat-room:updated"),
    SocketEventName("budget:private_chat"),
)
