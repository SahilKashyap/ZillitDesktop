package com.zillit.desktop.feature.auth.data

import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketEventName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The open production, changed or removed by somebody else.
 *
 * Both phones carry all four and both gate on the id: iOS compares
 * `dataDict["project_id"]` with the signed-in production before doing
 * anything (`ProjectObserver.swift:5114`), and Android re-reads its project
 * list on update or delete. The desktop listened for none of them, so a
 * production renamed elsewhere kept its old name here, and one deleted under
 * the user stayed open until the next request failed.
 *
 * Android's own in-production handler answers *any* `project:deleted` with a
 * 403 without reading the id (`BottomNavigationActivity.kt:772`). That is not
 * copied — a production deleted in another company would throw this user out
 * of the one they are working in.
 */
val PROJECT_LIFECYCLE_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("project:deleted"),
    SocketEventName("project:marked:for:deletion"),
    SocketEventName("project:unmarked:for:deletion"),
    SocketEventName("project:update"),
)

/** What a project lifecycle frame means to the shell. */
enum class ProjectLifecycle {
    /**
     * The open production no longer exists. Nothing here can be saved into it
     * any more, so the shell goes back to the picker.
     */
    Deleted,

    /**
     * The production's own record moved — renamed, marked for deletion, or
     * unmarked. The name in the shell and the rights on it are both read from
     * that record, so the whole context is re-read rather than patched.
     */
    Changed,
}

/**
 * Lifecycle frames for [openProject], as something the shell can act on.
 *
 * A frame naming a different production is dropped, and so is one naming no
 * production at all — this decides whether the user keeps their workspace,
 * and a nameless frame is not evidence about theirs.
 */
fun projectLifecycle(
    events: SocketEventBus,
    openProject: () -> String?,
): Flow<ProjectLifecycle> =
    events.onAny(PROJECT_LIFECYCLE_EVENTS).mapNotNull { message ->
        val open = openProject() ?: return@mapNotNull null
        val named = message.payload?.let(::projectIdOf) ?: return@mapNotNull null
        if (named != open) {
            null
        } else if (message.event == PROJECT_LIFECYCLE_EVENTS.first()) {
            ProjectLifecycle.Deleted
        } else {
            ProjectLifecycle.Changed
        }
    }

/** The `project_id` a lifecycle frame names — top level or under `detail`. */
fun projectIdOf(payload: JsonElement): String? {
    val obj = payload as? JsonObject ?: return null
    val detail = (obj["detail"] as? JsonObject) ?: obj
    val id = (detail["project_id"] as? JsonPrimitive)?.contentOrNull
        ?: (detail["projectId"] as? JsonPrimitive)?.contentOrNull
    return id?.takeIf { it.isNotBlank() }
}
