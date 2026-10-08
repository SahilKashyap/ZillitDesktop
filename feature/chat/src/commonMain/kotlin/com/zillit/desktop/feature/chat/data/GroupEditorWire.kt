package com.zillit.desktop.feature.chat.data

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.ChatScope
import com.zillit.desktop.feature.chat.domain.GroupDetail
import com.zillit.desktop.feature.chat.domain.GroupMember
import com.zillit.desktop.feature.chat.domain.GroupRoom
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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

/**
 * The `POST chat-room` body that edits an existing room — the web's
 * `editRoomUsers` payload (`InfoSiderGroup.jsx` `handleSaveName`): the same
 * create body, plus the room's own `chat_room_id`, which turns the write into
 * an update. `members` is the whole roster, not a delta.
 */
fun editRoomBody(room: GroupDetail, name: String): JsonObject =
    buildJsonObject {
        put("room_name", name)
        put("is_random_call_group", false)
        room.ownedBy?.let { put("owned_by", it) }
        put("members", buildJsonArray { room.members.forEach { add(JsonPrimitive(it.userId)) } })
        put("chat_room_id", room.id)
    }

/**
 * The `PUT chat-room/update-group-picture/{id}` body — the five storage keys
 * Android's `AttachmentModel` and the web both send. An empty picture clears
 * it (the web's "remove photo").
 */
fun roomPictureBody(picture: ChatAttachment?): JsonObject =
    buildJsonObject {
        put("thumbnail", picture?.thumbnail?.ifBlank { picture.media }.orEmpty())
        put("name", picture?.name.orEmpty())
        put("media", picture?.media.orEmpty())
        put("bucket", picture?.bucket.orEmpty())
        put("region", picture?.region.orEmpty())
    }

/**
 * `GET chat-room/{id}`'s `data` — `{chat_room:{…, members:[…]}}`
 * (`GetSingleRoomDetail`) — read tolerantly. A member the server marks
 * `enabled:false` has left and is not listed.
 */
fun roomDetailFrom(data: JsonElement?): GroupDetail? {
    val obj = data as? JsonObject ?: return null
    val room = (obj["chat_room"] as? JsonObject) ?: obj
    val id = room.text("_id") ?: return null
    val members = (room["members"] as? kotlinx.serialization.json.JsonArray).orEmpty()
        .mapNotNull { it as? JsonObject }
        .filter { (it["enabled"] as? JsonPrimitive)?.booleanOrNull != false }
        .mapNotNull { row ->
            row.text("user_id")?.let { userId ->
                GroupMember(userId, isAdmin = (row["chat_group_admin"] as? JsonPrimitive)?.booleanOrNull == true)
            }
        }
    return GroupDetail(
        id = id,
        name = room.text("room_name") ?: str(S.group),
        ownedBy = room.text("owned_by"),
        isSystemDefined = (room["is_system_defined"] as? JsonPrimitive)?.booleanOrNull == true,
        picture = (room["group_picture"] as? JsonObject)?.let(::pictureFrom),
        createdMillis = (room["created"] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() },
        members = members.distinctBy(GroupMember::userId),
    )
}

/** A `group_picture` object; null when it names no stored file (a cleared picture is `{}` or blanks). */
private fun pictureFrom(row: JsonObject): ChatAttachment? {
    val media = row.text("media") ?: return null
    return ChatAttachment(
        media = media,
        name = row.text("name").orEmpty(),
        contentType = "image/jpeg",
        bucket = row.text("bucket").orEmpty(),
        region = row.text("region").orEmpty(),
        thumbnail = row.text("thumbnail").orEmpty(),
    )
}

/** One `chat_rooms` row as the domain sees it; null without an `_id`. */
internal fun roomFrom(room: JsonObject): GroupRoom? {
    val id = room.text("_id") ?: return null
    return GroupRoom(
        id = id,
        name = room.text("room_name") ?: str(S.group),
        ownedBy = room.text("owned_by"),
        departmentId = room.text("department_id")?.takeIf { it.isNotBlank() },
        sortingActivity = (room["sorting_activity"] as? JsonPrimitive)
            ?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 0L,
    )
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
