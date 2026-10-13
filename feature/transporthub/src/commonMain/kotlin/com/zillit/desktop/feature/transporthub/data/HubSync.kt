package com.zillit.desktop.feature.transporthub.data

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.transporthub.domain.HubSyncKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The hub's wire events, each mapped to the load it invalidates.
 *
 * These are the v2 `transportation:*` streams the web's
 * `hooks/useHubSocketEvents.jsx` groups per page. The legacy tool
 * deliberately ignores all of them, and this tool deliberately takes them:
 * the two are separate surfaces on the same service.
 *
 * Two streams run in parallel during the server's own migration — the v2
 * `transportation:trip:*` names and the older `transportation:trip:request:*`
 * ones — and the server emits **both** for the same change. Listening to each
 * is intentional: the kinds collapse to one reload, and the ViewModel's
 * reloads are debounced, so a doubled frame costs nothing while a missed one
 * would leave a stale list.
 *
 * NOT here, because phase 1 has no page that would use them: the shuttle, the
 * maintenance, the geofence, the day- and trip-template, the talent and the
 * action-vehicle streams, and `driver:location:update` (the live map).
 */
val HUB_SYNC_KINDS: Map<SocketEventName, HubSyncKind> = buildMap {
    listOf(
        "transportation:vehicle:created",
        "transportation:vehicle:updated",
        "transportation:vehicle:deleted",
    ).forEach { put(SocketEventName(it), HubSyncKind.Fleet) }

    listOf(
        "transportation:driver:created",
        "transportation:driver:updated",
        "transportation:driver:deleted",
        "transportation:trip:driver:status:updated",
        "transportation:license:change:requested",
        "transportation:license:change:approved",
        "transportation:license:change:rejected",
    ).forEach { put(SocketEventName(it), HubSyncKind.Drivers) }

    listOf(
        "transportation:trip:created",
        "transportation:trip:updated",
        "transportation:trip:status:changed",
        "transportation:trip:assigned",
        "transportation:trip:transferred",
        "transportation:trip:deleted",
        "transportation:trip:bulk:status:updated",
        "transportation:trip:costs:updated",
        "transportation:trip:reminder:sent",
        "transportation:trip:passenger:added",
        "transportation:trip:passenger:updated",
        "transportation:trip:passenger:removed",
        "transportation:trip:passenger:status:changed",
    ).forEach { put(SocketEventName(it), HubSyncKind.Trips) }

    listOf(
        "transportation:ride:request:created",
        "transportation:ride:request:updated",
        "transportation:ride:request:edited",
        "transportation:ride:request:approved",
        "transportation:ride:request:assigned",
        "transportation:ride:request:rejected",
        "transportation:ride:request:cancelled",
        "transportation:ride:request:status:changed",
        "transportation:ride:request:call_driver",
        "transportation:ride:request:call_driver_for_actor",
        "transportation:ride:request:stop:added",
        "transportation:ride:request:stop:completed",
        "transportation:ride:request:passenger:added",
        "transportation:ride:request:passenger:removed",
        "transportation:ride:request:bulk:created",
        // The legacy stream: many of these fired for what are now ride
        // requests, and the server still emits them. A ride change moves a
        // row in the Trips list too, but Rides is the page that must be
        // right; Trips reloads on its own v2 names above.
        "transportation:trip:request:created",
        "transportation:trip:request:updated",
        "transportation:trip:request:assigned",
        "transportation:trip:request:completed",
        "transportation:trip:request:cancelled",
        "transportation:trip:request:inprogress",
    ).forEach { put(SocketEventName(it), HubSyncKind.Rides) }

    listOf(
        "transportation:permanent:trip:created",
        "transportation:permanent:trip:updated",
        "transportation:permanent:trip:deleted",
        "transportation:permanent:trip:completed",
        "transportation:permanent:trip:driver:swapped",
        "transportation:permanent:trip:vehicle:swapped",
        "transportation:permanent:trip:passengers:updated",
        "transportation:permanent:trip:activated",
        "transportation:permanent:trip:paused",
        "transportation:permanent:trip:reminder:sent",
        "transportation:permanent:trip:request:created",
        "transportation:permanent:trip:request:updated",
        "transportation:permanent:trip:request:assigned:as:driver",
        "transportation:permanent:trip:request:assigned:as:passenger",
    ).forEach { put(SocketEventName(it), HubSyncKind.Permanent) }
}

val HUB_SYNC_EVENTS: List<SocketEventName> = HUB_SYNC_KINDS.keys.toList()

/**
 * The web's project guard, kept lenient.
 *
 * A frame that names no production passes — dropping it would eat a refresh —
 * and only a frame naming *another* production is ignored.
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
