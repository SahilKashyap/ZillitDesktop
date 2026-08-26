package com.zillit.desktop.feature.costreport.data

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.costreport.domain.CostReportSync
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The report's own two lifecycle events — the only wire names the web's
 * cost-report handler map subscribes (`accountHubListeners.js:1905-1911`,
 * attached to the shared socket via `listenerSocket.js:2658-2661`). Both mean
 * the figures and lock state were recomputed server-side, and both are
 * answered with a silent re-pull (`CostReportWorksheetModule.jsx:5601-5603`).
 */
val CR_REPORT_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("cost_report:locked"),
    SocketEventName("cost_report:posted"),
)

/**
 * Feeder-tool events that make the worksheet's actuals/committed columns
 * stale — the web's `COST_REPORT_SOURCE_EVENTS` set
 * (`accountHubListeners.js:2005-2013`), fanned into a staleness signal by the
 * registration wrapper (`accountHubListeners.js:2087-2091`). These flip the
 * Refresh pill rather than reloading — see `CostReportSync.Source`.
 */
val CR_SOURCE_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("po:fully_approved"),
    SocketEventName("po:posted"),
    SocketEventName("invoice:approved"),
    SocketEventName("invoice:posted_to_ledger"),
    SocketEventName("card:receipt:approved"),
    SocketEventName("card:receipt:posted"),
    SocketEventName("batch:posted"),
    SocketEventName("cash:batch:approved"),
    SocketEventName("cash:batch:posted"),
    SocketEventName("timecard:approved"),
    SocketEventName("timecard:final_approved"),
    SocketEventName("timecard:posted"),
)

val CR_SYNC_EVENTS: List<SocketEventName> = CR_REPORT_SYNC_EVENTS + CR_SOURCE_SYNC_EVENTS

/** Which meaning [event] carries — see [CostReportSync]. */
internal fun costReportSyncFor(event: SocketEventName): CostReportSync =
    if (event in CR_REPORT_SYNC_EVENTS) CostReportSync.Report else CostReportSync.Source

/**
 * The slice of the account-hub envelope this port reads: which production the
 * frame is about. The web drops cross-project frames before any handler runs
 * (`accountHubListeners.js:14-33, 2060-2072`); the rest of the payload is
 * ignored because this port refetches (or flags) rather than patching cells.
 */
@Serializable
internal data class CrSyncEnvelope(
    @SerialName("project_id") val projectId: String? = null,
) {
    /** A frame that names another production is not ours; unnamed ones pass. */
    fun inProject(here: String?): Boolean =
        projectId == null || here == null || projectId == here
}
