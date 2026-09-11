package com.zillit.desktop.feature.saportal.data

import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.saportal.domain.SaRefresh
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.mapNotNull

/**
 * Which page of the artiste's portal a frame reloads.
 *
 * Like the AD dashboard beside it, this module had no subscription of any
 * kind until 2026-09-09: a reply to a query, a voucher signed off by the AD,
 * a day added — none of it reached the artiste's screen until they reopened
 * the page. The portal is the one surface where the person watching cannot
 * fix a stale view by doing the work themselves.
 *
 * Two families, and the verb differs between them for the same thing: the
 * artiste's own side says `sa:query:received` where the AD side says
 * `ad_dashboard:supporting_artiste:query:replied`. Both are here, because the
 * Queries page shows one conversation and either end can move it — which is
 * exactly what Android found and fixed on its own AD screen after a user
 * report (`AdQueriesActivity.kt:73-82`).
 */
internal val SA_REFRESH_BY_EVENT: Map<SocketEventName, SaRefresh> = buildMap {
    // The artiste record itself: their details page, and the name and status
    // the vouchers list draws from it.
    listOf(
        "sa:artiste:added",
        "sa:artiste:updated",
        "sa:artiste:deleted",
    ).forEach { put(SocketEventName(it), SaRefresh.Profile) }

    put(SocketEventName("sa:voucher:updated"), SaRefresh.Vouchers)

    listOf(
        "sa:query:opened",
        "sa:query:received",
        "sa:query:resolved",
        "ad_dashboard:supporting_artiste:query:opened",
        "ad_dashboard:supporting_artiste:query:replied",
        "ad_dashboard:supporting_artiste:query:resolved",
    ).forEach { put(SocketEventName(it), SaRefresh.Queries) }
}

/**
 * The portal's refresh pulses for a bus, or empty without one.
 *
 * Conflated: an AD settling a day emits a voucher frame per artiste and one
 * reload answers them all.
 */
internal fun saRefreshes(bus: SocketEventBus?): Flow<SaRefresh> =
    bus?.onAny(SA_REFRESH_BY_EVENT.keys)
        ?.mapNotNull { SA_REFRESH_BY_EVENT[it.event] }
        ?.conflate()
        ?: emptyFlow()
