package com.zillit.desktop.feature.chat.data

import com.zillit.desktop.core.common.ZillitLog
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
import kotlin.time.Instant

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
fun editRoomBody(
    room: GroupDetail,
    name: String,
    memberIds: List<String> = room.members.map { it.userId },
): JsonObject =
    buildJsonObject {
        put("room_name", name)
        put("is_random_call_group", false)
        room.ownedBy?.let { put("owned_by", it) }
        put("members", buildJsonArray { memberIds.forEach { add(JsonPrimitive(it)) } })
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
        sortingActivity = room.roomStamp(id),
    )
}

/**
 * When this room last spoke.
 *
 * **`chat-room` does not send `sorting_activity`.** Android reads that field
 * off its own rooms payload and the port followed it, but the desktop's rows
 * simply have no such key — read live on prod, 2026-10-14: every row answered
 * `sorting_activity=null`, and what the server does send is
 * `last_message_posted_on` (the same field the web's `fetchChatListData`
 * sorts by). A stamp of 0 is not cosmetic: [hasStanding] hides a room with no
 * stamp and no department from the All chip entirely, which is how two of
 * DN6's three rooms became invisible while the Groups chip still listed them.
 *
 * Both spellings are read, newest wins, and each is taken as epoch millis, a
 * numeric string, or an ISO-8601 instant — the three shapes this backend uses
 * for a time across its tools, and guessing wrong here silently returns to 0.
 * `created` is deliberately NOT a fallback: a room's birthday is not activity,
 * and using it would promote an empty room above a live conversation.
 */
private fun JsonObject.roomStamp(id: String): Long {
    val stamp = maxOf(instantMillis("last_message_posted_on"), instantMillis("sorting_activity"))
    if (stamp <= 0L) {
        // The room will sort last, and will be missing from All unless it has
        // a department. Ids and keys only — a room row carries no message text.
        ZillitLog.d(TAG) {
            "room $id has no activity stamp: last_message_posted_on=${this["last_message_posted_on"]} " +
                "sorting_activity=${this["sorting_activity"]} department_id=${this["department_id"]}"
        }
    }
    return stamp
}

/** One time field, as millis, a numeric string, or an ISO-8601 instant; 0 when it is none of them. */
private fun JsonObject.instantMillis(key: String): Long {
    val primitive = this[key] as? JsonPrimitive ?: return 0L
    val text = primitive.contentOrNull?.takeIf { it.isNotBlank() }
    val millis = primitive.longOrNull
        ?: text?.toLongOrNull()
        ?: text?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
    return (millis ?: 0L).coerceAtLeast(0L)
}

private const val TAG = "Chat"

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
