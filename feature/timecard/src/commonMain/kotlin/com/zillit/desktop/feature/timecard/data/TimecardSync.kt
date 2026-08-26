package com.zillit.desktop.feature.timecard.data

import com.zillit.desktop.core.socket.SocketEventName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The timecard lifecycle events — the wire names the web's handler map
 * subscribes on the shared socket (`accountHubListeners.js:1654-1835`,
 * attached via `listenerSocket.js:2658-2661`) and bridges to the
 * `ah:timecard:{my,approval,review}` refetch keys the pages answer with a
 * plain reload (`MyTimecardsModule.jsx:1869`, `ApproveTimeCardsPage.jsx:485`,
 * `CrewReviewModule.jsx:334`, `AssignedTimecardsModule.jsx:1115`).
 *
 * Not subscribed: the six `timecard:dispute:*` events (this port has no
 * disputes surface), `timecard_notifications_read` (a badge-clearing ghost
 * frame, `accountHubListeners.js:1703`), and `timecard:settings:updated`
 * (announces the config screen, which this port does not show).
 */
val TIMECARD_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("timecard:received_for_approval"),
    SocketEventName("timecard:approved"),
    SocketEventName("timecard:final_approved"),
    SocketEventName("timecard:rejected"),
    SocketEventName("timecard:assigned_to_completer"),
    SocketEventName("timecard:received_for_review"),
    SocketEventName("timecard:approved_after_review"),
    SocketEventName("timecard:returned_by_owner"),
    SocketEventName("timecard:locked"),
    SocketEventName("timecard:marked_paid"),
    SocketEventName("timecard:marked_unpaid"),
    SocketEventName("timecard:posted"),
    SocketEventName("timecard:claim_added"),
)

/**
 * The slice of the account-hub envelope this port reads: which production the
 * frame is about. The web drops cross-project frames before any handler runs
 * (`accountHubListeners.js:14-33, 2060-2072`); the rest of the payload is
 * ignored because the port refetches rather than patching rows.
 */
@Serializable
internal data class TimecardSyncEnvelope(
    @SerialName("project_id") val projectId: String? = null,
) {
    /** A frame that names another production is not ours; unnamed ones pass. */
    fun inProject(here: String?): Boolean =
        projectId == null || here == null || projectId == here
}
