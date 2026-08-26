package com.zillit.desktop.feature.dealmemo.data

import com.zillit.desktop.core.socket.SocketEventName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The deal-memo hub's lifecycle events — every wire name the web's handler map
 * subscribes on the shared socket (`accountHubListeners.js:1495-1620`,
 * attached via `listenerSocket.js:2658-2661`) and bridges to the `ah:deal_memo:*`
 * refetch keys the pages answer with a plain reload (`DMDealsPage.jsx:305`,
 * `DMMyDealPage.jsx:71`, `DMApprovalQueuePage.jsx:245`, `DMOverviewPage.jsx:120`).
 *
 * The legacy deal-memo surfaces' events — `dealmemo:uploadnda` /
 * `dealmemo:deletenda` (`listenerSocket.js:1988-1994`) and the e-signature
 * stream's `document:counter:signed` (`listenerSocket.js:2374`) — are
 * deliberately absent: they announce the settings-page NDA lists and the
 * user-side contract-status page, neither of which this tool shows, so a
 * subscription would only reload the deals list over something it cannot
 * display.
 */
val DEAL_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("deal:created"),
    SocketEventName("deal:submitted"),
    SocketEventName("deal:tier-approved"),
    SocketEventName("deal:approved"),
    SocketEventName("deal:rejected"),
    SocketEventName("deal:activated"),
    SocketEventName("deal:completed"),
    SocketEventName("deal:cancelled"),
    SocketEventName("deal:deactivate_scheduled"),
    SocketEventName("deal:updated"),
    SocketEventName("deal:approval_reset"),
    SocketEventName("deal:deleted"),
)

/**
 * The slice of the account-hub envelope this port reads: which production the
 * frame is about. The web drops cross-project frames before any handler runs
 * (`accountHubListeners.js:14-33, 2060-2072`); everything else in the payload
 * is ignored because the port refetches rather than patching rows.
 */
@Serializable
internal data class DealSyncEnvelope(
    @SerialName("project_id") val projectId: String? = null,
) {
    /** A frame that names another production is not ours; unnamed ones pass. */
    fun inProject(here: String?): Boolean =
        projectId == null || here == null || projectId == here
}
