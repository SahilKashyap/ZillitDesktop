package com.zillit.desktop.feature.cardexpenses.data

import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketEventName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

/**
 * Everything another client can do to a company card, its receipts or its
 * transactions — the 48 names both phones carry, in four sub-families
 * (`card:`, `card:receipt:`, `card:transaction:`, `card:alert:`).
 *
 * They are **not** mapped to individual pages, unlike every other sync in
 * this codebase, and that is deliberate. This tool has twenty pages whose
 * contents overlap: approving one receipt takes it out of Pending Coding and
 * out of the Approval Queue, puts it into History, and moves the Overview
 * totals. A name-to-page map would be forty-eight entries of guesswork, and
 * a wrong guess is a page that silently stops updating — the exact failure
 * this whole exercise is closing.
 *
 * So any of these reloads whichever page is open, and only that page. The
 * cost is one refetch of one list; the alternative is a stale approval queue,
 * which is what the tool exists to keep moving. Android arrives at the same
 * shape from the other side — each screen collects the flows for the lists it
 * shows and calls its own `load()`.
 *
 * The module subscribed to nothing at all before 2026-09-09.
 */
val CARD_SYNC_EVENTS: List<SocketEventName> = listOf(
    "card:activated", "card:alert:created",
    "card:alert:dismissed", "card:alert:investigated",
    "card:alert:resolved", "card:approval",
    "card:approved", "card:awaiting_approval",
    "card:created", "card:deleted",
    "card:import:processed", "card:overridden",
    "card:reactivated", "card:receipt:approved",
    "card:receipt:assigned", "card:receipt:coded",
    "card:receipt:coding_submitted", "card:receipt:confirmed",
    "card:receipt:deleted", "card:receipt:disputed",
    "card:receipt:duplicate_detected", "card:receipt:flagged_personal",
    "card:receipt:matched", "card:receipt:overridden",
    "card:receipt:posted", "card:receipt:rejected",
    "card:receipt:unmatched", "card:receipt:updated",
    "card:receipt:uploaded", "card:receipts:submitted",
    "card:rejected", "card:requested",
    "card:settings:updated", "card:suspended",
    "card:team:posting_rights_updated", "card:topup:history",
    "card:topup:needed", "card:transaction:created",
    "card:transaction:deleted", "card:transaction:posted",
    "card:transaction:queried", "card:transaction:reconciled",
    "card:transaction:rejected", "card:transaction:updated",
    "card:transactions:submitted_to_users", "card:transactions:submitted_to_users:accountants",
    "card:updated", "card:updated_by_accountant",
).map(::SocketEventName)

/**
 * One pulse per burst of card traffic, or empty without a bus.
 *
 * Conflated because a statement import emits a frame per transaction and a
 * bulk approval one per receipt; a single reload answers either.
 */
internal fun cardRefreshes(bus: SocketEventBus?): Flow<Unit> =
    bus?.onAny(CARD_SYNC_EVENTS)?.map { }?.conflate() ?: emptyFlow()
