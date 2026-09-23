package com.zillit.desktop.feature.accounthub.data

import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.core.forms.FormModule
import com.zillit.desktop.feature.accounthub.domain.HubArea
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Which hub area a frame reloads.
 *
 * The console had no subscription of any kind until 2026-09-09 — a vendor
 * verified by the production accountant, or an account code changed, stayed
 * invisible to everyone else's open hub. Both phones carry all seven names
 * (iOS `ProjectObserver.swift:7771-7774` plus the `vendor:*` block; Android
 * the same set), and the desktop already answers the `vendor:*` five inside
 * Invoices and Purchase Orders — the hub's own Vendors page was the one place
 * that did not.
 *
 * `trackingcodes:updated` / `:deleted` joined the list when the Layers tab
 * became editable — the chart page is where they render.
 */
internal val HUB_REFRESH_BY_EVENT: Map<SocketEventName, HubArea> = buildMap {
    put(SocketEventName("chartofaccounts:updated"), HubArea.ChartOfAccounts)
    put(SocketEventName("chartofaccounts:deleted"), HubArea.ChartOfAccounts)
    // The Layers tab edits tracking codes now, so the two names the phones
    // carry for them reload the chart page the tab lives on.
    put(SocketEventName("trackingcodes:updated"), HubArea.ChartOfAccounts)
    put(SocketEventName("trackingcodes:deleted"), HubArea.ChartOfAccounts)
    // Balance Sheet Codes are the chart's other tab; the web reloads on both.
    put(SocketEventName("balancesheetcode:updated"), HubArea.ChartOfAccounts)
    put(SocketEventName("balancesheetcode:deleted"), HubArea.ChartOfAccounts)
    listOf(
        "vendor:created",
        "vendor:updated",
        "vendor:deleted",
        "vendor:verified",
        "vendor:unverified",
    ).forEach { put(SocketEventName(it), HubArea.Vendors) }
    // Another accountant's chain edit. The web's `accountHubListeners` folds
    // all three into one `ah:approval_tier:list` refetch of the open module;
    // the page re-reads silently, and an open builder keeps its own edits.
    listOf(
        "approval_tier:configured",
        "approval_tier:updated",
        "approval_tier:deleted",
    ).forEach { put(SocketEventName(it), HubArea.Approvers) }
    // The project-settings document changed — another accountant's company,
    // currency or pay rule, or the deal wizard's inline "+ Add company". The
    // web re-pulls every setup slice on it (`accountHubListeners`); without
    // it a company created elsewhere stayed missing until the page reopened,
    // and the next whole-list company save deleted it.
    put(SocketEventName("production_setup:updated"), HubArea.ProductionSetup)
}

/**
 * The two frames that say a module's form has changed under us.
 *
 * Not in [HUB_REFRESH_BY_EVENT] because they do not name an area — they name a
 * *module*, and which one decides whether the open editor cares at all.
 */
internal val FORM_TEMPLATE_EVENTS: Set<SocketEventName> = setOf(
    SocketEventName("form_template:changed"),
    SocketEventName("form_template:reset"),
)

/**
 * Which module's form each frame is about, or nothing when it does not say.
 *
 * A frame with no module is dropped rather than guessed at: reloading the open
 * module on somebody else's edit to a different one would throw away unsaved
 * work for no reason.
 */
internal fun formTemplateRefreshes(bus: SocketEventBus?): Flow<FormModule> =
    bus?.onAny(FORM_TEMPLATE_EVENTS)
        ?.mapNotNull { message ->
            FormModule.from(
                (message.payload as? JsonObject)
                    ?.get("module")
                    ?.let { it as? JsonPrimitive }
                    ?.content,
            )
        }
        ?.conflate()
        ?: emptyFlow()

/**
 * The hub's refresh pulses for a bus, or empty without one.
 *
 * Conflated: importing a chart of accounts emits a frame per code, and one
 * reload answers the lot.
 */
internal fun hubRefreshes(bus: SocketEventBus?): Flow<HubArea> =
    bus?.onAny(HUB_REFRESH_BY_EVENT.keys)
        ?.mapNotNull { HUB_REFRESH_BY_EVENT[it.event] }
        ?.conflate()
        ?: emptyFlow()
