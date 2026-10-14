package com.zillit.desktop.feature.settings.approvals

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.core.socket.SocketMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Live updates for the two approval queues.
 *
 * The same six events AdminSync routes to the Pre-Approved and Crew pages,
 * with names confirmed against the web and iOS clients — both listen for all
 * six. This page listened for none of them, so a join request or a profile
 * change arriving while an admin had it open never appeared, and one decided
 * by a second admin stayed on screen to be decided again.
 */
val APPROVAL_SYNC_QUEUES: Map<SocketEventName, ApprovalQueue> = buildMap {
    listOf(
        "project:user:join:request:received",
        "project:user:join:request:accepted",
        "project:user:join:request:rejected",
    ).forEach { put(SocketEventName(it), ApprovalQueue.NewCrew) }
    listOf(
        "project:user:profile:change:requested",
        "project:user:profile:change:accepted",
        "project:user:profile:change:rejected",
    ).forEach { put(SocketEventName(it), ApprovalQueue.ProfileChanges) }
}

val APPROVAL_SYNC_EVENTS: List<SocketEventName> = APPROVAL_SYNC_QUEUES.keys.toList()

/**
 * The ledger unit each queue's notifications are filed under — the web's
 * `BADGE_CONSTANTS`, and the units iOS reads on a decision
 * (`ProjectObserver.swift:4289`, `:4518`).
 */
val ApprovalQueue.badgeUnit: String
    get() = when (this) {
        ApprovalQueue.NewCrew -> "project_join_user_request_label"
        ApprovalQueue.ProfileChanges -> "project_user_profile_change_request_label"
    }

/** Who is looking, as far as a decision made elsewhere needs to know. */
data class ApprovalViewer(
    val projectId: String? = null,
    /** This device's server id — what `approved_by_device_id` names when the decision was made here. */
    val deviceId: String? = null,
    val isAdmin: Boolean = false,
)

/**
 * One request decided — accepted or rejected — as its socket frame tells it:
 * whose request, on which production, and from which device.
 */
data class ApprovalDecided(
    val queue: ApprovalQueue,
    val userId: String,
    val projectId: String?,
    val byDeviceId: String?,
) {
    /**
     * Whether this decision's notification is the viewer's to clear — iOS's
     * three checks (`ProjectObserver.swift:4283-4309`, `4512-4537`): this
     * production, an admin (nobody else is notified of requests), and another
     * device. A decision made here was already read by the decide path.
     *
     * One deliberate difference: iOS skips the read when the frame names no
     * device, which leaves the row unread if another admin decided it. A
     * repeated read is harmless — the ledger flip and the server's read are
     * both idempotent — so an unnamed device is read rather than left lit.
     */
    fun clearsFor(viewer: ApprovalViewer): Boolean {
        val here = viewer.projectId != null && projectId == viewer.projectId
        val elsewhere = byDeviceId == null || byDeviceId != viewer.deviceId
        return viewer.isAdmin && here && elsewhere
    }
}

/** The four frames that mean a request left its queue; the other two mean one arrived. */
private val DECIDED_EVENTS: Set<String> = setOf(
    "project:user:join:request:accepted",
    "project:user:join:request:rejected",
    "project:user:profile:change:accepted",
    "project:user:profile:change:rejected",
)

/**
 * The decision a frame carries, or null for an arrival or a frame naming
 * nobody. The fields sit at the top of the frame, where iOS reads them
 * (`data.first["user_id"]` and friends); a `data` wrapper is looked into too,
 * because the server does not wrap its frames consistently.
 */
fun approvalDecided(message: SocketMessage): ApprovalDecided? {
    val queue = APPROVAL_SYNC_QUEUES[message.event]?.takeIf { message.event.value in DECIDED_EVENTS }
    val frame = message.payload as? JsonObject
    if (queue == null || frame == null) return null
    val body = (frame["data"] as? JsonObject)?.takeIf { "user_id" !in frame } ?: frame
    val userId = body.text("user_id") ?: return null
    return ApprovalDecided(
        queue = queue,
        userId = userId,
        projectId = body.text("project_id"),
        byDeviceId = body.text("approved_by_device_id"),
    )
}

private fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
