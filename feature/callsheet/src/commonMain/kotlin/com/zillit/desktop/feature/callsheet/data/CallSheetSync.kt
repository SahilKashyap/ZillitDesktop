package com.zillit.desktop.feature.callsheet.data

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.callsheet.domain.CallSheetStatus
import com.zillit.desktop.feature.callsheet.domain.SheetSyncEvent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The call-sheet WIRE events. These names are subscribed directly on the
 * socket — no re-emit alias in between — by the web module's own listener
 * (`call-sheet/socket/callSheetListeners.js:22-29`) and, for the comment
 * thread, by `CommentsModal.jsx:187-189`. `CallSheetApp.jsx:1873-1921` answers
 * the workflow events with targeted list reloads.
 */
val CALL_SHEET_SYNC_EVENTS: List<SocketEventName> = listOf(
    "callsheet:submit:for:approval",
    "callsheet:approval:requested",
    "callsheet:approval:approved",
    "callsheet:approval:rejected",
    "callsheet:approval:reminder:sent",
    "callsheet:comment:created",
    "callsheet:comment:updated",
    "callsheet:comment:deleted",
).map(::SocketEventName)

/**
 * What a frame names. Payloads arrive wrapped in `{data, message}` or bare,
 * with the sheet nested or flat, and in either key spelling. Comment frames
 * name their sheet as `callsheet_id` (the web filters on `callsheetId`).
 */
internal fun syncEventOf(name: String, payload: JsonElement?): SheetSyncEvent {
    val outer = payload as? JsonObject
    val frame = (outer?.get("data") as? JsonObject)
        ?.takeIf { outer.containsKey("message") || outer.containsKey("messageElements") } ?: outer
    val sheet = frame?.firstOf("call_sheet", "callSheet") as? JsonObject
    val commentObj = frame?.firstOf("comment") as? JsonObject
    val sheetId = listOfNotNull(
        sheet?.text("_id", "id"),
        frame?.text("callsheet_id", "callsheetId", "call_sheet_id", "callSheetId"),
        commentObj?.text("callsheet_id", "callsheetId", "call_sheet_id", "callSheetId"),
        frame?.text("_id", "id")?.takeIf { sheet == null && commentObj == null && !name.contains(":comment:") },
    ).firstOrNull { it.isNotBlank() }
    val status = (sheet?.text("status")?.takeIf { it.isNotBlank() } ?: frame?.text("status"))
        ?.takeIf { it.isNotBlank() }
        ?.let { CallSheetStatus.fromWire(it) }
    val isComment = name.startsWith("callsheet:comment:")
    return SheetSyncEvent(
        name = name,
        sheetId = sheetId,
        status = status,
        comment = if (isComment) SheetWire.comment(commentObj) else null,
        commentId = if (isComment) {
            frame?.text("comment_id", "commentId")?.takeIf { it.isNotBlank() } ?: commentObj?.text("_id", "id")
        } else {
            null
        },
    )
}

/**
 * Lenient production guard. The web handler does not filter by project —
 * its loads are project-scoped anyway — so only a frame that explicitly
 * names ANOTHER project is dropped here; anything else passes.
 */
internal fun JsonElement?.matchesProject(here: String?): Boolean {
    if (here.isNullOrBlank()) return true
    val obj = this as? JsonObject ?: return true
    val frame = obj["data"] as? JsonObject ?: obj
    val incoming = listOf(obj, frame)
        .firstNotNullOfOrNull { candidate -> candidate["project_id"] ?: candidate["projectId"] }
        ?.let { value -> (value as? JsonPrimitive)?.content }
        ?.takeIf { it.isNotBlank() }
        ?: return true
    return incoming == here
}
