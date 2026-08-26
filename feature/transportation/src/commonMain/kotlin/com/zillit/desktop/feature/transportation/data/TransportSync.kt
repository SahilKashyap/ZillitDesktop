package com.zillit.desktop.feature.transportation.data

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.transportation.domain.TransportSyncKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The transport WIRE events this tool reacts to, each mapped to what it
 * touches. The `transportation:*` names ARE the wire names —
 * `listenerSocket.js:2019-2139` subscribes each and re-emits it under the
 * same string; the page and its modals consume them from there.
 *
 * Fleet — vehicles and the driver-flagged crew:
 *  - `vehicle:created/updated/deleted` (`Transportation.jsx:688-745`,
 *    `VehicleFlow.jsx:194-260`): splices or refetches vehicles AND users;
 *  - `driver:updated` (`Transportation.jsx:799`, `VehicleFlow.jsx:241`):
 *    splices the driver's documents / vehicle / phone into the users list;
 *  - `license:change:requested/approved/rejected`
 *    (`DocumentRequests.jsx:39-85`): approved/rejected refetch ALL users
 *    — the licence lands on the crew row; requested feeds a requests
 *    drawer this client folds into the same crew reload.
 *
 * Trips — the request lists:
 *  - `trip:request:created/updated/assigned` (`TripRecordsModal.jsx:
 *    138,159,182`): refetch the open records list;
 *  - `trip:request:completed/cancelled` (`Transportation.jsx:656,672`):
 *    the web only pokes badges — this client's status-bucketed list moves
 *    the row, so it reloads;
 *  - `trip:request:driver:assigned` (`MyAssignmentsModal.jsx:80`):
 *    refetches the driver's own assignments;
 *  - `trip:passenger:updated` (`TripDetailsModal.jsx:602`) and
 *    `trip:driver:status:updated` (`TripDetailsModal.jsx:576`): patch the
 *    open trip in place.
 *
 * Permanent — the allocations:
 *  - `permanent:trip:request:created/updated` (`PermanentRequests.jsx:
 *    45,58` refetch the requests; `Transportation.jsx:585-605` also
 *    refetches ALL users — ZL-13708, the assignment flags ride on them).
 *
 * NOT here: `driver:location:update` (live map pins — no map on this
 * client), `trip:request:pending:document:reminder` (badges plus the
 * driver's own reminder banner), the `transportation:trip:*` /
 * `ride:request:*` / shuttle / maintenance v2 streams (the transportation
 * HUB's pages, a different tool), and
 * `access-grid:posting-rights:update:sync` (the permission grid's family).
 */
val TRANSPORT_SYNC_KINDS: Map<SocketEventName, TransportSyncKind> = buildMap {
    listOf(
        "transportation:vehicle:created",
        "transportation:vehicle:updated",
        "transportation:vehicle:deleted",
        "transportation:driver:updated",
        "transportation:license:change:requested",
        "transportation:license:change:approved",
        "transportation:license:change:rejected",
    ).forEach { put(SocketEventName(it), TransportSyncKind.Fleet) }
    listOf(
        "transportation:trip:request:created",
        "transportation:trip:request:updated",
        "transportation:trip:request:assigned",
        "transportation:trip:request:completed",
        "transportation:trip:request:cancelled",
        "transportation:trip:request:driver:assigned",
        "transportation:trip:passenger:updated",
        "transportation:trip:driver:status:updated",
    ).forEach { put(SocketEventName(it), TransportSyncKind.Trips) }
    listOf(
        "transportation:permanent:trip:request:created",
        "transportation:permanent:trip:request:updated",
    ).forEach { put(SocketEventName(it), TransportSyncKind.Permanent) }
}

val TRANSPORT_SYNC_EVENTS: List<SocketEventName> = TRANSPORT_SYNC_KINDS.keys.toList()

/**
 * The web's guard, made lenient: every handler checks
 * `data?.project_id === localStorage.getItem('project_id')`. A frame that
 * names no project passes — dropping it would eat a refresh — and only a
 * frame that names ANOTHER project is ignored.
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
