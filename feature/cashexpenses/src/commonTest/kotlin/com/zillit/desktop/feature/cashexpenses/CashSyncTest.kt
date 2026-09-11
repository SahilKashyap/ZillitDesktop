package com.zillit.desktop.feature.cashexpenses

import com.zillit.desktop.feature.cashexpenses.data.CASH_SYNC_EVENTS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Petty cash listened to nothing either, so a float issued to somebody
 * watching their own page never arrived there.
 */
class CashSyncTest {

    private val names = CASH_SYNC_EVENTS.map { it.value }

    @Test
    fun `all five sub-families are covered`() {
        listOf("cash:batch:", "cash:float:", "cash:recon:", "cash:topup:", "cash:settings:")
            .forEach { family -> assertTrue(names.any { it.startsWith(family) }, family) }
    }

    /**
     * The crew's own view of a float has its own suffixed names.
     *
     * `cash:float:issued` tells the accountant; `cash:float:issued:crew`
     * tells the person waiting for the money. Missing the second is the
     * failure that matters, because that reader cannot refresh by working.
     */
    @Test
    fun `the crew variants are subscribed beside the accounts ones`() {
        listOf("issued", "approved", "collected", "rejected", "queried", "closed").forEach { verb ->
            assertTrue("cash:float:$verb" in names, "cash:float:$verb")
            assertTrue("cash:float:$verb:crew" in names, "cash:float:$verb:crew")
        }
    }

    /** The escalation path a batch travels, end to end. */
    @Test
    fun `the batch lifecycle verbs are all here`() {
        listOf(
            "cash:batch:created",
            "cash:batch:submitted-for-coord",
            "cash:batch:escalated",
            "cash:batch:submitted-for-senior-review",
            "cash:batch:submitted-for-audit",
            "cash:batch:verified",
            "cash:batch:approved",
            "cash:batch:posted",
            "cash:batch:rejected",
        ).forEach { assertTrue(it in names, "$it must be subscribed") }
    }

    @Test
    fun `every name is a cash event, spelt once`() {
        assertEquals(names.size, names.toSet().size, "no duplicate subscriptions")
        assertTrue(names.all { it.startsWith("cash:") })
    }
}
