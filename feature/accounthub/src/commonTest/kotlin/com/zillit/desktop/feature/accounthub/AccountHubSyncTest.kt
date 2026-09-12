package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.accounthub.data.HUB_REFRESH_BY_EVENT
import com.zillit.desktop.feature.accounthub.domain.HubArea
import kotlin.test.Test
import kotlin.test.assertEquals

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

    /** The Layers tab lists tracking codes now, so their two verbs reload the chart. */
    @Test
    fun `tracking codes reload the chart's layers tab`() {
        assertEquals(HubArea.ChartOfAccounts, area("trackingcodes:updated"))
        assertEquals(HubArea.ChartOfAccounts, area("trackingcodes:deleted"))
    }

    /**
     * Another accountant's chain edit reloads the Approvers page — the web's
     * `approval_tier:*` handlers, which all refetch the open module.
     */
    @Test
    fun `every approval tier verb reloads the approvers page`() {
        listOf(
            "approval_tier:configured",
            "approval_tier:updated",
            "approval_tier:deleted",
        ).forEach { assertEquals(HubArea.Approvers, area(it), it) }
    }

    /** Setup has no event of its own on this wire. */
    @Test
    fun `only the three areas the wire announces are followed`() {
        assertEquals(
            setOf(HubArea.ChartOfAccounts, HubArea.Vendors, HubArea.Approvers),
            HUB_REFRESH_BY_EVENT.values.toSet(),
        )
    }
}
