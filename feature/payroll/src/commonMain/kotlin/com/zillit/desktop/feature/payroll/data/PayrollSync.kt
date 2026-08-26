package com.zillit.desktop.feature.payroll.data

import com.zillit.desktop.core.socket.SocketEventName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The wire events that change what payroll's grid shows — the five timecard
 * lifecycle names whose handlers bump `ah:payroll:list` on the web
 * (`accountHubListeners.js:1677-1830`, attached to the shared socket via
 * `listenerSocket.js:2658-2661`), answered by every payroll surface with a
 * plain reload (`PayrollRunModule.jsx:5189`, `AccountantPayrollModule.jsx:1085`,
 * `PayrollGridModule.jsx:484`, `ProducerBoardModule.jsx:168`).
 *
 * A final approval unlocks the week for payroll; lock, paid, unpaid and posted
 * are payroll's own states landing from another client. The per-crew
 * `ah:payroll:outstanding:<userId>` key is not ported: the week reload carries
 * the same rows this port's outstanding pane derives from.
 */
val PAYROLL_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("timecard:final_approved"),
    SocketEventName("timecard:locked"),
    SocketEventName("timecard:marked_paid"),
    SocketEventName("timecard:marked_unpaid"),
    SocketEventName("timecard:posted"),
)

/**
 * The slice of the account-hub envelope this port reads: which production the
 * frame is about. The web drops cross-project frames before any handler runs
 * (`accountHubListeners.js:14-33, 2060-2072`); the rest of the payload is
 * ignored because the port refetches rather than patching rows.
 */
@Serializable
internal data class PayrollSyncEnvelope(
    @SerialName("project_id") val projectId: String? = null,
) {
    /** A frame that names another production is not ours; unnamed ones pass. */
    fun inProject(here: String?): Boolean =
        projectId == null || here == null || projectId == here
}
