package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.accounthub.data.HUB_REFRESH_BY_EVENT
import com.zillit.desktop.feature.accounthub.domain.HubArea
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The console had no realtime at all.
 *
 * A vendor verified by the production accountant, or an account code changed,
 * reached nobody else's open hub. The `vendor:*` five were already answered
 * inside Invoices and Purchase Orders — the hub's own Vendors page was the
 * one place that ignored them.
 */
class AccountHubSyncTest {

    private fun area(event: String) = HUB_REFRESH_BY_EVENT[SocketEventName(event)]

    @Test
    fun `an account code change reloads the chart`() {
        assertEquals(HubArea.ChartOfAccounts, area("chartofaccounts:updated"))
        assertEquals(HubArea.ChartOfAccounts, area("chartofaccounts:deleted"))
    }

    @Test
    fun `every vendor verb reloads the vendors page`() {
        listOf(
            "vendor:created",
            "vendor:updated",
            "vendor:deleted",
            "vendor:verified",
            "vendor:unverified",
        ).forEach { assertEquals(HubArea.Vendors, area(it), it) }
    }

    /** No tracking-codes page here, so there is nothing to reload. */
    @Test
    fun `tracking codes stay unsubscribed`() {
        assertNull(area("trackingcodes:updated"))
        assertNull(area("trackingcodes:deleted"))
    }

    /** Setup and Approvers have no event of their own on this wire. */
    @Test
    fun `only the two areas the wire announces are followed`() {
        assertEquals(
            setOf(HubArea.ChartOfAccounts, HubArea.Vendors),
            HUB_REFRESH_BY_EVENT.values.toSet(),
        )
    }
}
