package com.zillit.desktop.feature.chat.data

import com.zillit.desktop.feature.chat.domain.ChatScope
import com.zillit.desktop.feature.chat.domain.GroupRoom
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The create-group wire (QA#13), beside [ChatWire](ChatWire.kt) rather than
 * in it — that file already carries the whole socket protocol.
 */

/**
 * The `POST chat-room` create body — Android's `ReqGroupModel`
 * (`ReqGroupModel.kt:7-16`) exactly as `CreateGroupPage.onSubmitClick` fills
 * it for a CNC group (`CreateGroupPage.kt:393-400`): `owned_by` the creator,
 * `room_tool` `cnc_section` (`Constants.kt:1898`), the name, the member user
 * ids. The null-defaulted fields (`chat_room_id`, `department_id`,
 * `budget_document_id`, `is_random_call_group`) are omitted, as Android's
 * default `Json` omits defaults.
 */
fun createRoomBody(
    name: String,
    ownerId: String,
    memberIds: List<String>,
    /** The surface the room belongs to — C&C unless a tool says otherwise. */
    scope: ChatScope = ChatScope(),
): JsonObject =
    buildJsonObject {
        put("room_name", name)
        put("room_tool", scope.tool)
        put("owned_by", ownerId)
        // Both are declared on `ReqGroupModel` and both are omitted when
        // empty, as Android's default `Json` omits its defaults — a budget
        // room names its department, a C&C room has none to name.
        if (scope.departmentId.isNotBlank()) put("department_id", scope.departmentId)
        if (scope.budgetDocumentId.isNotBlank()) put("budget_document_id", scope.budgetDocumentId)
        put("members", buildJsonArray { memberIds.forEach { add(JsonPrimitive(it)) } })
    }

/**
 * The created room out of the create answer's `data` — `{chat_room:{…}}`
 * (Android `GetSingleRoomDetail.data.chat_room`, `GetRoomsModel.kt:73-83`),
 * read tolerantly in case a server hands the row back bare.
 */
fun createdRoomFrom(data: JsonElement?): GroupRoom? {
    val obj = data as? JsonObject ?: return null
    return roomFrom((obj["chat_room"] as? JsonObject) ?: obj)
}

/** One `chat_rooms` row as the domain sees it; null without an `_id`. */
internal fun roomFrom(room: JsonObject): GroupRoom? {
    val id = room.text("_id") ?: return null
    return GroupRoom(
        id = id,
        name = room.text("room_name") ?: "Group",
        ownedBy = room.text("owned_by"),
        departmentId = room.text("department_id")?.takeIf { it.isNotBlank() },
        sortingActivity = (room["sorting_activity"] as? JsonPrimitive)
            ?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 0L,
    )
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
