package com.zillit.desktop.feature.budget.data

import com.zillit.desktop.core.socket.SocketEventName

/**
 * Live updates for the budget documents list.
 *
 * Two events, and they are the *document* family — not the budget chat. All
 * three clients carry them: Android's `_budgetObserver`, iOS's
 * `.updateBudgetPage`, the web's project-gated `budget_saved` /
 * `budget_deleted`. The desktop carried neither (audited 2026-09-07), so a
 * department budget uploaded by its HOD did not appear for the accountant
 * watching the list.
 *
 * The `budget:*chat*` family is **not** missing and is deliberately not here:
 * a budget conversation is the ordinary C&C thread with
 * `ChatScope.eventPrefix = "budget:"`, so `ChatRepository.scoped()` already
 * builds `budget:private_chat` and its nine siblings at runtime. They look
 * absent to any audit that greps for literals — which is exactly how they were
 * flagged — but they are subscribed.
 */
val BUDGET_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("budget:saved"),
    SocketEventName("budget:deleted"),
)
