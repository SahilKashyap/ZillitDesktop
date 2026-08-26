package com.zillit.desktop.feature.crewlist.data

import com.zillit.desktop.core.socket.SocketEventName
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The one WIRE event the web crew-list page reacts to. On the wire it is
 * `department:reordered` (`listenerSocket.js:1920-1922` subscribes it and
 * re-emits internally as `department_reordered`, which
 * `NewCrewList.jsx:244` and `CrewListCustom.jsx:873` consume); the handler
 * refetches the roster so the new department order lands.
 *
 * The web gates the refetch on `isAdmin`; this client reloads for every
 * viewer — the roster's order is wrong for everyone, and the reload is one
 * GET. The `project_user_*` and `department:create/update/delete` families
 * are NOT here: the crew-list page does not listen to them (they feed the
 * header, badges, and admin-settings pages).
 */
val CREW_LIST_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("department:reordered"),
)

/**
 * The web's guard, made lenient: the handler checks
 * `data?.project_id === getCurrentProjectId()`. A frame that names no
 * project passes — dropping it would eat a refresh — and only a frame
 * that names ANOTHER project is ignored.
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
