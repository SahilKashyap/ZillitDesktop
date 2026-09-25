package com.zillit.desktop.feature.cardexpenses

import com.zillit.desktop.feature.cardexpenses.data.CARD_SYNC_EVENTS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The card tool listened to nothing, so an approval queue somebody else was
 * working stayed as it was found.
 *
 * The set is coarse on purpose — see the list's own documentation — so what
 * is worth pinning is coverage of every sub-family and the absence of
 * duplicates or foreign names.
 */
class CardSyncTest {

    private val names = CARD_SYNC_EVENTS.map { it.value }

    @Test
    fun `all four sub-families are covered`() {
        assertTrue(names.any { it.startsWith("card:receipt:") }, "receipts")
        assertTrue(names.any { it.startsWith("card:transaction:") }, "transactions")
        assertTrue(names.any { it.startsWith("card:alert:") }, "smart alerts")
        assertTrue(names.any { it.count { c -> c == ':' } == 1 }, "the card itself")
    }

    /** The verbs a queue actually waits on. */
    @Test
    fun `the approval and coding verbs are all here`() {
        listOf(
            "card:receipt:approved",
            "card:receipt:rejected",
            "card:receipt:coded",
            "card:receipt:coding_submitted",
            "card:receipt:posted",
            "card:transaction:reconciled",
            "card:import:processed",
        ).forEach { assertTrue(it in names, "$it must be subscribed") }
    }

    /** The web refetches Top-Up To Do on all five (`accountHubListeners.js:1535-1549`). */
    @Test
    fun `every top-up verb the web listens to is here`() {
        listOf(
            "card:topup:needed",
            "card:topup:history",
            "card:topup:completed",
            "card:topup:partial",
            "card:topup:skipped",
            "card:receipt:awaiting_approval",
            "card:receipt:escalated",
        ).forEach { assertTrue(it in names, "$it must be subscribed") }
    }

    @Test
    fun `every name is a card event, spelt once`() {
        assertEquals(names.size, names.toSet().size, "no duplicate subscriptions")
        assertTrue(names.all { it.startsWith("card:") })
        assertTrue(names.none { it.contains(' ') })
    }
}
