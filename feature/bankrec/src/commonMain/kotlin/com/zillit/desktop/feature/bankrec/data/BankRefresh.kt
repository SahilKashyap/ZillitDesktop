package com.zillit.desktop.feature.bankrec.data

import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketEventName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

/** Which slice of the module a frame makes stale. */
enum class BankRefresh { Periods, Workspace, Exceptions, Fraud, Fx, PortalLinks, Rules }

/**
 * What each frame invalidates.
 *
 * Several frames touch more than one slice, and the fan-out is the service's,
 * not a guess: importing a statement produces transactions, exceptions, fraud
 * alerts and FX variances in one go; matching a line auto-accepts any pending
 * fraud alert on it and usually clears an exception; deleting a period takes
 * everything it produced with it and writes a fraud audit entry.
 */
internal val BANK_REFRESH_BY_EVENT: Map<SocketEventName, Set<BankRefresh>> = buildMap {
    put(
        SocketEventName("br:statement:imported"),
        setOf(BankRefresh.Periods, BankRefresh.Workspace, BankRefresh.Exceptions, BankRefresh.Fx, BankRefresh.Fraud),
    )
    put(SocketEventName("br:statement:automatch-rerun"), setOf(BankRefresh.Workspace))
    put(SocketEventName("br:automatch:rerun-completed"), setOf(BankRefresh.Workspace))
    put(
        SocketEventName("br:transaction:matched"),
        setOf(BankRefresh.Workspace, BankRefresh.Fraud, BankRefresh.Exceptions, BankRefresh.Periods),
    )

    put(
        SocketEventName("br:exception:status-changed"),
        setOf(BankRefresh.Exceptions, BankRefresh.Workspace),
    )

    listOf("br:fraud:created", "br:fraud:updated", "br:fraud:escalated", "br:fraud:dismissed")
        .forEach { put(SocketEventName(it), setOf(BankRefresh.Fraud)) }

    listOf("br:fxVariance:posted", "br:fxVariance:posted-bulk")
        .forEach { put(SocketEventName(it), setOf(BankRefresh.Fx)) }

    listOf(
        "br:portalLink:created",
        "br:portalLink:updated",
        "br:portalLink:revoked",
        "br:portalLink:viewed",
        "br:portalLink:expired",
    ).forEach { put(SocketEventName(it), setOf(BankRefresh.PortalLinks)) }

    put(SocketEventName("br:period:created"), setOf(BankRefresh.Periods))
    put(SocketEventName("br:period:updated"), setOf(BankRefresh.Periods))
    put(SocketEventName("br:period:ready-for-signoff"), setOf(BankRefresh.Periods))
    put(
        SocketEventName("br:period:signed-off"),
        setOf(BankRefresh.Periods, BankRefresh.Workspace),
    )
    put(
        SocketEventName("br:period:deleted"),
        setOf(BankRefresh.Periods, BankRefresh.Workspace, BankRefresh.Exceptions, BankRefresh.Fraud),
    )

    put(SocketEventName("br:rules:updated"), setOf(BankRefresh.Rules, BankRefresh.Workspace))

    // The account list is the hub's, and this module reads it: a bank account
    // added in Production Setup has to appear here without a reopen.
    listOf("bank_account:added", "bank_account:default_changed", "bank_account:deleted")
        .forEach { put(SocketEventName(it), setOf(BankRefresh.Periods)) }
}

/**
 * The module's refresh pulses for a bus, or empty without one.
 *
 * Conflated: importing a statement emits a frame per produced row, and one
 * reload answers the lot.
 */
internal fun bankRefreshes(bus: SocketEventBus?): Flow<Set<BankRefresh>> =
    bus?.onAny(BANK_REFRESH_BY_EVENT.keys)
        ?.map { BANK_REFRESH_BY_EVENT[it.event].orEmpty() }
        ?.conflate()
        ?: emptyFlow()
