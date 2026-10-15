package com.zillit.desktop.feature.crewdood.data

import com.zillit.desktop.core.socket.SocketEventName
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The deal events that move a bar on this grid.
 *
 * The web has no Crew DOOD socket of its own: the app-wide `deal:*` listeners
 * dispatch one `ah:deal_memo:list`, and the page refetches its deals in the
 * background. These are the same events Deal Memo listens to, minus the ones
 * that cannot change a booking window — templates and approval tiers.
 *
 * **Still not live** (handoff §5.5): a calendar save, a Production Calendar
 * edit and a crew-list change have no event names yet, so another viewer sees
 * those on reload.
 */
internal val DOOD_DEAL_EVENTS: Set<SocketEventName> = setOf(
    "deal:created",
    "deal:submitted",
    "deal:tier-approved",
    "deal:approved",
    "deal:rejected",
    "deal:activated",
    "deal:completed",
    "deal:cancelled",
    "deal:deactivate_scheduled",
    "deal:updated",
    "deal:approval_reset",
    "deal:deleted",
).map(::SocketEventName).toSet()

/**
 * The web's `sameProject` guard, kept as lenient as every other tool's: a
 * frame that names no project passes, and both spellings of the key are read.
 */
internal fun JsonElement?.matchesProject(here: String?): Boolean {
    if (here.isNullOrBlank()) return true
    val incoming = (this as? JsonObject)
        ?.let { frame -> frame["projectId"] ?: frame["project_id"] }
        ?.let { value -> (value as? JsonPrimitive)?.content }
        ?.takeIf { it.isNotBlank() }
        ?: return true
    return incoming == here
}
