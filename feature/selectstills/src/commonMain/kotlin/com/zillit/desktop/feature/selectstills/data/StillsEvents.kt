package com.zillit.desktop.feature.selectstills.data

import com.zillit.desktop.core.socket.SocketEventName
import kotlinx.serialization.json.JsonElement

/**
 * Live updates for Select Stills, emitted by `zillit_selectstills` through the
 * chat service and bridged RAW (the same name in and out), so a subscriber can
 * gate on `project_id`.
 *
 * A frame carries ids only, never a photo:
 *   still_kills:photos:changed    { project_id, photo_ids: [...] }   (at most 200 a frame)
 *   still_kills:members:changed   { project_id, member_ids: [...] }
 *   still_kills:settings:changed  { project_id }
 *
 * The screen then asks the service for those ids, and the service decides again
 * what this reader may see — so a frame gives nothing away by itself.
 */
object StillsEvents {
    val Photos = SocketEventName("still_kills:photos:changed")
    val Members = SocketEventName("still_kills:members:changed")
    val Settings = SocketEventName("still_kills:settings:changed")
}

val stillsSyncEvents: List<SocketEventName> = listOf(StillsEvents.Photos, StillsEvents.Members, StillsEvents.Settings)

/** The ids a frame named, or empty for one that carries none. */
internal fun JsonElement?.frameIds(key: String): List<String> = obj().strings(key)

/** Whether a frame is for the production that is open. */
internal fun JsonElement?.isForProject(projectId: String?): Boolean {
    val named = obj().strOrNull("project_id") ?: return true
    if (projectId.isNullOrBlank()) return true
    return named == projectId
}
