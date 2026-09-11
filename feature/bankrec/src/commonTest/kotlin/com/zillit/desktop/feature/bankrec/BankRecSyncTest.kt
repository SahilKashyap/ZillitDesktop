package com.zillit.desktop.feature.bankrec

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.bankrec.data.BANK_REFRESH_BY_EVENT
import com.zillit.desktop.feature.bankrec.data.BankRefresh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What each live frame makes stale.
 *
 * The fan-out is the service's own, not a guess, and getting it wrong is
 * invisible: the tab the user is not looking at goes quietly out of date, and
 * they act on figures that moved ten minutes ago.
 */
class BankRecSyncTest {

    private fun slices(event: String): Set<BankRefresh> =
        BANK_REFRESH_BY_EVENT[SocketEventName(event)].orEmpty()

    /**
     * Importing a statement produces four things at once.
     *
     * Transactions, exceptions, fraud alerts and FX variances all come out of
     * the same import, so all four slices go stale together.
     */
    @Test
    fun `a statement import invalidates everything it produces`() {
        assertEquals(
            setOf(
                BankRefresh.Periods,
                BankRefresh.Workspace,
                BankRefresh.Exceptions,
                BankRefresh.Fx,
                BankRefresh.Fraud,
            ),
            slices("br:statement:imported"),
        )
    }

    /**
     * Matching a line does more than match it.
     *
     * The service auto-accepts any pending fraud alert on the transaction and
     * usually clears an exception, so both of those tabs move too.
     */
    @Test
    fun `matching a line also clears its alert and its exception`() {
        val moved = slices("br:transaction:matched")

        assertTrue(BankRefresh.Workspace in moved)
        assertTrue(BankRefresh.Fraud in moved)
        assertTrue(BankRefresh.Exceptions in moved)
    }

    /**
     * Deleting a period takes everything it produced with it.
     *
     * And it writes a fraud audit entry, which is why fraud is in the list for
     * an event that has nothing obviously to do with it.
     */
    @Test
    fun `deleting a period invalidates every period-scoped slice`() {
        assertEquals(
            setOf(
                BankRefresh.Periods,
                BankRefresh.Workspace,
                BankRefresh.Exceptions,
                BankRefresh.Fraud,
            ),
            slices("br:period:deleted"),
        )
    }

    /** Changing the rules changes what the next match run does. */
    @Test
    fun `a rules change reaches the workspace as well as the rules page`() {
        assertEquals(setOf(BankRefresh.Rules, BankRefresh.Workspace), slices("br:rules:updated"))
    }

    /** Every fraud verb lands on the fraud tab and nowhere else. */
    @Test
    fun `the four fraud frames are all carried`() {
        listOf("created", "updated", "escalated", "dismissed").forEach { verb ->
            assertEquals(setOf(BankRefresh.Fraud), slices("br:fraud:$verb"), verb)
        }
    }

    /** So do the five a shared link can emit, including the two nobody triggers. */
    @Test
    fun `every portal-link frame is carried, viewed and expired included`() {
        listOf("created", "updated", "revoked", "viewed", "expired").forEach { verb ->
            assertEquals(setOf(BankRefresh.PortalLinks), slices("br:portalLink:$verb"), verb)
        }
    }

    /**
     * The account list is the hub's, and this module reads it.
     *
     * An account added in Production Setup has to appear here without a
     * reopen, so the hub's own three frames are carried too.
     */
    @Test
    fun `bank account changes from the hub reach this module`() {
        listOf("bank_account:added", "bank_account:default_changed", "bank_account:deleted")
            .forEach { event ->
                assertTrue(BankRefresh.Periods in slices(event), event)
            }
    }

    /** Both spellings of the re-run frame are carried; the service sends both. */
    @Test
    fun `both auto-match rerun frames are carried`() {
        assertEquals(setOf(BankRefresh.Workspace), slices("br:statement:automatch-rerun"))
        assertEquals(setOf(BankRefresh.Workspace), slices("br:automatch:rerun-completed"))
    }

    /** A frame this module does not model is dropped, not folded onto another. */
    @Test
    fun `an unknown frame invalidates nothing`() {
        assertTrue(slices("br:something:new").isEmpty())
    }
}
