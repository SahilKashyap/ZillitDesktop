package com.zillit.desktop.feature.budget

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.budget.data.BUDGET_SYNC_EVENTS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The budget documents list, kept live.
 *
 * The audit flagged fifteen `budget:*` events. Thirteen of them are the budget
 * *conversation* — the ordinary C&C thread with `ChatScope.eventPrefix =
 * "budget:"`, whose names `ChatRepository.scoped()` builds at runtime. They are
 * invisible to a literal grep and were never missing. These two are the
 * documents, and they genuinely were.
 */
class BudgetSyncTest {

    @Test
    fun `the documents list listens for its two`() {
        assertEquals(
            listOf("budget:saved", "budget:deleted"),
            BUDGET_SYNC_EVENTS.map(SocketEventName::value),
        )
    }

    @Test
    fun `the conversation family stays out of the documents subscription`() {
        // Subscribing here as well would give a budget thread two reload paths
        // — this list, and the chat repository's own scoped names.
        assertTrue(BUDGET_SYNC_EVENTS.map(SocketEventName::value).none { it.contains("chat") })
    }
}
