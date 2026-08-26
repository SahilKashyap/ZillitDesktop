package com.zillit.desktop.feature.permissiongrid.data

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.permissiongrid.domain.RightsSync
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The backend's three grid-sync events (ZL-17812). One handler serves all
 * three on the web (`AccessGrid.jsx`'s `handleAccessGridSync`), and the same
 * is true here — each carries the full row payload for one subject and tool.
 */
val RIGHTS_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("access-grid:viewing-rights:update:sync"),
    SocketEventName("access-grid:posting-rights:update:sync"),
    SocketEventName("access-grid:download-rights:update:sync"),
)

/**
 * The event's payload. Every flag nullable: the wire sends only what changed,
 * and a missing key must keep the cell's current value rather than clear it —
 * the web's `pick` reads `typeof data[key] === 'boolean'` for the same reason.
 * The lock gates are camelCase beside snake_case siblings, as everywhere on
 * this tool's wire.
 */
@Serializable
internal data class RightsSyncDto(
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("unit_name") val unitName: String? = null,
    @SerialName("view_access") val view: Boolean? = null,
    @SerialName("posting_access") val post: Boolean? = null,
    @SerialName("download_access") val download: Boolean? = null,
    @SerialName("viewingUpdatable") val viewUnlocked: Boolean? = null,
    @SerialName("postingUpdatable") val postUnlocked: Boolean? = null,
    @SerialName("downloadUpdatable") val downloadUnlocked: Boolean? = null,
) {
    /** Null when the event cannot name a row and a tool — nothing to apply. */
    fun toDomain(): RightsSync? {
        val user = userId?.takeIf { it.isNotBlank() } ?: return null
        val unit = unitName?.takeIf { it.isNotBlank() } ?: return null
        return RightsSync(
            projectId = projectId,
            userId = user,
            unitName = unit,
            view = view,
            post = post,
            download = download,
            viewUnlocked = viewUnlocked,
            postUnlocked = postUnlocked,
            downloadUnlocked = downloadUnlocked,
        )
    }
}
