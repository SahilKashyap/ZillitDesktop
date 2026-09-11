package com.zillit.desktop.feature.saportal

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.saportal.data.SA_REFRESH_BY_EVENT
import com.zillit.desktop.feature.saportal.domain.SaRefresh
import com.zillit.desktop.feature.saportal.ui.SaDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The portal is the one surface whose reader cannot refresh it by working.
 *
 * An artiste waits for a reply and for the AD to settle their day; neither
 * reached the screen, because the module subscribed to nothing at all.
 */
class SaPortalSyncTest {

    private fun kind(event: String) = SA_REFRESH_BY_EVENT[SocketEventName(event)]

    @Test
    fun `a settled day reloads the artiste's days`() {
        assertEquals(SaRefresh.Vouchers, kind("sa:voucher:updated"))
    }

    @Test
    fun `the artiste's own record reloads their details`() {
        assertEquals(SaRefresh.Profile, kind("sa:artiste:added"))
        assertEquals(SaRefresh.Profile, kind("sa:artiste:updated"))
        assertEquals(SaRefresh.Profile, kind("sa:artiste:deleted"))
    }

    /**
     * One conversation, two vocabularies.
     *
     * The artiste's side says `received` where the AD's says `replied`, and
     * the Queries page shows the thread either end moves — the same both-ends
     * merge Android added to its AD screen after a user report.
     */
    @Test
    fun `a query moves the page from either end`() {
        listOf(
            "sa:query:opened",
            "sa:query:received",
            "sa:query:resolved",
            "ad_dashboard:supporting_artiste:query:opened",
            "ad_dashboard:supporting_artiste:query:replied",
            "ad_dashboard:supporting_artiste:query:resolved",
        ).forEach { assertEquals(SaRefresh.Queries, kind(it), it) }
    }

    /** The overview is built from the days, so it follows them. */
    @Test
    fun `the overview follows the vouchers`() {
        assertEquals(SaRefresh.Vouchers, SaDestination.Dashboard.refresh)
        assertEquals(SaRefresh.Vouchers, SaDestination.Vouchers.refresh)
        assertEquals(SaRefresh.Queries, SaDestination.Queries.refresh)
        assertEquals(SaRefresh.Profile, SaDestination.Profile.refresh)
    }

    /**
     * Pay has no event of its own.
     *
     * It is derived from settled days server-side, and nothing on this wire
     * announces it — so the page stays load-on-open rather than pretending to
     * follow a name that is not sent.
     */
    @Test
    fun `pay has no socket name to follow`() {
        assertEquals(SaRefresh.Pay, SaDestination.Pay.refresh)
        assertNull(SA_REFRESH_BY_EVENT.values.firstOrNull { it == SaRefresh.Pay })
    }
}
