package com.zillit.desktop.feature.productionreport.data

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.productionreport.domain.ReportStatus
import com.zillit.desktop.feature.productionreport.domain.ReportSyncEvent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The production-report WIRE events, subscribed directly on the socket by
 * the web module's own listener (`productionreportversion2/socket/
 * productionReportListeners.js:34-52`). `ProductionReportApp.jsx:1538-1662`
 * answers each with targeted list reloads; comment events also re-read an
 * open thread (`CommentsModal.jsx:132-144`).
 *
 * NOT here: the `production_report:message:*` family — the tool's unit chat,
 * which the board engine hosting the Chat workspace listens to itself.
 */
val PRODUCTION_REPORT_SYNC_EVENTS: List<SocketEventName> = listOf(
    "productionreport:submit:for:approval",
    "productionreport:approval:requested",
    "productionreport:approval:approved",
    "productionreport:approval:created",
    "productionreport:approval:rejected",
    // ZL-20707: a report out for approval was deleted or withdrawn.
    "productionreport:approval:voided",
    // Underscore spelling on the wire.
    "production_report:previous_report:deleted",
    // ZL-20678: the reminder bell needs a refetch of `reminders`.
    "productionreport:approval:reminder:sent",
    "productionreport:comment:created",
    // ZL-21388 / ZL-21389.
    "productionreport:comment:updated",
    "productionreport:comment:deleted",
).map(::SocketEventName)

/**
 * The report id and status a frame names. Payloads arrive wrapped in
 * `{data, message}` or bare, with the report nested or flat, and in either
 * key spelling.
 */
internal fun syncEventOf(name: String, payload: JsonElement?): ReportSyncEvent {
    val outer = payload as? JsonObject
    val frame = (outer?.get("data") as? JsonObject)?.takeIf { outer.containsKey("message") } ?: outer
    val report = (frame?.firstOf("production_report", "productionReport") as? JsonObject)
    val comment = frame?.firstOf("comment") as? JsonObject
    val reportId = listOfNotNull(
        report?.text("_id", "id"),
        frame?.text("production_report_id", "productionReportId", "productionreport_id", "productionreportId"),
        comment?.text("production_report_id", "productionReportId"),
        frame?.text("_id", "id")?.takeIf { report == null && comment == null },
    ).firstOrNull { it.isNotBlank() }
    val status = (frame?.text("status")?.takeIf { it.isNotBlank() } ?: report?.text("status"))
        ?.takeIf { it.isNotBlank() }
        ?.let { ReportStatus.fromWire(it) }
    return ReportSyncEvent(name = name, reportId = reportId, status = status)
}

/**
 * Lenient production guard. The web handler does not filter by project —
 * its loads are project-scoped anyway — so only a frame that explicitly
 * names ANOTHER project is dropped here; anything else passes.
 */
internal fun JsonElement?.matchesProject(here: String?): Boolean {
    if (here.isNullOrBlank()) return true
    val obj = this as? JsonObject ?: return true
    val frame = obj["data"] as? JsonObject ?: obj
    val incoming = listOf(obj, frame)
        .firstNotNullOfOrNull { candidate -> candidate["project_id"] ?: candidate["projectId"] }
        ?.let { value -> (value as? JsonPrimitive)?.content }
        ?.takeIf { it.isNotBlank() }
        ?: return true
    return incoming == here
}
