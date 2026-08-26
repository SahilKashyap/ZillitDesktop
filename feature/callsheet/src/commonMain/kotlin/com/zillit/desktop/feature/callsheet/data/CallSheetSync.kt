package com.zillit.desktop.feature.callsheet.data

import com.zillit.desktop.core.socket.SocketEventName
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The call-sheet WIRE events. These names are subscribed directly on the
 * socket — no re-emit alias in between — by the web module's own listener
 * (`call-sheet/socket/callSheetListeners.js:23-30`, registered via
 * `socket.on` at line 70); `CallSheetApp.jsx:1631-1694` answers each with
 * targeted list reloads (drafts / sent / received / finalized / published
 * by the status the event implies), patching a carried sheet in place
 * first. This client's equivalent is one reload of whatever list is on
 * screen — the ViewModel's refresh already scopes to the open destination
 * and bucket.
 */
val CALL_SHEET_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("callsheet:submit:for:approval"),
    SocketEventName("callsheet:approval:requested"),
    SocketEventName("callsheet:approval:approved"),
    SocketEventName("callsheet:approval:rejected"),
    SocketEventName("callsheet:approval:reminder:sent"),
    SocketEventName("callsheet:comment:created"),
)

/**
 * Lenient production guard. The web handler does not filter by project —
 * its loads are project-scoped anyway — so only a frame that explicitly
 * names ANOTHER project is dropped here; anything else passes.
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
