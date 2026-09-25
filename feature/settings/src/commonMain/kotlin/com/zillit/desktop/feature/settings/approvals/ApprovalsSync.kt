package com.zillit.desktop.feature.settings.approvals

import com.zillit.desktop.core.socket.SocketEventName

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
