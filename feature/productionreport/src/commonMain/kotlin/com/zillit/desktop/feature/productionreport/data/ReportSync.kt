package com.zillit.desktop.feature.productionreport.data

import com.zillit.desktop.core.socket.SocketEventName
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The production-report WIRE events. These names are subscribed directly
 * on the socket — no re-emit alias in between — by the web v2 module's own
 * listener (`productionreportversion2/socket/productionReportListeners.js:
 * 30-38`, registered via `socket.on` at line 76);
 * `ProductionReportApp.jsx:958-1048` answers each with targeted list
 * reloads, removes a deleted previous report by id, and patches a carried
 * report in place first. This client's equivalent is one reload of
 * whatever list is on screen — the ViewModel's refresh already scopes to
 * the open destination and bucket.
 *
 * NOT here: the `production_report:message:*` family
 * (`pages/production_report/ProductionReport.jsx:205-380`) — that is the
 * LEGACY messaging page, a chat surface this module does not carry.
 */
val PRODUCTION_REPORT_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("productionreport:submit:for:approval"),
    SocketEventName("productionreport:approval:requested"),
    SocketEventName("productionreport:approval:approved"),
    SocketEventName("productionreport:approval:created"),
    SocketEventName("productionreport:approval:rejected"),
    SocketEventName("production_report:previous_report:deleted"),
    SocketEventName("productionreport:comment:created"),
)

/**
 * Lenient production guard. The web handler does not filter by project —
 * its loads are project-scoped anyway — so only a frame that explicitly
 * names ANOTHER project is dropped here; anything else passes.
 */
internal fun JsonElement?.matchesProject(here: String?): Boolean {
    if (here.isNullOrBlank()) return true
    val incoming = (this as? JsonObject)
        ?.let { frame -> frame["project_id"] ?: frame["projectId"] }
        ?.let { value -> (value as? JsonPrimitive)?.content }
        ?.takeIf { it.isNotBlank() }
        ?: return true
    return incoming == here
}
